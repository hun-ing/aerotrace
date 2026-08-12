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

---

## Phase 9 운영 검증 진행 상태

### 검증 완료

* Edge Gateway HTTPS 공개
* Dashboard Basic Auth 보호
* AeroTrace Backend / TimescaleDB 비공개 네트워크 구성
* OTLP 4317/4318 localhost 전용 노출
* Runtime API Key 환경변수 기반 관리
* Nginx unknown host 차단
* 인증서 자동 갱신 통합
* ACTIVE/PARKED 도메인 인증서 정책 정리
* Frontend 프로세스 장애 자동 재시작 검증
* Frontend IP 변경 후 Nginx stale upstream 504 장애 발견 및 동적 Docker DNS resolution 적용
* 수정 후 실제 Dashboard 서비스 자동 복구 검증
* OpenTelemetry Collector persistent queue 및 retry 설정 검증
* Backend 장애 + Collector 재시작 상황에서 queued telemetry 복구 검증
* 장애 복구 후 테스트 Span 정확히 1건 저장 확인

### 현재 Phase

Phase 9 — 홈서버 운영 배포 및 장애 복구 검증

### 다음 작업

OpenTelemetry Collector Persistent Queue의 실제 용량과 저장 비용을 측정한다.

확인 대상:

* queue size / capacity 설정
* queue 적재량 metric
* queue가 사용하는 Docker Volume 증가량
* 일정량의 Span을 queue에 적재했을 때 Span당 디스크 사용량
* Backend 장애 지속시간에 따른 버퍼링 가능량
* queue saturation 및 디스크 부족 시 데이터 유실 조건

---

### Persistent Queue 용량 측정 진행 상황

Backend 장애 상태에서 100개의 테스트 Span을 OpenTelemetry Collector Persistent Queue에 적재하는 첫 번째 저장량 측정을 완료했다.

실측 결과:

```text
Collector accepted spans = 100
Collector refused spans = 0

Queue size:
0 -> 100 -> 0

DB during outage = 0
DB after recovery = 100

Persistent storage apparent delta = 98304 bytes
Persistent storage allocated delta = 45056 bytes
```

이번 테스트 payload 기준 단순 비율은 apparent 약 983 bytes/span, allocated 약 451 bytes/span이었다.

이 값은 실제 운영 telemetry의 일반적인 Span 크기로 확정하지 않는다.

Queue drain 후에도 Persistent storage 파일 크기가 즉시 감소하지 않는 것을 확인했으므로 다음 1,000 Span 실험에서는 최초 baseline과 high-water mark를 함께 비교한다.

### 다음 작업

동일한 조건에서 1,000 Span을 Persistent Queue에 적재하여 queue/storage 증가 패턴과 복구 정합성을 측정한다.

---

### Persistent Queue 용량 측정

100 Span 및 1,000 Span 단계별 Persistent Queue 실험을 완료했다.

1,000 Span 테스트 결과:

```text
Collector accepted = 1000
Collector refused = 0
DB during outage = 0
DB after recovery = 1000

Queue high-water = 1000
Queue capacity = 50000

Persistent storage high-water:
Apparent  = 536576 bytes
Allocated = 307200 bytes
```

최초 persistent storage baseline에서 1,000 Span high-water까지 증가량:

```text
Apparent increase  = 491520 bytes
Allocated increase = 274432 bytes
```

현재 테스트 payload와 실험 조건에 한정된 수치이며 실제 운영 Span 크기로 일반화하지 않는다.

추가로 기존 측정 스크립트가 DB count 목표 달성만으로 queue drain 완료를 선언하는 문제를 발견했다.

1,000 Span DB 저장 완료 시점의 Collector metric에는 아직 `queue_size=31`이 남아 있었다.

다음 작업은 queue size가 실제 0이 되는 시점까지 대기하도록 측정 스크립트 완료 조건을 개선하는 것이다.

---

### Persistent Queue 측정 도구 검증 완료

Persistent Queue 측정 스크립트의 완료 조건을 개선했다.

이전에는 테스트 Span이 DB에 모두 저장되면 측정이 끝난 것으로 판단했지만, 1,000 Span 테스트에서 DB 저장 완료 시점에도 Collector queue가 남아 있는 사례를 확인했다.

현재 측정 완료 조건은 다음과 같다.

```text
DB test span count = target
AND
otelcol_exporter_queue_size = 0
AND
otelcol_exporter_in_flight_requests = 0
```

수정 후 10 Span smoke test에서 다음을 확인했다.

```text
Collector accepted = 10
DB during outage = 0
DB after recovery = 10
queue_size = 0
in_flight_requests = 0
```

따라서 현재 Persistent Queue 측정 스크립트의 완료 판정 로직은 검증된 상태다.

