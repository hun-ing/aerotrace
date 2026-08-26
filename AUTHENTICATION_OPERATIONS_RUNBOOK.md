# AeroTrace Authentication Operations Runbook

> 마지막 업데이트: 2026-08-26
> 상태: Phase A schema·authorization·bootstrap invite 구현, OAuth login과 JDBC session runtime은 아직 비활성
> 범위: 사용자 인증 기반 schema, 첫 OWNER bootstrap invite, invite 폐기와 DR security-state 무효화

## 1. 현재 운영 경계

Phase A는 인증과 onboarding의 데이터·권한 기반만 구현한다.

```text
구현됨
- Flyway V9 user/identity/membership/invite/audit/JDBC session schema
- active user + active membership + project tenant join authorization
- OWNER / ADMIN / VIEWER default-deny permission matrix
- 첫 OWNER bootstrap invite 발급·폐기 operator task
- invite hash-only 저장과 single-use consume transaction
- 마지막 active OWNER revoke/demotion 보호

아직 구현되지 않음
- GitHub OAuth authorization/callback
- Browser login/logout endpoint
- Spring Security와 Spring Session JDBC runtime 연결
- session cookie 생성·폐기
- Frontend login, invite 입력과 tenant/project selector
```

따라서 Phase A migration이나 bootstrap invite가 존재해도 사용자가 로그인할 수 없다. Production OAuth app/client secret을 만들거나 Frontend를 공개 인증 상태로 전환하지 않는다.

Codex는 Production migration, bootstrap invite 발급·폐기, session 삭제와 invite 일괄 폐기를 자동 실행하지 않는다. 운영자가 대상 DB와 영향 범위를 확인한 뒤 명시적으로 실행한다.

## 2. Schema와 비밀정보 경계

Flyway `V9__create_user_auth_foundation.sql`은 다음 table을 추가한다.

```text
app_users
user_identities
tenant_memberships
onboarding_invites
security_audit_events
aerotrace_session
aerotrace_session_attributes
```

주요 보장:

- GitHub identity key는 변경 가능한 login이 아닌 provider numeric subject다.
- Invite 원문은 `ati_`와 Base64URL 43자로 구성된 256-bit random token이며 DB에는 32-byte SHA-256만 저장한다.
- Audit schema에는 free-form payload column이 없다. 원문 invite/API Key, OAuth token, cookie와 session ID를 기록하지 않는다.
- JDBC session table은 Spring Session의 PostgreSQL column 계약을 사용하되 `AEROTRACE_SESSION` prefix를 사용한다.
- Runtime schema auto-initialization은 사용하지 않는다. Phase B에서도 Flyway가 schema owner다.

## 3. 공통 준비

Operator task는 기존 tenant만 대상으로 하며 tenant를 자동 생성하지 않는다. Java 21 환경에서 Backend가 사용할 정확한 DB에 연결한다. Password를 command argument나 shell history에 쓰지 않는다.

Operator는 Spring Boot context를 시작하므로 연결한 DB에 미적용 Flyway migration이 있으면 먼저 적용한다. 따라서 invite 발급을 read-only 조회로 보면 안 되며, V9 release·backup·rollback 승인 전에 Production DB를 가리키지 않는다.

```bash
cd /home/huning/aerotrace/backend

export AEROTRACE_DB_URL='jdbc:postgresql://<database-host>:5432/aerotrace?reWriteBatchedInserts=true'
export AEROTRACE_DB_USERNAME='<database-user>'
read -rsp 'AeroTrace DB password: ' AEROTRACE_DB_PASSWORD
echo
export AEROTRACE_DB_PASSWORD

export AEROTRACE_BOOTSTRAP_TENANT_SLUG='<existing-tenant-slug>'
```

실행 전 확인:

- 대상 host/database가 Local, Staging, Production 중 어디인지 별도로 대조한다.
- Shell debug tracing(`set -x`)과 terminal recording을 끈다.
- 원문 invite를 issue, chat, screenshot, clipboard history와 Git에 남기지 않는다.
- 전달받을 첫 OWNER 본인만 접근 가능한 별도 out-of-band channel을 정한다.
- 기존 active OWNER나 아직 유효한 bootstrap invite가 없는 tenant인지 확인한다.

## 4. 첫 OWNER bootstrap invite 발급

기본 만료는 24시간이고 1~168시간만 허용한다. `ISSUE` 확인 문자열이 없으면 실행되지 않는다.

```bash
export AEROTRACE_BOOTSTRAP_INVITE_EXPIRATION_HOURS='24'
export AEROTRACE_BOOTSTRAP_INVITE_CONFIRM='ISSUE'

bash ./gradlew issueBootstrapInvite --no-daemon

unset AEROTRACE_BOOTSTRAP_INVITE_EXPIRATION_HOURS
unset AEROTRACE_BOOTSTRAP_INVITE_CONFIRM
```

