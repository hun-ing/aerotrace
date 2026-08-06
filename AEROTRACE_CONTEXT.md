# AeroTrace 프로젝트 컨텍스트

> 마지막 업데이트: 2026-08-04  
> 현재 상태: Phase 8 웹 대시보드 MVP 완료  
> 현재 Phase: Phase 9 — 로컬 통합 실행 및 배포 준비  
> 다음 작업: 현재 Docker Compose와 환경변수 구성을 점검하고 Backend, Frontend, TimescaleDB, OpenTelemetry Collector의 통합 실행 절차를 확정한다.

---

## 1. 프로젝트 개요

AeroTrace는 OpenTelemetry 기반의 초경량 멀티테넌트 APM 서비스다.

초기 대상은 상용 APM의 비용이나 운영 복잡도가 부담스러운 다음 사용자다.

- 사이드 프로젝트 개발자
- 개인 서비스 운영자
- 소규모 서비스 운영자
- 소규모 개발팀
- 사내 PoC 대상 서비스

초기에는 SaaS로 제공하고, 이후 동일 코드베이스로 온프레미스 배포를 지원하는 것을 목표로 한다.

성공 기준은 기술을 많이 사용하는 것이 아니라 실제 문제를 해결하고, 직접 실행하고, 측정하고, 장애를 재현하고, 개선 근거를 남기는 것이다.

## 2. 최종 목표

1. 실제 사용자가 사용할 수 있는 안정적인 SaaS MVP 완성
2. 소규모 서비스를 위한 단순한 Trace 중심 APM 제공
3. 동일 코드베이스의 온프레미스 배포 지원
4. 사내 서비스에 PoC와 도입을 제안할 수 있는 수준 달성
5. 백엔드 개발자 이직에 활용할 수 있는 포트폴리오 구축
6. 기술 블로그, GitHub, LinkedIn에 공유할 근거와 자료 축적
7. OpenTelemetry, APM, 관측 가능성, 성능 측정, 장애 대응 경험 확보

## 3. 현재 기술 스택

### Backend

- Java 21
- Spring Boot 4.1.0
- Spring Web MVC
- Virtual Threads
- Spring JDBC
- Flyway
- HikariCP
- Micrometer / Actuator
- Gradle

Telemetry 저장과 조회 hot path는 Spring JDBC를 사용한다. Spring Data JPA는 Tenant, Project, 사용자, API Key 관리 같은 control-plane 기능이 커질 때 검토한다.

### Database

- PostgreSQL 15.18
- TimescaleDB 2.28.3
- Hypertable
- Rowstore / Hypercore Columnstore
- JSONB
- TimescaleDB background policy

### Telemetry

- OTLP/HTTP JSON Trace 수신
- OpenTelemetry Collector Contrib 0.157.0
- Collector batch processor
- OTLP HTTP JSON exporter
- File storage 기반 persistent sending queue
- Collector 내부 Prometheus metric endpoint

Metrics와 Logs 수신은 아직 구현하지 않았다.

### Frontend

- Node.js 24
- Next.js App Router
- TypeScript
- Tailwind CSS
- ESLint
- `src` 디렉터리 구조
- Next.js Route Handler 기반 서버 전용 BFF

### 실행 환경

- Windows 개발 PC
- Docker Desktop
- Docker Compose
- 향후 Oracle Cloud 무료 인스턴스
- 향후 N100, RAM 16GB, SSD 512GB 홈서버

## 4. 현재 시스템 구조

### Telemetry 수집 경로

```text
Instrumented Application
        │ OTLP
        ▼
OpenTelemetry Collector
        │ batch + retry
        │ persistent sending queue
        │ OTLP/HTTP JSON
        ▼
AeroTrace Spring Boot Backend
        │ API Key 인증
        │ OTLP 검증
        │ 요청 단위 transaction
        │ JDBC batch
        ▼
TimescaleDB
```

### 사용자 조회 경로

```text
Browser
   │ same-origin HTTP
   ▼
Next.js App Router
   │ server-only Route Handler
   │ Project API Key 주입
   ▼
AeroTrace Spring Boot Query API
   │ tenant_id + project_id 강제
   ▼
TimescaleDB
```

브라우저는 Spring Boot에 직접 API Key를 전달하지 않는다. Next.js 서버가 로컬 환경변수의 Project API Key로 Backend를 호출한다.