또한 이전 1,000 Span 실험에서 확보된 Persistent Queue storage가 이후 10 Span 적재 시 추가 파일 증가 없이 재사용되는 것을 확인했다.

---

### OTLP End-to-End 처리량 측정 시작

자동화된 정상 ingest benchmark를 구축했다.

첫 번째 1,000 Span baseline 조건:

```text
Batch size = 50
Concurrency = 4
Requests = 20
```

첫 측정 결과:

```text
Collector accepted = 1000/1000
Failed requests = 0

Collector acceptance ≈ 116761 spans/sec

DB completion ≈ 822.83 spans/sec
Pipeline completion ≈ 822.09 spans/sec

Final DB = 1000/1000
Final queue = 0
Final in-flight = 0
```

이 값은 최소 synthetic Span을 사용한 단일 실행 결과이므로 AeroTrace의 확정 처리량이나 최대 처리량으로 간주하지 않는다.

다음 단계는 동일 조건 반복 측정을 통해 처리량 분산과 중앙값을 확인하는 것이다.

---

### OTLP End-to-End 반복 처리량 Baseline

정상 상태에서 1,000 synthetic Span, batch 50, concurrency 4 조건의 End-to-End benchmark를 5회 반복했다.

5회 모두 다음 정합성 조건을 만족했다.

```text
Accepted spans = 1000/1000
DB = 1000/1000
queue_size = 0
in_flight_requests = 0
```

DB completion throughput:

```text
min    = 981.57 spans/s
median = 1240.51 spans/s
mean   = 1162.82 spans/s
max    = 1306.61 spans/s
stdev  = 141.50 spans/s
```

Pipeline completion throughput:

```text
min    = 980.67 spans/s
median = 1239.04 spans/s
mean   = 1161.44 spans/s
max    = 1305.02 spans/s
stdev  = 141.24 spans/s
```

현재 synthetic workload에서 대표 baseline은 최대값이 아닌 중앙값 약 1.24K spans/s로 취급한다.

이는 AeroTrace의 확정 최대 처리량이 아니며 실제 운영 Span보다 작은 synthetic payload와 약 1초 이하의 짧은 burst workload에서 측정된 값이다.

다음 성능 검증 단계는 sustained ingest workload에서 자원 사용량과 병목을 동시에 측정하는 것이다.

---

### 500 spans/s Sustained Load 검증 완료

정상 상태에서 synthetic OTLP workload를 500 spans/s로 60초 동안 지속하여 총 30,000 Span을 수집했다.

결과:

```text
Target rate     = 500 spans/s
Observed rate   = 500.00 spans/s
Duration        = 60.000133 sec

Accepted        = 30,000 / 30,000
Failed requests = 0
Final DB        = 30,000 / 30,000

Collector refused delta = 0
Final queue              = 0
Final in-flight          = 0
```

Resource baseline:

```text
Backend CPU
  avg 1.85%
  max 7.08%

Collector CPU
  avg 1.74%
  max 4.06%

TimescaleDB CPU
  avg 11.61%
  max 15.71%
```

TimescaleDB CPU가 세 컴포넌트 중 가장 높게 관찰됐지만 현재 부하에서 병목으로 확정할 수준의 근거는 없다.

Memory:

```text
Backend
318.5 MiB -> 319.7 MiB final

Collector
48.48 MiB -> 65.56 MiB final

TimescaleDB
164.2 MiB -> 187.4 MiB final
```

Collector와 TimescaleDB의 증가분은 현재 단일 실험만으로 leak이라고 판단하지 않으며 이후 sustained-load 단계에서 추세를 비교한다.

DB connection 총수는 11로 유지됐다.

5초 resource sample에서는 Collector queue 및 in-flight backlog가 관찰되지 않았다.

다음 성능 단계는 같은 측정 방법으로 750 spans/s sustained workload를 실행해 자원 사용량과 backlog 발생 여부를 비교하는 것이다.

---

### 750 spans/s Sustained Load 검증

500 spans/s baseline 이후 동일 조건에서 부하를 750 spans/s로 증가시켜 60초 sustained test를 수행했다.

```text
Observed rate   = 750.00 spans/s
Accepted        = 45,000 / 45,000
Failed requests = 0
DB final        = 45,000 / 45,000
Refused delta   = 0
Final queue     = 0
Final in-flight = 0
```

최종 데이터 정합성과 pipeline drain은 정상적으로 완료됐다.

자원 사용:

```text
Backend CPU
  avg 2.90%
  max 8.81%

Collector CPU
  avg 1.70%
  max 2.34%

TimescaleDB CPU
  avg 23.77%
  max 55.29%
```

500 spans/s에서는 sampled queue backlog가 없었지만 750 spans/s에서는:

```text
queue sampled max = 750
in_flight max = 1
```

이 처음 관찰됐다.

최종 queue는 0으로 정상 drain됐고 refusal은 없었다.

현재 750 spans/s는 데이터 정합성 관점에서는 PASS지만, TimescaleDB CPU 증가와 Collector queue backlog가 처음 나타난 성능 경계 조사 지점이다.

다음 단계에서는 부하를 바로 높이지 않고 750 spans/s resource time-series를 분석해 queue 발생 시점과 TimescaleDB CPU의 상관관계를 확인한다.

---

### 750 spans/s Backlog 분석

750 spans/s sustained test에서 관찰된 `queue_size=750`은 여러 sample 동안 지속되지 않고 한 번의 sample에서만 나타난 뒤 다음 sample에서 0으로 drain됐다.

동일 구간의 receiver/exporter counter 변화도 exporter가 잠시 incoming telemetry보다 뒤처졌다가 이후 따라잡은 패턴을 보였다.

따라서 현재 상태는:

```text
750 spans/s 데이터 정합성       PASS
지속적인 queue 증가             미관찰
일시적인 exporter backlog       관찰
최종 queue drain                PASS
TimescaleDB CPU 증가            관찰
DB CPU와 queue 직접 상관관계    미확정
```

로 기록한다.

다음 단계에서는 부하를 즉시 높이지 않고 Collector batch/sending queue 설정과 Backend JDBC batch/DB connection 설정을 확인하여 현재 처리 단위와 병렬성을 파악한다.

---

### 750 spans/s Batch 동작 해석 보정

750 spans/s sustained test의 Backend INFO 로그를 확인한 결과 Collector가 45,000 Span을 Backend에 총 61개의 요청으로 전달했다.

주요 request 크기는 750 spans였으며 실제 분포는:

```text
550 × 1
750 × 56
800 × 3
50  × 1
```

이었다.

이는 `batch.timeout=1s`, `send_batch_size=1024` 설정과 750 spans/s의 입력 rate에서 timeout 중심으로 batch가 flush되는 패턴과 일치한다.

따라서 한 차례 관찰된 `queue_size=750`은 지속 overload의 증거라기보다 하나의 Collector batch가 exporter queue를 통과하던 순간을 포착했을 가능성이 높다.

현재 750 spans/s 상태는:

```text
데이터 정합성              PASS
failed requests             0
refused                     0
최종 queue drain            PASS
지속 queue 증가             미관찰
순간 750-span queue         관찰
처리 한계 도달              미확인
```

으로 기록한다.

또한 DB count와 Collector metric이 순차적으로 수집되므로 `DB count < target`과 `queue=0`이 같은 출력에 존재하더라도 이를 동일 시점의 상태로 간주하지 않는다.

---

### Sustained Ingest 실제 처리 단위

750 spans/s runtime 로그 분석 결과:

```text
Sender requests = 900
Backend requests = 61
Total spans = 45,000
```

Backend request는 대부분 약 750 spans 단위였으며 Collector의 1초 batch timeout 동작과 일치했다.

Backend JDBC batch size는 1000이므로 현재 source 구현 기준으로 750 테스트의 모든 Backend request는 한 JDBC batch chunk 내에서 처리될 수 있다.

DB connection 출처 확인 결과:

```text
Application PostgreSQL JDBC connections = 10
TimescaleDB background connection        = 1
```

이었으므로 기존 resource monitor의 `db_connections=11`은 Hikari pool connection 11개를 의미하지 않는다.

Resource monitor는 앞으로 애플리케이션 원격 JDBC connection만 집계하도록 보정한다.

---

### Sustained Ingest 현재 검증 범위

설정 변경 없이 다음 sustained workload를 검증했다.

```text
500 spans/s × 60 sec → 30,000 / 30,000 PASS
750 spans/s × 60 sec → 45,000 / 45,000 PASS
875 spans/s × 60 sec → 52,500 / 52,500 PASS
```

875 spans/s 결과:

```text
Observed rate   = 875.00 spans/s
Failed requests = 0
Refused         = 0
Final queue     = 0
Final in-flight = 0
```

Resource sampling에서도 queue backlog가 관찰되지 않았다.

Collector batch processor는 1,050개의 sender request를 Backend request 61개로 합쳤다.

```text
Backend request average = 860.66 spans
Backend request maximum = 900 spans
```

현재 JDBC batch size는 1000이며 875 테스트에서는 JDBC batch size를 초과한 Backend request가 없었다.

750에서 관찰된 TimescaleDB CPU peak와 순간 queue는 더 높은 875 spans/s에서 재현되지 않았으므로 지속적인 saturation 또는 처리 한계로 판단하지 않는다.