성공 출력:

```text
AEROTRACE_BOOTSTRAP_INVITE_RESULT=ISSUED
AEROTRACE_BOOTSTRAP_INVITE_TENANT_ID=<UUID>
AEROTRACE_BOOTSTRAP_INVITE_ID=<UUID>
AEROTRACE_BOOTSTRAP_INVITE_EXPIRES_AT=<UTC instant>
AEROTRACE_BOOTSTRAP_INVITE=ati_<43 Base64URL characters>
```

마지막 줄의 원문만 한 번 표시된다. DB, 목록 명령이나 log에서 복구할 수 없다. 전달 전에 잃어버렸거나 잘못된 사람에게 노출했다면 새 invite를 먼저 만들려고 하지 말고 해당 row UUID를 폐기한다.

다음 안전 기본값이 적용된다.

- Tenant row lock으로 동시에 여러 bootstrap invite를 발급하지 못한다.
- Active OWNER가 이미 있으면 operator bootstrap을 거부한다.
- 같은 tenant에 아직 유효한 미사용 bootstrap invite가 있으면 중복 발급을 거부한다.
- Operator bootstrap은 `OWNER` role만 발급하고 `created_by_user_id`는 `NULL`이다.
- DB insert와 `BOOTSTRAP_INVITE_ISSUED` audit 성공 뒤에만 원문을 반환한다.

Phase B callback이 구현되기 전에는 이 token을 입력하거나 소비할 public route가 없다. 실제 사용자에게 전달하는 작업은 Phase B activation 직전에 수행한다.

## 5. 미사용 bootstrap invite 폐기

폐기는 원문이 아니라 발급 출력의 invite row UUID를 사용한다. Tenant slug, row UUID와 `REVOKE` 확인 문자열이 모두 일치해야 한다.

```bash
read -rp 'Bootstrap invite row UUID: ' AEROTRACE_BOOTSTRAP_INVITE_ID
export AEROTRACE_BOOTSTRAP_INVITE_ID
export AEROTRACE_BOOTSTRAP_INVITE_CONFIRM='REVOKE'

bash ./gradlew revokeBootstrapInvite --no-daemon

unset AEROTRACE_BOOTSTRAP_INVITE_ID
unset AEROTRACE_BOOTSTRAP_INVITE_CONFIRM
```

성공 또는 이미 완료된 상태:

```text
AEROTRACE_BOOTSTRAP_INVITE_REVOKE_RESULT=REVOKED|ALREADY_REVOKED
AEROTRACE_BOOTSTRAP_INVITE_ID=<UUID>
AEROTRACE_BOOTSTRAP_INVITE_REVOKED_AT=<UTC instant>
```

폐기 출력에는 token 원문과 hash가 없다. 이미 소비된 invite는 operator가 폐기하지 않는다. 잘못된 소비가 의심되면 account/membership incident로 취급하고 Phase B의 user session revoke 절차까지 수행해야 한다.

## 6. 권한과 상태 판정

Backend permission service는 role만 보지 않고 다음 관계를 한 query에서 확인한다.

```text
app_users.status=ACTIVE
AND tenant_memberships.status=ACTIVE
AND requested project.id exists
AND projects.tenant_id=membership.tenant_id
```

Membership 또는 project 관계가 없으면 resource 존재를 숨기는 `NOT_FOUND`, active membership은 있지만 role이 부족하면 `FORBIDDEN`, 명시적 role matrix가 허용할 때만 `ALLOWED`다. 새 permission enum은 switch에 명시적으로 추가하지 않으면 compile되지 않아 암묵적 허용 경로가 없다.

Role 변경과 revoke는 tenant row와 membership row를 잠근다. 마지막 active OWNER는 거부되며 소유권 이전은 다음 순서를 따른다.

```text
새 active member를 OWNER로 승격
-> active OWNER가 2명인지 확인
-> 기존 OWNER를 ADMIN/VIEWER로 강등하거나 revoke
```

Phase A에는 membership을 변경하는 public API와 operator task가 없다. DB row를 수동으로 수정해 이 보호를 우회하지 않는다.

## 7. DR restore 뒤 security state 무효화

Full logical backup에는 session과 invite table도 포함된다. 복원이 성공했다는 이유만으로 과거 security state를 다시 활성화하지 않는다. 격리 target 검증 후 실제 traffic을 열기 전에 다음 목표 상태를 만든다.

```text
restored session rows=0
restored session attribute rows=0
모든 미사용 invite=revoked
기존 active membership과 user status=운영자 검토 후 유지 또는 별도 revoke
새 bootstrap/owner invite=검토 완료 뒤 명시적으로 재발급
```

아래 SQL은 복원 target에 쓰기를 수행하는 operator action이다. Database 이름과 connection target을 대조하고 별도 승인한 뒤 transaction으로 실행한다.

