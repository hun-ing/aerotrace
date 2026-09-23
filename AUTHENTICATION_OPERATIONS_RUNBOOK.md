# AeroTrace Authentication Operations Runbook

> 마지막 업데이트: 2026-09-21
> 상태: Phase C session query·Frontend까지 repository 구현, 기본 Backend/기존 Production 인증은 비활성. 새 Frontend 배포 미실시
> 범위: 인증 profile과 endpoint, 첫 OWNER invite, activation·rollback·session 폐기, provider/DB 장애와 DR security-state 무효화

## 1. 현재 운영 경계

Repository에는 인증과 onboarding의 데이터·권한 기반 및 opt-in Backend runtime이 구현돼 있다.

```text
구현됨
- Flyway V9 user/identity/membership/invite/audit/JDBC session schema
- active user + active membership + project tenant join authorization
- OWNER / ADMIN / VIEWER default-deny permission matrix
- 첫 OWNER bootstrap invite 발급·폐기 operator task
- invite hash-only 저장과 single-use consume transaction
- 마지막 active OWNER revoke/demotion 보호
- Spring Security GitHub OAuth2 Client + Spring Session JDBC
- state + PKCE S256 + exact callback + exact read:user scope
- request-only OAuth authorized client + local minimal principal
- onboarding intent, callback provisioning, /api/v1/me와 POST logout
- Secure/HttpOnly/SameSite=Lax cookie와 session fixation 방어
- idle 8시간, absolute 7일, user/global session revoke service
- CSRF, exact Origin, safe redirect와 session-bound auth rate limit
- Session-authenticated tenant/project metadata와 Trace list/detail API
- Frontend login/invite/tenant/project/logout, server-only DAL와 session-only BFF

아직 운영에 적용되지 않음 또는 후속 단계
- Local/Production GitHub OAuth App 생성과 client secret 배치
- Production `auth-production` profile 활성화와 public traffic 전환
- 새 Frontend image와 session-only 환경의 Production 전환
- User/global session revoke operator CLI/API
- Edge/IP 기반 분산 login rate limit, account export/delete와 retention purge
```

기본값은 `aerotrace.auth.enabled=false`이며 이때 auth route는 default-deny되고 ingest/query API Key 경로만 기존대로 동작한다. V9 table이 존재하는 것만으로 로그인할 수 없다. **Phase C Frontend에는 공용 API Key fallback이 없으므로 단독 배포하면 기존 dashboard를 사용할 수 없다.** Production OAuth App/secret/profile과 Frontend를 별도 승인 없이 활성화하지 않는다.

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

Phase B에는 token을 same-origin POST body로 검증하고 callback에서 소비하는 route가 구현돼 있다. 다만 auth profile이 비활성인 환경에는 route가 없으므로 실제 사용자 전달은 해당 환경의 activation 직전에 수행한다.

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

현재 membership을 변경하는 public API와 operator task가 없다. DB row를 수동으로 수정해 이 보호를 우회하지 않는다.

## 7. Profile, OAuth App과 secret 경계

인증은 명시적 Spring profile로만 켠다.

| 환경 | profile | public origin | cookie |
|---|---|---|---|
| Local loopback HTTP | `auth-local` | 기본 `http://127.0.0.1:8080`, loopback만 허용 | `aerotrace_session`, `Secure=false`, `HttpOnly`, `SameSite=Lax`, `Path=/` |
| Production HTTPS | `auth-production` | `AEROTRACE_PUBLIC_ORIGIN` 필수 | `__Host-aerotrace_session`, `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`, no `Domain` |

Production origin은 lowercase scheme/host의 HTTPS origin 하나여야 하며 userinfo, path, query와 fragment를 허용하지 않는다. Local의 insecure 예외도 `localhost`, `127.0.0.1` 또는 `::1`만 허용한다. Cookie profile, scope, redirect와 lifetime이 계약과 다르면 startup이 실패한다.

Local과 Production은 서로 다른 GitHub OAuth App과 client secret을 사용한다. 각 App의 callback은 다음 한 개와 정확히 일치해야 한다.

```text
<AEROTRACE_PUBLIC_ORIGIN>/login/oauth2/code/github
```

Repository나 운영 문서에 client ID/secret의 실제 값을 기록하지 않는다. Client ID도 환경 구성을 통해 넣어 배포 환경 혼동을 줄이고 client secret은 승인된 secret store 또는 mode 0600 환경 파일에서만 주입한다.