다음 성능 검증 단계는 Collector batch size와 Backend JDBC batch size 경계에 근접하는 1,000 spans/s × 60초 sustained test다.

---

### 1,000 spans/s Sustained Load 재현성 검증

1,000 spans/s × 60초 sustained workload를 동일 조건으로 총 3회 수행했다.

```text
Run1 → DB 60,000/60,000 PASS
Run2 → DB 60,000/60,000 PASS
Run3 → DB 60,000/60,000 PASS
```

5초 resource sampling 결과:

```text
Run1 → queue_size=1000이 약 25초 동안 유지
Run2 → queue backlog 미관찰
Run3 → queue backlog 미관찰
```

Run2와 Run3의 t=10~60 TimescaleDB CPU 평균은 각각 약 25.88%, 26.06%로 매우 유사했다.

따라서 현재 synthetic workload에서 1,000 spans/s는 안정적으로 처리 가능한 범위이며 현재 throughput 한계로 판단하지 않는다.

다만 1/3 run에서 한 batch 수준의 exporter queue가 일정 시간 유지되는 변동성이 관찰됐으므로 더 높은 부하에서도 queue 발생 여부와 drain을 계속 측정한다.

다음 sustained load 단계는 1,125 spans/s다.

---

### 1,125 spans/s Sustained Load

1,125 spans/s를 60초 동안 유지하여 총 67,500 Span을 전량 저장했다.

```text
Observed rate = 1,125.00 spans/s
DB            = 67,500 / 67,500
Failed        = 0
Final queue   = 0
Final in-flight = 0
```

Collector → Backend runtime request:

```text
65 requests
average = 1,038.46 spans/request

1050-span request = 63 / 65
```

현재 Sender input batch 50과 Collector `send_batch_size=1024` 조합에서 21개의 sender batch가 합쳐지면 1050 Span이 되므로 실제 Collector output도 대부분 1050 Span으로 형성됐다.

Backend JDBC batch-size는 1000이므로 현재 source 기준 1050-span Backend request는 1000 + 50 두 JDBC chunk로 분리된다.

1,125 테스트의 계산 결과:

```text
Backend requests         = 65
Requests > JDBC 1000     = 63
Estimated JDBC chunks    = 128
```

Resource sampling에서는 1050-span queue가 간헐적으로 관찰됐지만 매번 다음 sample에서 0까지 drain되었으며 시간에 따라 누적되지는 않았다.

1,125 spans/s는 현재 synthetic sustained workload에서 데이터 정합성과 최종 pipeline drain이 확인된 처리 범위다.

다음 단계에서는 설정을 변경하지 않고 1,250 spans/s로 부하를 증가시켜 같은 1050-span batch 처리 빈도가 높아질 때 queue 및 TimescaleDB CPU 변화를 확인한다.

---

### 현재 Sustained Ingest 검증 범위 — 1,250 spans/s

설정 변경 없이 sustained workload를 1,250 spans/s까지 확장했다.

```text
1,250 spans/s × 60 sec
→ 75,000 / 75,000 DB 저장
→ failed request 0
→ final queue 0
→ final in-flight 0
```

Backend runtime request:

```text
Backend requests = 73
1050-span requests = 71
Average = 1,027.40 spans/request
```

현재 Collector `send_batch_size=1024`, Sender input batch=50 조합에서 1050-span Backend batch가 지속적으로 생성되고 있다.

Backend JDBC batch-size는 1000이므로 현재 source 구현 기준 대부분의 request가 1000 + 50 두 chunk로 처리된다.

Resource sampling에서는 테스트 초반 `queue_size=1050`이 세 sample 동안 유지됐지만 이후 부하 종료까지 queue가 0으로 유지됐다.

시간에 따라 queue가 증가하는 sustained backlog는 확인되지 않았다.

t=10~60 TimescaleDB CPU:

```text
average = 31.36%
median  = 30.98%
maximum = 43.84%
```

1,125 spans/s보다 workload가 증가했지만 평균 CPU가 증가하지 않았으므로 현재 1,250 spans/s까지 CPU saturation 근거는 없다.

다음 성능 단계에서는 설정 변경 없이 1,375 spans/s로 부하를 증가해 지속 backlog와 DB CPU headroom을 계속 확인한다.

---

### 1,375 spans/s 재현성 검증

1,375 spans/s × 60초 sustained workload를 동일 조건으로 2회 검증했다.

```text
Run1 → 82,500 / 82,500 PASS
Run2 → 82,500 / 82,500 PASS
```

두 실행 모두 최종 Collector queue와 in-flight가 0까지 drain됐다.

Full-rate TimescaleDB CPU:

```text
1,250       avg 31.36%

1,375 Run1  avg 40.97%
1,375 Run2  avg 36.79%
```