현재 Frontend에는 사용자 로그인과 세션이 없으므로 로컬 개발과 접근이 제한된 PoC에만 적합하다. 공개 SaaS 인증 구조로 사용하면 안 된다.

## 5. 데이터 소유 구조와 보안 경계

```text
Tenant
└── Project
    ├── Project API Key
    └── Service
        └── Trace
            └── Span
```

- Tenant는 개인, 팀, 회사의 데이터 소유 경계다.
- Project는 Tenant 내부 telemetry 구분 단위다.
- 하나의 Project에 여러 `service.name`이 포함될 수 있다.
- 모든 Span은 `tenant_id`, `project_id`에 귀속된다.
- 클라이언트 Tenant / Project Header를 신뢰하지 않는다.
- 인증된 API Key의 DB 소유권으로 Tenant와 Project를 결정한다.
- 목록과 상세 SQL은 항상 `tenant_id`, `project_id`를 함께 조건으로 사용한다.
- DB 복합 외래키가 잘못된 소유 관계 저장을 최종 차단한다.

## 6. Backend 패키지 방향

```text
com.huning.aerotrace
├── ingest
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── tenant
├── query
└── common
```

하나의 Spring Boot 애플리케이션으로 시작하는 모듈형 모놀리스다. Kafka, Kubernetes, Elasticsearch, 초기 마이크로서비스는 현재 요구사항과 측정 결과로 정당화되지 않아 도입하지 않는다.

## 7. 데이터베이스 Migration 상태

### V1 — TimescaleDB 확장

- TimescaleDB extension
- Flyway schema history

### V2 — Tenant와 Project

- `tenants`
- `projects`
- Tenant slug 유일성
- Tenant 내부 Project slug 유일성
- Project 존재 시 Tenant 삭제 제한

### V3 — Span hypertable

- `spans`
- `start_time` 기준 Hypertable
- 1일 Chunk
- Tenant / Project 복합 외래키
- Trace ID / Span ID / 시간 / duration / JSONB 제약조건
- 중복 방지 Unique Index
- 최근 조회와 Trace 조회 인덱스

### V4 — Span dropped count

- `dropped_attributes_count`
- `dropped_events_count`
- `dropped_links_count`
- OTLP `uint32` 범위

### V5 — Project API Key

- `project_api_keys`
- `key_id`
- `secret_hash`
- 만료 시각
- 폐기 시각
- Tenant / Project 소유 관계

### V6 — Columnstore 설정

- Segment: `tenant_id, project_id`
- Order: `start_time DESC`

### V7 — Columnstore 정책

- 2일보다 오래된 완성 Chunk 자동 전환

### V8 — Retention 정책

- `start_time` 기준 30일 초과 Chunk 자동 삭제

## 8. 수집 API

```text
POST /v1/traces
Content-Type: application/json
Authorization: Bearer <project-api-key>
```

### 지원 범위

- Resource
- Instrumentation Scope
- Span
- Resource / Span Attributes
- Events
- Links
- AnyValue: string, boolean, integer, double, bytes, array, nested key-value

### 주요 검증

- Trace ID 32자리 16진수
- Span ID 16자리 16진수
- all-zero ID 거부
- Parent Span ID 검증
- `service.name` 필수
- 시작 / 종료 시각
- Span kind / Status code
- OTLP `uint32`
- Attribute key와 중복 key
- AnyValue 값 종류
- Base64 bytes
- Event / Link 구조
- 요청 전체 원자성

### 요청 제한

```yaml
aerotrace:
  ingest:
    max-spans-per-request: 5000
    max-request-body-bytes: 10485760
    jdbc:
      batch-size: 1000
```

- 요청당 최대 5,000 Span
- 요청 본문 최대 10 MiB
- 제한 초과: 413
- 잘못된 요청: 400
- 지원하지 않는 Content-Type: 415
- 인증 실패: 401
- 일시적 DB 장애: 503
- 성공: `{}`

## 9. 저장 경로

```text
OTLP JSON
→ Parsing / Validation
→ ParsedSpan
→ Request Transaction
→ JSON Serialization
→ JDBC Chunk Batch
→ INSERT ... ON CONFLICT DO NOTHING
→ TimescaleDB
```

중복 식별 기준:

```text
tenant_id
project_id
trace_id
span_id
start_time
```

