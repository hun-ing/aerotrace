# AeroTrace Career Log

> 마지막 업데이트: 2026-08-04  
> 현재 Portfolio 단계: 수집, 저장, 장애 복구, 데이터 수명주기, 멀티테넌트 조회, Trace Explorer까지 End-to-End MVP 검증  
> 다음 Checkpoint: 로컬 통합 실행과 실제 배포

---

## 1. 현재까지 직접 확보한 실무 경험

### Backend와 Java

- Java 21과 Spring Boot 4.1.0 프로젝트 구성
- 실제 HTTP 요청에서 Virtual Threads 활성화 검증
- Virtual Threads와 DB Connection Pool의 역할 차이 이해
- Gradle, Actuator, 환경변수 기반 설정

### OpenTelemetry 수집

- OTLP/HTTP JSON Trace Receiver 구현
- Resource, Scope, Span 구조 파싱
- Trace ID, Span ID, Parent Span ID 검증
- Timestamp와 Duration 검증
- Span Kind와 Status Code 검증
- AnyValue 파싱
- Resource / Span Attributes
- Events
- Links
- `uint32`
- 요청 전체 원자성

### TimescaleDB 데이터 모델

- Hypertable과 1일 Chunk
- Tenant / Project 복합 외래키
- Unique Index와 Idempotent Insert
- JSONB와 일반 컬럼 혼합
- Rowstore / Columnstore / Retention
- Chunk 삭제 안전 검증

### 성능 측정

- JDBC 단건과 Batch 공정 비교
- 측정 범위 보정
- Warm-up과 반복
- 실행 순서 교차
- 중앙값
- Batch 크기별 비교
- `reWriteBatchedInserts`를 측정 후 보류
- TimescaleDB 실행계획과 Buffer 분석
- 데이터 선택도에 따른 SQL 성능 역전 검증

### 멀티테넌시와 보안

- Project API Key 구조
- 원문 Secret 미저장
- SecureRandom Secret
- SHA-256 Hash
- 상수시간 비교
- 만료와 폐기
- Tenant / Project Header 위조 방지
- API Key 소유권으로 데이터 경계 결정
- Project 간 동일 Trace ID 격리 Test
- 고카디널리티와 민감정보를 Metric Tag에서 제외

### 장애 대응과 데이터 유실 방지

- DB 장애와 인증 실패 구분
- Retryable 장애에 503
- Collector Retry
- File Storage Persistent Queue
- Collector 재시작 후 Queue 복구
- DB 복구 후 자동 재전송
- Queue Metric과 DB 최종 결과를 함께 검증
- 실험 오류로 200행이 생성된 원인 분석과 재실험

### Query API

- Trace 목록과 상세 API
- Keyset Pagination
- Query Fingerprint
- Trace 전체 집계 Filter
- Service / Error / Duration 조합
- 최대 조회 범위와 응답 크기 제한
- Mock, Controller, Integration, 실제 HTTP 검증 분리

### Frontend

- Next.js App Router
- Server-only BFF
- API Key Browser 미노출
- Runtime Response Validation
- Loading / Empty / Error
- URL과 Filter 상태 동기화
- Cursor Load More
- 추가 페이지 오류 시 기존 목록 유지
- Trace 상세와 Span Timeline
- Root / Parent 관계
- Multi-service Trace
- Error Span 시각화
- 실제 3 Span Trace로 End-to-End 검증

## 2. Portfolio 핵심 서사

AeroTrace의 핵심은 기술 목록이 아니라 다음 흐름이다.

```text
문제 정의
→ 대안 검토
→ 최소 구현
→ 사용자가 직접 실행
→ 실패 재현
→ 원인 분석
→ 측정
→ 설계 결정
→ 운영 위험 기록
```

현재 설명할 수 있는 End-to-End 흐름:

```text
OTLP Client
→ Collector
→ Retry / Persistent Queue
→ API Key 인증
→ OTLP 검증
→ JDBC Batch
→ TimescaleDB
→ Rowstore / Columnstore / Retention
→ Multi-tenant Query API
→ Next.js BFF
→ Trace List / Filter / Cursor
→ Span Timeline
```

## 3. 측정으로 증명된 성과

### JDBC Batch

| Span 수 | 단건 중앙값 | Batch 중앙값 | 개선 |
|---:|---:|---:|---:|
| 100 | 91.084ms | 30.460ms | 약 2.99배 |
| 1,000 | 874.985ms | 299.167ms | 약 2.92배 |
| 5,000 | 4,566.945ms | 1,485.885ms | 약 3.07배 |

사용 가능한 표현:

- 100~5,000 Span Persistence-only 환경에서 JDBC Batch가 단건 대비 약 2.9~3.1배 높은 처리량

사용하면 안 되는 표현:

- Collector부터 DB까지 3배 개선
- Network Round Trip을 1회로 감소
- 실제 운영 처리량 3배

### Batch 크기

5,000 Span 기준:

- 500: 1,634.554ms
- 1,000: 1,712.393ms
- 5,000: 1,698.537ms

결정:

- 가장 빠른 단일값만 선택하지 않고 실행 크기 제한과 안정성을 고려해 1,000 선택

### Query SQL

- Trace 20,000
- Span 109,998
- 현재 목록 SQL: 대체로 90~110ms
- 후보 1%: 19.819ms
- 후보 100%: 345.467ms
- 동일 100% 기존 SQL: 98.048ms

핵심 교훈:

- 후보를 먼저 줄이는 SQL도 선택도가 높으면 더 느릴 수 있다.
- 조건값만으로 데이터 선택도를 가정하면 위험하다.
- 측정 없이 Index와 Summary를 추가하지 않았다.

## 4. 장애 복구 성과

### 100 Span

검증:

```text
DB 장애
→ Backend 503
→ Collector Queue 100
→ Collector 재시작
→ Queue Metadata 복구
→ DB 복구
→ 자동 재전송
→ DB 100행
→ 고유 Span ID 100
→ Queue 0
```

사용 가능한 표현:

- Collector 재시작을 포함한 장애 복구 실험에서 100개 Span이 중복 없이 자동 저장되는 것을 확인

주의:

- 모든 상황에서 데이터 유실 0%를 보장했다고 표현하면 안 된다.
- Disk 손상, Overflow, 전원 차단은 검증하지 않았다.

### 10,000 Span

검증:

- Queue Capacity 50,000
- 장애 중 Size 10,000
- Enqueue Failure 없음 또는 0
- 복구 후 Queue Size 0

문서화되지 않은 값:

- 최종 DB Total Rows
- 최종 Distinct Span IDs

따라서 “10,000 Span이 중복 없이 최종 저장됐다”라고 쓰면 안 된다.

## 5. 데이터 수명주기 Portfolio Checkpoint

### 직접 경험한 내용

- Hypertable Chunk와 Partition Column 확인
- Hypercore Columnstore 설정
- 최근과 과거 데이터 저장 방식 분리
- Background Job 중지와 수동 실행
- Retention 삭제 후보 사전 검사
- Chunk 전체 삭제 위험 검토
- 삭제 대상과 보존 대상 동시 검증
- 정책 자동 스케줄 복구

### 검증된 수명주기

```text
0~2일
→ Rowstore

2~30일
→ Columnstore

30일 초과
→ Retention
```

### 평가 포인트

- 정책 설정만 추가하지 않고 실제 전환과 삭제 검증
- 대상 Chunk의 비테스트 행 확인 후 Retention 실행
- 늦게 도착한 오래된 Span의 조기 삭제 위험 인식
- 공유 Hypertable에서 Tenant별 Retention이 어려운 이유 설명 가능

### 이력서 문장 초안

- 제한된 저장 자원에서 Telemetry의 무기한 증가를 방지하기 위해 TimescaleDB Rowstore·Columnstore·Retention 수명주기를 설계하고, 최근 2일은 Rowstore, 2~30일은 Columnstore, 30일 초과 데이터는 Chunk 단위로 제거하도록 구성 및 실제 정책 실행 검증

- Retention 실행 전 대상 Chunk의 비테스트 행과 전체 삭제 후보를 확인하고, 35일 전 데이터만 제거되며 4일 전과 최근 데이터가 보존되는 것을 검증

## 6. 멀티테넌트 조회 Portfolio Checkpoint

### 직접 경험한 내용

- API Key 인증 결과를 Repository의 Tenant / Project 조건에 연결
- 클라이언트가 Tenant / Project를 선택하지 못하게 설계
- 서로 다른 Project에 동일 Trace ID 구성
- 목록과 상세의 데이터 격리 검증
- Offset 대신 Keyset Pagination
- 동일 시작 시각의 보조 정렬 Trace ID
- Query Fingerprint
- Trace 포함 Filter와 전체 Trace 집계 분리
- 최대 5,000 Span 상세 제한
- 400 / 401 / 404 / 422 계약
- 자동 Test와 실제 HTTP Fixture 검증

### 평가 포인트

- 단순 CRUD가 아니라 Multi-tenant 격리를 SQL과 Integration Test로 강제
- Cursor를 권한 경계로 착각하지 않음
- 실시간 데이터 특성에 맞는 Pagination 선택
- Filter에 맞는 Span과 전체 Trace 집계 의미 구분
- 새로운 Index를 추측으로 추가하지 않음

### 이력서 문장 초안

- API Key 인증 결과를 Tenant·Project 복합 조회 조건으로 강제하고, 서로 다른 Project에 동일한 Trace ID를 구성한 통합 테스트로 Trace 목록과 상세의 멀티테넌트 데이터 격리를 검증