1,375에서 첫 실행의 높은 CPU maximum은 재현되지 않았지만, steady-state CPU 수준 자체가 1,250보다 높아지는 추세는 두 실행에서 공통적으로 관찰됐다.

Collector queue는 약 1050 Span 한 batch 수준으로 간헐적으로 나타났다가 정상 drain됐으며 시간에 따른 지속적인 backlog 증가는 확인되지 않았다.

현재 1,375 spans/s는 synthetic workload에서 안정적으로 처리된 범위이나, 이후 부하에서는 TimescaleDB CPU headroom을 주요 경계 지표로 관찰한다.

---

### Sustained Ingest 검증 범위 — 1,500 spans/s

설정 변경 없이 synthetic sustained workload를 1,500 spans/s까지 확장했다.

```text
1,500 spans/s × 60 sec
Expected = 90,000 spans

Accepted = 90,000
DB       = 90,000 / 90,000
Failed   = 0

Final queue     = 0
Final in-flight = 0
```

Full-rate TimescaleDB CPU:

```text
average = 34.47%
median  = 31.80%
maximum = 50.75%
```

1,375 spans/s보다 workload가 높지만 DB CPU가 지속적으로 악화되는 현상은 재현되지 않았다.

Collector queue는 한 1050-span batch 수준에서 일정 시간 유지되는 구간이 있었지만 이후 정상 drain됐으며 시간에 따른 누적 backlog는 확인되지 않았다.

Backend runtime request:

```text
Backend requests = 86
1050-span requests = 85
Average request size = 1,046.51 spans
```

현재 source의 JDBC batch-size 1000 기준 estimated JDBC chunk 수는 171이다.

현재 synthetic 60초 workload에서 1,500 spans/s까지 데이터 정합성 및 pipeline drain을 확인했으며 아직 sustained saturation의 근거는 없다.

다음 단계에서는 설정을 변경하지 않고 더 높은 load에서 queue 증가와 TimescaleDB CPU headroom을 계속 탐색한다.

---

### Sustained Ingest 검증 범위 — 1,625 spans/s

1,625 spans/s × 60초 workload를 동일 조건으로 두 차례 검증했다.

```text
Run1 → 97,500 / 97,500 PASS
Run2 → 97,500 / 97,500 PASS

Failed = 0
Final queue = 0
Final in-flight = 0
```

TimescaleDB full-rate CPU:

```text
1,500
avg 34.47%
median 31.80%

1,625 Run1
avg 44.65%
median 41.21%

1,625 Run2
avg 47.11%
median 47.49%
```

1,625에서 DB CPU 상승이 동일 조건 반복 테스트에서도 재현됐다.

Collector queue는 한 1050-span batch 수준에서 간헐적으로 유지되다가 정상 drain됐으며 지속적으로 증가하는 backlog는 확인되지 않았다.

현재 1,625 spans/s는 synthetic sustained workload에서 정상 처리된 범위지만, 이후 부하 단계에서는 TimescaleDB CPU headroom 감소를 주요 경계 신호로 취급한다.

현재 설정 변경이나 성능 tuning은 수행하지 않는다.

---

### Sustained Ingest 검증 범위 — 1,750 spans/s

현재 synthetic sustained workload 검증 범위를 1,750 spans/s까지 확장했다.

```text
1,750 spans/s × 60 sec
Expected = 105,000

Accepted = 105,000
DB       = 105,000 / 105,000
Failed   = 0

Final queue     = 0
Final in-flight = 0
```

Collector refused metric은 이번 출력에서 별도로 확인하지 않았으므로 결과에 0으로 기록하지 않는다.

TimescaleDB full-rate CPU:

```text
average = 45.95%
median  = 48.52%
maximum = 57.37%
```

1,625 두 반복 테스트의 평균 CPU 수준 약 45.88%와 거의 동일하여 1,750까지 부하를 증가시켜도 DB CPU가 추가로 악화되는 패턴은 확인되지 않았다.

5초 resource sampling에서 Collector queue는 최대 1050 Span으로 한 batch 수준을 넘지 않았으며 최종적으로 모두 drain됐다.

Backend runtime request:

```text
Backend requests = 101
1050-span requests = 99
Average request = 1,039.60 spans
```

현재 source의 JDBC batch-size 1000 기준 estimated JDBC chunks는 200이다.

현재 synthetic 60초 workload에서 1,750 spans/s까지 sustained saturation은 확인되지 않았다.

다음 성능 탐색에서는 설정 변경 없이 부하를 소폭 증가시키되 queue의 지속 증가, 데이터 정합성 실패 또는 DB CPU saturation이 발생하면 추가 부하 상승을 중단한다.

---

