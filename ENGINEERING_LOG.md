# ENGINEERING_LOG.md

> 마지막 업데이트: 2026-07-27

## 1. Spring Boot 백엔드 초기 구성

### 구현 내용

* Spring Boot 4.1.0 프로젝트 생성
* Java 21 toolchain 적용
* Spring Web MVC
* Validation
* Actuator
* Gradle 기반 빌드

### 검증

* 애플리케이션 정상 실행
* Tomcat 11.0.22가 8080 포트에서 시작
* `/actuator/health`가 `UP` 반환
* `clean test` 성공

### 결과

백엔드가 없는 초기 상태에서 독립 실행 가능한 Spring Boot 애플리케이션 기반을 구성했다.

---

## 2. Virtual Threads 적용 및 검증

### 구현 내용

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

### 검증

임시 진단 endpoint에서 요청 처리 Thread의 다음 값을 확인했다.

```text
virtual = true
daemon = true
```

검증 후 내부 진단 endpoint는 삭제했다.

### 학습 내용

* Virtual Thread는 요청 동시성 처리를 쉽게 하지만 DB connection 수를 늘리지 않는다.
* 실제 DB 처리량은 HikariCP와 PostgreSQL이 제한할 수 있다.
* Virtual Threads 적용 여부는 설정만 보지 않고 실제 요청 처리 Thread로 검증해야 한다.

---

## 3. TimescaleDB와 Flyway 연결

### 구현 내용

* Spring JDBC 추가
* PostgreSQL Driver 추가
* HikariCP 연결
* Flyway 구성
* 환경변수 기반 DB 접속 설정

### Migration

```text
V1__enable_timescaledb.sql
```

### 검증 결과

* PostgreSQL 15.18 연결
* TimescaleDB 2.28.3 활성화
* `flyway_schema_history`에 V1 성공 기록
* 애플리케이션 재실행 시 migration이 다시 실행되지 않음

### 발생한 문제

PowerShell에서 다음 psql 메타 명령이 실패했다.

```text
\dt
```

중첩된 PowerShell, Docker CLI, shell 전달 과정에서 역슬래시가 사라져 PostgreSQL이 `dt`를 SQL로 해석했다.

### 해결

psql 전용 메타 명령 대신 `information_schema`와 PostgreSQL catalog를 조회하는 일반 SQL을 사용했다.

### 학습 내용

* 셸과 운영체제에 따라 CLI escaping이 달라질 수 있다.
* 반복 검증은 SQL 파일과 IntelliJ Database Console이 더 안정적이다.

---

## 4. Tenant와 Project 데이터 모델

### 구현 내용

* `tenants` 테이블
* `projects` 테이블
* UUID 식별자
* name과 slug 분리
* Tenant slug 전체 유일성
* Tenant 내부 Project slug 유일성
* `ON DELETE RESTRICT`

### 검증

* Tenant 생성
* Project 생성
* JOIN을 통한 소유 관계 확인
* Project 삭제 전 Tenant 삭제 제한 확인
* 테스트 데이터 정리

### 설계 의도

개인 사용자도 Tenant로 표현하고, 추후 팀과 회사도 같은 구조를 사용한다.

---

## 5. Span TimescaleDB hypertable

### 구현 내용

* `spans` 테이블
* `start_time` 기준 TimescaleDB hypertable
* 1일 chunk
* Tenant와 Project 복합 외래키
* Trace ID와 Span ID CHECK
* 시간 순서와 duration CHECK
* JSONB 자료형 CHECK

### 인덱스

```text
ux_spans_identity
ix_spans_recent
ix_spans_trace_lookup
```

### 중복 방지 기준

```text
tenant_id
project_id
trace_id
span_id
start_time
```

### 검증

* Hypertable 등록 확인
* Chunk 생성 확인
* Span 삽입 확인
* 동일 Span 재삽입 시 `INSERT 0 0`
* 행 수가 1로 유지되는 것 확인

---

## 6. IntelliJ 중심 개발 흐름 전환

### 기존 문제

PowerShell과 curl 명령으로 요청과 DB를 검증하는 과정이 반복적이고 불편했다.

### 변경

* IntelliJ Run Configuration으로 애플리케이션 실행
* IntelliJ HTTP Client `.http` 파일로 API 요청
* HTTP Client response handler로 상태와 본문 검증
* Database Tool Window로 PostgreSQL 연결
* 프로젝트 내부 SQL 파일로 반복 조회
* Services Tool Window로 Docker 확인

### 발생한 문제

HTTP Client에서 응답이 `{}`인데 다음 검사가 실패했다.

```javascript
Object.keys(response.body).length === 0
```

응답 객체가 일반 JavaScript object와 다르게 처리된 것이 원인이었다.

### 해결

응답이 문자열인지 객체인지 구분한 후 문자열 `{}` 형태로 비교했다.

### 결과

반복 가능한 API 수동 테스트와 DB 검증 환경을 저장소 내부 파일로 관리하게 됐다.

---

## 7. OTLP/HTTP JSON 수신

### 구현 내용

```text
POST /v1/traces
Content-Type: application/json
```

### 검증