- 실시간으로 Trace가 추가되는 목록의 페이지 중복과 누락 위험을 줄이기 위해 시작 시각과 Trace ID 기반 Keyset Pagination을 적용하고, Query Fingerprint로 Filter 또는 Project 변경 후 Cursor 재사용을 차단

- Service, Error, Span Duration Filter를 적용하면서 목록 집계는 Trace 전체 Span을 기준으로 유지하도록 SQL을 설계하고 실제 TimescaleDB Integration Test와 HTTP Fixture로 검증

## 7. Trace 조회 성능 Portfolio Checkpoint

### 직접 경험한 내용

- TimescaleDB Chunk별 실행계획
- `EXPLAIN ANALYZE`
- Buffer Hit
- GroupAggregate
- Merge Append
- Nested Loop
- Merge Join
- Filter 선택도
- Cursor가 기능적 Pagination과 DB 작업량 최적화에서 다른 역할임을 확인
- 선택도에 따라 최적화 SQL이 역전되는 현상 검증
- 불필요한 Index와 조기 Summary 도입 보류

### 이력서 문장 초안

- 20,000 Trace와 109,998 Span으로 TimescaleDB 실행계획을 분석하고, 후보 Trace 우선 집계가 선택도 1%에서는 19.819ms지만 후보 100%에서는 345.467ms로 악화되는 것을 검증하여 데이터 분포에 취약한 SQL 분기와 불필요한 Index 도입을 보류

### 면접 포인트

- 왜 후보가 적을 때 빨랐는가?
- 후보 100%에서 왜 더 느려졌는가?
- Buffer Hit 증가와 실행시간의 관계는?
- Duration 값만으로 SQL을 선택하면 왜 위험한가?
- Trace Summary의 늦게 도착하는 Span은 어떻게 처리할 것인가?

## 8. Trace Explorer Portfolio Checkpoint

### 직접 경험한 내용

- Next.js App Router 기반 Trace Explorer
- Browser에서 Backend 직접 호출을 피하는 BFF
- Server-only 환경변수
- Project API Key Browser 미노출
- Runtime JSON 검증
- Filter Draft와 적용 Query 분리
- URL 상태 복원
- Cursor Load More
- 첫 페이지 오류와 추가 페이지 오류 분리
- 추가 조회 실패 시 기존 데이터 유지
- 상세 Dynamic Route Handler
- Span Timeline
- 실제 Multi-span Fixture 생성
- Root / Parent, Multi-service, Error 상태 검증
- 상세 Panel 위치 문제를 DOM Scroll 동기화로 해결
- React Effect와 Event Handler의 책임 구분

### 실제 검증 Trace

```text
Span: 3
Service: 2
Root: Server / OK / 200ms
DB Child: Client / OK / 50ms
Worker Child: Consumer / Error / 80ms
Error Message: simulated verification failure
```

### 평가 포인트

- 수집부터 UI까지 End-to-End 연결
- Fake UI 데이터가 아니라 실제 OTLP 수집 데이터 사용
- API Key를 Browser에 노출하지 않음
- Cursor 오류와 중복 감지
- 추가 페이지 장애에서 기존 사용자 데이터 보존
- 실제 Backend 응답 확인 후 TypeScript 계약 정의
- 400 / 404 / 502 실패 경로 검증

### 이력서 문장 초안

- OpenTelemetry Trace 데이터를 조회할 수 있도록 기간·Service·Error·Span Duration Filter와 Cursor 기반 Load More를 갖춘 Next.js Trace Explorer를 구현하고, 부모·자식 Span과 다중 Service 실행 흐름을 상대 Timeline으로 시각화

- Project API Key의 Browser 노출을 방지하기 위해 Next.js Server-only BFF를 구성하고, Backend 장애와 추가 페이지 조회 실패 시 기존 Trace 목록을 유지하도록 오류 상태를 분리

- Root Span 1개와 Child Span 2개, 두 Service와 Error 상태를 포함한 Telemetry를 실제 수집 API로 생성해 수집·저장·집계·상세 조회·Timeline 전체 흐름을 End-to-End로 검증

### 현재 한계

- 사용자 로그인 / 세션 없음
- 다중 Project UI 없음
- 공개 SaaS 인증 구조 아님
- Frontend 자동화 Test 없음
- 대형 Trace Rendering 미측정

## 9. 통합 이력서 문장 초안

### 수집과 저장

- OpenTelemetry Trace의 Resource, Scope, Span, Attributes, Events, Links를 검증하고 TimescaleDB에 저장하는 OTLP/HTTP JSON 수집 파이프라인을 구현했으며, 요청 단위 Transaction과 Idempotent Insert로 부분 저장과 재전송 중복을 방지

### JDBC Batch

- 동일 데이터·SQL·Transaction 조건의 반복 Benchmark를 구성하고 Spring JDBC Batch를 적용해 100~5,000 Span Persistence-only 구간에서 단건 저장 대비 약 2.9~3.1배 높은 처리량을 확인했으며, Batch 크기별 측정을 통해 초기 운영값 1,000을 결정

### Multi-tenancy

- 클라이언트 Header 위조로 인한 데이터 격리 실패를 방지하기 위해 Project API Key의 DB 소유권으로 Tenant와 Project를 결정하고, 원문 Secret 미저장·Hash 검증·만료·폐기를 지원하는 인증 구조 구현

### 장애 복구

- TimescaleDB 장애를 503으로 분류해 Collector Retry와 File Storage Persistent Queue에 연결하고, Collector 재시작과 DB 복구 이후 100개 Span이 중복 없이 자동 저장되는 장애 복구 시나리오 검증

### 데이터 수명주기

- 최근 2일 Rowstore, 2~30일 Columnstore, 30일 초과 Retention 정책을 구성하고 실제 Chunk 전환과 삭제 대상을 사전 검증하여 제한된 저장 환경의 Telemetry 수명주기 구현

### Query

- API Key 인증 결과를 Tenant·Project 조회 경계로 강제하고, Trace 시작 시각과 ID 기반 Keyset Pagination 및 Query Fingerprint를 적용해 Filter 변경과 Project 간 Cursor 재사용을 차단

### Query Performance

- 20,000 Trace와 109,998 Span의 TimescaleDB 실행계획을 분석해 후보 우선 집계가 선택도에 따라 19.819ms에서 345.467ms까지 역전되는 것을 확인하고, 불필요한 SQL 분기와 Index 도입을 보류

### Frontend

- Next.js Server-only BFF와 Trace Explorer를 구현해 Project API Key 노출 없이 Trace 목록·Filter·Cursor Pagination·Span Timeline을 제공하고, 3 Span·2 Service·Error Trace를 실제 OTLP 경로로 End-to-End 검증

## 10. 현재 사용하면 안 되는 표현

- 초당 수만 Span 처리
- 무중단 APM
- 데이터 유실 0%
- 대규모 트래픽 운영
- 상용 서비스 운영
- 실제 사용자 확보
- 장애 예측 시스템 완성
- Collector부터 DB까지 3배 개선
- Network Round Trip 1회
- N100 안정 운영
- 10,000 Span 중복 없는 최종 저장
- 30일 저장 용량 산정 완료
- Columnstore 압축률 개선
- Public SaaS 인증 완료
- Production 배포 완료

## 11. 보존해야 할 증거

### 코드

- Flyway V1~V8
- `spans` Hypertable
- 복합 외래키
- Unique Index
- OTLP Parser
- AnyValue Parser
- Event / Link Parser
- JDBC Writer
- Batch Benchmark
- Project API Key
- Auth Filter
- Collector Config
- Query Repository
- Cursor Codec
- Multi-tenant Integration Test
- Next.js BFF
- Trace Explorer
- Span Timeline

### 로그와 출력

- Virtual Thread 확인
- Flyway 적용
- Hypertable / Chunk
- 동일 Span 중복
- Batch 결과
- 503
- Auth Metric
- Collector Retry
- Queue 100
- Collector Restart Metadata
- DB 100 / Distinct 100
- Queue 10,000
- Queue 0
- Columnstore 전환
- Retention 삭제
- Query Cursor
- Cursor Mismatch 400
- Trace Detail 404 / 422
- Frontend Detail 400 / 404 / 502

### 성능 자료

- JDBC Raw Measurement
- Rewrite ON / OFF
- Batch Size Table
- Query Dataset SQL
- `EXPLAIN ANALYZE`
- Buffer Count
- Candidate 1% / 5% / 100%
- Test Environment
- Date
- Repeat Count

### 화면

- Trace List
- Filter Query
- Load More
- Browser Network에 Authorization 없음
- 3 Span / 2 Service 목록
- Root / Client / Consumer Timeline
- Error Span과 Message
- Backend 장애 상세 오류

## 12. 면접 사례

### 사례 A — 성공 응답인데 Attribute가 저장되지 않음

원인:

- 기존 Test와 동일 Unique Identity
- `ON CONFLICT DO NOTHING`

해결:

- Test별 고유 식별자
- HTTP 응답과 DB 결과 함께 검증

평가 포인트:

- Idempotency가 Test에도 영향을 준다는 점
- 성공 응답만 믿지 않은 점

### 사례 B — `reWriteBatchedInserts` 보류

행동:

- ON / OFF 반복 비교
- Span 수별 결과 확인
- 일관된 개선 부재

평가 포인트:

- 유명 옵션을 무조건 적용하지 않음
- 효과 없는 결과도 문서화

### 사례 C — Queue 실험 200행

증상:

```text
total_rows = 200
distinct_span_ids = 100
```

분석:

- 실행 중복 또는 이전 Queue
- 새 Start Time으로 Unique Identity 변경

해결:

- DB와 Queue 초기화
- 한 번만 실행
- 100 / 100 확인

평가 포인트:

- Queue Metadata와 DB를 함께 분석
- 실험 입력 조건 통제

### 사례 D — Candidate SQL 성능 역전

증상:

- 1% 후보는 빠름
- 100% 후보는 기존보다 느림

분석:

- 후보 생성과 원본 재조회
- Join과 Buffer 증가
- 선택도 의존

평가 포인트:

- 실제 데이터 분포 검증
- 조기 최적화 방지

### 사례 E — View를 눌러도 반응이 없어 보임

증상:

- 버튼은 Selected
- 상세가 안 보임

원인:

- 상세 Panel은 목록 아래에 생성
- 긴 Table 때문에 Viewport 밖

해결:

- DOM Ref
- `scrollIntoView`
- Scroll Margin

평가 포인트:

- 상태 문제와 표시 위치 문제 분리
- 사용자 관점 오류 분석

## 13. 블로그 주제

### 1. JPA 대신 JDBC Batch를 선택한 과정

- Telemetry Hot Path
- 공정한 비교
- 2.9~3.1배
- Batch Size 1,000
- Rewrite 보류

### 2. Collector 재시작에도 Trace를 잃지 않도록 만든 과정

- Memory Queue 한계
- 503
- File Storage
- Restart
- 100 / 100
- 200행 실험 오류
- Persistent Queue가 보장하지 못하는 범위

### 3. TimescaleDB Telemetry 수명주기

- Rowstore
- Columnstore
- Retention
- 안전한 Chunk 삭제
- Late Span
- Tenant별 Retention의 어려움

### 4. 실시간 Trace 목록의 Cursor Pagination

- Offset 문제
- Start Time + Trace ID
- Limit + 1
- Query Fingerprint
- Cursor와 권한 경계 구분
- HAVING과 전체 Trace 집계

### 5. 빠른 SQL이 항상 빠르지는 않다

- Candidate-first
- 선택도 1%와 100%
- Buffer
- Planner
- Summary 도입 조건

### 6. API Key를 노출하지 않는 Next.js Trace Dashboard

- Browser 직접 호출 위험
- Server-only BFF
- 환경변수
- Filter와 Cursor
- Multi-span Fixture
- Local MVP와 Public SaaS 차이

## 14. 다음 Portfolio Checkpoint

Phase 9에서 다음 중 하나를 완료하면 갱신한다.

- Backend / Frontend / Collector / TimescaleDB 통합 Compose
- Health Check와 시작 순서
- Reverse Proxy와 HTTPS
- Backup / Restore 실전 검증
- Oracle Cloud 배포
- N100 홈서버 배포
- AeroTrace 자체 Dogfooding
- 실제 사용자 서비스 연동
- 사내 PoC
- 운영 장애와 복구 사례

### 다음 한 단계 높은 과제

```text
빈 환경에서 어떤 Secret과 설정을 준비하고,
어떤 순서로 전체 시스템을 실행하며,
어떤 Health Check로 준비 상태를 판단하고,
Trace를 전송한 뒤 Dashboard에서 확인하며,
서비스 하나가 실패했을 때 어디서 원인을 확인하고,
데이터와 설정을 어떻게 복구하는가?
```

이 과정을 완료하면 구현 경험이 실제 배포와 운영 경험으로 확장된다.

---

### 장애 복구 경험 — Nginx stale Docker upstream

AeroTrace 홈서버 운영 환경에서 Frontend 프로세스 장애를 직접 주입해 Docker restart policy의 실제 복구 동작을 검증했다.

Frontend 컨테이너는 자동 재시작되어 `healthy` 상태까지 복구됐지만 실제 Dashboard에서는 `504 Gateway Timeout`이 발생했다. Nginx 로그의 기존 upstream IP와 재시작 후 Docker IP 및 DNS 결과를 비교해 Nginx의 stale upstream resolution이 원인임을 확인했다.

Docker embedded DNS와 Nginx dynamic upstream resolution을 적용하고 동일 장애를 다시 주입하여 컨테이너 복구뿐 아니라 실제 사용자 요청 경로까지 자동 복구되는 것을 검증했다.

### 이력서 문장 초안

Docker Compose 기반 운영 환경에서 Frontend 장애 주입 테스트를 수행해 컨테이너 자동 재시작 후 Nginx가 변경 전 upstream IP를 유지하면서 발생하는 504 장애를 발견하고, Docker embedded DNS 기반 동적 upstream resolution을 적용해 실제 서비스 요청 경로의 자동 복구를 검증

### 면접 소재

`restart: unless-stopped`가 있는데도 서비스가 복구되지 않았던 이유, Docker 컨테이너 IP와 DNS의 관계, Nginx hostname resolution 방식, health check와 실제 사용자 가용성의 차이, 장애 주입을 통해 설정 존재 여부가 아닌 실제 복구 능력을 검증한 과정

### 블로그 주제

**제목:** Docker 컨테이너는 살아났는데 왜 Nginx는 504를 반환했을까?

핵심 메시지는 컨테이너 자동 재시작과 서비스 자동 복구는 같은 개념이 아니며, Reverse Proxy의 service discovery까지 장애 복구 설계에 포함해야 한다는 것이다.

보존할 자료는 504 Nginx error log, 장애 전후 Container PID/IP, RestartCount 변화, Docker DNS 조회 결과, dynamic upstream 설정, 수정 전후 장애 주입 결과이다.

---

## Persistent Queue 장애 복구 검증

### 직접 수행한 경험

AeroTrace telemetry 수집 경로에서 Backend 장애와 OpenTelemetry Collector 프로세스 장애를 연속으로 주입하고 Persistent Queue가 실제 데이터 유실을 방지하는지 검증했다.

Backend를 사용할 수 없는 동안 Collector가 수신한 테스트 Span이 DB에 저장되지 않은 상태임을 확인한 뒤 Collector 프로세스까지 강제 종료했다.

Collector가 Docker restart policy로 다시 실행된 이후에도 queued telemetry가 유지됐으며, Backend 복구 후 retry를 통해 TimescaleDB에 테스트 Span이 정확히 1건 저장되는 것을 확인했다.

### 포트폴리오 강조점

단순히 OpenTelemetry Collector의 `sending_queue`, `retry_on_failure`, `file_storage` 설정을 적용한 것이 아니라 실제 장애를 발생시켜 다음을 검증했다.

* Backend 장애 중 telemetry 수신
* 디스크 기반 queue 보존
* Collector crash/restart 이후 queue 복원
* Backend 복구 후 자동 retry
* 최종 데이터 유실 여부
* 최종 중복 저장 여부

### 이력서 문장 초안

OpenTelemetry Collector의 persistent queue와 retry 구조를 적용하고 Backend 장애 및 Collector 프로세스 재시작을 연속으로 주입하여, 수신된 telemetry가 장애 구간을 견디고 Backend 복구 후 TimescaleDB에 정확히 1건 저장되는 데이터 유실 방지 경로를 검증

### 예상 면접 질문

* Collector가 HTTP 200을 반환한 뒤 Backend 저장에 실패하면 데이터는 어디에 존재하는가?
* memory queue와 persistent queue의 장애 내성 차이는 무엇인가?
* Collector 컨테이너까지 재시작했는데 데이터가 남을 수 있었던 이유는 무엇인가?
* Persistent Queue가 있다고 데이터 유실이 절대 발생하지 않는가?
* queue가 가득 차거나 디스크가 가득 차면 어떻게 되는가?
* retry로 인해 동일 Span이 중복 저장될 가능성은 어떻게 처리해야 하는가?

### 블로그 주제

**Backend도 죽이고 Collector도 죽여봤다 — OpenTelemetry Persistent Queue는 정말 데이터를 지켜줄까?**

핵심 메시지는 설정 파일에 `persistent queue`가 존재하는 것과 실제 장애 상황에서 데이터 복구를 증명하는 것은 전혀 다른 수준의 검증이라는 것이다.

보존할 자료:

* 장애 테스트 명령과 순서
* Collector queue/retry 설정
* Persistent Volume mount
* Collector RestartCount 변화
* 장애 전/후 queue metric
* 테스트 Trace ID / Span ID
* DB 최종 count=1 결과

---

## OpenTelemetry Persistent Queue 저장 비용 실측

AeroTrace의 Backend 장애 상황을 재현해 OpenTelemetry Collector에 100개의 테스트 Span을 적재하고 queue metric, 디스크 사용량, 복구 후 데이터 정합성을 함께 측정했다.

Collector는 장애 중 100개 Span을 모두 수락했고 `queue_size`가 0에서 100으로 증가했다. `receiver_refused_spans`는 0이었으며 Backend 복구 후 queue가 다시 0으로 감소하고 TimescaleDB에 정확히 100건 저장되는 것을 확인했다.

Persistent Queue storage는 최초 상태 대비 apparent size가 98,304 bytes, filesystem allocated size가 45,056 bytes 증가했다.

이번 테스트 payload 기준으로 각각 약 983 bytes/span, 451 bytes/span의 증가량을 관찰했지만, 실제 운영 Span 크기로 일반화하지 않고 더 큰 표본과 실제 telemetry를 통해 추가 측정하기로 했다.