### Sustained Ingest — 1,875 spans/s Standing Queue 경계

1,875 spans/s × 60초 sustained workload를 동일 조건으로 2회 검증했다.

```text
Run1 → 112,500 / 112,500 PASS
Run2 → 112,500 / 112,500 PASS

Failed       = 0
Refused      = 0
Final queue  = 0
Final flight = 0
```

두 실행 모두 load 중반부터 약 35초 동안 `queue_size=1050`이 지속적으로 관찰됐다.

```text
Run1: t=25 ~ t=60
Run2: t=20 ~ t=55
```

따라서 1,875 spans/s는 현재 workload에서 **standing Collector queue가 재현되기 시작한 성능 구간**으로 기록한다.

그러나 queue size가 1050보다 계속 증가하는 현상은 없었고 테스트 종료 후 정상 drain됐으므로 sustained throughput saturation 또는 overload 한계로 판단하지 않는다.

TimescaleDB CPU는 변동성이 크며 Run2에서 Docker CPU 131.70% sample도 관찰됐다. 단일 CPU sample을 saturation 근거로 사용하지 않고 median, 반복 결과, queue 증가 여부 및 데이터 정합성을 함께 판단한다.

현재 설정은 유지한다.

---

### Sustained Ingest 검증 범위 — 2,000 spans/s

현재 synthetic sustained workload 검증 범위를 2,000 spans/s까지 확장했다.

```text
2,000 spans/s × 60 sec
Expected = 120,000

Accepted = 120,000
DB       = 120,000 / 120,000
Failed   = 0
Refused  = 0

Final queue     = 0
Final in-flight = 0
```

TimescaleDB full-rate CPU:

```text
average = 56.00%
median  = 53.24%
maximum = 84.99%
```

2,000 spans/s에서는 DB가 대략 50%대 CPU 수준을 지속적으로 사용하는 구간에 진입했다.

Collector queue는 초반 한 1050-span batch가 유지되는 구간이 있었지만 이후 반복적으로 0까지 drain됐고, 1050보다 큰 sampled queue는 관찰되지 않았다.

따라서 현재까지 증가형 backlog 또는 sustained saturation은 확인되지 않았다.

Backend runtime request:

```text
Backend requests = 115
1050-span requests = 114
Average request size = 1,043.48 spans
```

현재 source의 JDBC batch-size 1000 기준 estimated JDBC chunks는 229이다.

현재 synthetic 60초 workload에서 검증된 최고 sustained ingest rate는 2,000 spans/s다.

설정 tuning은 아직 수행하지 않는다.

---

### Sustained Ingest 검증 범위 — 2,125 spans/s

현재 synthetic sustained workload 검증 범위를 2,125 spans/s까지 확장했다.

```text
2,125 spans/s × 60 sec
Expected = 127,500

Accepted = 127,500
DB       = 127,500 / 127,500
Failed   = 0
Refused  = 0

Final queue     = 0
Final in-flight = 0
```

TimescaleDB full-rate CPU:

```text
average = 58.52%
median  = 56.49%
maximum = 81.41%
```

2,000 spans/s의 average 56.00%, median 53.24%보다 완만하게 증가했다.

Collector queue는 후반 약 25초 동안 1050 Span 한 batch 수준으로 유지됐지만 1050보다 큰 sampled queue는 관찰되지 않았고 최종적으로 정상 drain됐다.

Backend runtime request:

```text
Backend requests = 122
1050-span requests = 121
Average request size = 1,045.08 spans
```

현재 source의 JDBC batch-size 1000 기준 estimated JDBC chunks는 243이다.

현재 synthetic 60초 workload에서 검증된 최고 sustained ingest rate는 2,125 spans/s다.

DB headroom은 감소하고 있지만 증가형 queue, refused, failed 또는 데이터 정합성 실패는 아직 확인되지 않았다.

설정 tuning은 아직 수행하지 않는다.

---

### Sustained Ingest 검증 범위 — 2,250 spans/s

현재 synthetic sustained workload 검증 범위를 2,250 spans/s까지 확장했다.

```text
2,250 spans/s × 60 sec
Expected = 135,000

Accepted = 135,000
DB       = 135,000 / 135,000
Failed   = 0
Refused  = 0

Final queue     = 0
Final in-flight = 0
```

TimescaleDB full-rate CPU:

```text
average = 60.20%
median  = 59.70%
minimum = 53.53%
maximum = 67.09%
```

DB CPU가 약 60% 수준에서 비교적 지속적으로 유지되는 고부하 구간에 진입했지만 아직 지속 CPU saturation은 확인되지 않았다.

Collector sampled queue는 최대 1050 Span으로 한 batch 수준을 넘지 않았으며 반복적으로 0까지 drain됐다.