```text
SPRING_PROFILES_ACTIVE=auth-local|auth-production
AEROTRACE_PUBLIC_ORIGIN=<exact origin>
AEROTRACE_GITHUB_CLIENT_ID=<environment-specific client ID>
AEROTRACE_GITHUB_CLIENT_SECRET=<secret>
```

명령 인자, shell history, CI log, screenshot과 GitHub issue에 secret을 넣지 않는다. Profile 활성화 전에는 DB backup/rollback 승인, exact callback, TLS termination, trusted proxy/header 정책과 Frontend same-origin 구성을 함께 확인한다.

## 8. Endpoint와 정상 로그인 흐름

Backend와 same-origin BFF의 현재 contract (API Key self-service는 아직 없음):

| method/path | 인증 | 목적 | 중요 조건 |
|---|---|---|---|
| `GET /api/v1/auth/csrf` | anonymous 허용 | CSRF token과 anonymous JDBC session 준비 | `no-store` |
| `POST /api/v1/onboarding/intents` | anonymous 허용 | invite 원문을 검증하고 row UUID만 session에 연결 | CSRF + exact `Origin`, `no-store` |
| `GET /oauth2/authorization/github` | anonymous 허용 | GitHub authorization 시작 | state + PKCE S256, session rate limit |
| `GET /login/oauth2/code/github` | OAuth callback | code 교환, identity 확인과 local principal 전환 | exact callback, generic failure |
| `GET /api/v1/me` | local session 필수 | active user와 active tenant membership 조회 | idle/absolute expiry와 user status 확인, `no-store` |
| `GET /api/v1/tenants` | local session 필수 | 현재 active membership의 tenant 목록 | role은 응답 metadata이며 Browser가 권한을 지정하지 않음 |
| `GET /api/v1/tenants/{tenantId}/projects` | local session 필수 | 소속 tenant의 project 목록 | 관계 없음 `404`, 프로젝트 없음 `[]` |
| `GET /api/v1/projects/{projectId}` | local session 필수 | SSR용 project metadata | active user/membership/project join, 관계 없음 `404` |
| `GET /api/v1/projects/{projectId}/traces` | local session 필수 | project trace 목록 | `from`/`to` 필수, 기존 query/filter/cursor 계약, `no-store` |
| `GET /api/v1/projects/{projectId}/traces/{traceId}` | local session 필수 | project 내 trace detail | 타 project 데이터 비노출, `no-store` |
| `POST /api/v1/logout` | local session 필수 | 현재 session 폐기 | CSRF + exact `Origin`, `204`, `no-store` |

신규 사용자의 순서는 다음과 같다.

```text
GET /api/v1/auth/csrf
-> 응답 token과 session cookie 보관
-> POST /api/v1/onboarding/intents
   body={"invite":"ati_<redacted>"}
   Origin=<exact public origin>
   <응답이 지정한 CSRF header>=<token>
-> GET /oauth2/authorization/github with same cookie
-> GitHub callback
-> session ID rotation + invite row lock/revalidation/consume
-> local user/identity/membership/audit commit
-> safe same-origin relative path로 302
-> GET /api/v1/me
```

기존 active identity는 invite 없이 로그인할 수 있다. 다른 tenant의 유효한 invite를 먼저 등록하면 기존 user가 callback transaction에서 그 membership을 추가할 수 있다. 신규 identity는 유효한 invite 없이는 user나 membership을 만들지 않는다. Invite가 만료·폐기·소비됐거나 user가 disabled이면 callback 전체를 rollback한다.

OAuth authorized client는 callback request attribute에만 존재한다. Refresh token, scope 불일치와 예상하지 않은 registration은 거부하고 provider access token은 local identity 확인 뒤 제거한다. JDBC session에는 provider principal/token이 아니라 `userId`와 `authenticatedAt`만 가진 local principal을 저장한다.

Onboarding과 OAuth 시작은 한 anonymous session에서 10분 동안 최대 10회다. 11번째 요청은 `429`와 `Retry-After`를 반환한다. BFF는 API의 bounded numeric Retry-After를 전달하고 OAuth 실패는 고정 로그인 오류로 변환한다. 이는 단일 session abuse 완화일 뿐 IP를 바꾸거나 cookie를 버리는 공격의 분산 제한이 아니므로 public activation에는 edge/IP rate limit과 monitoring이 별도로 필요하다.

