# AeroTrace Project API Key Lifecycle Runbook

> 마지막 업데이트: 2026-09-16
> 상태: Operator 발급·조회·교체·폐기 구현 및 ephemeral acceptance 완료
> 범위: Collector workload API Key와 아직 배포 중인 legacy Frontend Key. Phase C Frontend는 session-only이며 Key를 사용하지 않음

## 1. 목적과 경계

**먼저 배포 revision을 확인한다.** 현재 repository Phase C Frontend에는 API Key를 주입하지 않는다. 기존 Production image가 legacy일 때만 아래의 두 client 갱신 절차를 사용한다. Phase C가 실제 배포된 뒤에는 Collector와 별도 legacy API Key client만 rotation 대상이며 로그인 UI 조회 성공을 API Key 검증으로 세지 않는다.

Project API Key 원문은 발급 순간에만 확인할 수 있고 DB에는 SHA-256 hash만 저장된다. 원문을 분실하면 복구할 수 없으므로 새 Key를 발급해야 하며, 노출된 Key는 폐기해야 한다.

이 runbook은 관리 UI가 없는 현재 self-hosted 단계에서 운영자가 다음 작업을 안전하게 수행하는 절차다.

```text
metadata 조회
-> 기존 project에 replacement 발급
-> Collector와 Frontend에 새 원문 주입
-> ingest와 query 검증
-> 이전 Key 폐기
```

지원하지 않는 작업:

- 폐기 취소 또는 원문 복구
- Key row 물리 삭제
- 자동·예약 rotation
- 사용자 self-service onboarding
- Production credential 자동 변경

Codex는 운영 DB Key 발급·폐기, secret file 수정과 service 재시작을 자동 실행하지 않는다. 운영자가 대상과 maintenance 조건을 확인한 뒤 명시적으로 실행한다.

## 2. 상태와 보장

| 상태 | 판정 | 인증 결과 |
|---|---|---|
| `ACTIVE` | `revoked_at`이 없고 `expires_at`이 현재보다 뒤 | 허용 |
| `EXPIRED` | 폐기되지 않았지만 만료 시각 도달 | 거부 |
| `REVOKED` | `revoked_at` 존재 | 거부 |

폐기는 논리 폐기이며 audit metadata를 남긴다. 새 인증 요청부터 즉시 거부되지만 이미 처리 중인 요청을 취소하지는 않는다. 같은 project에는 rotation 동안 복수의 활성 Key가 존재할 수 있다.

안전 기본값:

- `issue`와 `revoke`는 project row lock으로 같은 project의 lifecycle 변경을 직렬화한다.
- `issue`는 tenant/project slug가 이미 존재해야 하며 오타로 새 tenant/project를 만들지 않는다.
- 같은 이름의 활성 Key 재발급은 거부한다.
- 마지막 활성 Key 폐기는 기본적으로 거부한다.
- 폐기에는 row UUID, 기존 이름과 `REVOKE` 확인 문자열이 모두 필요하다.
- 반복 폐기는 변경 없이 `ALREADY_REVOKED`를 반환한다.

## 3. 공통 준비

Java 21 host에서 Backend가 사용하는 DB에 연결한다. Password를 command argument나 shell history에 쓰지 않는다.

```bash
cd /home/huning/aerotrace/backend

export AEROTRACE_DB_URL='jdbc:postgresql://<database-host>:5432/aerotrace?reWriteBatchedInserts=true'
export AEROTRACE_DB_USERNAME='<database-user>'
read -rsp 'AeroTrace DB password: ' AEROTRACE_DB_PASSWORD
echo
export AEROTRACE_DB_PASSWORD

export AEROTRACE_API_KEY_TENANT_SLUG='<existing-tenant-slug>'
export AEROTRACE_API_KEY_PROJECT_SLUG='<existing-project-slug>'
```

운영 전에는 다음을 확인한다.

- DB host와 database가 의도한 환경인지 확인
- tenant/project slug를 기존 값과 대조
- Terminal recording과 shell debug tracing(`set -x`) 비활성화
- 원문을 issue, log, screenshot, clipboard history나 Git에 남기지 않음
- Collector와 Frontend를 모두 갱신할 권한과 복구 시간 확보

