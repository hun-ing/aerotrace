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

---

## Portfolio Checkpoint — OpenTelemetry Persistent Queue 장애 복구 및 Capacity 설계

### 직접 경험한 문제

OpenTelemetry Collector에서 `block_on_overflow=true`를 적용해 queue overflow 시 silent telemetry loss를 방지한 뒤, persistent queue가 실제 장애 상황에서 어느 정도 데이터를 보호할 수 있는지 직접 검증했다.

단순히 persistent queue 설정을 추가하는 데서 끝내지 않고 다음 장애를 순차적으로 재현했다.

```text
Backend 완전 중단
Collector graceful restart
Collector SIGKILL
190,000 Span backlog
queue capacity 확대
```

### 직접 검증한 내용

20,000 Span을 persistent queue에 저장한 상태에서 Collector를 정상 재시작했다.

```text
restart 전:
queue = 20,000
DB    = 0

restart 후:
Loaded queue metadata
itemsSize = 20,000

Backend 복구:
DB = 20,000 / 20,000
```

이후 Collector를 SIGKILL로 강제 종료했다.

```text
exit code = 137
```

재시작 후:

```text
Loaded queue metadata
itemsSize       = 20,000
dispatchedItems = 2
```

가 확인됐으며 최종:

```text
DB = 20,000 / 20,000
```

으로 전부 복구됐다.

### Queue Capacity 설계 경험

기존 `queue_size=50,000`은 운영 후보 workload인 3,250 spans/s에서 Backend 완전 장애를 약 15.4초밖에 흡수하지 못한다는 점을 계산했다.

목표를:

```text
3,250 spans/s에서 약 1분 완전 장애 흡수
```

로 설정했다.

필요 queue:

```text
3,250 × 60
= 195,000 spans
```

이므로:

```yaml
queue_size: 200000
```

을 후보로 선정했다.

설정을 바로 운영에 넣지 않고 임시 Collector config로 실제 용량 실험을 수행했다.

### 실제 190k Backlog 측정

```text
checkpoint  queue    allocated_bytes
baseline    0        32,768
50k         50,000   6,049,792
100k        100,000  11,894,784
150k        150,000  17,547,264
190k        190,000  21,954,560
```

190,000 Span을 queue에 보유했을 때 baseline 대비 filesystem allocated 증가량은 약 20.9 MiB였다.

Backend 장애 중:

```text
DB = 0 / 190,000
```

Backend 복구 후:

```text
DB        = 190,000 / 190,000
queue     = 0
in_flight = 0
```

을 확인했다.

### 설계 변경 결과

기존:

```text
queue_size=50,000

3,250 spans/s 기준
약 15.4초 장애 buffer
```

변경:

```text
queue_size=200,000

3,250 spans/s 기준
약 61.5초 장애 buffer
```

로 확대했다.

정식 운영 후보 설정:

```yaml
sending_queue:
  sizer: items
  queue_size: 200000
  block_on_overflow: true
  storage: file_storage/aerotrace
```

### 채용 담당자 또는 면접관이 평가할 수 있는 부분

이 경험의 핵심은 OpenTelemetry 설정값 자체가 아니다.

다음 문제 해결 과정이 중요하다.

```text
장애 시 telemetry 보존 요구 정의
→ persistent queue storage 구조 확인
→ 실제 disk 사용량 측정
→ graceful restart 장애 재현
→ SIGKILL crash 재현
→ queue metadata 기반 복구 과정 확인
→ 최종 DB 데이터 보존 검증
→ ingest rate에서 outage budget 계산
→ capacity 목표 설정
→ 190k 실제 backlog 검증
→ 운영 기본값 승격
```

운영 설정을 감으로 결정하지 않고 실제 workload, failure semantics, disk 비용을 측정해서 결정한 경험으로 설명할 수 있다.

### 보존할 자료

포트폴리오에서 다음 자료를 보존한다.

```text
graceful restart 전후 queue=20000 출력
Loaded queue metadata itemsSize=20000 로그
dispatchedItems=2 복구 로그
graceful restart DB=20000/20000 결과

SIGKILL exit=137 출력
SIGKILL 후 Loaded queue metadata 로그
SIGKILL 후 DB=20000/20000 결과

growth.tsv:
baseline
50k
100k
150k
190k

190k:
allocated_bytes=21954560
db_during=0/190000
final_db=190000/190000

queue_size:
50000 → 200000 diff

정식 설정 smoke:
sender_rc=0
DB=200/200
queue=0
in_flight=0
```

가능하면 터미널 결과와 함께 queue 증가/감소 그래프를 추후 포트폴리오 자료로 만든다.

### 이력서 성과 문장 초안

- OpenTelemetry Collector persistent queue의 graceful restart 및 SIGKILL 장애를 직접 재현해 각각 20,000/20,000 Span 복구를 검증하고, 실제 telemetry workload 기반 storage 사용량과 장애 허용 시간을 측정해 데이터 유실 방지 구조의 내구성을 검증
- 운영 후보 workload 3,250 spans/s에서 기존 50,000 Span queue가 약 15초의 완전 장애만 흡수하는 한계를 확인하고, 190,000 Span backlog의 약 21 MiB filesystem 사용량과 190,000/190,000 복구를 검증한 뒤 queue capacity를 200,000으로 확대해 약 1분의 outage buffer 확보

두 번째 문장의 수치는 이번 실험에서 직접 측정하거나 측정값에서 계산한 범위에 한정한다.

### 예상 면접 질문

```text
왜 persistent queue가 필요한가?
memory queue와 persistent queue의 차이는 무엇인가?
왜 graceful restart와 SIGKILL을 따로 테스트했는가?
SIGKILL 후 진행 중이던 exporter item은 어떻게 처리됐는가?
Dropping data 로그가 있었는데 왜 최종 데이터는 유실되지 않았는가?
queue_size=200000은 왜 선택했는가?
왜 더 크게 1,000,000으로 잡지 않았는가?
queue size를 disk size 기준으로 결정하지 않은 이유는?
190k에서 bbolt file size가 증가하지 않았는데 allocated bytes가 증가한 이유를 어떻게 해석했는가?
persistent queue만 있으면 데이터 유실이 완전히 없어지는가?
현재 아직 검증하지 않은 장애는 무엇인가?
```

### 블로그 주제

**제목**

> OpenTelemetry Persistent Queue는 정말 장애에서 데이터를 지켜줄까? Restart부터 SIGKILL까지 직접 검증해봤다

**해결한 문제**

```text
Collector 설정에 persistent queue가 있다고 해서
실제 장애에서 telemetry가 살아남는다고 확신할 수 있는가?
```

**핵심 메시지**

```text
설정 존재 여부보다 실제 장애 복구 실험이 중요하다.
queue 용량은 ingest rate와 목표 outage duration에서 역산해야 한다.
```

**글 구성**