일반 컬럼에는 자주 조회하는 식별자, 서비스, Span 이름, Kind, Status, 시간, duration을 저장한다. Resource Attributes, Span Attributes, Events, Links는 JSONB에 저장한다.

## 10. API Key와 멀티테넌시

API Key 형식:

```text
atr_<16-character-key-id>.<43-character-secret>
```

- 발급 시에만 원문 반환
- 원문 Secret 미저장
- `key_id` 저장
- Secret SHA-256 hash 저장
- 상수시간 비교
- 만료 / 폐기 지원
- Unknown Key에도 Dummy Hash 비교
- 민감정보를 로그와 Metric Tag에 사용하지 않음

검증 완료:

- 정상 Key
- Header 누락
- 잘못된 Bearer Header
- 잘못된 Key 형식
- Unknown Key
- Secret mismatch
- 만료 / 폐기
- Tenant / Project Header 위조 무시
- 인증 Project에 저장
- Project 간 동일 Trace ID 조회 격리

## 11. Collector와 장애 복구

### 구성

- OTLP Receiver
- Batch Processor
- OTLP HTTP JSON Exporter
- Bearer API Key
- Retry
- `file_storage`
- Docker Named Volume
- Queue Consumer 2
- Queue Capacity 50,000 Span
- `block_on_overflow: false`

### 100 Span 장애 복구

- DB 중지
- Collector에 100 Span 전송
- Backend 503
- Queue Size 100
- Collector 재시작
- Queue Metadata 복구
- DB 복구
- 자동 재전송
- 최종 DB 100행
- 고유 Span ID 100
- Queue Size 0
- 최종 중복과 유실 관찰되지 않음

### 10,000 Span Queue 수용

- Capacity 50,000
- 장애 중 Queue Size 10,000
- Enqueue Failure 없음 또는 0
- 복구 후 Queue Size 0

10,000 Span 실험의 최종 DB 행 수와 고유 Span ID 수는 원본 출력이 남아 있지 않아 완료로 단정하지 않는다.

### 미검증

- Queue 50,000 초과
- Overflow Drop 수량
- 디스크 공간 부족
- File Storage 쓰기 오류
- 호스트 강제 종료 / 전원 차단
- Queue Drain 처리량
- Span당 Queue Disk 사용량

## 12. 데이터 수명주기

```text
0~2일
→ Rowstore

2~30일
→ Columnstore

30일 초과
→ Retention Chunk 삭제
```

검증 완료:

- 4일 전 Span의 Rowstore → Columnstore 전환
- 전환 후 기존 Hypertable 조회
- 최근 Span Rowstore 유지
- 35일 전 테스트 Chunk 삭제 후보 사전 검사
- Retention 수동 실행
- 35일 전 데이터 삭제
- 4일 전과 최근 데이터 보존
- 정책 자동 스케줄 복구

미측정:

- 대량 데이터 압축률
- 전환 전후 조회 성능
- 일일 DB 증가량
- 30일 예상 저장 크기
- Policy Job 실행 시간과 경보

## 13. Trace Query API

### 목록

```text
GET /api/v1/traces
```

파라미터:

- `from`
- `to`
- `limit`
- `cursor`
- `serviceName`
- `errorOnly`
- `minSpanDurationNano`

제한:

- 기본 Limit 50
- 최대 Limit 200
- 최대 기간 30일
- Service Exact Match
- Duration 0 이상

집계:

- Trace 시작 시각
- Span 수
- 고유 Service 수
- 가장 긴 Span duration

필터는 Trace 포함 여부에 적용하지만 집계값은 Trace 전체 Span을 기준으로 한다.

### 상세

```text
GET /api/v1/traces/{traceId}
```

- 인증 Project 범위 강제
- 시작 시각 순서
- 최대 5,000 Span
- Invalid ID: 400
- Not Found: 404
- Too Large: 422

### Cursor

정렬:

```text
trace_start_time DESC
trace_id DESC
```

Cursor:

- 마지막 Trace 시작 시각
- 마지막 Trace ID
- 조회 조건 Fingerprint

Fingerprint에는 Tenant, Project, 기간, Service, Error, Minimum Duration이 포함된다. 조건이 다른 요청에서 재사용하면 400이다.

## 14. Trace 조회 성능

테스트 데이터:

- Trace 20,000
- Span 109,998
- Trace당 3~8 Span
- Service 8
- Error Trace 2,000
- 50ms 이상 Trace 1,000
- 250ms 이상 Trace 200

| 조건 | 중앙 실행시간 |
|---|---:|
| 필터 없음 | 94.006ms |
| Service | 104.686ms |
| Error | 108.596ms |
| 250ms Duration | 97.209ms |
| 복합 필터 | 95.811ms |
| Cursor 두 번째 페이지 | 106.646ms |

후보 Trace 우선 SQL:

| 후보 | 중앙 실행시간 |
|---:|---:|
| 200 / 1% | 19.819ms |
| 1,000 / 5% | 34.795ms |
| 20,000 / 100% | 345.467ms |

동일 100% 조건의 기존 SQL은 98.048ms였다.

현재 결정:

- Raw Span 전체 집계 SQL 유지
- 후보 우선 SQL 자동 분기 보류
- Duration Index 보류
- Trace Summary 조기 도입 보류

## 15. Frontend Trace Explorer

구현 완료:

- Next.js App Router Dashboard
- Trace 목록 / 상세 서버 전용 BFF
- Backend URL과 API Key 서버 환경변수
- Browser API Key 미노출
- Loading / Success / Empty / Error
- 최근 7일 기본 조회
- From / To
- Service Exact Match
- Error Only
- Minimum Span Duration
- ms → ns 변환
- 30일 초과 차단
- URL Query 반영과 새로고침 복원
- Cursor Load More
- 추가 페이지 실패 시 기존 목록 유지
- 중복 Trace / 동일 Cursor 감지
- Trace 상세 Runtime Validation
- Span Kind / Status / Scope / 시간 / Duration
- Root / Parent Span
- 상대 Timeline
- Error Span 시각적 구분
- 상세 Panel 자동 이동

실제 다중 Span 검증:

- Span 3
- Service 2
- Root 200ms, Server, OK
- DB Child 50ms, Client, OK
- Worker Child 80ms, Consumer, Error
- Error Message: `simulated verification failure`

확인:

- 목록 Span Count 3
- Service Count 2
- Longest Span 200ms
- Root / Parent 관계
- Error 상태와 색상
- 상대 위치와 길이

현재 보안 한계:

- 사용자 로그인 / 세션 없음
- 서버당 하나의 Project API Key
- 사용자별 Project 선택 없음
- Frontend 접근자는 설정된 Project 데이터를 조회 가능

## 16. Phase 완료 현황

- Phase 1: Backend 기반 — 완료
- Phase 2: 데이터 모델 — 완료
- Phase 3: OTLP Trace 수집 — 완료
- Phase 4: 수집 성능과 제한 — 완료
- Phase 5: API Key와 멀티테넌시 — 완료
- Phase 6: Collector, 장애 복구, 데이터 수명주기 — 완료
- Phase 7: Trace Query API와 성능 분석 — 완료
- Phase 8: 웹 대시보드 MVP — 완료
- Phase 9: 로컬 통합 실행 및 배포 준비 — 시작

## 17. 현재 기술 부채

### 보안

- 사용자 로그인 / 세션
- 사용자 / 조직 / 멤버십 / 역할
- API Key 관리 UI와 Rotation
- Rate Limit
- Tenant Quota
- Actuator 보호
- Collector Receiver 보호
- 운영 Secret 관리

### 운영

- 통합 Compose 미완성
- Reverse Proxy / HTTPS / Domain
- Backup / Restore
- Runbook
- Prometheus / Grafana / Alerting
- Columnstore / Retention Job 경보
- Disk 경보
- Image Version 고정

### 기능

- OTLP Protobuf
- gzip
- Metrics / Logs
- Attributes / Events / Links 상세 UI
- Service / Endpoint 집계
- 사용자별 Project 전환
- Frontend 자동화 테스트

### 성능과 용량

- N100 / Oracle Cloud 측정
- End-to-End p50 / p95 / p99
- 동시 수집 / 조회
- HikariCP Sizing
- 일일 DB 증가량
- 평균 Span 크기
- Queue Disk 사용량
- Columnstore 압축률
- 대형 Trace UI 렌더링

## 18. 공개 운영 전에 반드시 필요한 것

