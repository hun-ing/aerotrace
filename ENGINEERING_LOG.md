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

### 테스트 환경

* Java 21
* Spring Boot 4.1.0
* PostgreSQL 15.18
* TimescaleDB 2.28.3
* Windows 개발 PC
* Docker Desktop
* persistence-only
* 워밍업 수행
* 반복 측정
* 실행 순서 교차
* 중앙값 비교
* 매 측정 전 테스트 Span 삭제
* 저장 행 수 검증

### 결과

| Span 수 | Rewrite |      단건 중앙값 |   Batch 중앙값 |          단건 처리량 |       Batch 처리량 |    배율 |
| -----: | :-----: | ----------: | ----------: | --------------: | --------------: | ----: |
|    100 |   OFF   |    85.974ms |    29.663ms | 1,163 spans/sec | 3,371 spans/sec | 2.90배 |
|    100 |    ON   |    90.740ms |    29.354ms | 1,102 spans/sec | 3,407 spans/sec | 3.09배 |
|  1,000 |  OFF 1차 |   918.588ms |   297.324ms | 1,089 spans/sec | 3,363 spans/sec | 3.09배 |
|  1,000 |  ON 1차  |   884.527ms |   289.700ms | 1,131 spans/sec | 3,452 spans/sec | 3.05배 |
|  1,000 |  ON 2차  |   895.522ms |   305.014ms | 1,117 spans/sec | 3,279 spans/sec | 2.94배 |
|  1,000 |  OFF 2차 |   912.754ms |   301.204ms | 1,096 spans/sec | 3,320 spans/sec | 3.03배 |
|  5,000 |   OFF   | 4,715.305ms | 1,610.337ms | 1,060 spans/sec | 3,105 spans/sec | 2.93배 |
|  5,000 |    ON   | 4,853.439ms | 1,678.639ms | 1,030 spans/sec | 2,979 spans/sec | 2.89배 |

### 해석

* JDBC batch는 모든 구간에서 약 2.9~3.1배 높은 처리량을 보였다.
* Batch 저장 채택을 측정 결과로 정당화했다.
* `reWriteBatchedInserts=true`는 일관된 개선 효과가 없었다.
* 5,000 Span에서는 rewrite 활성화 결과가 더 느렸다.

### 결정

* JDBC batch 유지
* `reWriteBatchedInserts` 비활성 상태 유지
* 다음 단계에서 batch chunk 크기 비교

### 측정 제한

측정에 포함되지 않은 항목:

* HTTP 네트워크
* OTLP JSON 파싱
* Collector
* 동시 요청
* 원격 DB
* N100 홈서버
* JVM 최대 heap
* CPU 사용률
* WAL 양
* 실제 DB statement 수

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