```text
1. 왜 persistent queue를 검증했는가
2. AeroTrace Collector storage 구조
3. Backend pause로 backlog 생성
4. 실제 disk 사용량 측정
5. graceful restart 실험
6. shutdown 중 Dropping data 로그 분석
7. SIGKILL crash 실험
8. queue_size=50k의 실제 outage budget
9. 200k capacity 목표 선정
10. 50k → 190k storage 성장 실험
11. 운영 기본값 변경
12. 아직 보장할 수 없는 failure scenario
```

### 다음에 경험할 한 단계 높은 과제

현재 검증은 Collector process 수준의 장애까지다.

다음 단계에서는 다음 중 우선순위가 높은 failure boundary를 검증한다.

```text
queue 완전 포화 이후 failure semantics
file_storage write 실패
host reboot 이후 persistent queue 복구
disk/storage 한계 접근 시 운영 동작
```

특히 실제 운영에서는 queue가 100%에 도달하기 전에 감지할 수 있도록 queue utilization과 persistent storage 사용량에 대한 monitoring/alerting 정책까지 연결해야 한다.

---

### Portfolio Checkpoint 추가 — Persistent Queue 완전 포화 Backpressure 검증

`queue_size=200000`을 운영 기본값으로 결정한 뒤 실제 운영 후보 workload인 3,250 spans/s에서 queue를 완전히 포화시키는 장애 실험을 수행했다.

실제 effective saturation:

```text
configured queue = 200,000
actual plateau    = 199,500
saturation time   = 62.622 sec
```

설계 시 계산했던:

```text
200,000 / 3,250
≈ 61.5 sec
```

와 실제 결과가 근접해 약 1분의 Backend 완전 장애 buffer가 실제 workload에서도 동작함을 검증했다.

queue 포화 이후 telemetry를 버리는 대신 upstream backpressure가 발생했다.

```text
Backpressure wait total      = 6,953.442 ms
Producer backpressure events = 155
Producer lag p99             = 5,503.897 ms
Send-start lag p99           = 5,996.734 ms
```

이 영향으로 목표 rate 유지에는 실패했지만:

```text
Requested spans = 211,250
Accepted spans  = 211,250
Failed requests = 0

Delivery success        = PASS
Sustained-rate validity = FAIL
```

이었다.

Backend 복구 후:

```text
DB        = 211,250 / 211,250
queue     = 0
in_flight = 0
```

으로 전체 데이터 보존을 확인했다.

Collector raw metric에서도 이전 200 Span smoke를 제외한 실험 delta가:

```text
accepted delta = 211,250
sent delta     = 211,250
refused        = 0
```

으로 확인됐다.

실험 과정에서 Collector metric helper가 모든 metric에 `data_type="traces"` label을 요구하면서 span counter를 읽지 못하고 빈 값을 0으로 처리하는 계측 오류도 발견했다.

Raw Prometheus metric을 직접 확인하여:

```text
queue gauge에는 data_type label이 존재하지만
span counter에는 해당 label이 존재하지 않는다
```

는 차이를 확인하고 잘못된 계측 결과와 실제 시스템 동작을 분리했다.

#### 이력서 성과 문장 보강

- OpenTelemetry Collector persistent queue를 3,250 spans/s workload에서 완전 포화시켜 199,500 Span effective capacity와 62.6초의 장애 buffer를 실측하고, 포화 후 upstream backpressure가 발생하는 동안에도 211,250/211,250 Span이 최종 저장되는 것을 검증
- Collector 내부 metric label 차이로 발생한 잘못된 zero metric을 raw Prometheus 데이터로 추적해 계측 오류를 식별하고, Collector counter와 최종 DB row를 교차 검증하여 실제 telemetry 유실 여부를 판단

#### 면접에서 설명할 포인트

```text
왜 queue_size=200000으로 결정했는가?
이론상 61.5초와 실제 62.6초 차이는 왜 발생했는가?
왜 실제 queue가 200000이 아니라 199500에서 멈췄는가?
Delivery PASS인데 sustained-rate FAIL인 이유는?
backpressure가 데이터 유실 문제를 어떤 문제로 바꾸는가?
sender_rc=22를 왜 전송 실패로 보지 않았는가?
internal metric이 잘못 측정된 것을 어떻게 발견했는가?
왜 DB row count까지 함께 검증했는가?
```

#### 보존할 핵심 스크린샷/로그

```text
queue=199500 stable=4
saturation_elapsed_sec=62.622

5초 hold 후 queue=199500

Producer backpressure events=155
Backpressure wait total=6953.442 ms

Requested/Accepted=211250
Failed requests=0

DB=211250/211250

raw Collector metrics:
sent_spans=211450
accepted_spans=211450
refused_spans=0
```

---

### Portfolio Checkpoint 추가 — Host Reboot 이후 Telemetry 복구 검증

OpenTelemetry Collector persistent queue의 장애 내구성을 process restart와 SIGKILL 수준에서 끝내지 않고 Host OS reboot까지 확장해서 검증했다.

Backend를 정상 stop한 상태에서 20,000 Span을 Collector persistent queue에 저장했다.

Host reboot 직전:

```text
queue = 20,000
DB    = 0 / 20,000
```

이 상태에서 실제 host를 reboot했다.

```text
sudo reboot
```

재부팅 후:

```text
Collector   = running
TimescaleDB = healthy
Backend     = exited
```

이었기 때문에 Backend가 queue를 drain하기 전에 persistent queue 복구 상태를 직접 확인할 수 있었다.

Collector startup log:

```text
Loaded queue metadata

itemsSize       = 20,000
bytesSize       = 2,234,800
dispatchedItems = 2
```

진행 중이던 두 item도:

```text
Moved items for dispatching back to queue
numberOfItems = 2
```

로 다시 queue에 복원됐다.

Backend가 여전히 중단된 상태에서:

```text
queue = 20,000
DB    = 0 / 20,000
```

을 확인했다.

Backend 복구 후:

```text
queue:
18,950
→ 15,800
→ 13,700
→ 10,550
→ 6,350
→ 3,150
→ 0

DB = 20,000 / 20,000
```

으로 전체 telemetry가 최종 저장됐다.

현재까지 직접 검증한 failure boundary:

```text
Collector graceful restart → 20,000 / 20,000
Collector SIGKILL          → 20,000 / 20,000
Host OS reboot             → 20,000 / 20,000
```

#### 포트폴리오에서 강조할 부분

단순히 OpenTelemetry persistent queue를 설정한 것이 아니라 장애 경계를 단계적으로 확장했다.

```text
process restart
→ SIGKILL
→ host reboot
```

각 단계에서:

```text
장애 전 queue 상태
DB 미저장 상태
startup queue metadata
진행 중 item 복원
queue drain
최종 DB 데이터
```

를 모두 확인했다.

특히 host reboot 실험에서는 shell과 `/tmp` 상태가 사라지는 문제를 고려해 실험 metadata를 persistent host 파일로 따로 저장하고 재접속 후 동일 telemetry를 추적했다.

#### 이력서 성과 문장 보강