또한 queue drain 이후에도 Persistent Queue 파일의 크기가 즉시 감소하지 않는 것을 직접 관찰하여, 디스크 사용량 분석 시 현재 파일 크기만이 아니라 high-water mark와 최초 baseline을 함께 봐야 한다는 운영 특성을 확인했다.

### 이력서 문장 후보

OpenTelemetry Collector Persistent Queue의 장애 대응 능력을 기능 검증에 그치지 않고 Backend 장애 상태에서 100 Span을 직접 적재하여 queue metric과 디스크 사용량을 측정하고, 복구 후 100건 전량 저장 및 중복·유실 없음까지 검증

### 면접 소재

* `queue_size`와 실제 디스크 사용량의 관계
* queue가 drain돼도 파일 크기가 즉시 감소하지 않은 이유를 운영상 어떻게 해석했는가
* 100 Span 결과만으로 최대 장애 시간을 산정하지 않은 이유
* apparent size와 allocated size를 둘 다 측정한 이유
* 실제 서비스 Span 저장량을 산정하려면 어떤 추가 실험이 필요한가

---

## Persistent Queue 100 → 1,000 Span 용량 실험

Backend 장애 상태에서 OpenTelemetry Collector Persistent Queue에 100개와 1,000개의 테스트 Span을 단계적으로 적재하고 queue metric, disk high-water mark, 복구 후 DB 정합성을 비교했다.

1,000 Span 테스트에서는 Collector가 전량 수락하고 receiver refused가 0인 상태에서 queue가 1,000까지 증가했으며 Backend 복구 후 TimescaleDB에 1,000건 모두 저장되는 것을 확인했다.

또한 DB 저장 완료 직후에도 Collector `queue_size`가 31로 남아 있는 것을 발견해, 기존 테스트가 DB count와 queue drain을 동일한 완료 조건으로 취급하고 있다는 측정 로직의 문제를 발견했다.

이를 통해 장애 테스트에서도 단순 PASS/FAIL보다 각 컴포넌트의 완료 기준을 독립적인 metric으로 검증해야 한다는 운영 경험을 얻었다.

### 포트폴리오 포인트

- Backend 장애 상태에서 1,000 Span queue high-water 직접 측정
- Collector accepted/refused 및 exporter queue metric과 DB 결과 교차 검증
- persistent storage의 공간 재사용 특성을 고려해 단순 Span당 평균값의 과도한 일반화를 피함
- 테스트 스크립트 자체의 잘못된 queue drain 판정을 metric으로 발견

### 이력서 문장 후보

OpenTelemetry Collector Persistent Queue에 100/1,000 Span을 단계적으로 적재해 장애 중 queue와 디스크 high-water mark를 실측하고 복구 후 전량 저장을 검증했으며, DB 저장 완료와 Collector queue drain 시점이 다를 수 있음을 발견해 장애 테스트 완료 조건을 metric 기반으로 개선

---

## 성능 측정 방법 개선 경험

AeroTrace 정상 ingest 처리량을 측정하면서 수동 stopwatch 방식의 end-to-end 측정에 운영자의 명령 입력 시간이 포함되어 잘못된 `53.76 spans/s` 결과가 생성되는 문제를 발견했다.

해당 측정값을 사용하지 않고 benchmark를 자동화해 다음 시점을 프로그램적으로 분리했다.

- OTLP 전송 시작
- Collector 수락 완료
- TimescaleDB 저장 완료
- Collector queue 및 in-flight 처리 완료

자동화된 1,000 Span 실험에서는 1,000건 전량 저장 및 queue drain을 검증했고, Collector 수락 속도와 실제 DB/pipeline 처리 속도가 크게 다름을 확인했다.

이를 통해 단순 request throughput과 실제 서비스 end-to-end throughput을 구분하여 성능을 측정하는 경험을 얻었다.

### 이력서 문장 후보

OpenTelemetry 기반 수집 파이프라인의 성능 측정 과정에서 수동 측정 오차를 발견해 benchmark를 자동화하고, Collector 수락 처리량과 TimescaleDB 저장 완료 처리량을 분리 측정하여 실제 end-to-end 성능 기준선을 구축

---

## 반복 측정을 통한 APM 수집 처리량 Baseline 검증

AeroTrace의 OTLP 수집 성능을 평가하면서 단일 benchmark 결과를 대표 성능값으로 사용하지 않고 동일 조건을 5회 반복해 최소값, 중앙값, 평균, 최대값, 표준편차를 비교했다.

1,000 synthetic Span, batch 50, concurrency 4 조건에서 모든 실행의 데이터 정합성과 Collector queue drain을 검증했으며, DB completion throughput 중앙값 약 1.24K spans/s를 현재 baseline으로 확보했다.

또한 Collector HTTP 수락 처리량과 실제 DB 저장 처리량이 같은 방향으로 움직이지 않는 결과를 관찰해, ingress acceptance throughput을 서비스의 end-to-end 저장 성능으로 해석하면 안 된다는 점을 실측으로 확인했다.

### 포트폴리오 포인트

- 잘못된 수동 성능 측정값을 폐기하고 자동 benchmark 구축
- Collector 수락과 DB/Pipeline 완료 시간을 독립적으로 측정
- 단일 benchmark가 아닌 5회 반복으로 성능 변동성 확인
- 최대값이 아닌 중앙값을 baseline으로 사용
- 모든 성능 테스트에서 데이터 누락과 queue drain을 동시에 검증

### 이력서 문장 후보

OpenTelemetry 수집 파이프라인의 Collector 수락 처리량과 TimescaleDB 저장 완료 처리량을 분리 측정하고 반복 benchmark를 자동화하여, 5회 데이터 정합성 검증과 함께 synthetic workload 기준 약 1.24K spans/s의 End-to-End 처리량 중앙값을 확보

---

## 60초 Sustained Telemetry Ingest 실험

짧은 burst benchmark만으로 운영 처리능력을 판단하지 않고, 목표 ingest rate를 일정하게 유지하는 부하 발생기와 CPU·memory·DB connection·Collector queue 관측기를 직접 구성하여 60초 sustained workload를 검증했다.

500 spans/s를 정확히 60초 유지하여 총 30,000 synthetic Span을 전송했고, 600개 OTLP 요청 전체 성공, TimescaleDB 30,000건 전량 저장, Collector refused 0 및 최종 queue drain을 확인했다.

또한 Collector queue가 이미 0인 시점에도 DB count가 29,450건으로 남아 있다가 이후 30,000건이 되는 현상을 관찰하여, queue drain과 DB persistence 완료를 동일하게 판단하면 안 된다는 점을 실제 테스트로 확인했다.

### 포트폴리오 포인트

- 순간 burst가 아닌 일정 rate의 sustained-load generator 구현
- sender schedule lag를 측정하여 부하 발생기 자체의 정확도 검증
- 부하와 동시에 Backend/Collector/TimescaleDB CPU·memory 자동 수집
- DB connection과 Collector queue 상태를 함께 관찰
- 30,000건 데이터 정합성과 refused/queue drain 동시 검증
- 관측 sampling 한계를 고려해 결과를 과도하게 해석하지 않음

### 이력서 문장 후보

OpenTelemetry 수집 파이프라인에 500 spans/s의 부하를 60초간 지속하고 Backend·Collector·TimescaleDB 자원 사용량과 queue 상태를 동시에 계측하여, 30,000 Span 전량 저장 및 Collector refused 0을 검증하고 sustained-load 성능 기준선을 구축

---

## 단계적 Sustained Load에서 첫 병목 신호 발견

AeroTrace의 sustained ingest workload를 500 spans/s에서 750 spans/s로 단계적으로 증가시키면서 단순 성공 여부뿐 아니라 CPU, memory, DB connection, Collector queue 및 데이터 정합성을 비교했다.

750 spans/s를 60초 동안 정확히 유지해 45,000 Span 전량 저장과 refused 0을 검증했지만, 500 spans/s에서는 관찰되지 않았던 Collector queue backlog가 처음 나타났고 TimescaleDB CPU도 비선형적으로 증가하는 현상을 확인했다.

이를 근거로 다음 부하를 즉시 높이지 않고 queue와 DB CPU time-series의 상관관계를 먼저 분석하는 방향을 선택했다.

### 포트폴리오 포인트

- 부하를 단계적으로 증가시키며 성능 경계 탐색
- 데이터 정합성 PASS와 내부 backlog 발생을 별개로 판단
- 최대 처리량 숫자를 만들기 위해 무작정 부하를 높이지 않고 병목 징후 발생 시점에서 원인 분석으로 전환
- Collector queue와 TimescaleDB 자원 사용량을 함께 관측
- queue metric 단위를 확인하기 전 span 수로 임의 환산하지 않음

### 이력서 문장 후보

OpenTelemetry 수집 파이프라인의 sustained workload를 500→750 spans/s로 단계적으로 증가시키며 데이터 정합성과 자원 사용량을 비교하고, 45,000 Span 전량 저장을 유지하는 동시에 Collector queue backlog와 TimescaleDB CPU의 비선형 증가를 포착해 병목 분석 지점을 식별

---

## Sustained Load 단계적 병목 탐색 — 500 → 750 → 875 spans/s

짧은 burst benchmark의 최대 처리량만 기록하지 않고 일정한 telemetry ingest rate를 유지하는 sustained test를 500, 750, 875 spans/s로 단계적으로 수행했다.

875 spans/s에서 60초 동안 52,500 Span을 전량 저장하고 요청 실패 및 Collector refusal 0을 확인했다.

