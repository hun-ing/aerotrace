# AeroTrace Notification Operations Runbook

> 마지막 업데이트: 2026-08-21
> 범위: Collector queue alert outbox, local-file/Webhook delivery, persistent failure-state, retryable backoff, permanent failure 수동 재시도

---

관련 문서:

- [Webhook Receiver Contract](WEBHOOK_RECEIVER_CONTRACT.md)
- [Notification SLO Draft](NOTIFICATION_SLO.md)

## 1. 현재 Production 기준선

2026-08-21 현재 서버에 설치된 notification runtime은 local-file transport다.

```text
transport=local-file
RestrictAddressFamilies=AF_UNIX
aerotrace-notification-outbox.timer=active (waiting)
pending_events=0
active_failure=false
/etc/aerotrace/notification.env 없음
```

다음 repository 버전의 unit은 Webhook 전환 준비용이며 현재 `/etc/systemd/system`에 설치하지 않는다.

```text
deploy/systemd/aerotrace-notification-outbox.service
deploy/systemd/aerotrace-notification-outbox-retry-permanent.service
```

실제 Webhook endpoint, receiver-side `event_id` deduplication, secret 관리, rollback 준비가 끝나기 전에는 installed local-file service를 repository Webhook service로 교체하지 않는다.

## 2. 운영 불변 조건

- Pending event는 receipt가 durable하게 저장되기 전에 삭제하지 않는다.
- Receipt가 이미 있는 pending event는 `ACK_EXISTING`으로 자동 복구한다.
- Retryable failure는 자동 복구 대상이지만 bounded backoff를 따른다.
- Permanent failure는 일반 timer 실행으로 다시 HTTP 요청하지 않는다.
- Permanent failure 재시도는 원인 수정 후 운영자가 명시적으로 한 번만 수행한다.
- Failure-state나 pending event를 정상 처리로 오인해 수동 삭제하지 않는다.
- Webhook delivery는 exactly-once가 아니다. Receiver는 동일 `event_id` 중복을 처리할 수 있어야 한다.
- Repository unit과 installed unit은 다를 수 있으므로 운영 판단에는 installed unit을 기준으로 사용한다.

## 3. 주요 경로와 Unit

```text
Repository root
/home/huning/aerotrace

Pending outbox
/var/lib/aerotrace-monitoring/notification-outbox

Delivery receipts
/var/lib/aerotrace-monitoring/notification-receipts

Persistent Webhook failure-state
/var/lib/aerotrace-monitoring/notification-failure.json

Webhook environment file
/etc/aerotrace/notification.env

Evaluator service/timer
aerotrace-collector-queue-alert.service
aerotrace-collector-queue-alert.timer

Notification service/timer
aerotrace-notification-outbox.service
aerotrace-notification-outbox.timer

Permanent failure manual retry service
aerotrace-notification-outbox-retry-permanent.service
```

## 4. 읽기 전용 상태 점검

다음 명령은 runtime을 변경하지 않는다.

### Timer 상태

```bash
systemctl is-active aerotrace-collector-queue-alert.timer
systemctl is-active aerotrace-notification-outbox.timer
systemctl list-timers --all aerotrace-collector-queue-alert.timer aerotrace-notification-outbox.timer
```

정상 기준:

```text
두 timer 모두 active
다음 실행 시각 존재
```

### Installed unit 확인

```bash
systemctl cat aerotrace-notification-outbox.service
systemctl show aerotrace-notification-outbox.service -p FragmentPath -p Result -p ExecMainStatus -p ExecStart
```

현재 local-file 기준선에서는 installed service의 `ExecStart`에 다음 값이 있어야 한다.

```text
--transport local-file
```

Network sandbox는 다음 값이어야 한다.

```text
RestrictAddressFamilies=AF_UNIX
```

Repository Webhook unit의 내용만 보고 production이 Webhook으로 동작한다고 판단하지 않는다.

### Pending outbox 확인

```bash
cd /home/huning/aerotrace
python3 scripts/check-notification-outbox.py \
  --outbox-dir /var/lib/aerotrace-monitoring/notification-outbox
```

정상 idle 출력:

```text
status=OK
pending_events=0
pending_bytes=0
oldest_pending_age_sec=N/A
```

Threshold 옵션을 지정하지 않은 이 명령은 pending 존재 여부와 구조를 확인하는 용도다. Pending event가 있어도 유효한 파일이면 `status=OK`일 수 있다. 운영 WARNING/CRITICAL 기준은 notification SLA가 정해진 뒤 별도로 설정한다.

### Persistent failure-state 확인

```bash
cd /home/huning/aerotrace
python3 scripts/check-notification-failure-state.py \
  --failure-state-file /var/lib/aerotrace-monitoring/notification-failure.json
```

정상 idle 출력:

```text
status=OK
active_failure=false
failure_count=0
```

주의:

- Permanent failure는 threshold 없이도 `status=CRITICAL`이다.
- Retryable failure는 threshold를 지정하지 않으면 `active_failure=true`여도 `status=OK`일 수 있다.
- 운영 경보에는 `status`만 보지 말고 `active_failure`, `failure_kind`, `failure_count`, `failure_duration_sec`, `last_failure_age_sec`를 함께 확인한다.

### 최근 실행 결과와 journal

```bash
systemctl show aerotrace-notification-outbox.service \
  -p Result -p ExecMainCode -p ExecMainStatus -p ActiveEnterTimestamp

journalctl -u aerotrace-notification-outbox.service -n 100 --no-pager
```

Notification service는 timer가 반복 실행하는 oneshot이다. 성공 후 `inactive (dead)`로 보이는 것은 정상일 수 있으므로 최근 `Result`와 `ExecMainStatus`, timer 상태를 함께 본다.

`--quiet-idle`이 설정된 repository Webhook service는 pending event가 없으면 journal output을 남기지 않는다.

## 5. Processor 결과 해석

### Exit code

```text
0  정상 delivery, ACK_EXISTING, idle 또는 의도된 retryable defer
2  retryable Webhook failure 또는 delivery I/O failure
3  pending event 또는 delivery contract 오류
4  configuration 또는 persistent failure-state 처리 오류
5  최초 permanent Webhook failure 또는 기존 permanent latch 차단
```

Exit code 0만으로 외부 delivery 성공을 판단하지 않는다.

```text
delivery_result=DELIVERED
→ 이번 실행에서 외부 delivery와 내부 finalization 성공

delivery_result=ACK_EXISTING
→ 기존 receipt를 근거로 pending ACK 복구

delivery_result=DEFERRED_RETRYABLE_FAILURE
adapter_status=DEFERRED
→ backoff가 남아 있어 HTTP 요청 없이 정상 종료
```

### Failure 종류

Retryable:

```text
HTTP 408
HTTP 429
HTTP 5xx
connection failure
timeout
```

Permanent:

```text
HTTP 3xx
retryable로 분류되지 않은 HTTP 4xx
그 밖의 retryable 목록에 없는 non-2xx HTTP status
```

Redirect는 자동으로 따라가지 않는다.

## 6. Retryable Failure 대응

기본 backoff:

```text
failure_count 1  → 5초
failure_count 2  → 10초
failure_count 3  → 20초
failure_count 4  → 40초
failure_count 5  → 80초
failure_count 6  → 160초
failure_count 7+ → 300초
```

`last_failed_at`부터 계산한 지연이 남아 있으면 timer가 service를 실행해도 HTTP 요청은 발생하지 않는다.

이 정책은 `--failure-state-file`을 사용하는 Webhook 실행에만 적용된다. Failure-state를 사용하지 않는 수동 Webhook 실행에는 durable retry history가 없어 invocation마다 실제 요청을 수행한다.

```text
delivery_result=DEFERRED_RETRYABLE_FAILURE
retry_after_sec=<remaining seconds>
exit code=0
```

Deferred 실행은 failure-state를 다시 쓰지 않고 `failure_count`를 증가시키지 않는다.

대응 순서:

1. Failure-state checker로 `failure_kind=retryable`과 event ID를 확인한다.
2. Outbox checker로 pending event 수와 가장 오래된 event ID를 확인한다.
3. Receiver availability, DNS, TLS, 인증, rate limit, HTTP 상태를 endpoint 운영 범위에서 확인한다.
4. Timer와 최근 service 결과를 확인한다.
5. Backoff가 끝날 때까지 기다린 뒤 자동 요청과 복구 결과를 journal에서 확인한다.
6. 복구 후 `pending_events=0`, `active_failure=false`, receipt 증가를 확인한다.

하지 말아야 할 작업:

- Backoff 중 service를 반복 수동 실행하지 않는다.
- `last_failed_at`이나 `failure_count`를 편집해 retry를 앞당기지 않는다.
- Failure-state만 삭제해 정책을 우회하지 않는다.
- Timeout 직후 event ID 확인 없이 재전송하지 않는다.

Timer는 `OnUnitInactiveSec=5s`이므로 backoff 종료 시각과 다음 실제 retry 사이에는 timer cadence만큼의 추가 지연이 있을 수 있다.

## 7. Permanent Failure 대응

일반 timer 실행에서 permanent latch를 만나면:

```text
delivery_result=BLOCKED_PERMANENT_FAILURE
failure_latched=true
exit code=5
```

이 실행은 실제 HTTP 요청을 수행하지 않고 failure-state와 `failure_count`를 유지한다.

### 원인 확인

```bash
cd /home/huning/aerotrace
python3 scripts/check-notification-failure-state.py \
  --failure-state-file /var/lib/aerotrace-monitoring/notification-failure.json

python3 scripts/check-notification-outbox.py \
  --outbox-dir /var/lib/aerotrace-monitoring/notification-outbox

journalctl -u aerotrace-notification-outbox.service -n 100 --no-pager
```

다음을 확인한다.

- `failure_kind=permanent`
- Failure-state의 `failed_event_id`
- Outbox의 `oldest_event_id`
- `failure_reason`
- Receiver endpoint, 인증, payload contract 변경 여부

### 수동 재시도 전 조건

- Permanent failure의 원인을 수정했다.
- Receiver가 동일 `event_id`를 다시 받아도 안전하다.
- Failure-state와 pending event ID가 일치한다.
- Webhook environment file과 installed retry unit이 준비돼 있다.
- 재시도 결과를 즉시 확인할 운영자가 있다.

### 명시적 1회 재시도

다음 명령은 production HTTP 요청과 state 변경을 수행하므로 운영자가 위 조건을 확인한 뒤 직접 실행한다. Codex는 명시적 승인 없이 실행하지 않는다.

```bash
sudo systemctl start aerotrace-notification-outbox-retry-permanent.service
```

Retry unit은 `--retry-permanent-failure --max-events 1`을 사용하며 timer에 연결하지 않는다.

실행 결과 확인:

```bash
systemctl show aerotrace-notification-outbox-retry-permanent.service \
  -p Result -p ExecMainCode -p ExecMainStatus

journalctl -u aerotrace-notification-outbox-retry-permanent.service -n 100 --no-pager

cd /home/huning/aerotrace
python3 scripts/check-notification-outbox.py \
  --outbox-dir /var/lib/aerotrace-monitoring/notification-outbox

python3 scripts/check-notification-failure-state.py \
  --failure-state-file /var/lib/aerotrace-monitoring/notification-failure.json
```

가능한 결과:

```text
DELIVERED / exit 0
→ receipt 저장
→ failure-state 삭제
→ pending ACK

ACK_EXISTING / exit 0
→ 기존 receipt 기반 pending ACK 복구
→ 실제 HTTP 요청 없음

permanent failure / exit 5
→ 실제 요청 1회
→ failure_count 증가
→ 다시 permanent latch

retryable failure / exit 2
→ 실제 요청 1회
→ failure_kind=retryable
→ 이후 일반 timer가 backoff 후 자동 재시도
```

Failure-state 파일이 없으면 `ConditionPathExists` 때문에 retry unit은 요청을 실행하지 않는다.

## 8. ACK_EXISTING과 Crash-window 복구

다음 상태는 외부 delivery가 성공했지만 pending ACK 전에 process가 중단된 경우 발생할 수 있다.

```text
valid receipt 있음
동일 pending event 있음
failure-state가 남아 있을 수 있음
```