작업 종료 시 공통 환경변수를 지운다.

```bash
unset AEROTRACE_DB_URL AEROTRACE_DB_USERNAME AEROTRACE_DB_PASSWORD
unset AEROTRACE_API_KEY_TENANT_SLUG AEROTRACE_API_KEY_PROJECT_SLUG
```

## 4. Metadata 조회

조회는 Key 원문, lookup용 `key_id`와 `secret_hash`를 SQL로 읽거나 출력하지 않는다.

```bash
export AEROTRACE_API_KEY_ACTION='list'
bash ./gradlew manageProjectApiKeys --no-daemon
unset AEROTRACE_API_KEY_ACTION
```

주요 출력:

```text
AEROTRACE_API_KEY_LIST_RESULT=OK
AEROTRACE_API_KEY_COUNT=<all rows>
AEROTRACE_API_KEY_ACTIVE_COUNT=<currently active rows>
AEROTRACE_API_KEY_1_ID=<row UUID>
AEROTRACE_API_KEY_1_NAME_BASE64URL=<encoded operator label>
AEROTRACE_API_KEY_1_STATUS=ACTIVE|EXPIRED|REVOKED
AEROTRACE_API_KEY_1_CREATED_AT=<UTC instant>
AEROTRACE_API_KEY_1_EXPIRES_AT=<UTC instant>|NEVER
AEROTRACE_API_KEY_1_REVOKED_AT=<UTC instant>|NONE
```

이름은 control character를 통한 line injection을 막기 위해 UTF-8 Base64URL without padding으로 출력한다. 필요하면 metadata 값만 다음처럼 decode한다.

```bash
read -rp 'NAME_BASE64URL: ' AEROTRACE_NAME_BASE64URL
python3 -c 'import base64, sys; value=sys.argv[1]; print(base64.urlsafe_b64decode(value + "=" * (-len(value) % 4)).decode("utf-8"))' "$AEROTRACE_NAME_BASE64URL"
unset AEROTRACE_NAME_BASE64URL
```

Row UUID는 폐기 대상을 지정하는 metadata이지 `atr_` credential 내부의 lookup ID가 아니다.

## 5. 무중단 rotation

### 5.1 현재 상태 확인

먼저 목록을 실행해 이전 Key의 row UUID, 이름, 상태와 만료 시각을 확인한다. 이미 활성 Key가 2개 이상이면 각각의 사용처를 먼저 식별한다. 사용처를 모르는 Key를 추측으로 폐기하지 않는다.

### 5.2 기존 project에 replacement 발급

새 이름을 사용하고 서비스에 반영할 충분한 만료 기간을 지정한다.

```bash
export AEROTRACE_API_KEY_ACTION='issue'
read -rp 'Replacement API Key name: ' AEROTRACE_API_KEY_NAME
read -rp 'Expiration days (1-3650): ' AEROTRACE_API_KEY_EXPIRATION_DAYS
export AEROTRACE_API_KEY_NAME AEROTRACE_API_KEY_EXPIRATION_DAYS

bash ./gradlew manageProjectApiKeys --no-daemon

unset AEROTRACE_API_KEY_ACTION
unset AEROTRACE_API_KEY_NAME AEROTRACE_API_KEY_EXPIRATION_DAYS
```

성공 시 `AEROTRACE_API_KEY=atr_...`가 한 번 출력된다. 이 원문을 즉시 승인된 secret 저장 위치에 옮긴다. DB나 이후 목록 명령으로 다시 얻을 수 없다.

원문을 안전하게 저장하기 전에 잃어버렸다면 이전 Key를 폐기하지 않는다. 분실한 새 Key의 metadata를 폐기하고 다른 이름으로 다시 발급한다.

### 5.3 사용처 갱신 — 배포 revision별 분기