또한 Backend runtime 로그를 실제 workload와 대조하여 Sender의 1,050개 작은 OTLP 요청이 Collector batching을 통해 61개의 Backend 요청으로 합쳐지는 것을 검증했다.

Backend 요청 평균은 860.66 Span, 최대는 900 Span이었으며 현재 JDBC batch size 1000을 초과한 요청은 없었다.

750 spans/s에서 TimescaleDB CPU peak와 순간 queue가 관찰됐지만 더 높은 875 spans/s에서는 재현되지 않았다. 이를 통해 단일 성능 샘플을 병목으로 단정하지 않고 더 높은 workload에서 재검증한 뒤 해석을 수정하는 경험을 확보했다.

### 포트폴리오 포인트

- 500 → 750 → 875 spans/s 단계적 sustained-load 검증
- Sender rate와 schedule lag를 측정해 부하 발생기 정확도 검증
- Collector batching 전후 request 수를 Backend runtime 로그로 검증
- Collector queue, DB CPU, memory, JDBC connection을 workload와 함께 분석
- 측정값 하나만으로 병목을 단정하지 않고 반복 실험으로 기존 가설을 수정
- Collector batch와 JDBC batch 경계를 실제 runtime 데이터로 추적

### 이력서 문장 후보

OpenTelemetry telemetry 수집 파이프라인에 500→750→875 spans/s의 sustained workload를 단계적으로 적용하고, 875 spans/s에서 52,500 Span 전량 저장과 refused 0을 검증했으며 Collector batching을 통해 1,050개 요청이 61개 Backend 요청으로 병합되는 실제 처리 구조를 runtime 로그로 분석

---

## 동일 부하 반복을 통한 성능 가설 검증

1,000 spans/s 테스트의 첫 실행에서 TimescaleDB CPU spike와 약 25초간 지속되는 1,000-span Collector queue가 관찰됐지만 이를 즉시 시스템 처리 한계로 판단하지 않았다.

동일 조건을 두 차례 추가 실행한 결과 두 실행 모두 60,000 Span 전량 저장과 최종 queue drain에 성공했으며, 5초 resource sampling에서는 queue backlog가 전혀 관찰되지 않았다.

또한 Run2와 Run3의 full-rate TimescaleDB CPU 평균이 각각 약 25.88%, 26.06%로 재현되면서 첫 실행의 CPU spike와 queue 현상이 지속적인 saturation이 아니라 간헐적인 변동일 가능성이 높다는 근거를 확보했다.

### 포트폴리오 포인트

- 단일 benchmark 결과를 최대 처리량으로 과대해석하지 않음
- 동일 조건 반복 실험으로 성능 결과의 재현성 검증
- CPU maximum보다 steady-state 구간을 분리하여 비교
- Collector queue의 존재와 지속적인 queue 증가를 구분
- 최초 가설과 후속 실험이 다를 경우 측정 결과에 맞춰 결론 수정
- Collector batch와 JDBC batch 경계를 실제 runtime workload로 검증

### 이력서 문장 후보

OpenTelemetry 수집 파이프라인에서 1,000 spans/s sustained workload를 3회 반복 검증하여 각 60,000 Span 전량 저장을 확인하고, 최초 실행에서 관찰된 queue 및 CPU spike를 후속 반복 실험과 steady-state CPU 분석으로 재검증해 단일 benchmark 결과의 과대해석을 방지

---

## Collector Batch와 JDBC Batch 경계의 실제 Runtime 분석

1,125 spans/s sustained workload를 통해 Collector와 Backend의 서로 다른 batch 설정이 실제 runtime에서 어떻게 상호작용하는지 분석했다.

Sender가 50 Span 단위로 telemetry를 보내고 Collector batch threshold가 1024인 환경에서 Backend runtime 로그를 분석한 결과 65개 Backend 요청 중 63개가 1050 Span으로 형성됐다.

이는 20개의 sender batch는 1000 Span이지만 21개가 합쳐지는 순간 1050 Span이 되어 Collector threshold를 넘는 구조와 일치했다.

Backend JDBC batch-size는 1000이므로 현재 source 기준 대부분의 1050-span request가 1000 + 50 두 JDBC chunk로 분리되는 경계에 진입했다.

그럼에도 67,500 Span 전량 저장, failed request 0, 최종 queue drain을 확인했으며 queue가 시간에 따라 증가하지 않고 한 batch 수준에서 반복적으로 drain되는 것을 time-series로 검증했다.

### 포트폴리오 포인트

- 설정값만 읽지 않고 runtime request distribution으로 실제 batch 동작 검증
- Sender batch 50, Collector threshold 1024, JDBC batch 1000의 상호작용 분석
- Backend request size로부터 JDBC chunk 실행 단위를 계산
- queue 존재와 지속적인 queue 증가를 구분
- CPU 평균과 median을 함께 비교하여 순간 spike가 평균에 미치는 영향 분석
- 설정 최적화를 서두르지 않고 실제 문제 발생 여부를 먼저 측정

### 이력서 문장 후보

OpenTelemetry 수집 파이프라인에서 1,125 spans/s sustained workload를 검증하고, Sender batch 50·Collector batch threshold 1024·JDBC batch 1000의 경계로 인해 Backend 요청 65개 중 63개가 1050 Span으로 형성되는 구조를 runtime 로그로 분석하여 실제 DB batch 처리 단위를 계측

---

## 1,250 spans/s까지 Sustained Telemetry 처리 범위 확장

AeroTrace의 synthetic telemetry ingest를 1,250 spans/s까지 단계적으로 증가시켜 60초 동안 75,000 Span 전량 저장, failed request 0, 최종 Collector queue drain을 검증했다.

1,125 spans/s부터 Sender batch 50과 Collector batch threshold 1024의 조합으로 대부분의 Backend request가 1050 Span으로 형성됐으며, JDBC batch-size 1000에 의해 현재 source 기준 1000 + 50 두 chunk로 분리되는 패턴이 1,250 spans/s에서도 재현됐다.

그럼에도 1,250 spans/s의 TimescaleDB steady-state CPU 평균은 약 31.36%로 1,125 spans/s와 거의 동일했고, Collector queue도 테스트 초반 한 batch 수준에서 유지된 뒤 완전히 drain됐다.

### 포트폴리오 포인트

- sustained workload를 1,250 spans/s까지 단계적으로 확장
- Collector batch threshold와 Sender input batch의 정수 경계가 실제 1050-span Backend request를 만드는 현상 검증
- JDBC batch-size를 초과하는 request가 대부분인 조건에서도 데이터 정합성과 자원 사용량 측정
- queue 존재 자체와 시간에 따른 backlog 증가를 구분
- 더 높은 workload에서도 CPU 평균이 악화되지 않는지 비교하여 단일 spike가 아닌 steady-state 기준으로 판단

### 이력서 문장 후보

OpenTelemetry 기반 수집 파이프라인의 sustained workload를 1,250 spans/s까지 확장하여 75,000 Span 전량 저장과 failed request 0을 검증하고, Collector batch 1024와 JDBC batch 1000 경계에서 생성되는 1050-span request 및 queue·DB CPU 변화를 runtime 데이터로 분석

---

## 처리 성공과 자원 Headroom을 분리한 성능 경계 분석

1,375 spans/s sustained workload에서 82,500 Span 전량 저장에 성공했지만 TimescaleDB CPU가 1,250 spans/s보다 증가하는 현상을 관찰했다.

단일 CPU spike를 병목으로 단정하지 않고 동일 조건을 반복 검증한 결과 첫 실행의 83% peak는 재현되지 않았지만, 두 1,375 runs 모두 1,250보다 높은 steady-state CPU 수준을 보여 DB 처리 비용 증가 추세 자체는 재현됨을 확인했다.

동시에 Collector queue는 한 batch 수준에서 반복적으로 발생했지만 매번 정상 drain됐으며 지속적인 backlog 증가는 없었다.

이를 통해 “요청 성공 여부”, “queue 존재 여부”, “queue 증가 여부”, “CPU headroom”을 분리하여 sustained throughput 경계를 판단했다.

### 이력서 문장 후보

OpenTelemetry 수집 파이프라인의 sustained workload를 1,375 spans/s까지 확장하고 동일 조건 반복 실험을 통해 82,500 Span 전량 저장을 검증하는 동시에 TimescaleDB steady-state CPU 증가 추세와 비누적 Collector queue 패턴을 분리 분석하여 성능 headroom을 측정

---

## 1,500 spans/s Sustained Telemetry 처리 검증

AeroTrace telemetry ingest workload를 단계적으로 1,500 spans/s까지 증가시켜 60초 동안 총 90,000 Span 전량 저장과 Sender failed request 0을 검증했다.

Collector batch processor에 의해 Sender의 1,800개 OTLP request가 Backend에서는 86개 request로 감소했으며, 85개 요청이 1050 Span으로 형성됐다.

현재 Backend JDBC batch-size가 1000이므로 대부분의 request가 source 기준 1000 + 50 두 JDBC chunk로 처리되는 조건에서도 데이터 정합성과 pipeline drain을 유지했다.

1,375 spans/s에서 TimescaleDB CPU 증가가 관찰됐지만 더 높은 1,500 spans/s에서는 steady-state 평균 CPU가 약 34.47%로 낮아져 단일 workload 결과를 saturation으로 과대해석하지 않고 단계적·반복 측정을 통해 성능 경계를 탐색했다.

### 포트폴리오 포인트