시간에 따라 증가하는 exporter backlog는 확인되지 않았다.

Backend runtime request:

```text
Backend requests = 129
1050-span requests = 128
Average request size = 1,046.51 spans
```

현재 source의 JDBC batch-size 1000 기준 estimated JDBC chunks는 257이다.

현재 synthetic 60초 workload에서 검증된 최고 sustained ingest rate는 2,250 spans/s다.

DB headroom은 감소하고 있지만 growing queue, refused, failed 또는 데이터 정합성 실패는 아직 확인되지 않았다.

현재 설정 tuning은 수행하지 않는다.

---

### 2,375 spans/s 재현성 검증

2,375 spans/s × 60초 sustained workload를 동일 조건으로 2회 수행했다.

```text
Run1 → 142,500 / 142,500 PASS
Run2 → 142,500 / 142,500 PASS

Failed  = 0
Refused = 0

Final queue     = 0
Final in-flight = 0
```

TimescaleDB full-rate CPU:

```text
Run1
avg    = 66.53%
median = 65.80%

Run2
avg    = 65.80%
median = 65.99%
```

2,375 spans/s에서 약 60%대 중반 DB CPU 사용이 동일 조건 반복에서도 재현됐다.

Collector queue는 한 1050-span batch 수준으로 반복적으로 유지됐지만 2100 이상으로 성장하지 않았으며 최종 drain됐다.

따라서 2,375 spans/s는 현재 synthetic workload에서 정상 처리 가능한 범위지만 DB headroom 감소가 명확하게 재현된 구간이다.

설정 tuning은 아직 수행하지 않는다.

---

### Sustained Ingest 검증 범위 — 2,500 spans/s

현재 synthetic sustained workload 검증 범위를 2,500 spans/s까지 확장했다.

동일 조건으로 2회 수행:

```text
2,500 spans/s × 60 sec
Expected = 150,000

Run1 DB = 150,000 / 150,000
Run2 DB = 150,000 / 150,000

Failed  = 0
Refused = 0

Final queue     = 0
Final in-flight = 0
```

TimescaleDB full-rate CPU:

```text
Run1
average = 72.69%
median  = 69.79%

Run2
average = 64.03%
median  = 64.17%
```

DB CPU는 동일 workload에서도 의미 있는 run-to-run variation이 관찰됐다.

Run1에서는 sampled queue=2100이 초반 한 번 관찰됐지만 즉시 drain됐으며 Repeat2에서는 sampled queue 최대값이 1050이었다.

두 실행 모두 지속적으로 증가하는 Collector backlog는 확인되지 않았다.

따라서 현재 synthetic 60초 workload에서 검증된 최고 sustained ingest rate는 2,500 spans/s다.

2,500 spans/s를 sustained throughput ceiling으로 판단할 증거는 아직 없다.

설정 tuning은 수행하지 않는다.

---

### Sustained Ingest 검증 범위 — 2,625 spans/s

현재 synthetic sustained workload 검증 범위를 2,625 spans/s까지 확장했다.

동일 조건 2회 수행:

```text
2,625 spans/s × 60 sec
Expected = 157,500

Run1 DB = 157,500 / 157,500
Run2 DB = 157,500 / 157,500

Failed  = 0
Refused = 0

Final queue     = 0
Final in-flight = 0
```

TimescaleDB full-rate CPU:

```text
Run1
average = 73.80%
median  = 66.80%

Run2
average = 69.86%
median  = 73.32%
```

2,625 spans/s에서는 TimescaleDB가 대체로 60~70%대 CPU를 사용하는 high-load 영역에 진입했다.

Run1에서는 queue=1050이 전체 부하 구간에서 지속적으로 sampled됐지만 Repeat2에서는 1050과 0이 반복되었다.

두 실행 모두 sampled queue는 1050을 넘지 않았으며 growing backlog는 확인되지 않았다.

따라서 현재 synthetic 60초 workload에서 검증된 최고 sustained ingest rate는 2,625 spans/s다.

2,625 spans/s를 sustained throughput ceiling으로 판단할 증거는 아직 없다.

설정 tuning은 수행하지 않는다.

---

### Sustained Ingest 검증 범위 — 2,750 spans/s

현재 synthetic sustained workload 검증 범위를 2,750 spans/s까지 확장했다.

```text
2,750 spans/s × 60 sec
Expected = 165,000

Accepted = 165,000
DB       = 165,000 / 165,000
Failed   = 0
Refused  = 0

Final queue     = 0
Final in-flight = 0
```

TimescaleDB full-rate CPU:

```text
samples = 11
average = 71.53%
median  = 70.48%
minimum = 65.90%
maximum = 81.02%
```