Phase C session-only Frontend 배포 후에는 `otel-collector.env` 등 실제 workload client의 secret만 교체하고 해당 Collector만 재생성한다. Frontend에는 Key를 넣거나 rotation 목적으로 재생성하지 않는다. Production overlay를 포함한 정확한 명령은 운영 승인 시 확정하며 아래 기본 개발 명령을 그대로 적용하지 않는다.

아래 두 사용처 예시는 **legacy Frontend image가 배포 중일 때만** 적용한다.

현재 runtime에서는 같은 새 원문을 다음 server-side secret에 넣는다.

```text
otel-collector.env  -> Collector가 Backend ingest에 사용
frontend.env        -> Next.js BFF가 Backend query에 사용
```

두 파일은 mode `0600`을 유지하고 public/client-side 환경변수에 넣지 않는다. Secret을 command argument에 포함하는 `sed`, `docker compose ... -e`나 직접 `curl -H` 예시는 사용하지 않는다.

Compose runtime은 secret 수정 후 Collector와 Frontend만 재생성한다. 실제 운영 적용은 operator action이며 대상 Compose overlay를 먼저 확인한다.

```bash
cd /home/huning/aerotrace
docker compose \
  -f docker-compose.yaml \
  -f docker-compose.app.yaml \
  --profile app \
  up -d --no-deps --force-recreate otel-collector frontend
```

이 단계에서는 이전 Key도 아직 활성 상태이므로 두 client를 순차 갱신할 수 있다.

### 5.4 새 Key 검증

Legacy Frontend만 아래 BFF query로 Key 경계를 확인한다. 기간은 예시이며 필요한 조회 범위로 바꾼다. 브라우저나 curl에는 원문 Key가 노출되지 않는다. Phase C에서는 이 Frontend route가 `404`인 것이 정상이며 Collector ingest와 승인된 legacy Backend Key client로 Key를 별도 검증한다.

```bash
curl --silent --show-error \
  --output /dev/null \
  --write-out 'frontend_trace_query_http=%{http_code}\n' \
  'http://127.0.0.1:3000/api/traces?from=2026-09-01T00%3A00%3A00Z&to=2026-09-02T00%3A00%3A00Z&limit=1'
```

Collector ingest를 실제로 확인해야 할 때는 승인된 controlled smoke로 한 synthetic span만 전송한다.

```bash
cd /home/huning/aerotrace
python3 scripts/otlp-ingest-sustained.py \
  --target-spans-per-sec 1 \
  --duration-sec 1 \
  --batch-size 1 \
  --workers 1
```

Backend/Collector log에서 401·403·permanent authentication error가 없고 해당 synthetic trace가 query되는지 확인한다. 검증이 실패하면 이전 Key가 활성인 동안 client secret을 바로잡고, 원인을 모른 채 이전 Key를 폐기하지 않는다.

### 5.5 이전 Key 폐기

목록에서 확인한 이전 row UUID와 정확한 이름을 사용한다. 이름은 prompt로 받아 history에 남기지 않는다.

```bash
cd /home/huning/aerotrace/backend
export AEROTRACE_API_KEY_ACTION='revoke'
read -rp 'Old API Key row UUID: ' AEROTRACE_API_KEY_ID
read -rp 'Old API Key exact name: ' AEROTRACE_API_KEY_EXPECTED_NAME
export AEROTRACE_API_KEY_ID AEROTRACE_API_KEY_EXPECTED_NAME
export AEROTRACE_API_KEY_CONFIRM_REVOKE='REVOKE'

bash ./gradlew manageProjectApiKeys --no-daemon

unset AEROTRACE_API_KEY_ACTION AEROTRACE_API_KEY_ID
unset AEROTRACE_API_KEY_EXPECTED_NAME AEROTRACE_API_KEY_CONFIRM_REVOKE
```

성공 기준:

```text
AEROTRACE_API_KEY_REVOKE_RESULT=REVOKED
AEROTRACE_API_KEY_REVOKED_STATUS=REVOKED
AEROTRACE_API_KEY_ACTIVE_COUNT_AFTER>=1
```

