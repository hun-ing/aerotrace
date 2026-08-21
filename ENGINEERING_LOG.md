# AeroTrace Engineering Log

> 마지막 업데이트: 2026-08-04  
> 현재 Phase: Phase 9 — 로컬 통합 실행 및 배포 준비  
> 기록 원칙: 사용자가 직접 적용하고 실행한 결과만 완료로 기록하며, 원본 출력이 없는 수치는 추측하지 않는다.

---

## 1. Phase 1 — Spring Boot Backend 기반

### 구현

- Java 21
- Spring Boot 4.1.0
- Spring Web MVC
- Validation
- Actuator
- Gradle
- Virtual Threads
- HikariCP
- Flyway
- PostgreSQL Driver

### 검증

- 애플리케이션 정상 기동
- Tomcat 11.0.22가 8080 포트에서 시작
- `/actuator/health`가 `UP`
- Gradle Test 성공
- 실제 HTTP 요청 처리 Thread에서 다음 확인

```text
virtual = true
daemon = true
```

### 학습

- Virtual Thread는 동시 요청 처리를 쉽게 하지만 DB Connection을 늘리지 않는다.
- 실제 DB 처리량은 HikariCP와 PostgreSQL이 제한할 수 있다.
- 설정 여부가 아니라 실제 요청 Thread로 검증해야 한다.

---

## 2. Phase 2 — TimescaleDB와 데이터 모델

### 구현

- PostgreSQL 15.18
- TimescaleDB 2.28.3
- Flyway Migration
- Tenant
- Project
- Span Hypertable
- 복합 외래키
- Unique / Check 제약조건
- 1일 Chunk
- JSONB

### Migration

```text
V1 TimescaleDB extension
V2 tenants and projects
V3 spans hypertable and indexes
V4 span dropped counts
V5 project_api_keys
V6 spans columnstore
V7 columnstore policy after 2 days
V8 retention policy after 30 days
```

### 주요 Index

```text
ux_spans_identity
ix_spans_recent
ix_spans_trace_lookup
spans_start_time_idx
```

### 검증

- Flyway 적용과 재실행 방지
- Hypertable 등록
- Chunk 생성
- Tenant와 Project 소유 관계
- Project 존재 시 Tenant 삭제 제한
- 동일 Span 재삽입 시 행 수 1 유지
- 잘못된 ID, 시간, JSONB 제약 차단

### 발생한 문제

PowerShell과 Docker를 거쳐 `psql` 메타 명령을 전달할 때 역슬래시가 사라져 `\dt`가 SQL `dt`로 해석됐다.

### 해결

- 일반 SQL Catalog 조회
- IntelliJ Database Console
- 저장소 내부 SQL Fixture

---

## 3. Phase 3 — OTLP/HTTP JSON Trace 수집

### Endpoint

```text
POST /v1/traces
Content-Type: application/json
```

### 구현

- Resource / Scope / Span 파싱
- `service.name`
- Scope Name / Version
- Trace ID
- Span ID
- Parent Span ID
- Span Name
- Span Kind
- Status Code / Message
- Start / End Timestamp
- Duration
- Trace State
- Flags
- Dropped Counts
- Resource Attributes
- Span Attributes
- Events
- Links

### AnyValue

지원:

- String
- Boolean
- Signed 64-bit Integer
- Double
- Base64 Bytes
- Array
- Nested Key-value List

검증:

- Attribute Key 공백 거부
- 중복 Attribute Key 거부
- AnyValue 값 필드 정확히 하나
- 잘못된 Base64 거부
- Integer 범위 검증

### 식별자와 시간 검증

- Trace ID 32자리 16진수
- Span ID 16자리 16진수
- all-zero ID 거부
- Parent Span ID 검증
- 종료 시각이 시작 시각보다 빠르면 거부
- Span Kind 범위
- Status Code 범위
- OTLP `uint32` 범위

### 정책

`service.name`은 OTLP 전체에서 항상 필수는 아니지만 AeroTrace의 조회와 집계에 필요해 필수로 정했다.

### 검증 결과

- 정상 요청: 200, `{}`
- 빈 OTLP 요청: 200
- 잘못된 JSON: 400
- 잘못된 Trace ID: 400
- 잘못된 시간: 400
- 잘못된 Content-Type: 415
- Attributes, Events, Links JSONB 저장
- 요청 일부만 저장되지 않도록 Transaction 적용

---

## 4. Phase 4 — JDBC 저장

### 초기 저장 흐름

```text
OTLP JSON
→ ParsedSpan
→ TraceIngestionService
→ SpanWriter
→ JdbcSpanWriter
→ TimescaleDB
```

### 단건 저장 검증

- 첫 요청: 신규 1
- 동일 요청 재전송: 중복 1
- DB 행 수 1
- JSONB 저장

### 발생한 문제 — 테스트 데이터 충돌

Attribute 저장 요청은 200을 반환했지만 기대한 데이터가 조회되지 않았다.

원인:

```text
tenant_id
project_id
trace_id
span_id
start_time
```

기존 테스트와 동일해 Unique Index가 중복으로 처리했다.

해결:

- 테스트 목적마다 고유 Trace ID와 Span ID 사용
- HTTP 응답뿐 아니라 DB 행과 저장 로그 확인

### JDBC Batch 전환

기존:

```text
Span 1개당 JdbcTemplate.update() 1회
```

변경:

```text
요청 Span
→ JSON 직렬화
→ 고정 크기 Chunk
→ JdbcTemplate.batchUpdate()
```

결과 분류:

- inserted
- duplicate
- unknown success
- failed

3 Span 검증:

```text
첫 요청
received=3
inserted=3
duplicates=0

동일 요청
received=3
inserted=0
duplicates=3

신규 1 + 중복 2
received=3
inserted=1
duplicates=2
```

---

## 5. JDBC 단건과 Batch 성능 비교

### 측정 범위 보정

초기 실험에서 단건과 Batch의 JSON 직렬화 측정 범위가 달랐다.

최종 실험은 두 방식 모두 다음을 포함했다.

```text
JSON 직렬화
→ Transaction
→ JDBC 저장
→ Commit
```

제외:

- 저장 행 수 검증
- 다음 측정을 위한 데이터 삭제

### 환경

- Java 21
- Spring Boot 4.1.0
- PostgreSQL 15 / TimescaleDB
- Windows 개발 PC
- Docker Desktop
- Persistence-only
- Warm-up
- 반복 실행
- 실행 순서 교차
- 중앙값

### 결과

| Span | Rewrite | 단건 | Batch | 단건 처리량 | Batch 처리량 | 배율 |
|---:|:---:|---:|---:|---:|---:|---:|
| 100 | OFF | 91.084ms | 30.460ms | 1,098/s | 3,283/s | 2.99배 |
| 100 | ON | 98.687ms | 32.836ms | 1,013/s | 3,045/s | 3.01배 |
| 1,000 | OFF | 874.985ms | 299.167ms | 1,143/s | 3,343/s | 2.92배 |
| 1,000 | ON | 865.236ms | 288.642ms | 1,156/s | 3,465/s | 3.00배 |
| 5,000 | OFF | 4,566.945ms | 1,485.885ms | 1,095/s | 3,365/s | 3.07배 |
| 5,000 | ON | 4,672.900ms | 1,593.576ms | 1,070/s | 3,138/s | 2.93배 |

### 결론

- JDBC Batch가 약 2.9~3.1배 높은 처리량
- `reWriteBatchedInserts=true`는 일관된 개선 없음
- Rewrite 옵션 적용 보류

### 측정 제외

- HTTP
- OTLP JSON Parsing
- Collector
- 동시 요청
- 원격 DB
- N100
- CPU
- Heap
- WAL
- 실제 Network Round Trip 수

---

## 6. Batch Chunk 크기 비교

총 5,000 Span을 고정하고 Chunk 크기만 변경했다.

| Batch 크기 | 총 처리시간 | 처리량 |
|---:|---:|---:|
| 50 | 2,207.706ms | 2,265 spans/s |
| 100 | 1,679.864ms | 2,976 spans/s |
| 250 | 1,950.707ms | 2,563 spans/s |
| 500 | 1,634.554ms | 3,059 spans/s |
| 1,000 | 1,712.393ms | 2,920 spans/s |
| 2,000 | 1,711.864ms | 2,921 spans/s |
| 5,000 | 1,698.537ms | 2,944 spans/s |

### 결정

```yaml
aerotrace:
  ingest:
    jdbc:
      batch-size: 1000
```

### 이유

- 500이 가장 빠른 단일 결과였지만 500~5,000 차이가 크지 않았다.
- 1,000은 처리량과 실행 크기 제한의 균형점이다.
- 절대 최적값이 아니라 초기 운영값이다.

---

## 7. 요청 제한과 오류 계약

```yaml
aerotrace:
  ingest:
    max-spans-per-request: 5000
    max-request-body-bytes: 10485760
```

### 검증

- 5,000 Span 초과: 413
- 10 MiB 초과: 413
- 제한 초과 요청의 부분 저장 없음
- 잘못된 JSON: 400
- 지원하지 않는 Content-Type: 415
- 오류 JSON 응답 통일

---

## 8. Phase 5 — Project API Key 인증

### 데이터 모델

`project_api_keys`:

- ID
- Tenant ID
- Project ID
- Name
- `key_id`
- `secret_hash`
- Created
- Expires
- Revoked

### API Key 형식

```text
atr_<16-character-key-id>.<43-character-secret>
```

### 보안 구현

- 32-byte SecureRandom Secret
- Base64 URL-safe, No Padding
- 원문 Secret 미저장
- SHA-256 Hash
- `MessageDigest.isEqual`
- Byte Array 방어적 복사
- 민감정보 `toString()` 방지
- Unknown Key에도 Dummy Hash
- 원문 API Key와 Key ID를 Log / Metric Tag에 사용하지 않음

### 인증 결과

- success
- missing_credentials
- invalid_authorization
- malformed_key
- unknown_key
- secret_mismatch
- expired
- revoked
- lookup_error

### HTTP Filter

- `Authorization: Bearer <api-key>`
- 수집과 조회 API 보호
- 인증 실패: 401
- `WWW-Authenticate`
- Tenant / Project Header 미신뢰
- API Key 소유 Project를 Request Attribute로 전달

### 검증

- 정상 Key
- Header 누락
- 잘못된 Bearer Header
- Malformed Key
- Unknown Key
- Secret Mismatch
- 만료
- 폐기
- 위조 Tenant / Project Header 무시
- 인증 Project 저장
- 조회 API Project 격리

---

## 9. 인증 관측 가능성

### Micrometer

```text
aerotrace.auth.api_key.attempts
aerotrace.auth.api_key.lookup.duration
```

### Tag 정책

허용:

- 고정 Outcome
- 고정 Reason

금지:

- Tenant ID
- Project ID
- Key ID
- API Key
- IP

### 검증

- Actuator Health
- Actuator Metrics
- 인증 성공 / 실패 Counter
- DB Lookup Timer
- Header 단계 실패는 Lookup Timer 제외

### 남은 위험

- Actuator 운영 보호 미구성
- Metric은 App 재시작 시 초기화
- Prometheus / Grafana 미연동

---

## 10. DB 장애 응답

### 구현

- API Key 조회 DB 장애를 Retryable Exception으로 변환
- Span 저장 DB 장애도 Retryable 분류
- 일시적 DB 연결 / 자원 오류: 503
- SQL 문법 / 프로그래밍 오류는 503으로 숨기지 않음
- DB 장애 응답에 `WWW-Authenticate` 없음
- Hikari Connection Timeout 3초

### 자동 테스트

- Credential Store 장애
- Filter 503
- Filter Chain 미호출
- `WWW-Authenticate` 없음
- Gradle Test 성공

### 실제 장애 검증

1. TimescaleDB 중지
2. 동일 API Key로 수집 요청
3. Backend 503
4. 인증 Lookup Error Metric 증가
5. TimescaleDB 복구
6. 같은 Key로 200 복구

### 미측정

- 실제 503 응답시간

---

## 11. Phase 6 — Collector 연동

### 구성

- OpenTelemetry Collector Contrib 0.157.0
- OTLP Receiver
- Batch Processor
- OTLP HTTP Exporter
- JSON Encoding
- Compression `none`
- Exporter Bearer API Key
- Backend Endpoint `host.docker.internal:8080`
- Secret File Git 제외

### 경로

```text
OTLP Client
→ Collector Receiver
→ Batch Processor
→ Persistent Sending Queue
→ OTLP HTTP JSON Exporter
→ AeroTrace Backend
→ TimescaleDB
```

### 검증

- Collector `localhost:4318/v1/traces` 수신
- Client가 Collector API Key를 알 필요 없음
- Exporter가 Backend에 Bearer Key 추가
- Backend 인증 성공
- TimescaleDB 저장
- Tenant / Project가 API Key 소유권과 일치
- 5ms Span 저장

### 운영 위험

Collector Receiver는 로컬에서 인증 없이 열려 있으므로 인터넷에 직접 공개하면 안 된다.

---

## 12. Persistent Queue

### 구성

```yaml
sending_queue:
  enabled: true
  num_consumers: 2
  sizer: items
  queue_size: 50000
  block_on_overflow: false
  storage: file_storage/aerotrace
```

- Docker Named Volume
- File Storage
- 비-root UID용 Storage Init Container
- Retry 활성화
- Retry 최대 경과시간 제한 없음

### 100 Span 장애 복구

절차:

1. DB와 Queue 초기화 확인
2. TimescaleDB 중지
3. Collector에 100 Span 전송
4. Collector 수신 200
5. Backend 503
6. Queue Size 100
7. Collector 재시작
8. Queue Metadata 복구
9. TimescaleDB 복구
10. 실행기 재실행 없이 결과 확인

결과:

```text
total_rows        = 100
distinct_span_ids = 100
first_span_name   = persistent-queue-span-001
last_span_name    = persistent-queue-span-100
queue_size_after  = 0
```

결론:

- DB 장애 중 보관
- Collector 재시작 후 복구
- DB 복구 후 자동 재전송
- 최종 유실과 중복 관찰되지 않음

### 실험 오류 — 200행

첫 실험:

```text
total_rows        = 200
distinct_span_ids = 100
```

원인 후보:

- 실행기를 두 번 실행
- 이전 Queue 항목이 남음
- 실행마다 새 `start_time`
- 같은 Span ID라도 Unique Identity가 달라짐

조치:

- DB와 Queue 초기화
- 실행기를 한 번만 실행
- 최종 100 / 100 확인

---

## 13. Collector 내부 Metric

### Endpoint

```text
http://localhost:8888/metrics
```

로컬 바인딩:

```text
127.0.0.1:8888:8888
```

### 확인 Metric

```text
otelcol_receiver_accepted_spans
otelcol_receiver_refused_spans
otelcol_exporter_sent_spans
otelcol_exporter_queue_size
otelcol_exporter_queue_capacity
```

실패가 없을 때 다음 Metric은 없을 수 있다.

```text
otelcol_exporter_send_failed_spans
otelcol_exporter_enqueue_failed_spans
```

### 100 Span

장애 중:

```text
queue_capacity = 50000
queue_size     = 100
```

복구 후:

```text
queue_size = 0
```

### 10,000 Span

장애 중:

```text
queue_capacity = 50000
queue_size     = 10000
enqueue_failed_spans = 없거나 0
```

복구 후:

```text
queue_size = 0
```

### 확인하지 못한 값

- 최종 DB Total Rows
- 최종 DB Distinct Span IDs
- 정확한 Drain Time
- Drain Throughput
- Queue Disk Bytes per Span

10,000 Span의 최종 저장 정합성은 원본 출력이 없으므로 완료로 기록하지 않는다.

---

## 14. TimescaleDB Columnstore

### 환경

- Hypertable `public.spans`
- Time `start_time`
- Chunk 1일
- Columnstore 기준 2일
- Segment `tenant_id, project_id`
- Order `start_time DESC`

### 검증

1. 정책 중지
2. 4일 전 Span 전송
3. 과거 Chunk 저장 확인
4. 전환 전 Rowstore
5. Job 수동 실행
6. Job 성공
7. 대상 Chunk Columnstore
8. 기존 Hypertable 조회 성공
9. 최근 Span 전송
10. 최근 Chunk Rowstore
11. 정책 재활성화

### 미측정

- 대량 Chunk 압축률
- 전환 전후 조회 성능
- Job 자원 사용량
- 실패 경보

---

## 15. TimescaleDB Retention

### 정책

- 기준 `start_time`
- 보존 30일
- 실행 하루 한 번
- Chunk 단위 제거

### 테스트 데이터

- 35일 전 Span: 삭제 대상
- 4일 전 Span: 보존 대상
- 현재 Span: 보존 대상

### 안전 검사

- 35일 전 Span이 별도 Chunk
- 대상 Chunk에 테스트 외 행 0
- 30일 초과 후보가 테스트 Chunk 하나
- 보존 대상 수량 사전 기록

### 결과

- Retention Job 성공
- 35일 전 Span 삭제
- 대상 Chunk 제거
- 4일 전 데이터 보존
- 최근 데이터 보존
- 감소 행 수 일치
- Columnstore / Retention 정책 재활성화

### 운영 특성

- 기준은 `ingested_at`이 아니라 `start_time`
- 늦게 도착한 오래된 Span은 빠르게 삭제될 수 있음
- 공유 Chunk의 여러 Tenant 데이터가 함께 제거됨
- 현재는 전역 30일 보존

---

## 16. Phase 7 — Trace Query API

### Endpoint

```text
GET /api/v1/traces
GET /api/v1/traces/{traceId}
```

### 목록 Parameter

- `from`
- `to`
- `limit`
- `cursor`
- `serviceName`
- `errorOnly`
- `minSpanDurationNano`

### 목록 집계

- 최초 Span 시작 시각
- Span 수
- 고유 Service 수
- 가장 긴 Span Duration

Filter는 Trace 포함 여부를 결정하지만 집계는 Trace 전체 Span을 기준으로 한다.

### 멀티테넌트 경계

- AuthenticatedProject의 Tenant / Project 사용
- 모든 SQL에 두 값 함께 적용
- Project A와 B에 동일 Trace ID Fixture
- 서로의 목록과 상세에 섞이지 않음
- 동일 Trace ID 집계도 Project별 분리

### Cursor

```text
traceStartTime DESC
traceId DESC
```

Cursor:

- Trace Start
- Trace ID
- Query Fingerprint

Fingerprint:

- Tenant
- Project
- Time Range
- Service
- Error
- Minimum Duration

조건 변경 후 이전 Cursor: 400

### 상세 제한

- 최대 5,000 Span
- 5,001개 조회로 초과 감지
- Not Found 404
- Invalid ID 400
- Too Large 422
- Unauthorized 401

### 입력 제한

- 최대 Limit 200
- 최대 기간 30일
- Service 최대 255자
- Error true / false
- Duration 0 이상
- Trace ID 32자리 소문자 16진수, all-zero 거부

### 자동 검증

- Service Test
- Controller Contract
- Auth Filter
- Cursor Encode / Decode
- Fingerprint
- TimescaleDB Repository Integration
- Project 격리
- Cursor 중복 방지
- 조합 Filter
- Gradle Regression

### 실제 HTTP 검증

- 첫 페이지 Cursor
- 두 번째 페이지 다른 Trace
- 페이지 중복 없음
- 마지막 Cursor null
- Error / Service / Duration 변경 후 이전 Cursor 400
- Fixture 정리

### 발생한 문제 — Cursor 범위 검증 순서

Mockito에서 Tenant / Project를 Stub하지 않아 기대한 Cursor 범위 예외보다 `tenantId must not be null`이 먼저 발생했다.

검증 순서 변경:

```text
기본 요청
→ Service / Duration
→ Cursor 범위
→ Fingerprint
→ Cursor Fingerprint 비교
→ Repository
```

### 발생한 문제 — Pagination Fixture 부족

기존 데이터가 조회 조건을 만족하지 않아 첫 페이지가 비고 Cursor가 생성되지 않았다.

해결:

- 동일 조건 Trace 두 개 삽입
- Pagination 검증
- Fixture 삭제

---

## 17. Trace 조회 성능 기준선

### 데이터

- Trace 20,000
- Span 109,998
- Trace당 3~8 Span
- Service 8
- Error Trace 2,000
- 50ms 이상 1,000
- 250ms 이상 200

### 기존 SQL

| 조건 | 중앙값 |
|---|---:|
| No Filter | 94.006ms |
| Service | 104.686ms |
| Error | 108.596ms |
| Duration 250ms | 97.209ms |
| Combined | 95.811ms |
| Cursor Second Page | 106.646ms |

모든 Query가 전체 Span과 Trace를 먼저 처리했다.

### 후보 우선

| 후보 | 중앙값 |
|---|---:|
| 200 / 1% | 19.819ms |
| 1,000 / 5% | 34.795ms |
| 20,000 / 100% | 345.467ms |

동일 100% 조건의 기존 방식:

```text
98.048ms
```

### 분석

- 선택도가 낮으면 후보 우선이 빠르다.
- 후보가 많으면 후보 생성, 정렬, 재조회, Join 비용이 추가된다.
- Duration 값만으로 선택도를 예측할 수 없다.

### 결정

- Raw Span 집계 SQL 유지
- 후보 우선 분기 보류
- Duration Index 보류
- Trace Summary 보류

### 한계

- Local Docker
- Warm Cache
- 각 조건 3회
- 동시 요청 없음
- HTTP 제외
- 운영 데이터 분포와 다를 수 있음

---

## 18. Phase 8 — Next.js Frontend 기반

### 생성

- Next.js
- Node 24
- TypeScript
- ESLint
- Tailwind
- App Router
- `src` Directory
- `.nvmrc` 24

### 검증

- Development Server
- Lint
- Production Build
- Nested Git Repository 없음

### 기본 UI

- AeroTrace Layout / Metadata
- Sidebar
- Header
- Overview Cards
- Filters
- Trace Table
- Responsive Dark Layout
- Fake Trace 데이터 미사용

---

## 19. Trace 목록 BFF

### 구조

```text
Browser
→ GET /api/traces
→ Next.js Route Handler
→ GET /api/v1/traces
→ Spring Boot
```

### 보안

- API Key는 `.env.local`
- `NEXT_PUBLIC_` 미사용
- `.env.local` Git 제외
- `.env.example` 추적
- Browser 요청에 Authorization 없음
- 허용 Query Parameter만 전달
- `Cache-Control: no-store`

### 오류 처리

- Backend Error Status 유지
- Backend JSON Error Message 추출
- Invalid Backend JSON 502
- Backend Connection Error 502
- Timeout 502

### 검증

- 정상 200
- Backend 중지 502
- Secret 추적 파일 미포함
- Lint / Build

### 발생한 문제 — Module 경로

`@/lib/server/aerotrace-backend`를 찾지 못하는 오류가 발생했다.

확인:

- 실제 파일 경로
- 파일 확장자
- `tsconfig` Alias
- `.next` Cache
- Frontend Working Directory

파일이 올바른 위치에 생성된 뒤 정상 동작했다.

---

## 20. Trace 목록 UI와 Filter

### 목록

- Client Component 분리
- `/api/traces` 호출
- Runtime Response Validation
- Loading
- Success
- Empty
- Error
- Refresh

### Filter

- From
- To
- Service Exact Match
- Error Only
- Minimum Span Duration
- Millisecond → Nanosecond
- 30일 초과 차단
- From >= To 차단
- Search를 눌렀을 때만 요청
- Reset 최근 7일
- URL Query 반영
- 새로고침 후 복원

### React ESLint 문제

초기화 Effect 본문에서 동기적으로 `setState`를 호출해 `react-hooks/set-state-in-effect` 오류가 발생했다.

수정:

- 초기화 작업을 Microtask로 이동
- Cleanup에서 취소 상태 관리
- ESLint 비활성화 주석을 사용하지 않음

---

## 21. Cursor 기반 Load More

### 구현

- 첫 페이지와 추가 페이지 상태 분리
- `nextCursor`
- 기존 Filter 유지
- Items Append
- 중복 Trace 검사
- 동일 Cursor 반복 검사
- Browser URL에는 Cursor 미포함
- 새 검색 / Reset / Refresh 시 첫 페이지 초기화
- 추가 요청 취소

### 장애 처리

- 추가 조회 실패 시 기존 목록 유지
- Footer Pagination Error
- Retry Load More
- 마지막 Cursor null이면 완료 표시

### 검증

- 50 → 100 누적
- 기존 Trace 유지
- Filter 유지
- URL Cursor 없음
- Backend 중지 시 기존 목록 유지
- 복구 후 Retry
- 마지막 페이지 처리

---

## 22. Trace 상세 BFF

### Endpoint

```text
GET /api/traces/{traceId}
```

### 검증

- Trace ID 32자리 16진수
- `from`, `to`만 전달
- Invalid ID 400
- Not Found 404 유지
- Backend Unavailable 502
- `Cache-Control: no-store`
- API Key Browser 미노출

### 실제 응답 계약

```json
{
  "traceId": "32 hex",
  "spanCount": 1,
  "spans": [
    {
      "spanId": "16 hex",
      "parentSpanId": null,
      "serviceName": "columnstore-policy-verification",
      "scopeName": "aerotrace.columnstore.verifier",
      "scopeVersion": "1.0.0",
      "name": "columnstore-recent-check",
      "spanKind": 2,
      "statusCode": 1,
      "statusMessage": "",
      "startTime": "ISO-8601",
      "endTime": "ISO-8601",
      "durationNano": 5000000
    }
  ]
}
```

---

## 23. Trace 상세와 Span Timeline

### 구현

- 실제 상세 응답 Runtime Validation
- Trace ID 일치
- `spanCount`와 배열 길이 일치
- Span ID / Parent ID
- Timestamp
- Duration
- Span Kind
- Status
- Scope
- Root / Parent
- Start / End
- Status Message
- Trace 전체 시간 기준 상대 Timeline
- Error Span 색상
- 선택 행 표시
- 상세 Close
- 상세 위치 자동 이동

### UI 문제 — View 클릭 후 반응이 없어 보임

증상:

- `View`가 `Selected`로 바뀜
- 현재 화면에서 상세가 보이지 않음

원인:

- 상세 Panel이 긴 Trace 목록 아래에 렌더링됨
- 자동 Scroll이 없음

해결:

- 상세 Panel Wrapper Ref
- `scrollIntoView`
- Scroll Margin

---

## 24. 다중 Span Trace 실제 검증

### 데이터 생성

Fake Frontend 데이터가 아니라 실제 수집 API로 검증 Trace를 저장했다.

구조:

```text
Root: POST /verification
├── Client: SELECT verification_data
└── Consumer: process verification job
```

Service:

```text
aerotrace-multispan-api
aerotrace-multispan-worker
```

### 확인 값

Root:

```text
Kind: Server
Status: OK
Duration: 200ms
Parent: Root
```

DB Child:

```text
Kind: Client
Status: OK
Duration: 50ms
Parent: Root Span ID
```

Worker Child:

```text
Kind: Consumer
Status: Error
Duration: 80ms
Status Message: simulated verification failure
Parent: Root Span ID
```

목록:

```text
spanCount = 3
serviceCount = 2
longestSpanDurationNano = 200000000
```

### 검증 결과

- 목록 집계 일치
- 상세 Span 3
- Service 2
- Root / Parent 관계
- Span Kind
- Error 상태와 Message
- Relative Timeline
- Error Span 시각적 구분

---

## 25. 현재 완료된 것

사용자가 직접 적용하고 정상 동작을 확인한 항목:

- Backend 기반
- OTLP JSON 수집
- JDBC Batch
- 요청 제한
- Project API Key
- Multi-tenant 경계
- Auth Metric
- DB 503
- Collector
- Persistent Queue
- 100 Span 재시작 복구
- 10,000 Span Queue 수용과 Drain
- Columnstore
- Retention
- Trace 목록 / 상세
- Keyset Cursor
- Query Performance Baseline
- Next.js Trace Explorer
- Frontend BFF
- Filters
- Load More
- Span Timeline
- 3 Span / 2 Service / Error Fixture

---

## 26. 검증이 더 필요한 것

- 10,000 Span 최종 DB 행 수와 고유 Span ID
- Queue Overflow
- Queue Disk 사용량
- Queue Drain Throughput
- N100 성능
- Oracle Cloud 성능
- End-to-End HTTP p50 / p95 / p99
- 동시 수집과 조회
- HikariCP Sizing
- Columnstore 압축률
- 일일 DB 증가량
- 30일 저장 용량
- Background Job 실패
- 대형 Trace UI 성능

---

## 27. 현재 기술 부채

### 보안

- 사용자 인증 / 세션
- Role / Membership
- API Key 관리 UI / Rotation
- Rate Limit
- Quota
- Actuator 보호
- Collector Receiver 보호
- Secret 관리

### 운영

- 통합 Compose
- Reverse Proxy / TLS
- Backup / Restore
- Alerting
- Runbook
- Image Version 고정
- 운영 Health Check

### 기능

- Protobuf
- gzip
- Metrics / Logs
- Attributes / Events / Links 상세
- Service / Endpoint 집계
- Frontend Automated Test

---

## 28. Phase 9 시작 조건

현재 기준:

```text
Backend MVP
+ Trace Query API
+ Frontend Trace Explorer
+ Local Collector
+ TimescaleDB
```

첫 작업:

1. 현재 Docker Compose 전체 확인
2. Backend / Frontend 포함 여부 확인
3. 환경변수와 Secret 위치 확인
4. Health Check 확인
5. Image Tag 확인
6. 실행 순서와 의존성 확인
7. 한 명령으로 로컬 통합 실행할 목표 구조 정의

---

## Phase 9 — Docker 통합 실행 및 데이터 보존 검증

### 구현 내용

* TimescaleDB, Backend, OpenTelemetry Collector, Frontend를 Docker Compose 통합 모드로 실행
* PowerShell 기반 통합 실행 스크립트에 설정 검사, 시작, 종료, 재시작, 상태 확인, 로그 확인 기능 추가
* `docker compose down`으로 Container와 Network를 제거하되 Named Volume은 유지하도록 운영 절차 고정
* Windows PowerShell에서 안정적으로 실행되도록 TimescaleDB 조회 명령을 `docker exec ... psql` 직접 실행 방식으로 변경

### 검증 조건

1. 통합 모드에서 전체 서비스 실행
2. 재생성 전 `public.spans` 행 수 조회
3. 전체 Container와 Compose Network 제거
4. TimescaleDB 및 Collector Named Volume 유지 확인
5. 전체 Container 재생성
6. 재생성 후 `public.spans` 행 수 재조회
7. Backend, Frontend, Collector 수집 경로 재검증

### 측정 결과

```text
재생성 전 Span 수: 120,107
재생성 후 Span 수: 120,107
데이터 보존 여부: True
```

### 확인된 사항

* TimescaleDB 데이터가 Container 파일시스템이 아닌 Docker Named Volume에 정상 저장됨
* Container 제거 및 재생성 이후에도 기존 Span 데이터가 유실되지 않음
* 재생성 이후 Backend와 Frontend가 기존 데이터를 다시 조회할 수 있음
* 운영 절차에서 `docker compose down -v`를 사용하면 데이터 Volume이 삭제될 수 있으므로 금지 명령으로 문서화

### 남은 위험

* Named Volume 유지 검증은 완료했지만 디스크 손상, 서버 손실, 사용자 실수에 대비한 외부 백업은 아직 없음
* 데이터베이스 백업 및 복원 절차는 운영 배포 전에 별도로 검증해야 함
* Collector Persistent Queue에 실제 미전송 데이터가 존재하는 상태에서의 재기동 복구 테스트는 별도 장애 시나리오로 수행해야 함

---

## ENGINEERING_LOG.md 추가

### 2026-08-10 — Frontend 자동 재시작 후 Nginx stale upstream으로 인한 504 장애

#### 테스트 목적

Docker `restart: unless-stopped` 설정이 단순히 컨테이너를 다시 실행하는 수준을 넘어, 실제 AeroTrace Dashboard 서비스까지 자동 복구시키는지 장애 주입으로 검증했다.

#### 장애 주입

`aerotrace-frontend` 컨테이너의 PID 1 프로세스를 `SIGKILL`로 강제 종료했다.

테스트 직전 상태:

```text
PID: 835820
RestartCount: 0
StartedAt: 2026-08-07T03:08:31.740774962Z
```

장애 후 Docker가 컨테이너를 자동으로 재시작했다.

```text
RestartCount: 0 -> 1
새 PID: 2488763
StartedAt: 2026-08-10T01:14:10.973935763Z
health: starting -> healthy
```

Frontend 컨테이너 자체는 약 3초 안에 `healthy` 상태로 복구됐다.

#### 발견된 문제

컨테이너가 정상 복구된 이후 Basic Auth를 통과한 Dashboard 요청에서 `504 Gateway Timeout`이 발생했다.

Nginx error log:

```text
upstream timed out while connecting to upstream
upstream: "http://172.16.50.20:3000/"

connect() failed (113: Host is unreachable) while connecting to upstream
upstream: "http://172.16.50.20:3000/..."
```

재시작 후 실제 Frontend의 `edge-gateway-net` IP는 다음과 같았다.

```text
172.16.50.5
```

Docker embedded DNS 역시 정상적으로 새로운 IP를 반환했다.

```text
aerotrace-web -> 172.16.50.5
```

Edge Gateway 컨테이너에서 새 주소로 직접 요청했을 때 Next.js는 정상적으로 `HTTP 200`을 반환했다.

따라서 Docker 및 Frontend 자체 문제가 아니라 Nginx가 기존 upstream IP `172.16.50.20`을 계속 사용하고 있는 것이 원인이었다.

#### 원인

기존 설정:

```nginx
proxy_pass http://aerotrace-web:3000;
```

에서 Nginx가 Docker hostname을 설정 로딩 시 해석한 뒤 기존 IP를 계속 사용했다.

Frontend 컨테이너가 비정상 종료 후 재생성/재연결되면서 IP가 변경됐지만 Nginx upstream은 새로운 Docker DNS 결과를 반영하지 못했다.

따라서 다음 상태가 발생했다.

```text
Frontend 프로세스 장애
→ Docker 자동 재시작 성공
→ Frontend Docker IP 변경
→ Docker DNS 갱신 성공
→ Nginx는 과거 IP 유지
→ 504 Gateway Timeout
```

#### 해결

AeroTrace Frontend upstream을 Docker embedded DNS 기반 동적 resolution 방식으로 변경했다.

핵심 설정:

```nginx
upstream aerotrace_frontend_upstream {
    zone aerotrace_frontend_upstream 64k;

    resolver 127.0.0.11 valid=5s ipv6=off;
    resolver_timeout 2s;

    server aerotrace-web:3000 resolve;
}
```

그리고 기존 직접 hostname proxy를 다음과 같이 변경했다.

```nginx
proxy_pass http://aerotrace_frontend_upstream;
```

#### 재검증

동일한 Frontend 프로세스 장애 주입 테스트를 다시 수행했다.

Docker 자동 재시작, health 복구, Docker DNS 갱신과 함께 실제 외부 AeroTrace Dashboard까지 정상 복구되는 것을 확인했다.

최종 판정:

```text
Container recovery : PASS
Docker DNS update  : PASS
Nginx DNS update   : PASS
Service recovery   : PASS
```

#### 실무적 교훈

컨테이너에 `restart: unless-stopped`가 설정되어 있다는 사실만으로 실제 서비스 복구를 보장할 수 없다.

Reverse Proxy, DNS resolution, Docker network, health check까지 포함한 전체 요청 경로를 장애 주입으로 검증해야 한다.

특히 컨테이너 hostname을 사용하는 Nginx upstream에서는 컨테이너 IP 변경 시 DNS 재해석 방식이 실제 장애 복구 능력에 영향을 줄 수 있다.

---

## 2026-08-10 — Backend 장애 + Collector 재시작 Persistent Queue 복구 검증

### 목적

OpenTelemetry Collector가 telemetry를 수신한 뒤 AeroTrace Backend가 일시적으로 사용할 수 없는 상황에서도 데이터를 유실하지 않고 보관하며, Collector 자체까지 재시작된 이후 Backend가 복구되면 저장을 완료하는지 검증했다.

### 장애 시나리오

다음 순서로 실제 장애를 주입했다.

1. 정상 상태의 Backend, Collector, TimescaleDB 확인
2. AeroTrace Backend 일시 정지
3. 고유한 Trace ID / Span ID / Span Name을 가진 테스트 Span 1개를 OTLP/HTTP로 Collector에 전송
4. Backend가 정지된 상태에서 DB에 Span이 저장되지 않았음을 확인
5. 아직 전송되지 못한 telemetry가 있는 상태에서 Collector 프로세스를 강제 종료
6. Docker restart policy에 의해 Collector 자동 재시작
7. Backend를 복구
8. Collector의 retry/persistent queue를 통해 telemetry 재전송
9. TimescaleDB 저장 결과 확인

### 결과

테스트가 정상 완료됐다.

```text
Backend 장애 중 Collector 수신             PASS
Backend 장애 중 DB 미저장                   PASS
Collector 비정상 종료 후 자동 재시작         PASS
Collector 재시작 후 queued telemetry 유지    PASS
Backend 복구 후 retry/export                PASS
최종 DB 저장                                PASS
최종 test span count = 1                    PASS
```

테스트 Span이 최종적으로 정확히 1건 저장되어 장애 복구 과정에서 데이터 유실이나 중복 저장이 발생하지 않았음을 확인했다.

### 검증된 구조

```text
OTLP Client
    ↓
OpenTelemetry Collector
    ↓
Persistent Sending Queue
    ↓
Docker Volume /var/lib/otelcol
    ↓
Backend 장애 중 데이터 보존
    ↓
Collector 재시작
    ↓
Persistent Queue 복원
    ↓
Backend 복구
    ↓
Retry
    ↓
AeroTrace Backend
    ↓
TimescaleDB
```

### 운영상 의미

Collector 설정에 persistent queue가 존재한다는 사실만 확인한 것이 아니라 실제 Backend 장애와 Collector 프로세스 장애를 연속으로 발생시켜 데이터 복구 경로 전체를 검증했다.

단, Persistent Queue 용량과 저장 디스크는 유한하므로 장시간 Backend 장애나 디스크 고갈 상황에서의 최대 버퍼링 능력은 별도의 측정이 필요하다.

### 다음 검증 과제

* sending queue 최대 용량 확인
* queue 적재량 증가에 따른 디스크 사용량 측정
* Backend 장기 장애 시 최대 버퍼링 가능 telemetry 양 측정
* queue saturation 시 동작 및 데이터 유실 조건 확인
* 운영 alert 기준 수립

---

## 2026-08-10 — OpenTelemetry Persistent Queue 100 Span 저장 비용 측정

### 목적

AeroTrace Backend 장애 시 OpenTelemetry Collector Persistent Queue가 telemetry를 얼마나 저장하는지 실제 디스크 사용량과 Collector 내부 metric을 기준으로 측정했다.

단순 기능 검증이 아니라 향후 Backend 장애 지속시간과 필요한 queue/storage 용량을 산정하기 위한 첫 번째 baseline 실험이다.

### 테스트 조건

* 테스트 Span: 100개
* OTLP/HTTP 요청: 요청당 Span 1개
* AeroTrace Backend: `docker pause`로 일시 정지
* Collector: 정상 실행 유지
* TimescaleDB: 정상 실행 유지
* Persistent storage: `/var/lib/otelcol` Docker Volume
* 테스트 중 Collector 재시작 없음

### 장애 전 상태

```text
otelcol_exporter_queue_capacity = 50000
otelcol_exporter_queue_size = 0

Persistent storage apparent bytes = 45056
Persistent storage allocated bytes = 32768
```

### Backend 장애 중 전송 결과

Collector에 총 100개의 Span을 전송했다.

```text
Requested spans       = 100
Collector accepted    = 100
DB count during outage = 0
```

Collector 내부 metric:

```text
otelcol_exporter_queue_capacity = 50000
otelcol_exporter_queue_size = 100

otelcol_receiver_accepted_spans = 100
otelcol_receiver_refused_spans = 0
```

Backend가 정지된 동안 Collector가 모든 Span을 수락했고 TimescaleDB에는 아직 데이터가 저장되지 않았다.

### Persistent Queue 저장량

100개의 테스트 Span이 queue에 존재하는 시점의 storage 사용량:

```text
Apparent bytes before = 45056
Apparent bytes queued = 143360
Apparent delta        = 98304 bytes

Allocated bytes before = 32768
Allocated bytes queued = 77824
Allocated delta        = 45056 bytes
```

이번 테스트 payload 기준 단순 비율:

```text
Apparent storage 증가 ≈ 983 bytes/span
Allocated storage 증가 ≈ 451 bytes/span
```

이 값은 현재 테스트 Span 구조와 최초 storage 상태에서 측정된 값이며 운영 telemetry의 일반적인 Span 크기라고 간주하지 않는다.

Resource attributes, Span attributes, events, links 등의 크기가 증가하면 실제 저장 비용 역시 달라질 수 있다.

### Backend 복구 결과

Backend를 unpause한 직후 일시적으로 health check가 `unhealthy`였으며 6번째 확인에서 `healthy`로 복귀했다.

Queue drain 이후:

```text
otelcol_exporter_queue_size = 0
otelcol_exporter_sent_spans = 101

Final DB count = 100
```

테스트 이전 `sent_spans=1`에서 테스트 이후 `101`로 증가하여 이번 테스트의 100 Span과 일치했다.

DB에도 테스트 Span이 정확히 100건 존재했다.

따라서 이번 실험에서는:

```text
수락   100
거부     0
복구   100
중복     0
유실     0
```

으로 확인됐다.

### Queue drain 이후 storage 특성

Queue가 완전히 비워진 이후에도:

```text
Apparent bytes = 143360
Allocated bytes = 77824
```

로 유지됐다.

즉, queue drain이 파일 크기의 즉시 축소를 의미하지 않는 동작이 관찰됐다.

따라서 이후 실험에서는 각 실험 시작점의 `du` 차이만으로 Span당 저장량을 단순 계산하지 않고, 최초 baseline과 queue high-water mark를 함께 비교해야 한다.

### 다음 실험

100 Span 결과를 기준으로 1,000 Span을 동일 조건에서 적재한다.

확인 항목:

* queue_size 증가
* queue_capacity 대비 사용률
* 1,000 Span high-water mark
* 최초 baseline 대비 storage 증가량
* 100 Span과 1,000 Span 사이의 storage 증가 패턴
* Backend 복구 후 정확히 1,000건 저장
* refused/enqueue failure 발생 여부

이 결과를 통해 Persistent Queue 디스크 사용량이 테스트 범위에서 어느 정도 선형성을 가지는지 확인한다.

---

## 2026-08-10 — Persistent Queue 1,000 Span 저장량 측정

### 목적

100 Span 실험보다 10배 큰 1,000 Span을 동일한 Backend 장애 조건에서 OpenTelemetry Collector Persistent Queue에 적재하여 queue 크기, persistent storage high-water mark, 데이터 복구 정합성을 측정했다.

### 테스트 조건

- 테스트 Span: 1,000개
- 요청당 Span: 1개
- Backend: `docker pause`
- Collector: 정상 실행
- TimescaleDB: 정상 실행
- Queue capacity: 50,000
- 이전 100 Span 테스트 후 persistent storage 파일이 확보한 공간을 유지하고 있는 상태에서 측정

### 결과

```text
Requested spans         = 1000
Collector accepted      = 1000
DB count during outage  = 0
Final DB count          = 1000

receiver accepted:
100 -> 1100

receiver refused:
0

queue:
0 -> 1000
```

Backend 장애 중 TimescaleDB에는 테스트 Span이 저장되지 않았으며 Backend 복구 이후 테스트 Span 1,000건이 모두 조회됐다.

### Persistent storage

1,000 Span 실험 시작 시:

```text
Apparent  = 143360 bytes
Allocated = 77824 bytes
```

Queue 1,000 high-water:

```text
Apparent  = 536576 bytes
Allocated = 307200 bytes
```

이번 실행 중 추가 증가:

```text
Apparent delta  = 393216 bytes
Allocated delta = 229376 bytes
```

단순히 이번 실행의 증가량으로 계산하면 각각 약 393.2 bytes/span, 229.4 bytes/span이다.

그러나 이전 100 Span 실험에서 persistent storage가 이미 확보한 공간을 유지하고 있었으므로 이 값을 일반적인 Span당 storage 비용으로 간주하지 않는다.

최초 실험 전 baseline과 이번 1,000 Span high-water를 비교하면:

```text
Initial apparent baseline = 45056
1000-span high-water      = 536576
Total apparent increase   = 491520 bytes

Initial allocated baseline = 32768
1000-span high-water       = 307200
Total allocated increase   = 274432 bytes
```

현재 테스트 payload와 실험 순서 기준 high-water 비율은 약:

```text
Apparent  ≈ 491.5 bytes/span
Allocated ≈ 274.4 bytes/span
```

이었다.

실제 운영 Span의 attributes, events, links 등의 크기가 다르므로 운영 데이터 저장 비용으로 일반화하지 않는다.

### 추가 발견 — Queue drain 판정 오류

DB에 1,000건이 모두 저장된 직후 Collector metric은 다음 상태였다.

```text
queue_size = 31
sent_spans = 1070
```

실험 시작 시 `sent_spans=101`이었으므로 해당 metric snapshot 기준으로는 +969였다.

따라서 기존 측정 스크립트는 DB count가 목표치에 도달했다는 사실만으로 queue drain까지 완료됐다고 판단하고 있었다.

데이터 정합성 검증은 통과했지만 queue가 실제 0이 되는 시점까지 기다리는 조건이 빠져 있었다.

다음 작업에서는 측정 스크립트를 수정해 다음 두 조건을 별도로 검증한다.

1. 테스트 Span DB count가 목표값에 도달
2. Collector `queue_size=0`이 될 때까지 추가 대기

그 후에만 queue drain 완료로 판정한다.

---

### 2026-08-10 — Persistent Queue 측정 도구 Queue Drain 판정 개선 검증

1,000 Span 측정에서 TimescaleDB에 모든 테스트 Span이 저장된 시점에도 OpenTelemetry Collector의 `queue_size`가 31로 남아 있는 것을 발견했다.

기존 측정 스크립트는 DB의 테스트 Span 수가 목표값에 도달하면 곧바로 측정 완료로 판단했기 때문에, DB 저장 완료와 Collector 내부 pipeline 처리 완료를 동일한 시점으로 잘못 판단할 수 있었다.

측정 완료 조건을 다음과 같이 변경했다.

```text
1. 테스트 Span의 DB count가 목표값에 도달
2. otelcol_exporter_queue_size = 0
3. otelcol_exporter_in_flight_requests = 0
4. 위 조건을 모두 만족한 후 PASS
```

수정 후 10 Span smoke test를 수행했다.

결과:

```text
Requested spans        = 10
Collector accepted     = 10
DB during outage       = 0
Final DB count         = 10

queue during outage    = 10

queue after recovery   = 0
in_flight_requests     = 0
Collector queue drain  = OK

Persistent queue 10-span measurement = PASS
```

독립적으로 Collector metric을 다시 조회한 결과도 다음과 같았다.

```text
queue_size         = 0
in_flight_requests = 0
queue_capacity     = 50000
```

Backend, Collector, TimescaleDB도 최종적으로 모두 정상 상태임을 확인했다.

추가로 Persistent Queue storage는 1,000 Span 실험에서 확보한 공간을 10 Span 실험에서 재사용했다.

```text
Apparent bytes before = 536576
Apparent bytes queued = 536576
delta                 = 0

Allocated bytes before = 307200
Allocated bytes queued = 307200
delta                  = 0
```

이는 queue가 drain된 이후에도 확보한 storage 공간을 즉시 반환하지 않고 이후 queue 적재에 재사용할 수 있다는 이전 관찰과 일치한다.

이번 검증으로 Persistent Queue 측정 도구는 DB 정합성과 Collector 내부 queue 처리 완료를 독립적으로 검증할 수 있게 됐다.

---

## 2026-08-10 — 자동화된 OTLP End-to-End 처리량 Baseline 측정

### 목적

OpenTelemetry Collector가 OTLP 요청을 수락하는 속도와 AeroTrace Backend가 실제로 TimescaleDB 저장까지 완료하는 속도를 분리하여 측정했다.

기존 수동 측정에서는 benchmark 시작과 종료 사이에 운영자가 직접 명령을 입력하는 시간이 포함되어 `53.76 spans/s`라는 잘못된 end-to-end 결과가 만들어졌다.

해당 값은 성능 지표에서 폐기하고, Span 전송부터 DB 저장 및 Collector queue drain 완료까지 하나의 스크립트에서 자동으로 측정하도록 benchmark를 개선했다.

### 테스트 조건

```text
Total spans = 1000
OTLP batch size = 50 spans/request
HTTP requests = 20
Concurrency = 4
Collector protocol = OTLP/HTTP
Collector endpoint = localhost:4318
Backend = 정상
TimescaleDB = 정상
Collector queue before test = 0
Collector in-flight before test = 0
```

Synthetic Span은 최소 Resource/Scope/Span 데이터를 사용하는 테스트 payload다.

따라서 실제 운영 telemetry workload와 동일한 크기라고 간주하지 않는다.

### 결과

```text
Requested spans    = 1000
Accepted spans     = 1000

Requested requests = 20
Accepted requests  = 20
Failed requests    = 0
```

Sender → Collector:

```text
Send elapsed                 = 0.008564 sec
Accepted spans/sec           = 116761.15
Accepted requests/sec        = 2335.22

Request latency p50          = 0.779 ms
Request latency p95          = 3.110 ms
Request latency p99          = 3.879 ms
Request latency max          = 3.879 ms
```

전체 자동화 측정:

```text
Wrapper send elapsed         = 0.076144 sec
DB completion elapsed        = 1.215317 sec
Pipeline completion elapsed  = 1.216411 sec

Observed DB completion       = 822.83 spans/sec
Observed pipeline completion = 822.09 spans/sec
```

최종 정합성:

```text
DB count       = 1000/1000
queue_size     = 0
in_flight      = 0

OTLP end-to-end benchmark = PASS
```

### 해석

Collector의 OTLP HTTP 수락 속도는 이번 실행에서 약 116K spans/sec였지만 실제 DB 및 pipeline 완료 속도는 약 822 spans/sec였다.

따라서 현재 테스트 조건에서 Collector HTTP 수신 자체가 전체 end-to-end throughput의 직접적인 병목이라는 증거는 없다.

하지만 단일 실행 결과만으로 Backend 또는 DB의 최대 처리량이 822 spans/sec라고 확정하지 않는다.

또한 request latency percentile은 요청 표본이 20개뿐이므로 현재는 참고값으로만 사용한다.

### 다음 측정

동일 조건의 1,000 Span benchmark를 여러 번 반복하여 다음을 계산한다.

- DB completion throughput 중앙값
- pipeline throughput 중앙값
- 최소/최대값
- 실행 간 변동폭
- Collector acceptance throughput 변동

반복 결과가 안정적이면 테스트 Span 수를 증가시켜 장시간 sustained ingest 조건에서 CPU, memory, DB connection 및 queue 동작을 측정한다.

---

## 2026-08-10 — OTLP End-to-End 처리량 5회 반복 Baseline

### 목적

단일 실행 결과를 AeroTrace의 대표 처리량으로 사용하는 오류를 피하기 위해 동일한 조건의 End-to-End benchmark를 5회 반복했다.

각 실행은 다음 완료 조건을 모두 만족해야 PASS로 판정했다.

```text
테스트 Span 전체 Collector 수락
테스트 Span 전체 TimescaleDB 저장
Collector queue_size = 0
Collector in_flight_requests = 0
```

### 테스트 조건

```text
Runs = 5
Total spans/run = 1000
Batch size = 50 spans/request
Requests/run = 20
Concurrency = 4
Protocol = OTLP/HTTP
Collector = localhost:4318
Backend = 정상 상태
TimescaleDB = 정상 상태
```

테스트 payload는 최소 synthetic Span이므로 실제 운영 Span workload와 동일한 크기라고 간주하지 않는다.

### 개별 결과

| Run | Collector accepted spans/s | DB spans/s | Pipeline spans/s | DB elapsed | Pipeline elapsed |
|---:|---:|---:|---:|---:|---:|
| 1 | 120064.85 | 981.57 | 980.67 | 1.018773s | 1.019712s |
| 2 | 123124.61 | 1240.51 | 1239.04 | 0.806122s | 0.807077s |
| 3 | 116401.07 | 1241.99 | 1240.39 | 0.805159s | 0.806197s |
| 4 | 119508.58 | 1043.43 | 1042.07 | 0.958379s | 0.959626s |
| 5 | 91770.45 | 1306.61 | 1305.02 | 0.765341s | 0.766272s |

모든 실행:

```text
DB count = 1000/1000
queue_size = 0
in_flight_requests = 0
OTLP end-to-end benchmark = PASS
```

### 통계

Collector acceptance:

```text
min    = 91770.45 spans/s
median = 119508.58 spans/s
mean   = 114173.91 spans/s
max    = 123124.61 spans/s
stdev  = 12749.04 spans/s
```

DB completion:

```text
min    = 981.57 spans/s
median = 1240.51 spans/s
mean   = 1162.82 spans/s
max    = 1306.61 spans/s
stdev  = 141.50 spans/s
```

Pipeline completion:

```text
min    = 980.67 spans/s
median = 1239.04 spans/s
mean   = 1161.44 spans/s
max    = 1305.02 spans/s
stdev  = 141.24 spans/s
```

DB 및 Pipeline throughput의 상대 표준편차는 약 12%로 관찰됐다.

5회 반복만으로 통계적으로 안정된 최대 처리량이라고 판단하지 않으며, 현재 단계에서는 정상 상태 synthetic workload의 baseline 범위로 사용한다.

### 해석

이전에 수행한 단일 자동 benchmark에서는 DB completion이 약 822.83 spans/s로 측정됐지만 이번 5회 반복에서는 최저값도 981.57 spans/s였다.

따라서 단일 실행값을 대표 처리량으로 확정하지 않고 반복 측정을 수행한 것이 필요했음을 확인했다.

현재 조건의 대표 baseline은 최대값이 아니라 중앙값을 사용하여 다음과 같이 기록한다.

```text
DB completion median ≈ 1240.51 spans/s
Pipeline median      ≈ 1239.04 spans/s
```

또한 DB completion 이후 pipeline completion까지의 추가 시간은 각 실행에서 약 0.9~1.25ms였으며, 이번 정상 부하에서는 DB 저장 완료 이후 Collector drain이 큰 추가 지연을 만들지 않았다.

Collector acceptance throughput과 DB throughput도 동일하게 움직이지 않았다.

예를 들어 Run 5에서는 Collector acceptance가 5회 중 가장 낮았지만 DB completion throughput은 가장 높았다.

따라서 현재 workload에서 localhost OTLP HTTP 수락 성능을 Backend/DB 저장 처리량의 대리 지표로 사용하지 않는다.

### 다음 측정

1,000 Span burst는 전체 실행 시간이 약 1초 이하로 짧기 때문에 CPU, memory, DB connection pool, queue 증가 등 sustained-load 특성을 평가하기 어렵다.

다음 단계에서는 더 긴 시간 동안 지속되는 ingest workload를 만들고 다음을 동시에 측정한다.

- 실제 sustained spans/sec
- Backend CPU
- Backend memory
- Collector CPU/memory
- TimescaleDB CPU/memory
- DB connection 사용량
- Collector queue 크기
- 실패 요청
- 데이터 누락/중복

---

## 2026-08-10 — 500 spans/s × 60초 Sustained Load Baseline

### 목적

약 1초 동안 수행되던 기존 burst benchmark를 넘어 일정한 ingest rate를 장시간 유지하면서 AeroTrace Backend, OpenTelemetry Collector, TimescaleDB의 자원 사용량과 데이터 정합성을 측정했다.

첫 sustained-load는 기존 synthetic End-to-End throughput 중앙값보다 충분히 낮은 500 spans/s에서 시작했다.

### 테스트 조건

```text
Target rate       = 500 spans/s
Duration          = 60 sec
Batch size        = 50 spans/request
Request rate      = 10 requests/sec
Expected spans    = 30,000

Resource interval = 5 sec
Resource duration = 70 sec
Samples           = 15
```

부하 전 5초 baseline과 부하 종료 후 5초 post-load 구간을 포함했다.

### Sender 결과

```text
Requested spans          = 30,000
Accepted spans           = 30,000

Requested requests       = 600
Accepted requests        = 600
Failed requests          = 0

Actual elapsed           = 60.000133 sec
Target rate              = 500 spans/sec
Observed accepted rate   = 500.00 spans/sec
```

Request latency:

```text
p50 = 2.017 ms
p95 = 2.314 ms
p99 = 2.418 ms
max = 7.436 ms
```

Sender schedule lag:

```text
p50 = 0.227 ms
p95 = 0.329 ms
p99 = 0.336 ms
max = 0.338 ms
```

따라서 sender가 목표 부하를 생성하지 못해 낮은 부하가 걸린 테스트는 아니었다.

### 데이터 정합성

최종 결과:

```text
Expected spans = 30,000
DB count       = 30,000 / 30,000

queue          = 0
in_flight      = 0

Collector accepted counter delta = 30,000
Collector refused counter delta  = 0
Collector sent counter delta     = 30,000
```

30,000건 모두 저장되었으며 요청 실패와 Collector refused 증가가 없었다.

### Collector와 DB 완료 시점 차이

Sender 종료 직후 첫 completion poll:

```text
DB count   = 29,450 / 30,000
queue      = 0
in_flight  = 0
```

다음 poll에서는:

```text
DB count   = 30,000 / 30,000
queue      = 0
in_flight  = 0
```

이었다.

따라서 Collector queue drain만으로 TimescaleDB persistence까지 완료됐다고 판단하지 않는다.

Sustained benchmark 완료 조건은 계속 다음 세 조건을 함께 사용한다.

```text
DB test span count = target
AND
Collector queue_size = 0
AND
Collector in_flight_requests = 0
```

### CPU

15개 resource sample 전체:

```text
Backend
  avg = 1.85%
  max = 7.08%

Collector
  avg = 1.74%
  max = 4.06%

TimescaleDB
  avg = 11.61%
  max = 15.71%
```

현재 500 spans/s 조건에서는 TimescaleDB의 CPU 사용량이 세 컴포넌트 중 가장 높게 관찰됐다.

그러나 해당 사용량만으로 TimescaleDB를 병목이라고 확정하지 않는다.

### Memory

```text
Backend
  baseline = 318.5 MiB
  average  = 319.37 MiB
  max      = 319.8 MiB
  final    = 319.7 MiB

Collector
  baseline = 48.48 MiB
  average  = 61.01 MiB
  max      = 65.57 MiB
  final    = 65.56 MiB

TimescaleDB
  baseline = 164.2 MiB
  average  = 176.80 MiB
  max      = 187.4 MiB
  final    = 187.4 MiB
```

Collector와 TimescaleDB의 memory가 부하 이후 baseline보다 높은 상태로 유지됐지만, 60초 단일 실험과 5초 post-load 관측만으로 memory leak이라고 판단하지 않는다.

향후 부하 단계에서 high-water 재사용 또는 지속 증가 여부를 비교한다.

### DB Connections

```text
connections
  baseline = 11
  average  = 11
  max      = 11
  final    = 11

active
  sampled max = 0
```

DB connection 총수는 증가하지 않았다.

다만 5초 sampling으로 짧은 DB transaction을 놓칠 수 있으므로 `active=0`을 DB가 사용되지 않았다는 의미로 해석하지 않는다.

Connection pool saturation 판단에는 더 세밀한 pool/DB metric이 필요하다.

### Collector Queue

5초 간격으로 수집한 모든 resource sample:

```text
queue_size max sampled = 0
in_flight max sampled  = 0
```

이는 500 spans/s에서 지속적인 queue backlog가 관찰되지 않았음을 의미한다.

5초 sample 사이의 짧은 queue 변동까지 존재하지 않았다고 단정하지 않는다.

### 결론

500 spans/s를 60초 동안 정확하게 유지한 synthetic workload에서:

- 30,000 Span 전량 저장
- 실패 요청 0
- Collector refused 0
- 지속적인 queue backlog 미관찰
- DB connection 총수 증가 없음
- Backend와 Collector CPU에 충분한 여유 관찰
- TimescaleDB가 상대적으로 가장 높은 CPU 사용량 기록

을 확인했다.

현재 조건에서는 500 spans/s sustained ingest가 안정적으로 처리되는 baseline으로 기록한다.

다음 단계에서는 동일한 방법으로 750 spans/s까지 한 단계만 부하를 증가시켜 CPU, memory, queue, 정합성 변화 추세를 비교한다.

---

## 2026-08-10 — 750 spans/s × 60초 Sustained Load

### 목적

500 spans/s sustained baseline 이후 동일한 측정 방식과 workload 구조를 유지하면서 목표 rate를 750 spans/s로 50% 증가시켜 데이터 정합성, Collector backlog, CPU, memory 및 DB connection 변화를 비교했다.

### 테스트 조건

```text
Target rate       = 750 spans/s
Duration          = 60 sec
Batch size        = 50 spans/request
Requests          = 900
Expected spans    = 45,000

Resource interval = 5 sec
Resource duration = 70 sec
Samples           = 15
```

### Sender

```text
Requested spans   = 45,000
Accepted spans    = 45,000

Requested requests = 900
Accepted requests  = 900
Failed requests    = 0

Elapsed            = 60.000122 sec
Observed rate      = 750.00 spans/sec
```

Request latency:

```text
p50 = 1.988 ms
p95 = 2.249 ms
p99 = 2.366 ms
max = 2.945 ms
```

Schedule lag:

```text
p50 = 0.193 ms
p95 = 0.325 ms
p99 = 0.339 ms
max = 0.468 ms
```

부하 발생기는 목표 750 spans/s를 안정적으로 유지했다.

### 데이터 정합성

```text
DB final              = 45,000 / 45,000
Collector accepted Δ  = 45,000
Collector refused Δ   = 0
Collector sent Δ      = 45,000
Final queue           = 0
Final in-flight       = 0
```

데이터 유실, 요청 실패 또는 Collector refusal이 관찰되지 않았다.

### Sender 종료 직후 DB 상태

첫 completion poll:

```text
DB        = 44,200 / 45,000
queue     = 0
in_flight = 0
```

다음 poll:

```text
DB        = 45,000 / 45,000
queue     = 0
in_flight = 0
```

500 spans/s 실험에서도 동일한 패턴이 관찰됐으므로 Collector queue drain과 DB persistence 완료를 동일한 시점으로 취급하지 않는다.

### 500 vs 750 CPU 비교

```text
                 500 avg/max       750 avg/max

Backend          1.85 / 7.08%      2.90 / 8.81%
Collector        1.74 / 4.06%      1.70 / 2.34%
TimescaleDB     11.61 / 15.71%    23.77 / 55.29%
```

Workload는 50% 증가했지만 TimescaleDB CPU 평균은 약 105%, sampled maximum은 약 252% 증가했다.

단일 5초 sampling 기반 결과만으로 TimescaleDB를 확정 병목으로 판단하지 않지만, 750 spans/s부터 가장 먼저 조사해야 할 병목 후보로 기록한다.

### Memory

```text
Backend
  baseline = 319.7 MiB
  avg      = 329.47 MiB
  max      = 330.5 MiB
  final    = 330.1 MiB

Collector
  baseline = 48.87 MiB
  avg      = 64.17 MiB
  max      = 67.78 MiB
  final    = 67.77 MiB

TimescaleDB
  baseline = 187.5 MiB
  avg      = 207.04 MiB
  max      = 224.5 MiB
  final    = 220.3 MiB
```

Collector baseline이 이전 실험 종료 high-water보다 다시 낮아졌으므로 현재 결과만으로 지속적 memory leak 패턴은 확인되지 않는다.

TimescaleDB memory high-water는 증가했으므로 이후 테스트에서 추세를 계속 비교한다.

### DB Connections

```text
connections
  baseline = 11
  avg      = 11
  max      = 11
  final    = 11

active
  avg = 0.07
  max = 1
```

Connection 수 증가 또는 connection exhaustion 징후는 관찰되지 않았다.

### Collector Queue

500 spans/s 테스트에서는 5초 sampling 동안 queue가 관찰되지 않았지만 750 spans/s에서는:

```text
queue sampled max = 750
in_flight max      = 1
```

이 처음 관찰됐다.

최종적으로 queue는 0까지 drain됐고 refused도 없었으므로 750 spans/s를 처리하지 못한 상태는 아니다.

다만 750이라는 queue metric의 단위를 span 수로 임의 해석하지 않는다. Queue sizer 설정을 확인한 후 의미를 확정한다.

### 결론

750 spans/s를 60초 동안 정확히 유지하면서 45,000 Span 전량 저장과 최종 queue drain에 성공했다.

그러나 500 spans/s 대비:

- Collector queue backlog 최초 관찰
- TimescaleDB CPU의 비선형적 증가
- TimescaleDB memory high-water 증가

가 확인됐다.

따라서 바로 1,000 spans/s로 부하를 증가시키지 않고 resource time-series를 분석하여 queue backlog와 TimescaleDB CPU 증가 시점의 상관관계를 먼저 확인한다.

---

### 750 spans/s Resource Time-Series 추가 분석

750 spans/s sustained-load의 5초 resource time-series를 분석했다.

부하 후반부:

```text
sec   DB CPU   queue   in-flight   accepted Δ   sent Δ
55    21.05%     0         0          3750       3750
60    19.85%   750         1          3750       3050
65    48.31%     0         0          2150       3050
70     0.04%     0         0             0          0
```

60초 sample에서 receiver accepted 증가량보다 exporter sent 증가량이 700 적었으며 queue metric 750과 in-flight 1이 동시에 관찰됐다.

다음 sample에서는 accepted 증가량 2,150보다 sent 증가량이 3,050으로 더 많았고 queue가 다시 0이 됐다.

따라서 지속적으로 증가하는 queue backlog가 아니라 부하 후반에 발생한 일시적인 exporter backlog가 이후 drain된 패턴으로 해석한다.

receiver accepted와 exporter sent counter 차이는 전체 pipeline 내 미전송량의 참고 신호지만 `queue_size`와 동일한 값으로 해석하지 않는다. Span은 batch processor, exporter worker 및 in-flight 상태 등에 존재할 수 있으며 metric snapshot도 완전히 원자적으로 수집되지 않는다.

TimescaleDB CPU 최대값과 queue 발생 시점도 일치하지 않았다.

```text
DB CPU maximum:
t=45 → 55.29%, queue=0

queue sampled maximum:
t=60 → queue=750, DB CPU=19.85%
```

따라서 현재 데이터만으로 TimescaleDB CPU spike가 Collector queue 발생의 직접 원인이라고 판단하지 않는다.

TimescaleDB CPU 자체는 sustained load 동안 burst 형태로 증가하는 패턴을 보였으므로 Collector batch 설정, exporter queue 설정, Backend JDBC batch 및 DB connection 설정을 다음 단계에서 읽기 전용으로 확인한다.

5초 resource sample은 한 행 내부에서도 Docker stats, PostgreSQL, Collector metric을 순차적으로 조회하므로 각 값이 완전히 동일한 순간의 snapshot은 아니다. 현재 time-series는 병목 후보 탐색용으로 사용하고 인과관계 증명에는 사용하지 않는다.

---

### 750 spans/s Collector Batch 동작 추가 검증

750 spans/s sustained test의 Backend runtime 로그를 분석하여 Collector가 실제 Backend로 전달한 request 크기를 확인했다.

Sender는 다음과 같이 Collector에 telemetry를 전달했다.

```text
900 requests
50 spans/request
총 45,000 spans
```

반면 Backend의 `OtlpTraceController` INFO 로그에는 총 61개의 저장 요청이 기록됐다.

실제 request 크기 분포:

```text
550 spans × 1
750 spans × 56
800 spans × 3
 50 spans × 1

Total = 45,000 spans
```

따라서 Collector batch processor가 900개의 작은 OTLP 요청을 61개의 큰 Backend 요청으로 합쳐 전달했음을 확인했다.

Backend request 평균 크기는 약 737.7 spans였다.

현재 Collector 설정:

```text
batch.timeout = 1s
batch.send_batch_size = 1024
```

과 750 spans/s의 입력 rate를 함께 보면, 1024 Span threshold에 도달하기 전에 약 1초 timeout으로 batch가 flush되어 약 750 Span 단위의 Backend request가 만들어지는 패턴과 실제 runtime 로그가 일치한다.

따라서 750 spans/s 테스트에서 한 차례 관찰된:

```text
queue_size = 750
in_flight = 1
```

은 지속적으로 누적된 overload backlog라기보다 약 750 Span batch 하나가 exporter sending queue를 통과하던 순간을 resource monitor가 포착했을 가능성이 높다.

다음 resource sample에서는:

```text
queue_size = 0
in_flight = 0
```

으로 정상 drain되었고 최종 counter도:

```text
accepted delta = 45,000
sent delta     = 45,000
refused delta  = 0
```

였다.

따라서 현재 750 spans/s에서는 지속적인 Collector queue backlog 또는 처리 한계 도달이 확인됐다고 판단하지 않는다.

### JDBC batch DEBUG 로그

`JdbcSpanWriter`의 batch completion 로그는 `DEBUG` level로 구현되어 있다.

현재 런타임에서는 Controller INFO 로그가 출력되고 별도의 DEBUG logging override가 없으므로 `JDBC Span batch completed` 로그가 보이지 않는 것은 write path 미실행을 의미하지 않는다.

성능 분석을 위해 운영 Backend 전체 로그 레벨을 DEBUG로 변경하지 않는다.

### DB count와 Collector queue snapshot 해석 정정

기존 sustained test에서 다음 결과가 관찰됐다.

```text
DB count = 44,200 / 45,000
queue    = 0
in_flight = 0
```

이 결과를 Collector queue drain 이후에도 DB persistence가 완료되지 않았다는 증거로 해석했으나, benchmark wrapper는 DB count와 Collector metrics를 하나의 원자적 snapshot으로 읽지 않는다.

현재 조회 순서는:

```text
DB count
→ queue_size
→ in_flight
```

이므로 DB 조회 이후 Collector metric을 조회하기 전에 마지막 Backend request가 완료됐을 가능성을 배제할 수 없다.

따라서 해당 결과만으로 Collector queue drain과 DB commit 시점의 순서를 판단하지 않는다.

Benchmark 완료 조건 자체는 데이터 정합성을 위해 계속 다음 세 조건을 함께 사용한다.

```text
DB test span count = target
AND
queue_size = 0
AND
in_flight = 0
```

---

### DB Connection Metric 해석 보정

750 spans/s 분석 과정에서 PostgreSQL connection 출처를 직접 확인했다.

```text
PostgreSQL JDBC Driver                   = 10
TimescaleDB Background Worker Scheduler  = 1
```

기존 resource monitor가 기록한 `db_connections=11`은 애플리케이션 JDBC connection 11개가 아니라 애플리케이션 JDBC 10개와 TimescaleDB 내부 background connection 1개의 합계였다.

따라서 기존 500/750 sustained test의 DB connection 결과를 애플리케이션 connection pool 크기 11로 해석하지 않는다.

Resource monitor의 DB connection query를 원격 client connection만 집계하도록 변경하여 TimescaleDB 내부 background worker와 monitor 자체 connection을 제외한다.

또한 현재 10개의 PostgreSQL JDBC connection이 존재한다는 사실만으로 Hikari maximum pool size가 10이라고 단정하지 않는다. Runtime configuration과 실제 pool saturation 여부를 별도로 검증한다.

### 750 spans/s 실제 Backend Request / JDBC Batch 처리 단위

750 spans/s sustained test에서 Sender는 Collector에:

```text
900 requests × 50 spans
= 45,000 spans
```

를 전달했다.

Backend runtime INFO 로그에서는:

```text
50 spans  × 1 request
550 spans × 1 request
750 spans × 56 requests
800 spans × 3 requests

Backend requests = 61
Received spans   = 45,000
```

이 확인됐다.

현재 source의 `JdbcSpanWriter`는 configured batch size 1000보다 큰 입력만 chunk로 분할한다.

750 테스트에서 관찰된 모든 Backend request가 1000 spans 이하였으므로 현재 source 구현 기준으로 각 Backend request는 하나의 JDBC batch chunk로 처리된다.

따라서 750 spans/s workload에서 Collector batching이 900개의 작은 sender request를 61개의 큰 Backend request로 합쳐 DB write 호출 횟수를 크게 줄이는 구조임을 확인했다.

---

## 2026-08-10 — 875 spans/s × 60초 Sustained Load

### 테스트 목적

500 및 750 spans/s sustained test 이후 설정을 변경하지 않고 부하를 875 spans/s까지 증가시켜 데이터 정합성, Collector backlog, Backend batch 처리 단위 및 시스템 자원 사용 추세를 비교했다.

### 테스트 조건

```text
Target rate       = 875 spans/s
Duration          = 60 sec
Sender batch      = 50 spans/request
Expected spans    = 52,500
Sender requests   = 1,050

Resource interval = 5 sec
Resource duration = 70 sec
```

### Sender 결과

```text
Requested spans         = 52,500
Accepted spans          = 52,500
Requested requests      = 1,050
Accepted requests       = 1,050
Failed requests         = 0

Actual elapsed          = 60.000101 sec
Observed rate           = 875.00 spans/sec
```

Request latency:

```text
p50 = 1.947 ms
p95 = 2.222 ms
p99 = 2.345 ms
max = 7.236 ms
```

Schedule lag:

```text
p50 = 0.179 ms
p95 = 0.287 ms
p99 = 0.301 ms
max = 0.329 ms
```

부하 발생기는 목표 rate를 정확하게 유지했다.

### 데이터 정합성 및 Collector

```text
DB final             = 52,500 / 52,500
Collector accepted Δ = 52,500
Collector sent Δ     = 52,500
Collector refused Δ  = 0

sampled queue max    = 0
sampled in-flight max = 0

final queue          = 0
final in-flight      = 0
```

875 spans/s에서는 resource sampling 동안 exporter queue backlog가 관찰되지 않았다.

### CPU

```text
Backend
  avg = 1.24%
  max = 2.11%

Collector
  avg = 1.84%
  max = 4.53%

TimescaleDB
  avg = 18.73%
  max = 27.01%
```

750 spans/s 테스트에서는 TimescaleDB CPU가 평균 23.77%, 최대 55.29%였지만 더 높은 875 spans/s에서는 평균 18.73%, 최대 27.01%로 낮아졌다.

따라서 750 테스트에서 관찰된 DB CPU peak를 지속적인 CPU saturation 또는 처리 한계의 증거로 해석하지 않는다.

### Memory

```text
Backend
  baseline = 330.2 MiB
  max      = 330.2 MiB
  final    = 330.2 MiB

Collector
  baseline = 48.96 MiB
  max      = 67.89 MiB
  final    = 67.87 MiB

TimescaleDB
  baseline = 219.4 MiB
  max      = 257.8 MiB
  final    = 257.8 MiB
```

TimescaleDB memory high-water는 이전 테스트보다 증가했다.

현재 결과만으로 memory leak이라고 판단하지 않으며 이후 load 단계와 idle 상태에서 추세를 계속 관찰한다.

### Application DB Connections

Resource monitor의 connection filter를 보정한 이후:

```text
connections = 10
active sampled max = 0
idle = 10
```

으로 측정됐다.

PostgreSQL에서 별도로 확인한 `PostgreSQL JDBC Driver` connection 10개와 일치한다.

5초 sampling에서 `active=0`이 관찰됐다고 해서 JDBC connection이 사용되지 않았다고 해석하지 않는다.

### Collector → Backend 실제 Batch 크기

고정된 테스트 시간 범위의 Backend runtime INFO 로그를 분석했다.

```text
Backend requests = 61
Received spans   = 52,500

request distribution:

250 spans × 1
500 spans × 1
850 spans × 27
900 spans × 32
```

통계:

```text
minimum request size = 250 spans
maximum request size = 900 spans
average request size = 860.66 spans
```

Sender가 Collector에 1,050개의 50-span 요청을 전달했지만 Collector batch processor가 이를 61개의 Backend 요청으로 합쳤다.

Backend 요청 수가 sender 요청 대비 약 17.2분의 1로 감소했다.

### JDBC Batch 경계

현재 Backend source의 JDBC batch size:

```text
1000 spans
```

875 테스트의 최대 Backend request:

```text
900 spans
```

이며:

```text
requests_over_jdbc_batch_size = 0
```

을 확인했다.

따라서 현재 source 구현 기준으로 875 테스트에서는 모든 Backend request가 단일 JDBC batch chunk 안에서 처리될 수 있다.

### 결론

875 spans/s를 60초 지속한 synthetic workload에서:

- 52,500 Span 전량 저장
- 요청 실패 0
- Collector refused 0
- sampled queue backlog 0
- 최종 queue/in-flight drain 완료
- Application JDBC connection 10개 유지
- Sender rate 정확성 유지

를 확인했다.

750 테스트보다 높은 load임에도 DB CPU와 sampled queue 상태가 악화되지 않았으므로 750에서 관찰된 단일 CPU/queue peak를 처리 한계로 판단하지 않는다.

다음 단계는 Collector `send_batch_size=1024`와 Backend `JDBC batch-size=1000`에 근접하는 1,000 spans/s sustained test다.

---

## 2026-08-10 — 1,000 spans/s Sustained Load 3회 재현성 검증

### 목적

Collector `send_batch_size=1024`와 Backend JDBC `batch-size=1000` 경계에 근접하는 1,000 spans/s에서 단일 실행 결과의 변동성을 확인하기 위해 동일한 60초 sustained workload를 총 3회 수행했다.

### 공통 조건

```text
Target rate = 1,000 spans/s
Duration    = 60 sec
Expected    = 60,000 spans
Sender batch = 50 spans/request
```

세 실행 모두 최종 DB count 60,000/60,000과 queue/in-flight drain을 확인했다.

### Run 1

```text
DB = 60,000 / 60,000

queue_size=1000:
t=35, 40, 45, 50, 55, 60

in_flight=1:
t=35, 40, 45, 50, 55, 60
```

Full-rate 구간인 t=10~60의 TimescaleDB CPU:

```text
average = 35.55%
maximum = 76.32%
median  = 29.38%
```

한 batch 수준의 queue가 약 25초 동안 유지됐지만 1000→2000→3000처럼 증가하지 않았으며 테스트 종료 후 정상 drain됐다.

### Run 2

5초 resource sampling에서 queue와 in-flight backlog가 관찰되지 않았다.

t=10~60 TimescaleDB CPU:

```text
average = 25.88%
maximum = 27.39%
```

최종 결과:

```text
DB = 60,000 / 60,000
queue = 0
in_flight = 0
```

### Run 3

세 번째 동일 조건 테스트에서도 resource sampling 전 구간에서 queue 및 in-flight가 관찰되지 않았다.

t=10~60 TimescaleDB CPU:

```text
average = 26.06%
maximum = 27.71%
```

최종 결과:

```text
DB = 60,000 / 60,000
queue = 0
in_flight = 0
```

### 재현성 분석

Run2와 Run3의 full-rate TimescaleDB CPU 평균은:

```text
Run2 = 25.88%
Run3 = 26.06%
```

으로 매우 유사했다.

따라서 정상적인 1,000 spans/s steady-state의 TimescaleDB CPU 사용량은 현재 테스트 조건에서 약 26% 수준으로 관찰됐다.

Run1의 높은 CPU spike 및 `queue_size=1000` standing 상태는 동일 조건 Run2와 Run3에서는 재현되지 않았다.

현재 결과를 다음과 같이 해석한다.

```text
1,000 spans/s sustained ingest
- 데이터 정합성: 3/3 PASS
- 지속적인 queue 증가: 미관찰
- sampled standing queue: 1/3 run에서 관찰
- queue 없는 run: 2/3
- DB CPU steady-state: 약 26% 수준으로 2회 재현
```

따라서 1,000 spans/s를 현재 시스템의 sustained throughput 한계로 판단하지 않는다.

다만 한 번의 실행에서 exporter queue가 장시간 유지된 변동성이 관찰됐으므로 이후 더 높은 부하에서도 queue 발생 빈도와 drain 여부를 계속 관찰한다.

### 1,000 spans/s Batch Boundary

첫 번째 1,000 spans/s 실행의 Backend runtime 로그에서:

```text
Backend requests = 61
Received spans   = 60,000

100 spans  × 1
750 spans  × 1
950 spans  × 1
1000 spans × 54
1050 spans × 4
```

를 확인했다.

```text
average Backend request = 983.61 spans
requests == JDBC batch-size = 54
requests > JDBC batch-size  = 4
```

현재 source 구현 기준으로 1050-span request는 JDBC batch-size 1000에 의해 1000 + 50 두 chunk로 분리될 수 있다.

1,000 spans/s부터 Collector batch와 Backend JDBC batch 경계가 실제 runtime workload에 영향을 주기 시작했지만 데이터 정합성 또는 지속적인 queue 증가 문제는 확인되지 않았다.

---

## 2026-08-10 — 1,125 spans/s Sustained Load와 Batch Boundary 검증

### 테스트 조건

```text
Target rate       = 1,125 spans/s
Duration          = 60 sec
Expected spans    = 67,500
Sender batch      = 50 spans/request
Sender requests   = 1,350
```

### 결과

```text
Observed rate     = 1,125.00 spans/s

Requested spans   = 67,500
Accepted spans    = 67,500
DB final          = 67,500 / 67,500

Requested requests = 1,350
Accepted requests  = 1,350
Failed requests    = 0

Final queue       = 0
Final in-flight   = 0
```

Sender request latency:

```text
p50 = 1.890 ms
p95 = 2.186 ms
p99 = 2.293 ms
max = 7.910 ms
```

Schedule lag:

```text
p50 = 0.157 ms
p95 = 0.279 ms
p99 = 0.298 ms
max = 0.356 ms
```

### Collector → Backend Request Distribution

Backend runtime 로그:

```text
450 spans  × 1
900 spans  × 1
1050 spans × 63
```

통계:

```text
Backend requests         = 65
Received spans           = 67,500
Average request size     = 1,038.46 spans
Minimum request size     = 450
Maximum request size     = 1,050
```

Sender가 50 Span 단위로 Collector에 데이터를 보내고 Collector `send_batch_size`가 1024이므로:

```text
20 × 50 = 1000
21 × 50 = 1050
```

이 된다.

1,125 spans/s에서는 1초 timeout 이전에 1024 threshold를 넘어가는 조건이 만들어지며 실제 Backend request 65개 중 63개가 1050 Span이었다.

따라서 현재 workload에서는 Collector batch size threshold와 Sender input batch 단위가 결합되어 1050 Span이 주된 Backend request 크기로 형성되는 것을 runtime 데이터로 확인했다.

### JDBC Batch Boundary

Backend JDBC batch size:

```text
1000
```

Backend request 65개 중:

```text
below 1000 = 2
equal 1000 = 0
over 1000  = 63
```

이었다.

현재 `JdbcSpanWriter` source의 chunking 로직을 적용하면 1050 Span request는:

```text
1000 + 50
```

두 JDBC chunk로 분리된다.

계산된 처리 단위:

```text
estimated JDBC chunks       = 128
estimated chunks/request    = 1.969
```

1,125 spans/s부터 Collector가 생성하는 대부분의 Backend request가 JDBC batch-size를 초과하면서 요청당 JDBC chunk 수가 사실상 두 개로 증가하는 경계에 진입했다.

이는 실제 JDBC invocation counter가 아니라 현재 확인한 source chunking 로직과 runtime Backend request size를 기반으로 계산한 값이다.

### Collector Queue Time-Series

5초 sampling에서:

```text
t=15 → queue=1050, in_flight=1
t=20 → queue=0

t=30 → queue=1050, in_flight=1
t=35 → queue=0

t=45 → queue=1050, in_flight=1
t=50 → queue=0

t=55 → queue=1050, in_flight=1
t=60 → queue=0
```

이 관찰됐다.

queue가 1050→2100→3150처럼 시간에 따라 증가하지 않았으며 매번 다음 sample에서 정상적으로 0까지 drain됐다.

accepted/sent counter에서도 queue가 관찰된 다음 구간에 exporter sent 증가량이 accepted 증가량보다 커져 backlog를 따라잡는 패턴이 반복됐다.

따라서 현재 queue 현상은 지속적인 overload보다 한 Collector batch 수준의 주기적 pipeline handoff로 해석한다.

### TimescaleDB CPU

Full-rate 구간 t=10~60:

```text
average = 31.43%
median  = 26.61%
minimum = 25.91%
maximum = 60.61%
```

1,000 spans/s의 재현성이 높았던 Run2/Run3 steady-state 평균 약 25.97%보다 평균 CPU는 증가했다.

그러나 median은 26.61%였고 일부 높은 sample이 평균을 끌어올렸다.

queue 발생 시점과 DB CPU peak도 지속적으로 일치하지 않았으므로 JDBC chunk 분할 또는 queue 발생을 DB CPU spike의 직접 원인이라고 아직 판단하지 않는다.

### 결론

1,125 spans/s synthetic sustained workload에서:

- 67,500 Span 전량 저장
- failed request 0
- 최종 queue/in-flight drain
- 지속 증가하는 queue 미관찰
- 대부분의 Collector batch가 1050 Span으로 형성
- Backend request 63/65가 JDBC batch-size 1000 초과
- TimescaleDB steady CPU 증가 관찰

을 확인했다.

현재 결과는 처리 실패나 saturation을 나타내지 않지만, Collector batch와 JDBC batch 크기 불일치가 실제 JDBC chunk 수를 증가시키는 구간에 진입했으므로 이후 부하 단계에서도 DB CPU와 queue 추세를 계속 측정한다.

---

## 2026-08-10 — 1,250 spans/s Sustained Load

### 테스트 조건

```text
Target rate     = 1,250 spans/s
Duration        = 60 sec
Expected spans  = 75,000
Sender batch    = 50 spans/request
Sender requests = 1,500
```

### 데이터 정합성

```text
Observed rate     = 1,250.00 spans/s
Requested spans   = 75,000
Accepted spans    = 75,000
DB final          = 75,000 / 75,000

Requested requests = 1,500
Accepted requests  = 1,500
Failed requests    = 0

Final queue       = 0
Final in-flight   = 0
```

Sender latency:

```text
p50 = 1.772 ms
p95 = 2.116 ms
p99 = 2.225 ms
max = 3.312 ms
```

Schedule lag:

```text
p50 = 0.161 ms
p95 = 0.279 ms
p99 = 0.287 ms
max = 1.900 ms
```

### Collector Queue Time-Series

5초 resource sampling에서:

```text
t=10 → queue=1050, in_flight=1
t=15 → queue=1050, in_flight=1
t=20 → queue=1050, in_flight=1

t=25 → queue=0
...
t=60 → queue=0
```

이 관찰됐다.

queue는 한 batch 수준인 1050에서 유지되다가 정상 drain됐으며 1050→2100→3150처럼 지속적으로 증가하지 않았다.

t=20 구간에서는:

```text
accepted delta = 6200
sent delta     = 6300
```

으로 exporter가 유입량보다 더 많이 처리했고 다음 sample부터 queue가 0이 됐다.

따라서 테스트 초반 일시적인 standing queue는 관찰됐지만 지속적인 overload backlog는 확인되지 않았다.

### TimescaleDB CPU

Full-rate 구간 t=10~60:

```text
average = 31.36%
median  = 30.98%
minimum = 26.81%
maximum = 43.84%
```

1,125 spans/s의 동일 구간:

```text
average = 31.43%
median  = 26.61%
maximum = 60.61%
```

과 비교하면 workload가 약 11.1% 증가했음에도 평균 CPU는 사실상 동일했고 maximum은 오히려 낮았다.

따라서 현재 데이터에서는 1,250 spans/s에서 TimescaleDB CPU saturation 또는 비선형적인 처리 비용 증가가 확인되지 않는다.

### Collector → Backend Request Distribution

```text
50 spans   × 1
400 spans  × 1
1050 spans × 71
```

통계:

```text
Backend requests     = 73
Received spans       = 75,000
Average request size = 1,027.40
Minimum              = 50
Maximum              = 1,050
```

73개 Backend request 중 71개가 1050 Span으로 구성됐다.

Sender batch 50과 Collector `send_batch_size=1024` 조합에서:

```text
20 × 50 = 1000
21 × 50 = 1050
```

이므로 1024 threshold를 초과하는 첫 입력 단위인 1050 Span이 지속적으로 Backend request 크기로 형성되는 동작이 재현됐다.

### JDBC Batch Boundary

Backend JDBC batch size:

```text
1000
```

이번 요청 분포:

```text
requests below 1000 = 2
requests equal 1000 = 0
requests over 1000  = 71
```

현재 source chunking 로직 기준:

```text
estimated JDBC chunks        = 144
estimated chunks per request = 1.973
```

이다.

대부분의 1050-span request는:

```text
1000 + 50
```

두 JDBC chunk로 분리된다.

1,125 spans/s에서도 동일한 batch 경계가 관찰됐으며 더 높은 1,250 spans/s에서도 데이터 정합성, sender latency 또는 지속 queue backlog 악화는 확인되지 않았다.

### 결론

1,250 spans/s × 60초 synthetic sustained workload에서:

- 75,000 Span 전량 저장
- failed request 0
- 최종 queue/in-flight drain
- 초기 한 batch 수준 queue 이후 완전 drain
- 지속 backlog 증가 없음
- TimescaleDB steady CPU 평균 약 31%
- 대부분의 Backend request가 1050 Span
- JDBC 1000 경계 초과 지속

를 확인했다.

현재 측정 조건에서 1,250 spans/s를 sustained throughput 한계로 판단할 근거는 없다.

---

### 1,375 spans/s 동일 조건 재현성 검증

1,375 spans/s 첫 테스트에서 TimescaleDB CPU 증가가 관찰되어 동일한 설정과 workload로 두 번째 60초 sustained test를 수행했다.

두 번째 실행도:

```text
Expected spans = 82,500
DB count       = 82,500 / 82,500
Final queue    = 0
Final in-flight = 0
```

으로 정상 완료됐다.

#### TimescaleDB CPU 재현성

Full-rate 구간 t=10~60 기준:

```text
1,250 spans/s
average = 31.36%
median  = 30.98%
maximum = 43.84%

1,375 spans/s Run1
average = 40.97%
median  = 36.63%
maximum = 83.14%

1,375 spans/s Run2
average = 36.79%
median  = 34.88%
maximum = 51.28%
```

두 1,375 spans/s 실행의 평균 CPU를 단순 평균하면 약 38.88%다.

첫 실행의 83.14% maximum은 두 번째 실행에서 재현되지 않았으므로 이를 정상 steady-state CPU 비용으로 판단하지 않는다.

그러나 두 실행 모두 1,250 spans/s의 steady-state CPU 평균 및 median보다 높은 수준을 보여 1,375 spans/s부터 TimescaleDB CPU 비용이 증가하는 추세는 재현된 것으로 기록한다.

#### Collector Queue

두 번째 실행에서는:

```text
t=10 → queue=1050
t=15 → queue=0

t=25 → queue=1050
t=30 → queue=0

t=35 → queue=1050
t=40 → queue=0

t=45 → queue=1050
t=50 → queue=0

t=55 → queue=1050
t=60 → queue=0
```

패턴이 관찰됐다.

queue는 한 Collector batch 수준에서 반복적으로 나타났지만 매번 다음 sample에서 0으로 drain됐다.

시간에 따라 queue가 1050→2100→3150처럼 증가하는 지속 backlog는 확인되지 않았다.

queue 발생 sample의 TimescaleDB CPU 역시 약 28~42% 범위로 다양해 queue와 DB CPU peak 사이의 직접적인 상관관계는 확인되지 않았다.

#### 결론

1,375 spans/s는 동일 조건 두 차례 모두 데이터 정합성과 최종 pipeline drain에 성공했다.

현재 상태는 sustained throughput 한계를 초과한 상태로 판단하지 않는다.

다만 1,250→1,375 workload 증가 시 TimescaleDB steady-state CPU 상승이 반복 관찰됐으므로 이후 부하 단계에서는 DB CPU headroom을 주요 성능 경계 지표로 함께 사용한다.

아직 queue 누적, refused, 데이터 유실 또는 CPU saturation이 확인되지 않았으므로 현재 설정을 변경하거나 batch tuning을 수행하지 않는다.

---

## 2026-08-10 — 1,500 spans/s Sustained Load

### 테스트 조건

```text
Target rate      = 1,500 spans/s
Duration         = 60 sec
Expected spans   = 90,000
Sender batch     = 50 spans/request
Sender requests  = 1,800
```

### 데이터 정합성

```text
Observed rate      = 1,500.00 spans/s
Requested spans    = 90,000
Accepted spans     = 90,000
DB final           = 90,000 / 90,000

Requested requests = 1,800
Accepted requests  = 1,800
Failed requests    = 0

Final queue         = 0
Final in-flight     = 0
```

서비스 restart 증가도 관찰되지 않았다.

### Sender

Request latency:

```text
p50 = 1.359 ms
p95 = 2.077 ms
p99 = 2.200 ms
max = 6.972 ms
```

Schedule lag:

```text
p50 = 0.111 ms
p95 = 0.278 ms
p99 = 0.289 ms
max = 3.957 ms
```

Maximum schedule lag outlier는 존재했지만 p95/p99는 0.3ms 이하로 유지되어 지속적인 sender pacing 문제는 관찰되지 않았다.

### TimescaleDB CPU

Full-rate 구간 t=10~60:

```text
average = 34.47%
median  = 31.80%
minimum = 27.03%
maximum = 50.75%
```

1,375 spans/s 반복 실험:

```text
Run1 average = 40.97%
Run2 average = 36.79%
```

과 비교하면 workload를 1,500 spans/s까지 증가시켰음에도 TimescaleDB CPU가 더 높아지지 않았다.

따라서 1,375에서 관찰된 CPU 상승을 sustained throughput saturation의 증거로 판단하지 않는다.

현재까지 run 간 CPU 변동과 순간 spike가 존재하지만 1,500 spans/s에서도 높은 CPU가 지속적으로 유지되는 상태는 관찰되지 않았다.

### Collector Queue

Resource time-series에서:

```text
t=5  → queue=1050
t=10 → queue=1050
t=15 → queue=1050
t=20 → queue=0

t=40 → queue=1050
t=45 → queue=1050
t=50 → queue=1050
t=55 → queue=0
```

패턴이 관찰됐다.

queue는 한 Collector batch 수준에서 일정 시간 유지됐지만 시간에 따라 증가하지 않았으며 이후 0으로 drain됐다.

queue가 해소된 구간에서는 exporter sent 증가량이 accepted 증가량보다 높아 backlog를 따라잡는 패턴도 확인됐다.

따라서 현재 상태를 지속적인 overload backlog로 판단하지 않는다.

### Collector → Backend Batch

Backend runtime request 분포:

```text
750 spans  × 1
1050 spans × 85
```

통계:

```text
Backend requests     = 86
Received spans       = 90,000
Average request size = 1,046.51 spans

Requests below 1000 = 1
Requests equal 1000 = 0
Requests over 1000  = 85
```

Sender가 Collector에 1,800개의 요청을 전달했지만 Backend에는 86개 request가 전달됐다.

Collector batching에 의해 Backend HTTP request 수가 sender request 대비 약 20.9분의 1 수준으로 감소했다.

### JDBC Chunk Boundary

현재 source의 JDBC batch-size 1000을 적용하면:

```text
Estimated JDBC chunks       = 171
Estimated chunks/request    = 1.988
```

이다.

대부분의 Backend request가 1050 Span이므로 현재 source 구현 기준 사실상:

```text
1000 + 50
```

두 JDBC chunk로 처리되는 패턴이 유지됐다.

그럼에도 90,000 Span 전량 저장, failed request 0 및 최종 pipeline drain을 확인했다.

### 결론

1,500 spans/s × 60초 synthetic sustained workload에서:

- 90,000 Span 전량 저장
- Sender failed request 0
- 최종 Collector queue/in-flight drain
- 서비스 restart 증가 없음
- 지속 증가하는 queue 미관찰
- TimescaleDB steady-state CPU saturation 미관찰
- 대부분의 Backend request가 JDBC batch-size를 초과하는 조건에서도 정상 처리

를 확인했다.

현재 테스트 조건에서는 1,500 spans/s를 sustained throughput 한계로 판단할 근거가 없다.

---

## 2026-08-10 — 1,625 spans/s Sustained Load 재현성 검증

### 테스트 조건

```text
Target rate     = 1,625 spans/s
Duration        = 60 sec
Expected spans  = 97,500
Sender batch    = 50
```

동일한 설정과 workload로 2회 테스트했다.

### 데이터 정합성

두 실행 모두:

```text
Expected spans = 97,500
DB final       = 97,500 / 97,500
Failed         = 0
Final queue    = 0
Final in-flight = 0
```

으로 정상 완료됐다.

### TimescaleDB CPU 재현성

Full-rate 구간 t=10~60:

```text
1,500 spans/s
average = 34.47%
median  = 31.80%
maximum = 50.75%

1,625 Run1
average = 44.65%
median  = 41.21%
maximum = 88.82%

1,625 Run2
average = 47.11%
median  = 47.49%
maximum = 70.45%
```

두 1,625 run의 average를 단순 평균하면 약 45.88%다.

Run1의 88.82% maximum 자체는 Run2에서 재현되지 않았으므로 단일 peak를 saturation 증거로 사용하지 않는다.

그러나 average와 median이 두 실행 모두 1,500 spans/s보다 명확하게 높은 수준으로 나타나 1,625 spans/s부터 TimescaleDB CPU 비용이 상승하는 현상은 재현된 것으로 판단한다.

### Collector Queue

Run2의 5초 sampling:

```text
t=10 → queue=1050
t=15 → queue=0

t=20 → queue=1050
t=25 → queue=1050
t=30 → queue=0

t=40 → queue=1050
t=45 → queue=1050
t=50 → queue=0

t=60 → queue=1050
t=65 → queue=0
```

queue는 한 Collector batch 수준에서 일정 시간 유지되었지만 시간에 따라 계속 증가하지 않았으며 모든 backlog가 정상 drain됐다.

따라서 현재 queue 패턴은 지속적인 exporter overload로 판단하지 않는다.

Resource monitoring은 5초 sampling이며 각 구성 요소를 순차적으로 조회하기 때문에 짧은 transient queue와 구성 요소 간 정확한 event ordering은 이 데이터만으로 확정하지 않는다.

### Sender

Run2:

```text
Observed accepted rate = 1,625.00 spans/s

Request latency
p50 = 1.147 ms
p95 = 2.066 ms
p99 = 2.210 ms
max = 9.901 ms

Schedule lag
p50 = 0.115 ms
p95 = 0.277 ms
p99 = 0.301 ms
max = 5.096 ms
```

tail outlier는 있었지만 p95/p99 수준은 낮게 유지되어 sustained sender pacing 문제는 확인되지 않았다.

### 결론

1,625 spans/s는 동일 조건 두 차례 모두 데이터 정합성과 최종 pipeline drain에 성공했다.

따라서 현재 synthetic workload에서 1,625 spans/s를 throughput saturation으로 판단하지 않는다.

다만 TimescaleDB steady CPU 상승이 두 실행에서 재현됐으므로 1,625 spans/s부터 DB headroom 감소가 실제 성능 추세로 나타나는 구간으로 기록한다.

아직 queue 누적, failed request, 데이터 유실 또는 지속 CPU saturation이 확인되지 않았으므로 batch 또는 DB 설정은 변경하지 않는다.

---

## 2026-08-10 — 1,750 spans/s Sustained Load 검증

### 테스트 조건

```text
Target rate      = 1,750 spans/s
Duration         = 60 sec
Expected spans   = 105,000
Sender batch     = 50
Sender requests  = 2,100
```

### 결과

```text
Observed rate      = 1,750.00 spans/s

Requested spans    = 105,000
Accepted spans     = 105,000
DB final           = 105,000 / 105,000

Requested requests = 2,100
Accepted requests  = 2,100
Failed requests    = 0

Final queue         = 0
Final in-flight     = 0
```

이번 출력에서는 Collector refused metric을 직접 확인하지 않았으므로 refused=0으로 기록하지 않는다.

### Sender

```text
Request latency
p50 = 0.984 ms
p95 = 2.023 ms
p99 = 2.130 ms
max = 5.283 ms

Schedule lag
p50 = 0.111 ms
p95 = 0.276 ms
p99 = 0.287 ms
max = 0.328 ms
```

1,750 spans/s에서도 sender pacing과 request latency의 지속적인 악화는 관찰되지 않았다.

### TimescaleDB CPU

Full-rate 구간 t=10~60:

```text
average = 45.95%
median  = 48.52%
minimum = 33.62%
maximum = 57.37%
```

비교:

```text
1,500
avg = 34.47%
median = 31.80%

1,625 Run1
avg = 44.65%
median = 41.21%

1,625 Run2
avg = 47.11%
median = 47.49%

1,750
avg = 45.95%
median = 48.52%
```

1,625 두 실행의 average 단순 평균은 약 45.88%이며 1,750의 45.95%와 사실상 동일하다.

따라서 1,500→1,625 구간에서 관찰된 DB CPU 수준 상승이 1,750에서 계속 비선형적으로 악화되지는 않았다.

1,625 Run1에서 관찰된 88.82% peak 역시 1,750에서는 재현되지 않았다.

현재 결과에서는 지속적인 TimescaleDB CPU saturation이 확인되지 않는다.

### Collector Queue

5초 sampling:

```text
t=15 → queue=1050
t=20 → queue=1050
t=25 → queue=0

t=30 → queue=1050
t=35 → queue=1050
t=40 → queue=0

t=50 → queue=1050
t=55 → queue=0
```

sampled queue는 최대 1050으로 한 Collector batch 수준을 넘지 않았으며 시간이 지날수록 증가하는 backlog는 관찰되지 않았다.

queue가 drain되는 구간에서는 exporter sent 증가량이 accepted 증가량을 초과하는 패턴도 확인됐다.

Resource monitoring은 5초 sampling이므로 sample 사이의 짧은 transient queue 존재 여부까지 배제하지 않는다.

### Backend Batch

```text
250 spans  × 1
800 spans  × 1
1050 spans × 99
```

통계:

```text
Backend requests     = 101
Received spans       = 105,000
Average request size = 1,039.60

Requests below 1000 = 2
Requests equal 1000 = 0
Requests over 1000  = 99
```

Sender의 2,100 requests가 Collector batching 이후 Backend에서는 101 requests로 감소했다.

Sender 대비 Backend request 수는 약 20.8분의 1 수준이다.

### JDBC Batch

현재 source의 JDBC batch-size 1000 기준:

```text
Estimated JDBC chunks        = 200
Estimated chunks per request = 1.980
```

대부분의 1050-span Backend request가 현재 source 기준 1000 + 50 두 JDBC chunk로 처리되는 조건에서도 105,000 Span 전량 저장에 성공했다.

### 결론

1,750 spans/s × 60초 synthetic sustained workload에서 데이터 정합성, sender rate 및 최종 pipeline drain을 확인했다.

1,625에서 증가했던 TimescaleDB CPU 수준은 1,750에서도 약 46% 수준으로 유지됐지만 추가적인 악화나 지속 saturation은 나타나지 않았다.

sampled Collector queue도 최대 한 batch 수준으로 유지됐으며 지속 증가하는 backlog는 관찰되지 않았다.

따라서 현재 측정 조건에서는 1,750 spans/s를 sustained throughput 한계로 판단할 근거가 없다.

---

## 2026-08-10 — 1,875 spans/s Standing Queue 재현성 검증

1,875 spans/s × 60초 sustained workload에서 장시간 유지되는 Collector queue가 관찰되어 동일 조건으로 두 번째 테스트를 수행했다.

### 데이터 정합성

두 실행 모두:

```text
Expected spans    = 112,500
DB final          = 112,500 / 112,500
Failed requests   = 0
Refused delta     = 0
Final queue       = 0
Final in-flight   = 0
```

으로 정상 완료됐다.

### Standing Queue 재현

Run1:

```text
t=25 ~ t=60 → queue=1050
t=65        → queue=0
```

Run2:

```text
t=20 ~ t=55 → queue=1050
t=60        → queue=0
```

두 실행에서 약 35초 동안 한 Collector batch 수준의 sampled queue가 연속적으로 유지됐다.

따라서 1,875 spans/s에서 장시간 standing queue가 나타나는 현상은 동일 조건 반복 테스트에서 재현됐다.

다만 두 테스트 모두:

```text
1050 → 2100 → 3150
```

처럼 queue size가 시간에 따라 증가하지 않았다.

최종적으로 queue와 in-flight가 모두 0으로 drain됐고 refused와 failed request도 발생하지 않았다.

따라서 현재 현상을 sustained overload backlog 또는 throughput saturation으로 판단하지 않는다.

5초 resource sampling이므로 sample 사이의 더 짧은 queue 변화를 모두 관찰한 것은 아니며, 각 resource row도 구성 요소별 순차 조회 결과이므로 정확한 event ordering을 보장하지 않는다.

### TimescaleDB CPU

Full-rate t=10~60:

```text
Run1
average = 52.76%
median  = 51.93%
maximum = 83.59%

Run2
average = 54.34%
median  = 44.79%
maximum = 131.70%
```

Run2에는 131.70% CPU sample이 하나 존재한다.

Docker container CPU metric은 멀티코어 환경에서 100%를 초과할 수 있으므로 해당 값을 단독으로 saturation 근거로 사용하지 않는다.

typical CPU 수준을 확인하기 위한 보조 계산으로 해당 한 sample을 제외하면 Run2:

```text
average ≈ 46.60%
median  ≈ 44.74%
```

이다.

이는 이상치를 삭제해 공식 benchmark 결과를 변경한다는 의미가 아니라 평균값이 한 sample에 얼마나 영향을 받는지를 확인하기 위한 분석이다.

현재 두 run에서는 TimescaleDB 부하 증가 흔적이 존재하지만 지속 CPU saturation이 재현됐다고 판단할 근거는 아직 부족하다.

### 결론

1,875 spans/s는 데이터 정합성 및 최종 pipeline drain 기준으로 두 번 모두 성공했다.

그러나 이전 낮은 rate에서 주기적으로 0까지 내려가던 queue와 달리, 1,875에서는 한 batch인 1050 Span이 약 35초 동안 유지되는 standing queue 현상이 2회 재현됐다.

현재 상태를 다음과 같이 분류한다.

```text
1,875 spans/s

데이터 정합성              PASS
failed                     0
refused                    0
최종 pipeline drain        PASS

standing queue             재현
queue size 증가            미관찰
지속 backlog               미확인
CPU saturation             미확인
sustained throughput limit 미도달
```

설정 변경이나 tuning은 아직 수행하지 않는다.

다음 성능 단계에서는 standing queue가 실제 증가형 backlog로 전환되는지를 확인한다.

---

## 2026-08-10 — 2,000 spans/s Sustained Load 검증

### 테스트 조건

```text
Target rate      = 2,000 spans/s
Duration         = 60 sec
Expected spans   = 120,000
Sender batch     = 50
Sender requests  = 2,400
```

### 데이터 정합성

```text
Observed rate      = 2,000.00 spans/s

Requested spans    = 120,000
Accepted spans     = 120,000
DB final           = 120,000 / 120,000

Requested requests = 2,400
Accepted requests  = 2,400
Failed requests    = 0

Refused delta      = 0
Final queue        = 0
Final in-flight    = 0
```

서비스 restart 증가도 관찰되지 않았다.

### Sender

```text
Request latency
p50 = 0.819 ms
p95 = 1.981 ms
p99 = 2.088 ms
max = 7.118 ms

Schedule lag
p50 = 0.108 ms
p95 = 0.273 ms
p99 = 0.283 ms
max = 3.158 ms
```

2,000 spans/s에서도 sender pacing 또는 request latency의 지속적인 악화는 관찰되지 않았다.

### Collector Queue

5초 resource sampling:

```text
t=5  → queue=1050
t=10 → queue=1050
t=15 → queue=1050
t=20 → queue=1050
t=25 → queue=1050
t=30 → queue=0

t=35 → queue=1050
t=40 → queue=0

t=45 → queue=1050
t=50 → queue=0

t=55 → queue=1050
t=60 → queue=0
```

초반에는 한 Collector batch 수준의 queue가 일정 시간 유지됐으나 이후 반복적으로 0까지 drain됐다.

queue size가:

```text
1050 → 2100 → 3150
```

처럼 증가하는 현상은 관찰되지 않았다.

1,875 spans/s에서는 약 35초 동안 queue=1050이 지속적으로 관찰됐지만, 더 높은 2,000 spans/s에서는 중후반에 반복적으로 0까지 drain됐다.

따라서 standing queue 지속시간이 workload와 단순 비례한다고 판단하지 않는다.

5초 sampling이므로 sample 사이의 더 짧은 transient queue를 모두 관찰한 것은 아니다.

### TimescaleDB CPU

Full-rate t=10~60:

```text
average = 56.00%
median  = 53.24%
minimum = 50.46%
maximum = 84.99%
```

84.99% sample 하나를 제외한 보조 통계:

```text
average ≈ 53.10%
median  ≈ 52.92%
```

이는 benchmark 결과에서 해당 sample을 제거한다는 의미가 아니라 단일 peak가 average에 미치는 영향을 확인하기 위한 분석이다.

최근 결과:

```text
1,750
avg = 45.95%
median = 48.52%

1,875 Run1
avg = 52.76%
median = 51.93%

1,875 Run2
avg = 54.34%
median = 44.79%

2,000
avg = 56.00%
median = 53.24%
```

2,000 spans/s에서는 TimescaleDB가 대략 50%대 CPU 수준을 지속적으로 사용하는 구간에 들어온 것으로 관찰된다.

그러나 지속 queue 증가, refused, failed request 또는 데이터 정합성 실패가 없으므로 현재 CPU 수준만으로 throughput saturation이라고 판단하지 않는다.

### Backend Batch

Backend runtime request:

```text
300 spans  × 1
1050 spans × 114
```

통계:

```text
Backend requests     = 115
Received spans       = 120,000
Average request size = 1,043.48

Requests below 1000 = 1
Requests equal 1000 = 0
Requests over 1000  = 114
```

Sender의 2,400 requests가 Collector batching 후 Backend에서는 115 requests로 감소했다.

이는 Sender 대비 Backend HTTP request 수가 약 20.9분의 1 수준임을 의미한다.

### JDBC Batch

현재 source의 JDBC batch-size 1000 기준:

```text
Estimated JDBC chunks        = 229
Estimated chunks per request = 1.991
```

대부분의 Backend request가 1050 Span으로 구성되어 현재 source 기준 대부분:

```text
1000 + 50
```

두 JDBC chunk로 처리된다.

이 조건에서도 총 120,000 Span 전량 저장을 확인했다.

### 결론

2,000 spans/s × 60초 synthetic sustained workload에서:

- 120,000 Span 전량 저장
- failed request 0
- refused delta 0
- 최종 queue/in-flight drain
- 증가형 Collector backlog 미관찰
- TimescaleDB 약 50%대 CPU 지속 사용
- 서비스 restart 증가 없음

을 확인했다.

현재 synthetic workload에서 검증된 최고 sustained rate는 2,000 spans/s다.

DB headroom은 이전보다 감소하고 있으나 아직 sustained throughput saturation으로 판단할 증거는 없다.

---

## 2026-08-10 — 2,125 spans/s Sustained Load 검증

### 테스트 조건

```text
Target rate      = 2,125 spans/s
Duration         = 60 sec
Expected spans   = 127,500
Sender batch     = 50
Sender requests  = 2,550
```

### 데이터 정합성

```text
Observed rate      = 2,125.00 spans/s

Requested spans    = 127,500
Accepted spans     = 127,500
DB final           = 127,500 / 127,500

Requested requests = 2,550
Accepted requests  = 2,550
Failed requests    = 0

Refused delta      = 0
Final queue        = 0
Final in-flight    = 0
```

서비스 restart 증가도 관찰되지 않았다.

### Sender

```text
Request latency
p50 = 0.813 ms
p95 = 1.984 ms
p99 = 2.145 ms
max = 2.944 ms

Schedule lag
p50 = 0.103 ms
p95 = 0.270 ms
p99 = 0.285 ms
max = 0.553 ms
```

2,125 spans/s에서도 sustained sender pacing 문제는 관찰되지 않았다.

### TimescaleDB CPU

Full-rate 구간 t=10~60:

```text
average = 58.52%
median  = 56.49%
minimum = 53.66%
maximum = 81.41%
```

직전 2,000 spans/s:

```text
average = 56.00%
median  = 53.24%
maximum = 84.99%
```

과 비교하면 workload는 6.25% 증가했고 DB CPU average와 median도 완만하게 증가했다.

그러나 대부분의 sample은 약 53~60% 수준이며 81.41%는 단일 sample이므로 이를 단독으로 saturation 근거로 사용하지 않는다.

현재 DB CPU headroom은 감소하고 있지만 지속적인 CPU saturation은 확인되지 않았다.

### Collector Queue

5초 sampling:

```text
t=10 → queue=1050
t=15 → queue=1050
t=20 → queue=0
t=25 → queue=0
t=30 → queue=0

t=35 → queue=1050
t=40 → queue=1050
t=45 → queue=1050
t=50 → queue=1050
t=55 → queue=1050
t=60 → queue=1050

t=65 → queue=0
```

후반 약 25초 동안 한 Collector batch 수준의 standing queue가 유지됐다.

그러나 queue size는 1050보다 증가하지 않았으며 테스트 종료 후 정상 drain됐다.

따라서 2,125 spans/s에서도 지속적으로 성장하는 exporter backlog는 확인되지 않았다.

### Backend Batch

```text
450 spans  × 1
1050 spans × 121
```

통계:

```text
Backend requests     = 122
Received spans       = 127,500
Average request size = 1,045.08

Requests below 1000 = 1
Requests equal 1000 = 0
Requests over 1000  = 121
```

Sender 2,550 requests가 Collector batching 후 Backend 122 requests로 감소했다.

### JDBC Batch

현재 source의 JDBC batch-size 1000 기준:

```text
Estimated JDBC chunks        = 243
Estimated chunks per request = 1.992
```

거의 모든 1050-span request가 현재 source 기준 1000 + 50 두 chunk로 처리되는 조건에서도 127,500 Span 전량 저장에 성공했다.

### 결론

2,125 spans/s × 60초 synthetic sustained workload에서:

- 127,500 Span 전량 저장
- failed request 0
- refused delta 0
- 최종 queue/in-flight drain
- sampled queue 최대 1050
- 증가형 backlog 미관찰
- TimescaleDB CPU average 약 58.5%
- 서비스 restart 증가 없음

을 확인했다.

현재 DB headroom은 점차 감소하고 있으나 2,125 spans/s를 sustained throughput ceiling으로 판단할 근거는 없다.

---

## 2026-08-10 — 2,250 spans/s Sustained Load 검증

### 테스트 조건

```text
Target rate      = 2,250 spans/s
Duration         = 60 sec
Expected spans   = 135,000
Sender batch     = 50
Sender requests  = 2,700
```

### 데이터 정합성

```text
Observed rate      = 2,250.00 spans/s

Requested spans    = 135,000
Accepted spans     = 135,000
DB final           = 135,000 / 135,000

Requested requests = 2,700
Accepted requests  = 2,700
Failed requests    = 0

Refused delta      = 0
Final queue        = 0
Final in-flight    = 0
```

서비스 restart 증가도 관찰되지 않았다.

### Sender

```text
Request latency
p50 = 0.782 ms
p95 = 1.942 ms
p99 = 2.087 ms
max = 7.381 ms

Schedule lag
p50 = 0.102 ms
p95 = 0.271 ms
p99 = 0.302 ms
max = 3.463 ms
```

2,250 spans/s에서도 sustained sender pacing 또는 request latency의 지속적인 악화는 관찰되지 않았다.

### TimescaleDB CPU

Full-rate 구간 t=10~60:

```text
average = 60.20%
median  = 59.70%
minimum = 53.53%
maximum = 67.09%
```

최근 측정:

```text
2,000
average = 56.00%
median  = 53.24%

2,125
average = 58.52%
median  = 56.49%

2,250
average = 60.20%
median  = 59.70%
```

2,250 spans/s에서는 모든 full-rate DB CPU sample이 약 53~67% 범위에 위치했다.

이전 run에서 관찰된 80% 이상의 단일 peak와 달리 이번에는 높은 CPU 사용량이 비교적 일정하게 유지됐다.

따라서 TimescaleDB CPU headroom이 단계적으로 감소하는 현상은 명확하지만, 아직 CPU saturation으로 판단할 수준은 아니다.

### Collector Queue

5초 sampling:

```text
t=10 → queue=1050
t=15 → queue=0
t=20 → queue=0

t=25 → queue=1050
t=30 → queue=1050
t=35 → queue=0

t=40 → queue=1050
t=45 → queue=1050
t=50 → queue=0

t=55 → queue=1050
t=60 → queue=1050
t=65 → queue=0
```

sampled queue는 최대 1050 Span이었다.

시간이 지날수록 2100, 3150 등으로 증가하는 backlog는 관찰되지 않았으며 테스트 종료 후 queue와 in-flight가 모두 정상 drain됐다.

따라서 2,250 spans/s에서도 지속적인 exporter overload는 확인되지 않았다.

### Backend Batch

```text
600 spans  × 1
1050 spans × 128
```

통계:

```text
Backend requests     = 129
Received spans       = 135,000
Average request size = 1,046.51

Requests below 1000 = 1
Requests equal 1000 = 0
Requests over 1000  = 128
```

Sender 2,700 requests가 Collector batching 이후 Backend 129 requests로 감소했다.

### JDBC Batch

현재 source의 JDBC batch-size 1000 기준:

```text
Estimated JDBC chunks        = 257
Estimated chunks per request = 1.992
```

대부분의 1050-span Backend request가 현재 source 기준 1000 + 50 두 JDBC chunk로 처리되는 조건에서도 135,000 Span 전량 저장에 성공했다.

### 결론

2,250 spans/s × 60초 synthetic sustained workload에서:

- 135,000 Span 전량 저장
- failed request 0
- refused delta 0
- 최종 queue/in-flight drain
- sampled queue 최대 1050
- growing backlog 미관찰
- TimescaleDB CPU average 약 60.2%
- TimescaleDB CPU median 약 59.7%
- 서비스 restart 증가 없음

을 확인했다.

현재 DB CPU headroom은 지속적으로 감소하고 있으나 2,250 spans/s를 sustained throughput ceiling으로 판단할 증거는 없다.

---

## 2026-08-10 — 2,375 spans/s DB Headroom 재현성 검증

2,375 spans/s × 60초 sustained workload에서 TimescaleDB CPU가 60%대 중반으로 상승하고 standing Collector queue가 관찰되어 동일 조건으로 두 번째 테스트를 수행했다.

### 데이터 정합성

두 실행 모두:

```text
Expected spans    = 142,500
DB final          = 142,500 / 142,500
Failed requests   = 0
Refused delta     = 0
Final queue       = 0
Final in-flight   = 0
```

으로 정상 완료됐다.

### TimescaleDB CPU 재현성

Full-rate t=10~60:

```text
Run1
average = 66.53%
median  = 65.80%
minimum = 60.03%
maximum = 73.91%

Run2
average = 65.80%
median  = 65.99%
minimum = 56.61%
maximum = 75.49%
```

두 실행의 average와 median이 거의 동일하게 재현됐다.

따라서 2,375 spans/s에서 TimescaleDB가 약 60%대 중반 CPU를 지속적으로 사용하는 현상은 단일 run 변동이 아니라 재현 가능한 workload 특성으로 판단한다.

### Collector Queue

Run2의 5초 sampling:

```text
t=10 → queue=1050
t=15 → queue=1050
t=20 → queue=0

t=25 → queue=1050
t=30 → queue=1050
t=35 → queue=1050
t=40 → queue=0

t=45 → queue=1050
t=50 → queue=1050
t=55 → queue=0

t=60 → queue=1050
t=65 → queue=0
```

한 Collector batch 수준의 standing queue가 반복적으로 나타났지만 queue size가 2100 이상으로 증가하지 않았고 최종적으로 정상 drain됐다.

따라서 2,375 spans/s에서 standing queue는 재현됐지만 growing backlog 또는 exporter saturation은 확인되지 않았다.

### 결론

2,375 spans/s는 동일 조건 두 차례 모두 데이터 정합성과 최종 pipeline drain에 성공했다.

동시에 TimescaleDB full-rate CPU가 약 66% 수준으로 재현되어 이 구간부터 DB headroom 감소가 명확한 성능 특성으로 확인됐다.

현재 상태:

```text
데이터 정합성      PASS
failed             0
refused            0
standing queue     재현
growing backlog    미관찰
DB CPU ~66%        재현
DB saturation      미확인
```

따라서 아직 tuning은 수행하지 않지만, 이후 부하 상승 폭을 더 줄이고 DB CPU와 queue 성장 여부를 엄격하게 관찰한다.

---

## 2026-08-12 — 2,500 spans/s Sustained Load 재현성 검증

2,500 spans/s × 60초 첫 테스트에서 이전 단계와 다른 신호인 sampled queue=2100과 높은 TimescaleDB CPU가 관찰되어 동일 조건으로 두 번째 테스트를 수행했다.

### 데이터 정합성

두 실행 모두:

```text
Expected spans  = 150,000
DB final        = 150,000 / 150,000
Failed requests = 0
Refused delta   = 0
Final queue     = 0
Final in-flight = 0
```

으로 정상 완료됐다.

Observed sender rate도 두 실행 모두 2,500.00 spans/s였다.

### TimescaleDB CPU 재현성

Full-rate t=10~60:

```text
Run1
average = 72.69%
median  = 69.79%
minimum = 59.16%
maximum = 104.50%

Run2
average = 64.03%
median  = 64.17%
minimum = 53.66%
maximum = 74.80%
```

Run1에서 관찰된 약 70% 이상의 DB CPU 수준은 동일 조건 Repeat2에서는 재현되지 않았다.

직전 2,375 spans/s 결과는:

```text
Run1 average = 66.53%
Run1 median  = 65.80%

Run2 average = 65.80%
Run2 median  = 65.99%
```

였으므로, 현재 측정만으로 workload 증가와 DB CPU 사용량 사이에 단순한 선형 관계가 있다고 판단하지 않는다.

5초 간격의 container resource sampling과 실제 서버의 background activity 및 workload timing에 따른 run-to-run variation을 고려해야 한다.

### Collector Queue

Run1에서는 테스트 초반:

```text
t=5  → queue=2100
t=10 → queue=0
```

이 관찰됐다.

이는 현재까지 처음 관찰된 two-batch 수준의 sampled queue였지만 즉시 drain됐고 이후 증가하지 않았다.

Repeat2에서는:

```text
t=5  → 1050
t=10 → 1050
t=15 → 1050
t=20 → 1050

t=25 → 0
t=30 → 0
t=35 → 0

t=40 → 1050
t=45 → 1050
t=50 → 1050
t=55 → 1050
t=60 → 1050

t=65 → 0
```

으로 최대 sampled queue는 1050이었다.

따라서 Run1의 queue=2100은 동일 조건에서 재현되지 않았다.

두 실행 모두:

```text
1050 → 2100 → 3150 → ...
```

처럼 시간에 따라 성장하는 backlog는 관찰되지 않았으며 최종 queue/in-flight는 정상 drain됐다.

### 결론

2,500 spans/s × 60초 workload를 동일 조건으로 2회 검증한 결과:

- 두 실행 모두 150,000 Span 전량 저장
- failed request 0
- refused delta 0
- 최종 queue/in-flight 정상 drain
- growing backlog 미관찰
- Run1의 queue=2100은 Repeat2에서 미재현
- DB CPU는 run 간 변동 존재

를 확인했다.

따라서 2,500 spans/s는 현재 synthetic workload에서 정상 처리 가능한 범위로 확인됐지만 sustained throughput ceiling으로 판단할 근거는 없다.

또한 단일 benchmark의 순간 CPU 또는 queue 관찰값을 capacity 경계로 단정하지 않고 동일 조건 반복 측정으로 재현성을 확인해야 한다는 점을 실제 측정으로 검증했다.

설정 tuning은 아직 수행하지 않는다.

---

## 2026-08-12 — 2,625 spans/s High-load 재현성 검증

2,625 spans/s × 60초 sustained workload에서 첫 번째 실행의 DB CPU spike와 지속 standing queue 특성을 확인하기 위해 동일 조건으로 Repeat2를 수행했다.

### 데이터 정합성

두 실행 모두:

```text
Expected spans  = 157,500
DB final        = 157,500 / 157,500
Failed requests = 0
Refused delta   = 0
Final queue     = 0
Final in-flight = 0
```

으로 정상 완료됐다.

Observed sender rate 역시 두 실행 모두 2,625.00 spans/s였다.

### TimescaleDB CPU

Full-rate t=10~60:

```text
Run1
average = 73.80%
median  = 66.80%
minimum = 63.31%
maximum = 109.44%

Run2
average = 69.86%
median  = 73.32%
minimum = 60.68%
maximum = 75.85%
```

Run1에서는 100% 이상의 CPU sample 두 개가 average를 높였고, Repeat2에서는 해당 spike가 재현되지 않았다.

반면 Repeat2에서는 대부분의 full-rate sample이 약 60~76% 범위에 위치했고 median은 73.32%였다.

따라서 2,625 spans/s에서는 TimescaleDB가 대체로 60~70%대 CPU를 사용하는 high-load 영역에 진입한 것으로 판단한다.

다만 failed/refused 또는 데이터 정합성 실패가 없으므로 이를 sustained saturation으로 판단하지 않는다.

### Collector Queue

Run1에서는 5초 sampling 기준 t=5~60 동안 모든 sample에서 queue=1050이 관찰됐다.

Repeat2에서는:

```text
t=5  → 1050
t=10 → 0
t=15 → 1050
t=20 → 0
t=25 → 1050
t=30 → 0
t=35 → 1050
t=40 → 0
t=45 → 1050
t=50 → 0
t=55 → 1050
t=60 → 0
```

으로 한 batch 수준의 queue가 반복적으로 생성되고 drain되는 패턴이었다.

따라서 Run1의 지속 standing queue 특성은 Repeat2에서 동일하게 재현되지 않았다.

두 실행 모두 sampled queue는 1050을 넘지 않았고 시간에 따라 증가하는 backlog는 관찰되지 않았다.

### 결론

2,625 spans/s × 60초 workload를 동일 조건으로 2회 검증한 결과:

- 두 실행 모두 157,500 Span 전량 저장
- failed request 0
- refused delta 0
- 최종 queue/in-flight 정상 drain
- sampled queue 최대 1050
- growing backlog 미관찰
- TimescaleDB는 대체로 60~70%대 CPU의 high-load 영역

을 확인했다.

따라서 2,625 spans/s는 현재 synthetic workload에서 high-load 영역이지만 sustained throughput ceiling은 아니다.

설정 tuning은 아직 수행하지 않는다.

---

## 2026-08-12 — 2,750 spans/s Sustained Load 검증

### 테스트 조건

```text
Target rate      = 2,750 spans/s
Duration         = 60 sec
Expected spans   = 165,000
Sender batch     = 50
Sender requests  = 3,300
```

### 데이터 정합성

```text
Observed rate      = 2,750.00 spans/s

Requested spans    = 165,000
Accepted spans     = 165,000
DB final           = 165,000 / 165,000

Requested requests = 3,300
Accepted requests  = 3,300
Failed requests    = 0

Refused delta      = 0
Final queue        = 0
Final in-flight    = 0
```

서비스 restart 증가는 관찰되지 않았다.

### Sender

```text
Request latency
p50 = 0.731 ms
p95 = 1.831 ms
p99 = 2.030 ms
max = 4.539 ms

Schedule lag
p50 = 0.098 ms
p95 = 0.263 ms
p99 = 0.276 ms
max = 2.332 ms
```

2,750 spans/s에서도 sender pacing 또는 request latency의 지속적인 악화는 관찰되지 않았다.

### DB CPU 분석 구간 오류 발견

기존 DB CPU 분석 명령은 실제 timestamp 차이를 사용해:

```python
if 10 <= sec <= 60:
```

조건으로 full-rate sample을 선택했다.

resource monitor의 실제 timestamp가 목표 시각에서 수 ms 벗어날 수 있기 때문에 화면상 `t=60`으로 출력된 sample도 실제 `sec`가 60을 약간 초과하면 분석에서 제외될 수 있다.

2,750 테스트에서 time-series에는 t=10~60 총 11개 sample이 있었지만 기존 요약은 `full_rate_samples=10`으로 출력되어 이 문제가 확인됐다.

향후 분석에서는 sampling 시각을 intended 5-second slot으로 반올림한 뒤 full-rate 구간을 선택한다.

2,750 테스트의 실제 표시된 t=10~60 11개 sample 기준 TimescaleDB CPU는:

```text
samples = 11
average = 71.53%
median  = 70.48%
minimum = 65.90%
maximum = 81.02%
```

이다.

이번 테스트에서는 모든 full-rate sample이 약 66~81% 범위에 위치해 TimescaleDB가 지속적인 high-load 영역에서 동작한 것으로 관찰됐다.

그러나 직전 2,625 Repeat2의 average 69.86%, median 73.32%와 비교하면 workload 증가에 따라 DB CPU가 단순 선형 증가한다고 판단할 수는 없다.

### Collector Queue

5초 sampling:

```text
t=5  → 1050
t=10 → 1050
t=15 → 1050
t=20 → 1050

t=25 → 0
t=30 → 0

t=35 → 1050
t=40 → 1050
t=45 → 1050
t=50 → 1050
t=55 → 1050
t=60 → 1050

t=65 → 0
```

sampled queue 최대값은 1050이었다.

2100 이상의 queue가 연속 유지되거나 시간에 따라 3150, 4200 등으로 증가하는 패턴은 관찰되지 않았다.

따라서 2,750 spans/s에서도 growing exporter backlog는 확인되지 않았다.

### Backend / JDBC Batch

Backend runtime request:

```text
150 spans  × 1
1050 spans × 157
```

통계:

```text
Backend requests     = 158
Received spans       = 165,000
Average request size = 1,044.30

Requests below 1000 = 1
Requests equal 1000 = 0
Requests over 1000  = 157
```

현재 source의 JDBC batch-size 1000 기준:

```text
Estimated JDBC chunks        = 315
Estimated chunks per request = 1.994
```

대부분의 request가 1050 Span으로 유지되어 기존 Collector/JDBC batch 패턴의 구조적인 변화는 확인되지 않았다.

### 결론

2,750 spans/s × 60초 synthetic sustained workload에서:

- 165,000 Span 전량 저장
- failed request 0
- refused delta 0
- 최종 queue/in-flight 정상 drain
- sampled queue 최대 1050
- growing backlog 미관찰
- TimescaleDB CPU average 71.53%
- TimescaleDB CPU median 70.48%
- 서비스 restart 증가 없음

을 확인했다.

따라서 2,750 spans/s는 현재 synthetic workload에서 정상 처리 가능한 high-load 영역이며 아직 sustained throughput ceiling으로 판단할 증거는 없다.

또한 timestamp 기반 full-rate sample 경계 조건에서 sample이 누락될 수 있는 측정 도구 문제를 발견했으며 이후 분석 시 sampling slot을 반올림해 처리한다.

---

## 2026-08-12 — Sustained Load DB CPU 분석 경계 오류 수정

2,750 spans/s sustained-load 결과를 분석하는 과정에서 time-series에는 t=10~60 구간의 sample이 11개 존재했지만 기존 CPU 요약 결과는 `full_rate_samples=10`으로 계산되는 문제를 발견했다.

### 원인

기존 분석은 첫 resource sample과의 실제 timestamp 차이를 사용해 다음과 같이 full-rate 구간을 선택했다.

```python
if 10 <= sec <= 60:
```

resource monitor는 목표 sampling 시각을 기준으로 동작하지만 실제 timestamp에는 수 ms 수준의 scheduling drift가 존재할 수 있다.

따라서 화면상 반올림 결과가 `t=60`으로 보이는 sample도 실제 elapsed time이 예를 들어 `60.002`초이면 위 조건에서 제외될 수 있었다.

이 문제 때문에 DB CPU average와 median을 계산할 때 마지막 full-rate sample이 누락될 수 있었다.

### 개선

다음 재사용 분석 스크립트를 추가했다.

```text
scripts/summarize-sustained-db-cpu.py
```

스크립트는 실제 elapsed timestamp를 가장 가까운 sampling interval slot으로 정규화한다.

예:

```text
10.003 sec → t=10
14.999 sec → t=15
60.002 sec → t=60
```

또한 기대하는 full-rate slot 중 하나라도 존재하지 않을 경우 부분 데이터로 통계를 계산하지 않고 non-zero exit code로 종료하도록 했다.

기본 full-rate slots:

```text
10,15,20,25,30,35,40,45,50,55,60
```

### 기존 2,750 spans/s 데이터 재검증

대상:

```text
benchmark-results/sustained-20260812T003946Z/resources.tsv
```

결과:

```text
full_rate_slots=10,15,20,25,30,35,40,45,50,55,60
full_rate_samples=11
db_cpu_avg=71.53%
db_cpu_median=70.48%
db_cpu_min=65.90%
db_cpu_max=81.02%
```

기존 분석에서 누락됐던 t=60 sample까지 포함해 총 11개 full-rate sample이 정상 집계됐다.

### 누락 검출 테스트

기존 resource 데이터의 full-rate sample 하나를 임시 파일에서 제거한 후 분석 스크립트를 실행했다.

결과:

```text
missing full-rate sampling slots: t=10
Exit code: 2
```

sample 누락 시 부분 데이터로 잘못된 통계를 생성하지 않고 명시적으로 실패하는 것을 확인했다.

### 완료 조건

```text
Python 문법 검증                  PASS
정상 데이터 11개 slot 집계       PASS
2,750 CPU 통계 재현              PASS
누락 sample 검출                 PASS
누락 시 non-zero 종료            PASS
```

### 운영/성능 측정 의미

성능 테스트에서는 서비스 자체의 처리 오류뿐 아니라 측정 도구의 경계조건 오류도 잘못된 capacity 판단을 만들 수 있다.

향후 sustained-load DB CPU 분석은 `scripts/summarize-sustained-db-cpu.py`를 기준으로 수행하고, 기대 sampling slot이 모두 존재할 때만 CPU 통계를 유효한 결과로 사용한다.

---

## 2026-08-12 — 2,875 spans/s High-load 재현성 검증

2,875 spans/s × 60초 첫 실행에서 TimescaleDB CPU가 높은 수준으로 유지되고 transient queue=2100이 관찰되어 동일 조건으로 Repeat2를 수행했다.

### 데이터 정합성

두 실행 모두:

```text
Expected spans  = 172,500
DB final        = 172,500 / 172,500
Failed requests = 0
Refused delta   = 0
Final queue     = 0
Final in-flight = 0
Observed rate   = 2,875.00 spans/s
```

으로 정상 완료됐다.

### TimescaleDB CPU 재현성

검증된 `scripts/summarize-sustained-db-cpu.py`를 사용해 full-rate t=10~60의 11개 sampling slot을 집계했다.

```text
Run1
average = 80.79%
median  = 75.80%
minimum = 70.45%
maximum = 135.41%

Run2
average = 78.88%
median  = 77.22%
minimum = 74.27%
maximum = 87.10%
```

두 실행 전체 22개 sample의 참고 통계:

```text
average = 79.84%
median  = 76.62%
```

Run1에는 135.41%의 높은 단일 sample이 있었지만 Repeat2에서는 모든 full-rate sample이 74.27~87.10% 범위에 위치했다.

따라서 2,875 spans/s에서 TimescaleDB가 대체로 75~80% 수준의 CPU를 지속적으로 사용하는 high-load 특성은 동일 조건 반복에서도 재현된 것으로 판단한다.

### Collector Queue

Run1에서는 t=50에서 queue=2100이 한 번 관찰됐지만 다음 sample에서 1050으로 감소했고 최종 drain됐다.

Repeat2에서는 sampled queue 최대값이 1050이었다.

```text
t=10 → 1050
t=15 → 1050
t=20 → 0
t=25 → 1050
t=30 → 1050
t=35 → 0
t=40 → 1050
t=45 → 1050
t=50 → 0
t=55 → 1050
t=60 → 1050
```

따라서:

```text
queue=2100 transient      1/2 run
2100 연속 유지            0/2 run
growing backlog           0/2 run
final drain               2/2 run
```

으로 확인됐다.

DB CPU headroom은 크게 감소했지만 Collector exporter queue가 시간에 따라 증가하는 sustained throughput saturation은 아직 확인되지 않았다.

### Sender Tail Outlier

Repeat2 sender:

```text
Request latency
p50 = 0.733 ms
p95 = 1.756 ms
p99 = 2.042 ms
max = 105.549 ms

Schedule lag
p99 = 0.273 ms
max = 89.303 ms
```

max latency와 schedule lag에서 단일 큰 tail 값이 관찰됐지만 p95/p99는 낮은 수준을 유지했고 failed request 없이 목표 2,875 spans/s pacing을 달성했다.

따라서 현재는 지속적인 sender saturation이 아니라 tail outlier로 기록한다.

### 결론

2,875 spans/s × 60초 workload를 동일 조건으로 2회 검증한 결과:

- 두 실행 모두 172,500 Span 전량 저장
- failed request 0
- refused delta 0
- 최종 queue/in-flight 정상 drain
- growing Collector backlog 미관찰
- TimescaleDB CPU 약 75~80% high-load 특성 재현

을 확인했다.

2,875 spans/s는 현재 synthetic workload에서 명확한 high-load 영역이지만 sustained throughput ceiling으로 판단할 증거는 아직 없다.

다음 부하 단계에서는 DB headroom과 queue 성장 여부를 더욱 엄격하게 확인하며, 지속 queue 증가 또는 처리 실패가 관찰되면 추가 rate 상승을 중단한다.

---

## 2026-08-12 — 3,000 spans/s High-load 재현성 검증

3,000 spans/s × 60초 첫 실행에서 TimescaleDB CPU가 약 80% 이상으로 유지되고 Collector queue=1050이 전체 부하 구간에서 지속적으로 관찰되어 동일 조건으로 Repeat2를 수행했다.

### 데이터 정합성

두 실행 모두:

```text
Expected spans  = 180,000
DB final        = 180,000 / 180,000
Failed requests = 0
Refused delta   = 0
Final queue     = 0
Final in-flight = 0
```

으로 정상 완료됐다.

서비스 restart도:

```text
backend   0 → 0
collector 1 → 1
db        0 → 0
```

으로 증가하지 않았다.

### Sender

Run1 observed rate:

```text
2,999.99 spans/s
```

Repeat2:

```text
Observed rate = 2,999.94 spans/s

Request latency
p50 = 0.729 ms
p95 = 1.641 ms
p99 = 1.955 ms
max = 7.347 ms

Schedule lag
p99 = 0.271 ms
max = 5.918 ms
```

Run1에서 request latency max 182.345 ms와 schedule lag max 166.345 ms의 tail outlier가 관찰됐지만 Repeat2에서는 재현되지 않았다.

두 실행 모두 failed request 없이 목표 rate를 유지했으므로 현재는 지속적인 sender saturation 신호로 판단하지 않는다.

### TimescaleDB CPU 재현성

검증된 `scripts/summarize-sustained-db-cpu.py`를 사용해 t=10~60의 full-rate sampling slot 11개를 모두 집계했다.

```text
Run1
average = 83.23%
median  = 83.87%
minimum = 78.20%
maximum = 90.21%

Run2
average = 78.81%
median  = 78.55%
minimum = 76.96%
maximum = 82.62%
```

두 실행의 전체 22개 full-rate sample을 사용한 참고 통계:

```text
average ≈ 81.02%
median  ≈ 79.80%
minimum = 76.96%
maximum = 90.21%
```

combined 통계는 개별 run을 대체하는 capacity 수치가 아니라 동일 workload 반복 결과의 전체적인 CPU 범위를 확인하기 위한 보조 지표로만 사용한다.

두 실행 모두 full-rate 구간에서 TimescaleDB가 지속적으로 높은 CPU를 사용했다.

따라서 3,000 spans/s에서 TimescaleDB가 대략 80% 전후의 CPU를 사용하는 high-load 특성이 재현됐다고 판단한다.

### Collector Queue 재현성

Run1과 Repeat2 모두 5초 sampling 기준 테스트 부하 구간 t=5~60의 모든 sample에서:

```text
queue=1050
in_flight=1
```

수준이 지속적으로 관찰됐다.

그러나 두 실행 모두 queue가:

```text
1050 → 2100 → 3150
```

처럼 시간에 따라 증가하지 않았으며 테스트 종료 후 queue와 in-flight는 모두 0으로 정상 drain됐다.

따라서 3,000 spans/s에서 한 Collector batch 수준의 persistent standing queue는 2/2 재현됐지만 growing exporter backlog는 확인되지 않았다.

### Backend / JDBC

Run1 Backend request:

```text
500 spans  × 1
1000 spans × 1
1050 spans × 170
```

통계:

```text
Backend requests     = 172
Received spans       = 180,000
Average request size = 1,046.51

Estimated JDBC chunks        = 342
Estimated chunks per request = 1.988
```

기존 Collector batching 및 JDBC batch 구조의 급격한 변화는 확인되지 않았다.

### 결론

3,000 spans/s × 60초 synthetic sustained workload를 동일 조건으로 2회 검증한 결과:

- 두 실행 모두 180,000 Span 전량 저장
- failed request 0
- refused delta 0
- 서비스 restart 증가 없음
- 최종 queue/in-flight 정상 drain
- one-batch standing queue가 전체 부하 구간에서 2/2 재현
- growing backlog 미관찰
- TimescaleDB 약 80% CPU high-load 특성 재현

을 확인했다.

현재 synthetic 60초 workload에서 검증된 최고 sustained ingest rate는 3,000 spans/s다.

단, 이는 최대 처리량 또는 production capacity를 의미하지 않는다.

3,000 spans/s에서 DB headroom이 상당히 감소하고 persistent one-batch queue가 재현됐지만 growing backlog, refused, failed 또는 데이터 정합성 실패가 없으므로 sustained throughput saturation으로 판단할 근거는 아직 없다.

추가 부하 탐색은 작은 증가폭으로 진행하며 queue가 2100 이상으로 지속되거나 성장할 경우 추가 rate 상승을 중단하고 병목 분석으로 전환한다.

---

## 2026-08-12 — 3,125 spans/s High-load 재현성 검증

3,125 spans/s × 60초 synthetic sustained workload를 동일 조건으로 2회 수행했다.

### 데이터 정합성

두 실행 모두:

```text
Expected spans  = 187,500
DB final        = 187,500 / 187,500
Failed requests = 0
Refused delta   = 0
Final queue     = 0
Final in-flight = 0
```

으로 정상 완료됐다.

서비스 restart도 증가하지 않았다.

```text
backend   0 → 0
collector 1 → 1
db        0 → 0
```

### TimescaleDB CPU

검증된 `scripts/summarize-sustained-db-cpu.py`를 사용해 각 실행의 t=10~60 full-rate slot 11개를 집계했다.

```text
Run1
average = 84.31%
median  = 82.37%
minimum = 81.26%
maximum = 90.49%

Run2
average = 88.73%
median  = 84.54%
minimum = 82.48%
maximum = 127.11%
```

Repeat2에는 127.11%의 단일 높은 CPU sample이 포함됐다.

해당 sample이 average에 미치는 영향을 확인하기 위한 보조 통계로 이를 제외하면:

```text
average ≈ 84.89%
median  ≈ 84.21%
```

이다.

이는 공식 benchmark 결과를 대체하기 위한 값이 아니라 단일 spike를 제외하더라도 Repeat2의 전형적인 DB CPU가 약 84% 수준임을 확인하기 위한 보조 분석이다.

Run1 median 82.37%, Repeat2 median 84.54%로 3,125 spans/s에서 TimescaleDB가 지속적으로 80%대 CPU를 사용하는 high-load 특성이 재현됐다.

### Collector Queue

Run1에서는 t=5~60의 모든 5초 sample에서 queue=1050이 관찰됐다.

Repeat2에서는 대부분의 부하 구간에서 queue=1050이었으나 t=20과 t=60에서는 queue=0이 관찰됐다.

따라서 one-batch queue가 빈번하게 존재하는 현상은 재현됐지만 전체 부하 구간에 걸친 완전한 standing queue는 Repeat2에서 재현되지 않았다.

두 실행 모두:

```text
queue >= 2100    미관찰
growing backlog  미관찰
final queue      0
final in-flight  0
```

이었다.

따라서 TimescaleDB CPU headroom은 상당히 감소했지만 입력률보다 처리율이 지속적으로 낮아 queue가 시간에 따라 증가하는 throughput saturation은 아직 확인되지 않았다.

### Sender

Repeat2:

```text
Observed accepted rate = 3,125.00 spans/s

Request latency
p50 = 0.723 ms
p95 = 1.515 ms
p99 = 1.882 ms
max = 73.767 ms

Schedule lag
p99 = 0.260 ms
max = 58.969 ms
```

Run1과 Repeat2 모두 max latency/schedule lag에서 순간적인 tail 값은 존재했지만 p95/p99가 낮은 상태에서 failed request 없이 목표 rate를 유지했다.

현재는 sender saturation 증거로 판단하지 않는다.

### 결론

3,125 spans/s × 60초를 동일 조건으로 2회 검증한 결과:

- 각 실행 187,500 Span 전량 저장
- failed request 0
- refused delta 0
- restart 증가 없음
- 최종 queue/in-flight 정상 drain
- TimescaleDB 80%대 CPU high-load 재현
- one-batch queue 빈번하게 관찰
- queue >= 2100 미관찰
- growing backlog 미관찰

을 확인했다.

현재 synthetic 60초 workload에서 검증된 최고 sustained ingest rate는 3,125 spans/s다.

이는 최대 처리량 또는 production capacity를 의미하지 않는다.

3,125 spans/s에서 DB headroom은 상당히 감소했지만 sustained throughput saturation을 판단할 근거는 아직 없으므로 설정 tuning 없이 작은 단위로 ceiling 탐색을 계속한다.

향후 queue >= 2100이 연속 sample에서 유지되거나 시간에 따라 증가할 경우 rate 상승을 중단하고 Collector → Backend → JDBC → TimescaleDB 경로의 병목 분석으로 전환한다.

---

## 2026-08-12 — 3,250 spans/s 고부하 경계 3회 검증

3,250 spans/s × 60초 synthetic sustained workload를 총 3회 실행해 고부하 경계 신호의 재현성을 검증했다.

결과 디렉터리:

- Run1: `sustained-20260812T022108Z`
- Run2: `sustained-20260812T030219Z`
- Run3: `sustained-20260812T031142Z`

### 데이터 정합성

세 실행 모두:

- Expected spans: 195,000
- DB final: 195,000 / 195,000
- Failed requests: 0
- Refused delta: 0
- Final queue: 0
- Final in-flight: 0
- Backend/Collector/DB restart 증가 없음

으로 정상 완료됐다.

따라서 3,250 spans/s workload를 60초 동안 처리하는 동안 데이터 유실이나 요청 실패는 관찰되지 않았다.

### TimescaleDB CPU

검증된 `scripts/summarize-sustained-db-cpu.py`를 사용해 각 실행의 t=10~60 full-rate sample 11개를 집계했다.

Run1:

- average: 95.68%
- median: 91.25%
- min: 58.12%
- max: 154.07%

Run2:

- average: 91.31%
- median: 89.00%
- min: 85.74%
- max: 114.59%

Run3:

- average: 91.32%
- median: 89.27%
- min: 84.88%
- max: 112.27%

3회 전체 33개 full-rate sample의 참고 통계:

- average: 92.77%
- median: 89.27%
- min: 58.12%
- max: 154.07%

multi-core CPU spike 때문에 average가 영향을 받을 수 있으므로 각 실행의 median을 주요 판단 지표로 사용한다.

Run1 91.25%, Run2 89.00%, Run3 89.27%로 TimescaleDB가 약 90% 수준의 CPU를 사용하는 high-load 특성은 3/3 재현됐다.

### Collector Queue

Run1에서는 sampled queue가 최대 3150까지 증가했으며 다음과 같은 연속 multi-batch queue가 관찰됐다.

`3150 → 2100`

이는 사전에 정한 추가 rate 상승 중단 조건인 `queue >= 2100` 연속 sample 조건을 충족했다.

그러나 Run2와 Run3에서는 sampled queue 최대값이 각각 1050이었으며 queue >= 2100은 재현되지 않았다.

따라서:

- multi-batch queue 경계 신호: 1/3
- queue >= 2100 연속: 1/3
- growing backlog: 미확인
- final drain: 3/3 성공

으로 판단한다.

Run1의 경계 신호를 sustained saturation으로 확정할 수는 없지만 무시할 수도 없으므로, 더 높은 rate 탐색 대신 병목 분리 측정으로 전환한다.

### Sender

Run1:

- request latency p99: 113.203 ms
- schedule lag p99: 661.332 ms

Run2:

- request latency p99: 1.925 ms
- schedule lag p99: 0.258 ms

Run3:

- request latency p99: 1.856 ms
- schedule lag p99: 0.263 ms

Run1에서 발생한 sender scheduling stall은 Run2와 Run3에서는 재현되지 않았다.

따라서 지속적인 sender saturation으로 판단하지 않는다.

### 결론

현재 synthetic 60초 workload에서 검증된 최고 sustained ingest rate는 3,250 spans/s다.

단, 이는 최대 처리량이나 production capacity를 의미하지 않는다.

3,250 spans/s에서:

- 195,000 Span 전량 저장 3/3
- failed/refused 0
- 최종 drain 3/3
- DB CPU 약 90% high-load 3/3
- multi-batch queue 경계 신호 1/3

이 확인됐다.

DB headroom이 상당히 감소했고 사전에 정의한 queue 경계 조건이 한 번 발생했으므로 추가 rate 상승은 중단한다.

다음 단계에서는 3,250 spans/s 부하를 기준으로 Collector → Backend → JDBC → TimescaleDB 경로를 분리 측정하여 실제 병목 위치를 확인한다.

측정 전 설정 tuning은 수행하지 않는다.

---

---

## 2026-08-13 — PostgreSQL JDBC Batch 저장 병목 분석과 `reWriteBatchedInserts` 최적화

### 목적

3,250 spans/s sustained telemetry ingest에서 60초 동안 195,000 Span 전량 저장에는 성공했지만 TimescaleDB CPU 사용률이 높은 수준까지 증가하고 일부 Backend 저장 요청이 다음 Collector batch 도착 주기보다 오래 걸리는 현상을 분석했다.

단순히 JDBC batch size나 DB 설정을 변경하지 않고 실제 저장 경로에서 시간이 어디에 소비되는지 단계적으로 계측한 뒤 최적화 후보를 검증했다.

### 테스트 환경

- Java 21
- Spring Boot 4.1
- Spring JDBC
- PostgreSQL 15.18
- TimescaleDB
- OpenTelemetry Collector
- N100 Ubuntu 홈서버
- Backend JDBC batch size: 1,000
- Sender batch size: 50
- Collector `send_batch_size`: 1,024
- Collector batch timeout: 1초
- Collector exporter consumers: 2
- Collector persistent queue size: 50,000

### 주요 테스트 조건

```text
Target ingest rate: 3,250 spans/s
Duration: 60초
Expected spans: 195,000
Sender batch size: 50
Backend JDBC batch size: 1,000
주요 Backend request size: 1,050 Span
Collector size-trigger 예상 주기: 약 323.08ms
```

Sender가 초당 65개의 50-span request를 전송하고 Collector batch threshold가 1,024이므로 일반적인 size-trigger 조건에서는 21개의 sender batch가 모인 1,050 Span이 Backend로 전달되는 구조가 확인됐다.

따라서 대부분의 Backend 요청은 JDBC batch size 1,000에 의해 다음 두 chunk로 저장됐다.

```text
1,050 Span request
├── 1,000 Span JDBC batch
└──    50 Span JDBC batch
```

### 3,250 spans/s sustained baseline

대표 baseline run:

```text
Target spans/sec: 3250
Duration sec: 60
Expected spans: 195000

Requested spans: 195000
Accepted spans: 195000
Failed requests: 0

Actual elapsed sec: 60.000071
Observed accepted spans/sec: 3250.00

DB count: 195000/195000
Queue: 0
In flight: 0
```

Backend request distribution:

```text
1 x 750 Span
185 x 1,050 Span
```

DB CPU:

```text
full_rate_samples=11
db_cpu_avg=94.29%
db_cpu_median=94.05%
db_cpu_min=86.72%
db_cpu_max=100.89%
```

데이터 유실이나 최종 backlog는 없었지만 DB CPU headroom이 매우 작았다.

### PostgreSQL Wait 분석

높은 DB CPU가 lock contention이나 지속적인 storage I/O wait 때문인지 구분하기 위해 PostgreSQL active session과 wait event를 별도로 sampling했다.

3,250 spans/s 조건에서 확인된 주요 값:

```text
active avg ≈ 0.94
active max = 1

대부분 active_no_wait
lock wait 관찰되지 않음
지속적인 I/O wait 관찰되지 않음
```

따라서 lock contention이나 persistent I/O wait를 주요 원인으로 볼 근거는 확인되지 않았다.

단, `active_no_wait` 자체는 PostgreSQL 프로세스가 CPU를 실제로 계속 점유하고 있다는 직접 증거가 아니므로 Docker DB CPU 측정값과 함께 해석했다.

### 저장 경로 계측

병목 위치를 좁히기 위해 다음 단계에 임시 timing instrumentation을 추가했다.

```text
OTLP JSON parsing
→ PreparedSpanRow 생성 / JSON 직렬화
→ PreparedStatement parameter binding
→ JdbcTemplate.batchUpdate
→ Transaction envelope
```

Controller에서는 다음을 측정했다.

```text
parseNanos
ingestEnvelopeNanos
```

`JdbcSpanWriter`에서는 다음을 측정했다.

```text
writerTotalNanos
prepareRowsNanos
batchUpdateNanos
bindNanos
batchAfterBindNanos
writerOtherNanos
```

Chunk별로도 다음을 분리했다.

```text
chunkPrepareRowsNanos
chunkBatchUpdateNanos
chunkBindNanos
```

### 1,050-span 요청 전체 분석

baseline의 1,050-span 요청 185개를 분석했다.

```text
writer_total:
avg=299.91ms
median=288.75ms
p95=393.07ms
p99=449.00ms

prepare_rows:
avg=2.07ms
median=1.64ms
p95=4.35ms
p99=16.94ms

batch_update:
avg=297.81ms
median=286.84ms
p95=389.16ms
p99=435.89ms

bind:
avg=1.39ms
median=1.19ms
p95=2.10ms
p99=5.33ms

batch_after_bind:
avg=296.42ms
median=285.54ms
p95=382.81ms
p99=431.22ms
```

`batchUpdate` 내부 비중:

```text
bind_share_of_batch_update:
avg=0.45%
median=0.40%
p95=0.74%

after_bind_share_of_batch_update:
avg=99.55%
median=99.60%
p95=99.74%
```

### 1,000-row / 50-row Chunk 분석

1,000-row chunk:

```text
batch_update median = 272.32ms
bind median         =   1.13ms
after_bind median   = 271.15ms
```

50-row chunk:

```text
batch_update median = 13.67ms
bind median         =  0.06ms
after_bind median   = 13.61ms
```

두 chunk의 단순 per-row 비용도 크게 다르지 않았다.

다만 50-row chunk는 독립적인 batch-size benchmark가 아니라 동일 Transaction 안의 두 번째 chunk이므로 batch size 50과 1,000의 독립 A/B 결과로 해석하지 않았다.

### 병목 범위 축소

측정 결과 다음 항목은 주요 병목에서 제외했다.

```text
OTLP JSON parsing
JSONB 직렬화
PreparedSpanRow 생성
PreparedStatement parameter binding
```

특히 parameter binding은 `batchUpdate` 전체 시간의 중앙값 기준 약 0.40%였다.

병목 범위는 다음으로 좁혀졌다.

```text
JdbcTemplate.batchUpdate
└── parameter binding 이후
    ├── JdbcTemplate batch 처리
    ├── pgJDBC batch 처리
    ├── PostgreSQL protocol / network
    ├── INSERT 실행
    ├── Unique Index 검사
    ├── ON CONFLICT 처리
    ├── TimescaleDB 저장
    └── update count 결과 처리
```

`batchAfterBindNanos`는 위 항목을 모두 포함하는 residual이므로 PostgreSQL INSERT 자체의 실행시간이라고 표현하지 않았다.

### Collector Batch Cadence와 Writer 시간 비교

Sender 조건:

```text
3,250 spans/s
50 spans/request
= 65 sender requests/sec
```

Collector size-trigger는 일반적으로 21개의 sender request가 모이는 시점이다.

```text
21 / 65 × 1000
≈ 323.08ms
```

baseline에서:

```text
writer > 323.08ms:
19 / 185

batchAfterBind > 323.08ms:
18 / 185
```

일부 Backend writer가 다음 Collector size-trigger 예상 시점보다 오래 걸리고 있었다.

이는 즉시 데이터 유실을 의미하지는 않지만 입력 cadence보다 저장 작업이 느린 요청이 반복될 경우 queue와 backlog 증가 가능성을 높인다.

---

### `reWriteBatchedInserts=true` 후보 검토

현재 JDBC URL에는 `reWriteBatchedInserts` 설정이 없었다.

초기 Windows Docker persistence-only benchmark에서는 rewrite 옵션이 일관된 개선을 보여주지 않아 적용을 보류한 상태였다.

그러나 실제 N100 sustained workload에서는 JDBC/DB 경로가 병목 범위로 좁혀졌기 때문에 운영 후보 환경에서 다시 검증했다.

성능 테스트 전에 먼저 data correctness와 JDBC update count semantics를 검증했다.

### Rewrite correctness 테스트

임시 Docker Compose override로 다음을 적용했다.

```text
reWriteBatchedInserts=true
```

테스트 데이터:

```text
1차 요청
신규 Span 0..49
→ 신규 50개

2차 요청
동일 Span 0..49
→ 중복 50개

3차 요청
Span 0..24 + 50..74
→ 기존 25개 + 신규 25개
```

첫 신규 50개 결과:

```text
received=50
inserted=0
duplicates=0
unknown=50
```

동일 50개 재전송:

```text
received=50
inserted=0
duplicates=50
unknown=0
```

기존 25 + 신규 25:

```text
received=50
inserted=0
duplicates=0
unknown=50
```

최종 DB:

```text
75
```

예상 데이터:

```text
첫 요청      +50
두 번째       +0
mixed        +25
----------------
최종          75
```

와 정확히 일치했다.

### Correctness 결론

확인된 사실:

```text
DB idempotency                  유지
중복 Span 추가 저장             없음
최종 DB row 수                  정확함
ON CONFLICT 기반 중복 방지       유지
```

반면 rewritten batch에 신규 row가 포함되면 JDBC driver가 개별 row 결과를 정확히 구분하지 못하고 `Statement.SUCCESS_NO_INFO`를 반환할 수 있었다.

따라서 다음 내부 통계는 항상 정확하지 않게 된다.

```text
insertedCount
duplicateCount
```

현재 `SpanWriteResult`는 이를 위해 이미 다음 필드를 가지고 있다.

```text
unknownSuccessCount
```

그리고 OTLP HTTP 응답은 inserted / duplicate / unknown 값을 외부 응답 계약으로 제공하지 않고 `{}`를 반환한다.

따라서 데이터 저장 정합성을 유지하면서 내부 분류 정확성 일부를 성능과 교환하는 것이 현재 MVP에서는 허용 가능한 trade-off라고 판단했다.

---

### Rewrite 단일 A/B 성능 테스트

동일한 3,250 spans/s × 60초 조건에서 rewrite를 활성화했다.

결과:

```text
Requested spans: 195000
Accepted spans: 195000
Failed requests: 0
Observed accepted spans/sec: 3250.00

DB count: 195000/195000
Queue: 0
In flight: 0
```

DB CPU:

```text
full_rate_samples=11
db_cpu_avg=40.88%
db_cpu_median=42.23%
db_cpu_min=26.73%
db_cpu_max=48.43%
```

rewrite=true의 1,050-span 요청 전체 분석:

```text
writer_total:
avg=120.15ms
median=115.09ms
p95=145.09ms
p99=207.85ms

batch_update:
avg=117.71ms
median=113.07ms
p95=142.73ms
p99=199.95ms

bind:
avg=1.70ms
median=1.44ms
p95=4.22ms
p99=7.13ms

batch_after_bind:
avg=116.01ms
median=111.59ms
p95=140.11ms
p99=196.14ms
```

단일 A/B 중앙값 변화:

```text
writer_total:
288.75ms → 115.09ms
-60.14%

batch_update:
286.84ms → 113.07ms
-60.58%

batch_after_bind:
285.54ms → 111.59ms
-60.92%

1000-row batch:
272.32ms → 106.77ms
-60.79%

1000-row after-bind:
271.15ms → 105.32ms
-61.16%
```

Collector cadence 초과:

```text
writer > 323.08ms:
0 / 184

batchAfterBind > 323.08ms:
0 / 184
```

단일 실행만으로 개선 수치를 확정하지 않고 반복 A/B를 추가 수행했다.

---

### 반복 A/B 재현성 검증

최초 baseline/rewrite 각 1회에 더해 다음 순서로 추가 실험했다.

```text
baseline-2
rewrite-2
rewrite-3
baseline-3
```

한 조건이 항상 먼저 또는 나중에 실행되어 DB cache, 시간 경과, 데이터 증가 영향을 독점하지 않도록 ABBA 형태로 배치했다.

추가 반복 결과:

| Run | Mode | 1050 request | Writer median | Batch median | Bind median | After-bind median | Writer > 323ms | DB CPU avg | DB CPU median |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| baseline-2 | baseline | 185 | 287.77ms | 286.10ms | 1.15ms | 284.82ms | 12 | 94.36% | 90.36% |
| rewrite-2 | rewrite | 185 | 104.84ms | 102.83ms | 1.40ms | 101.32ms | 0 | 31.74% | 31.52% |
| rewrite-3 | rewrite | 185 | 107.07ms | 104.81ms | 1.41ms | 103.47ms | 0 | 37.55% | 37.63% |
| baseline-3 | baseline | 185 | 297.64ms | 296.19ms | 1.09ms | 294.42ms | 26 | 95.40% | 94.84% |

최초 실행까지 포함한 조건별 3회 결과의 run 중앙값:

| 지표 | rewrite=false | rewrite=true | 변화 |
|---|---:|---:|---:|
| Writer median | 288.75ms | 107.07ms | -62.9% |
| BatchUpdate median | 286.84ms | 104.81ms | -63.5% |
| After-bind median | 285.54ms | 103.47ms | -63.8% |
| DB CPU avg | 94.36% | 37.55% | -60.2% |
| DB CPU median | 94.05% | 37.63% | -60.0% |

Collector size-trigger 예상 주기 323.08ms를 초과한 writer:

```text
rewrite=false
57 / 555

rewrite=true
0 / 554
```

각 sustained run에서 다음을 확인했다.

```text
Target rate = 3250 spans/s
Duration = 60초
Expected = 195000 spans

DB count = 195000/195000
Failed requests = 0
Final queue = 0
Final in-flight = 0
```

### 성능 결과 해석

이번 결과는 최대 처리량이 63% 증가했다는 의미가 아니다.

최대 안정 처리량을 별도로 다시 탐색하지 않았기 때문이다.

정확한 결론은 다음과 같다.

> 동일한 3,250 spans/s sustained workload에서 `reWriteBatchedInserts=true` 적용 후 JDBC writer 중앙값 약 63%, TimescaleDB CPU 약 60% 감소를 조건별 3회 반복 측정으로 확인했다.

또한 기존에는 일부 writer 실행이 Collector size-trigger 예상 주기보다 길었지만 rewrite 조건에서는 반복 측정 전체에서 해당 cadence를 초과한 1,050-span writer가 관찰되지 않았다.

### 초기 Windows Benchmark와 결과가 달랐던 이유에 대한 판단

초기 benchmark:

```text
Windows 개발 PC
Docker Desktop
Persistence-only
동시 sustained workload 없음
Collector 없음
낮은 DB 포화도
```

이번 benchmark:

```text
N100 실제 운영 후보 서버
Collector 포함
3,250 spans/s sustained workload
1,050-span Backend request 중심
높은 DB CPU
실제 runtime request distribution
```

따라서 초기 결과를 잘못된 실험으로 폐기하지 않았다.

대신 다음 교훈을 얻었다.

> 성능 최적화의 효과는 workload와 실행 환경에 따라 달라질 수 있으므로 개발 PC의 micro/persistence benchmark만으로 운영 설정을 결정하면 안 된다.

초기에는 측정 결과에 따라 rewrite를 보류했고, 실제 운영 후보 workload에서 병목이 확인된 뒤 다시 측정하여 결정을 변경했다.

### 최종 결정

Telemetry 저장용 PostgreSQL JDBC URL에 다음을 정식 적용했다.

```text
reWriteBatchedInserts=true
```

변경 파일:

```text
backend/src/main/resources/application.yaml
docker-compose.yaml
```

Commit:

```text
e8a1408 PostgreSQL 배치 INSERT 재작성 최적화 적용
```

홈서버 실제 Runtime:

```text
AEROTRACE_DB_URL=jdbc:postgresql://timescaledb:5432/aerotrace?reWriteBatchedInserts=true
```

### 배포 후 Smoke 검증

최적화 적용 후 1-span:

```text
Requested spans: 1
Accepted spans: 1
Failed requests: 0
```

Backend:

```text
received=1
inserted=1
duplicates=0
unknown=0
```

최종 Backend 상태:

```text
status=running
health=healthy
restart=0
```

### 임시 성능 계측 코드 제거

병목 분석 완료 후 운영 요청 경로에 추가했던 임시 timing instrumentation을 제거했다.

제거 대상:

```text
Controller parse timing
Controller ingestion envelope timing
Writer total timing
prepareRows timing
batchUpdate timing
row별 bind timing
chunk별 timing list
성능 분석용 INFO 로그
```

Benchmark 전용 클래스의 `System.nanoTime()`은 benchmark 목적이므로 유지했다.

Commit:

```text
5152cc1 성능 분석용 임시 계측 코드 제거
```

배포 후 smoke에서 다음 성능 분석용 로그가 더 이상 발생하지 않는 것을 확인했다.

```text
JDBC Span writer timing
OTLP trace timing
```

운영 저장 결과 로그는 유지했다.

```text
OTLP trace request stored
```

### 남은 Trade-off

`reWriteBatchedInserts=true`에서는 rewritten batch의 개별 INSERT 결과를 항상 정확하게 구분할 수 없으므로 다음 값은 운영상 정확한 통계로 사용할 수 없다.

```text
insertedCount
duplicateCount
```

특히 신규 row가 포함된 rewritten batch에서는 `unknownSuccessCount`가 증가할 수 있다.

현재는 다음 이유로 해당 trade-off를 허용한다.

```text
DB Unique Index가 중복 저장 최종 방어
ON CONFLICT DO NOTHING으로 idempotency 유지
실제 correctness test에서 최종 row 수 검증
OTLP API가 inserted/duplicate count를 외부 계약으로 노출하지 않음
unknownSuccessCount가 이미 모델링되어 있음
```

다음 요구가 생기면 반드시 재검토한다.

```text
Billing이 실제 inserted row 수에 의존
Tenant quota가 실제 inserted row 수에 의존
사용자에게 inserted / duplicate 결과를 제공
정확한 row 단위 ingest metric이 필요
pgJDBC upgrade로 rewrite semantics가 변경
```

### 보존해야 할 측정 자료

반복 A/B:

```text
/home/huning/aerotrace/benchmark-results/rewrite-ab-repeat-20260813T001308Z
```

추가 보존 자료:

```text
최초 3250 spans/s baseline sender 결과
최초 rewrite sender 결과
baseline/rewrite resources.tsv
Backend timing 로그
PostgreSQL wait sampling 결과
rewrite correctness 테스트 로그
DB 최종 count=75 출력
195000/195000 sustained 결과
runtime JDBC URL 확인 출력
Backend healthy / restart=0 출력
```

### 실무적 교훈

- 높은 DB CPU 하나만으로 원인을 단정하지 않는다.
- 성능 병목은 큰 구간에서 작은 구간으로 단계적으로 분리한다.
- `JdbcTemplate.batchUpdate()` 시간 전체를 PostgreSQL SQL 실행시간이라고 부르면 안 된다.
- parameter binding과 DB/JDBC 실행 비용을 분리하면 잘못된 Java-side 최적화를 피할 수 있다.
- 성능 옵션 적용 전에 데이터 정합성과 API semantics를 별도로 검증해야 한다.
- 한 번의 benchmark 결과가 아니라 반복 A/B로 재현성을 확인해야 한다.
- micro benchmark 결과보다 실제 운영 후보 workload의 측정 결과가 최종 설정 결정에 더 중요하다.
- 성능 진단용 instrumentation은 분석이 끝난 뒤 운영 코드에서 제거한다.
- 최적화 결과는 측정한 범위만 표현하고 최대 처리량 증가처럼 측정하지 않은 성과를 과장하지 않는다.

---

## Collector Queue Overflow 데이터 유실 및 Backpressure 장애 실험

### 문제 발견

OpenTelemetry Collector의 exporter `sending_queue`가 가득 찼을 때 현재 설정이 telemetry 보존에 어떤 영향을 주는지 검증했다.

초기 설정:

```yaml
sending_queue:
  enabled: true
  num_consumers: 2
  sizer: items
  queue_size: 50000
  block_on_overflow: false
  storage: file_storage/aerotrace
```

Backend 장애를 `docker pause`로 재현하고 70,000 Span sustained workload를 전송했다.

### block_on_overflow=false 장애 결과

측정 결과:

```text
requested        = 70,000
sender accepted  = 70,000
receiver accepted Δ = 70,000

enqueue_failed Δ = 20,250
sent Δ           = 49,750

DB               = 49,750
missing          = 20,250
```

Sender와 Collector receiver 관점에서는 70,000 Span이 수락됐지만 exporter queue 진입 실패로 20,250 Span이 최종 저장되지 않았다.

이 실험을 통해 queue overflow 시 telemetry가 조용히 유실될 수 있음을 확인했다.

### block_on_overflow=true A/B

임시 Collector config를 사용하여 다음 설정으로 동일한 장애를 재현했다.

```yaml
block_on_overflow: true
```

정상 경로를 먼저 200 Span smoke로 검증했다.

```text
Requested spans = 200
Accepted spans  = 200
Failed requests = 0
DB              = 200 / 200
queue           = 0
enqueue_failed  = 0
```

이후 70,000 Span 장애 실험:

```text
enqueue_failed Δ = 0
sent Δ           = 70,000
accepted Δ       = 70,000
refused Δ        = 0
DB               = 70,000
missing          = 0
```

대신 Sender에서는 backpressure가 관찰됐다.

```text
Request latency p99          = 170.502 ms
Producer lag p99             = 7,854.452 ms
Send-start lag p99           = 8,654.874 ms
Producer backpressure events = 312

Delivery success        = PASS
Sustained-rate validity = FAIL
```

데이터 유실이 upstream 지연으로 이동한 것으로 판단했다.

### 장기 Queue Saturation

queue 포화 상태를 15초 유지했다.

포화 중:

```text
queue      = 49,750
in_flight  = 2
enqueue_failed = 0
```

Sender 결과:

```text
Requested spans = 70,000
Accepted spans  = 69,600
Failed requests = 8

Observed accepted spans/sec = 1,265.39
Request latency max         = 10,011.129 ms
Producer lag p99            = 25,679.388 ms
Send-start lag p99          = 26,481.702 ms
Backpressure wait total     = 28,323.182 ms

Delivery success        = FAIL
Sustained-rate validity = FAIL
```

Sender는 8개 요청, 총 400 Span을 timeout으로 실패 판단했다.

하지만 Backend 복구와 Collector drain 후:

```text
enqueue_failed Δ = 0
sent Δ           = 70,000
accepted Δ       = 70,000
refused Δ        = 0

DB               = 70,000
missing          = 0
```

이었다.

client timeout과 실제 telemetry 저장 결과가 다를 수 있다는 점을 확인했다.

### 고정 Payload 기반 Ambiguous Timeout 재현

Timeout된 요청이 실제로 저장되는지 명확히 추적하기 위해 고정 OTLP payload를 생성했다.

```text
request files = 8
spans/request = 50
total spans   = 400
```

사전 DB count:

```text
0
```

Backend를 pause한 후 filler load로 Collector queue를 포화시켰다.

첫 번째 saturation detector는 `queue >= 49,750`이라는 고정 임계값을 사용했지만 queue가 `49,350`에서 장시간 정체돼 조건을 만족하지 못했다.

이 결과를 통해 절대 queue 값 하나만을 saturation 조건으로 사용하는 테스트가 workload의 batch 크기에 의존한다는 문제를 발견했다.

포화 판정을 다음처럼 수정했다.

```text
queue >= 49,000
in_flight >= 2
동일 queue 값이 연속적으로 유지
```

재실험에서:

```text
queue_saturated     = 49,550
in_flight_saturated = 2
```

를 확인했다.

고정된 8개 요청을 동시에 전송한 결과:

```text
request 01 curl_rc=28 HTTP=000
request 02 curl_rc=28 HTTP=000
request 03 curl_rc=28 HTTP=000
request 04 curl_rc=28 HTTP=000
request 05 curl_rc=28 HTTP=000
request 06 curl_rc=28 HTTP=000
request 07 curl_rc=28 HTTP=000
request 08 curl_rc=28 HTTP=000
```

모든 요청이 client timeout이었다.

Backend 복구 후:

```text
probe_db = 400 / 400
```

각 요청별:

```text
request 01 ~ 08
DB = 50 / 50
```

최종 Collector:

```text
queue     = 0
in_flight = 0
```

따라서 client가 timeout으로 실패 판단한 요청도 Collector 내부에 이미 수락되어 이후 저장될 수 있음을 재현했다.

### Ambiguous Timeout Retry Idempotency

Timeout된 8개의 JSON payload를 수정하지 않고 그대로 다시 전송했다.

Retry 전:

```text
count             = 400
distinct identity = 400
max ingested_at   = 2026-08-14 03:35:59.609128+00
```

Retry 결과:

```text
request 01 ~ 08
curl_rc = 0
HTTP    = 200
```

Retry 후:

```text
count             = 400
distinct identity = 400
max ingested_at   = 2026-08-14 03:35:59.609128+00
row growth        = 0
```

현재 DB Unique Identity:

```text
tenant_id
project_id
trace_id
span_id
start_time
```

저장 로직:

```sql
ON CONFLICT (
    tenant_id,
    project_id,
    trace_id,
    span_id,
    start_time
)
DO NOTHING
```

동일 telemetry retry가 실제 DB 중복을 만들지 않는 것을 장애 조건에서 확인했다.

### 운영 설정 적용

실험 결과를 근거로 정식 Collector 설정을 변경했다.

변경 파일:

```text
otel-collector-config.yaml
```

변경:

```diff
- block_on_overflow: false
+ block_on_overflow: true
```

임시 `/tmp` overlay 없이 Compose를 다시 구성했고 Runtime mount를 확인했다.

```text
source=/home/huning/aerotrace/otel-collector-config.yaml
destination=/etc/otel-collector-config.yaml
```

정식 설정으로 Collector를 재기동한 후 200 Span smoke:

```text
Requested spans        = 200
Accepted spans         = 200
Failed requests        = 0
Observed accepted rate = 99.97 spans/s
Delivery success       = PASS
Sustained-rate validity= PASS
sender_rc              = 0
DB                     = 200 / 200
```

Collector 최종 상태:

```text
queue     = 0
in_flight = 0
```

`git diff --check` 오류 없음.

### 최종 판단

AeroTrace는 Collector exporter queue overflow에서 silent telemetry loss보다 upstream backpressure를 우선한다.

```text
block_on_overflow=true
```

를 정식 기본값으로 사용한다.

장애가 길어질 경우 client latency 증가와 timeout은 발생할 수 있지만, 이번 실험에서는 queue 진입 단계 데이터 유실을 방지했고 timeout 후 동일 Span retry도 DB idempotency로 중복 저장 없이 처리됐다.

### 실무적 교훈

- OTLP request 성공과 최종 DB 저장 성공은 동일한 의미가 아니다.
- Collector receiver accepted metric만으로 end-to-end 데이터 보존을 판단하면 안 된다.
- queue overflow에서는 `enqueue_failed`와 최종 DB row 수를 함께 확인해야 한다.
- backpressure를 활성화하면 데이터 유실 문제가 latency와 timeout 문제로 이동할 수 있다.
- client timeout은 요청이 처리되지 않았다는 확정적인 증거가 아니다.
- ambiguous timeout이 존재하는 시스템에서는 저장 계층의 idempotency가 중요하다.
- retry 정책을 검증할 때 단순 정상 재전송뿐 아니라 실제 장애 조건에서 동일 payload를 재전송해야 한다.
- queue saturation을 고정된 절대 수치 하나로 판정하면 workload의 batch granularity 때문에 잘못된 테스트가 될 수 있다.
- 장애 실험에는 cleanup을 넣어 Backend가 pause 상태로 남지 않도록 해야 한다.
- 데이터 보존 정책은 throughput뿐 아니라 failure semantics까지 측정한 뒤 결정해야 한다.

### 보존해야 할 증거

다음 결과는 향후 포트폴리오와 운영 정책 검토를 위해 보존 가치가 있다.

```text
block_on_overflow=false 70,000 Span 결과
enqueue_failed=20,250 / DB=49,750 결과

block_on_overflow=true 70,000 Span 결과
enqueue_failed=0 / DB=70,000 결과

15초 saturation Sender timeout 결과
Failed requests=8 / Sender accepted=69,600
Collector accepted=70,000 / DB=70,000

고정 400 Span ambiguous timeout 결과
8/8 curl_rc=28
DB=400/400

동일 payload retry 결과
before_count=400
after_count=400
row_growth=0
ingested_at unchanged

정식 설정 200 Span smoke
sender_rc=0
DB=200/200
queue=0
in_flight=0
```

---

## Persistent Queue 장애 내구성 및 200k Queue Capacity 검증

### 목적

`block_on_overflow=true` 적용 후 AeroTrace Collector가 downstream 장애를 실제로 얼마나 버틸 수 있는지 검증했다.

검증 항목:

```text
persistent queue 실제 disk 저장
Collector graceful restart 복구
Collector SIGKILL 복구
queue item 수에 따른 storage 증가
기존 queue_size=50,000의 outage budget
queue_size=200,000 확대 가능성
190,000 Span backlog end-to-end 복구
```

### 현재 Persistent Storage 구조

Collector 설정:

```yaml
sending_queue:
  enabled: true
  num_consumers: 2
  sizer: items
  storage: file_storage/aerotrace

file_storage/aerotrace:
  directory: /var/lib/otelcol/storage
  timeout: 1s
  max_size: 536870912
  fsync: true
  compaction:
    on_start: true
    cleanup_on_start: true
```

Docker storage:

```text
volume = aerotrace-otelcol-data

container:
/var/lib/otelcol

host:
/var/lib/docker/volumes/aerotrace-otelcol-data/_data
```

Host filesystem 측정:

```text
/dev/sda2
Size      ≈ 468 GiB
Available ≈ 369 GiB
Usage     = 17%
```

### 20,000 Span Persistent Storage 실험

조건:

```text
Backend pause
target rate = 2,000 spans/s
duration    = 10 sec
requested   = 20,000 spans
```

Sender:

```text
Accepted spans  = 20,000
Failed requests = 0
sender_rc       = 0
```

장애 중:

```text
queue     = 20,000
in_flight = 2
DB        = 0 / 20,000
```

Filesystem:

```text
before apparent = 77,824
during apparent = 4,206,592
growth          = 4,128,768 bytes

before allocated = 57,344
during allocated = 2,678,784
growth           = 2,621,440 bytes
```

Backend 복구 후:

```text
DB        = 20,000 / 20,000
queue     = 0
in_flight = 0
```

### Collector Graceful Restart Recovery

다시 20,000 Span backlog를 생성했다.

Restart 전:

```text
queue     = 20,000
in_flight = 2
DB        = 0 / 20,000
```

`docker restart aerotrace-otel-collector` 실행.

Collector는 SIGTERM 기반 정상 shutdown을 수행했다.

Startup 이후:

```text
Loaded queue metadata

itemsSize       = 20000
bytesSize       = 2234800
dispatchedItems = 2
```

진행 중이던 export item:

```text
Fetching items left for dispatch by consumers
numberOfItems = 2

Moved items for dispatching back to queue
numberOfItems = 2
```

Restart 후 Backend가 계속 pause된 상태:

```text
queue     = 20,000
in_flight = 2
DB        = 0 / 20,000
```

Backend 복구 후:

```text
DB        = 20,000 / 20,000
queue     = 0
in_flight = 0
```

### Graceful Shutdown 중 Dropping Data 로그

Shutdown 과정에서:

```text
Exporting failed. Dropping data.
dropped_items = 1050
```

가 consumer 두 개에서 각각 발생했다.

단순 로그 합계로는 2,100 Span drop처럼 보였지만 restart 후:

```text
itemsSize = 20,000
final DB  = 20,000 / 20,000
```

이었다.

이번 failure path에서는 진행 중 export가 shutdown으로 실패하면서 drop 로그가 발생했지만 persistent queue state가 항목을 다시 복구했다.

교훈:

```text
Dropping data 로그만으로 실제 영구 유실을 단정하지 않는다.
persistent queue metadata와 최종 저장 결과를 함께 확인한다.
```

### Collector SIGKILL Recovery

Graceful shutdown이 불가능한 crash 상황을 재현했다.

조건:

```text
Backend pause
queue = 20,000
DB    = 0
```

실행:

```text
docker kill --signal=KILL aerotrace-otel-collector
```

종료 결과:

```text
running = false
status  = exited
exit    = 137
```

다시 Collector를 시작한 뒤:

```text
Loaded queue metadata

itemsSize       = 20000
bytesSize       = 2234800
dispatchedItems = 2
```

그리고 두 dispatched item을 다시 queue로 이동한 로그가 확인됐다.

Backend가 계속 pause된 상태:

```text
queue = 20,000
DB    = 0 / 20,000
```

Backend 복구 후:

```text
DB        = 20,000 / 20,000
queue     = 0
in_flight = 0
```

결과:

```text
graceful restart → 20,000 / 20,000 복구
SIGKILL          → 20,000 / 20,000 복구
```

### 기존 Queue Capacity 분석

기존:

```yaml
queue_size: 50000
```

Backend 처리량이 0인 완전 장애 기준:

```text
2,000 spans/s → 약 25초
3,250 spans/s → 약 15.4초
```

3,250 spans/s workload는 앞선 운영 후보 서버 sustained ingest 검증에서 사용한 실제 측정 조건이다.

따라서 50,000 Span capacity는 약 15초의 짧은 장애 buffer만 제공한다.

### Queue 200,000 후보 선정

목표:

```text
3,250 spans/s workload에서
Backend 완전 장애 약 1분 흡수
```

계산:

```text
3,250 × 60
= 195,000 spans
```

따라서 테스트 후보:

```yaml
queue_size: 200000
```

### 200k Queue 성장 곡선 실험

Repository 설정을 바로 변경하지 않고 `/tmp` Collector config를 사용했다.

조건:

```text
queue_size         = 200,000
block_on_overflow  = true
Backend            = paused
incoming rate      = 2,000 spans/s
```

순차적으로 backlog를 증가시켰다.

```text
checkpoint  queue    in_flight  file_size    apparent_bytes  allocated_bytes
baseline    0        0          32,768       45,056          32,768
50k         50,000   2          8,388,608    8,400,896       6,049,792
100k        100,000  2          16,777,216   16,789,504      11,894,784
150k        150,000  2          33,599,488   33,611,776      17,547,264
190k        190,000  2          33,599,488   33,611,776      21,954,560
```

각 workload:

```text
50k sender_rc  = 0
100k sender_rc = 0
150k sender_rc = 0
190k sender_rc = 0
```

장애 중 최종:

```text
queue = 190,000
DB    = 0 / 190,000
```

190k에서 baseline을 제외한 allocated storage 증가:

```text
21,921,792 bytes
≈ 20.9 MiB
```

이번 payload 기준으로 단순 환산하면 약:

```text
115 bytes/span allocated
```

였지만 bbolt page allocation과 reuse가 존재하므로 일반적인 고정 Span storage size로 사용하지 않는다.

특히:

```text
150k file_size = 33,599,488
190k file_size = 33,599,488
```

로 file size 자체는 증가하지 않은 반면 allocated bytes는 증가했다.

### 190k Backlog 복구

Backend unpause 후 queue drain:

```text
145,900
134,050
118,300
103,600
87,300
72,600
55,800
38,950
26,350
11,650
0
```

최종:

```text
DB        = 190,000 / 190,000
queue     = 0
in_flight = 0
```

190,000 Span persistent backlog 전체가 최종 DB까지 복구됐다.

### 정식 설정 승격

실험 결과를 근거로 repository 설정을 변경했다.

파일:

```text
otel-collector-config.yaml
```

변경:

```diff
- queue_size: 50000
+ queue_size: 200000
```

기존 정책:

```yaml
block_on_overflow: true
```

는 유지했다.

최종:

```yaml
sending_queue:
  enabled: true
  num_consumers: 2
  sizer: items
  queue_size: 200000
  block_on_overflow: true
  storage: file_storage/aerotrace
```

임시 overlay 없이 실제 runtime mount:

```text
source=/home/huning/aerotrace/otel-collector-config.yaml
destination=/etc/otel-collector-config.yaml
```

### 정식 설정 Smoke

```text
Requested spans = 200
Accepted spans  = 200
Failed requests = 0

sender_rc       = 0
DB              = 200 / 200

final queue     = 0
final in_flight = 0
```

`git diff --check` 오류 없음.

### 장애 Budget 변화

변경 전:

```text
queue_size=50,000

2,000 spans/s → 약 25초
3,250 spans/s → 약 15.4초
```

변경 후:

```text
queue_size=200,000

2,000 spans/s → 약 100초
3,250 spans/s → 약 61.5초
```

따라서 운영 후보 workload 기준 완전 장애 흡수 시간을 약 15초에서 약 1분으로 확대했다.

### 실무적 교훈

- persistent queue는 설정 존재 여부가 아니라 실제 restart/crash 실험으로 검증해야 한다.
- graceful restart만 통과했다고 프로세스 crash 복구까지 검증된 것은 아니다.
- SIGKILL 테스트를 통해 shutdown hook 없이도 persistent queue metadata가 복구되는지 확인할 수 있다.
- exporter의 `Dropping data` 로그와 최종 영구 유실은 항상 동일하지 않을 수 있다.
- queue logical bytes, bbolt file size, filesystem allocated bytes는 서로 다른 값이다.
- persistent queue storage는 payload 크기와 bbolt page allocation 특성 때문에 단순 선형 증가를 가정하면 안 된다.
- queue capacity는 임의의 큰 숫자가 아니라 목표 outage duration과 ingest rate에서 역산해야 한다.
- 50,000 Span queue는 높은 ingest rate에서 생각보다 짧은 장애만 흡수한다.
- capacity 변경 전 임시 config로 실제 workload를 재현하면 운영 설정 변경의 근거를 남길 수 있다.
- 데이터 보존은 queue drain뿐 아니라 최종 DB row 수까지 확인해야 한다.

### 아직 검증하지 않은 장애

다음은 후속 장애 실험 대상으로 남긴다.

```text
host reboot
강제 전원 차단
filesystem corruption
Docker volume loss
file_storage max_size 도달
host disk full
queue_size=200000 완전 포화 이후 동작
```

현재 결과를 위 장애까지 포함하는 것으로 과장하지 않는다.

### 보존해야 할 증거

```text
20k persistent storage filesystem 측정 결과
itemsSize=20000 / bytesSize=2234800 startup log
graceful restart 20000/20000 복구 결과
SIGKILL exit=137 결과
SIGKILL 후 Loaded queue metadata 로그
SIGKILL 후 20000/20000 복구 결과

50k / 100k / 150k / 190k growth.tsv
190k allocated_bytes=21954560
db_during=0/190000
final_db=190000/190000

queue_size=200000 정식 config diff
정식 mount source 확인
200 Span smoke 200/200
final_queue=0
final_in_flight=0
```

---

## 200k Persistent Queue 완전 포화 Failure Semantics 검증

### 목적

`queue_size=200000`이 계산상 약 1분의 outage buffer를 제공하는 것에 그치지 않고 실제 운영 후보 workload에서도 같은 동작을 보이는지 확인했다.

또한 queue가 실제 capacity에 도달한 이후:

```text
telemetry가 유실되는가?
Collector가 요청을 거부하는가?
upstream에 backpressure가 발생하는가?
Backend 복구 후 전체 backlog가 저장되는가?
```

를 검증했다.

### 테스트 조건

```text
Backend             = paused
target rate         = 3,250 spans/s
duration            = 65 sec
requested spans     = 211,250
batch size          = 50
sender workers      = 4

Collector:
queue_size          = 200,000
sizer               = items
num_consumers       = 2
block_on_overflow   = true
persistent storage  = enabled
```

테스트 시작 상태:

```text
queue     = 0
in_flight = 0

Backend:
running
paused=false
healthy
```

### Queue Saturation

queue는 점진적으로 증가했고 최종:

```text
queue_saturated     = 199,500
in_flight_saturated = 2
saturation_elapsed  = 62.622 sec
```

에서 안정적으로 plateau를 형성했다.

포화 판정은:

```text
queue >= 198,000
in_flight >= 2
동일 queue 값 4회 연속 관찰
```

조건으로 수행했다.

설계 시 계산값:

```text
200,000 / 3,250
≈ 61.54 sec
```

와 실제 62.622초가 근접하게 재현됐다.

configured queue capacity 200,000보다 작은 199,500에서 plateau가 형성된 것은 현재 workload에서 관찰되는 약 1,050 Span 단위 Collector batch granularity 영향으로 해석한다.

### Saturation Hold

포화 상태를 추가 5초 유지했다.

```text
queue_after_hold     = 199,500
in_flight_after_hold = 2
```

queue가 더 이상 증가하지 않았고 upstream sender에 backpressure가 전달됐다.

### Sender Backpressure

최종 Sender:

```text
Requested spans    = 211,250
Accepted spans     = 211,250
Requested requests = 4,225
Accepted requests  = 4,225
Failed requests    = 0

Actual elapsed     = 69.354845 sec
Target rate        = 3,250 spans/s
Observed rate      = 3,045.93 spans/s
Rate error         = 6.279%
```

Latency 및 backpressure:

```text
Request latency p99 = 7.932 ms
Request latency max = 6,513.931 ms

Producer lag p99    = 5,503.897 ms
Producer lag max    = 5,961.468 ms

Send-start lag p99  = 5,996.734 ms
Send-start lag max  = 6,453.542 ms

Backpressure wait total = 6,953.442 ms
Backpressure wait p99   = 241.917 ms
Backpressure wait max   = 5,961.325 ms

Producer backpressure events = 155
```

결과:

```text
Delivery success        = PASS
Sustained-rate validity = FAIL
sender_rc               = 22
```

`sender_rc=22`는 데이터 delivery 실패를 의미하지 않는다.

모든 211,250 Span 요청이 Collector에 전달됐지만 queue saturation으로 약 6초 수준의 지연이 upstream으로 전파되면서 목표 sustained rate를 유지하지 못한 결과다.

### Backend 복구와 Queue Drain

Backend 복구 후 queue:

```text
156,650
141,950
127,250
112,550
95,750
81,050
64,250
47,450
32,750
15,950
0
```

최종:

```text
queue     = 0
in_flight = 0
```

DB:

```text
211,250 / 211,250
```

으로 전체 telemetry 저장을 확인했다.

### Collector Counter Helper 오류 발견

실험 초기에 사용한 metric helper가 모든 Collector metric에 다음 label을 요구했다.

```text
data_type="traces"
```

그 결과:

```text
sent_spans
accepted_spans
refused_spans
enqueue_failed_spans
```

counter를 찾지 못하고 빈 값을 `0`으로 변환했다.

최초 출력:

```text
sent_before=0
accepted_before=0

sent_delta=0
accepted_delta=0
```

은 실제 metric 값이 아니라 helper 오류였다.

Raw metrics를 확인한 결과 label 구조는 다음과 달랐다.

Queue gauge:

```text
otelcol_exporter_queue_size{
  data_type="traces",
  exporter="otlp_http/aerotrace"
}

otelcol_exporter_in_flight_requests{
  data_type="traces",
  exporter="otlp_http/aerotrace"
}
```

Span counters:

```text
otelcol_exporter_sent_spans{
  exporter="otlp_http/aerotrace",
  ...
}

otelcol_receiver_accepted_spans{
  receiver="otlp",
  transport="http"
}

otelcol_receiver_refused_spans{
  receiver="otlp",
  transport="http"
}
```

즉 span counter에는 `data_type="traces"` label이 존재하지 않았다.

실험 후 실제 raw metric:

```text
otelcol_exporter_sent_spans     = 211,450
otelcol_receiver_accepted_spans = 211,450
otelcol_receiver_refused_spans  = 0
```

현재 Collector lifecycle에서 포화 실험 전에 수행한 정식 설정 smoke가 200 Span이므로 실제 포화 실험 delta는:

```text
sent delta     = 211,250
accepted delta = 211,250
refused delta  = 0
```

이다.

`otelcol_exporter_enqueue_failed_spans`는 raw metrics에 series 자체가 존재하지 않았다.

따라서 존재하지 않는 counter series를 자동으로 0으로 변환한 값을 측정값으로 사용하지 않는다.

### End-to-End 최종 결과

```text
Requested spans        = 211,250
Sender accepted        = 211,250
Failed sender requests = 0

Collector accepted Δ   = 211,250
Collector sent Δ       = 211,250
Collector refused      = 0

DB stored              = 211,250
Missing                = 0

Final queue            = 0
Final in-flight        = 0
```

### 결론

`queue_size=200000`은 3,250 spans/s workload에서 실제로 약 62.6초 후 effective saturation에 도달했다.

따라서 설계 목표였던:

```text
약 1분 Backend 완전 장애 buffer
```

가 실제 workload에서도 검증됐다.

queue가 포화된 이후에는 telemetry를 조용히 버리는 대신 upstream sender에 backpressure가 전달됐다.

Backend 복구 후 211,250 Span 전체가 DB까지 저장됐으며 이번 실험에서 영구 missing telemetry는 0건이었다.

### 실무적 교훈

- queue capacity의 이론적 outage budget은 실제 장애 테스트로 확인해야 한다.
- configured queue size와 실제 effective saturation point가 batch granularity 때문에 정확히 같지 않을 수 있다.
- backpressure가 정상 작동하면 delivery는 성공하면서 sustained-rate 목표는 실패할 수 있다.
- sender exit code만 보고 telemetry delivery 실패로 판단하면 안 된다.
- Collector internal metric은 metric 이름별 실제 label schema를 확인하고 수집해야 한다.
- 존재하지 않는 metric series를 무조건 0으로 변환하면 관측 오류를 실제 시스템 상태로 오인할 수 있다.
- internal metric 검증과 end-to-end DB count를 함께 사용해야 failure semantics를 정확히 판단할 수 있다.

### 보존해야 할 증거

```text
queue_saturated=199500
in_flight_saturated=2
saturation_elapsed_sec=62.622

queue_after_hold=199500
in_flight_after_hold=2

Backpressure wait total=6953.442 ms
Producer backpressure events=155

Requested=211250
Accepted=211250
Failed requests=0

DB=211250/211250
final_queue=0
final_in_flight=0

raw metric:
sent_spans=211450
accepted_spans=211450
refused_spans=0

200 Span prior smoke를 제외한:
sent delta=211250
accepted delta=211250
```

---

## Host Reboot Persistent Queue Recovery 실험

### 목적

앞선 실험에서 다음 Collector process 단위 장애에 대한 persistent queue 복구를 확인했다.

```text
graceful restart
SIGKILL
```

이번에는 failure boundary를 host 수준까지 확대해 다음을 검증했다.

```text
Host OS reboot
Docker daemon restart
Docker named volume 재마운트
Collector 재시작
Backend가 없는 상태에서 persistent backlog 복원
Backend 복구 후 최종 DB 저장
```

### 실험 준비

시작 전 Collector:

```text
queue     = 0
in_flight = 0
```

Backend:

```text
running
healthy
```

Host reboot 직후 queue가 의도치 않게 drain되는 것을 방지하기 위해 Backend를 `pause`하지 않고 정상 stop했다.

```text
docker stop aerotrace-backend
```

이후 Backend가 없는 상태에서:

```text
target rate = 2,000 spans/s
duration    = 10 sec
batch size  = 50
workers     = 4
```

조건으로 20,000 Span을 Collector에 전송했다.

Reboot 직전:

```text
queue_before_reboot     = 20,000
in_flight_before_reboot = 2
DB_before_reboot        = 0 / 20,000
```

persistent storage file size:

```text
33,570,816 bytes
```

였다.

실험 metadata는 shell과 `/tmp`가 reboot 후 사라질 수 있으므로 홈 디렉터리에 별도 저장했다.

```text
~/aerotrace-pq-host-reboot.state
```

저장 내용:

```text
REBOOT_RUN=20260814T053923Z
PREFIX=aerotrace-sustained-20260814T053924Z-a954b5-
QUEUE_BEFORE_REBOOT=20000
INFLIGHT_BEFORE_REBOOT=2
DB_BEFORE_REBOOT=0
FILE_BEFORE_REBOOT=33570816
```

### Host Reboot

실행:

```text
sudo reboot
```

SSH 연결이 종료된 뒤 host에 다시 접속했다.

재부팅 후 container 상태:

```text
aerotrace-otel-collector = Up
aerotrace-timescaledb    = Up / healthy
aerotrace-backend        = Exited (143)
```

Backend가 자동 시작되지 않았기 때문에 Collector가 persistent queue를 복원한 직후 상태를 DB drain 전에 확인할 수 있었다.

### Collector Persistent Queue 복구

Collector startup log:

```text
Loaded queue metadata

itemsSize       = 20000
bytesSize       = 2234800
dispatchedItems = 2
```

이어:

```text
Fetching items left for dispatch by consumers
numberOfItems = 2

Moved items for dispatching back to queue
numberOfItems = 2
```

가 확인됐다.

이는 host reboot 이전에 consumer가 dispatch 중이던 두 item까지 persistent metadata를 통해 다시 queue로 복구한 것이다.

Backend가 여전히 stopped인 상태에서 실제 Collector metric:

```text
queue_after_reboot     = 20,000
in_flight_after_reboot = 2
```

DB:

```text
0 / 20,000
```

이었다.

따라서 reboot 전에 DB에 저장된 데이터를 잘못 복구 결과로 판단하는 가능성을 제거했다.

### Backend 복구

Backend를 수동으로 시작했다.

```text
docker start aerotrace-backend
```

health:

```text
running false starting
...
running false healthy
```

까지 정상 복구됐다.

Collector queue drain:

```text
18,950
15,800
13,700
10,550
6,350
3,150
0
```

최종:

```text
queue     = 0
in_flight = 0
```

DB:

```text
20,000 / 20,000
```

Backend:

```text
running
healthy
```

### 최종 결과

```text
queue before reboot = 20,000
DB before reboot    = 0 / 20,000

Host reboot

queue after reboot  = 20,000
DB after reboot     = 0 / 20,000

Backend recovery

final DB            = 20,000 / 20,000
final queue         = 0
final in-flight     = 0
Backend             = running / healthy
```

### 현재까지의 Persistent Queue 장애 복구 결과

```text
Collector graceful restart
→ 20,000 / 20,000

Collector SIGKILL
→ 20,000 / 20,000

Host OS reboot
→ 20,000 / 20,000
```

세 실험 모두 Backend 복구 후 최종 DB row 수로 end-to-end 데이터 보존을 확인했다.

### 실무적 교훈

- process restart와 host reboot는 서로 다른 failure boundary이므로 별도로 검증해야 한다.
- persistent Docker volume을 사용한다고 해서 host reboot 복구를 가정만 해서는 안 된다.
- reboot 테스트에서는 downstream이 자동 복구되기 전에 queue 상태를 확인할 수 있도록 실험 조건을 설계해야 한다.
- shell 변수와 `/tmp` 파일은 reboot 실험의 기준 데이터 보존 위치로 적합하지 않다.
- reboot 전 prefix, queue size, DB count 등의 metadata를 persistent host path에 저장하면 재접속 후 동일 데이터를 정확히 추적할 수 있다.
- Collector startup log의 `Loaded queue metadata`는 persistent queue 복구 확인에 중요한 증거다.
- `itemsSize`뿐 아니라 dispatch 중이던 item이 다시 queue로 이동되는지도 확인해야 한다.
- persistent queue 복구 성공 여부는 startup log만이 아니라 최종 DB row 수까지 확인해야 한다.
- 정상 OS reboot 성공 결과를 강제 전원 차단이나 filesystem corruption 내구성까지 확대 해석하면 안 된다.

### 아직 검증하지 않은 Host/Storage 장애

```text
강제 전원 차단
filesystem corruption
Docker volume 손실
SSD 장애
host disk full
file_storage max_size 도달
file_storage write 실패
```

### 보존해야 할 증거

```text
~/aerotrace-pq-host-reboot.state

queue_before_reboot=20000
DB_before_reboot=0

Host reboot 후:
Backend=Exited
Collector=Up
TimescaleDB=healthy

Loaded queue metadata:
itemsSize=20000
bytesSize=2234800
dispatchedItems=2

Moved items for dispatching back to queue:
numberOfItems=2

queue_after_reboot=20000
DB_after_reboot=0

queue drain:
18950 → 15800 → 13700 → 10550 → 6350 → 3150 → 0

final_db=20000/20000
final_queue=0
final_in_flight=0
backend=running health=healthy
```

---

## Collector Queue 운영 체크 및 Threshold 검증

### 목적

Persistent queue 장애 복구 검증 후 운영자가 queue saturation 위험을 일관되게 판단할 수 있도록 재사용 가능한 운영 체크 도구를 구현했다.

새 파일:

```text
scripts/check-collector-queue.py
```

### 제공 정보

스크립트는 Collector metrics와 실제 repository config를 사용해 다음 정보를 출력한다.

```text
status
queue_size
queue_capacity
queue_utilization_pct
queue_remaining_items
full_outage_headroom_sec
reference_spans_per_sec

in_flight

sent_spans
enqueue_failed_spans
send_failed_spans

accepted_spans
refused_spans
```

### Queue Capacity

다음 값을 코드에 중복 hard-code하지 않고:

```text
otel-collector-config.yaml
```

에서 읽도록 구현했다.

현재:

```text
queue_size=200000
```

실제 실행에서도:

```text
queue_capacity=200000
```

으로 확인했다.

### Metric Series 부재 처리

이전 saturation 실험에서 metric helper가 존재하지 않는 series를 0으로 변환하는 문제가 있었다.

새 스크립트에서는 해당 문제를 방지하기 위해 series가 없으면:

```text
N/A
```

로 표시한다.

Host reboot 직후 실제 상태:

```text
sent_spans=20000

enqueue_failed_spans=N/A
send_failed_spans=N/A
accepted_spans=N/A
refused_spans=N/A
```

이었다.

이는 Collector reboot 이후 새 OTLP 수신 없이 persistent queue에 복원된 20,000 Span만 exporter가 Backend로 전송한 현재 lifecycle과 일치한다.

### 상태 및 Exit Code

기본:

```text
OK       = exit 0
WARNING  = exit 1
CRITICAL = exit 2
UNKNOWN  = exit 3
```

기본 threshold:

```text
warning  = 50%
critical = 80%
```

### 정상 상태 검증

```text
status=OK
queue_size=0
queue_capacity=200000
queue_utilization_pct=0.00
queue_remaining_items=200000
full_outage_headroom_sec=61.54
reference_spans_per_sec=3250.00
in_flight=0

exit=0
```

### Threshold Validation 검증

잘못된 설정:

```text
warn=0.9
critical=0.8
```

결과:

```text
UNKNOWN: warning ratio must be lower than critical ratio.
exit=3
```

잘못된 threshold로 운영 상태가 계산되지 않도록 방어했다.

### 실제 Queue Backlog 생성

Backend를 pause한 상태에서:

```text
target rate = 1,000 spans/s
duration    = 2 sec
requested   = 2,000 spans
accepted    = 2,000 spans
failed      = 0
sender_rc   = 0
```

의 실제 telemetry backlog를 생성했다.

Collector:

```text
queue_size=2000
queue_capacity=200000
queue_utilization_pct=1.00
queue_remaining_items=198000
full_outage_headroom_sec=60.92
in_flight=2
```

### 기본 Threshold

실제 queue utilization 1%에서는:

```text
status=OK
exit=0
```

이었다.

즉 작은 일시적 backlog를 운영 장애로 판단하지 않는다.

### WARNING 경로 검증

실제 queue를 대규모로 다시 채우지 않고 동일한 실제 2,000 Span backlog를 사용하면서 테스트 invocation에서만 threshold를 낮췄다.

```text
warning  = 0.5%
critical = 2%
```

실제:

```text
queue utilization = 1%
```

결과:

```text
status=WARNING
exit=1
```

### CRITICAL 경로 검증

테스트 threshold:

```text
warning  = 0.1%
critical = 0.5%
```

결과:

```text
status=CRITICAL
exit=2
```

따라서 실제 Collector metric을 입력으로 사용해:

```text
OK
WARNING
CRITICAL
UNKNOWN
```

네 상태와 exit code를 모두 검증했다.

### Backend Recovery

Backend unpause 후 health:

```text
unhealthy
unhealthy
healthy
```

queue는 첫 확인 시 이미:

```text
queue=0
```

까지 drain됐다.

최종 DB:

```text
2000/2000
```

### 최종 상태

```text
status=OK
queue_size=0
queue_capacity=200000
queue_utilization_pct=0.00
full_outage_headroom_sec=61.54
in_flight=0

sent_spans=22000
accepted_spans=2000
refused_spans=0
```

`sent_spans=22000`과 `accepted_spans=2000`의 차이는 오류가 아니다.

현재 Collector lifecycle에서:

```text
Host reboot 후 persistent queue 재전송 = 20,000
이번 테스트 신규 receiver 수신          = 2,000
```

이므로:

```text
exported total = 22,000
newly received = 2,000
```

과 일치한다.

### 실무적 교훈

- queue가 0보다 크다고 즉시 장애는 아니다.
- queue utilization과 남은 장애 buffer를 함께 봐야 한다.
- 운영 체크 도구는 사람이 읽는 출력뿐 아니라 자동화 가능한 exit code를 가져야 한다.
- production threshold를 검증하기 위해 실제 queue를 위험한 수준까지 매번 채울 필요는 없다.
- 실제 Collector metric을 사용하되 테스트 invocation에서 threshold만 낮춰 상태 분기 자체를 검증할 수 있다.
- metric series 부재와 실제 counter 0은 반드시 구분해야 한다.
- Collector counter는 process lifecycle에 종속된 cumulative metric이므로 서로 다른 restart 구간의 counter를 단순 비교하면 안 된다.
- queue capacity는 운영 config와 단일 source of truth를 유지해야 한다.

### 현재 기본 운영 기준

```text
queue < 50%
→ OK

queue >= 50%
→ WARNING

queue >= 80%
→ CRITICAL
```

현재 3,250 spans/s reference workload에서는:

```text
0% used
→ 약 61.54 sec remaining

50% used
→ 약 30.77 sec remaining

80% used
→ 약 12.31 sec remaining
```

단, 이는 Backend throughput이 완전히 0이라는 단순 outage 모델 기준이다.

### 다음 작업

현재 스크립트는 사람이 직접 실행해야 한다.

다음 단계에서는:

```text
누가 주기적으로 실행하는가?
어느 host/container에서 실행하는가?
경고 상태를 어디로 전달하는가?
중복 알림을 어떻게 방지하는가?
Collector 자체가 죽어 metrics를 읽을 수 없는 UNKNOWN은 어떻게 처리하는가?
```

를 설계한다.

---

## Collector Queue Alert 상태 전이 및 중복 알림 억제 구현

### 목적

`check-collector-queue.py`를 반복 실행할 수 있게 된 뒤 실제 운영 자동화를 위해 다음 문제가 남았다.

```text
동일 WARNING/CRITICAL이 반복될 때 중복 알림 방지
상태가 악화될 때 재알림
복구 시 recovery 알림
Collector 자체 장애 감지
scheduler와 alert 상태 판정 분리
```

이를 위해 다음 파일을 추가했다.

```text
scripts/evaluate-collector-queue-alert.py
```

### 구성

```text
Collector metrics
    ↓
scripts/check-collector-queue.py
    ↓
scripts/evaluate-collector-queue-alert.py
```

queue checker는 현재 상태만 판정한다.

```text
OK       → exit 0
WARNING  → exit 1
CRITICAL → exit 2
UNKNOWN  → exit 3
```

Evaluator는 이전 상태를 state file에 저장하고 상태 전이 여부를 판단한다.

### Evaluator Event

지원 event:

```text
NONE
ALERT
STATUS_CHANGE
RECOVERY
REMINDER
```

의미:

```text
NONE
→ notification 필요 없음

ALERT
→ 정상에서 장애 상태로 전환되었거나 최초 non-OK 상태

STATUS_CHANGE
→ WARNING → CRITICAL 등 non-OK 상태 사이의 변화

RECOVERY
→ non-OK → OK

REMINDER
→ 동일 장애 상태가 repeat interval 이상 지속
```

### 실제 Collector 정상 상태 테스트

실제 Collector queue가 비어 있는 상태에서:

```text
event=NONE
alert_required=false
previous_status=NONE
current_status=OK
checker_exit_code=0
evaluator_rc=0
```

state file:

```json
{
  "current_status": "OK",
  "last_changed_at": "2026-08-14T06:09:15+00:00",
  "last_evaluated_at": "2026-08-14T06:09:15+00:00"
}
```

가 생성됐다.

### 상태 머신 테스트

실제 queue를 반복적으로 채우는 대신 임시 fake checker를 사용해 evaluator의 상태 전이 로직을 독립적으로 검증했다.

#### 최초 OK

```text
event=NONE
alert_required=false
previous_status=NONE
current_status=OK
checker_exit_code=0
```

#### OK → WARNING

```text
event=ALERT
alert_required=true
previous_status=OK
current_status=WARNING
checker_exit_code=1

evaluator_rc=0
```

#### WARNING 지속

```text
event=NONE
alert_required=false
previous_status=WARNING
current_status=WARNING

evaluator_rc=0
```

동일 WARNING이 반복돼도 새로운 alert event가 발생하지 않았다.

#### WARNING → CRITICAL

```text
event=STATUS_CHANGE
alert_required=true
previous_status=WARNING
current_status=CRITICAL
checker_exit_code=2
```

장애 수준이 상승하면 새로운 notification event가 발생한다.

#### CRITICAL → OK

```text
event=RECOVERY
alert_required=true
previous_status=CRITICAL
current_status=OK
checker_exit_code=0
```

복구 상태 역시 notification 대상으로 분류된다.

#### 최초 UNKNOWN

```text
event=ALERT
alert_required=true
previous_status=NONE
current_status=UNKNOWN
checker_exit_code=3

evaluator_rc=0
```

UNKNOWN을 단순 무시하지 않는다.

### Checker 자체 실행 실패

존재하지 않는 checker 경로:

```text
/tmp/aerotrace-does-not-exist.py
```

를 사용했다.

결과:

```text
event=ALERT
alert_required=true
previous_status=NONE
current_status=UNKNOWN
checker_exit_code=N/A

checker_stderr=
checker execution failed:
No such file or directory

missing_checker_rc=0
```

즉 checker/Collector 계층의 문제는 운영 상태 UNKNOWN으로 정상 분류했다.

Evaluator 자체는 정상적으로 한 번의 평가를 수행했으므로 exit code는 0이다.

### Evaluator 자체 실패

잘못된 설정:

```text
--repeat-after-sec -1
```

결과:

```text
evaluator_error=
--repeat-after-sec must be >= 0

evaluator_error_rc=4
```

따라서 다음을 구분할 수 있다.

```text
Collector/checker 문제
→ UNKNOWN
→ evaluator exit 0

Evaluator 자체 문제
→ evaluator exit 4
```

### Alert Storm 방지

향후 evaluator를 5초마다 실행하더라도 상태가 변하지 않으면:

```text
WARNING → WARNING
CRITICAL → CRITICAL
```

에서는 `event=NONE`이므로 매 실행마다 알림을 보내지 않는다.

단, 장시간 장애를 완전히 잊는 것을 막기 위해:

```text
--repeat-after-sec
```

기능을 제공한다.

기본:

```text
300 sec
```

이후 실제 alert sender가 추가되면 REMINDER 경로도 함께 검증한다.

### Persistent State

기본 state file:

```text
~/.local/state/aerotrace/collector-queue-alert.json
```

state 기록은:

```text
temporary file write
→ flush
→ fsync
→ rename
```

순서로 처리해 일부만 기록된 state를 읽을 위험을 줄였다.

### 실무적 교훈

- 상태 판정과 notification 발생 여부는 서로 다른 책임이다.
- polling 주기가 짧다고 동일 장애 알림도 같은 빈도로 전송하면 안 된다.
- WARNING에서 CRITICAL로 악화되는 상태 변화는 새로운 이벤트로 취급해야 한다.
- 장애 발생 알림만큼 복구 알림도 중요하다.
- UNKNOWN은 정상 상태가 아니며 monitoring path 자체의 장애일 수 있다.
- scheduler가 해석하는 process failure와 실제 서비스 WARNING/CRITICAL을 분리해야 한다.
- state machine을 실제 대규모 장애 실험 없이 fake checker로 독립 검증하면 테스트 비용과 위험을 낮출 수 있다.
- 상태를 파일에 저장할 경우 partial write 가능성을 고려해야 한다.

### 다음 작업

현재 evaluator는 수동 실행 방식이다.

다음 단계에서는 SaaS host에서:

```text
systemd service
+
systemd timer
```

를 이용해 주기 실행한다.

판정 로직은 Python script에 유지하고 systemd는 실행 scheduling만 담당하도록 해 향후 온프레미스 환경에서 다른 scheduler로 교체할 수 있게 한다.

---

## Collector Queue Alert systemd 자동 실행 및 E2E 장애 감지 검증

### 목적

수동으로 검증한 queue checker와 alert evaluator를 실제 운영 scheduler에 연결해 다음 전체 경로를 검증했다.

```text
주기 실행
상태 persistence
정상 상태 로그 억제
Collector 장애 자동 탐지
동일 장애 중복 억제
Collector 복구 자동 탐지
```

### Quiet Mode 추가

Evaluator에:

```text
--quiet-no-event
```

옵션을 추가했다.

최초 구현 과정에서는 argparse option만 추가되고 실제 return 조건이 누락돼:

```text
quiet_rc=0
quiet_output_bytes=455
```

로 정상 상태 출력이 계속 발생하는 문제를 발견했다.

원인:

```text
--quiet-no-event option은 존재
event=NONE 조건의 early return은 누락
```

수정:

```python
if args.quiet_no_event and event == "NONE":
    return 0
```

을 state write 이후, 출력 직전에 추가했다.

재검증:

```text
quiet_rc=0
quiet_output_bytes=0
```

state:

```json
{
  "current_status": "OK",
  "last_changed_at": "2026-08-18T03:14:32+00:00",
  "last_evaluated_at": "2026-08-18T03:14:32+00:00"
}
```

로 정상 기록됐다.

이벤트가 발생하면 quiet mode에서도 출력하도록 유지했다.

### systemd Unit

Repository:

```text
deploy/systemd/aerotrace-collector-queue-alert.service
deploy/systemd/aerotrace-collector-queue-alert.timer
```

Service 구조:

```text
Type=oneshot
User=huning
Group=huning
WorkingDirectory=/home/huning/aerotrace
```

Evaluator:

```text
--state-file
/var/lib/aerotrace-monitoring/collector-queue-alert.json

--repeat-after-sec 300
--checker-timeout-sec 10
--quiet-no-event
```

Timer:

```text
OnBootSec=30s
OnUnitInactiveSec=5s
AccuracySec=1s
```

### Static Verification

`systemd-analyze verify` 실행 시 AeroTrace unit 자체 오류는 발생하지 않았다.

Host에 이미 존재하는 다른 unit에서 다음 메시지가 출력됐다.

```text
netplan-ovs-cleanup.service permission warning
snapd.service RestartMode unknown key
```

AeroTrace service/timer를 가리키는 오류는 없었다.

Python compile 및:

```text
git diff --check
```

도 통과했다.

### systemd 설치

Unit을:

```text
/etc/systemd/system
```

에 설치하고:

```text
systemctl daemon-reload
```

를 수행했다.

실제 systemd가 읽은 service 값:

```text
User=huning
Group=huning
WorkingDirectory=/home/huning/aerotrace
StateDirectory=aerotrace-monitoring
```

을 확인했다.

### Service 단독 실행

Timer를 활성화하기 전에 service를 한 번 직접 실행했다.

결과:

```text
Type=oneshot
Active=inactive (dead)
Result=success
ExecMainStatus=0
```

`inactive (dead)`는 oneshot 실행 종료 후의 정상 상태다.

### Persistent StateDirectory 검증

실제 생성:

```text
/var/lib/aerotrace-monitoring
```

State:

```text
/var/lib/aerotrace-monitoring/collector-queue-alert.json
```

권한:

```text
directory:
huning:huning
750

state file:
huning:huning
640
```

현재 상태:

```text
current_status=OK
```

을 확인했다.

### Timer 활성화

실행:

```text
systemctl enable --now
aerotrace-collector-queue-alert.timer
```

결과:

```text
enabled
active
```

Timer status:

```text
active (waiting)
```

이며 약 5~6초 간격으로 service 실행이 반복됐다.

### 실제 반복 실행 검증

State file의 mtime과 `last_evaluated_at`을 비교했다.

첫 측정:

```text
before_evaluated=
2026-08-18T03:42:28+00:00

after_evaluated=
2026-08-18T03:42:40+00:00

timer_state_update=PASS
```

다음 cycle:

```text
timer_repeat=PASS
```

따라서 timer가 active 표시만 되는 것이 아니라 실제 evaluator 실행을 반복하는 것을 검증했다.

### 정상 상태 Journal

최근 journal:

```text
Starting AeroTrace Collector queue alert evaluator...
Deactivated successfully.
Finished AeroTrace Collector queue alert evaluator.
```

가 반복됐다.

정상 `event=NONE` 상태에서는 다음 상세 출력이 기록되지 않았다.

```text
checker_output_begin
queue_size=...
checker_output_end
```

따라서 5초 polling에서 정상 metric dump로 인한 journal spam을 억제했다.

### Collector 장애 자동 감지 E2E 테스트

시작:

```text
timer=enabled/active
current_status=OK
Collector=running
Backend=healthy
```

Collector를 실제 중단했다.

```text
docker stop aerotrace-otel-collector

running=false
status=exited
exit=0
```

Timer가 자동 실행된 뒤 state:

```text
current_status=UNKNOWN
last_changed_at=2026-08-18T03:46:39+00:00
```

Journal:

```text
event=ALERT
alert_required=true
previous_status=OK
current_status=UNKNOWN
checker_exit_code=3

checker_stderr=
UNKNOWN: <urlopen error [Errno 111] Connection refused>
```

사람이 evaluator를 직접 실행하지 않고 systemd timer가 Collector 장애를 자동 감지했다.

### 동일 장애 중복 억제

Collector가 계속 중단된 상태에서 timer가 여러 번 평가됐지만:

```text
alert_count=1
```

이었다.

즉:

```text
최초 OK → UNKNOWN
→ ALERT

이후 UNKNOWN 유지
→ event=NONE
→ quiet output
```

이 실제 scheduler 환경에서도 동작했다.

### Collector Recovery 자동 감지

Collector를 다시 시작했다.

```text
docker start aerotrace-otel-collector
```

metrics endpoint 복구 확인 후 timer가 자동으로 상태 변화를 발견했다.

```text
event=RECOVERY
alert_required=true
previous_status=UNKNOWN
current_status=OK
checker_exit_code=0
```

state:

```text
current_status=OK
last_changed_at=2026-08-18T03:47:15+00:00
```

최종 이벤트 수:

```text
alert_count=1
recovery_count=1
```

### 최종 자동화 경로

현재 실제 검증된 흐름:

```text
Collector healthy
    ↓
systemd timer 5초 polling
    ↓
OK

Collector stop
    ↓
metrics Connection refused
    ↓
UNKNOWN
    ↓
ALERT 1회
    ↓
동일 UNKNOWN 중복 억제

Collector start
    ↓
metrics 복구
    ↓
OK
    ↓
RECOVERY 1회
```

### 실무적 교훈

- scheduler가 active인 것과 실제 workload가 반복 실행되는 것은 별도로 검증해야 한다.
- persistent state의 mtime과 timestamp를 확인하면 실제 scheduler execution을 증명할 수 있다.
- 정상 상태의 짧은 polling은 로그 억제가 없으면 불필요한 journal 사용량을 만든다.
- Collector 자체 장애는 queue CRITICAL이 아니라 metrics 접근 불가 UNKNOWN으로 감지해야 한다.
- UNKNOWN 상태 역시 alert 대상이어야 monitoring system 자체 장애를 놓치지 않는다.
- polling과 notification을 분리하면 동일 장애의 반복 실행과 반복 알림을 독립적으로 제어할 수 있다.
- recovery event를 실제 scheduler 경로에서 검증해야 장애 종료까지 운영 자동화가 완성된다.

### 다음 작업

현재 자동화는 event를 systemd journal에 출력하는 단계까지다.

다음에는 실제 queue backlog에 대해:

```text
OK
→ WARNING
→ CRITICAL
→ RECOVERY
```

자동 상태 전이를 검증한다.

대규모 100k/160k backlog를 다시 생성하지 않고 테스트용 threshold를 안전하게 전달할 수 있는 구조를 먼저 만든다.

이후 외부 notification adapter를 연결한다.

---

## Collector Queue WARNING / CRITICAL / RECOVERY 자동 전이 E2E 검증

### 목적

Collector process 자체 장애에 대한 UNKNOWN 자동 탐지 검증 이후 실제 persistent queue backlog를 이용해 다음 severity state transition 전체를 systemd timer 자동 실행 경로에서 검증했다.

```text
OK
→ WARNING
→ CRITICAL
→ RECOVERY
```

대규모 production threshold backlog를 반복 생성하지 않기 위해 production configuration은 그대로 유지하면서 runtime-only test threshold를 사용했다.

### Evaluator Checker Argument 전달 기능

수정 파일:

```text
scripts/evaluate-collector-queue-alert.py
```

반복 지정 가능한 옵션을 추가했다.

```text
--checker-arg
```

특징:

```text
action=append
여러 번 지정 가능
checker subprocess argv에 순서대로 전달
```

기존 evaluator 실행 호환성 테스트:

```text
event=NONE
alert_required=false
previous_status=NONE
current_status=OK
checker_exit_code=0
base_rc=0
```

통과.

Test threshold 전달:

```text
--checker-arg=--warn-ratio
--checker-arg=0.001
--checker-arg=--critical-ratio
--checker-arg=0.005
```

queue=0 상태에서:

```text
current_status=OK
checker_exit_code=0
arg_rc=0
```

을 확인했다.

잘못된 threshold:

```text
warn=0.9
critical=0.8
```

전달 시 checker:

```text
checker_exit_code=3
checker_stderr=UNKNOWN: warning ratio must be lower than critical ratio.
```

Evaluator:

```text
event=ALERT
alert_required=true
previous_status=NONE
current_status=UNKNOWN
bad_rc=0
```

으로 동작했다.

즉 checker configuration failure는 monitoring 상태 UNKNOWN으로 변환되고 evaluator 자체 evaluation은 정상 완료된다.

### Production 설정과 테스트 설정 분리

Production checker는 기존 값을 유지했다.

```text
WARNING=50%
CRITICAL=80%
```

테스트에만 다음 runtime systemd drop-in을 사용했다.

```text
/run/systemd/system/
aerotrace-collector-queue-alert.service.d/
10-test-thresholds.conf
```

테스트 종료 후 파일과 directory를 제거하고:

```text
systemctl daemon-reload
```

를 실행했다.

최종:

```text
production_execstart=PASS
```

로 production ExecStart에 `--checker-arg`가 남아 있지 않음을 확인했다.

---

### WARNING E2E 테스트

초기 상태:

```text
current_status=OK
queue_size=0
backend=running
backend=healthy
timer=enabled
timer=active
```

Test threshold:

```text
WARNING=0.5%
CRITICAL=2%
```

Backend를 pause했다.

```text
running=true
paused=true
status=paused
```

실제 telemetry 2,000 spans를 전송했다.

Sender 결과:

```text
Requested spans: 2000
Accepted spans: 2000
Requested requests: 40
Accepted requests: 40
Failed requests: 0

Actual elapsed sec: 2.000266
Target spans/sec: 1000
Observed accepted spans/sec: 999.87
Rate error pct: 0.013

Backpressure wait total ms: 0.000
Producer backpressure events: 0

Delivery success: PASS
Sustained-rate validity: PASS
```

Collector queue:

```text
status=OK
queue_size=2000
queue_capacity=200000
queue_utilization_pct=1.00
queue_remaining_items=198000
full_outage_headroom_sec=60.92
in_flight=2
accepted_spans=2000
refused_spans=0
```

Production checker는 50%/80% default를 사용하므로:

```text
status=OK
```

이었다.

Systemd evaluator는 runtime threshold를 사용해 자동으로 다음 이벤트를 생성했다.

```text
event=ALERT
alert_required=true
previous_status=OK
current_status=WARNING
checker_exit_code=1
queue_size=2000
queue_utilization_pct=1.00
```

동일 WARNING 상태의 반복 평가 후:

```text
warning_alert_count=1
```

로 alert storm이 발생하지 않았다.

Backend를 unpause한 뒤 health는 다음 순서로 회복했다.

```text
unhealthy
unhealthy
unhealthy
healthy
```

Collector queue는 복구 후 첫 확인에서:

```text
queue=0
```

으로 drain 완료됐다.

Systemd evaluator는 자동으로 다음 recovery를 생성했다.

```text
event=RECOVERY
alert_required=true
previous_status=WARNING
current_status=OK
checker_exit_code=0
```

DB 저장 결과:

```text
2000/2000
```

테스트 runtime override 제거 후 최종 상태:

```text
current_status=OK
queue_size=0
timer_enabled=enabled
timer_active=active
db=2000/2000
```

---

### WARNING → CRITICAL E2E 테스트

두 번째 실험에서도 동일한 실제 backlog 크기를 사용했다.

초기 test threshold:

```text
WARNING=0.5%
CRITICAL=2%
```

Backend를 pause한 뒤 실제 2,000 spans를 전송했다.

Sender:

```text
Requested spans: 2000
Accepted spans: 2000
Requested requests: 40
Accepted requests: 40
Failed requests: 0

Actual elapsed sec: 2.000172
Observed accepted spans/sec: 999.91
Rate error pct: 0.009

Backpressure wait total ms: 0.000
Producer backpressure events: 0

Delivery success: PASS
Sustained-rate validity: PASS
```

Collector:

```text
queue_size=2000
queue_capacity=200000
queue_utilization_pct=1.00
queue_remaining_items=198000
in_flight=2
```

Systemd가 자동 WARNING을 생성했다.

```text
event=ALERT
alert_required=true
previous_status=OK
current_status=WARNING
checker_exit_code=1
queue_size=2000
queue_utilization_pct=1.00
```

Backend를 pause 상태로 유지해 queue를 2,000으로 유지한 뒤 runtime threshold만 다음 값으로 변경했다.

```text
WARNING=0.1%
CRITICAL=0.5%
```

동일한 실제 queue metric에 대해 systemd evaluator가 다음 severity escalation을 자동 생성했다.

```text
event=STATUS_CHANGE
alert_required=true
previous_status=WARNING
current_status=CRITICAL
checker_exit_code=2
queue_size=2000
queue_utilization_pct=1.00
```

이벤트 count:

```text
alert_count=1
status_change_count=1
```

따라서 동일 상태의 반복 ALERT가 아니라 실제 severity 변화에 대해 별도의 STATUS_CHANGE가 발생함을 확인했다.

### CRITICAL Recovery

Backend를 unpause했다.

Backend health:

```text
unhealthy
unhealthy
unhealthy
healthy
```

Collector queue drain 과정:

```text
1000
1000
0
```

Systemd evaluator는 queue가 정상화된 뒤 자동으로 다음 이벤트를 생성했다.

```text
event=RECOVERY
alert_required=true
previous_status=CRITICAL
current_status=OK
checker_exit_code=0
```

Recovery count:

```text
recovery_count=1
```

DB 저장 결과:

```text
2000/2000
```

### 최종 운영 상태

Runtime test override 제거 후:

```text
production_execstart=PASS
```

최종 Collector 상태:

```text
status=OK
queue_size=0
queue_capacity=200000
queue_utilization_pct=0.00
queue_remaining_items=200000
full_outage_headroom_sec=61.54
in_flight=0
```

Collector process lifecycle 누적 metric:

```text
sent_spans=4000
accepted_spans=4000
refused_spans=0
```

이번 WARNING/CRITICAL 실험 두 번에서 각각 2,000 spans를 전송한 결과와 일치한다.

Alert state:

```text
current_status=OK
```

Scheduler:

```text
timer_enabled=enabled
timer_active=active
```

### 검증된 자동 상태 머신

실제 Collector queue metric 기준:

```text
OK
    ↓ queue threshold 초과
WARNING
    ↓ severity 상승
CRITICAL
    ↓ queue drain
OK
```

Evaluator event:

```text
OK → WARNING
ALERT

WARNING → CRITICAL
STATUS_CHANGE

CRITICAL → OK
RECOVERY
```

각 event:

```text
ALERT=1
STATUS_CHANGE=1
RECOVERY=1
```

로 검증됐다.

### 실무적 교훈

- Alert state machine 테스트와 production threshold 설정 변경은 분리해야 한다.
- `/run/systemd/system` drop-in을 사용하면 reboot-persistent production configuration을 오염시키지 않고 실제 scheduler 경로를 테스트할 수 있다.
- systemd `ExecStart`를 override할 때 기존 명령을 먼저 빈 `ExecStart=`로 초기화해야 한다.
- Unit override 테스트 후 production configuration 복원 여부까지 검증해야 실험이 완료된 것이다.
- 동일 metric에서도 threshold 변화에 따라 severity가 상승할 수 있으므로 단순 non-OK boolean보다 WARNING과 CRITICAL 상태를 구분할 가치가 있다.
- WARNING 반복과 WARNING → CRITICAL 승격은 서로 다른 운영 의미를 가지므로 `ALERT`와 `STATUS_CHANGE`를 분리하는 것이 유용하다.
- Recovery 시 직전 상태를 보존하면 WARNING 장애였는지 CRITICAL 장애였는지 전체 lifecycle을 설명할 수 있다.
- Sender 성공, queue metric, alert event, DB 최종 저장량을 함께 확인해야 monitoring 테스트 과정에서 telemetry data loss가 발생하지 않았음을 검증할 수 있다.

### 남은 범위

현재 alert event는 systemd journal까지 자동 생성된다.

아직 실제 운영자에게 전달되는 외부 notification channel은 연결하지 않았다.

다음 단계에서는:

```text
alert_required=true
```

인 이벤트를 notification adapter가 받아 외부 채널로 전달하도록 한다.

Evaluator의 상태 판단 로직과 notification transport는 분리한다.

Notification 실패가 Collector queue monitoring 자체를 방해하지 않도록 timeout, retry, duplicate notification 정책도 별도로 설계한다.

---

## Durable Notification Outbox 및 systemd Failure Isolation 검증

### 목표

Collector queue alert의 상태 판단과 외부 notification transport를 분리하고 notification provider 장애가 monitoring 자체를 중단시키지 않는 구조를 구현·검증했다.

기존:

```text
Collector metrics
→ checker
→ evaluator
→ journal
```

변경 후:

```text
Collector metrics
→ checker
→ evaluator
→ notification JSON outbox
→ independent notification adapter
→ transport
```

### 변경 파일

```text
scripts/evaluate-collector-queue-alert.py
scripts/process-notification-outbox.py
```

### Evaluator JSON Event Contract

Evaluator에:

```text
--output-format text|json
```

을 추가했다.

기본값은:

```text
text
```

이므로 기존 production systemd 실행과 호환된다.

JSON mode:

```text
--output-format json
```

에서는 한 이벤트당 JSON 한 줄만 출력한다.

NONE event 검증:

```text
json_rc=0
json_lines=1
json_contract=PASS
```

주요 값:

```text
schema_version=1
event=NONE
alert_required=false
previous_status=null
current_status=OK
checker_exit_code=0
```

`--quiet-no-event`와 JSON을 같이 사용한 경우:

```text
quiet_json_rc=0
quiet_json_bytes=0
```

을 확인했다.

잘못된 checker threshold를 이용한 ALERT JSON:

```text
event=ALERT
alert_required=true
previous_status=null
current_status=UNKNOWN
checker_exit_code=3
```

검증:

```text
alert_json_contract=PASS
```

### Event Outbox

Evaluator에:

```text
--event-outbox-dir
```

을 추가했다.

Notification event에는:

```text
event_id
```

가 추가됐다.

최초 ALERT:

```text
outbox_alert_rc=0
outbox JSON file=1
stdout_outbox_match=PASS
outbox_contract=PASS
```

동일 UNKNOWN 상태를 다시 평가하면:

```text
repeat_rc=0
repeat_output_bytes=0
outbox_file_count=1
```

로 새로운 notification event가 추가되지 않았다.

### Outbox Failure Ordering

Outbox path가 directory가 될 수 없도록 의도적으로 실패시켰다.

결과:

```text
evaluator_error=event outbox write failed: [Errno 20] Not a directory
outbox_failure_rc=4
state_after_outbox_failure=PASS_not_written
```

Outbox 문제를 해결한 뒤 재실행:

```text
retry_rc=0
event=ALERT
current_status=UNKNOWN
retry_outbox_count=1
```

Evaluator state도 이후 정상 생성됐다.

따라서 outbox 저장 실패 때문에 최초 notification event가 사라지는 것을 방지했다.

### Notification Adapter

새 스크립트:

```text
scripts/process-notification-outbox.py
```

기능:

```text
pending JSON 조회
schema_version 검증
event_id 검증
event/status contract 검증
순서대로 event 처리
delivery 성공 시 pending 제거
delivery 실패 시 pending 유지
event_id duplicate ACK
```

정상 local delivery:

```text
delivery_result=DELIVERED
event=ALERT
processed_events=1
remaining_events=0
adapter_rc=0
```

파일 상태:

```text
pending_before=1
pending_after=0
delivered_after=1
```

### Duplicate ACK

이미 delivered된 event를 pending에 다시 놓아 crash window를 재현했다.

Adapter:

```text
delivery_result=ACK_EXISTING
```

결과:

```text
pending_after_ack_existing=0
delivered_after_ack_existing=1
```

로 동일 event가 이중 저장되지 않았다.

### Adapter Delivery Failure

Delivered path를 일반 파일로 만들어 delivery를 실패시켰다.

결과:

```text
adapter_error=delivery failed: [Errno 17] File exists: ...
delivery_failure_rc=2
pending_after_delivery_failure=1
```

Evaluator state:

```text
current_status=UNKNOWN
```

은 그대로 유지됐다.

Delivery path 복구 후:

```text
delivery_result=DELIVERED
delivery_retry_rc=0
pending_after_retry=0
delivered_after_retry=1
```

로 재처리됐다.

### IDLE Log Suppression

5초 systemd timer의 정상 상태 로그를 억제하기 위해 adapter에:

```text
--quiet-idle
```

을 추가했다.

기존 기본 동작:

```text
adapter_status=IDLE
processed_events=0
remaining_events=0
normal_idle_rc=0
```

`--quiet-idle`:

```text
quiet_idle_rc=0
quiet_idle_bytes=0
```

Outbox directory 자체가 없는 경우에도:

```text
missing_outbox_rc=0
missing_outbox_bytes=0
```

실제 event가 존재하면 quiet 옵션과 관계없이 delivery 로그는 출력됐다.

```text
delivery_result=DELIVERED
event=ALERT
processed_events=1
remaining_events=0
event_delivery_rc=0
```

### systemd Normal Pipeline

Runtime-only systemd 설정으로 다음 두 독립 timer를 구성했다.

```text
aerotrace-collector-queue-alert.timer
aerotrace-notification-outbox.timer
```

Evaluator에는 runtime-only:

```text
--event-outbox-dir /var/lib/aerotrace-monitoring/notification-outbox
```

을 추가했다.

Production checker threshold argument는 추가하지 않았다.

검증:

```text
evaluator_outbox=PASS
production_threshold=PASS_default
```

Notification timer:

```text
notification_timer_active=active
```

Evaluator 반복 실행:

```text
before_evaluated=2026-08-18T06:35:57+00:00
after_evaluated=2026-08-18T06:36:14+00:00
evaluator_still_running=PASS
```

정상 상태:

```text
pending_events=0
delivered_events=0
```

Notification service:

```text
Result=success
ExecMainStatus=0
```

Journal에는 Python IDLE/OK metric spam 없이 systemd lifecycle log만 남았다.

### systemd ALERT / RECOVERY E2E

초기:

```text
current_status=OK
Collector running
pending=0
delivered=0
```

Collector를 실제 중단했다.

```text
running=false
status=exited
exit=0
```

Evaluator 자동 상태 전이:

```text
event=ALERT
alert_required=true
previous_status=OK
current_status=UNKNOWN
checker_exit_code=3
checker_stderr=UNKNOWN: <urlopen error [Errno 111] Connection refused>
```

Notification adapter timer 자동 처리:

```text
delivery_result=DELIVERED
event=ALERT
processed_events=1
remaining_events=0
```

결과:

```text
pending_after_alert=0
delivered_after_alert=1
automatic_alert_delivery=PASS
```

Collector를 다시 시작하고 metrics endpoint 준비를 확인했다.

Evaluator가 자동으로:

```text
UNKNOWN → OK
RECOVERY
```

를 생성했고 notification adapter가 자동 전달했다.

최종 sequence:

```text
1: event=ALERT previous=OK current=UNKNOWN
2: event=RECOVERY previous=UNKNOWN current=OK
```

검증:

```text
automatic_recovery_delivery=PASS
delivered_event_sequence=PASS

final_pending=0
final_delivered=2
```

Delivered JSON 파일 권한:

```text
owner=huning
group=huning
mode=640
```

으로 systemd service의 `UMask=0027`이 적용됨을 확인했다.

### systemd Notification Failure Isolation

Baseline:

```text
delivered_baseline=2
pending_baseline=0
before_failure_evaluated=2026-08-18T06:44:47+00:00
```

Notification service의 delivered-dir만 runtime override로 고장냈다.

Collector 중단 후:

```text
attempt=03
state=UNKNOWN
pending=1

pending_alert_after_delivery_failure=PASS
```

Notification journal:

```text
adapter_error=delivery failed: [Errno 17] File exists: '/var/lib/aerotrace-monitoring/notification-delivery-blocker'
failed_event_id=1787035511951348652-1520983
remaining_events=1
```

Notification failure를 유지한 채 14초 후 evaluator:

```text
before_failure_evaluated=2026-08-18T06:44:47+00:00
after_failure_evaluated=2026-08-18T06:45:29+00:00

evaluator_independent_from_notification=PASS
```

동시에:

```text
pending_during_failure=1
evaluator_timer=active
notification_timer=active

Evaluator:
Result=success
ExecMainStatus=0
```

이었다.

따라서 notification delivery 실패가 Collector 상태 평가 주기를 중단시키지 않았다.

### 자동 Pending Retry

Notification failure override만 제거하고 adapter를 수동 실행하지 않았다.

첫 확인에서:

```text
pending=0
delivered=3

automatic_pending_retry=PASS
```

가 됐다.

Retry된 event:

```text
latest_event=ALERT
previous=OK
current=UNKNOWN
event_id=1787035511951348652-1520983

retried_alert_contract=PASS
```

원래 pending event_id가 그대로 전달됐다.

Collector를 복구한 뒤:

```text
state=OK
delivered=4

automatic_recovery_after_notification_failure=PASS
```

마지막 두 event:

```text
1: ALERT
   OK → UNKNOWN

2: RECOVERY
   UNKNOWN → OK

failure_recovery_sequence=PASS
```

### Runtime Cleanup

Notification test timer 중단 후 다음을 제거했다.

```text
runtime evaluator outbox override
runtime notification service
runtime notification timer
notification failure override
test outbox
test delivered sink
```

최종:

```text
production_evaluator=PASS
notification_runtime_cleanup=PASS

evaluator_timer_enabled=enabled
evaluator_timer_active=active

Collector:
running=true
status=running

queue:
status=OK
queue_size=0

state:
current_status=OK
```

Collector restart 이후 일부 metric:

```text
in_flight=N/A
sent_spans=N/A
accepted_spans=N/A
refused_spans=N/A
```

은 새로운 Collector process에서 아직 해당 metric series가 생성되지 않은 상태로 해석한다.

Checker는 missing metric을 0으로 변환하지 않고 `N/A`로 표시하도록 설계되어 있으므로 정상 동작이다.

### 확인된 운영 특성

이번 실험으로 다음을 실제 검증했다.

```text
notification failure가 evaluator를 중단시키지 않음
pending event가 notification 실패 중 유지됨
동일 UNKNOWN 반복으로 pending ALERT가 증가하지 않음
transport 복구 후 timer가 pending event를 자동 retry
ALERT 전달 후 RECOVERY가 순서대로 전달됨
normal state log spam 억제
runtime-only systemd 테스트 후 production configuration 복구
```

### 남은 기술 부채

Evaluator outbox writer는 현재:

```text
file fsync
→ rename
```

까지 수행하지만 rename 이후 outbox directory fsync는 수행하지 않는다.

따라서 다음 경계는 아직 검증되지 않았다.

```text
호스트 abrupt power loss
rename 직후 filesystem metadata durability
disk full
filesystem corruption
```

또한 evaluator state의:

```text
last_notification_at
last_notification_epoch
```

는 실제 transport delivery 완료 시간이 아니라 alert-required event를 생성하고 handoff한 시간에 가깝다.

실제 외부 notification transport를 production에 연결하기 전에 이 의미를 명확하게 분리해야 한다.

---

## Evaluator Notification State 의미 정리 및 Production Migration

### 목표

외부 notification transport를 연결하기 전에 evaluator state의 `last_notification_*` 필드가 실제 delivery 완료 시각처럼 오해될 수 있는 문제를 수정했다.

기존:

```text
last_notification_at
last_notification_epoch
```

변경:

```text
last_alert_event_at
last_alert_event_epoch
```

### Migration 전략

Python 내부 identifier와 persisted JSON key migration을 분리했다.

새 persisted key를 우선 읽고 값이 없으면 기존 legacy key를 fallback한다.

```text
last_alert_event_epoch
→ 없으면 last_notification_epoch

last_alert_event_at
→ 없으면 last_notification_at
```

State를 다시 저장할 때 legacy key를 제거하고 새 key로 보존한다.

### 첫 Patch 실패

최초 자동 patch는:

```text
legacy migration insertion point was not found
```

에서 종료됐다.

`path.write_text()` 전에 종료됐기 때문에 실제 source file과 production state에는 부분 변경이 남지 않았다.

확인:

```text
git diff 없음
production state legacy key 유지
evaluator 정상 실행
```

따라서 rollback이나 state 복구 없이 patch를 다시 적용할 수 있었다.

### Legacy State Migration

Legacy fixture에:

```text
last_notification_at
last_notification_epoch
```

를 저장한 뒤 evaluator를 실행했다.

결과:

```text
migration_rc=0
legacy_state_migration=PASS
```

변환된 state:

```text
last_alert_event_at 존재
last_alert_event_epoch 존재
last_notification_at 없음
last_notification_epoch 없음
```

### Reminder Suppression 회귀 테스트

Fake checker:

```text
status=WARNING
exit=1
```

Legacy WARNING state에 현재 시각의:

```text
last_notification_epoch
```

를 저장하고 repeat interval을 300초로 실행했다.

결과:

```text
event=NONE
alert_required=false
previous_status=WARNING
current_status=WARNING

legacy_repeat_suppression=PASS
reminder_state_migration=PASS
```

따라서 migration 때문에 동일 WARNING에 대한 REMINDER가 즉시 재발생하지 않았다.

### Production Migration

Migration 전 production state:

```text
current_status=OK

last_notification_at
= 2026-08-18T06:45:59+00:00

last_notification_epoch
= 1787035559.0960143
```

Collector 상태:

```text
status=OK
queue_size=0
```

Production evaluator를 한 번 실행해 state를 migration했다.

결과:

```text
production_migration_rc=0
production_state_migration=PASS
```

Migration 후:

```text
last_alert_event_at
= 2026-08-18T06:45:59+00:00

last_alert_event_epoch
= 1787035559.0960143
```

으로 기존 timestamp가 그대로 유지됐다.

Legacy key는 제거됐다.

### Runtime 검증

Evaluator timer를 다시 시작했다.

```text
evaluator_timer_enabled=enabled
evaluator_timer_active=active
```

반복 실행 후 state:

```text
current_status=OK
last_alert_event_at 존재
last_alert_event_epoch 존재
last_notification_* 없음
```

Collector:

```text
status=OK
queue_size=0
```

으로 정상 상태를 유지했다.

### 결과

Evaluator의 상태 의미가 다음과 같이 명확해졌다.

```text
last_alert_event_*
= evaluator가 notification-required event를 발생시킨 시각
```

실제 외부 transport delivery 성공 시각은 이후 notification adapter의 별도 receipt state로 관리한다.

---

## Notification Delivery Receipt 구현 및 Crash-window 검증

### 변경 파일

```text
scripts/process-notification-outbox.py
```

### 목표

Notification event 발생 시각과 실제 transport 성공 시각을 별도의 데이터로 관리한다.

기존 event:

```text
evaluated_at
```

에 더해 adapter 성공 시점에:

```text
delivered_at
```

을 가진 receipt를 생성한다.

### Receipt Contract

Receipt schema:

```text
receipt_schema_version=1
event_id
transport
delivered_at
event
```

현재 테스트 transport:

```text
transport=local-file
```

실제 검증:

```text
event_id=1787037470416077807-1565940
evaluated_at=2026-08-18T07:17:50+00:00
delivered_at=2026-08-18T07:17:57+00:00

delivery_receipt_contract=PASS
```

`receipt.event_id`와 원본 `event.event_id`가 동일함을 확인했다.

### Receipt Directory CLI

새 canonical option:

```text
--receipt-dir
```

기존 option:

```text
--delivered-dir
```

은 compatibility alias로 유지한다.

검증:

```text
legacy_alias_rc=0
legacy_alias_bytes=0
```

### ACK_EXISTING

Receipt가 이미 존재하는 동일 event를 pending에 다시 생성해 delivery 성공 후 ACK 전에 process가 종료된 crash window를 재현했다.

재처리 결과:

```text
delivery_result=ACK_EXISTING
event_id=1787037470416077807-1565940
event=ALERT

adapter_status=OK
processed_events=1
remaining_events=0

ack_existing_rc=0
```

파일 상태:

```text
pending_before_ack_existing=1
pending_after_ack_existing=0
receipt_after_ack_existing=1
```

### Delivery Timestamp 보존

기존 receipt:

```text
delivered_at_before
= 2026-08-18T07:17:57+00:00
```

ACK_EXISTING 이후:

```text
delivered_at_after
= 2026-08-18T07:17:57+00:00
```

검증:

```text
delivery_timestamp_preserved=PASS
```

따라서 동일 event retry가 최초 delivery 성공 시각을 변경하지 않는다.

### Receipt Identity

최종 receipt:

```text
receipt_schema_version=1
transport=local-file
event=ALERT
event_id=1787037470416077807-1565940
evaluated_at=2026-08-18T07:17:50+00:00
delivered_at=2026-08-18T07:17:57+00:00
```

검증:

```text
receipt_identity=PASS
```

### Receipt 저장 실패

Receipt directory 위치를 일반 파일로 막아 저장 실패를 재현했다.

결과:

```text
adapter_error=delivery failed: [Errno 17] File exists: ...
receipt_failure_rc=2
pending_after_receipt_failure=1
```

즉 receipt가 영속화되지 않은 notification event는 pending outbox에서 제거하지 않는다.

### 현재 보장 범위

현재 구현은 local-file transport에서 다음을 검증했다.

```text
event 발생 시각과 delivery 성공 시각 분리
receipt identity 보존
receipt 저장 후 pending ACK
crash window의 ACK_EXISTING
최초 delivered_at 보존
receipt 실패 시 pending 유지
기존 --delivered-dir CLI 호환
```

아직 HTTP transport의 다음 특성은 검증하지 않았다.

```text
2xx 성공 기준
4xx 처리 정책
5xx retry
connection refused
timeout
response body 제한
ambiguous timeout
외부 provider duplicate delivery
```

다음 단계에서 Generic Webhook transport를 local fake HTTP server로 검증한다.

---

## Generic Webhook Transport 구현 및 HTTP Failure Semantics 검증

### 변경 파일

```text
scripts/process-notification-outbox.py
```

### Transport 경계

Notification adapter에:

```text
--transport local-file|webhook
```

을 추가했다.

기본 transport:

```text
local-file
```

로 기존 동작을 유지했다.

Webhook configuration:

```text
--webhook-url
AEROTRACE_WEBHOOK_URL
--webhook-timeout-sec
```

을 지원한다.

초기 timeout default:

```text
5 seconds
```

이며 실제 운영 최적값으로 확정한 수치는 아니다.

### Configuration Validation

다음 오류를 실제 검증했다.

```text
local-file + --webhook-url
→ rc=4

webhook + URL 없음
→ rc=4

ftp scheme
→ rc=4

URL userinfo credential
→ rc=4

webhook timeout <= 0
→ rc=4
```

기존:

```text
--delivered-dir
```

은 `--receipt-dir` alias로 계속 동작한다.

```text
legacy_alias_rc=0
legacy_alias_bytes=0
```

### 기존 Local-file 회귀

Transport dispatch 추가 후 기존 기본 실행을 다시 검증했다.

```text
delivery_result=DELIVERED
local_default_rc=0
default_local_transport=PASS
```

Webhook 구현 이후에도 다시:

```text
local_regression_rc=0
local_transport_regression=PASS
```

를 확인했다.

### Webhook 2xx Success Path

Local fake HTTP server를 임의 localhost port에서 실행하고 실제 POST를 전송했다.

Server 응답:

```text
HTTP 204
```

Adapter 결과:

```text
delivery_result=DELIVERED
event=ALERT
adapter_status=OK
processed_events=1
remaining_events=0

webhook_204_rc=0
```

Outbox:

```text
pending_before_webhook=1
pending_after_webhook=0
```

Receipt:

```text
webhook_receipt_count=1
transport=webhook
```

### 실제 Request Contract

Fake HTTP server에서 실제 request를 capture했다.

```text
method=POST
path=/aerotrace
content_type=application/json
accept=application/json
user_agent=AeroTrace-Notification/1
```

Header:

```text
X-AeroTrace-Event-Id
= 1787039514075281594-1612606
```

Body:

```text
event_id
= 1787039514075281594-1612606

event=ALERT
current_status=WARNING
alert_required=true
```

검증:

```text
webhook_request_contract=PASS
```

### Webhook Receipt

실제 값:

```text
evaluated_at=2026-08-18T07:51:54+00:00
delivered_at=2026-08-18T07:52:02+00:00
```

검증:

```text
webhook_receipt_contract=PASS
```

### Failure Class

추가한 실패 타입:

```text
WebhookRetryableError
WebhookPermanentError
```

Status classification:

```text
408
429
500~599
→ WebhookRetryableError

3xx
기타 4xx
→ WebhookPermanentError
```

Exit code:

```text
retryable = 2
permanent = 5
```

두 경우 모두 pending event를 삭제하지 않는다.

### HTTP 400

결과:

```text
prepared_case=400 pending=1

adapter_error=permanent delivery failure:
webhook returned permanent HTTP 400

http_400_rc=5
http_400_pending=1
http_400_receipts=0
```

### HTTP 408

결과:

```text
http_408_pending_before=1

adapter_error=retryable delivery failure:
webhook returned retryable HTTP 408

http_408_rc=2
http_408_pending_after=1
http_408_receipts=0

http_408_server_stopped=PASS
```

### HTTP 429

결과:

```text
prepared_case=429 pending=1

adapter_error=retryable delivery failure:
webhook returned retryable HTTP 429

http_429_rc=2
http_429_pending=1
```

### HTTP 500

결과:

```text
prepared_case=500 pending=1

adapter_error=retryable delivery failure:
webhook returned retryable HTTP 500

http_500_rc=2
http_500_pending=1
```

### Redirect

Fake server:

```text
/redirect
→ HTTP 302
→ Location: /redirect-target
```

결과:

```text
adapter_error=permanent delivery failure:
webhook returned permanent HTTP 302

redirect_rc=5
redirect_requests_added=1
redirect_target_requests_added=0
redirect_pending=1
```

따라서 urllib의 기본 redirect 동작을 사용하지 않고 redirect를 실제로 차단했다.

### Connection Refused

사용하지 않는 localhost port에 POST했다.

결과:

```text
adapter_error=retryable delivery failure:
webhook request failed: [Errno 111] Connection refused

connection_refused_rc=2
connection_refused_pending=1
```

### Ambiguous Timeout

Fake server 동작:

```text
request body 수신 및 기록
→ 1초 sleep
→ HTTP 204
```

Adapter timeout:

```text
0.2 seconds
```

첫 시도:

```text
adapter_error=retryable delivery failure:
webhook request timed out

timeout_rc=2
timeout_server_received=1
timeout_pending=1
timeout_receipts=0
```

Receiver는 request를 수신했지만 AeroTrace에는 성공 receipt가 없다.

같은 pending을 다시 처리했다.

```text
timeout_retry_rc=2
timeout_second_request_received=1
```

두 request의 event ID:

```text
1787039776322030495-1618606
```

으로 동일했다.

검증:

```text
timeout_duplicate_identity=PASS
```

### 확인된 Delivery 특성

이번 실험으로 다음을 실제 확인했다.

```text
HTTP 성공 시에만 receipt 생성
HTTP 성공 후에만 pending ACK

permanent failure에서도 pending 보존
retryable failure에서도 pending 보존

redirect 자동 추적 안 함
connection failure retry 가능

timeout 후 receiver 수신 여부가 ambiguous할 수 있음
동일 event_id로 retry되어 duplicate delivery가 발생할 수 있음
```

따라서 Generic Webhook notification을 exactly-once라고 표현하지 않는다.

### 현재 운영 위험

현재 retry 자체는 systemd timer의 반복 실행에 의존한다.

아직:

```text
per-event exponential backoff
Retry-After 지원
retry 횟수
first failure timestamp
outbox oldest age
dead-letter queue
outbox growth limit
```

을 구현하지 않았다.

Webhook endpoint가 장기간 실패하면 pending outbox가 증가할 수 있으므로 실제 production webhook 활성화 전에 이 부분을 운영 관점에서 추가 검토해야 한다.

---

### Portfolio Checkpoint — Generic Webhook Delivery와 Ambiguous Timeout 검증

Collector queue alert를 실제 HTTP webhook으로 전달할 수 있는 notification transport를 구현하고, 성공뿐 아니라 HTTP 및 network failure semantics를 local fake server로 직접 검증했다.

### 구현한 구조

```text
Collector queue state
→ evaluator
→ JSON outbox
→ notification adapter
→ HTTP POST
→ webhook receiver
→ delivery receipt
→ pending ACK
```

### HTTP Success Path

HTTP 204 응답에서:

```text
pending 1 → 0
webhook receipt 0 → 1
adapter rc=0
```

을 확인했다.

실제 request:

```text
POST /aerotrace
Content-Type: application/json
X-AeroTrace-Event-Id: <event_id>
```

Header event ID와 body event ID가 동일함을 검증했다.

```text
webhook_request_contract=PASS
webhook_receipt_contract=PASS
```

### Failure Classification

실제 HTTP server를 이용해 다음 결과를 검증했다.

```text
400 → permanent → rc=5
408 → retryable → rc=2
429 → retryable → rc=2
500 → retryable → rc=2
302 → permanent → rc=5
connection refused → retryable → rc=2
timeout → retryable → rc=2
```

모든 failure에서 pending event가 유지됐다.

### Redirect Security

HTTP 302에 Location header를 설정했다.

검증:

```text
redirect_requests_added=1
redirect_target_requests_added=0
```

으로 redirect target에 payload가 전달되지 않았음을 확인했다.

### Ambiguous Timeout 실험

Receiver가 request를 기록한 후 응답을 늦게 보내도록 만들고 AeroTrace timeout을 더 짧게 설정했다.

결과:

```text
timeout_server_received=1
timeout_rc=2
timeout_pending=1
timeout_receipts=0
```

즉 receiver는 request를 실제로 받았지만 sender는 성공 여부를 확인할 수 없는 상태를 재현했다.

같은 pending을 재시도하자:

```text
timeout_second_request_received=1
```

이 추가됐고 두 request의 event ID가 동일했다.

```text
timeout_duplicate_identity=PASS
```

이를 통해 HTTP notification에서 exactly-once delivery를 단순히 구현할 수 없고, receiver-side idempotency가 왜 필요한지 실제 코드와 네트워크 실험으로 확인했다.

### 직접 얻은 실무 경험

이번 단계에서 다음 운영 개념을 실제로 다뤘다.

```text
HTTP transport failure classification
retryable vs permanent failure
at-least-once 성격의 retry
ambiguous timeout
idempotency key
receiver deduplication
delivery receipt
redirect security
outbox ordering
failure 시 event 보존
```

### 보존할 로그와 증거

```text
webhook_204_rc=0
webhook_request_contract=PASS
webhook_receipt_contract=PASS

http_400_rc=5
http_400_pending=1
http_400_receipts=0

http_408_rc=2
http_408_pending_after=1
http_408_receipts=0

http_429_rc=2
http_429_pending=1

http_500_rc=2
http_500_pending=1

redirect_rc=5
redirect_requests_added=1
redirect_target_requests_added=0
redirect_pending=1

connection_refused_rc=2
connection_refused_pending=1

timeout_rc=2
timeout_server_received=1
timeout_pending=1
timeout_receipts=0

timeout_retry_rc=2
timeout_second_request_received=1
timeout_duplicate_identity=PASS
```

특히 timeout experiment의 log와 event ID 비교 결과는 포트폴리오와 기술 블로그에 보존할 가치가 높다.

### 이력서 성과 문장 초안

- Collector 장애 알림을 JSON outbox 기반 Generic Webhook으로 전달하는 경로를 구현하고 HTTP `2xx/400/408/429/5xx`, redirect, connection failure, timeout 시나리오를 실제 local HTTP server로 검증하여 실패 유형별 pending 보존 및 retry semantics를 설계
- HTTP timeout에서 receiver가 요청을 수신했지만 sender가 성공 여부를 확인하지 못하는 ambiguous delivery를 재현하고 동일 `event_id` 기반 재전송을 검증해 exactly-once 한계를 확인하고 receiver-side idempotency를 고려한 notification 구조를 구축

실제 외부 provider 또는 production systemd 배포까지 완료된 것처럼 표현하지 않는다.

### 예상 면접 질문

```text
왜 HTTP 400과 500의 exit code를 다르게 했는가?

왜 permanent error에서도 pending을 삭제하지 않는가?

왜 HTTP 302 redirect를 따라가지 않는가?

timeout인데 receiver가 실제 request를 받은 것을 어떻게 증명했는가?

동일 notification이 두 번 전달될 수 있는데 어떻게 대응할 것인가?

event_id를 HTTP header에도 넣은 이유는 무엇인가?

delivery receipt는 어떤 시점에 만들어야 하는가?

receipt를 먼저 만들고 pending을 삭제하는 이유는 무엇인가?

왜 exactly-once라고 표현하면 안 되는가?

429 Retry-After는 현재 어떻게 처리하며 이후 어떻게 개선할 것인가?
```

### 블로그 주제

**제목**

```text
Webhook 알림은 왜 Exactly-Once가 아닌가:
Timeout과 중복 전송을 직접 재현해 본 과정
```

**핵심 흐름**

```text
1. 단순 POST 구현
2. Outbox와 receipt 설계
3. 2xx 성공 기준
4. 400 / 429 / 500 분류
5. redirect 차단
6. timeout 실험
7. receiver는 받았지만 sender는 모르는 상태
8. 동일 event_id retry
9. receiver-side idempotency 필요성
10. 실제 운영에서 남은 retry/backoff 문제
```

### 다음 한 단계 높은 과제

Webhook 전송 자체보다 다음 운영 문제를 해결하는 것이 중요하다.

```text
notification endpoint 장기 장애
→ pending outbox 증가
→ 얼마나 오래 실패 중인지 알 수 없음
→ systemd가 계속 동일 event를 retry
```

따라서 다음 단계에서는 retry/backoff를 바로 복잡하게 구현하기 전에 먼저:

```text
pending event 수
oldest pending age
실패 지속 시간
최근 failure type
```

을 운영자가 관찰할 수 있도록 만들고, 그 측정 결과를 바탕으로 backoff / retry budget / dead-letter 정책 도입 여부를 결정한다.

---

## Notification Outbox 적체 관측 및 실제 Age 상태 전이 검증

### 구현 파일

```text
scripts/check-notification-outbox.py
```

### 목표

Webhook notification 전달 실패가 장기화될 때 단순히 pending 파일이 존재하는지 확인하는 것을 넘어 다음 상태를 운영자가 직접 확인할 수 있도록 했다.

```text
pending event 수
pending 전체 bytes
가장 오래된 pending event age
가장 오래된 event ID
가장 오래된 event evaluated_at
```

### 제공하는 관측값

Checker는 다음 값을 출력한다.

```text
status
pending_events
pending_bytes
oldest_pending_age_sec
oldest_event_id
oldest_evaluated_at
count_threshold_status
age_threshold_status
```

상태별 exit code:

```text
OK       = 0
WARNING  = 1
CRITICAL = 2
UNKNOWN  = 3
```

### Empty Outbox 검증

존재하지 않는 outbox directory는 pending event가 없는 정상 상태로 처리했다.

결과:

```text
status=OK
pending_events=0
pending_bytes=0
oldest_pending_age_sec=N/A
oldest_event_id=N/A
oldest_evaluated_at=N/A
missing_outbox_rc=0
```

### 실제 Pending 파일 관측

테스트 event 두 개를 생성해 관측값을 검증했다.

실제 결과:

```text
pending_events=2
pending_bytes=585
oldest_pending_age_sec=94.694
oldest_event_id=v7b41-oldest
```

자동 검증:

```text
outbox_observability=PASS
```

### Pending Age 계산 기준

파일의 mtime 대신 event JSON의 `evaluated_at`을 사용한다.

계산:

```text
현재 시각 - evaluated_at
```

파일이 이동되거나 복구되면서 mtime이 변경되더라도 실제 notification event가 발생한 시각을 유지하기 위한 선택이다.

### Corruption Detection

손상된 pending JSON을 추가했다.

```text
{broken json
```

결과:

```text
status=UNKNOWN
pending_events=3
checker_error=invalid pending event: ...
broken_event_rc=3
```

따라서 notification outbox 내부 데이터가 손상된 상태를 정상으로 숨기지 않는다.

### 잘못된 Outbox Path

Outbox path에 directory 대신 일반 파일을 배치했다.

결과:

```text
status=UNKNOWN
checker_error=outbox path is not a directory: ...
outbox_path_error_rc=3
```

### Configurable Threshold 구현

추가한 옵션:

```text
--warn-count
--critical-count
--warn-age-sec
--critical-age-sec
```

Threshold가 없을 경우 관측 전용 동작을 유지한다.

실제 pending 두 개가 있어도:

```text
status=OK
count_threshold_status=OK
age_threshold_status=OK
default_threshold_rc=0
```

으로 동작했다.

### Count WARNING 검증

조건:

```text
pending_events=2
warn-count=2
critical-count=3
```

결과:

```text
status=WARNING
count_threshold_status=WARNING
age_threshold_status=OK
count_warning_rc=1
```

### Count CRITICAL 검증

조건:

```text
pending_events=2
warn-count=1
critical-count=2
```

결과:

```text
status=CRITICAL
count_threshold_status=CRITICAL
age_threshold_status=OK
count_critical_rc=2
```

### Age WARNING 검증

약 120초 된 test event 하나를 사용했다.

조건:

```text
warn-age-sec=60
critical-age-sec=3600
```

결과:

```text
status=WARNING
pending_events=1
count_threshold_status=OK
age_threshold_status=WARNING
age_warning_rc=1
```

### Age CRITICAL 검증

동일 event에서 다음 threshold를 사용했다.

```text
warn-age-sec=60
critical-age-sec=90
```

결과:

```text
status=CRITICAL
count_threshold_status=OK
age_threshold_status=CRITICAL
age_critical_rc=2
```

### Combined Severity 검증

Count와 age가 서로 다른 상태일 때 더 높은 severity를 전체 상태로 사용한다.

실제 조건:

```text
count_threshold_status=WARNING
age_threshold_status=CRITICAL
```

결과:

```text
status=CRITICAL
combined_status_rc=2
```

### Threshold Configuration Error 검증

다음 잘못된 설정을 테스트했다.

```text
warn-count >= critical-count
warn-age-sec >= critical-age-sec
threshold <= 0
```

결과는 모두:

```text
status=UNKNOWN
exit code=3
```

이었다.

실제 결과 예:

```text
checker_error=--warn-count must be lower than --critical-count
bad_count_threshold_rc=3
```

```text
checker_error=--warn-age-sec must be lower than --critical-age-sec
bad_age_threshold_rc=3
```

```text
checker_error=--warn-age-sec must be > 0
zero_threshold_rc=3
```

### Threshold 추가 후 Corruption Detection 회귀 검증

Threshold 기능을 추가한 뒤에도 손상 JSON 검출이 유지되는지 다시 검증했다.

결과:

```text
status=UNKNOWN
pending_events=1
checker_error=invalid pending event: ...
broken_regression_rc=3
```

### 실제 Notification Failure 기반 상태 전이 검증

Synthetic timestamp가 아니라 실제 webhook 전달 실패로 pending event를 생성했다.

Fake checker 결과:

```text
status=WARNING
queue_size=1000
```

Evaluator:

```text
event=ALERT
alert_required=true
previous_status=NONE
current_status=WARNING
checker_exit_code=1
evaluator_rc=0
```

실제 사용하지 않는 localhost port에 webhook을 전송했다.

```text
http://127.0.0.1:1/aerotrace
```

결과:

```text
adapter_error=retryable delivery failure:
webhook request failed: [Errno 111] Connection refused

delivery_failure_rc=2
pending_after_failure=1
receipts_after_failure=0
```

생성된 실제 pending event:

```text
event_id=1787182663140772001-653546
```

### 첫 번째 상태 전이 테스트에서 발견한 테스트 설계 문제

초기 실험에서는 baseline age가:

```text
23.407s
```

이었다.

WARNING threshold를:

```text
33.407s
```

로 설정했다.

하지만 사람이 다음 명령을 입력하는 동안 실제 시간이 흘렀고 최초 threshold 검사 시점에는:

```text
33.408s
```

가 됐다.

따라서 결과는:

```text
status=WARNING
```

이었다.

이는 checker 오류가 아니라 다음 비교가 정확하게 동작한 결과였다.

```text
33.408 >= 33.407
```

이후 WARNING 확인을 위해 추가 대기했을 때 age가:

```text
50.324s
```

까지 증가해 이미 critical threshold:

```text
43.407s
```

도 넘었기 때문에 CRITICAL로 전이했다.

### 상태 전이 테스트 개선

사람의 명령 입력 시간을 테스트 결과에서 제거하기 위해 fresh pending event를 다시 생성하고 하나의 연속 shell 실행 안에서 다음 과정을 수행했다.

```text
baseline 측정
→ threshold 계산
→ 즉시 최초 검사
→ sleep
→ WARNING 검사
→ sleep
→ CRITICAL 검사
```

테스트용 threshold:

```text
WARNING  = baseline + 10초
CRITICAL = baseline + 25초
```

이 값은 production threshold가 아니다.

### 실제 Age 상태 전이 결과

Baseline:

```text
baseline_age=5.792
warn_age=15.792
critical_age=30.792
```

초기 검사:

```text
status=OK
pending_events=1
oldest_pending_age_sec=5.853
oldest_event_id=1787182663140772001-653546
age_threshold_status=OK
initial_rc=0
```

시간 경과 후:

```text
status=WARNING
pending_events=1
oldest_pending_age_sec=17.889
oldest_event_id=1787182663140772001-653546
age_threshold_status=WARNING
warning_rc=1
```

추가 시간 경과 후:

```text
status=CRITICAL
pending_events=1
oldest_pending_age_sec=31.927
oldest_event_id=1787182663140772001-653546
age_threshold_status=CRITICAL
critical_rc=2
```

자동 검증:

```text
initial_age=5.853
warning_age=17.889
critical_age=31.927
event_id=1787182663140772001-653546
actual_pending_age_transition=PASS
```

세 시점 모두 동일 `event_id`가 유지됐다.

최종:

```text
final_pending=1
final_receipts=0
pending_event_identity_preserved=PASS
```

### 확인된 동작

이번 단계에서 다음을 실제로 확인했다.

```text
실제 webhook failure
→ pending event 보존

시간 경과
→ 동일 event의 oldest age 증가

age가 warning threshold 도달
→ WARNING / rc=1

age가 critical threshold 도달
→ CRITICAL / rc=2

delivery 성공 전까지
→ receipt 없음
→ 동일 pending event 유지
```

### 현재 한계

현재 production WARNING/CRITICAL 숫자는 확정하지 않았다.

테스트에서 사용한 다음 숫자는 상태 전이를 검증하기 위한 값일 뿐이다.

```text
count 1 / 2 / 3
age 10초 / 25초 / 60초 / 90초
```

실제 운영 threshold는 notification 발생 빈도, systemd retry 주기, 허용 가능한 전달 지연, 장기 장애 시 outbox 증가량을 추가 측정한 뒤 결정해야 한다.

또한 oldest pending age는 미전송 event의 나이를 보여주지만 transport 자체의 최초 실패 시각이나 연속 실패 횟수를 직접 나타내지는 않는다.

다음 단계에서는 persistent transport failure state를 추가하는 방향을 검토한다.

---

## Webhook Persistent Failure State 및 성공 Finalization 복구 검증

### 변경 파일

```text
scripts/process-notification-outbox.py
```

### 추가 기능

Notification webhook adapter에 optional persistent failure state를 추가했다.

CLI:

```text
--failure-state-file <path>
```

현재는:

```text
--transport webhook
```

에서만 사용할 수 있다.

### Failure State 필드

```text
failure_state_schema_version
transport
failed_event_id
failure_kind
failure_reason
first_failed_at
last_failed_at
failure_count
```

Schema:

```text
failure_state_schema_version=1
```

### Structured Exception

기존:

```text
WebhookRetryableError
WebhookPermanentError
```

에 공통 base를 추가했다.

```text
WebhookDeliveryError
```

각 delivery error는 구조화된:

```text
failure_kind
failure_reason
```

을 가진다.

따라서 persistent state가 exception message parsing에 의존하지 않는다.

### Failure Reason 예

```text
connection_error
timeout
http_400
http_408
http_429
http_500
```

### Local-file Configuration Validation

다음 실행을 차단했다.

```text
--transport local-file
--failure-state-file ...
```

결과:

```text
adapter_error=--failure-state-file requires --transport webhook
local_failure_state_rc=4
```

### Retryable Failure 첫 시도

Connection refused를 실제 발생시켰다.

```text
http://127.0.0.1:1/aerotrace
```

결과:

```text
adapter_error=retryable delivery failure:
webhook request failed: [Errno 111] Connection refused

failure_state_file=/tmp/aerotrace-v7b43-failure-state.json
failure_count=1
failure_kind=retryable
failure_reason=connection_error
failed_event_id=1787185530153348081-718365
remaining_events=1
failure1_rc=2
```

실제 state:

```json
{
  "failure_state_schema_version": 1,
  "transport": "webhook",
  "failed_event_id": "1787185530153348081-718365",
  "failure_kind": "retryable",
  "failure_reason": "connection_error",
  "first_failed_at": "2026-08-20T00:25:34+00:00",
  "last_failed_at": "2026-08-20T00:25:34+00:00",
  "failure_count": 1
}
```

검증:

```text
failure_state_first_attempt=PASS
```

### Retryable Failure 두 번째 시도

동일 pending event를 다시 전송했다.

결과:

```text
failure_count=2
failure_kind=retryable
failure_reason=connection_error
failure2_rc=2
```

State:

```text
first_failed_at=2026-08-20T00:25:34+00:00
last_failed_at=2026-08-20T00:25:49+00:00
failure_count=2
```

검증:

```text
failure_state_second_attempt=PASS
```

따라서:

```text
first_failed_at 유지
last_failed_at 갱신
failure_count 1 → 2
```

를 실제 확인했다.

### 최초 Recovery 검증

Local HTTP server가 204를 반환하도록 구성했다.

동일 pending event를 실제 성공시켰다.

결과:

```text
failure_state_cleared=true
delivery_result=DELIVERED
event_id=1787185530153348081-718365
event=ALERT
adapter_status=OK
processed_events=1
remaining_events=0
recovery_rc=0
```

최종:

```text
failure_state_removed=PASS
pending_after_recovery=0
receipts_after_recovery=1
```

### 성공 Finalization 순서에서 발견한 문제

초기 구현에서는 successful delivery 함수가:

```text
receipt 생성
→ pending 삭제
→ return
```

을 수행한 뒤 `main()`에서 failure state를 삭제했다.

이 구조에서는:

```text
receipt 저장 성공
pending 삭제 성공
failure state 삭제 실패
```

시 stale failure state만 남고 pending event가 없어 다음 run에서 자동 복구가 불가능할 수 있었다.

이 문제는 recovery test 이후 코드 검토에서 발견했다.

### Finalization 순서 수정

Webhook 성공 경로를 다음 순서로 변경했다.

```text
HTTP 성공
→ receipt durable 저장
→ failure state clear
→ pending unlink
→ outbox directory fsync
```

`ACK_EXISTING` 경로도 동일하게:

```text
기존 receipt 검증
→ failure state clear
→ pending unlink
```

순서로 변경했다.

### Failure State Clear Failure 실험

Fresh retryable failure state를 만든 뒤 state directory permission을:

```text
dr-x------
```

로 변경했다.

실제:

```text
chmod 500 /tmp/aerotrace-v7b43c-state
```

HTTP server는 204를 반환했다.

Failure state 삭제 단계에서 실제 permission error가 발생했다.

결과:

```text
adapter_error=failure state clear failed before pending ACK:
[Errno 13] Permission denied:
'/tmp/aerotrace-v7b43c-state/failure.json'

failed_event_id=1787185690763474192-722007
remaining_events=1
clear_failure_rc=4
```

중요한 filesystem 상태:

```text
pending_after_clear_failure=1
receipts_after_clear_failure=1
failure_state_after_clear_failure=1
```

즉 external delivery는 성공했으므로 receipt가 존재하지만 internal finalization이 끝나지 않아 pending을 유지했다.

### ACK_EXISTING Recovery

204 server를 완전히 종료한 뒤 state directory permission을 정상화했다.

이 상태에서 adapter를 다시 실행했다.

서버가 종료됐기 때문에 HTTP POST를 다시 실행했다면 connection refused가 발생해야 했다.

실제 결과:

```text
failure_state_cleared=true
delivery_result=ACK_EXISTING
event_id=1787185690763474192-722007
event=ALERT
adapter_status=OK
processed_events=1
remaining_events=0
ack_existing_rc=0
```

Connection refused는 발생하지 않았다.

최종:

```text
final_pending=0
final_receipts=1
final_failure_state=0
```

따라서 receipt를 이용해 duplicate network delivery 없이 incomplete finalization을 복구할 수 있음을 확인했다.

### HTTP 400 Persistent State

Local HTTP server가 400을 반환하도록 구성했다.

결과:

```text
adapter_error=permanent delivery failure:
webhook returned permanent HTTP 400

failure_state_file=/tmp/aerotrace-v7b43d-failure.json
failure_count=1
failure_kind=permanent
failure_reason=http_400
failed_event_id=1787207361956265193-1211675
remaining_events=1
http_400_failure_rc=5
```

실제 state:

```json
{
  "failure_state_schema_version": 1,
  "transport": "webhook",
  "failed_event_id": "1787207361956265193-1211675",
  "failure_kind": "permanent",
  "failure_reason": "http_400",
  "first_failed_at": "2026-08-20T06:29:32+00:00",
  "last_failed_at": "2026-08-20T06:29:32+00:00",
  "failure_count": 1
}
```

자동 검증:

```text
permanent_failure_state=PASS
```

### 손상 Failure State 검증

다음 파일을 생성했다.

```text
{broken json
```

실행 전 hash:

```text
c3e7d1b00a65589b59f816c0b0b668d795a3c28123697d5ab9555bdb8aa04604
```

Adapter 실행:

```text
adapter_error=invalid failure state:
Expecting property name enclosed in double quotes:
line 1 column 2 (char 1)

corrupt_failure_state_rc=4
```

Connection refused URL을 지정했지만 failure state validation이 먼저 실패했으므로 network delivery는 수행되지 않았다.

실행 후 hash:

```text
c3e7d1b00a65589b59f816c0b0b668d795a3c28123697d5ab9555bdb8aa04604
```

검증:

```text
corrupt_state_preserved=PASS
```

최종:

```text
corrupt_pending_after=1
corrupt_receipts_after=0
```

손상된 state를 자동 초기화하거나 덮어쓰지 않고 운영 오류로 노출한다.

### 검증된 Failure State Semantics

```text
Retryable failure
→ state 기록
→ pending 유지

Repeated failure
→ failure_count 증가
→ first_failed_at 유지
→ last_failed_at 갱신

Permanent failure
→ permanent/http_xxx state 기록
→ pending 유지

Success
→ receipt 저장
→ failure state clear
→ pending ACK

State clear failure
→ receipt 유지
→ pending 유지
→ state 유지

다음 실행
→ receipt 확인
→ HTTP 재전송 없음
→ ACK_EXISTING
→ state clear
→ pending ACK

Corrupted state
→ network 전에 중단
→ state 보존
→ pending 보존
```

### 현재 기술 부채

현재 failure state는 최신 연속 장애 한 건만 나타낸다.

아직 다음은 없다.

```text
failure history
duration 계산 checker
failure count threshold
systemd 운영 경로
failure state metrics
retry backoff
Retry-After
dead-letter
```

이들은 실제 운영 요구와 장애 데이터를 기준으로 후속 적용한다.

---

## Persistent Failure Checker 및 반복 장애 지표 비교 검증

### 구현 파일

```text
scripts/check-notification-failure-state.py
```

### Checker 관측값

Persistent webhook failure state에서 다음 값을 출력하도록 구현했다.

```text
status
active_failure
failure_kind_status
count_threshold_status
duration_threshold_status
transport
failed_event_id
failure_kind
failure_reason
failure_count
failure_duration_sec
last_failure_age_sec
first_failed_at
last_failed_at
```

### Failure State 없음

State 파일이 존재하지 않을 경우 현재 transport 장애가 없는 것으로 처리했다.

```text
status=OK
active_failure=false
failure_count=0
failure_duration_sec=N/A
last_failure_age_sec=N/A
missing_failure_state_rc=0
```

### 실제 Connection Failure 관측

실제 connection refused를 발생시킨 뒤 state를 읽었다.

```text
failure_kind=retryable
failure_reason=connection_error
failure_count=1

failure_duration_sec=6.718
last_failure_age_sec=6.718
```

검증:

```text
persistent_failure_observability=PASS
```

### 동일 Event 두 번째 Failure

동일 pending event를 다시 전송해 실패시켰다.

```text
failure_count=2
failure_duration_sec=17.820
last_failure_age_sec=1.820
```

`first_failed_at`은 유지됐고 `last_failed_at`은 갱신됐다.

검증:

```text
persistent_failure_retry_observability=PASS
```

이를 통해 다음 두 값을 실제로 분리해 확인했다.

```text
failure_duration_sec
→ 최초 failure부터의 전체 장애 지속시간

last_failure_age_sec
→ 최근 failure 이후 경과시간
```

### Severity Threshold

Retryable failure에 optional threshold를 추가했다.

```text
--warn-count
--critical-count
--warn-duration-sec
--critical-duration-sec
```

Threshold가 없을 때:

```text
active_failure=true
failure_kind=retryable
failure_count=2

status=OK
default_retryable_rc=0
```

을 유지했다.

### Count Threshold 검증

```text
failure_count=2
warn-count=2
critical-count=3

→ WARNING
→ rc=1
```

```text
failure_count=2
warn-count=1
critical-count=2

→ CRITICAL
→ rc=2
```

### Duration Threshold 검증

실제 failure duration을 기준으로 테스트 threshold를 동적으로 계산했다.

WARNING:

```text
status=WARNING
duration_threshold_status=WARNING
failure_duration_warning_rc=1
```

CRITICAL:

```text
status=CRITICAL
duration_threshold_status=CRITICAL
failure_duration_critical_rc=2
```

테스트에 사용한 숫자는 production policy가 아니다.

### Permanent Failure 정책 검증

Valid permanent state fixture:

```text
failure_kind=permanent
failure_reason=http_400
failure_count=1
```

결과:

```text
status=CRITICAL
failure_kind_status=CRITICAL
count_threshold_status=OK
duration_threshold_status=OK
permanent_failure_checker_rc=2
```

따라서 permanent failure는 count와 duration에 관계없이 즉시 CRITICAL이 됐다.

### 잘못된 Threshold 검증

다음 설정은 모두 UNKNOWN으로 처리했다.

```text
warn-count >= critical-count
warn-duration-sec >= critical-duration-sec
threshold <= 0
```

exit code:

```text
3
```

State 파일이 존재하지 않는 경우에도 configuration validation을 먼저 수행했다.

```text
status=UNKNOWN
checker_error=--warn-count must be lower than --critical-count
bad_config_without_state_rc=3
```

따라서 state 부재가 잘못된 checker configuration을 숨기지 않는다.

### 손상 State 회귀 검증

손상 JSON에서:

```text
status=UNKNOWN
corrupt_threshold_regression_rc=3
```

을 유지했다.

### 실제 반복 Retryable Failure 측정

Fresh notification event를 생성하고 동일 event에 connection refused를 네 차례 발생시켰다.

Event:

```text
1787210007147775125-1271557
```

실제 측정:

```text
attempt  adapter_rc  failure_count  failure_duration_sec  last_failure_age_sec  oldest_pending_age_sec  pending_events
1        2           1              0.136                 0.136                 5.168                   1
2        2           2              4.285                 0.285                 9.318                   1
3        2           3              8.434                 0.434                13.467                   1
4        2           4             12.586                 0.586                17.618                   1
```

네 번 모두:

```text
failed_event_id
=
oldest_event_id
=
1787210007147775125-1271557
```

이었다.

검증:

```text
repeated_failure_measurement=PASS
```

Outbox age와 failure duration의 차이:

```text
backlog_minus_failure_min_sec=5.032
backlog_minus_failure_max_sec=5.033
```

### 반복 장애 종료 시점 최종 상태

추가 시간이 지난 뒤:

```text
active_failure=true
failure_count=4
failure_duration_sec=30.185
last_failure_age_sec=18.185
```

Outbox:

```text
pending_events=1
pending_bytes=341
oldest_pending_age_sec=35.218
```

최종:

```text
final_pending=1
final_receipts=0
```

아직 실제 notification delivery가 성공하지 않았으므로 정상적인 상태다.

### 측정으로 확인한 지표 특성

`failure_count`는 retry cadence에 종속된다.

이번 실험에서는 약 4초마다 retry했기 때문에 다음과 같이 증가했다.

```text
1 → 2 → 3 → 4
```

반면 `failure_duration_sec`는 retry 횟수와 관계없이 실제 최초 transport failure 이후 시간을 표현한다.

따라서 retryable failure의 production severity 주 기준 후보는 `failure_duration_sec`로 판단했다.

`failure_count`는 retry storm, retry budget, retry 동작 확인을 위한 보조 지표로 유지한다.

`last_failure_age_sec`는 retry 직후 다시 낮아지므로 현재 severity의 주 기준에는 사용하지 않는다.

향후 active failure 상태인데 `last_failure_age_sec`가 비정상적으로 증가하면 retry scheduler/worker 정지를 탐지하는 용도로 검토할 수 있다.

### 현재 남은 과제

아직 production duration threshold 숫자는 확정하지 않았다.

다음 근거가 필요하다.

```text
실제 systemd notification retry cadence
허용 가능한 alert 전달 지연
실제 webhook provider 장애 특성
운영자가 원하는 notification SLA
장기 장애 시 Outbox 증가량
```

실제 운영 근거를 확보하기 전에는 테스트 threshold를 production 값으로 사용하지 않는다.

---

## Persistent Failure State Checker 및 반복 Transport 장애 측정

### 구현 파일

```text
scripts/check-notification-failure-state.py
```

### 목표

Persistent webhook failure state를 사람이 JSON 파일을 직접 열지 않아도 운영 상태로 조회할 수 있도록 checker를 추가했다.

관측 항목:

```text
active_failure
failure_kind
failure_reason
failure_count
failure_duration_sec
last_failure_age_sec
first_failed_at
last_failed_at
```

이후 retryable failure에 count/duration threshold를 선택적으로 적용하고 permanent failure는 즉시 CRITICAL로 평가하도록 확장했다.

### State 없음

Failure state 파일이 없는 경우:

```text
status=OK
active_failure=false
transport=N/A
failed_event_id=N/A
failure_kind=N/A
failure_reason=N/A
failure_count=0
failure_duration_sec=N/A
last_failure_age_sec=N/A
first_failed_at=N/A
last_failed_at=N/A
missing_failure_state_rc=0
```

을 확인했다.

### 실제 Connection Failure 관측

Adapter를 실제 사용하지 않는 localhost port에 연결했다.

```text
http://127.0.0.1:1/aerotrace
```

결과:

```text
adapter_error=retryable delivery failure:
webhook request failed: [Errno 111] Connection refused

failure_count=1
failure_kind=retryable
failure_reason=connection_error
transport_failure_rc=2
```

2초 이상 경과 후 checker 결과:

```text
status=OK
active_failure=true
transport=webhook
failed_event_id=1787209586391601425-1262022
failure_kind=retryable
failure_reason=connection_error
failure_count=1
failure_duration_sec=6.718
last_failure_age_sec=6.718
first_failed_at=2026-08-20T07:06:26+00:00
last_failed_at=2026-08-20T07:06:26+00:00
failure_checker_rc=0
```

자동 검증:

```text
persistent_failure_observability=PASS
```

### 두 번째 실제 Failure

동일 pending event를 다시 connection refused 상태로 전송했다.

결과:

```text
failure_count=2
failure_duration_sec=17.820
last_failure_age_sec=1.820
first_failed_at=2026-08-20T07:06:26+00:00
last_failed_at=2026-08-20T07:06:42+00:00
second_transport_failure_rc=2
```

자동 검증:

```text
persistent_failure_retry_observability=PASS
```

확인한 관계:

```text
first_failed_at
→ 유지

last_failed_at
→ 갱신

failure_count
→ 1 → 2

failure_duration
→ 증가

last_failure_age
→ 최근 실패 직후 다시 작아짐
```

### 손상 State Checker

손상 JSON:

```text
{broken json
```

결과:

```text
status=UNKNOWN
checker_error=invalid failure state: ...
corrupt_failure_checker_rc=3
```

### Directory Path 오류

Failure state path로 directory를 전달했다.

결과:

```text
status=UNKNOWN
checker_error=failure state path is not a file: ...
failure_state_path_rc=3
```

### Severity Threshold 구현

추가한 CLI:

```text
--warn-count
--critical-count
--warn-duration-sec
--critical-duration-sec
```

Threshold를 설정하지 않으면 retryable active failure가 있어도 관측 전용 상태를 유지한다.

실제 값:

```text
failure_kind=retryable
failure_reason=connection_error
failure_count=2
failure_duration_sec=201.062
last_failure_age_sec=185.062
```

결과:

```text
failure_kind_status=OK
count_threshold_status=OK
duration_threshold_status=OK
status=OK
default_retryable_rc=0
```

### Count WARNING

조건:

```text
failure_count=2
warn-count=2
critical-count=3
```

결과:

```text
status=WARNING
count_threshold_status=WARNING
failure_count_warning_rc=1
```

### Count CRITICAL

조건:

```text
failure_count=2
warn-count=1
critical-count=2
```

결과:

```text
status=CRITICAL
count_threshold_status=CRITICAL
failure_count_critical_rc=2
```

### Duration WARNING

Baseline:

```text
current_failure_duration=213.903
```

현재 duration보다 낮은 warning threshold와 충분히 높은 critical threshold를 동적으로 설정했다.

실제 검사 시:

```text
failure_duration_sec=220.768
duration_threshold_status=WARNING
failure_duration_warning_rc=1
```

### Duration CRITICAL

현재 duration보다 낮은 critical threshold를 설정했다.

실제 검사 시:

```text
failure_duration_sec=229.207
duration_threshold_status=CRITICAL
failure_duration_critical_rc=2
```

### Permanent Failure 즉시 CRITICAL

Valid permanent failure fixture:

```text
failure_kind=permanent
failure_reason=http_400
failure_count=1
```

실제 검사:

```text
status=CRITICAL
failure_kind_status=CRITICAL
count_threshold_status=OK
duration_threshold_status=OK
failure_duration_sec=9.958
last_failure_age_sec=6.958
permanent_failure_checker_rc=2
```

Threshold가 없어도 permanent 자체로 CRITICAL이 됨을 확인했다.

### 잘못된 Threshold

Count:

```text
warn-count=10
critical-count=5
```

결과:

```text
status=UNKNOWN
checker_error=--warn-count must be lower than --critical-count
bad_failure_count_threshold_rc=3
```

Duration:

```text
warn-duration-sec=30
critical-duration-sec=10
```

결과:

```text
status=UNKNOWN
checker_error=--warn-duration-sec must be lower than --critical-duration-sec
bad_failure_duration_threshold_rc=3
```

0 threshold:

```text
warn-count=0
```

결과:

```text
status=UNKNOWN
checker_error=--warn-count must be > 0
zero_failure_threshold_rc=3
```

### State 없음 + 잘못된 Checker 설정

Failure state 파일이 없는 상태에서도 configuration 검증을 먼저 수행했다.

조건:

```text
warn-count=10
critical-count=5
```

결과:

```text
status=UNKNOWN
checker_error=--warn-count must be lower than --critical-count
bad_config_without_state_rc=3
```

따라서 state 없음이 checker configuration 오류를 숨기지 않는다.

### Corrupt State 회귀 검증

Threshold 추가 후에도 손상 state 검출이 유지됐다.

```text
status=UNKNOWN
corrupt_threshold_regression_rc=3
```

### 실제 반복 장애 측정

Fresh ALERT event를 생성한 뒤 실제 connection refused를 네 번 반복했다.

동일 event:

```text
1787210007147775125-1271557
```

측정 결과:

```text
attempt  adapter_rc  failure_count  failure_duration_sec  last_failure_age_sec  oldest_pending_age_sec  pending_events
1        2           1              0.136                 0.136                 5.168                   1
2        2           2              4.285                 0.285                 9.318                   1
3        2           3              8.434                 0.434                 13.467                  1
4        2           4              12.586                0.586                 17.618                  1
```

모든 시도에서:

```text
failure_kind=retryable
failure_reason=connection_error
pending_events=1
```

을 유지했다.

자동 검증:

```text
repeated_failure_measurement=PASS
```

### Event Identity

네 번의 실패 모두:

```text
failed_event_id
=
oldest_event_id
=
1787210007147775125-1271557
```

이었다.

따라서 새로운 notification을 계속 생성한 것이 아니라 동일 미전송 event를 반복 retry했음을 확인했다.

### Backlog Age와 Transport Failure Duration 차이

각 측정에서:

```text
oldest_pending_age_sec >= failure_duration_sec
```

관계가 유지됐다.

측정된 차이:

```text
backlog_minus_failure_min_sec=5.032
backlog_minus_failure_max_sec=5.033
```

이는 notification event 생성 시점부터 첫 실제 webhook 실패 시점까지 약 5초가 있었기 때문이다.

따라서:

```text
oldest_pending_age
→ event 자체의 전체 대기시간

failure_duration
→ 최초 실제 transport failure 이후 시간
```

으로 의미를 분리했다.

### Last Failure Age 동작

각 retry 직후 측정:

```text
attempt1=0.136
attempt2=0.285
attempt3=0.434
attempt4=0.586
```

으로 작게 유지됐다.

Retry를 더 수행하지 않은 뒤 최종 확인:

```text
failure_count=4
failure_duration_sec=30.185
last_failure_age_sec=18.185
```

이었다.

따라서 `last_failure_age`는 전체 장애 지속시간보다 최근 retry 실행 여부를 나타내는 값으로 해석하는 것이 적절함을 확인했다.

### 최종 Outbox 상태

실험 종료 시 notification은 아직 전달되지 않았다.

Failure checker:

```text
status=OK
active_failure=true
failure_kind=retryable
failure_reason=connection_error
failure_count=4
failure_duration_sec=30.185
last_failure_age_sec=18.185
```

Outbox checker:

```text
status=OK
pending_events=1
pending_bytes=341
oldest_pending_age_sec=35.218
oldest_event_id=1787210007147775125-1271557
```

파일 상태:

```text
final_pending=1
final_receipts=0
```

이는 실제 delivery가 성공하지 않았으므로 정상이다.

### 지표 역할 결론

실제 측정 결과를 바탕으로 다음과 같이 구분했다.

```text
failure_duration_sec
→ retryable 장애 severity의 주 시간 기준

failure_count
→ retry cadence에 의존하는 보조 진단 지표

last_failure_age_sec
→ retry worker/timer liveness 후보

oldest_pending_age_sec
→ notification backlog와 사용자 영향

pending_events
→ backlog 수량

failure_kind=permanent
→ 즉시 CRITICAL
```

### Production Threshold

이번 단계에서는 production threshold 값을 확정하지 않았다.

실험에 사용한 count와 duration 숫자는 코드 동작 검증용이다.

Production 값은 실제 retry cadence와 운영 요구가 확정된 뒤 측정 근거와 함께 결정한다.

---

## Production systemd Notification Outbox E2E 검증

### 변경 파일

```text
deploy/systemd/aerotrace-collector-queue-alert.service
deploy/systemd/aerotrace-notification-outbox.service
deploy/systemd/aerotrace-notification-outbox.timer
```

### 기존 상태

Production에는 다음 두 unit만 존재했다.

```text
aerotrace-collector-queue-alert.service
aerotrace-collector-queue-alert.timer
```

Evaluator:

```text
StateDirectory=aerotrace-monitoring
```

Timer:

```text
OnUnitInactiveSec=5s
```

상태는 정상:

```text
service=inactive/dead
last exit=0/SUCCESS

timer=active/waiting
```

oneshot service이므로 service의 inactive/dead 상태는 정상이다.

기존 evaluator에는 notification Outbox 설정이 없었다.

### 변경 내용

Evaluator에 추가:

```text
--event-outbox-dir
/var/lib/aerotrace-monitoring/notification-outbox
```

Notification processor 추가:

```text
aerotrace-notification-outbox.service
```

실행:

```text
process-notification-outbox.py
--outbox-dir /var/lib/aerotrace-monitoring/notification-outbox
--receipt-dir /var/lib/aerotrace-monitoring/notification-receipts
--transport local-file
--max-events 100
--quiet-idle
```

Notification timer:

```text
OnUnitInactiveSec=5s
AccuracySec=1s
```

### systemd 검증

`systemd-analyze verify` 실행 시 AeroTrace unit 자체에는 오류가 없었다.

출력된 netplan/snapd 관련 경고는 AeroTrace unit과 무관했다.

실제 설치 후:

```text
aerotrace-notification-outbox.service
→ status=0/SUCCESS

aerotrace-notification-outbox.timer
→ active/waiting
```

을 확인했다.

### State Directory

실제 생성:

```text
/var/lib/aerotrace-monitoring/notification-outbox
/var/lib/aerotrace-monitoring/notification-receipts
```

권한/소유:

```text
drwxr-x---
huning:huning
```

기존 evaluator state:

```text
/var/lib/aerotrace-monitoring/collector-queue-alert.json
```

과 동일 StateDirectory 경계에서 관리한다.

### 실제 ALERT 테스트

테스트 전:

```text
Collector=Up
pending_events=0
receipts_baseline=0
```

실제 Collector:

```text
otel-collector
```

를 중지했다.

Evaluator 결과:

```text
2026-08-20T16:41:43+0900
event=ALERT
previous_status=OK
current_status=UNKNOWN
checker_exit_code=3
```

Notification processor:

```text
2026-08-20T16:41:49+0900
delivery_result=DELIVERED
event_id=1787211703960728995-1310439
event=ALERT
adapter_status=OK
remaining_events=0
```

Receipt contract 검증:

```text
transport=local-file
event=ALERT
current_status=UNKNOWN
checker_exit_code=3
```

결과:

```text
systemd_alert_receipt=PASS
```

Outbox:

```text
pending_events=0
```

Evaluator event 생성부터 delivery까지 이번 실행에서는 약 6초가 걸렸다.

### 실제 RECOVERY 테스트

Collector를 다시 시작했다.

Evaluator:

```text
2026-08-20T16:42:13+0900
event=RECOVERY
previous_status=UNKNOWN
current_status=OK
checker_exit_code=0
```

Notification processor:

```text
2026-08-20T16:42:19+0900
delivery_result=DELIVERED
event_id=1787211733960954909-1311457
event=RECOVERY
adapter_status=OK
remaining_events=0
```

Receipt contract:

```text
transport=local-file
event=RECOVERY
previous_status=UNKNOWN
current_status=OK
checker_exit_code=0
```

결과:

```text
systemd_recovery_receipt=PASS
```

Evaluator event 생성부터 delivery까지 이번 실행에서도 약 6초가 걸렸다.

### 테스트 종료 상태

```text
production_receipts=2

pending_events=0
pending_bytes=0
```

Timer:

```text
aerotrace-collector-queue-alert.timer
→ active/waiting

aerotrace-notification-outbox.timer
→ active/waiting
```

Collector도 다시 정상 실행 상태로 복구했다.

### 현재 의미

실제 production systemd에서 다음 경로가 검증됐다.

```text
Collector 장애
→ checker UNKNOWN
→ evaluator ALERT
→ durable Outbox
→ notification timer
→ adapter
→ receipt
→ pending ACK

Collector 복구
→ checker OK
→ evaluator RECOVERY
→ durable Outbox
→ notification timer
→ adapter
→ receipt
→ pending ACK
```

### 남은 작업

현재 transport는 systemd wiring 검증용 local-file이다.

다음 단계:

```text
Webhook URL을 별도 environment file로 관리
Webhook transport 활성화
persistent failure-state production 경로 연결
실제 Webhook failure/recovery 테스트
```

이후 failure-state checker systemd 연결과 production threshold는 별도 단계에서 진행한다.

---

## Production systemd Webhook 장애·재시도·자동 복구 E2E 실험

### 변경 파일

```text
deploy/systemd/aerotrace-notification-outbox.service
```

### Webhook systemd 구성

추가:

```text
EnvironmentFile=/etc/aerotrace/notification.env
```

Adapter:

```text
--transport webhook
--webhook-timeout-sec 5
--failure-state-file /var/lib/aerotrace-monitoring/notification-failure.json
```

Network sandbox:

```text
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
```

### Runtime Environment File 검증

테스트 환경파일:

```text
/etc/aerotrace/notification.env
```

권한:

```text
600 root:root
```

실제 systemd oneshot 실행:

```text
notification_service_result=success
notification_service_exec_status=0
```

을 확인했다.

따라서 systemd가 root-owned environment file을 읽고 `User=huning` service 실행 환경에 전달하는 경로가 실제로 동작했다.

### 빈 Outbox 회귀

빈 Outbox에서:

```text
receiver_requests_after_idle=0
failure_state_after_idle=0
```

을 확인했다.

즉 idle 상태에서 불필요한 HTTP request나 failure state를 생성하지 않는다.

### Webhook ALERT 성공

실제 Collector stop:

```text
16:52:54
event=ALERT
OK → UNKNOWN
```

Webhook delivery:

```text
16:53:00
delivery_result=DELIVERED
event=ALERT
remaining_events=0
```

검증:

```text
systemd_webhook_alert=PASS
```

Event ID:

```text
1787212374958106638-1326724
```

HTTP header/body와 receipt identity가 일치했다.

### Webhook RECOVERY 성공

Collector start 후:

```text
systemd_webhook_recovery=PASS
```

Event:

```text
1787212410955694121-1327916
```

성공 경로 최종:

```text
pending_events=0
active_failure=false
final_webhook_receipts=2
final_receiver_requests=2
```

### Transport Failure Injection

Webhook receiver를 종료했다.

```text
receiver_stopped=PASS
```

Collector를 중지해 실제 ALERT를 생성했다.

Persistent failure state:

```text
failed_event_id=1787212535955638444-1330962
failure_kind=retryable
failure_reason=connection_error
failure_count=1
failure_duration_sec=2.599
```

Outbox:

```text
pending_events=1
pending_bytes=385
oldest_pending_age_sec=8.632
oldest_event_id=1787212535955638444-1330962
```

Receipt count:

```text
2
```

로 증가하지 않았다.

### Retry 확인

측정 시작:

```text
failure_count_before_wait=2
```

12초 후:

```text
failure_count_after_wait=4
```

검증:

```text
systemd_retry_count=2->4
systemd_webhook_retry=PASS
```

Journal에서는 이후:

```text
16:56:05 failure_count=5
16:56:11 failure_count=6
```

까지 실제 retry가 이어졌다.

### 실제 Retry 간격

Journal timestamps:

```text
16:55:47
16:55:53
16:55:59
16:56:05
16:56:11
```

약 6초 간격이었다.

Timer configuration:

```text
OnUnitInactiveSec=5s
```

와 실제 실행 cadence가 정확히 같지 않음을 확인했다.

### Pending Identity

Failure state:

```text
failed_event_id=1787212535955638444-1330962
```

Outbox:

```text
pending_event_id=1787212535955638444-1330962
```

검증:

```text
failure_pending_identity=PASS
```

동일 notification event가 실패 상태와 Outbox에서 보존됐다.

### Receiver 복구

Collector는 DOWN 상태를 유지하고 receiver만 재시작했다.

```text
receiver_recovered=1
```

수동 adapter 실행 없이 systemd timer만 기다렸다.

결과:

```text
automatic_webhook_recovery=1
```

복구된 receiver request:

```text
event=ALERT
event_id=1787212535955638444-1330962
```

검증:

```text
systemd_failure_recovery_identity=PASS
```

성공 후:

```text
active_failure=false
failure_count=0
pending_events=0
```

Webhook receipts:

```text
3
```

으로 증가했다.

### 최종 RECOVERY

Transport 복구 후 Collector를 시작했다.

```text
final_recovery_delivered=1
```

최종 receiver 기록 마지막 두 건:

```text
2026-08-20 16:56:17 KST
ALERT
1787212535955638444-1330962

2026-08-20 16:56:47 KST
RECOVERY
1787212601963824599-1332855
```

최종:

```text
pending_events=0
active_failure=false
두 timer active/waiting
```

### Runtime 정리

테스트 Webhook receiver와 localhost environment 설정은 실제 production endpoint가 아니므로 테스트 종료 후 제거한다.

현재 서버 runtime은 검증된 local-file transport로 복원하고 repository에는 Webhook-capable systemd unit 변경을 유지한다.

실제 외부 endpoint를 선택할 때 다시 environment file을 생성하여 Webhook runtime을 활성화한다.

### 남은 위험

현재 permanent HTTP failure도 timer에 의해 반복 실행될 수 있다.

예:

```text
HTTP 400
→ adapter rc=5
→ pending 유지
→ 다음 timer에서 다시 실행
```

따라서 향후:

```text
permanent failure retry 정책
dead-letter
retry budget
backoff
```

을 별도로 설계해야 한다.

Timeout 같은 ambiguous delivery에서는 외부 receiver가 이미 처리했어도 sender가 재전송할 수 있으므로 exactly-once는 보장하지 않는다.

---

## V-7B-4-6 Permanent Webhook Failure 무한 재전송 방지 검증

### 기준점

```text
commit=9df7cbd
runtime transport=local-file
production pending_events=0
production active_failure=false
```

기존 분석용 untracked 스크립트 세 개는 변경하지 않았다.

### 구현

변경 파일:

```text
scripts/process-notification-outbox.py
```

추가한 동작:

- `PermanentFailureLatchedError`
- 현재 event와 일치하는 permanent state 조회
- receipt 확인 뒤, 실제 HTTP 요청 전 latch 차단
- `delivery_result=BLOCKED_PERMANENT_FAILURE`
- `failure_latched=true`
- `--retry-permanent-failure`
- local-file 및 failure-state 미설정 조합의 configuration validation

Failure-state schema version은 변경하지 않았다. 기존 `failure_kind=permanent`와 `failed_event_id`를 latch의 durable 근거로 사용한다.

### 최초 permanent failure와 자동 실행 차단

HTTP 400 fixture에서 같은 pending event로 adapter를 두 번 실행했다.

첫 실행:

```text
first_rc=5
requests_after_first=1
failure_kind=permanent
failure_reason=http_400
failure_count=1
pending_events=1
receipts=0
```

두 번째 일반 실행:

```text
delivery_result=BLOCKED_PERMANENT_FAILURE
failure_latched=true
second_rc=5
requests_after_second=1
failure_count=1
```

두 번째 실행 전후 failure-state SHA-256도 동일했다.

결과:

```text
permanent_latch_no_resend=PASS
actual_webhook_requests=1
assertion_rc=0
```

Adapter는 다시 실행됐지만 HTTP 요청과 state 재작성은 발생하지 않았다.

### 명시적 재시도와 복구

기존 latch에 `--retry-permanent-failure`를 사용하여 HTTP 400을 한 번 더 호출했다.

```text
explicit_retry_400_rc=5
requests_after_explicit_retry=2
failure_count=2
```

그다음 일반 실행에서는 다시 latch가 적용됐다.

```text
blocked_after_retry_rc=5
requests_after_blocked_run=2
```

Webhook을 HTTP 204로 수정한 뒤 명시적으로 재시도했다.

```text
explicit_retry_204_rc=0
requests_after_recovery=3
delivery_result=DELIVERED
failure_state_cleared=true
pending_events=0
receipts=1
failure_state_exists=False
```

결과:

```text
explicit_retry_and_recovery=PASS
assertion_rc=0
```

### ACK_EXISTING crash-window 회귀 검증

다음 상태를 별도 fixture로 재현했다.

```text
valid webhook receipt 있음
동일 pending event 있음
동일 event permanent failure-state 있음
Webhook endpoint는 connection refused 상태
```

결과:

```text
failure_state_cleared=true
delivery_result=ACK_EXISTING
adapter_rc=0
actual_webhook_requests=0
pending_events=0
receipts=1
failure_state_exists=False
ack_existing_precedes_latch=PASS
```

따라서 latch가 기존 receipt 기반 crash-window 복구보다 먼저 실행되지 않음을 확인했다.

### Retryable 회귀 검증

Connection refused를 같은 event에 두 번 발생시켰다.

```text
attempt 1
exit code=2
failure_kind=retryable
failure_reason=connection_error
failure_count=1

attempt 2
exit code=2
failure_kind=retryable
failure_reason=connection_error
failure_count=2
```

`first_failed_at`은 유지됐고 `last_failed_at`은 두 번째 실제 실패 시각으로 증가했다.

HTTP 204 endpoint를 시작한 뒤 명시적 permanent 재시도 옵션 없이 실행했다.

```text
delivery_result=DELIVERED
failure_state_cleared=true
pending_events=0
receipts=1
```

결과:

```text
retryable_automatic_retry=PASS
retryable_automatic_recovery=PASS
retryable_failure_count=1->2
recovery_webhook_requests=1
assertion_rc=0
```

Permanent 전용 latch가 retryable 자동 재시도와 복구를 막지 않음을 확인했다.

### Configuration 및 local-file 회귀 검증

```text
local-file + --retry-permanent-failure
→ exit code 4

webhook + --retry-permanent-failure
+ failure-state-file 없음
→ exit code 4

기존 local-file idle 실행
→ adapter_status=IDLE
→ exit code 0
```

결과:

```text
permanent_retry_configuration=PASS
local_file_regression=PASS
```

### Production 안전 상태

검증 종료 시 installed runtime은 계속 local-file 기준선이었다.

```text
transport=local-file
RestrictAddressFamilies=AF_UNIX
notification timer=active (waiting)
pending_events=0
active_failure=false
```

따라서 임시 Webhook fixture와 permanent latch 테스트가 production notification state에 남지 않았다.

### 남은 위험과 기술 부채

- Permanent pending event의 dead-letter/quarantine 정책은 아직 없다.
- Latched timer 실행의 journal 반복량은 별도로 측정하지 않았다.
- 후속 작업에서 Python adapter tracked `unittest` suite를 추가했고, 이후 커밋 `00b421d`에서 CI job 연결까지 완료했다.
- HTTP timeout의 ambiguous success로 인한 중복 POST 가능성은 해결 범위 밖이다.
- Production systemd Webhook unit에서 실제 HTTP 400 요청 수가 1회로 고정되는 검증은 아직 수행하지 않았다.

---

## V-7B-4-7 Retryable Webhook Failure Bounded Backoff 구현 및 검증

### 기준점

```text
commit=720b838
commit message=Webhook 영구 실패 무한 재전송 방지
runtime transport=local-file
production pending_events=0
production active_failure=false
```

기존 분석용 untracked 스크립트 세 개는 변경하지 않았다.

```text
scripts/sample-collector-exporter.py
scripts/sample-postgres-waits.py
scripts/summarize-postgres-waits.py
```

### 해결하려는 문제

Permanent failure의 자동 무한 재전송은 latch로 차단했지만 retryable failure는 notification timer 실행마다 실제 HTTP 요청을 다시 수행했다.

Endpoint 장애가 길어지면 connection failure, timeout, HTTP 408, HTTP 429, HTTP 5xx가 실제 복구 가능성보다 높은 빈도로 반복될 수 있었다.

목표는 retryable 자동 복구를 유지하면서 실제 실패 횟수에 따라 재시도 간격을 늘리고 최대 지연을 제한하는 것이었다.

### 변경 파일

```text
scripts/process-notification-outbox.py
deploy/systemd/aerotrace-notification-outbox.service
deploy/systemd/aerotrace-notification-outbox-retry-permanent.service
```

### Adapter 변경 내용

추가 옵션:

```text
--retryable-backoff-initial-sec
--retryable-backoff-max-sec
```

기본값:

```text
initial=5초
max=300초
```

`calculate_retryable_backoff_sec()`는 `failure_count` 1부터 initial 값을 적용하고 failure마다 두 배로 증가시키며 maximum에서 제한한다.

```text
5 → 10 → 20 → 40 → 80 → 160 → 300 → 300
```

`load_retryable_failure_defer()`는 다음 조건에서만 backoff를 적용한다.

```text
failure-state 파일 존재
transport=webhook
failed_event_id=현재 pending event_id
failure_kind=retryable
last_failed_at + 계산된 delay가 아직 지나지 않음
```

Failure-state schema version은 변경하지 않았다. 기존 `failure_count`와 `last_failed_at`을 사용하며 별도 next-attempt field는 저장하지 않는다.

Backoff가 남아 있으면 `RetryableFailureDeferredError`를 통해 HTTP 요청 전에 main flow로 반환한다.

출력:

```text
delivery_result=DEFERRED_RETRYABLE_FAILURE
adapter_status=DEFERRED
failure_deferred=true
failure_count=<existing count>
failure_kind=retryable
failure_reason=<existing reason>
failed_event_id=<event id>
retry_after_sec=<remaining seconds>
remaining_events=<pending count>
```

Exit code는 0이다.

이 경로에서는 HTTP 요청, failure-state 변경, `failure_count` 증가, receipt 생성, pending ACK가 발생하지 않는다.

### Webhook 처리 순서

`deliver_to_webhook()`의 처리 순서를 다음과 같이 유지했다.

```text
ACK_EXISTING
→ permanent latch
→ retryable backoff
→ 실제 HTTP request
```

Receipt가 이미 있으면 failure-state 종류와 backoff 잔여 시간에 관계없이 기존 crash-window 복구가 먼저 수행된다.

Permanent latch는 계속 일반 자동 실행을 막는다. `--retry-permanent-failure`는 permanent latch를 명시적으로 우회하고 permanent state는 retryable defer 조건과 일치하지 않으므로 실제 요청을 한 번 수행한다.

### Retryable HTTP 503 Backoff 검증

Local fake HTTP receiver가 HTTP 503을 반환하도록 구성하고 하나의 pending event를 처리했다.

첫 실제 실패:

```text
exit code=2
actual requests=1
failure_kind=retryable
failure_reason=http_503
failure_count=1
pending_events=1
receipts=0
```

첫 실패 직후 같은 event를 다시 실행했다.

```text
delivery_result=DEFERRED_RETRYABLE_FAILURE
adapter_status=DEFERRED
exit code=0
actual requests=1
failure_count=1
pending_events=1
receipts=0
```

따라서 deferred invocation이 HTTP 요청이나 failure-state 증가를 만들지 않음을 확인했다.

첫 5초 backoff가 지난 뒤 다시 실행했다.

```text
exit code=2
actual requests=2
failure_count=2
failure_kind=retryable
failure_reason=http_503
```

두 번째 실패 직후 실행은 다시 deferred됐다.

```text
delivery_result=DEFERRED_RETRYABLE_FAILURE
exit code=0
actual requests=2
failure_count=2
```

두 번째 실패에 적용되는 10초 backoff가 실제 요청을 차단했다.

결과:

```text
retryable_immediate_defer=PASS
retryable_second_failure_backoff=PASS
deferred_request_count_unchanged=PASS
deferred_failure_state_unchanged=PASS
```

### HTTP 204 자동 복구

두 번째 backoff가 지난 뒤 receiver를 HTTP 204로 변경하고 명시적 permanent retry 옵션 없이 adapter를 실행했다.

```text
exit code=0
delivery_result=DELIVERED
actual requests=3
pending_events=0
receipts=1
failure_state_exists=False
```

성공 finalization 순서에 따라 receipt가 생성되고 retryable failure-state와 pending event가 제거됐다.

결과:

```text
retryable_automatic_recovery=PASS
failure_state_cleared_after_success=PASS
```

### Backoff 계산 검증

`failure_count`별 기본 계산 결과를 직접 검증했다.

```text
1=5
2=10
3=20
4=40
5=80
6=160
7=300
8=300
```

Maximum에 도달한 뒤 큰 `failure_count`에서도 300초 상한을 유지했다.

결과:

```text
bounded_backoff_sequence=PASS
bounded_backoff_maximum=PASS
```

### Configuration Validation

다음 잘못된 값을 각각 실행했다.

```text
--retryable-backoff-initial-sec 0
--retryable-backoff-initial-sec NaN
--retryable-backoff-initial-sec 10 --retryable-backoff-max-sec 5
```

각 실행은 HTTP 요청 전에 configuration error와 exit code 4를 반환했다.

```text
invalid_zero_initial=PASS
invalid_nan=PASS
invalid_max_less_than_initial=PASS
```

### ACK_EXISTING 우선순위 회귀

다음 fixture를 구성했다.

```text
valid webhook receipt 있음
동일 pending event 있음
동일 event retryable failure-state 있음
backoff 시간은 아직 남아 있음
```

결과:

```text
delivery_result=ACK_EXISTING
exit code=0
actual webhook requests=0
pending_events=0
failure_state_exists=False
ack_existing_precedes_backoff=PASS
```

따라서 retryable defer가 receipt 기반 crash-window 복구를 막지 않음을 확인했다.

### Permanent Failure 회귀

기존 permanent latch와 explicit retry 동작을 다시 확인했다.

```text
첫 permanent failure
→ 실제 HTTP 요청 1회
→ failure_count=1
→ pending 유지

일반 재실행
→ BLOCKED_PERMANENT_FAILURE
→ 실제 요청 수 유지
→ failure_count 유지

--retry-permanent-failure
→ 실제 요청 1회 수행
→ 성공 시 receipt 저장, failure-state 삭제, pending ACK
```

결과:

```text
permanent_latch_regression=PASS
explicit_permanent_retry_regression=PASS
```

### Local-file 회귀

기존 local-file transport smoke를 실행했다.

```text
local_file_smoke=PASS
```

Backoff 옵션 추가가 installed production transport의 기존 처리 경로를 변경하지 않음을 확인했다.

### systemd Unit 변경 및 검증

Repository Webhook unit의 `ExecStart`에 정책값을 명시했다.

```text
--retryable-backoff-initial-sec 5
--retryable-backoff-max-sec 300
```

Permanent failure는 자동 retry하면 안 되므로 timer가 없는 수동 oneshot unit을 추가했다.

```text
deploy/systemd/aerotrace-notification-outbox-retry-permanent.service
ConditionPathExists=/var/lib/aerotrace-monitoring/notification-failure.json
--retry-permanent-failure
--max-events 1
```

`systemd-analyze verify` 결과 AeroTrace unit 오류는 없었다.

```text
systemd_unit_verify=PASS
```

### Production 안전 상태

Repository Webhook unit과 permanent retry unit은 `/etc/systemd/system`에 설치하지 않았다.

검증 종료 시 installed runtime은 계속 local-file 기준선이었다.

```text
transport=local-file
RestrictAddressFamilies=AF_UNIX
notification timer=active (waiting)
pending_events=0
active_failure=false
/etc/aerotrace/notification.env 없음
```

따라서 fake receiver, retryable failure-state, Webhook URL이 production notification state에 남지 않았다.

### 최종 결과

```text
retryable first failure                 PASS
immediate deferred execution            PASS
second failure after backoff            PASS
second immediate deferred execution     PASS
HTTP 204 automatic recovery             PASS
5→10→20→40→80→160→300 calculation       PASS
invalid configuration exit code 4       PASS
local-file regression                   PASS
ACK_EXISTING precedence                 PASS
permanent latch regression              PASS
explicit permanent retry regression     PASS
systemd unit verification               PASS
production local-file safety            PASS
```

### 남은 위험과 기술 부채

- HTTP timeout ambiguous delivery의 중복 가능성은 남아 있다.
- Deferred invocation도 journal output을 남긴다.
- Head event backoff 중 뒤 event를 처리하지 않는다.
- Wall clock의 큰 변경은 계산된 retry 시각에 영향을 줄 수 있다.
- 실제 외부 endpoint의 rate limit과 notification SLA는 아직 정해지지 않았다.
- Python adapter black-box 검증을 tracked `unittest` suite로 전환했고, 이후 커밋 `00b421d`에서 CI job 연결까지 완료했다.
- Repository Webhook unit과 permanent retry unit은 실제 endpoint 준비 전까지 production에 설치하지 않는다.

---

## 2026-08-14 — PostgreSQL WAL checkpoint로 인한 Collector queue overflow와 Telemetry 유실 분석

### 문제 발견

pgJDBC `reWriteBatchedInserts=true` 적용 후 단기 sustained ingest에서는 높은 rate까지 정상 처리됐지만, 장시간 workload에서 주기적인 processing stall이 발생했다.

대표 실패 실행:

    Target       = 4,500 spans/s
    Duration     = 180 sec
    Expected     = 810,000
    Final DB     = 478,200
    Missing      = 331,800
    Missing rate = 40.96%

누락량:

    331800 / 1050 = 316

으로 당시 Collector가 Backend에 전달하던 대표적인 1,050-span batch의 정확한 배수였다.

### Collector 관찰

실패 구간에서 exporter queue가 다음까지 증가했다.

    queue max = 49,350
    capacity  = 50,000

Collector 로그에는 다음 오류가 반복됐다.

    Client.Timeout exceeded while awaiting headers
    sending queue is full

누적 Collector metric에서도 다음 값이 관찰됐다.

    receiver accepted = 17,970,964
    exporter sent     = 17,314,715

    difference        = 656,249
    enqueue_failed    = 656,250

accepted-but-not-exported 차이와 enqueue failure가 사실상 동일한 규모였다.

로그는 sampling될 수 있으므로 실제 유실 판정에는 Collector counter와 최종 DB 결과를 우선한다.

### PostgreSQL 관찰

같은 고부하 구간에서 PostgreSQL은 반복적으로 다음 checkpoint를 시작했다.

    checkpoint starting: wal

당시 설정:

    max_wal_size = 1024 MB
    checkpoint_timeout = 300 sec
    checkpoint_completion_target = 0.9

실제 checkpoint 예:

    total ≈ 145.7 sec
    distance ≈ 1.14 GB

    total ≈ 47.2 sec
    distance ≈ 1.05 GB

    total ≈ 53.5 sec
    distance ≈ 1.15 GB

    total ≈ 52.2 sec
    distance ≈ 1.29 GB

5분의 `checkpoint_timeout`보다 먼저 WAL 크기 한도에 반복적으로 도달하고 있었다.

### Benchmark 판정 문제 발견

기존 Sender의 `Delivery success`는 Sender가 Collector Receiver에 telemetry를 전달했다는 뜻이었다.

따라서 다음 조건만으로는 DB persistence 성공을 보장할 수 없었다.

    Sender accepted = expected

이에 sustained-load runner의 final integrity 조건을 다음과 같이 강화했다.

    Sender cadence valid
    DB prefix count == expected
    receiver refused delta == 0
    exporter enqueue failed delta == 0
    final queue == 0
    final in-flight == 0

또한 DB count와 Collector metric을 순차적으로 조회하면서 마지막 DB commit을 놓칠 수 있는 race가 있었다.

첫 저부하 smoke에서:

    Collector drain 시점 DB = 4,700 / 5,000

이었지만 이후 동일 prefix를 직접 다시 조회하면:

    DB = 5,000 / 5,000

이었다.

따라서 실제 데이터 유실이 아니라 관측 순서 race로 인한 false negative였다.

Runner에 DB settle polling을 추가한 뒤 동일 저부하 smoke를 다시 실행했다.

    Collector drain 시점 DB = 4,850 / 5,000
    DB settle               = 5,000 / 5,000

최종:

    Collector accepted delta      = 5,000
    Collector refused delta       = 0
    Exporter sent delta            = 5,000
    Exporter enqueue failed delta  = 0
    Final DB                       = 5,000 / 5,000
    Sustained load test            = PASS

를 확인했다.

### `max_wal_size` A/B 실험

다른 주요 설정은 변경하지 않고 PostgreSQL `max_wal_size`만 변경했다.

공통 조건:

    Target         = 4,500 spans/s
    Duration       = 180 sec
    Expected       = 810,000 spans
    Sender batch   = 50
    Sender workers = 32
    Sender queue   = 64
    JDBC batch     = 1,000

### 1GB 실패 결과

    DB                  = 478,200 / 810,000
    Missing             = 331,800
    Queue max           = 49,350
    Collector timeout   = 반복
    Queue full          = 발생
    Sustained validity  = FAIL
    Data integrity      = FAIL

### 4GB 결과

    DB                         = 810,000 / 810,000
    Exporter enqueue failed Δ  = 0
    Receiver refused Δ         = 0
    Final queue                = 0
    Final in-flight            = 0

    Request latency p99        = 1.900 ms
    Producer backpressure      = 0

    Backend CPU avg            = 7.52%
    Backend CPU median         = 8.01%

    Collector CPU avg          = 8.27%
    Collector CPU median       = 8.16%

    DB CPU avg                 = 45.66%
    DB CPU median              = 45.87%

    Sampled queue max          = 1,050
    queue > 1,050 samples      = 0

    Sustained-rate validity    = PASS
    Data integrity             = PASS

같은 테스트 구간에서 Collector timeout과 queue-full 로그는 없었다.

PostgreSQL에서는 WAL-triggered checkpoint가 관찰되지 않았고 time-triggered checkpoint가 관찰됐다.

### 2GB Run 1

    DB                         = 810,000 / 810,000
    Exporter enqueue failed Δ  = 0
    Receiver refused Δ         = 0

    Request latency p99        = 1.854 ms
    Producer backpressure      = 0

    Backend CPU avg            = 7.23%
    Backend CPU median         = 7.05%

    Collector CPU avg          = 8.49%
    Collector CPU median       = 8.82%

    DB CPU avg                 = 48.90%
    DB CPU median              = 46.78%
    DB CPU max                 = 85.48%

    Sampled queue max          = 1,050
    queue > 1,050 samples      = 0

    Sustained-rate validity    = PASS
    Data integrity             = PASS

Collector timeout과 queue-full 로그는 없었다.

### 2GB Run 2

    Requested spans            = 810,000
    Accepted spans             = 810,000
    Failed requests            = 0

    Observed rate              = 4,500.00 spans/s
    Rate error                 = 0.000%

    Request latency p50        = 0.866 ms
    Request latency p95        = 1.543 ms
    Request latency p99        = 2.126 ms

    Producer lag p99           = 0.776 ms
    Send-start lag p99         = 1.471 ms
    Worker dequeue lag p99     = 0.885 ms

    Producer backpressure      = 0

    DB                         = 810,000 / 810,000
    Exporter enqueue failed Δ  = 0
    Receiver refused Δ         = 0

    Sustained-rate validity    = PASS
    Data integrity             = PASS

두 번째 2GB 실행에서는 실제 WAL-triggered checkpoint가 발생했다.

    checkpoint starting: wal

완료:

    write    = 132.076 sec
    total    = 132.259 sec
    distance = 1093312 kB

그러나 checkpoint가 진행되는 동안 Collector timeout, queue-full, enqueue failure 또는 DB 데이터 유실은 발생하지 않았다.

따라서 문제는 checkpoint 존재 자체가 아니라 1GB 조건에서의 높은 WAL-triggered checkpoint 빈도와 그에 따른 downstream latency pressure로 해석한다.

### 최종 결정

현재 N100 기반 AeroTrace MVP 환경에서는:

    max_wal_size=2GB

를 사용한다.

2GB는 테스트한 값 중 가장 작은 안정값이며 절대 최소값으로 간주하지 않는다.

### Docker Compose 영구 반영

`docker-compose.yaml`:

    command:
      - postgres
      - -c
      - max_wal_size=2GB

홈서버에 적용한 뒤 TimescaleDB 컨테이너만 Named Volume을 유지한 채 재생성했다.

실제 컨테이너 command:

    ["postgres","-c","max_wal_size=2GB"]

PostgreSQL runtime:

    max_wal_size    = 2048 MB
    source          = command line
    pending_restart = false

실험 중 사용했던 `ALTER SYSTEM` 설정은 제거했다.

    ALTER SYSTEM RESET max_wal_size;

`postgresql.auto.conf` 확인:

    max_wal_size entries = 0

따라서 PostgreSQL WAL 설정의 source of truth는 Docker Compose다.

### 재배포 데이터 보존 검증

TimescaleDB 컨테이너 재생성 전:

    spans = 19,754,641

재생성 후:

    spans = 19,754,641

로 동일했다.

Docker Named Volume을 유지한 컨테이너 재생성에서 DB 데이터가 보존됨을 확인했다.

재생성 후 서비스 상태:

    TimescaleDB         = healthy
    Backend             = healthy
    Frontend            = healthy
    Collector           = running
    Exporter queue      = 0
    Exporter in-flight  = 0

### 최종 장애 연쇄 해석

현재 측정으로 가장 강하게 지지되는 경로:

    max_wal_size=1GB
    → WAL 한도에 빠르게 도달
    → WAL-triggered checkpoint 반복
    → PostgreSQL write latency tail 증가
    → Backend 응답 지연
    → Collector exporter HTTP timeout
    → exporter consumer 정체
    → persistent sending queue 증가
    → queue capacity 접근/도달
    → block_on_overflow=false
    → enqueue failure
    → telemetry 유실

2GB에서는 WAL checkpoint가 완전히 없어지지는 않았지만 동일한 장기 부하를 두 번 반복하면서 데이터 유실 없이 처리했다.

### 실무적 교훈

- 짧은 benchmark 성공만으로 장시간 안정성을 판단할 수 없다.
- CPU 평균만으로 periodic write stall을 찾기 어렵다.
- Collector Receiver의 HTTP 성공은 DB persistence 성공이 아니다.
- Persistent Queue가 있어도 queue가 가득 차고 overflow 정책이 신규 데이터를 거부하면 telemetry가 유실될 수 있다.
- Benchmark 도구 자체의 판정 오류도 실제 데이터 유실처럼 보일 수 있으므로 측정 도구도 검증해야 한다.
- PostgreSQL checkpoint, Backend latency, Collector retry/queue, 최종 DB row를 하나의 end-to-end 장애 경로로 함께 관찰해야 한다.
- 설정을 여러 개 동시에 변경하지 않고 단일 변수 A/B를 수행해야 원인에 대한 신뢰도를 높일 수 있다.

---

## V-7B-4-8 Webhook Receiver Contract, Notification SLO 초안 및 Tracked 회귀 테스트

### 기준점

```text
Webhook backoff/runbook commit=c558902
installed transport=local-file
production pending_events=0
production active_failure=false
```

### 목표

Webhook sender 구현과 수동 검증은 완료됐지만 실제 receiver가 지켜야 할 idempotency contract, notification SLI/SLO 결정 틀과 반복 실행 가능한 tracked 자동 테스트가 없었다.

이번 단계의 목표:

- Sender와 receiver 사이의 HTTP contract 고정
- Timeout duplicate를 receiver가 처리해야 하는 이유 명시
- Endpoint가 없는 상태에서 SLO 값을 추측하지 않고 측정 기준만 정의
- 기존 수동 black-box 핵심 시나리오를 tracked `unittest`로 전환

### 추가 및 변경 문서

```text
WEBHOOK_RECEIVER_CONTRACT.md
NOTIFICATION_SLO.md
OPERATIONS_RUNBOOK.md
AEROTRACE_CONTEXT.md
DECISIONS.md
CAREER_LOG.md
ENGINEERING_LOG.md
```

### Receiver Contract

`WEBHOOK_RECEIVER_CONTRACT.md`에 다음을 정의했다.

```text
POST + UTF-8 JSON
Content-Type=application/json
Accept=application/json
User-Agent=AeroTrace-Notification/1
X-AeroTrace-Event-Id=body.event_id
schema_version=1
```

Receiver의 성공 2xx는 sender가 더 이상 retry하지 않아도 되는 durable acceptance를 의미해야 한다.

동일 `event_id` duplicate는 사용자-visible side effect를 다시 만들지 않고 2xx를 반환한다. 같은 ID에 다른 payload가 오면 conflict로 기록하고 permanent 4xx로 거부한다.

Timeout은 receiver가 처리했지만 sender가 response를 받지 못한 ambiguous delivery일 수 있으므로 receiver-side durable deduplication이 production activation의 필수 조건이다.

현재 adapter에는 사용자 정의 `Authorization` 또는 signature header가 없고 `Retry-After`를 해석하지 않는다는 제약도 명시했다.

### Notification SLO Draft

`NOTIFICATION_SLO.md`에 다음 SLI를 정의했다.

```text
end-to-end durable delivery latency
delivery-within-objective ratio
oldest pending age
active transport failure duration
retry progress
receiver duplicate side effects
pipeline liveness
```

현재 실제 external endpoint, provider rate limit, user notification latency requirement와 incident owner가 없다.

따라서 다음 수치는 모두 `TBD/미채택`으로 유지했다.

```text
ALERT/RECOVERY delivery objective
monthly success ratio
outbox/failure WARNING·CRITICAL threshold
permanent failure response/restore time
retry budget
incident ownership
```

Current timer, Webhook timeout, `5 → 10 → 20 → 40 → 80 → 160 → 300` backoff에서 가능한 earliest retry timeline을 SLO가 아닌 현재 algorithm 특성으로 분리했다.

### Tracked Test Suite

추가 파일:

```text
tests/test_notification_outbox.py
```

External dependency 없이 Python standard library `unittest`, temporary directory, local `ThreadingHTTPServer`를 사용한다.

표준 실행:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -v
```

검증 항목:

```text
bounded backoff sequence와 maximum
invalid 0, NaN, maximum < initial
HTTP status retryable/permanent 분류
local-file delivery smoke
Webhook payload/header contract
HTTP 503 immediate defer와 state 무변경
backoff 만료 후 두 번째 failure_count 증가
HTTP 204 자동 복구와 finalization
ACK_EXISTING precedes retryable backoff
permanent latch no-resend
explicit permanent retry와 복구
retry-permanent configuration validation
```

최종 결과:

```text
tests=8
passed=8
failed=0
errors=0
result=PASS
```

Sandbox 내부 최초 실행에서는 loopback socket 생성이 `PermissionError`로 차단됐고 network를 사용하지 않는 테스트는 통과했다. 동일 suite를 loopback이 허용된 host 실행 환경에서 다시 실행해 8개 전체 통과를 확인했다.

### Production 안전 상태

Test suite는 `TemporaryDirectory`와 local fake receiver만 사용했다.

다음 production 경로를 사용하거나 변경하지 않았다.

```text
/var/lib/aerotrace-monitoring/notification-outbox
/var/lib/aerotrace-monitoring/notification-receipts
/var/lib/aerotrace-monitoring/notification-failure.json
/etc/aerotrace/notification.env
/etc/systemd/system
```

Repository Webhook unit과 permanent retry unit도 production에 설치하지 않았다.

### 남은 작업

- 실제 receiver/provider와 authentication 방식을 선택한다.
- Receiver durable deduplication acceptance test를 수행한다.
- Notification SLO의 `TBD` 값과 incident owner를 확정한다.
- Checker threshold를 실행할 독립 monitoring 경로를 구현한다.
- 실제 endpoint의 `Retry-After` 또는 rate limit이 현재 backoff와 맞는지 검증한다.

---

## V-7B-4-9 Notification Outbox 회귀 테스트 CI 연결

### 기준점

```text
branch=feature/notification-contract-slo-tests
test suite=tests/test_notification_outbox.py
local result=8/8 PASS
production transport=local-file
```

### 구현

`.github/workflows/notification-outbox-tests.yml`을 추가했다.

```text
trigger=every pull_request update, related main push, workflow_dispatch
runner=ubuntu-latest
python=3.10
permissions=contents:read
timeout=5 minutes
command=PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -v
```

Pull request에서는 최신 HEAD를 항상 검증하고, `main` push만 sender 구현, 회귀 suite, workflow 자체 변경으로 제한했다. Checkout credential은 job 종료 전뿐 아니라 테스트 실행 중에도 불필요하므로 `persist-credentials: false`를 사용했다.

### 검증

로컬에서 YAML 파싱과 `git diff --check`를 통과했고, loopback socket을 허용한 환경에서 회귀 suite 8개 전체 통과를 다시 확인했다.

Draft PR #1 생성 후 GitHub Actions run `32441836314`를 확인했다.

```text
workflow=Notification Outbox Tests
job=notification-outbox
run_number=1
status=completed
conclusion=success
regression test step=success
```

PR은 `main` 기준 충돌 없이 merge 가능한 상태로 확인했다.

이후 문서-only 커밋을 push했을 때 기존 path filter 때문에 최신 HEAD에 run이 생성되지 않는 것을 확인했다. Pull request의 `paths` filter를 제거해 모든 synchronize 이벤트에서 suite를 실행하도록 수정했다. `main` push의 path filter는 유지했다.

수정 커밋 `79dd9e9`의 GitHub Actions run `32442220666`에서 job과 모든 step이 다시 성공했다.

### Production 안전 상태

이 단계는 repository workflow와 문서만 변경했다. `/etc/systemd/system`, `/etc/aerotrace`, production outbox·receipt·failure-state에는 쓰기를 수행하지 않았다. 실제 runtime은 계속 local-file 기준선이다.

### 남은 작업

- 실제 receiver/provider와 authentication 방식을 선택한다.
- Receiver의 durable `event_id` deduplication을 acceptance test로 검증한다.
- Notification SLO 수치와 incident ownership을 확정한다.
- 격리된 Webhook E2E와 rollback rehearsal 후 production 전환 승인을 받는다.

---

## V-7B-4-10 Slack + Cloudflare Durable Notification Receiver 구현

### 기준점

```text
branch=feature/notification-contract-slo-tests
upstream HEAD=a9187fe
draft PR=#1
installed production transport=local-file
pending_events=0
active_failure=false
```

### Channel과 architecture 결정

Notification channel은 Slack으로 선택했다. Sender가 Slack URL을 직접 호출하지 않고 다음 경계를 사용한다.

```text
filesystem outbox sender
-> exact-body HMAC HTTPS
-> Cloudflare Worker
-> D1 durable event_id claim
-> Cloudflare Queue
-> Slack Incoming Webhook
```

구현 디렉터리:

```text
receiver/cloudflare-slack/
```

### Sender HMAC

`scripts/process-notification-outbox.py`에 다음을 추가했다.

```text
env=AEROTRACE_WEBHOOK_SIGNING_SECRET
minimum=32 UTF-8 bytes
signed_content=v1.<unix-seconds>.<exact request body>
algorithm=HMAC-SHA256
headers=X-AeroTrace-Timestamp, X-AeroTrace-Signature
```

Webhook transport는 signing secret이 없거나 짧거나 앞뒤 공백이 있으면 HTTP 요청 전에 exit code 4로 종료한다. Local-file transport는 이 secret을 요구하지 않는다.

### Durable receiver

Worker HTTP handler는 다음 순서를 사용한다.

```text
64 KiB body 제한
-> ±300초 timestamp와 HMAC 검증
-> fatal UTF-8 decode와 JSON/schema validation
-> canonical payload SHA-256
-> D1 INSERT OR IGNORE
-> Queue disk-confirmed send
-> new 202 / duplicate 204 / conflict 409
```

Queue send가 실패하면 D1 `accepted` row를 유지하고 503을 반환한다. Sender retry 또는 5분 scheduled reconciliation이 재publish할 수 있다.

D1 state:

```text
accepted -> queued -> delivering
delivering -> delivered | queued | failed_permanent
DLQ -> failed_exhausted
```

Slack 408/429/5xx와 network/timeout은 retryable이고 5, 10, 20, 40, 80, 160, 300초 상한 delay를 사용한다. Numeric `Retry-After`는 최대 86400초까지 우선한다. Consumer `max_retries=10` 후 DLQ가 final failure를 기록한다.

Slack 성공 후에는 D1 `payload_json`을 `{}`로 redaction하고 `payload_redacted_at`을 기록한다. Dedup hash와 event/delivery metadata는 유지한다.

### Health fallback

Primary Slack 경로 내부의 failure가 sender에는 보이지 않는 문제를 보완하기 위해 `GET /health`가 다음 D1 aggregate를 확인한다.

```text
failed_permanent
failed_exhausted
stale_in_flight >= 600초
```

하나라도 있으면 event ID와 payload 없이 HTTP 503을 반환한다. Production activation 전 UptimeRobot Free HTTP(S) monitor를 5분 간격과 operator email contact로 연결해야 한다.

### 구현 중 발견하고 수정한 문제

1. Node test memory repository에서 `Array.map(structuredClone)`이 index를 options 인자로 전달해 실패했다. Arrow callback으로 교정했다.
2. DLQ duplicate가 이미 delivered event를 덮어쓸 수 있는 경계를 final-state read/ACK로 차단했다.
3. 기본 `TextDecoder`가 invalid UTF-8을 대체할 수 있어 `{ fatal: true }`로 변경했다.
4. D1 insert 후 Queue publish failure가 durable row를 유지하고 duplicate retry로 복구되는 테스트를 추가했다.
5. Slack 성공 row의 diagnostic payload 장기 보존을 제거했다.
6. Receiver async permanent failure가 sender state에 나타나지 않는 관측 공백을 `/health` 503으로 노출했다.

### 자동 검증

Sender:

```text
tests=10
passed=10
failed=0
errors=0
result=PASS
```

Receiver:

```text
tests=14
passed=14
failed=0
result=PASS
```

Receiver test 범위:

```text
HMAC exact body, tamper, stale timestamp
canonical payload hash
durable accept, duplicate, conflict
invalid signature before D1
invalid UTF-8 before D1
Queue failure durable recovery
Slack 2xx, 429 Retry-After, permanent 400, network failure
DLQ exhaustion and delivered-state preservation
scheduled reconciliation
/health degradation
```

Wrangler validation:

```text
version=4.125.0
npm audit vulnerabilities=0
bundle dry-run=PASS
upload size=28.68 KiB
gzip size=7.22 KiB
fresh local D1 migration=3 commands PASS
```

GitHub Actions에 Node.js 22 receiver job을 추가했다. Locked dependency install, Node tests, syntax check와 Worker dry-run을 실행한다.

### SLO와 운영 정책

초기 provisional 목표:

```text
receiver acceptance=99% within 60초
Slack delivered=99% within additional 300초
sender outbox warning/critical=60/300초
sender failure warning/critical=60/300초
receiver stale health=600초
```

Sender retry는 pending을 무기한 보존하고, receiver Slack retry는 10회 후 D1 final failure로 분리했다. Owner는 현재 단일 `AeroTrace service operator` 역할이고 secondary가 없다는 위험을 기록했다.

### Production 안전 상태

다음 작업은 수행하지 않았다.

```text
Cloudflare login/deploy
remote D1 migration
Slack app/Webhook 생성
/etc/aerotrace/notification.env 생성
/etc/systemd/system unit 설치
production timer/service 변경
```

Installed runtime은 계속 local-file 기준선이다.

### 다음 단계

- 운영자가 Slack private channel과 Incoming Webhook을 생성한다.
- Cloudflare Worker를 secrets file과 함께 deploy하고 remote migration을 적용한다.
- Synthetic 202, Slack 1회, duplicate와 `/health`를 검증한다.
- UptimeRobot `/health` DOWN/UP email, HMAC rotation과 local-file rollback을 rehearsal한다.
- 결과를 문서에 반영하고 전체 문서 review 후 commit/push한다.

### GitHub Actions와 PR

Implementation commit:

```text
063b7b2 Slack용 영속 Webhook 리시버 추가
```

기존 draft PR #1을 새로 만들지 않고 재사용했다. PR 제목과 body에서 과거 `8 tests`, provider/auth/SLO `TBD` 문구를 제거하고 현재 Slack/Cloudflare/HMAC/24 tests와 production 미활성 상태로 갱신했다.

GitHub Actions:

```text
workflow=Notification Pipeline Tests
run_id=32445952757
run_number=5
conclusion=success

job=notification-outbox
conclusion=success

job=cloudflare-slack-receiver
conclusion=success
locked install=success
Node tests=success
syntax=success
Worker dry-run=success
fresh D1 migration=success
```

PR은 open/draft, mergeable 상태를 유지했다.

### 문서 전면 review

현재 상태 문서:

```text
WEBHOOK_RECEIVER_CONTRACT.md
NOTIFICATION_SLO.md
OPERATIONS_RUNBOOK.md
receiver/cloudflare-slack/README.md
AEROTRACE_CONTEXT.md의 최상단 current context
```

검토 기준:

```text
현재값 상호 모순
명령과 실제 package/systemd option 일치
sender 2xx와 Slack delivered 의미 분리
secret 이름과 저장 위치
retry 횟수, delay, threshold, owner
duplicate/timeout/DLQ/requeue/rollback 경계
local link와 Markdown heading 구조
실제 credential 포함 여부
과거 기록과 current truth 구분
```

Review에서 수정한 항목:

1. Provider/auth/SLO 미확정 문구를 current 문서에서 제거했다.
2. Slack 3xx가 fetch exception으로 retryable이 되지 않도록 manual redirect 분류와 테스트를 추가했다.
3. Invalid UTF-8을 replacement decode하지 않도록 변경했다.
4. DLQ duplicate가 delivered final state를 덮어쓰지 않도록 했다.
5. D1 `event_id`에 explicit NOT NULL/length와 hash/attempt constraint를 추가했다.
6. 성공 payload redaction과 receiver final failure health를 문서·코드에 일치시켰다.
7. `/health` SQL이 delivered row를 full scan하지 않고 covering reconciliation index를 사용함을 query plan으로 확인했다.
8. Generic email fallback을 UptimeRobot Free 5분 HTTP(S) + operator email로 확정했다.
9. Root runbook과 receiver README의 중복은 각각 host incident/rollback과 Cloudflare deploy라는 독자 범위로 분리했다.
10. Test count를 sender 10, receiver 14, total 24로 모든 current 문서에서 일치시켰다.

Historical engineering/decision section의 `8 tests`, `TBD`, `signature 없음` 문장은 당시 상태를 설명하는 기록이므로 현재 사실처럼 고치지 않았다. Current truth는 각 문서 header와 context 최상단을 기준으로 한다.

### 추가 문서 제안 review

현재 production activation 전 필수 문서는 contract, SLO, operations runbook과 receiver README로 충분하다. 같은 내용을 반복하는 별도 deployment/test 문서는 만들지 않는다.

다음 문서는 조건이 생길 때 추가한다.

```text
SECURITY.md
  trigger=외부 contributor 또는 public vulnerability report 접수 경로가 필요할 때
  scope=notification secret뿐 아니라 AeroTrace repository 전체 disclosure policy

DATA_RETENTION_POLICY.md
  trigger=remote D1 30일 사용량과 event volume을 측정한 뒤
  scope=delivered metadata retention, failed payload maximum retention, purge evidence

NOTIFICATION_INCIDENT_TEMPLATE.md
  trigger=첫 실제 notification incident 또는 secondary on-call 도입 전
  scope=timeline, event_id, sender/receiver state, duplicate, requeue, SLO impact
```

지금 이 문서들을 미리 만들면 owner, 실제 D1 volume, 첫 incident workflow가 없는 placeholder와 runbook 중복이 된다. Trigger 시점에 실측 근거로 작성한다.