- OpenTelemetry Collector persistent queue의 장애 내구성을 graceful restart, SIGKILL, Host OS reboot까지 단계적으로 검증하고, 각 장애에서 20,000/20,000 Span이 최종 DB까지 복구되는 것을 확인해 telemetry 수집 경로의 장애 복구 신뢰성을 검증
- Host reboot 전 DB 미저장 상태의 20,000 Span backlog를 생성하고 Docker volume 재마운트 후 `itemsSize=20000` queue 복원과 최종 20,000/20,000 저장을 검증하여 process lifecycle을 넘어선 데이터 보존 특성을 확인

#### 예상 면접 질문

```text
왜 docker restart와 host reboot를 따로 테스트했는가?
Backend를 pause하지 않고 stop한 이유는?
왜 reboot 이후 Backend가 자동으로 올라오지 않는 상태가 오히려 테스트에 유리했는가?
reboot 전에 DB=0인지 확인한 이유는?
왜 실험 metadata를 홈 디렉터리에 저장했는가?
Loaded queue metadata에서 무엇을 확인했는가?
dispatchedItems=2는 어떤 의미로 해석했는가?
Docker volume을 사용하면 host reboot에서도 항상 안전하다고 말할 수 있는가?
아직 검증하지 않은 storage failure는 무엇인가?
```

#### 보존할 핵심 증거

```text
reboot state 파일
queue_before_reboot=20000
DB_before_reboot=0

재부팅 후 container 상태:
Collector Up
TimescaleDB healthy
Backend Exited

Loaded queue metadata:
itemsSize=20000
bytesSize=2234800
dispatchedItems=2

queue_after_reboot=20000
DB_after_reboot=0

final_db=20000/20000
final_queue=0
final_in_flight=0
Backend healthy
```

#### 블로그 소재

**제목**

> OpenTelemetry Persistent Queue는 서버 재부팅도 버틸까? Process Crash에서 Host Reboot까지 장애 실험

**핵심 흐름**

```text
왜 process restart 테스트만으로 부족한가
→ Docker volume과 persistent queue 구조
→ reboot 전 DB=0인 backlog 만들기
→ 실험 metadata 보존
→ 실제 Host reboot
→ Collector metadata reload 확인
→ dispatch 중 item 복구
→ Backend 복구
→ 최종 20,000/20,000 검증
→ 아직 검증하지 않은 power-loss 영역
```

---

### Portfolio Checkpoint — Collector 장애 자동 탐지 및 Alert Storm 방지

Collector queue monitoring을 수동 스크립트 수준에서 끝내지 않고 systemd timer를 이용한 실제 운영 자동화 경로까지 확장했다.

구조:

```text
Collector metrics
→ queue 상태 checker
→ stateful alert evaluator
→ systemd oneshot service
→ 5초 timer
```

운영 상태 판정 로직은 systemd에 넣지 않고 Python 코드에 유지해 scheduler와 monitoring logic을 분리했다.

#### 정상 상태 polling 검증

5초 단위 timer를 활성화한 뒤 state file의:

```text
last_evaluated_at
```

이 반복 갱신되는 것을 확인했다.

```text
timer_state_update=PASS
timer_repeat=PASS
```

정상 상태에서는 `event=NONE` 상세 출력을 억제해 5초마다 Collector metric 전체가 journal에 기록되는 문제를 방지했다.

#### 실제 Collector 장애 자동 탐지

Collector container를 실제 중단했다.

```text
running=false
status=exited
```

사람이 checker/evaluator를 직접 실행하지 않은 상태에서 systemd timer가 자동으로:

```text
previous_status=OK
current_status=UNKNOWN
event=ALERT
alert_required=true
```

를 생성했다.

실제 원인:

```text
Collector metrics endpoint
Connection refused
```

도 journal에 남았다.

동일 장애가 계속되는 동안 timer가 반복 실행됐지만:

```text
alert_count=1
```

로 중복 alert가 억제됐다.

#### 자동 Recovery 감지

Collector를 복구한 뒤:

```text
previous_status=UNKNOWN
current_status=OK
event=RECOVERY
alert_required=true
```

가 자동 발생했다.

최종:

```text
alert_count=1
recovery_count=1
```

이었다.

#### 직접 경험한 운영 문제

이번 단계에서 다음 실무 문제를 직접 다뤘다.

```text
짧은 polling 주기와 로그 증가
alert storm
상태 persistence
process exit code와 서비스 상태 분리
monitoring 대상 자체가 죽은 경우의 UNKNOWN 처리
recovery notification
systemd oneshot lifecycle
timer 실제 실행 여부 검증
runtime state 권한 관리
```

또한 최초 quiet mode 구현에서 argument만 추가되고 실제 early-return 조건이 빠져 정상 상태에서도 출력이 계속되는 문제를 실행 결과로 발견하고 수정했다.

#### 이력서 성과 문장 초안

- OpenTelemetry Collector queue 상태를 5초 주기로 자동 평가하는 systemd 기반 운영 monitoring을 구성하고, persistent alert state와 상태 전이 기반 중복 억제를 적용해 Collector 장애 시 UNKNOWN ALERT 1회와 복구 시 RECOVERY 1회가 자동 발생하는 E2E 경로를 검증
- 짧은 polling 환경에서 정상 상태 metric dump로 발생할 수 있는 journal 증가를 event 기반 quiet mode로 억제하고, monitoring 대상 자체의 장애를 UNKNOWN 상태로 분리해 실제 Collector 중단 시 Connection Refused를 자동 탐지하도록 운영 경로를 구축

#### 면접에서 설명할 포인트

```text
왜 1분 cron이 아니라 5초 polling을 선택했는가?
왜 systemd에 threshold 로직을 직접 넣지 않았는가?
OnUnitInactiveSec를 선택한 이유는?
왜 WARNING/CRITICAL에서 evaluator exit code를 non-zero로 만들지 않았는가?
UNKNOWN과 evaluator failure는 어떻게 다른가?
동일 장애의 alert storm은 어떻게 막았는가?
왜 recovery notification이 필요한가?
StateDirectory를 사용한 이유는?
정상 상태에서 journal spam은 어떻게 방지했는가?
timer가 active인 것과 실제 실행되고 있는 것을 어떻게 구분해 검증했는가?
```

#### 보존할 증거

```text
timer enabled/active 출력

timer_state_update=PASS
timer_repeat=PASS

systemd state:
current_status=OK

Collector stop:
running=false status=exited

자동 장애 탐지:
event=ALERT
previous_status=OK
current_status=UNKNOWN
checker_exit_code=3
Connection refused

alert_count=1

자동 복구:
event=RECOVERY
previous_status=UNKNOWN
current_status=OK

recovery_count=1
```

#### 다음 한 단계 높은 과제

현재는 Collector process 자체 장애를 자동 감지했다.

다음 단계에서는 Collector는 살아 있지만 downstream 장애로 persistent queue가 증가하는 상황에서:

```text
OK
→ WARNING
→ CRITICAL
→ RECOVERY
```

상태 변화가 자동으로 탐지되는지 검증한다.

그 이후 실제 notification adapter를 연결해 운영자가 외부 채널에서 장애와 복구를 받을 수 있는 구조로 확장한다.

---

### Portfolio Checkpoint — 실제 Collector Queue 기반 Alert Severity State Machine 검증

Collector process 중단에 대한 UNKNOWN 자동 탐지에 이어 실제 persistent queue backlog를 이용해 운영 alert severity 전체 경로를 검증했다.

### 구현한 구조

Evaluator가 checker option을 전달할 수 있도록 다음 기능을 추가했다.

```text
--checker-arg
```

이를 이용해 production threshold:

```text
WARNING 50%
CRITICAL 80%
```

를 변경하지 않고 테스트 환경에서만 낮은 threshold를 적용했다.

테스트 threshold는 repository나 영구 systemd 설정에 저장하지 않고 다음 runtime 영역의 drop-in으로 적용했다.

```text
/run/systemd/system
```

실험 종료 후 drop-in을 제거하고:

```text
production_execstart=PASS
```

로 production 설정이 정상 복구된 것을 확인했다.

### 실제 Queue WARNING 자동 탐지

Backend를 pause하고 실제 2,000 spans를 전송했다.

Sender:

```text
Accepted spans=2000
Failed requests=0
Observed accepted spans/sec=999.87
Rate error=0.013%
Delivery success=PASS
Sustained-rate validity=PASS
```

Queue:

```text
queue_size=2000
queue_capacity=200000
utilization=1.00%
```

Production checker는 기존 50%/80% 기준을 유지해:

```text
status=OK
```

이었다.

반면 테스트 threshold가 적용된 systemd timer 경로에서는 자동으로:

```text
OK
→ WARNING

event=ALERT
alert_required=true
```

가 발생했다.

동일 WARNING 상태가 반복돼도:

```text
warning_alert_count=1
```

로 중복 alert를 억제했다.

Backend 복구 후:

```text
WARNING
→ OK

event=RECOVERY
```

가 자동 발생했고 DB에는:

```text
2000/2000
```

이 저장됐다.

### 실제 WARNING → CRITICAL 승격 검증

두 번째 실험에서도 실제 2,000 spans backlog를 사용했다.

Sender:

```text
Accepted spans=2000
Failed requests=0
Observed accepted spans/sec=999.91
Rate error=0.009%
Delivery success=PASS
Sustained-rate validity=PASS
```

먼저 다음 자동 전이를 확인했다.

```text
OK
→ WARNING

event=ALERT
```

Backend를 계속 pause한 상태에서 실제 queue는 그대로 두고 test severity 기준만 변경했다.

그 결과:

```text
WARNING
→ CRITICAL

event=STATUS_CHANGE
alert_required=true
checker_exit_code=2
```

가 systemd timer를 통해 자동 생성됐다.

Event count:

```text
ALERT=1
STATUS_CHANGE=1
```

이었다.

Backend 복구 후 queue는 다음과 같이 drain됐다.

```text
1000
1000
0
```

그리고:

```text
CRITICAL
→ OK

event=RECOVERY
```

가 자동 발생했다.

최종:

```text
RECOVERY=1
DB=2000/2000
queue_size=0
current_status=OK
timer_enabled=enabled
timer_active=active
production_execstart=PASS
```

을 확인했다.

### 직접 경험한 실무 영역

이번 단계에서 다음 운영 문제를 직접 다뤘다.

```text
production configuration과 test configuration 격리
systemd runtime drop-in
실제 metric 기반 severity transition
WARNING과 CRITICAL의 운영 의미 분리
alert storm 방지
severity escalation
recovery lifecycle
실험 후 production 설정 복원 검증
queue drain 관찰
telemetry 최종 DB 정합성 검증
```

단순 unit test가 아니라 실제 다음 전체 경로에서 상태 전이를 검증했다.

```text
OTLP sender
→ OpenTelemetry Collector
→ persistent queue
→ Collector metrics
→ queue checker
→ alert evaluator
→ systemd timer
→ journal
→ Backend
→ TimescaleDB
```

### 이력서 성과 문장 초안

- OpenTelemetry Collector의 실제 persistent queue metric을 기반으로 `OK → WARNING → CRITICAL → RECOVERY` 상태 머신을 구축하고, systemd 5초 polling 환경에서 ALERT·severity escalation·RECOVERY가 각각 1회 발생하며 동일 상태의 중복 alert가 억제되는 것을 E2E 검증
- Production 50%/80% queue threshold를 변경하지 않고 systemd runtime-only drop-in과 checker argument 전달 구조를 적용해 작은 실제 backlog로 alert severity 전이를 안전하게 재현하고, 테스트 종료 후 production 설정 복원 및 span 2,000/2,000 최종 저장을 검증

### 면접에서 설명할 포인트

```text
왜 실제 100k/160k backlog를 다시 만들지 않았는가?
Production threshold를 직접 낮추지 않은 이유는?
왜 /etc가 아니라 /run의 systemd drop-in을 사용했는가?
ExecStart override에서 빈 ExecStart=가 필요한 이유는?
WARNING과 CRITICAL을 boolean failure 하나로 처리하지 않은 이유는?
WARNING → CRITICAL은 왜 ALERT가 아니라 STATUS_CHANGE인가?
동일 WARNING의 alert storm은 어떻게 방지했는가?
테스트 설정이 production에 남지 않았다는 것을 어떻게 증명했는가?
왜 sender 성공뿐 아니라 DB 2000/2000까지 확인했는가?
Queue가 1000 → 1000 → 0으로 보인 것은 무엇을 의미하는가?
```

### 보존할 로그와 증거

WARNING 실험:

```text
queue_size=2000
queue_utilization_pct=1.00

event=ALERT
previous_status=OK
current_status=WARNING

warning_alert_count=1

event=RECOVERY
previous_status=WARNING
current_status=OK

db=2000/2000
```

CRITICAL 승격 실험:

```text
event=ALERT
previous_status=OK
current_status=WARNING

event=STATUS_CHANGE
previous_status=WARNING
current_status=CRITICAL

alert_count=1
status_change_count=1

queue drain:
1000
1000
0

event=RECOVERY
previous_status=CRITICAL
current_status=OK

recovery_count=1
db=2000/2000
```

최종 운영 상태:

```text
production_execstart=PASS
queue_size=0
current_status=OK
timer_enabled=enabled
timer_active=active
```

### 이번 단계에서 설명할 수 있어야 하는 핵심

- Production threshold와 alert state machine 테스트 조건을 왜 분리했는가?
- `--checker-arg`는 evaluator와 checker 사이에서 어떤 역할을 하는가?
- 왜 테스트 설정을 `/run/systemd/system`에 두었는가?
- WARNING 반복과 WARNING → CRITICAL 전환은 운영적으로 무엇이 다른가?
- 왜 CRITICAL에서 바로 OK가 됐을 때도 RECOVERY 하나로 처리하는가?
- queue alert 테스트에서 DB 최종 저장량까지 확인한 이유는 무엇인가?