Adapter는 실제 HTTP 요청보다 먼저 receipt를 확인한다.

```text
receipt 검증
→ failure-state 삭제
→ pending ACK
→ delivery_result=ACK_EXISTING
```

이 상태에서 receipt나 pending event를 수동 삭제하지 않는다. 정상 timer 실행 또는 승인된 manual service 실행으로 자동 복구시킨다.

## 9. 비정상 State 또는 Event 대응

### Checker가 UNKNOWN인 경우

```text
status=UNKNOWN
checker_error=...
```

가능한 원인:

- JSON 손상
- schema 또는 field type 불일치
- filename과 event ID 불일치
- timezone 없는 timestamp
- 디렉터리 대신 파일이 있는 경로
- 읽기 권한 또는 I/O 문제

대응 원칙:

1. Timer와 service 결과를 확인한다.
2. 오류 경로와 message를 기록한다.
3. 원본 파일을 삭제하거나 직접 수정하지 않는다.
4. Receipt 존재 여부와 event ID를 먼저 대조한다.
5. 원인과 중복 전달 위험을 판단한 뒤 승인된 변경 시간에 원본 보존 사본을 만들고 복구한다.

Corrupt failure-state를 무조건 삭제하면 기존 permanent latch 또는 retryable backoff를 잃어 동일 event를 재전송할 수 있다.

Malformed pending event를 무조건 삭제하면 아직 전달되지 않은 notification을 유실할 수 있다. 현재 dead-letter/quarantine 자동화가 없으므로 별도 사고 기록과 운영 판단이 필요하다.

## 10. Webhook 전환 전 점검표

현재 local-file 기준선에서 Webhook으로 전환하려면 다음 조건이 모두 필요하다.

- 실제 endpoint와 소유자가 정해졌다.
- Receiver가 HTTPS를 지원한다.
- Endpoint 인증이 현재 adapter의 지원 범위와 맞는다.
- Receiver가 `event_id` 기반 중복 억제를 지원하거나 중복을 안전하게 처리한다.
- HTTP 2xx 성공 contract와 3xx/4xx/5xx 정책을 receiver 측과 합의했다.
- Timeout 후 ambiguous delivery 대응 절차가 있다.
- Notification 지연 SLA와 outbox/failure 경보 threshold가 정해졌다.
- Backoff `initial=5`, `maximum=300`이 endpoint rate limit과 SLA에 적합하다.
- `/etc/aerotrace/notification.env`의 소유권과 권한 정책이 정해졌다.
- 전환 직전 `pending_events=0`, `active_failure=false`를 확인했다.
- Installed local-file unit의 rollback 사본을 만들었다.
- 성공 ALERT/RECOVERY와 retryable/permanent failure를 change window에서 검증할 계획이 있다.

현재 adapter가 보내는 header는 `Content-Type`, `Accept`, `User-Agent`, `X-AeroTrace-Event-Id`로 고정돼 있으며 사용자 정의 `Authorization` header를 지원하지 않는다. URL userinfo도 validation에서 거부한다. Header 인증이 필요한 endpoint라면 Webhook 전환 전에 별도 구현과 검증이 필요하다.

### Repository unit 사전 검증

이 명령은 repository 파일을 검증하며 production runtime을 변경하지 않는다.

```bash
systemd-analyze verify \
  /home/huning/aerotrace/deploy/systemd/aerotrace-notification-outbox.service \
  /home/huning/aerotrace/deploy/systemd/aerotrace-notification-outbox-retry-permanent.service \
  /home/huning/aerotrace/deploy/systemd/aerotrace-notification-outbox.timer
```

### Repository 회귀 테스트

이 명령은 temporary directory와 local fake HTTP receiver만 사용하며 production outbox와 systemd runtime을 변경하지 않는다.

```bash
cd /home/huning/aerotrace
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -v
```

검증 범위:

- Bounded backoff 계산과 invalid configuration
- Local-file delivery smoke
- Webhook request payload/header contract
- HTTP status retryable/permanent 분류
- Retryable immediate defer, 두 번째 실패, HTTP 204 복구
- `ACK_EXISTING`이 retryable backoff보다 먼저 실행됨
- Permanent latch와 explicit retry