- sustained ingest를 1,500 spans/s까지 단계적으로 검증
- 90,000 Span 전량 저장 확인
- Collector batching으로 1,800 → 86 Backend request 감소 검증
- 대부분의 Backend request가 JDBC batch 경계를 넘는 조건 측정
- queue의 존재와 증가형 backlog를 구분
- 단일 CPU spike가 아닌 steady-state와 반복 결과로 병목 판단

### 이력서 문장 후보

OpenTelemetry 기반 APM 수집 파이프라인의 sustained workload를 1,500 spans/s까지 단계적으로 확장하여 60초간 90,000 Span 전량 저장을 검증하고, Collector batching 및 JDBC batch 경계에서의 queue·DB CPU·실제 Backend request 분포를 계측해 처리 headroom을 분석

---

## 반복 실험으로 DB Headroom 감소 구간 식별

AeroTrace의 sustained telemetry ingest를 1,625 spans/s까지 증가시킨 뒤 동일 조건으로 반복 검증했다.

두 실험 모두 97,500 Span 전량 저장, failed request 0 및 최종 Collector queue drain에 성공했다.

동시에 TimescaleDB steady-state CPU 평균이 각각 약 44.65%, 47.11%로 나타나 1,500 spans/s의 약 34.47%보다 높은 CPU 비용이 반복적으로 확인됐다.

단일 88% CPU spike를 병목으로 단정하지 않고 average, median, 반복 실행 결과와 Collector queue 추세를 함께 분석하여 처리 성공과 자원 headroom 감소를 구분했다.

### 포트폴리오 포인트

- 동일 부하 반복으로 단일 benchmark 결과의 변동성 검증
- 처리 성공과 CPU headroom을 별도 성능 지표로 관리
- queue 존재와 지속 backlog 증가를 구분
- 높은 CPU peak 하나가 아닌 average/median 및 재현성으로 병목 판단
- 실제 saturation 증거가 나오기 전에 성급한 tuning을 하지 않는 측정 기반 접근

### 이력서 문장 후보

OpenTelemetry 수집 파이프라인을 1,625 spans/s까지 단계적으로 부하 테스트하고 동일 조건 반복 실험으로 97,500 Span 전량 저장을 검증하는 동시에 TimescaleDB steady CPU 상승을 재현하여 처리 한계와 자원 headroom 감소를 구분 분석

---

## 2,000 spans/s Sustained Telemetry Ingest 검증

AeroTrace telemetry ingest를 설정 변경 없이 단계적으로 2,000 spans/s까지 증가시켜 60초 동안 총 120,000 Span 전량 저장을 검증했다.

2,400개의 Sender OTLP request가 Collector batching을 거쳐 Backend에서는 115개 request로 감소했으며, 대부분의 Backend request가 1050 Span으로 형성됐다.

Backend JDBC batch-size 1000 기준 대부분의 요청이 현재 source에서 1000 + 50 두 chunk로 처리되는 조건에서도 failed request와 Collector refused 없이 전체 데이터를 저장했다.

1,875 spans/s부터 standing queue가 관찰됐지만 2,000 spans/s에서도 sampled queue가 1050보다 증가하지 않았고 반복적으로 0까지 drain되어 queue 존재와 실제 증가형 backlog를 분리해서 분석했다.

TimescaleDB CPU는 약 50%대 수준까지 증가했지만 queue 증가, refused, 정합성 실패가 발생하지 않아 CPU 사용량만으로 saturation을 단정하지 않고 처리 성공·queue 추세·DB headroom을 함께 평가했다.

### 이력서 문장 후보

OpenTelemetry 기반 APM 수집 파이프라인을 synthetic sustained workload 기준 2,000 spans/s까지 단계적으로 검증하여 60초간 120,000 Span 전량 저장과 failed/refused 0을 확인하고, Collector queue·TimescaleDB CPU·실제 Backend/JDBC batch 동작을 계측해 처리 headroom을 분석

---

## Sustained Telemetry Ingest 3,000 spans/s 검증

OpenTelemetry 기반 AeroTrace ingest pipeline의 sustained load를 단계적으로 증가시키고 동일 조건 반복 테스트를 통해 synthetic workload 기준 3,000 spans/s까지 검증했다.

3,000 spans/s × 60초 테스트를 2회 수행해 각 실행에서 180,000 Span 전량 저장, failed request 0, Collector refused 0, 최종 queue drain을 확인했다.

TimescaleDB는 약 80% 전후 CPU의 high-load 영역에 진입했고 Collector에는 한 batch 수준의 standing queue가 전체 부하 구간에서 반복적으로 관찰됐지만, queue가 시간에 따라 증가하는 growing backlog는 발생하지 않았다.

단일 CPU spike 또는 순간 queue 값을 capacity 한계로 단정하지 않고 동일 조건 반복 측정, median과 average 비교, queue 성장 추세, 최종 데이터 정합성을 함께 사용해 실제 saturation 여부를 판단했다.

또한 benchmark timestamp 경계 때문에 CPU sample이 누락되는 측정 도구 오류를 발견하고 sampling slot 기반 분석 및 누락 검증을 추가해 성능 측정 신뢰도를 개선했다.

### 이력서 문장 후보

제한된 서버 환경에서 OpenTelemetry APM 수집 파이프라인의 sustained 부하를 단계적으로 측정하고 동일 조건 반복 검증을 수행해 synthetic workload 기준 3,000 spans/s에서 60초간 180,000 Span 전량 저장과 failed/refused 0을 확인했으며, TimescaleDB CPU·Collector queue·JDBC batch를 계측해 데이터 정합성을 유지한 상태에서의 실제 처리 headroom을 분석

---

## 성능 한계 탐색 — 3,250 spans/s 고부하 경계

synthetic sustained telemetry ingest 부하를 단계적으로 증가시키고 동일 조건 반복 측정을 통해 3,250 spans/s까지 검증했다.

3,250 spans/s × 60초 테스트를 3회 수행해 매 실행 195,000 Span 전량 저장, failed/refused 0, 최종 queue drain을 확인했다.

단순히 성공 여부만 확인하지 않고 TimescaleDB CPU, Collector queue, sender latency와 scheduling lag, JDBC batch 구조를 함께 측정했다.

TimescaleDB CPU median이 3회 모두 약 89~91% 수준으로 재현됐고, 한 실행에서는 Collector queue가 3150 → 2100으로 연속 유지되는 경계 신호가 발생했다.

해당 queue 현상과 sender stall이 다른 두 실행에서는 재현되지 않았기 때문에 이를 즉시 최대 처리량으로 단정하지 않고, 동시에 높은 DB CPU와 사전에 정의한 중단 기준을 고려해 추가 rate 상승을 중단하고 병목 분리 측정 단계로 전환했다.

포트폴리오 핵심 포인트:

- benchmark 완료 조건을 DB count + Collector queue/in-flight drain으로 정의
- 동일 조건 반복 실행으로 일시적 spike와 재현 가능한 현상 구분
- 평균뿐 아니라 median 및 time-series 기반으로 고부하 상태 판단
- 측정 결과 없이 tuning하지 않고 baseline을 먼저 확보
- 처리 성공률과 실제 시스템 headroom을 구분

---

# AeroTrace Career Log

> 사용자가 직접 구현을 적용하고 실행·검증·측정한 경험을 이력서, 포트폴리오, 면접, 기술 블로그 소재로 보존한다.
>
> 측정하지 않은 숫자는 만들지 않고, 실제 테스트 결과와 보존된 로그를 근거로 작성한다.

---

## 2026-08-13 — pgJDBC Batch 저장 병목 분석 및 최적화

### 경험 요약

AeroTrace의 OpenTelemetry telemetry ingest를 N100 홈서버에서 3,250 spans/s로 sustained load test하던 중 60초 동안 195,000 Span 전량 저장과 failed request 0은 유지했지만 TimescaleDB CPU가 높은 수준까지 증가하는 현상을 확인했다.

단순히 DB가 느리다고 판단하거나 JDBC batch size를 임의로 변경하지 않고 다음 순서로 병목 범위를 좁혔다.

```text
Collector request distribution 분석
→ PostgreSQL wait sampling
→ Controller timing
→ JDBC writer timing
→ JSON / row preparation timing
→ PreparedStatement binding timing
→ JdbcTemplate.batchUpdate residual 분석
→ pgJDBC batch rewrite 가설
→ correctness test
→ 반복 A/B
→ 운영 적용
→ 임시 계측 제거
```

### 실제로 확인한 Runtime 구조

Sender:

```text
3,250 spans/s
50 spans/request
65 requests/sec
```

Collector:

```text
send_batch_size=1024
```

실제 Backend 요청은 대부분 다음 크기로 형성됐다.

```text
1,050 Span
```

Backend JDBC batch size가 1,000이므로 대부분:

```text
1,000 + 50
```

두 JDBC chunk로 저장되는 실제 실행 구조를 runtime 로그로 확인했다.

### 초기 병목 현상

3,250 spans/s baseline:

```text
Requested spans: 195000
Accepted spans: 195000
Failed requests: 0
DB count: 195000/195000
Final queue: 0
Final in-flight: 0
```

DB CPU 대표 run:

```text
avg=94.29%
median=94.05%
max=100.89%
```

처리 자체는 성공했지만 DB CPU headroom이 거의 남지 않았다.

### PostgreSQL Wait 분석 경험

DB CPU가 높다는 이유만으로 lock 또는 disk I/O 병목이라고 단정하지 않고 PostgreSQL wait event를 별도로 sampling했다.

확인 결과:

```text
active avg ≈ 0.94
active max = 1
대부분 active_no_wait
lock wait 없음
지속적인 I/O wait 근거 없음
```

