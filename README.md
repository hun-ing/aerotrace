# AeroTrace

AeroTrace는 OpenTelemetry trace를 수집·저장·조회하고 운영 알림까지 연결하는 소규모 서비스용 APM 프로젝트다. 복잡한 분산 시스템을 먼저 도입하기보다 filesystem queue, PostgreSQL/TimescaleDB와 측정 가능한 운영 절차로 신뢰성을 단계적으로 검증한다.

> 현재 단계: self-hosted MVP와 단일 운영자 production 검증 단계다. 사용자 로그인, 공개 SaaS tenant onboarding과 API Key 관리 UI는 아직 없으므로 인터넷에 그대로 공개하는 완성형 SaaS가 아니다.

## 현재 구현 범위

- OTLP/gRPC·OTLP/HTTP 수신과 OpenTelemetry Collector persistent queue
- JSON OTLP trace ingest, Project API Key 인증과 tenant/project 격리
- 기존 project 전용 Project API Key 조회·replacement·폐기 operator lifecycle
- TimescaleDB 저장, Flyway migration, JDBC batch insert와 중복 억제
- 시간·service·error·최소 duration filter와 cursor 기반 trace 조회
- Next.js BFF를 통한 trace 목록과 span timeline UI
- Collector queue 상태 평가와 filesystem notification outbox
- HMAC Webhook → Cloudflare Worker/D1/Queue → private Slack 알림
- Retry/DLQ, receiver health, UptimeRobot email fallback과 rolling SLI

## 구조

```text
OTLP client
  -> OpenTelemetry Collector :4317/:4318
     -> persistent file queue
     -> Spring Boot /v1/traces
        -> Project API Key authentication
        -> TimescaleDB

Browser
  -> Next.js :3000
     -> server-only BFF + Project API Key
     -> Spring Boot /api/v1/traces

Collector queue evaluator
  -> filesystem notification outbox
  -> HMAC Webhook sender
  -> Cloudflare Worker -> D1 -> Queue -> Slack
                         -> /health -> UptimeRobot email
```

Notification receiver의 HTTP 2xx는 D1 claim과 Queue write 완료를 뜻하며 Slack 화면 표시 완료나 exactly-once를 뜻하지 않는다. 자세한 보장 수준은 [Webhook receiver contract](WEBHOOK_RECEIVER_CONTRACT.md)를 따른다.

## 기술 스택

| 영역 | 구성 |
|---|---|
| Backend | Java 21, Spring Boot 4.1, Spring Web MVC, Virtual Threads, JDBC, Flyway |
| Storage | PostgreSQL 15, TimescaleDB 2.28 |
| Telemetry | OpenTelemetry Collector Contrib 0.157, OTLP/gRPC, OTLP/HTTP |
| Frontend | Next.js 16, React 19, TypeScript, Tailwind CSS 4 |
| Notification | Python filesystem outbox, HMAC-SHA256, Cloudflare Workers/D1/Queues, Slack |
| Runtime | Docker Compose, systemd notification timers |

## 저장소 구성

```text
backend/                     Spring Boot ingest/query/auth application
frontend/                    Next.js trace explorer and server-only BFF
receiver/cloudflare-slack/   Durable Slack Webhook receiver
scripts/                     Runtime, benchmark, notification and DB recovery tools
tests/                       Notification regressions and DB recovery fixtures
deploy/systemd/              Notification evaluator/outbox units and timers
benchmark-results/           Reproducible measurement evidence
```

## 로컬 통합 실행

### 요구 사항

- Docker Engine과 Docker Compose plugin
- 전체 host 개발을 할 때 Java 21과 Node.js 22 이상
- 실제로 발급된 `atr_` 형식의 Project API Key

### 1. 환경 파일 준비

Linux/macOS 예시:

```bash
cp .env.example .env
cp otel-collector.env.example otel-collector.env
cp frontend.env.example frontend.env
chmod 600 .env otel-collector.env frontend.env
```

Placeholder를 실제 local 값으로 바꾼다.

```text
.env
  TimescaleDB database name, username and password

otel-collector.env
  Backend endpoint and Project API Key

frontend.env
  Next.js BFF가 사용할 같은 Project API Key
```