다시 목록과 Frontend/Collector smoke를 확인한다. 반복 실행에서 `ALREADY_REVOKED`가 나오면 추가 DB 변경 없이 이미 목표 상태라는 뜻이다.

## 6. 노출·침해 긴급 폐기

노출된 Key 외에 검증된 활성 Key가 있으면 일반 폐기 절차를 사용한다. 노출된 Key가 마지막 활성 Key이고 즉시 차단이 ingest/query 중단보다 중요할 때만 명시적 override를 추가한다.

```bash
export AEROTRACE_API_KEY_ALLOW_LAST_ACTIVE='true'
bash ./gradlew manageProjectApiKeys --no-daemon
unset AEROTRACE_API_KEY_ALLOW_LAST_ACTIVE
```

이 명령에도 `ACTION=revoke`, tenant/project slug, row UUID, expected name과 `CONFIRM_REVOKE=REVOKE`가 모두 필요하다. 결과의 active count가 0이면 Collector ingest와 legacy API Key query는 replacement 배포 전까지 인증 실패한다. Phase C 사용자 session 자체가 이 명령으로 폐기되지는 않는다.

긴급 순서:

```text
영향 범위와 대상 row 확인
-> 노출 Key 폐기
-> 기존 project에 replacement issue
-> Collector와 Frontend secret 갱신·재생성
-> ingest/query 확인
-> incident 기록과 노출 경로 제거
```

## 7. 만료 관리

만료는 자동 폐기나 자동 replacement를 만들지 않는다. 만료 시점부터 인증만 거부된다. 현재 expiry alert는 없으므로 정기 목록 점검에서 replacement window를 확보해야 한다.

초기 운영 권장값:

- 이름에 용도와 발급 시기를 포함
- 만료 최소 7일 전에 replacement 발급·검증
- 사용처가 둘 이상이면 소유자와 반영 완료 시각 기록
- 만료·폐기 row는 audit를 위해 삭제하지 않음

Expired Key는 마지막 활성 Key 보호와 관계없이 폐기 metadata를 기록할 수 있다.

## 8. 실패 처리

| 증상 | 조치 |
|---|---|
| tenant/project not found | slug를 대조한다. `issue`는 새 project를 자동 생성하지 않는다. |
| duplicate active name | 새 고유 이름을 사용하거나 기존 Key의 실제 사용처를 확인한다. |
| expected name mismatch | 목록의 Base64URL 이름을 decode해 대상 row를 다시 확인한다. |
| last active refusal | replacement를 먼저 발급·배포·검증한다. 긴급 상황만 override한다. |
| issue 후 원문 분실 | 이전 Key를 유지하고 분실한 새 row를 폐기한 뒤 재발급한다. |
| 새 Key 배포 후 query/ingest 실패 | 이전 Key 폐기 전 client secret, service 재생성과 대상 project를 교정한다. |
| 폐기 후 잘못된 대상임을 발견 | un-revoke하지 않는다. 새 Key를 발급하고 incident로 기록한다. |

## 9. 자동 검증과 변경 경계

Backend unit test는 상태 판정, 기존 project lock, duplicate active name, 마지막 활성 Key 보호, 명시적 override, expired/revoked 처리와 반복 폐기를 확인한다.

Backend CI의 ephemeral TimescaleDB acceptance는 다음 실제 흐름을 수행한다.

```text
bootstrap 첫 Key
-> metadata list
-> last-active revoke refusal
-> existing-project-only replacement issue
-> 이전 Key revoke
-> 동일 revoke ALREADY_REVOKED
-> ACTIVE 1 / REVOKED 1
-> lifecycle output credential pattern 없음
```

Production tenant/project/Key, secret file과 runtime은 이 자동 검증의 대상이 아니다. 기존 V5 schema에 필요한 timestamp가 이미 있어 새 DB migration은 추가하지 않는다.

## 10. 보안 근거

- [OWASP Secrets Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)
- [AeroTrace Security Policy](SECURITY.md)
- [AeroTrace Database Backup/Restore Runbook](DATABASE_BACKUP_RESTORE_RUNBOOK.md)