* 정상 OTLP 요청: `200`, `{}`
* 빈 요청: `200`, `{}`
* 잘못된 `resourceSpans` 구조: `400`
* 잘못된 Content-Type: `415`
* 헤더 누락: `400`

### 초기 구현

처음에는 전체 DTO를 만들지 않고 Jackson `JsonNode` 기반으로 구조와 Span 수만 확인했다.

이후 단계에서 의미 검증과 도메인 변환을 추가했다.

---

## 8. OTLP 핵심 필드 파싱

### 구현 필드

* service name
* scope name, version
* trace ID
* span ID
* parent span ID
* Span name
* Span kind
* status code, message
* start/end timestamp
* duration

### 검증

* Trace ID 32자리 16진수
* Span ID 16자리 16진수
* all-zero ID 거부
* 종료 시간이 시작 시간보다 빠르면 거부
* `service.name` 누락 거부
* kind와 status 범위 검증

### 정책

`service.name`은 OTLP 전체에서 무조건 필수인 필드는 아니지만 AeroTrace의 조회와 집계에 필요하므로 필수 정책으로 정했다.

---

## 9. OTLP AnyValue와 Attributes 파싱

### 지원 자료형

* String
* Boolean
* signed 64-bit integer
* Double
* Base64 bytes
* Array
* Nested key-value list

### 검증 정책

* Attribute key는 비어 있을 수 없음
* 같은 Attribute 배열 안의 중복 key 거부
* AnyValue에는 지원되는 값 필드가 정확히 하나 있어야 함
* 잘못된 Base64 거부
* `intValue` 범위 검증

### 저장 방식

Java `Map<String, Object>`와 `List<Object>`로 변환한 뒤 Jackson을 사용해 JSONB 문자열로 직렬화했다.

---

## 10. Span Event와 Link 파싱

### Event

* `timeUnixNano`
* name
* attributes
* dropped attributes count

### Link

* trace ID
* span ID
* trace state
* attributes
* dropped attributes count
* flags

### 검증

* 잘못된 Link trace ID 거부
* Link span ID 형식 검증
* unsigned 32-bit 범위 검증
* Event와 Link Attribute 파싱

### 저장

`events`, `links` JSONB 컬럼에 record 배열을 직렬화해 저장했다.

---

## 11. Span 메타데이터 보존

### Migration

```text
V4__add_span_drop_counts.sql
```

### 추가 저장 항목

* Span trace state
* Span flags
* dropped attributes count
* dropped events count
* dropped links count

### 검증

* `flags = 257` 저장
* dropped count `3`, `2`, `1` 저장
* `4294967296`과 같이 uint32 범위를 넘는 요청은 `400`
* 이전 Span에는 기본값 `0` 적용

---

## 12. JDBC 단건 Span 저장

### 저장 흐름

```text
OTLP JSON
→ ParsedSpan
→ TraceIngestionService
→ SpanWriter
→ JdbcSpanWriter
→ TimescaleDB
```

### 구현 내용

* 임시 Tenant ID와 Project ID HTTP 헤더
* 요청 단위 트랜잭션
* PreparedStatement 바인딩
* JSONB 직렬화
* `ON CONFLICT DO NOTHING`

### 검증

* 첫 요청: `inserted=1`
* 동일 요청 재전송: `duplicates=1`
* DB 행 수는 1 유지
* Attributes, Events, Links 저장 확인

### 발생한 문제

Attribute 저장용 요청이 정상 응답을 반환했지만 기대한 서비스 데이터가 DB에서 조회되지 않았다.

### 원인

Attribute 테스트 요청과 기존 정상 요청이 같은 다음 값을 사용했다.

```text
trace_id
span_id
start_time
tenant_id
project_id
```

유일 인덱스에 의해 중복으로 판정되어 새 데이터가 삽입되지 않았다.

### 해결

테스트 목적이 다른 HTTP 요청마다 고유한 trace ID와 span ID를 사용했다.

### 학습 내용

* 중복 방지 테스트 외에는 테스트 데이터 식별자가 겹치지 않아야 한다.
* API 성공 응답만으로 신규 저장을 판단하면 안 된다.
* 저장 로그와 DB 행을 함께 검증해야 한다.

---

## 13. JDBC batch 저장 전환

### 기존 방식

```text
Span 1개당 JdbcTemplate.update() 1회
```

### 변경 방식

```text
요청에 포함된 Span 목록
→ JSON 직렬화 사전 수행
→ JdbcTemplate.batchUpdate() 1회
```

### 결과 분류

* inserted
* duplicate
* unknown success
* failed

### 검증

3개 Span 요청:

```text
received=3
inserted=3
duplicates=0
unknown=0
```

동일 요청 재전송:

```text
received=3
inserted=0
duplicates=3
unknown=0
```

신규 1개와 중복 2개 혼합:

```text
received=3
inserted=1
duplicates=2
unknown=0
```

---

## 14. JDBC 단건과 batch 성능 비교

### 테스트 목적

동일한 SQL, 바인딩, Span 데이터, DB 스키마, 트랜잭션 조건에서 단건 반복과 JDBC batch 성능을 비교한다.

