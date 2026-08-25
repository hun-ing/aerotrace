# AeroTrace User Authentication and Onboarding Design

> 상태: 채택 — 구현 전 설계 계약
> 결정일: 2026-08-25
> 적용 범위: 공개 Web UI의 사용자 로그인, tenant membership, project 선택과 Project API Key self-service 경계
> 현재 구현 여부: 미구현. 현재 Frontend는 계속 server-only Project API Key 하나를 사용한다.

## 1. 목적

현재 AeroTrace는 Collector와 Next.js BFF가 같은 Project API Key로 Backend에 접근한다. 이 방식은 local 개발과 제한된 PoC에는 단순하지만, Frontend에 접근한 사람을 구분하지 못하고 서버당 한 project만 조회할 수 있다.

이 문서는 공개 MVP 구현 전에 다음 경계를 고정한다.

- 사람의 로그인과 Collector의 Project API Key 인증을 분리한다.
- 사용자, 외부 identity, tenant membership과 권한의 source of truth는 Backend와 PostgreSQL로 둔다.
- Browser에는 불투명한 HttpOnly session cookie만 저장한다.
- Next.js는 same-origin BFF를 유지하지만 최종 project 권한은 Backend가 매 요청 확인한다.
- Abuse 방어가 준비되기 전에는 공개 self-signup 대신 초대 전용 onboarding만 허용한다.

구현·운영 절차는 이 문서의 단계와 acceptance를 통과한 뒤 별도 runbook으로 작성한다.

## 2. 현재 상태와 변경하지 않는 것

현재 구현은 다음과 같다.

```text
Collector -> Project API Key -> POST /v1/traces
Browser -> Next.js BFF -> server-only Project API Key -> GET /api/v1/traces
Project API Key -> tenant_id + project_id
```

이번 결정에서도 다음은 유지한다.

- `tenants`, `projects`, `project_api_keys`와 span의 `(tenant_id, project_id)` 격리
- Project API Key의 hash-only 저장, 만료와 logical revoke
- 최초 bootstrap과 기존 project key lifecycle의 operator task
- Browser가 Project API Key 원문을 받지 않는 BFF 경계
- Production Backend와 Database를 public network에 직접 게시하지 않는 원칙

Project API Key는 계속 workload credential이다. 로그인 session, 사용자 token 또는 tenant 관리 권한으로 확장하지 않는다.

## 3. 검토한 대안

| 대안 | 장점 | 채택하지 않은 이유 |
|---|---|---|
| 자체 email/password | Provider 의존 없음 | Password reset, email verification, credential breach 대응과 MFA까지 직접 운영해야 함 |
| Next.js에서만 Auth/RBAC | Frontend 변경이 집중됨 | Backend가 사용자와 resource 관계를 알지 못해 우회 경로와 향후 API에서 권한 누락 위험이 큼 |
| BFF가 project별 API Key 선택 | 기존 Backend 변경이 작음 | 사람의 권한을 workload secret으로 대신하며 universal BFF secret의 blast radius가 커짐 |
| Self-hosted IdP | 표준 OIDC와 provider 독립성 | 현재 단일 운영자가 별도 고가용 IdP, backup과 upgrade까지 운영해야 함 |
| Managed auth SaaS | 빠른 구현과 다양한 login | 비용·tenant lock-in과 외부 장애 경계가 추가됨 |
| GitHub OAuth + AeroTrace session | Password를 저장하지 않고 개발자 대상 MVP에 적합 | GitHub 로그인 의존성과 provider 장애가 생김 |

초기 선택은 마지막 대안이다. 다만 GitHub OAuth token을 AeroTrace API credential로 사용하지 않고, 로그인 순간의 외부 identity 확인에만 사용한다.

## 4. 채택 결정

### 4.1 Identity provider