### 한 단계 높은 다음 과제

현재 AeroTrace는 장애 상태를 자동 판단하고 systemd journal에 이벤트를 생성할 수 있다.

다음 단계에서는 판단 로직과 전달 로직을 분리한 notification adapter를 추가해:

```text
ALERT
STATUS_CHANGE
RECOVERY
```

이벤트를 실제 운영자에게 전달하는 경로를 구성한다.

Notification 전송 실패가 Collector queue monitoring 자체를 막지 않도록 timeout, retry, duplicate notification 정책을 별도로 설계한다.

---

### Portfolio Checkpoint — Durable Notification Pipeline과 Failure Isolation

OpenTelemetry Collector의 queue 상태를 판단하는 monitoring 경로와 운영자 notification 전달 경로를 독립적으로 분리했다.

### 해결한 문제

기존에는 Collector 장애를 자동으로 감지해도 결과가 systemd journal에서 끝났다.

외부 notification을 evaluator에 직접 넣으면 Slack, Discord 또는 webhook 장애가 monitoring 자체의 실행에 영향을 줄 수 있다.

이를 방지하기 위해 다음 구조를 구현했다.

```text
Collector metrics
→ queue checker
→ alert evaluator
→ durable JSON outbox
→ independent notification adapter
→ transport
```

### 직접 검증한 Event Contract

Evaluator는 기존 text 출력을 유지하면서 opt-in JSON contract를 제공한다.

```text
schema_version
event_id
event
alert_required
previous_status
current_status
checker_exit_code
evaluated_at
checker_output
```

JSON contract:

```text
한 이벤트당 한 줄
schema_version=1
previous status 없음은 null
boolean/int/null 타입 보존
```

을 자동 assertion으로 검증했다.

### Alert Event 유실 방지

Outbox를 evaluator state보다 먼저 저장하도록 구성했다.

Outbox write를 의도적으로 실패시킨 결과:

```text
outbox_failure_rc=4
state_after_outbox_failure=PASS_not_written
```

이었다.

Outbox 경로 복구 후 동일 Collector 상태에서 최초 ALERT를 다시 생성할 수 있음을 확인했다.

따라서 state만 진행되고 alert event가 사라지는 failure mode를 방지했다.

### Notification Adapter Failure Semantics

독립 notification adapter를 구현해:

```text
pending event
→ delivery
→ 성공 시 ACK
→ 실패 시 pending 유지
```

로 동작하게 했다.

정상:

```text
pending 1 → 0
delivered 0 → 1
DELIVERED
```

전송 실패:

```text
delivery_failure_rc=2
pending=1
```

전송 복구:

```text
pending=0
delivered=1
DELIVERED
```

을 검증했다.

### Crash Window 중복 처리

전송은 성공했지만 pending ACK 전에 process가 종료되는 상황을 재현했다.

동일 event_id가 이미 delivered돼 있는 경우:

```text
delivery_result=ACK_EXISTING
```

으로 처리해 중복 local delivery를 발생시키지 않았다.

### 실제 systemd 자동 E2E

Evaluator와 notification adapter를 별도 systemd timer로 실행했다.

사람이 evaluator 또는 adapter를 수동 실행하지 않은 상태에서 Collector를 실제 중단했다.

자동 경로:

```text
Collector stop
→ OK → UNKNOWN
→ ALERT
→ outbox
→ notification timer
→ DELIVERED
```

실제 로그:

```text
event=ALERT
previous_status=OK
current_status=UNKNOWN
checker_exit_code=3
```

Notification:

```text
delivery_result=DELIVERED
event=ALERT
processed_events=1
remaining_events=0
```

Collector를 다시 시작하자:

```text
UNKNOWN → OK
→ RECOVERY
→ outbox
→ notification timer
→ DELIVERED
```

까지 자동 실행됐다.

최종:

```text
delivered_event_sequence=PASS
final_pending=0
final_delivered=2
```

### Notification 장애 격리 실험

Notification transport만 의도적으로 실패시켰다.

Collector 장애 발생 후:

```text
state=UNKNOWN
pending=1
```

이 됐고 adapter journal에는:

```text
adapter_error=delivery failed
failed_event_id=1787035511951348652-1520983
remaining_events=1
```

이 기록됐다.

동시에 evaluator는 계속 실행됐다.

```text
last_evaluated_at
06:44:47
→
06:45:29

evaluator_independent_from_notification=PASS
```

Timer 상태:

```text
evaluator_timer=active
notification_timer=active
```

Evaluator service:

```text
Result=success
ExecMainStatus=0
```

따라서 실제 systemd 환경에서:

```text
Notification 장애
≠
Collector monitoring 장애
```

를 검증했다.

### 자동 Retry

Notification transport를 복구한 뒤 adapter를 수동 실행하지 않았다.

다음 timer trigger에서 기존 pending ALERT가 자동 처리됐다.

```text
pending=0
delivered=3
automatic_pending_retry=PASS
```

원래 event_id:

```text
1787035511951348652-1520983
```

가 그대로 전달됐다.

Collector 복구 후 RECOVERY도 자동 전달됐다.

```text
delivered=4
automatic_recovery_after_notification_failure=PASS
failure_recovery_sequence=PASS
```

마지막 event lifecycle:

```text
ALERT
OK → UNKNOWN

RECOVERY
UNKNOWN → OK
```

### 운영 환경 복구

모든 notification systemd 설정은 `/run/systemd/system`의 runtime-only configuration으로 검증했다.

실험 종료 후 모두 제거했다.

```text
production_evaluator=PASS
notification_runtime_cleanup=PASS

evaluator_timer_enabled=enabled
evaluator_timer_active=active

Collector running
queue_size=0
current_status=OK
```

### 이력서 성과 문장 초안

- OpenTelemetry Collector 장애 감지와 notification 전달 경로를 JSON outbox 기반으로 분리하고, notification transport 장애 중에도 alert event를 pending 상태로 보존하면서 Collector 상태 평가 timer가 계속 실행되는 failure isolation 구조를 구현·E2E 검증
- systemd 기반 독립 evaluator/notification timer를 구성해 실제 Collector 중단 시 `OK → UNKNOWN → ALERT`, 복구 시 `UNKNOWN → OK → RECOVERY`가 자동 전달되는 것을 검증하고, notification 실패 후 기존 event_id의 자동 retry와 최종 `pending=0` 복구를 확인
- Notification 전송 성공 후 ACK 전 process crash를 가정한 중복 event 시나리오에서 event_id 기반 `ACK_EXISTING` 처리를 적용해 동일 local delivery의 중복 생성을 방지

### 면접에서 설명할 수 있는 내용

