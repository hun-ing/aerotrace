# AeroTrace Frontend

Next.js 기반 trace explorer다. Browser가 Spring Boot에 Project API Key를 직접 보내지 않도록 server-only BFF route가 Backend trace API를 호출한다.

## 기능

- UTC 시간 범위 기반 trace 조회
- Service name exact match, error-only와 최소 span duration filter
- Cursor pagination과 추가 결과 로드
- Trace별 span 수, service 수와 최대 duration 요약
- 선택한 trace의 span timeline과 status/detail 표시
- Backend 오류·timeout·잘못된 JSON의 안전한 BFF 응답 변환

## 요구 사항

- Node.js 22 이상
- 실행 중인 AeroTrace Backend
- DB에 등록된 `atr_` 형식 Project API Key

## 환경 설정

```bash
cd frontend
cp .env.example .env.local
chmod 600 .env.local
```

```text
AEROTRACE_BACKEND_BASE_URL=http://localhost:8080
AEROTRACE_API_KEY=<provisioned-project-api-key>
```

두 값은 server-only 환경변수다. `NEXT_PUBLIC_` 이름으로 바꾸거나 Browser bundle, Git, issue와 screenshot에 원문 API Key를 노출하지 않는다.

Docker 통합 runtime은 repository root의 `frontend.env`에서 Key를 읽고 Backend URL을 Compose service name으로 덮어쓴다.

## 실행

```bash
npm ci
npm run dev
```

<http://localhost:3000>에서 확인한다.

## 검증

```bash
npm run lint
npm run build
```

## BFF route

```text
GET /api/traces
  -> GET <backend>/api/v1/traces

GET /api/traces/{traceId}
  -> GET <backend>/api/v1/traces/{traceId}
```

BFF는 허용된 query parameter만 전달하고 Backend 요청에 `Authorization: Bearer <Project API Key>`를 추가한다. Response는 `no-store`이며 Backend 요청 timeout은 5초다.

## 현재 보안 경계

현재 Frontend에는 사용자 로그인과 session이 없다. 정상 운영에서는 서버가 하나의 runtime Project API Key를 사용하고, [operator rotation](../PROJECT_API_KEY_RUNBOOK.md) 중에만 기존·신규 Key를 짧게 겹쳐 허용한다. 따라서 local 개발, private deployment 또는 접근이 제한된 PoC에만 적합하며 공개 SaaS 인증·tenant 선택 구조로 사용하지 않는다.

GitHub OAuth, PostgreSQL server session, invite-only onboarding, tenant role과 Project API Key의 workload-only 경계는 [User Authentication and Onboarding Design](../USER_AUTH_ONBOARDING_DESIGN.md)에 채택했다. Backend의 Phase A schema·authorization·bootstrap 기반만 구현됐으며, 현재 Frontend가 인증됐다는 의미가 아니다.

전체 runtime과 운영 문서는 [repository root README](../README.md)를 따른다.
