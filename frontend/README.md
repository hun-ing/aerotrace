# AeroTrace Frontend

GitHub 로그인과 초대 전용 onboarding을 사용하는 Next.js Trace explorer다. 사용자와 프로젝트의 최종 권한은 Backend가 매 요청 확인한다. Frontend에는 공용 Project API Key가 필요하지 않다.

## 기능

- GitHub 로그인, 초대 코드 입력, CSRF를 포함한 POST logout
- 현재 사용자, 조직·프로젝트 선택과 권한 없는 리소스의 404 처리
- UTC 시간 범위·service·error·최소 duration filter, cursor pagination
- Trace 목록과 span timeline/detail
- 세션 만료, 권한 회수, 조직/프로젝트 없음, Backend 장애의 안전한 상태 표시

## 로컬 실행

Node.js 22 이상, 실행 중인 인증 활성화 Backend, 별도의 Local GitHub OAuth App과 첫 OWNER 초대가 필요하다. 운영 절차와 callback 설정은 [인증 Runbook](../AUTHENTICATION_OPERATIONS_RUNBOOK.md)의 7·8·14절을 따른다.

```bash
cd frontend
cp .env.example .env.local
chmod 600 .env.local
npm ci
npm run dev
```

```text
AEROTRACE_BACKEND_BASE_URL=http://127.0.0.1:8080
AEROTRACE_PUBLIC_ORIGIN=http://127.0.0.1:3000
AEROTRACE_AUTH_ALLOW_INSECURE_LOCAL=true
```

브라우저도 정확히 <http://127.0.0.1:3000>으로 연다. Backend의 public origin과 OAuth App callback은 같은 Frontend origin을 사용해야 한다. `localhost`와 `127.0.0.1`을 섞지 않는다.

Production은 `AEROTRACE_PUBLIC_ORIGIN=https://<approved-host>`, insecure flag는 `false`, Backend는 `auth-production`이다. 위 값은 모두 server-only이며 `NEXT_PUBLIC_` 이름으로 바꾸지 않는다. OAuth secret은 Backend에만 주입한다. Compose에서는 root `frontend.env`를 읽고 Backend URL을 internal service URL로 지정한다.

## 요청·보안 계약

- 허용된 `/api/v1/...`와 두 GitHub OAuth 경로만 Backend에 중계한다. Endpoint 표는 [Runbook](../AUTHENTICATION_OPERATIONS_RUNBOOK.md)의 8절이 기준이다.
- 요청에서는 현재 환경의 session cookie 하나만 전달한다. Browser Authorization·Forwarded·X-Forwarded-*·identity header는 전달하지 않는다.
- Host는 설정한 public host와 일치해야 한다. 상태 변경에는 exact Origin과 Backend가 검증하는 CSRF가 필요하다.
- Production cookie는 `__Host-aerotrace_session; Secure; HttpOnly; SameSite=Lax; Path=/`, Domain 없음. 명시적 HTTP loopback만 local cookie를 사용한다.
- Backend 요청은 5초 timeout·redirect manual·no-store. JSON 응답은 최대 8 MiB, SSR metadata는 1 MiB, POST body는 4 KiB다.
- 잘못된 초대 POST도 CSRF 검증을 거쳐 이전 Backend intent를 제거하도록 빈 invite sentinel만 전달한다. 잘못된 원문·초과 body는 중계하지 않는다.
- OAuth 시작은 GitHub authorization URL, callback 성공은 `/`만 허용한다. 실패는 고정 로그인 오류 화면으로 이동하며 code/token을 표시하거나 log에 남기지 않는다.
- SSR은 server-only helper로 Backend를 직접 조회한다. 조직·프로젝트 전환과 logout은 전체 문서 이동으로 이전 cursor·선택한 Trace·client cache를 버린다.
- 기존 Frontend `/api/traces`는 제거됐다. Backend의 API Key 기반 `/api/v1/traces`는 local/legacy 호환용으로만 유지한다.
- `/health`는 Frontend 프로세스 상태만 나타낸다. Backend 인증 설정이나 사용자 로그인 성공의 증거가 아니다.

## 자동 검증

```bash
npm ci
npm audit --audit-level=high
npm run lint
npm run build
npm test
```

`npm test`는 먼저 빌드한 Next.js 서버와 fake Backend를 임시 loopback port에서 실행한다. 실제 OAuth provider·운영 DB·API Key는 사용하지 않는다. Cookie 회전/삭제, Host/Origin/CSRF, proxy allowlist, cross-user SSR 격리, 숨겨진 프로젝트, 오류 상태를 실제 HTTP 경로로 검증한다. Frontend CI에도 포함돼 있다.

Turbopack이 제한된 환경의 로컬 port binding 때문에 실패할 때는 `npm run build -- --webpack`으로 별도 빌드 검증할 수 있다. 기본 CI는 표준 build를 유지한다.

## 배포 경계

Phase C는 repository 구현이다. 기존 Production Frontend image·환경 파일·OAuth profile을 자동 변경하지 않는다. **이 Frontend를 단독 배포하면 기존 API Key dashboard로 돌아가지 않으며, 인증 Backend가 준비되지 않은 경우 조회할 수 없다.** 실제 OAuth E2E, TLS/edge 제한, rollback·backup 승인을 묶어 별도 활성화한다.