```text
왜 evaluator가 Slack을 직접 호출하지 않게 설계했는가?

왜 단순 stdout pipe 대신 durable outbox가 필요한가?

왜 outbox를 evaluator state보다 먼저 저장하는가?

Outbox 성공 후 state 저장 전에 crash하면 어떤 일이 생기는가?

Delivery 성공 후 pending 삭제 전에 crash하면 어떻게 처리하는가?

Notification adapter 장애가 evaluator에 전파되지 않는다는 것을 어떻게 검증했는가?

같은 UNKNOWN 상태가 계속될 때 pending ALERT가 증가하지 않는 이유는 무엇인가?

systemd timer의 Result=success만 보고 과거 실행 실패 여부를 판단하면 왜 위험한가?

왜 local sink 테스트 이후 바로 Slack을 붙이지 않았는가?

At-most-once와 at-least-once 중 현재 구조는 어느 쪽에 가까운가?
```

### 보존할 증거

```text
json_contract=PASS
alert_json_contract=PASS

stdout_outbox_match=PASS
outbox_contract=PASS

state_after_outbox_failure=PASS_not_written

delivery_result=DELIVERED
delivery_result=ACK_EXISTING

delivery_failure_rc=2
pending_after_delivery_failure=1

automatic_alert_delivery=PASS
automatic_recovery_delivery=PASS
delivered_event_sequence=PASS

evaluator_independent_from_notification=PASS

pending_during_failure=1

automatic_pending_retry=PASS
retried_alert_contract=PASS

automatic_recovery_after_notification_failure=PASS
failure_recovery_sequence=PASS

production_evaluator=PASS
notification_runtime_cleanup=PASS
```

### 아직 과장하면 안 되는 부분

현재 outbox는 process/systemd failure 경계에서 검증됐다.

아직 다음까지 증명하지 않았다.

```text
abrupt host power loss 직전 outbox durability
filesystem corruption
disk full
외부 webhook timeout 후 실제 수신 여부가 불명확한 상황
Slack/Discord 등 provider 자체의 duplicate semantics
```

따라서 포트폴리오에서는 현재 구조를 “process/systemd notification failure isolation 및 retry 검증”으로 표현하고 “power-loss safe exactly-once notification”으로 표현하지 않는다.

### 다음 한 단계 높은 과제

실제 외부 notification transport를 연결하기 전에 다음 두 의미를 정리한다.

```text
Evaluator가 notification event를 발생시킨 시각
외부 transport가 실제 delivery에 성공한 시각
```

현재 evaluator의:

```text
last_notification_at
last_notification_epoch
```

는 실제 외부 delivery 시각이 아니므로 책임과 명칭을 재검토한다.

그 다음 Generic Webhook / Slack / Discord 중 MVP transport를 선택하고 HTTP timeout, retry, ambiguous delivery, credential 관리까지 검증한다.

---

### Portfolio Checkpoint — Notification Outbox 적체와 실제 장애 지속 상태 관측

Webhook notification을 단순히 재시도하는 것에서 끝내지 않고, 전달 장애가 장기화될 때 미전송 event가 얼마나 쌓이고 얼마나 오래 대기하고 있는지 확인할 수 있는 Notification Outbox checker를 구현하고 실제 장애를 발생시켜 상태 전이를 검증했다.

### 구현한 관측 구조

Notification Outbox에서 다음 지표를 확인할 수 있도록 했다.

```text
pending event count
pending bytes
oldest pending age
oldest event ID
oldest evaluated_at
```

상태와 exit code:

```text
OK       = 0
WARNING  = 1
CRITICAL = 2
UNKNOWN  = 3
```

Count와 age threshold를 독립적으로 설정할 수 있으며 두 상태 중 더 높은 severity를 전체 상태로 사용하도록 했다.

### 기본 동작

Threshold를 지정하지 않으면 pending event가 있더라도 관측값만 출력한다.

```text
pending_events > 0
threshold 없음
→ status=OK
```

운영 데이터를 측정하기 전에 테스트용 숫자를 production alert 기준으로 고정하지 않기 위한 선택이다.

### Outbox 관측 검증

Test event 두 개에서 다음 값을 실제로 확인했다.

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

### 데이터 손상 검출

손상된 JSON:

```text
{broken json
```

을 Outbox에 배치했을 때:

```text
status=UNKNOWN
broken_event_rc=3
```

을 반환하는 것을 확인했다.

Outbox path가 directory가 아닌 일반 파일인 경우도:

```text
status=UNKNOWN
outbox_path_error_rc=3
```

으로 처리했다.

### Count Threshold 검증

실제 pending 두 개에서:

```text
warn-count=2
critical-count=3
→ WARNING
→ rc=1
```

그리고:

```text
warn-count=1
critical-count=2
→ CRITICAL
→ rc=2
```

를 확인했다.

### Age Threshold 검증

약 120초 된 event에서:

```text
warn-age-sec=60
critical-age-sec=3600
→ WARNING / rc=1
```

그리고:

```text
warn-age-sec=60
critical-age-sec=90
→ CRITICAL / rc=2
```

를 확인했다.

Count가 WARNING이고 age가 CRITICAL인 경우:

```text
overall=CRITICAL
combined_status_rc=2
```

가 됐다.

### 실제 장애 실험

Synthetic event만 사용하는 것으로 끝내지 않고 실제 webhook connection failure를 발생시켰다.

사용한 endpoint:

```text
http://127.0.0.1:1/aerotrace
```

실제 결과:

```text
adapter_error=retryable delivery failure:
webhook request failed: [Errno 111] Connection refused

delivery_failure_rc=2
pending_after_failure=1
receipts_after_failure=0
```

생성된 실제 pending event:

```text
1787182663140772001-653546
```

### 실제 시간 경과에 따른 상태 전이

동일 event를 유지한 상태에서 실제 시간이 흐르도록 하고 age를 연속 측정했다.

초기:

```text
oldest_pending_age_sec=5.853
status=OK
initial_rc=0
```

시간 경과 후:

```text
oldest_pending_age_sec=17.889
status=WARNING
warning_rc=1
```

추가 시간 경과 후:

```text
oldest_pending_age_sec=31.927
status=CRITICAL
critical_rc=2
```

검증:

```text
actual_pending_age_transition=PASS
```

세 시점 모두 동일 event ID를 유지했다.

```text
event_id=1787182663140772001-653546
```

최종 상태:

```text
final_pending=1
final_receipts=0
pending_event_identity_preserved=PASS
```

### 테스트 실패 원인 분석 경험

첫 번째 상태 전이 실험에서는 baseline age가:

```text
23.407s
```

였고 WARNING threshold를:

```text
33.407s
```

로 설정했다.

하지만 사람이 명령을 입력하는 사이 시간이 지나 최초 검사 값이:

```text
33.408s
```

가 되어 즉시 WARNING으로 진입했다.

이 결과를 checker 버그로 수정하지 않고 실제 수치를 비교해 테스트 자체의 timing race임을 확인했다.

이후 fresh event를 만들고 전체 상태 전이를 하나의 연속 shell 실행으로 묶어 사람의 입력 시간을 제거했다.