이를 통해 높은 CPU와 PostgreSQL wait를 별개의 증거로 다루는 경험을 확보했다.

### Java 저장 경로 계측

1,050-span 요청 185개를 분석했다.

```text
writer_total median       288.75ms
prepare_rows median         1.64ms
batch_update median        286.84ms
bind median                  1.19ms
batch_after_bind median    285.54ms
```

`batchUpdate` 전체 중 parameter binding:

```text
median share = 약 0.40%
```

bind 이후 residual:

```text
median share = 약 99.60%
```

이를 통해 다음을 주요 병목에서 제외했다.

```text
OTLP JSON parsing
JSONB serialization
PreparedSpanRow 생성
PreparedStatement parameter binding
```

병목을 JDBC driver / PostgreSQL batch 실행 경로로 좁혔다.

### 잘못된 최적화를 피한 경험

parameter binding이 느릴 것이라는 추측만으로 bind 코드를 최적화했다면 전체 0.4% 수준의 부분을 개선하려 했을 가능성이 있다.

실제 timing을 추가해 병목을 분리함으로써 우선순위가 낮은 Java-side 최적화를 피했다.

### `reWriteBatchedInserts` Correctness 검증

성능 옵션을 바로 운영에 적용하지 않고 데이터 정합성을 먼저 검증했다.

시나리오:

```text
신규 50개
동일 50개 재전송
기존 25개 + 신규 25개
```

최종 DB:

```text
75 rows
```

예상과 정확히 일치했다.

중복 데이터 자체는 추가 저장되지 않았다.

동시에 중요한 trade-off도 발견했다.

신규 50개:

```text
inserted=0
duplicates=0
unknown=50
```

동일 50개 재전송:

```text
inserted=0
duplicates=50
unknown=0
```

중복 25 + 신규 25:

```text
inserted=0
duplicates=0
unknown=50
```

즉 데이터 idempotency는 유지되지만 pgJDBC rewrite 후에는 개별 row의 inserted / duplicate 결과를 정확하게 분류하지 못할 수 있음을 실제 테스트로 확인했다.

### 반복 성능 A/B

한 번의 benchmark를 성과로 사용하지 않고 baseline과 rewrite 조건을 각각 총 3회 측정했다.

조건:

```text
3,250 spans/s
60초
195,000 spans/run
```

조건별 run 중앙값:

| 지표 | rewrite=false | rewrite=true | 변화 |
|---|---:|---:|---:|
| JDBC Writer median | 288.75ms | 107.07ms | -62.9% |
| BatchUpdate median | 286.84ms | 104.81ms | -63.5% |
| After-bind median | 285.54ms | 103.47ms | -63.8% |
| DB CPU avg | 94.36% | 37.55% | -60.2% |
| DB CPU median | 94.05% | 37.63% | -60.0% |

Collector size-trigger 예상 주기 약 323.08ms를 초과한 writer:

```text
baseline:
57 / 555

rewrite:
0 / 554
```

모든 sustained run에서:

```text
195000 / 195000 Span 저장
failed request = 0
final queue = 0
final in-flight = 0
```

을 확인했다.

### 성능 결과를 과장하지 않는 경험

이번 테스트에서는 최대 처리량을 다시 측정하지 않았다.

따라서 다음 표현은 사용하지 않는다.

```text
처리량 63% 증가
최대 ingest 성능 63% 향상
```

실제 측정으로 말할 수 있는 것은 다음이다.

> 동일한 3,250 spans/s workload에서 JDBC writer 중앙값 약 63%, TimescaleDB CPU 약 60% 감소를 반복 측정으로 확인했다.

### 초기 Benchmark와 다른 결과를 다룬 경험

초기 Windows Docker persistence-only benchmark에서는 `reWriteBatchedInserts=true`가 일관된 성능 개선을 보여주지 않아 적용을 보류했다.

그러나 실제 N100 sustained workload에서는 큰 차이가 확인됐다.

두 실험의 차이:

```text
초기
Windows 개발 PC
Docker Desktop
Persistence-only
Collector 없음
낮은 DB 포화도

후속
N100 운영 후보 서버
Collector 포함
3,250 spans/s sustained load
1,050-span runtime request 중심
높은 DB CPU
```

따라서 이전 결과를 감추거나 잘못된 실험으로 취급하지 않고:

```text
초기 측정 → 적용 보류
실제 workload → 병목 발견
운영 후보 환경 재측정
→ 결정 변경
```

과정을 그대로 기록했다.

### 최종 운영 적용

적용:

```text
reWriteBatchedInserts=true
```

Commit:

```text
e8a1408 PostgreSQL 배치 INSERT 재작성 최적화 적용
```

홈서버 Runtime:

```text
AEROTRACE_DB_URL=jdbc:postgresql://timescaledb:5432/aerotrace?reWriteBatchedInserts=true
```

배포 후:

```text
status=running
health=healthy
restart=0
```

1-span smoke:

```text
Requested=1
Accepted=1
Failed=0

received=1
inserted=1
duplicates=0
unknown=0
```

### 진단 코드 정리 경험

성능 분석을 위해 운영 요청 경로에 추가했던 `System.nanoTime()` timing instrumentation은 분석 완료 후 제거했다.

제거:

```text
Controller parse timing
Controller ingestion timing
Writer total timing
prepareRows timing
batchUpdate timing
row별 bind timing
chunk timing
성능 분석 INFO 로그
```

Benchmark 전용 timing 코드는 유지했다.

Commit:

```text
5152cc1 성능 분석용 임시 계측 코드 제거
```

배포 후에도 ingest가 정상 동작하고 성능 분석용 INFO 로그가 더 이상 발생하지 않는 것을 확인했다.

---

## 이력서 성과 문장 후보

### 상세 버전

OpenTelemetry 기반 APM 수집 파이프라인의 3,250 spans/s sustained workload에서 JDBC 저장 경로를 JSON 직렬화·parameter binding·batch 실행 단계로 계측해 pgJDBC batch 실행 병목을 식별하고, `reWriteBatchedInserts` 적용 및 조건별 3회 반복 A/B 검증을 통해 동일 처리량에서 JDBC writer 중앙값 약 63%, TimescaleDB CPU 약 60% 감소를 확인하면서 매 run 195,000 Span 전량 저장과 중복 방지 정합성을 유지

### 중간 길이 버전

3,250 spans/s OpenTelemetry ingest 환경에서 JDBC 저장 병목을 단계별 계측으로 분석하고 pgJDBC batch rewrite를 적용하여 동일 부하에서 writer 처리시간 약 63%, DB CPU 약 60% 감소를 반복 A/B로 검증

### 짧은 버전

OpenTelemetry ingest JDBC 병목을 계측·분석하고 pgJDBC batch rewrite 적용으로 동일 3,250 spans/s 부하에서 writer 약 63%, DB CPU 약 60% 감소를 반복 검증

---

## 포트폴리오 강조점

### 단순 기술 사용이 아닌 문제 해결

단순히 다음 기술을 사용했다는 것이 핵심이 아니다.

```text
Spring JDBC
PostgreSQL
TimescaleDB
OpenTelemetry
```

핵심 경험은:

```text
높은 DB CPU 발견
→ 성능 가설 수립
→ PostgreSQL wait 측정
→ Java 저장 구간 계측
→ 병목 범위 축소
→ pgJDBC 동작 분석
→ correctness 검증
→ 반복 A/B
→ trade-off 결정
→ 운영 반영
→ 진단 코드 제거
```

까지 직접 수행한 것이다.

### 채용 담당자가 평가할 수 있는 부분

- 실제 sustained workload 생성
- 부하 발생기의 정확도 확인
- DB CPU와 Collector queue 동시 분석
- PostgreSQL wait event 분석
- Java 애플리케이션 내부 timing instrumentation 설계
- 성능 병목 단계적 축소
- 추측 기반 최적화 방지
- correctness-before-performance 접근
- 동일 조건 반복 A/B
- 성능과 observability 정확성 trade-off 판단
- 운영 설정 적용
- 배포 후 smoke / health 검증
- temporary instrumentation cleanup

---

## 보존해야 할 증거

### Benchmark 결과

```text
/home/huning/aerotrace/benchmark-results/rewrite-ab-repeat-20260813T001308Z
```

### 반드시 보존할 출력

```text
baseline 3250 spans/s summary
rewrite 3250 spans/s summary
ABBA 반복 결과표
DB CPU summary
Backend 1050-span timing 통계
bind share 0.40%
after-bind share 99.60%
PostgreSQL wait sampling
rewrite correctness 로그
최종 DB count=75
195000/195000 결과
Collector queue=0
Collector in-flight=0
runtime DB URL
Backend healthy
restart=0
```

### Commit

```text
e8a1408 PostgreSQL 배치 INSERT 재작성 최적화 적용
5152cc1 성능 분석용 임시 계측 코드 제거
```

---

## 예상 면접 질문

### Q1. 왜 DB CPU가 95%라는 사실만 보고 PostgreSQL이 병목이라고 결론 내리지 않았나요?

CPU 수치는 자원 사용 상태를 보여주지만 어느 SQL 단계나 어떤 대기 원인이 문제인지 알려주지 않는다.

따라서 PostgreSQL wait sampling과 애플리케이션 내부 timing을 함께 측정해 lock, persistent I/O wait, Java serialization, binding 등의 후보를 하나씩 제외했다.

### Q2. `batchUpdateNanos`를 PostgreSQL INSERT 실행시간이라고 부르면 안 되는 이유는?