Frontend POST는 exact Origin과 CSRF를 공통 처리한다. Body 제한/형식 검증으로 초대 요청을 거절해도 빈 invite sentinel을 Backend에 보내 CSRF 검증 후 기존 intent를 제거한다. 잘못된 원문은 보내지 않는다. BFF는 Backend의 상세 오류를 노출하지 않고 지정한 session cookie만 전달하며, Cookie의 Path/HttpOnly/SameSite/Secure/no Domain 계약을 검사한다. Browser `Authorization`, `Forwarded`, `X-Forwarded-*`, 사용자 식별 header는 upstream에 전달하지 않는다.

## 9. Session 수명과 폐기

Spring Session JDBC의 idle timeout은 8시간이고 만료 cleanup은 매분 실행한다. Local principal의 `authenticatedAt`부터 7일이 지나거나 user가 disabled이면 `/api/v1/me`, tenant/project metadata와 project trace 요청에서 session을 무효화한다. Membership 회수는 session에 저장된 role이 아니라 매 요청 DB join으로 반영한다. 향후 session route 추가 시 같은 검사를 빠뜨리지 않는다.

정상 logout은 Browser가 CSRF token과 exact Origin을 포함해 `POST /api/v1/logout`을 호출하는 방식이다. Disabled user나 현재 사용자 조회용 DB 접근이 실패해도 logout route는 남아 있는 cookie를 폐기할 수 있다.

Application에는 user별/global revoke service가 있지만 operator CLI/API는 아직 없다. 긴급 폐기는 승인된 target DB에서 다음 SQL을 transaction으로 실행한다. `principal_name`에는 공개된 username이 아니라 local user UUID 문자열을 사용한다. Attribute row는 foreign key cascade로 삭제된다.

```sql
BEGIN;

DELETE FROM aerotrace_session
WHERE principal_name = '<local-user-uuid>';

COMMIT;
```

전체 session 폐기는 모든 로그인 사용자를 즉시 로그아웃시키는 파괴적 운영 작업이다. 대상 환경, 영향과 재로그인 가능 여부를 확인하고 승인한 뒤에만 실행한다.

```sql
BEGIN;
DELETE FROM aerotrace_session;
COMMIT;
```

사후에는 session ID나 attribute bytes를 출력하지 않고 aggregate만 확인한다.

```sql
SELECT COUNT(*) AS session_rows
FROM aerotrace_session;
```

## 10. Provider·DB 장애와 secret rotation

| 증상 | 사용자 영향 | 운영 판단 |
|---|---|---|
| GitHub authorization/token/user-info 장애 | 신규 login callback은 generic `401`; tracked login attempt만 제한된 failure audit | 기존 JDBC session과 `/api/v1/me`가 정상인지 분리 확인하고 provider 응답/token을 log에 붙이지 않는다. |
| Invalid/missing state 또는 임의 callback flood | generic `401`, session 폐기 | login-start marker 없는 callback은 audit row를 만들지 않아 DB 증폭을 제한한다. |
| Application DB 장애 | `/api/v1/me`는 fail-closed `503`; ingest/query도 각 DB 의존성에 따라 영향 | logout은 계속 허용한다. DB 복구 전 auth를 우회하거나 user를 active로 가정하지 않는다. |
| Disabled user | `/api/v1/me` `401`과 session 폐기 | 필요하면 user별 session SQL로 잔여 session을 삭제한다. |
| Callback 설정 불일치 | provider 또는 callback 단계 실패 | public origin, App callback과 active profile을 exact string으로 비교한다. Wildcard callback을 추가하지 않는다. |

Secret rotation은 별도 maintenance change로 수행한다.

```text
새 client secret 생성
-> 승인된 secret store에 저장
-> 한 Backend instance 또는 staging에서 새 secret으로 callback acceptance
-> 전체 instance rollout
-> 신규 login과 기존 session 확인
-> rollback window 종료 뒤 이전 secret 폐기
-> 실제 값을 제외한 변경 시각·승인·결과 기록
```

Provider가 old/new secret overlap을 지원하지 않는 시점에는 신규 login 중단 시간을 먼저 공지하고 rollback 자료를 준비한다. Existing JDBC session은 client secret rotation만으로 자동 폐기되지 않는다. Secret 유출이면 rotation과 별도로 전체 session 폐기 필요성을 incident 범위에 따라 판단한다.

## 11. DR restore 뒤 security state 무효화

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