이를 통해:

```text
OK → WARNING → CRITICAL
```

상태 전이를 안정적으로 재현했다.

### 직접 얻은 실무 경험

이번 단계에서 다음 운영 관점을 직접 다뤘다.

```text
queue depth와 queue age의 차이
oldest pending age
event timestamp와 filesystem mtime의 차이
threshold severity aggregation
UNKNOWN 상태와 monitoring failure
실제 장애 지속에 따른 상태 악화
운영 threshold와 테스트 threshold의 분리
시간 의존 테스트의 race condition
측정 결과를 기반으로 한 테스트 원인 분석
```

특히 단순히 "pending file이 있다"는 사실보다 oldest pending age가 notification 장애 지속 정도를 파악하는 데 더 직접적인 지표가 될 수 있음을 실제 데이터로 확인했다.

### 보존할 로그와 증거

다음 결과는 포트폴리오와 기술 블로그 자료로 보존한다.

```text
outbox_observability=PASS

pending_events=2
pending_bytes=585
oldest_pending_age_sec=94.694

broken_event_rc=3
outbox_path_error_rc=3

count_warning_rc=1
count_critical_rc=2

age_warning_rc=1
age_critical_rc=2
combined_status_rc=2

delivery_failure_rc=2
pending_after_failure=1
receipts_after_failure=0

baseline_age=5.792
warn_age=15.792
critical_age=30.792

initial_age=5.853
warning_age=17.889
critical_age=31.927

actual_pending_age_transition=PASS
pending_event_identity_preserved=PASS

final_pending=1
final_receipts=0
```

### 이력서 성과 문장 초안

- Webhook 장애 장기화 시 알림 유실 여부뿐 아니라 미전송 Outbox의 pending 수, 저장량, oldest event age를 관측하는 checker를 구현하고 실제 connection failure 상태에서 동일 event의 age가 `5.853s → 17.889s → 31.927s`로 증가하며 `OK → WARNING → CRITICAL`로 전이되는 과정을 검증
- 운영 임계값을 임의로 고정하지 않고 pending count와 oldest age threshold를 외부 설정으로 분리해 실제 장애 데이터 측정 후 정책을 결정할 수 있는 notification monitoring 구조를 설계
- 시간 기반 상태 전이 테스트에서 명령 입력 지연으로 threshold가 먼저 초과되는 문제를 측정값으로 원인 분석하고, fresh event와 연속 실행 방식으로 테스트를 재설계해 안정적으로 상태 전이를 재현

현재 테스트 threshold를 실제 production SLA처럼 표현하지 않는다.

### 예상 면접 질문

```text
왜 queue count만 보지 않고 oldest age도 보는가?

왜 filesystem mtime 대신 evaluated_at을 사용하는가?

pending event가 하나뿐인데 CRITICAL이 될 수 있는 이유는 무엇인가?

count WARNING, age CRITICAL이면 전체 상태는 어떻게 결정하는가?

왜 production threshold를 지금 정하지 않았는가?

모니터링 대상 JSON 자체가 손상되면 어떤 상태를 반환하는가?

Outbox oldest age와 실제 transport failure duration은 항상 같은가?

첫 번째 상태 전이 테스트가 실패한 이유는 무엇이었는가?

시간 기반 테스트를 어떻게 안정화했는가?

장애가 복구된 뒤 이 상태는 어떻게 정상화되어야 하는가?
```

### 블로그 소재

제목 후보:

```text
Webhook 장애는 몇 초째 지속되고 있을까?
Notification Outbox의 Depth와 Age를 함께 관측한 이유
```

또는:

```text
시간 기반 모니터링 테스트가 1ms 차이로 실패한 이유와 재현 가능한 테스트로 바꾼 과정
```

글의 핵심 흐름:

```text
1. Webhook retry만으로 부족했던 이유
2. Pending count만 봤을 때의 한계
3. Oldest pending age 도입
4. evaluated_at과 filesystem mtime 비교
5. Count / Age threshold 설계
6. UNKNOWN 상태가 필요한 이유
7. 실제 connection refused 장애 생성
8. 동일 event의 age 증가 측정
9. OK → WARNING → CRITICAL 전이
10. 첫 테스트의 timing race
11. 테스트 재설계 과정
12. Production threshold를 아직 정하지 않은 이유
```

### 다음 한 단계 높은 과제

Outbox age는 "미전송 event가 얼마나 오래됐는가"를 보여주지만 transport 자체의 연속 실패 횟수와 최초 실패 시각을 직접 기록하지는 않는다.

다음 단계에서는 notification adapter에 persistent failure state를 추가해 다음 정보를 기록한다.

```text
first_failed_at
last_failed_at
failure_count
failure_kind
failed_event_id
```

성공 시 이 상태를 어떻게 초기화하거나 보존할지도 함께 검증한다.

이를 통해 다음 두 개념을 분리해서 관찰할 수 있게 한다.

```text
oldest pending age
vs
actual transport failure duration
```

---

### Portfolio Checkpoint — Notification Transport 장애 상태 영속화와 Crash-window 복구

Webhook notification의 미전송 event age뿐 아니라 실제 transport 장애가 언제 시작됐고 몇 번 연속 실패했는지 추적하기 위해 persistent failure state를 구현하고 실제 network/filesystem failure를 조합해 복구 semantics를 검증했다.

### 구현한 Persistent Failure State

현재 상태:

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

을 저장한다.

Failure state는 webhook transport에서 optional로 활성화할 수 있다.

### 연속 장애 추적 실험

사용하지 않는 localhost port에 webhook을 전송해 connection refused를 발생시켰다.

첫 실패:

```text
failure_count=1
failure_kind=retryable
failure_reason=connection_error
first_failed_at=2026-08-20T00:25:34+00:00
last_failed_at=2026-08-20T00:25:34+00:00
```

검증:

```text
failure_state_first_attempt=PASS
```

동일 event 두 번째 실패:

```text
failure_count=2
first_failed_at=2026-08-20T00:25:34+00:00
last_failed_at=2026-08-20T00:25:49+00:00
```

검증:

```text
failure_state_second_attempt=PASS
```

이를 통해 최초 장애 시각과 최근 장애 시각을 분리해 추적할 수 있음을 확인했다.

### Permanent Failure 구조화

HTTP 400을 실제 서버에서 반환하도록 했다.

결과:

```text
http_400_failure_rc=5
failure_count=1
failure_kind=permanent
failure_reason=http_400
```

검증:

```text
permanent_failure_state=PASS
```

따라서 retryable/permanent를 로그 문자열이 아닌 구조화된 상태로 저장할 수 있게 했다.

### 성공 Recovery

연속 failure 이후 HTTP 204를 반환하는 local server로 동일 pending event를 성공시켰다.

결과:

```text
failure_state_cleared=true
delivery_result=DELIVERED
remaining_events=0
recovery_rc=0

failure_state_removed=PASS
pending_after_recovery=0
receipts_after_recovery=1
```