`JdbcTemplate.batchUpdate()`에는 PostgreSQL 서버 실행시간뿐 아니라 다음이 포함될 수 있다.

```text
JdbcTemplate 내부 처리
JDBC driver 처리
parameter batch 관리
network
PostgreSQL 실행
index 검사
ON CONFLICT
결과 수신
update count 변환
```

따라서 별도 server-side measurement 없이 전체 값을 SQL 실행시간이라고 표현하면 안 된다.

### Q3. parameter binding이 병목이 아니라는 것을 어떻게 확인했나요?

`BatchPreparedStatementSetter.setValues()` 안의 실제 `persistenceSupport.bind()` 호출을 row별로 timing하고 요청 단위로 합산했다.

1,050-span 요청에서:

```text
bind median = 1.19ms
batchUpdate median = 286.84ms
```

로 binding은 약 0.40%였다.

### Q4. `reWriteBatchedInserts`가 어떤 trade-off를 만들었나요?

실제 DB 데이터 중복 방지는 유지됐지만 rewritten batch에서 JDBC driver가 개별 row별 성공 여부를 알 수 없는 경우 `SUCCESS_NO_INFO`가 발생해 inserted / duplicate 분류가 `unknown`으로 변할 수 있었다.

### Q5. 왜 이 상태에서도 rewrite를 채택했나요?

현재 inserted / duplicate count는 외부 OTLP API 계약이 아니고 실제 데이터 correctness는 Unique Index와 `ON CONFLICT DO NOTHING`으로 유지된다.

또한 `unknownSuccessCount`가 이미 모델링되어 있다.

반면 실제 운영 후보 workload에서는 writer 시간과 DB CPU 감소가 반복적으로 매우 크게 확인됐다.

### Q6. 왜 한 번의 A/B 결과로 결정하지 않았나요?

성능 측정은 OS scheduling, cache, background work, DB 상태 등에 영향을 받을 수 있기 때문이다.

최초 A/B 후 ABBA 순서의 추가 반복을 수행해 조건별 총 3회 결과가 같은 방향으로 재현되는지 확인했다.

### Q7. 왜 이번 결과를 처리량 63% 증가라고 표현하면 안 되나요?

측정한 것은 동일 3,250 spans/s 입력에서 writer 실행시간과 CPU 변화다.

최대 안정 ingest rate 자체를 rewrite 적용 후 다시 탐색하지 않았기 때문에 최대 처리량 개선 수치는 알 수 없다.

### Q8. 왜 초기 Windows benchmark와 이번 N100 결과가 다른데 둘 다 유효하다고 보나요?

성능은 실행 환경과 workload에 따라 달라진다.

초기 benchmark는 persistence-only였고 DB 포화도가 낮았지만 이번 테스트는 실제 Collector를 포함한 sustained ingest와 높은 DB CPU 조건이었다.

결과가 다른 것이 모순이라기보다 workload-specific optimization의 사례다.

### Q9. 다음 병목이 발생하면 바로 Kafka를 도입할 건가요?

아니다.

먼저 현재 Collector queue, DB CPU, connection pool, WAL, disk, JDBC/COPY 등의 실제 병목을 측정한다.

Kafka는 현재 구조에서 해결할 수 없는 buffering, decoupling, scale 요구가 실제로 나타났을 때 검토한다.

---

## 기술 블로그 후보

### 제목

**JDBC Batch를 쓰는데 왜 DB CPU가 95%일까? OpenTelemetry 3,250 spans/s 병목을 추적한 과정**

### 해결한 문제

JDBC batch를 이미 사용하고 있음에도 실제 sustained ingest에서 TimescaleDB CPU가 거의 포화되는 문제를 추측이 아니라 계측으로 추적했다.

### 핵심 메시지

```text
Batch를 사용한다
≠
Batch가 효율적으로 실행되고 있다
```

라이브러리 기능을 사용했다는 사실보다 실제 runtime에서 어떻게 실행되는지 측정하는 것이 중요하다.

### 글 구성

```text
1. 3,250 spans/s까지 부하를 올린 이유
2. 195,000 Span은 저장됐는데 DB CPU가 95%
3. Queue가 0이라고 병목이 없는 것은 아니다
4. Collector size-trigger cadence 계산
5. PostgreSQL wait sampling
6. Controller timing
7. JDBC writer timing
8. JSON serialization 병목 제외
9. PreparedStatement binding 병목 제외
10. pgJDBC batch 실행 경로 가설
11. reWriteBatchedInserts correctness test
12. SUCCESS_NO_INFO 발견
13. 단일 A/B
14. ABBA 반복 검증
15. 약 63% writer / 60% CPU 감소
16. 왜 처리량 63% 증가라고 쓰지 않는가
17. 초기 Windows benchmark와 결과가 달랐던 이유
18. 운영 반영
19. 진단 코드 제거
20. 다음 병목에서 측정할 것
```

### 포함할 그래프

#### Writer median

```text
baseline ≈ 289ms
rewrite  ≈ 107ms
```

#### DB CPU

```text
baseline ≈ 94%
rewrite  ≈ 38%
```

#### Collector cadence 초과 요청

```text
baseline 57 / 555
rewrite   0 / 554
```

### 포함할 코드

```text
JdbcTemplate.batchUpdate
BatchPreparedStatementSetter
SpanWriteResult unknownSuccessCount
reWriteBatchedInserts JDBC URL
ON CONFLICT DO NOTHING
```

### 독자가 얻을 실무적 교훈

- 성능 문제가 생겼을 때 작은 설정부터 무작정 바꾸지 않는다.
- application timing과 DB wait를 함께 봐야 한다.
- framework method 전체 시간을 SQL 실행시간으로 착각하지 않는다.
- 성능 옵션은 correctness semantics를 바꿀 수 있다.
- micro benchmark와 실제 sustained workload는 결과가 다를 수 있다.
- 반복 측정 없이 성능 수치를 확정하지 않는다.
- 측정하지 않은 최대 처리량 향상을 이력서에 쓰지 않는다.

---

# Portfolio Checkpoint — JDBC Ingest 병목 분석과 최적화

## 직접 얻은 실무 경험

이번 단계에서 직접 경험한 것은 단순한 `reWriteBatchedInserts` 옵션 사용법이 아니다.

다음 전체 성능 분석 사이클을 경험했다.

```text
성능 이상 징후 발견
→ workload 재현
→ 측정 지표 정의
→ 병목 후보 분리
→ instrumentation 추가
→ 통계 분석
→ 가설 수정
→ correctness 검증
→ A/B benchmark
→ 반복 재현성 검증
→ trade-off 결정
→ 운영 설정 반영
→ smoke / health 확인
→ 임시 instrumentation 제거
```

## 면접관이 평가할 부분

- 성능 수치를 과대해석하지 않음
- 원인을 모르고 설정부터 변경하지 않음
- 하나의 CPU 수치가 아니라 여러 증거를 결합
- application과 DB의 경계를 이해
- idempotency를 성능보다 먼저 보호
- 라이브러리 최적화가 반환 semantics를 바꿀 수 있음을 검증
- 한 번의 benchmark가 아닌 반복 A/B 수행
- 실제 운영 후보 장비에서 재측정
- 성능 개선 뒤 코드 cleanup까지 수행

## 현재 증명 가능한 성과

다음 표현은 실제 측정 자료로 증명할 수 있다.

> 3,250 spans/s의 동일 sustained workload에서 pgJDBC batch rewrite 적용 후 JDBC writer 중앙값 약 63%, TimescaleDB CPU 약 60% 감소를 조건별 3회 반복 측정으로 확인했다.

또한 모든 테스트에서:

```text
195,000 Span/run
failed request 0
최종 Collector queue 0
최종 in-flight 0
```

을 확인했다.

## 아직 주장하면 안 되는 것

아직 다음은 측정하지 않았다.

```text
rewrite 적용 후 최대 처리량
최대 안정 spans/s
일일 최대 ingest capacity
실사용자 workload에서 동일한 60% 개선
Oracle Cloud에서도 동일한 개선
```

따라서 포트폴리오나 이력서에서 해당 수치를 추측하지 않는다.

## 다음 한 단계 높은 과제

다음 성능 단계에서는 단순히 더 높은 rate만 테스트하기보다 다음을 판단할 수 있다.

```text
rewrite 적용 후 새로운 sustained 처리 경계
Collector backlog가 지속 증가하기 시작하는 지점
DB CPU headroom 감소 지점
Hikari connection 사용량
동시 Trace 조회 + ingest 영향
WAL / disk write 변화
batch rewrite 적용 후 COPY 비교 필요성
```

현재는 rewrite 적용으로 기존에 확인한 주요 DB/JDBC 병목이 크게 완화된 상태이므로 새로운 경계를 별도의 측정으로 찾아야 한다.

---

## 학습 확인 질문

1. 왜 `batchAfterBindNanos`를 PostgreSQL INSERT 시간이라고 부르면 안 되는가?
2. 왜 `reWriteBatchedInserts=true`에서도 실제 duplicate 데이터는 안전하지만 `insertedCount`는 정확하지 않을 수 있는가?
3. 왜 이번 결과를 처리량 63% 증가라고 표현하면 안 되는가?
4. 왜 초기 Windows benchmark에서 rewrite를 보류하고 이번 N100 benchmark에서는 채택한 것이 모순이 아닌가?
5. Collector의 약 323ms cadence보다 writer가 오래 걸리는 요청이 반복되면 어떤 운영 문제가 발생할 수 있는가?