1. 사용자 인증과 세션
2. Tenant / Project / Membership / Role
3. API Key 관리와 Rotation
4. Rate Limit
5. Tenant Quota
6. 관리 Endpoint 보호
7. Collector Receiver 네트워크 보호
8. TLS와 안전한 Secret 관리
9. Backup / Restore 실전 검증
10. Queue / DB / Disk / Policy Job 알림
11. 장애 대응 Runbook
12. 운영 Health Check
13. 이미지 버전 고정

## 19. Phase 9 다음 작업

### Step 9-1 — 현재 실행 구성 점검

- Root Docker Compose 확인
- TimescaleDB 서비스와 Volume 확인
- Collector 서비스와 Persistent Volume 확인
- Backend / Frontend의 Compose 포함 여부 확인
- 환경변수와 Secret 위치 확인
- Image Tag 확인
- Health Check 확인
- 서비스 시작 순서와 재시작 정책 확인

### 목표 실행 흐름

```text
docker compose up
→ TimescaleDB Ready
→ Collector Ready
→ Backend Ready
→ Frontend Ready
→ Trace 전송
→ Dashboard 조회
```

### 이후

- 운영 환경변수 분리
- Health Check와 시작 순서
- Reverse Proxy / HTTPS / Domain
- Backup / Restore
- Oracle Cloud 또는 홈서버 배포 구조 결정

## 20. 완료 판단 원칙

- 사용자가 직접 적용하고 실행한 항목만 완료로 기록한다.
- 원본 출력이 없는 수치는 완료로 단정하지 않는다.
- 성능 수치를 추측하지 않는다.
- Collector 수신 성공을 DB 저장 성공으로 간주하지 않는다.
- 보안 경계는 UI나 Cursor가 아니라 인증 결과와 Repository의 Tenant / Project 조건에서 강제한다.
- 현재 규모에 필요하지 않은 Kafka, Kubernetes, Elasticsearch를 포트폴리오 목적으로 추가하지 않는다.

---

## 현재 배포 방향

### 최초 외부 공개 검증 환경

Oracle Cloud Ampere A1을 최초 외부 공개 검증 환경으로 사용한다.

목적:

* SaaS Dashboard 외부 접근 검증
* 외부 애플리케이션의 OTLP 전송 검증
* 회사 PoC 및 포트폴리오 데모
* ARM64 Docker 이미지 검증
* 공개 네트워크 환경의 보안 및 운영 절차 학습

Oracle Cloud 환경은 무료 인스턴스의 용량 부족 및 회수 가능성이 있으므로 실제 사용자 데이터의 유일한 저장 위치로 사용하지 않는다.

### N100 Ubuntu 홈서버

N100 Ubuntu 홈서버는 다음 용도로 사용한다.

* amd64 온프레미스 배포 검증
* 장기 데이터 저장량 측정
* JDBC batch 처리량 측정
* TimescaleDB Retention 및 Compression 실험
* Collector Persistent Queue 장애 복구 실험
* 백업 및 복원 실험
* AeroTrace 자체 모니터링

초기 단계에서는 홈 네트워크를 인터넷에 직접 공개하지 않는다.

### 환경 분리 원칙

다음 환경은 각각 독립적인 데이터베이스와 Secret을 사용한다.

```text
local-development
oci-public-demo
n100-on-premise
```

환경별로 별도 관리할 값:

```text
DB 사용자
DB 비밀번호
DB 이름
AeroTrace API Key
Frontend BFF API Key
Collector Backend Endpoint
도메인 및 TLS 설정
Retention 설정
```

환경 간 데이터베이스 자동 복제는 MVP 범위에 포함하지 않는다.

### 현재 Phase

Phase 9 — 로컬 통합 실행 및 운영 배포 준비

완료된 작업:

* Backend Docker 이미지 생성
* Frontend Standalone Docker 이미지 생성
* Collector와 Backend Docker Network 직접 연결
* 전체 통합 실행 스크립트 작성
* Container 재생성 후 TimescaleDB 데이터 보존 검증
* amd64 및 arm64 이미지 호환성 검증
* 최초 공개 환경과 온프레미스 환경의 역할 결정

다음 작업:

* OCI 및 N100에서 공통으로 사용할 운영 Compose 구조 분리
* 운영용 포트 공개 범위 결정
* Reverse Proxy와 TLS 구조 결정
* Ubuntu 서버 기본 보안 설정
* 운영 Secret 생성 및 전달 절차 작성
* DB 데이터 디렉터리와 백업 위치 결정
