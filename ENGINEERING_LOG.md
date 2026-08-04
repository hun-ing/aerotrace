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