- 초기 provider는 GitHub OAuth Web Application Flow 하나다.
- OAuth `state`와 PKCE `S256`을 모두 사용한다.
- 정확한 HTTPS callback URL만 등록하고 wildcard callback은 사용하지 않는다.
- Repository, organization, email 접근 scope를 요청하지 않는다. 공개 profile 확인에 필요한 최소 범위만 사용한다.
- 사용자 식별자는 변경 가능한 login이나 email이 아니라 GitHub의 불변 numeric user ID를 문자열 subject로 저장한다.
- GitHub access token과 refresh token은 DB, session, log와 audit event에 저장하지 않는다.
- 로그인 callback에서 identity를 확인한 뒤 OAuth authorized-client state를 제거한다.

GitHub login은 identity proof다. AeroTrace tenant 접근 권한은 항상 local membership에서 별도로 결정한다.

### 4.2 Onboarding policy

초기 공개 MVP는 invite-only다.

- 기존 tenant에 대해 `OWNER`는 모든 role invite를, `ADMIN`은 `ADMIN`과 `VIEWER` invite를 만들 수 있다.
- 첫 owner는 operator task가 기존 tenant에 bootstrap invite를 발급한다.
- Invite code는 CSPRNG 256-bit 이상 entropy를 사용한다.
- 원문 invite code는 발급 시 한 번만 표시하고 DB에는 SHA-256 hash만 저장한다.
- 기본 만료는 24시간이며 한 번만 사용할 수 있다.
- Invite code는 URL query/path에 넣지 않는다. 사용자가 same-origin form에 입력하고 HTTPS POST body로 보낸다.
- Backend는 code를 즉시 hash로 변환해 검증하고 anonymous server session에는 원문이 아닌 onboarding intent ID만 연결한 뒤 GitHub로 redirect한다.
- Callback에서 invite row를 잠그고 만료·폐기·기사용 여부를 다시 확인한 transaction 안에서 user identity와 membership을 만든다.
- 동시에 같은 invite를 사용하면 한 요청만 성공한다.
- 기존 active membership 사용자는 invite 없이 다시 로그인할 수 있다.
- Invite가 없는 새 identity는 user나 membership을 자동 생성하지 않고 거부한다.

공개 self-signup과 첫 로그인 자동 tenant 생성은 rate limit, tenant quota, abuse monitoring과 사용 약관이 준비될 때까지 보류한다.

### 4.3 Session

- Spring Security의 server-side `HttpSession`과 Spring Session JDBC를 사용한다.
- Session table은 같은 PostgreSQL에 두고 Flyway가 schema를 관리한다. Runtime 자동 schema 생성은 끈다.
- Browser cookie에는 무작위 session ID만 둔다. User profile, role, tenant와 OAuth token을 넣지 않는다.
- Production cookie 이름은 `__Host-aerotrace_session`을 사용한다.
- Cookie 속성은 `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`, `Domain` 없음이다.
- OAuth top-level redirect 때문에 `SameSite=Strict` 대신 `Lax`를 사용한다.
- Login 성공 시 session ID를 회전하고 logout 시 server session을 삭제한다.
- Idle timeout은 8시간, absolute lifetime은 7일로 시작하며 remember-me는 제공하지 않는다.
- User disable과 account incident response에서는 해당 사용자의 session을 모두 폐기할 수 있어야 한다.
- Membership revoke는 session이 남아 있어도 해당 tenant 접근을 다음 요청부터 거부한다.

Local HTTP profile은 loopback에서만 별도 `aerotrace_session` 이름과 `Secure=false`를 허용한다. 이 예외가 Production profile에서 활성화되면 startup 또는 acceptance가 실패해야 한다.

Session에는 local `user_id`와 최소한의 authentication marker만 직렬화한다. 권한과 membership은 민감한 요청마다 DB에서 다시 읽는다.

### 4.4 CSRF와 Browser request