Local fake receiver가 loopback TCP port를 사용하므로 sandboxed 실행 환경에서는 socket permission이 필요할 수 있다.

## 11. Webhook 전환 명령 — 현재 실행 금지

이 절의 명령은 `/etc` 파일과 production systemd runtime을 변경한다. 실제 endpoint와 승인된 변경 시간이 준비된 뒤 운영자가 직접 실행한다.

### 11.1 현재 installed unit 백업

이유: Repository Webhook service가 같은 unit 이름의 installed local-file 기준선을 교체하므로 즉시 복구 가능한 원본이 필요하다.

```bash
sudo systemctl stop aerotrace-notification-outbox.timer

sudo cp --preserve=all --no-clobber \
  /etc/systemd/system/aerotrace-notification-outbox.service \
  /etc/systemd/system/aerotrace-notification-outbox.service.local-file.bak
```

기존 backup이 있으면 덮어쓰지 않는다. 계속하기 전에 backup에도 `--transport local-file`과 `RestrictAddressFamilies=AF_UNIX`가 있는지 읽기 전용으로 확인한다.

```bash
rg -n -- '--transport local-file|RestrictAddressFamilies=AF_UNIX' \
  /etc/systemd/system/aerotrace-notification-outbox.service.local-file.bak
```

### 11.2 Webhook environment file 생성

이유: Webhook URL이나 credential을 repository와 unit 파일에 저장하지 않는다.

```bash
sudo install -d -m 0750 -o root -g root /etc/aerotrace
sudoedit /etc/aerotrace/notification.env
sudo chown root:root /etc/aerotrace/notification.env
sudo chmod 0600 /etc/aerotrace/notification.env
```

파일 형식:

```text
AEROTRACE_WEBHOOK_URL="https://<approved-endpoint>"
```

실제 URL이나 token을 terminal history, journal, Git diff, issue, 문서에 붙여 넣지 않는다. 환경 파일 전체를 상태 확인 목적으로 출력하지 않는다.

### 11.3 Unit 설치와 활성화

이유: Webhook transport와 permanent failure 수동 retry 경로를 production systemd에 반영한다.

```bash
sudo install -m 0644 -o root -g root \
  /home/huning/aerotrace/deploy/systemd/aerotrace-notification-outbox.service \
  /etc/systemd/system/aerotrace-notification-outbox.service

sudo install -m 0644 -o root -g root \
  /home/huning/aerotrace/deploy/systemd/aerotrace-notification-outbox-retry-permanent.service \
  /etc/systemd/system/aerotrace-notification-outbox-retry-permanent.service

sudo systemctl daemon-reload
sudo systemctl start aerotrace-notification-outbox.service
```

첫 service start는 `pending_events=0` 상태에서 environment와 unit configuration이 유효한지 확인한다. 이 실행이 성공한 것을 다음 명령으로 확인한 뒤 timer를 시작한다.

```bash
systemctl show aerotrace-notification-outbox.service \
  -p Result -p ExecMainCode -p ExecMainStatus

sudo systemctl start aerotrace-notification-outbox.timer
```

### 11.4 전환 직후 확인

```bash
systemctl cat aerotrace-notification-outbox.service
systemctl is-active aerotrace-notification-outbox.timer
systemctl show aerotrace-notification-outbox.service -p Result -p ExecMainStatus
journalctl -u aerotrace-notification-outbox.service -n 100 --no-pager
```

Installed service에서 다음 값을 확인한다.

```text
--transport webhook
--failure-state-file /var/lib/aerotrace-monitoring/notification-failure.json
--retryable-backoff-initial-sec 5
--retryable-backoff-max-sec 300
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
```

Environment file의 URL 값 자체는 출력하지 않는다.

그다음 승인된 ALERT/RECOVERY test event로 다음을 검증한다.

```text
receiver request event_id와 outbox event_id 일치
HTTP 2xx 후 receipt 생성
pending 감소
failure-state 없음
ALERT 이후 RECOVERY 전달
```

## 12. Webhook 전환 Rollback — 사전 백업 필요