DB는 약 70% CPU 수준의 high-load 영역에서 동작했지만 sampled queue는 최대 1050이었고 지속적으로 증가하는 backlog는 확인되지 않았다.

Backend runtime request:

```text
Backend requests = 158
1050-span requests = 157
Average request size = 1,044.30 spans
```

현재 source의 JDBC batch-size 1000 기준 estimated JDBC chunks는 315이다.

현재 synthetic 60초 workload에서 검증된 최고 sustained ingest rate는 2,750 spans/s다.

2,750 spans/s를 sustained throughput ceiling으로 판단할 증거는 아직 없다.

성능 분석 중 timestamp 경계 조건 때문에 화면상 t=60 sample이 full-rate CPU 요약에서 제외될 수 있는 문제를 발견했다. 향후 full-rate 분석은 sampling slot을 반올림한 후 수행한다.

설정 tuning은 아직 수행하지 않는다.

---

### Sustained Load CPU 분석 도구

DB CPU sustained-load 분석용 스크립트:

```text
scripts/summarize-sustained-db-cpu.py
```

기존 timestamp 직접 범위 비교 방식에서 sampling drift 때문에 화면상 t=60인 sample이 full-rate 통계에서 누락될 수 있는 문제를 수정했다.

현재 분석 방식:

- 실제 elapsed timestamp를 sampling interval slot으로 정규화
- 기본 full-rate 구간 t=10~60
- 5초 간격 총 11개 sample 기대
- expected slot 누락 시 통계 생성을 중단하고 non-zero exit
- 부분 데이터로 average/median을 조용히 계산하지 않음

2,750 spans/s 기존 benchmark 데이터로 검증:

```text
full_rate_samples = 11
DB CPU average    = 71.53%
DB CPU median     = 70.48%
DB CPU minimum    = 65.90%
DB CPU maximum    = 81.02%
```

full-rate sample 하나를 제거한 failure test에서는 missing slot을 탐지하고 exit code 2로 종료하는 것을 확인했다.

향후 sustained ingest 성능 테스트의 DB CPU 통계는 이 분석 스크립트를 기준으로 사용한다.

---

### Sustained Ingest 검증 범위 — 2,875 spans/s

2,875 spans/s × 60초 synthetic sustained workload를 동일 조건으로 2회 검증했다.

```text
Run1 DB = 172,500 / 172,500
Run2 DB = 172,500 / 172,500

Failed  = 0
Refused = 0

Final queue     = 0
Final in-flight = 0
```

TimescaleDB full-rate CPU:

```text
Run1
average = 80.79%
median  = 75.80%

Run2
average = 78.88%
median  = 77.22%
```

2,875 spans/s에서는 TimescaleDB가 대체로 75~80% CPU의 high-load 영역에서 동작하는 현상이 재현됐다.

Run1에서 queue=2100이 transient하게 한 번 관찰됐지만 Repeat2에서는 sampled queue 최대값이 1050이었고, 두 실행 모두 시간에 따라 증가하는 backlog는 확인되지 않았다.

따라서 현재 synthetic 60초 workload에서 검증된 최고 sustained ingest rate는 2,875 spans/s다.

DB headroom은 상당히 감소했지만 2,875 spans/s를 sustained throughput ceiling으로 판단할 증거는 아직 없다.

설정 tuning은 아직 수행하지 않는다.

---

### Sustained Ingest 검증 범위 — 3,000 spans/s

3,000 spans/s × 60초 synthetic sustained workload를 동일 조건으로 2회 검증했다.

```text
Run1 DB = 180,000 / 180,000
Run2 DB = 180,000 / 180,000

Failed  = 0
Refused = 0

Final queue     = 0
Final in-flight = 0
Restart 증가   = 없음
```

TimescaleDB full-rate CPU:

```text
Run1
average = 83.23%
median  = 83.87%

Run2
average = 78.81%
median  = 78.55%
```

두 실행 모두 TimescaleDB가 대략 80% 전후 CPU를 사용하는 high-load 특성이 확인됐다.

Collector queue는 두 실행 모두 t=5~60의 모든 5초 sample에서 1050으로 유지되어 one-batch standing queue가 재현됐다.

그러나 queue가 2100 이상으로 증가하거나 시간에 따라 성장하는 현상은 없었으며 두 실행 모두 최종적으로 정상 drain됐다.

현재 synthetic 60초 workload에서 검증된 최고 sustained ingest rate는 3,000 spans/s다.

이는 최대 처리량 또는 production capacity를 의미하지 않는다.

현재 상태:

```text
데이터 정합성        2/2 PASS
failed               0
refused              0
standing queue       2/2 재현
growing backlog      미관찰
DB high-load         재현
saturation           미확인
```

설정 tuning은 아직 수행하지 않는다.