### 측정 조건 보정

초기 벤치마크에서는 단건 방식의 JSON 직렬화가 시간 측정 전에 수행됐지만, batch 방식은 시간 측정 안에서 수행되는 차이가 발견됐다.

단건 측정도 다음 범위를 포함하도록 보정했다.

```text
JSON 직렬화
→ 트랜잭션 시작
→ JDBC 저장
→ 트랜잭션 완료
```

최종 측정에서는 단건과 batch 모두 다음 범위를 동일하게 포함한다.

* JSON 직렬화
* 트랜잭션
* JDBC 저장

다음 항목은 측정에서 제외했다.

* 저장 행 수 검증
* 다음 측정을 위한 테스트 데이터 삭제

### 테스트 환경

* Java 21
* Spring Boot 4.1.0
* PostgreSQL 15 기반 TimescaleDB
* Windows 개발 PC
* Docker Desktop
* persistence-only 측정
* 워밍업 수행
* 반복 측정
* 실행 순서 교차
* 중앙값 비교
* 매 측정 전 테스트 Span 삭제
* 저장 행 수 검증

### 보정된 측정 결과

| Span 수 | Rewrite |      단건 중앙값 |   Batch 중앙값 |          단건 처리량 |       Batch 처리량 |    배율 |
| -----: | :-----: | ----------: | ----------: | --------------: | --------------: | ----: |
|    100 |   OFF   |    91.084ms |    30.460ms | 1,098 spans/sec | 3,283 spans/sec | 2.99배 |
|    100 |    ON   |    98.687ms |    32.836ms | 1,013 spans/sec | 3,045 spans/sec | 3.01배 |
|  1,000 |   OFF   |   874.985ms |   299.167ms | 1,143 spans/sec | 3,343 spans/sec | 2.92배 |
|  1,000 |    ON   |   865.236ms |   288.642ms | 1,156 spans/sec | 3,465 spans/sec | 3.00배 |
|  5,000 |   OFF   | 4,566.945ms | 1,485.885ms | 1,095 spans/sec | 3,365 spans/sec | 3.07배 |
|  5,000 |    ON   | 4,672.900ms | 1,593.576ms | 1,070 spans/sec | 3,138 spans/sec | 2.93배 |

### 이상치

100 Span 단건 측정에서 다음과 같은 큰 값이 일부 관찰됐다.

* Rewrite OFF: 218.136ms
* Rewrite ON: 280.925ms

소규모 테스트는 JVM, Docker, 디스크, 운영체제 스케줄링 같은 일시적인 영향이 전체 결과에서 차지하는 비율이 크다.

최솟값이나 평균값이 아니라 중앙값을 사용해 이상치 영향을 제한했다.

### 결과 해석

* JDBC batch는 모든 테스트 크기에서 단건 반복보다 약 2.9~3.1배 높은 처리량을 보였다.
* 측정 범위를 동일하게 보정한 뒤에도 batch 채택 결론이 유지됐다.
* `reWriteBatchedInserts=true`는 Span 수에 따라 개선과 악화가 혼재했다.
* 현재 환경에서는 rewrite 옵션의 일관된 효과를 확인하지 못했다.

### 결정

* 요청 단위 JDBC batch 저장 유지
* `reWriteBatchedInserts` 비활성 유지
* 다음 단계에서 고정된 총 Span 수를 chunk로 나눠 저장하는 방식을 비교

### 측정 제한

측정에 포함되지 않은 항목:

* HTTP 네트워크
* OTLP JSON 파싱
* OpenTelemetry Collector
* 동시 요청
* 원격 DB
* N100 홈서버
* CPU 사용률
* 최대 JVM heap
* WAL 발생량
* 실제 서버 측 SQL statement 수

---

## 15. 현재 남은 위험

### 데이터 유실

* Collector persistent queue 미구성
* DB 장애 시 retry와 응답 정책 미확정
* Process crash 시 수신 중 데이터 처리 미검증

### 보안

* API Key 미구현
* 임시 Tenant·Project UUID 헤더 사용
* Rate limit 없음
* 요청 크기 제한 없음
* DB password가 개발 Compose에 직접 존재

### 성능

* Batch chunk 크기 미결정
* 요청 전체를 메모리에서 파싱
* 매우 큰 OTLP 요청 처리 미검증
* 동시 요청 시 connection pool 병목 미측정

### 저장 비용

* retention 미적용
* compression 미적용
* 평균 Span 크기 미측정
* 일일 DB 증가량 미측정
* JSONB와 인덱스 크기 미측정

---

## 16. 다음 실험

### Batch chunk 크기 비교

총 Span 수를 고정하고 다음 chunk 크기를 비교한다.

```text
50
100
250
500
1,000
2,000
5,000
```

측정 항목:

* 총 처리 시간
* 처리량
* batch 실행 횟수
* JVM heap 변화
* 결과 편차
* 트랜잭션 롤백 동작

### 이후

* 요청 최대 크기
* API Key
* 표준 오류 응답
* Collector 연동
* DB 장애 실험
* N100 재측정
* end-to-end 부하 테스트
* 