Collector와 Frontend에는 DB에 등록된 동일 Project API Key를 넣어야 한다. 원문 Key는 발급 시 한 번만 표시되고 DB에는 hash만 저장된다. Secret 파일과 발급 결과는 Git, issue, terminal command argument에 남기지 않는다.

### 2. 최초 Project API Key 발급

이 단계는 새 DB에 tenant/project/key가 아직 없을 때 한 번 수행한다. 먼저 TimescaleDB만 시작한다.

```bash
docker compose -f docker-compose.yaml up -d timescaledb
```

Java 21이 설치된 host에서 `.env`와 같은 DB 이름·사용자를 입력하고, DB password는 화면에 표시되지 않는 prompt로 받는다. 아래 tenant/project 값은 local 예시이며 slug는 소문자 영문·숫자·하이픈만 사용할 수 있다.

```bash
(
  cd backend
  export AEROTRACE_DB_URL='jdbc:postgresql://localhost:5432/aerotrace?reWriteBatchedInserts=true'
  export AEROTRACE_DB_USERNAME='postgres'
  read -rsp 'AeroTrace DB password: ' AEROTRACE_DB_PASSWORD
  echo
  export AEROTRACE_DB_PASSWORD
  export AEROTRACE_PROVISION_TENANT_NAME='AeroTrace Local'
  export AEROTRACE_PROVISION_TENANT_SLUG='aerotrace-local'
  export AEROTRACE_PROVISION_PROJECT_NAME='AeroTrace Local'
  export AEROTRACE_PROVISION_PROJECT_SLUG='aerotrace-local'
  export AEROTRACE_PROVISION_API_KEY_NAME='local-runtime'
  export AEROTRACE_PROVISION_API_KEY_EXPIRATION_DAYS='365'
  bash ./gradlew provisionProjectApiKey
)
```

출력의 `AEROTRACE_PROVISIONED_API_KEY` 원문을 즉시 `otel-collector.env`와 `frontend.env`의 `AEROTRACE_API_KEY`에 각각 저장한다. 다른 출력 ID는 진단용 metadata이며 Key 원문을 별도 문서나 shell history에 복사하지 않는다. 같은 tenant/project slug는 같은 이름일 때 재사용하지만, 같은 이름의 활성 API Key가 있으면 중복 발급하지 않고 실패한다.

기존 project의 Key 목록, 무중단 replacement 발급, 이전 Key 폐기와 긴급 폐기는 [Project API Key Lifecycle Runbook](PROJECT_API_KEY_RUNBOOK.md)을 따른다. Rotation용 `manageProjectApiKeys`는 기존 tenant/project만 허용하며 새 tenant/project를 만들지 않는다.

### 3. 구성 검사와 시작

```bash
docker compose \
  -f docker-compose.yaml \
  -f docker-compose.app.yaml \
  --profile app \
  config --quiet

docker compose \
  -f docker-compose.yaml \
  -f docker-compose.app.yaml \
  --profile app \
  up -d --build --remove-orphans
```

Windows PowerShell에서는 다음 helper를 사용할 수 있다.

```powershell
.\scripts\runtime\aerotrace.ps1 Up
```

### 4. 확인

| 대상 | 주소 |
|---|---|
| Trace dashboard | <http://localhost:3000> |
| Backend health | <http://localhost:8080/actuator/health> |
| OTLP/gRPC | `localhost:4317` |
| OTLP/HTTP | <http://localhost:4318> |
| Collector metrics | <http://localhost:8888/metrics> |
| PostgreSQL | `localhost:5432` |

```bash
curl --fail --silent --show-error \
  http://localhost:8080/actuator/health

docker compose \
  -f docker-compose.yaml \
  -f docker-compose.app.yaml \
  --profile app \
  ps -a
```

`aerotrace-otel-storage-init`이 `Exited (0)`인 것은 persistent queue 디렉터리 권한 설정을 마친 정상 상태다.

### 5. 종료

```bash
docker compose \
  -f docker-compose.yaml \
  -f docker-compose.app.yaml \
  --profile app \
  down --remove-orphans
```

Named volume을 보존하려면 `down -v`를 사용하지 않는다. 자세한 재시작·로그·데이터 보존 절차는 [Local Runtime Runbook](LOCAL_RUNBOOK.md)에 있다.