- Cookie-authenticated unsafe method에는 Spring Security CSRF를 유지한다.
- OAuth authorization request와 callback은 `state`와 PKCE로 보호한다.
- Onboarding, logout과 다른 local unsafe method에는 CSRF token을 요구한다. Logout은 POST만 허용한다.
- BFF는 Browser가 보낸 session cookie, CSRF cookie와 CSRF header 중 필요한 allowlist만 Backend에 전달한다.
- `Origin`과 `Host`는 expected public origin과 비교하고 불일치 요청을 거부한다.
- Login 후 이동 경로는 same-origin 상대 경로 allowlist만 받으며 absolute URL과 protocol-relative URL을 거부한다.
- `SameSite`는 보조 방어이며 CSRF token을 대체하지 않는다.
- 모든 session/auth 응답은 `Cache-Control: no-store`다.

### 4.5 Authorization source of truth

Backend가 default-deny로 모든 권한을 결정한다.

- Session은 사용자 신원만 증명한다.
- URL의 `tenantId`, `projectId`, invite ID와 API Key row ID는 모두 신뢰하지 않는 입력이다.
- Project 조회와 변경 전에 `projects.tenant_id`와 active membership을 같은 authorization query에서 확인한다.
- 다른 tenant의 존재를 노출하지 않도록 접근 불가 resource는 기본적으로 `404`를 반환한다.
- UI 숨김과 BFF 검사는 사용성·조기 거부용이며 Backend 검사를 대신하지 않는다.
- 권한은 모든 요청에서 확인하고 새 endpoint는 명시적으로 허용하지 않으면 거부한다.
- Invite, role/owner 변경과 Project API Key issue/revoke는 15분 이내의 최근 인증을 추가로 요구한다.

## 5. Data model

구현 단계에서 새 Flyway migration으로 다음 논리 schema를 추가한다. 이름과 제약은 migration review에서 최종 확정하지만 의미를 바꾸지 않는다.

### 5.1 `app_users`

```text
id UUID PK
display_name VARCHAR(100) NOT NULL
avatar_url TEXT NULL
status ACTIVE | DISABLED
created_at TIMESTAMPTZ
updated_at TIMESTAMPTZ
last_login_at TIMESTAMPTZ NULL
```

Email은 초기 identity와 invite에 필요하지 않으므로 저장하지 않는다.

### 5.2 `user_identities`

```text
id UUID PK
user_id UUID FK -> app_users
provider GITHUB
provider_subject VARCHAR(100) NOT NULL
provider_login VARCHAR(100) NOT NULL
created_at TIMESTAMPTZ
last_seen_at TIMESTAMPTZ
UNIQUE(provider, provider_subject)
```

`provider_login`은 표시·진단용 metadata이며 authorization key가 아니다.

### 5.3 `tenant_memberships`

```text
tenant_id UUID FK -> tenants
user_id UUID FK -> app_users
role OWNER | ADMIN | VIEWER
status ACTIVE | REVOKED
created_at TIMESTAMPTZ
updated_at TIMESTAMPTZ
revoked_at TIMESTAMPTZ NULL
PRIMARY KEY(tenant_id, user_id)
```

초기 모델은 tenant membership이 tenant 아래 모든 project에 적용된다. Project별 membership이 필요한 실제 요구가 확인되기 전에는 별도 ACL table을 추가하지 않는다.

마지막 active `OWNER`의 revoke/demotion은 transaction에서 tenant와 membership row를 잠근 뒤 기본 거부한다. 소유권 이전은 새 owner 승격 후 기존 owner 강등 순서로 수행한다.

### 5.4 `onboarding_invites`

```text
id UUID PK
tenant_id UUID FK -> tenants
role OWNER | ADMIN | VIEWER
token_hash BYTEA NOT NULL
created_by_user_id UUID NULL
created_at TIMESTAMPTZ
expires_at TIMESTAMPTZ
consumed_at TIMESTAMPTZ NULL
consumed_by_user_id UUID NULL
revoked_at TIMESTAMPTZ NULL
UNIQUE(token_hash)
```

`created_by_user_id IS NULL`은 명시적 operator bootstrap만 의미한다. 원문 token과 GitHub access token은 저장하지 않는다.

### 5.5 Session과 audit