## 12. Rollback과 장애 처리

기본 Backend profile은 인증 비활성이지만 Phase C Frontend는 session-only이므로 image 교체가 기존 dashboard에 영향을 준다. Rollback 시 먼저 외부 UI 접근을 제한하고 승인된 이전 Backend/Frontend image·환경 조합을 복원한다. 인증을 껐다는 이유로 예전 공용 API Key dashboard를 public에 열지 않는다. Flyway V9 table을 drop하거나 history를 되돌리지 않으며 Collector/API Key 경계는 별도로 확인한다.

| 증상 | 조치 |
|---|---|
| tenant not found | slug와 대상 DB를 대조한다. Operator는 tenant를 만들지 않는다. |
| active OWNER exists | operator bootstrap을 사용하지 않는다. Membership application service는 있지만 운영 CLI/API는 아직 없으므로 관리 경로 구현·승인 없이 SQL로 보호를 우회하지 않는다. |
| usable bootstrap already exists | 기존 출력의 row UUID를 확인해 전달하거나 폐기한다. 원문을 잃었다면 폐기 후 재발급한다. |
| wrong confirmation | `ISSUE`/`REVOKE`를 의도한 작업에만 정확히 설정한다. |
| consumed invite revoke refusal | membership/user incident로 전환한다. Invite row를 삭제하거나 consumed state를 되돌리지 않는다. |
| provider outage | 신규 login과 기존 JDBC session을 분리해 확인한다. 기존 session을 임의로 전부 삭제하지 않는다. |
| session compromise | 대상 user UUID가 확정되면 user별 폐기를 우선하고 범위를 모르면 승인 후 global 폐기를 수행한다. |
| auth activation regression | 외부 UI를 제한한 뒤 이전 image·환경 조합으로 rollback하고 API Key ingest/query 회귀를 확인한다. 새 Frontend에 API Key를 넣는 방식으로 우회하지 않는다. |

## 13. 자동 검증

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
auth disabled default와 unknown route default-deny
Local/Production origin·cookie·scope·redirect fail-closed validation
state + PKCE S256 + exact callback + read:user authorization redirect
invalid state provider 호출 전 거부와 callback audit flood 제한
request-only OAuth token, refresh/scope mismatch 거부와 local principal-only session
callback session ID rotation과 old cookie 거부
invite-only 신규 identity transaction과 기존 identity 재로그인/추가 tenant join
disabled user, invalid invite와 concurrent same identity/invite rollback·직렬화
CSRF + exact Origin + malformed onboarding stale-intent 제거
10분 10회 session rate limit과 Retry-After
idle/absolute expiry, /api/v1/me, POST logout과 user/global session revoke
Production __Host cookie의 Secure/HttpOnly/SameSite/Path/no Domain
기존 OTLP ingest와 Project API Key query 경계 회귀
```

2026-09-04 격리 Java 21 + tmpfs TimescaleDB에서 auth infrastructure 선택 테스트 39/39와 Backend 전체 29 suite, 130/130이 통과했다. Provider token과 user-info network call은 test stub으로 대체했고 Production OAuth App, secret, DB와 runtime은 변경하지 않았다.

Phase C 검증은 Backend 30 suite, 140 test와 Frontend standalone HTTP 10개 scenario(+parent test)를 추가 확인한다. Backend는 role별 read, 타 tenant/project·cursor 경계, 즉시 membership 회수와 모든 session read의 disabled/absolute expiry를 검증한다. Frontend는 proxy allowlist·Host/Origin/CSRF·Cookie/redirect·cross-user SSR·malformed invite sentinel·safe error를 검증하며 CI에 포함한다. Chromium fixture smoke에서 프로젝트 변경의 이전 상세/커서 제거, 조직 변경, logout과 보호 화면 복귀도 확인했다. 이는 실제 GitHub provider E2E를 대체하지 않는다.

Database backup/restore acceptance는 V1~V9와 auth/session fixture를 full archive로 복원하고 11개 필수 table, aggregate count와 one-way fingerprint가 source/target에서 일치하는지 확인한다.

## 14. Local 준비와 Production activation checkpoint

### 14.1 Local opt-in 구성

Local GitHub OAuth App의 callback을 `http://127.0.0.1:3000/login/oauth2/code/github`로 설정하고 같은 origin으로 Frontend를 연다. 원격 SSH 서버에서 개발할 때도 승인된 port forwarding 등을 통해 브라우저의 loopback과 연결해야 한다. 이 예시를 서버의 public HTTP 주소로 바꾸지 않는다.