Webhook 전환에 실패하면 새 HTTP 요청을 중단하고 기존 local-file 기준선으로 복원한다.

다음 명령은 production runtime을 변경하므로 운영자가 직접 실행한다.

```bash
sudo systemctl stop aerotrace-notification-outbox.timer

sudo install -m 0644 -o root -g root \
  /etc/systemd/system/aerotrace-notification-outbox.service.local-file.bak \
  /etc/systemd/system/aerotrace-notification-outbox.service

sudo systemctl daemon-reload
```

복원한 unit과 notification state를 timer가 멈춘 상태에서 먼저 확인한다.

```bash
systemctl cat aerotrace-notification-outbox.service

cd /home/huning/aerotrace
python3 scripts/check-notification-outbox.py \
  --outbox-dir /var/lib/aerotrace-monitoring/notification-outbox

python3 scripts/check-notification-failure-state.py \
  --failure-state-file /var/lib/aerotrace-monitoring/notification-failure.json
```

반드시 다음 기준선으로 돌아왔는지 확인한다.

```text
--transport local-file
RestrictAddressFamilies=AF_UNIX
```

`pending_events=0`, `active_failure=false`인 경우에만 local-file timer를 바로 재시작한다.

```bash
sudo systemctl start aerotrace-notification-outbox.timer
systemctl is-active aerotrace-notification-outbox.timer
```

Webhook 실패 event나 failure-state가 남아 있으면 timer를 멈춘 상태로 유지하고 처리 방식을 먼저 결정한다. Local-file service를 시작하면 pending event가 외부로 전달되지 않은 채 local-file receipt로 ACK되고 Webhook failure-state가 남을 수 있다.

Rollback 후 Webhook environment file과 retry unit은 원인 분석이 끝날 때까지 삭제하지 말고 접근을 제한한 상태로 보존한다. 제거가 필요하면 별도 승인 후 recoverable한 이름으로 이동한다.

Unit 파일 복원 자체는 pending event나 failure-state를 삭제하지 않는다. Webhook failure가 남아 있을 때는 외부 전달을 복구할지, 별도 수동 전달 후 ACK할지, local-file 기준선으로 의도적으로 전환할지를 결정하고 기록한 뒤 timer 재시작 여부를 승인한다.

## 13. Incident 종료 조건

Notification incident는 다음을 모두 확인한 뒤 종료한다.

```text
aerotrace-notification-outbox.timer=active
최근 service Result=success
pending_events=0 또는 원인이 설명된 승인된 pending만 존재
active_failure=false
expected receipt 존재
Webhook incident이면 receiver의 expected event_id 확인
Webhook ALERT가 있었다면 필요한 RECOVERY 전달 확인
```

기록할 항목:

- Incident 시작과 종료 시각
- 최초 및 마지막 failure 시각
- `failure_kind`, `failure_reason`, 최종 `failure_count`
- 영향받은 event ID와 event 종류
- 실제 receiver request 수
- Deferred 횟수와 적용된 backoff
- 수동 permanent retry 수행 여부와 승인자
- Duplicate delivery 발생 여부
- Pending/receipt/failure-state 최종 상태
- 설정 또는 unit 변경과 rollback 여부

## 14. 알려진 한계

- Webhook은 at-least-once 성격이며 exactly-once를 보장하지 않는다.
- Timeout은 receiver 처리 여부를 알 수 없는 ambiguous failure다.
- Head pending event가 backoff 또는 permanent latch 상태이면 뒤 event가 막힌다.
- Dead-letter와 quarantine lifecycle이 없다.
- Retryable deferred 실행도 journal output을 남긴다.
- Backoff 계산은 server wall clock에 의존한다.
- 현재 production outbox/failure WARNING·CRITICAL threshold와 notification SLA가 확정되지 않았다.
- 실제 외부 Webhook provider와 credential은 아직 선택되지 않았다.
- 사용자 정의 Webhook authentication header를 지원하지 않는다.
- Tracked `unittest` suite는 local과 GitHub Actions에서 실행되지만 실제 external receiver의 durable acceptance와 deduplication까지 검증하지는 않는다.
