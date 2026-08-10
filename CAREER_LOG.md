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