- `AEROTRACE_SESSION`, `AEROTRACE_SESSION_ATTRIBUTES`: Spring Session JDBC 호환 table
- `security_audit_events`: login 결과, invite 생성·사용·폐기, membership과 API Key 변경의 비밀정보 없는 metadata

Audit에는 actor user ID, tenant/project ID, action, result, timestamp와 correlation ID만 허용한다. 원문 invite/API Key/OAuth token, cookie와 trace payload는 금지한다.

Full database restore 뒤 과거 session이나 아직 만료되지 않은 invite가 다시 유효해지면 안 된다. DR traffic을 열기 전에 모든 restored session을 삭제하고, 미사용 invite를 일괄 revoke한 뒤 새 bootstrap/owner invite만 명시적으로 발급한다.

## 6. Role matrix

| 동작 | OWNER | ADMIN | VIEWER |
|---|---:|---:|---:|
| Tenant와 project 목록·trace 조회 | 허용 | 허용 | 허용 |
| Project 생성·이름 변경·archive | 허용 | 허용 | 거부 |
| Project API Key metadata 조회 | 허용 | 허용 | 거부 |
| Project API Key 발급·폐기 | 허용 | 허용 | 거부 |
| VIEWER/ADMIN invite와 revoke | 허용 | 허용 | 거부 |
| OWNER 부여·강등 | 허용 | 거부 | 거부 |
| Tenant 설정·삭제 | 허용 | 거부 | 거부 |
| 자신의 session logout | 허용 | 허용 | 허용 |

초기 UI에 write 기능이 없더라도 Backend permission과 테스트를 먼저 정의한다. 역할 이름만 검사하지 않고 `actor membership + target tenant/project + action` 관계를 평가한다.

Invite, owner/role 변경과 API Key lifecycle은 role 허용만으로 충분하지 않다. Session의 `authenticated_at`이 15분을 넘으면 GitHub login flow를 다시 완료한 뒤 실행한다.

## 7. Request와 trust boundary

목표 흐름은 다음과 같다.

```text
Browser
  -> HTTPS Edge
     -> Next.js page / allowlisted Route Handler
        -> session cookie + CSRF 전달
        -> Spring Boot
           -> Spring Security session authentication
           -> active user + tenant membership authorization
           -> tenant/project-scoped JDBC query

Collector
  -> OTLP endpoint
     -> existing Project API Key authentication
     -> tenant/project-scoped ingest

GitHub
  -> exact same-origin OAuth callback
     -> Backend OAuth client through allowlisted BFF proxy
     -> local identity/session creation
```

### 7.1 BFF contract

- Auth와 application route를 정확한 allowlist로 proxy하고 임의 destination을 받지 않는다.
- Browser의 `Authorization`, `X-Forwarded-*`와 internal identity header를 그대로 신뢰하거나 전달하지 않는다.
- Backend target과 public origin은 server-only configuration으로 고정한다.
- Session/CSRF cookie, expected `Origin`, correlation ID와 필요한 content header만 전달한다.
- Backend의 `Set-Cookie`, redirect와 safe error를 필요한 범위에서만 Browser에 반환한다.
- Cookie 또는 token을 application log에 남기지 않는다.
- Query와 session response는 cache하지 않는다.

### 7.2 API 경계

구체 path는 구현 PR에서 OpenAPI/Controller test와 함께 확정하되 다음 resource shape를 사용한다.

```text
GET  /api/v1/me
GET  /api/v1/tenants
GET  /api/v1/tenants/{tenantId}/projects
GET  /api/v1/projects/{projectId}/traces
GET  /api/v1/projects/{projectId}/traces/{traceId}

POST /api/v1/onboarding/intents
POST /api/v1/logout

GET  /api/v1/projects/{projectId}/api-keys
POST /api/v1/projects/{projectId}/api-keys
POST /api/v1/projects/{projectId}/api-keys/{keyRowId}/revoke
```