```bash
cp auth.env.example auth.env
chmod 600 auth.env
```

`auth.env`에는 Local App client ID/secret과 `SPRING_PROFILES_ACTIVE=auth-local`을 넣는다. `frontend.env`는 `frontend.env.example`을 기준으로 public origin이 같고 insecure flag가 `true`인지 확인한다. Frontend에는 OAuth secret이나 Project API Key를 넣지 않는다. 실제 secret 파일 내용은 출력하지 않는다.

기존 README의 DB/Collector 준비가 끝난 Local 환경에서만 다음 opt-in overlay를 추가한다. 이 명령은 deployment를 수행하므로 Production에는 별도 승인 없이 실행하지 않는다.

```bash
docker compose -f docker-compose.yaml -f docker-compose.app.yaml -f docker-compose.auth.yaml --profile app config --quiet
docker compose -f docker-compose.yaml -f docker-compose.app.yaml -f docker-compose.auth.yaml --profile app up -d --build
```

첫 OWNER는 3·4절의 operator 절차로 기존 tenant에 invite를 발급하고 `/login`에서 입력한다. `GET /health`는 Frontend 프로세스 liveness만 뜻한다. 실제 완료 조건은 callback 성공, 조직/프로젝트 목록, 권한 있는 Trace 조회와 logout이다. 기존 PowerShell runtime helper는 이 auth overlay를 자동 추가하지 않는다.

### 14.2 Production 승인 조건

Repository 구현만으로 아래 운영 checkpoint가 완료된 것은 아니다. Production login은 각 항목의 evidence와 rollback 승인을 확보한 뒤 별도 change로 활성화한다.

- [x] Spring Security OAuth2 Client와 Spring Session JDBC dependency/config
- [x] Runtime schema initialization `never`와 table prefix `AEROTRACE_SESSION`
- [x] `state`, PKCE `S256`, exact callback와 exact `read:user` scope
- [x] Invite 검증부터 user identity/membership 생성까지 한 callback transaction
- [x] Production/Local cookie profile fail-closed acceptance
- [x] Session fixation, idle/absolute expiry, user disable와 global/user revoke service
- [x] CSRF, Origin, safe redirect와 session-bound rate limit
- [x] OAuth token/session attribute non-persistence test
- [ ] Production hostname/TLS/trusted proxy와 exact origin 확정
- [ ] Local/Production GitHub OAuth App 분리 생성과 secret custody/rotation rehearsal
- [ ] Production DB backup 승인 뒤 V9 migration 및 auth profile deployment
- [ ] Edge/IP 분산 rate limit과 auth monitoring/alert
- [x] Frontend Phase C same-origin session/CSRF proxy와 API Key 제거 (repository)
- [ ] 실제 GitHub OAuth Local E2E와 Production Frontend/Backend 동시 전환 승인
- [ ] User/global revoke의 안전한 operator entry point 또는 승인된 SQL rehearsal
- [ ] 사용자 export/delete, purge와 backup 잔존 기간 확정
- [ ] Production-sized encrypted off-host backup/upload/download/restore rehearsal
- [ ] Activation canary, legacy ingest/query 회귀와 default-disabled rollback rehearsal

Production은 별도 OAuth App, HTTPS public origin, `auth-production`과 Frontend insecure flag `false`가 필요하다. Backend/DB를 public에 직접 노출하지 않고 edge는 승인된 public Host를 보존한다. OAuth callback query/code, Cookie, invite body를 edge/access log에 남기지 않도록 검증한다. 기본 Compose port는 개발용이므로 기존 prod/edge overlay와 정책 검토를 생략하지 않는다.

Phase C는 DB schema 변경이 없다. 현재 보류된 production-sized encrypted off-host backup은 실제 사용자 data 수집 전 별도 필수 checkpoint로 유지한다.

## 15. 관련 문서

- [User Authentication and Onboarding Design](USER_AUTH_ONBOARDING_DESIGN.md)
- [User Data Retention Policy](USER_DATA_RETENTION_POLICY.md)
- [Database Backup/Restore Runbook](DATABASE_BACKUP_RESTORE_RUNBOOK.md)
- [Project API Key Lifecycle Runbook](PROJECT_API_KEY_RUNBOOK.md)
- [Security Policy](SECURITY.md)