## 개발 검증

Backend:

```bash
cd backend
bash ./gradlew test
```

Frontend:

```bash
cd frontend
npm ci
npm run lint
npm run build
```

Notification sender와 SLI:

```bash
PYTHONDONTWRITEBYTECODE=1 \
  python3 -m unittest discover -s tests -v
```

Cloudflare Slack receiver:

```bash
cd receiver/cloudflare-slack
npm ci
npm test
npm run check
npm run deploy:dry-run
npm run db:migrate:local
```

`deploy:dry-run`과 local D1 migration은 remote Cloudflare resource를 변경하지 않는다. Production deploy, D1 mutation, secret rotation과 systemd 설치는 operator 승인 작업이다.

## 운영 및 설계 문서

| 문서 | 역할 |
|---|---|
| [Project Context](AEROTRACE_CONTEXT.md) | 현재 단계, architecture와 다음 작업 |
| [Decisions](DECISIONS.md) | 채택한 기술·운영 결정과 trade-off |
| [Engineering Log](ENGINEERING_LOG.md) | 구현·실험·검증 evidence |
| [Career Log](CAREER_LOG.md) | 검증된 성과와 과장하지 않을 범위 |
| [Local Runbook](LOCAL_RUNBOOK.md) | 로컬/통합 runtime과 데이터 보존 |
| [Project API Key Lifecycle Runbook](PROJECT_API_KEY_RUNBOOK.md) | 기존 project의 Key 조회, 교체, 폐기와 침해 대응 |
| [User Authentication and Onboarding Design](USER_AUTH_ONBOARDING_DESIGN.md) | GitHub OAuth, server session, invite-only onboarding과 tenant RBAC 구현 계약 |
| [Database Backup/Restore Runbook](DATABASE_BACKUP_RESTORE_RUNBOOK.md) | TimescaleDB backup, 빈 target 복원과 DR 경계 |
| [Webhook Receiver Contract](WEBHOOK_RECEIVER_CONTRACT.md) | Payload, HMAC, HTTP, dedup 계약 |
| [Notification SLO](NOTIFICATION_SLO.md) | Delivery 경계, threshold와 error budget |
| [Notification Operations Runbook](OPERATIONS_RUNBOOK.md) | 장애 확인, retry, rollback과 복구 |
| [Notification Operations Review](NOTIFICATION_OPERATIONS_REVIEW.md) | 첫 7일·30일 운영 측정 결과 |
| [Incident Template](NOTIFICATION_INCIDENT_TEMPLATE.md) | Private incident 기록 양식 |
| [Data Retention Policy](DATA_RETENTION_POLICY.md) | Payload redaction과 보존 목표 |
| [Security Policy](SECURITY.md) | Vulnerability report 경로와 범위 |
| [Cloudflare Slack Receiver](receiver/cloudflare-slack/README.md) | Receiver 배포·검증·recovery |

## 알려진 제한

- Frontend는 현재 server-side Project API Key 하나를 사용하며 사용자 로그인·세션이 없다. 다음 구현 경계는 [User Authentication and Onboarding Design](USER_AUTH_ONBOARDING_DESIGN.md)에 채택했지만 아직 적용되지 않았다.
- Project/API Key self-service onboarding, rotation UI와 expiry alert가 없으며 lifecycle은 operator task로 수행한다.
- 기본 Compose는 개발 편의를 위해 서비스 port를 host에 게시하므로 firewall, TLS와 접근 제어 없이 public network에 배포하면 안 된다.
- Slack delivery는 at-least-once이며 provider timeout에서 사용자-visible duplicate가 가능하다.
- Notification 운영은 단일 owner 구조이며 24x7 SLA가 아니다.
- Receipt/D1 metadata retention은 초기 policy target이고 자동 purge는 아직 없다.
- TimescaleDB backup/restore는 ephemeral acceptance만 완료됐고 production-sized/off-host backup은 아직 없다.
- Eligible production notification이 아직 없어 첫 30일 SLO는 `NO_DATA` calibration 상태다.

보안 문제는 공개 issue에 세부 정보를 올리지 말고 [Security Policy](SECURITY.md)의 private report 절차를 사용한다.