기존 API Key 기반 `GET /api/v1/traces`는 local compatibility 동안 유지하고 deprecated로 표시한다. Session 기반 project route와 Frontend 전환이 끝난 뒤 사용처가 0임을 확인하고 제거 여부를 별도 결정한다.

## 8. 오류와 privacy 계약

- 인증 없음·만료 session은 API에서 `401`이다.
- 로그인했지만 role이 부족한 action은 `403`이다.
- 다른 tenant resource와 존재하지 않는 resource는 기본 `404`로 통일한다.
- CSRF 실패는 `403`과 일반화된 message를 반환한다.
- OAuth provider 장애는 `503` 또는 안전한 login failure page로 변환하고 provider token/error body를 노출하지 않는다.
- Disabled user와 revoked membership은 다음 요청부터 접근할 수 없다.
- Login, authorization 거부와 lifecycle audit에는 credential, session ID와 provider response 원문이 없어야 한다.

## 9. 구현 순서

### Phase A — Domain과 authorization 기반

1. User/identity/membership/invite/audit와 JDBC session migration
2. Membership permission service와 default-deny test
3. Existing tenant bootstrap invite operator task
4. Last-owner와 single-use invite concurrency test
5. 사용자 identity/audit/session의 보존·삭제 계약 초안

이 단계는 production login을 열지 않는다.

### Phase B — GitHub OAuth와 server session

1. Spring Security OAuth2 Client와 Spring Session JDBC
2. State, PKCE, exact callback와 minimal provider scope
3. Local principal 전환과 OAuth token 비보존
4. Session cookie, timeout, fixation, logout와 session revocation
5. `/api/v1/me`와 auth failure contract
6. Local과 Production OAuth app/callback/client secret 분리
7. Login initiation, callback failure와 onboarding intent rate limit

### Phase C — Project query와 Frontend 전환

1. Session-authenticated project trace API
2. BFF auth/session proxy와 centralized server-only authorization helper
3. Login, invite code 입력, tenant/project selector와 logout UI
4. Frontend `AEROTRACE_API_KEY` 제거
5. Existing local API Key query compatibility 확인

### Phase D — Self-service credential lifecycle

1. OWNER/ADMIN API Key list/issue/revoke API
2. 원문 한 번 표시와 browser cache/clipboard warning
3. 기존 operator task를 break-glass 경로로 유지
4. Audit, expiry warning과 rotation UI

### Phase E — 공개 self-signup 재검토

다음이 모두 검증되기 전에는 진행하지 않는다.

- Login/onboarding와 ingest rate limit
- Tenant storage/ingest quota
- Abuse monitoring과 owner notification
- Terms/privacy와 account deletion/data export
- Production-sized encrypted off-host backup/restore
- Incident session revoke와 provider outage runbook

### 구현과 함께 추가할 문서

- `AUTHENTICATION_OPERATIONS_RUNBOOK.md`: OAuth app/callback, secret rotation, bootstrap invite, session revoke, provider outage와 rollback
- `USER_DATA_RETENTION_POLICY.md`: user identity, login/audit, invite와 session metadata의 보존, export와 account deletion

두 문서는 실제 schema와 운영 명령이 확정되는 구현 PR에서 작성한다. 수동 test 절차만 별도 문서로 복제하지 않고 이 문서의 acceptance를 tracked automated test와 CI로 만든다.

## 10. Acceptance criteria

### Authentication과 session

- GitHub callback의 invalid/missing state와 PKCE verifier가 거부된다.
- Exact callback 외 redirect가 허용되지 않는다.
- Login 후 absolute/protocol-relative return URL이 거부된다.
- 실제 authorization request가 repository/organization/email scope를 포함하지 않는다.
- 새 login이 이전 anonymous session ID를 그대로 사용하지 않는다.
- Cookie가 Production에서 `Secure; HttpOnly; SameSite=Lax; Path=/`이며 `Domain`이 없다.
- Idle 8시간과 absolute 7일 경계가 test clock으로 검증된다.
- Logout과 user disable 뒤 기존 session이 재사용되지 않는다.
- Membership revoke 뒤 같은 session으로 해당 tenant에 접근할 수 없다.
- Invite, role/owner와 API Key 변경에서 15분을 넘긴 session이 재인증 없이 사용되지 않는다.
- DB/session/log/artifact credential scan에서 OAuth access/refresh token과 session ID가 검출되지 않는다.

