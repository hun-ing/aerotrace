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