```sql
BEGIN;

DELETE FROM aerotrace_session_attributes;
DELETE FROM aerotrace_session;

UPDATE onboarding_invites
SET revoked_at = CURRENT_TIMESTAMP
WHERE consumed_at IS NULL
  AND revoked_at IS NULL;

INSERT INTO security_audit_events (
    id,
    actor_user_id,
    tenant_id,
    project_id,
    action,
    result,
    occurred_at,
    correlation_id
)
VALUES (
    gen_random_uuid(),
    NULL,
    NULL,
    NULL,
    'DR_SECURITY_STATE_INVALIDATED',
    'SUCCESS',
    CURRENT_TIMESTAMP,
    gen_random_uuid()
);

COMMIT;
```

원문 payload나 row ID를 출력하지 않는 사후 aggregate:

```sql
SELECT
    (SELECT COUNT(*) FROM aerotrace_session) AS session_rows,
    (SELECT COUNT(*) FROM aerotrace_session_attributes) AS session_attribute_rows,
    (
        SELECT COUNT(*)
        FROM onboarding_invites
        WHERE consumed_at IS NULL
          AND revoked_at IS NULL
    ) AS usable_invite_rows;
```

세 값이 모두 0이 아니면 traffic을 열지 않는다.

## 8. Rollback과 장애 처리

Phase A는 login route와 session runtime을 활성화하지 않으므로 repository 배포만으로 사용자의 인증 흐름이 바뀌지 않는다. Flyway V9가 DB에 적용된 뒤에는 table을 임의로 drop하거나 Flyway history를 되돌리지 않는다. 문제가 있으면 애플리케이션 revision을 이전 것으로 되돌리고 추가 migration으로 교정한다.

| 증상 | 조치 |
|---|---|
| tenant not found | slug와 대상 DB를 대조한다. Operator는 tenant를 만들지 않는다. |
| active OWNER exists | operator bootstrap을 사용하지 않는다. Phase B membership 관리 경로를 사용한다. |
| usable bootstrap already exists | 기존 출력의 row UUID를 확인해 전달하거나 폐기한다. 원문을 잃었다면 폐기 후 재발급한다. |
| wrong confirmation | `ISSUE`/`REVOKE`를 의도한 작업에만 정확히 설정한다. |
| consumed invite revoke refusal | membership/user incident로 전환한다. Invite row를 삭제하거나 consumed state를 되돌리지 않는다. |
| provider outage | Phase B login 활성화 전에는 영향 없음. Phase B에서 기존 session 유지와 신규 login 실패 대응을 추가한다. |
| session compromise | Phase A runtime에는 session 없음. Phase B activation 전에 user별/global revoke operator를 구현한다. |

## 9. 자동 검증

Backend unit/integration suite는 다음을 확인한다.

```text
OWNER/ADMIN/VIEWER 전체 permission matrix
membership 없음 default NOT_FOUND
active user + membership + project tenant join
disabled user / revoked membership 즉시 거부
cross-tenant project NOT_FOUND
invite 256-bit 형식과 32-byte hash-only 저장
invite domain object `toString()` 원문·hash redaction
만료 invite와 active-owner bootstrap 발급 거부
동일 invite concurrent consume 정확히 1건 성공
bootstrap revoke와 ALREADY_REVOKED
두 OWNER concurrent demotion에서 active OWNER 1명 보존
Spring Session JDBC table column 호환성
```

Database backup/restore acceptance는 V1~V9와 auth/session fixture를 full archive로 복원하고 11개 필수 table, aggregate count와 one-way fingerprint가 source/target에서 일치하는지 확인한다.

## 10. Phase B activation checkpoint

다음 항목이 구현·검증되기 전에는 GitHub OAuth app과 Production login을 활성화하지 않는다.

- Spring Security OAuth2 Client와 Spring Session JDBC dependency/config
- Runtime schema initialization `never`와 table prefix `AEROTRACE_SESSION`
- `state`, PKCE `S256`, exact callback와 minimal scope
- Invite 검증부터 user identity/membership 생성까지 한 callback transaction
- Production/Local cookie profile fail-closed acceptance
- Session fixation, idle/absolute expiry, user disable와 global/user revoke
- CSRF, Origin, safe redirect와 rate limit
- OAuth token/session ID non-persistence scan
- 사용자 데이터 export/delete와 backup 잔존 경계 검토

## 11. 관련 문서

- [User Authentication and Onboarding Design](USER_AUTH_ONBOARDING_DESIGN.md)
- [User Data Retention Policy](USER_DATA_RETENTION_POLICY.md)
- [Database Backup/Restore Runbook](DATABASE_BACKUP_RESTORE_RUNBOOK.md)
- [Project API Key Lifecycle Runbook](PROJECT_API_KEY_RUNBOOK.md)
- [Security Policy](SECURITY.md)