### Onboarding과 RBAC

- Invite 원문은 한 번만 출력되고 DB에는 32-byte hash만 저장된다.
- Invite 원문이 URL, session attribute, log와 audit event에 남지 않는다.
- 만료·폐기·기사용 invite가 거부되고 concurrent consume에서 정확히 한 건만 성공한다.
- Invite 없는 신규 GitHub identity는 membership을 만들지 못한다.
- GitHub login/email 변경 뒤에도 provider subject로 같은 user를 찾는다.
- OWNER, ADMIN, VIEWER role matrix의 허용·거부 test가 있다.
- 마지막 OWNER revoke/demotion이 기본 거부된다.
- Disabled user와 revoked membership은 다음 request에서 거부된다.

### Tenant isolation

- Tenant A 사용자가 Tenant B의 추측한 tenant/project/trace/key row ID로 접근할 수 없다.
- 모든 project query가 membership과 `projects.tenant_id`를 함께 검사한다.
- Cross-tenant list/detail/cursor 재사용이 거부된다.
- BFF 검사를 우회해 Backend를 호출해도 같은 결과가 난다.

### Browser와 BFF

- 보호 page와 Route Handler가 session 없이 data를 반환하지 않는다.
- Unsafe method의 missing/wrong CSRF token과 unexpected Origin이 거부된다.
- BFF는 open proxy가 아니며 allowlist 밖 Backend path/header를 전달하지 않는다.
- Frontend runtime과 image에 `AEROTRACE_API_KEY`가 필요하지 않다.
- Response는 session·trace data를 shared cache에 저장하지 않는다.

### 회귀와 운영

- 기존 OTLP Project API Key ingest와 operator lifecycle test가 그대로 통과한다.
- Backend 전체 test와 ephemeral TimescaleDB auth/onboarding acceptance가 CI에서 통과한다.
- Frontend login/project selection route test, lint와 production build가 통과한다.
- Backup/restore acceptance가 새 security table과 aggregate 검증을 포함한다.
- DR restore 뒤 restored session과 미사용 invite가 모두 무효화된다.
- Production activation 전 auth/onboarding runbook, rollback과 secret rotation 절차가 있다.

## 11. 명시적 비목표

- 자체 password database와 password reset
- 공개 self-signup과 자동 tenant 생성
- SAML, enterprise SSO와 SCIM
- Project별 세분화 ACL
- API Key를 Browser session이나 사용자 token으로 재사용
- GitHub repository/organization data 접근
- OAuth access token의 장기 저장 또는 refresh
- 구현 전 production OAuth app, secret, session table과 route 변경

## 12. 재검토 조건

- GitHub 계정이 없는 실제 사용자 요구
- Enterprise OIDC/SAML 또는 self-hosted deployment의 provider 독립성 요구
- 한 tenant 안에서 project별 접근 분리가 필요할 때
- 다중 instance에서 JDBC session 부하나 cleanup이 병목이 될 때
- 공개 self-signup, billing 또는 automated tenant provisioning 도입
- 별도 managed IdP가 단일 운영자 부담보다 유리해질 때

## 13. 근거

- [Spring Security Authentication](https://docs.spring.io/spring-security/reference/servlet/authentication/index.html)
- [Spring Security Session Management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
- [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Spring Session JDBC](https://docs.spring.io/spring-session/reference/guides/boot-jdbc.html)
- [Next.js Authentication Guide](https://nextjs.org/docs/app/guides/authentication)
- [GitHub OAuth Web Application Flow](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps)
- [GitHub OAuth App Scopes](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps)
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)
- [OWASP Authorization Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html)