현재 연속 장애가 끝나면 failure state를 제거하는 구조를 검증했다.

### 성공 후 내부 상태 정리 실패 발견

초기 구현 검토 중 다음 crash-window를 발견했다.

```text
HTTP 성공
→ receipt 저장
→ pending 삭제
→ failure state 삭제 실패
```

이 경우 pending이 이미 없어 stale failure state만 남을 수 있었다.

이를 방지하기 위해 성공 finalization 순서를 수정했다.

```text
HTTP 성공
→ receipt 저장
→ failure state 삭제
→ pending ACK
```

### Filesystem Permission Failure 실험

Failure state directory를 read/execute-only로 만들어 state unlink가 실패하도록 했다.

```text
chmod 500 <state-directory>
```

Receiver는 HTTP 204를 반환했지만 failure state clear는 permission denied로 실패했다.

결과:

```text
clear_failure_rc=4

pending_after_clear_failure=1
receipts_after_clear_failure=1
failure_state_after_clear_failure=1
```

즉 외부 notification 성공 증거인 receipt는 보존하면서 pending ACK를 하지 않아 복구 가능한 상태를 유지했다.

### Duplicate HTTP Delivery 없는 Recovery

위 실패 상태에서 HTTP server를 완전히 종료했다.

서버가 없으므로 network 요청을 다시 실행했다면 connection refused가 발생해야 했다.

하지만 adapter는 기존 receipt를 발견했다.

결과:

```text
failure_state_cleared=true
delivery_result=ACK_EXISTING
ack_existing_rc=0
```

최종:

```text
final_pending=0
final_receipts=1
final_failure_state=0
```

즉 external delivery 성공 후 local bookkeeping 실패가 발생하더라도 receipt 기반으로 HTTP를 재전송하지 않고 복구하는 경로를 실제로 검증했다.

### 손상 State 방어

Failure state를:

```text
{broken json
```

으로 손상시켰다.

실행 결과:

```text
corrupt_failure_state_rc=4
```

그리고 실행 전후 SHA-256 값이 동일했다.

```text
c3e7d1b00a65589b59f816c0b0b668d795a3c28123697d5ab9555bdb8aa04604
```

검증:

```text
corrupt_state_preserved=PASS
```

따라서 손상된 운영 상태를 조용히 덮어써 장애 정보를 숨기지 않고 명시적으로 실패하도록 했다.

### 직접 얻은 실무 경험

이번 단계에서 다음 개념을 실제 코드와 failure injection으로 다뤘다.

```text
persistent failure state
retryable vs permanent failure
structured failure reason
first failure timestamp
last failure timestamp
consecutive failure count
durable state write
file fsync
directory fsync
atomic replace
success finalization ordering
partial success
crash-window
receipt-based recovery
idempotent ACK
filesystem permission failure injection
corrupted state detection
```

특히 단순 HTTP 오류 처리보다 외부 side effect와 로컬 상태 변경 사이에서 어느 순서로 durable state를 남겨야 복구 가능한지를 직접 검증했다.

### 보존할 로그와 증거

```text
failure_state_first_attempt=PASS

failure_count=1
failure_kind=retryable
failure_reason=connection_error

failure_state_second_attempt=PASS

failure_count=2
first_failed_at=2026-08-20T00:25:34+00:00
last_failed_at=2026-08-20T00:25:49+00:00

failure_state_removed=PASS
pending_after_recovery=0
receipts_after_recovery=1

clear_failure_rc=4

pending_after_clear_failure=1
receipts_after_clear_failure=1
failure_state_after_clear_failure=1

delivery_result=ACK_EXISTING
ack_existing_rc=0

final_pending=0
final_receipts=1
final_failure_state=0

http_400_failure_rc=5
failure_kind=permanent
failure_reason=http_400
permanent_failure_state=PASS

corrupt_failure_state_rc=4
corrupt_state_preserved=PASS
corrupt_pending_after=1
corrupt_receipts_after=0
```

### 이력서 성과 문장 초안

- Webhook notification의 연속 transport 장애를 추적하기 위해 `first_failed_at`, `last_failed_at`, `failure_count`, 구조화된 failure reason을 persistent state로 저장하고 connection failure 및 HTTP 400 시나리오를 실제 서버로 검증
- 외부 Webhook 전달 성공 후 로컬 failure-state 정리가 실패하는 partial-success 시나리오를 filesystem permission failure로 재현하고, `receipt → failure-state clear → pending ACK` 순서로 finalization을 재설계해 receipt 기반 `ACK_EXISTING` 복구 시 HTTP 중복 전송을 방지
- 손상된 persistent failure state를 hash 비교로 검증해 자동 덮어쓰기 대신 delivery 이전에 `rc=4`로 중단하도록 설계하여 운영 상태 손실을 방지

실제 production systemd 배포나 외부 SaaS webhook provider까지 완료된 것으로 표현하지 않는다.

### 예상 면접 질문

```text
Outbox oldest age와 transport failure duration은 왜 다른가?

왜 failure_count를 state에 저장하는가?

왜 exception 문자열을 파싱하지 않고 failure_reason을 구조화했는가?

왜 webhook URL을 failure state에 저장하지 않는가?

HTTP 성공 후 failure state를 언제 삭제해야 하는가?

왜 receipt보다 먼저 failure state를 삭제하지 않는가?

왜 pending을 failure state보다 먼저 삭제하면 위험한가?

ACK_EXISTING은 어떤 장애를 복구하는가?

HTTP server를 종료했는데도 ACK_EXISTING이 성공한 이유는 무엇인가?

failure state가 손상됐을 때 왜 자동 초기화하지 않았는가?

이 구조가 exactly-once delivery를 보장하는가?
```

### 블로그 소재

제목 후보:

```text
Webhook은 성공했는데 로컬 상태 저장이 실패한다면?
Receipt와 ACK 순서를 장애 주입으로 검증한 과정
```

핵심 구성:

```text
1. Outbox age만으로 부족했던 이유
2. Persistent failure state 도입
3. 연속 실패 count와 timestamps
4. Retryable / Permanent 구조화
5. HTTP 성공 후 state clear 순서
6. Permission denied 장애 주입
7. Receipt는 있는데 pending도 남은 상태
8. Server를 내리고 재시도
9. ACK_EXISTING으로 network 호출 없이 복구
10. exactly-once와 idempotency의 차이
```

### 다음 한 단계 높은 과제

Persistent failure state는 현재 장애 정보를 저장할 수 있지만 운영자가 이를 직접 상태로 평가하는 checker는 아직 없다.

다음 단계에서는 다음 값을 읽어 상태를 계산할 수 있도록 한다.

```text
failure_count
first_failed_at
last_failed_at
failure_kind
failure_reason
failure_duration
```

그리고 Outbox checker의:

```text
oldest_pending_age
```

와 함께 비교해 다음 두 상태를 분리해서 관측한다.

```text
notification backlog age
vs
actual webhook transport failure duration
```