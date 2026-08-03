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


# Engineering Log Backfill — 2026-08-03

> 이 기록은 AeroTrace 개발 과정에서 실행하고 확인했지만 당시 문서에 저장하지 못한 내용을 대화 기록을 기준으로 복구한 것이다.
> 실제 수치를 확인하지 못한 항목은 추측하지 않고 `미측정` 또는 `확인 필요`로 표시한다.

---

## 1. Spring Boot 백엔드 기반 구성

### 구현 내용

* Java 21 기반 Spring Boot 4 애플리케이션 구성
* Virtual Threads 활성화
* PostgreSQL/TimescaleDB 연결
* Flyway migration 구성
* Docker Compose 기반 TimescaleDB와 OpenTelemetry Collector 실행 환경 구성

### 검증 결과

* Spring Boot 애플리케이션 정상 기동
* Flyway migration 정상 적용
* TimescaleDB 연결 정상
* 애플리케이션 종료 시 HikariCP 정상 종료 확인

---

## 2. OTLP JSON Trace 수신 및 저장

### 구현 내용

* `POST /v1/traces` OTLP JSON 수신 API 구현
* resource, scope, span 구조 파싱
* `service.name` 추출
* Trace ID와 Span ID 형식 검증
* all-zero ID 거부
* Span 시작·종료 시간 검증
* enum 값 검증
* attributes, events, links 파싱
* uint32 범위 검증
* 요청 단위 transaction 적용
* Spring JDBC batch insert 적용
* 중복 Span에 `ON CONFLICT DO NOTHING` 적용

### 제한 설정

```yaml
aerotrace:
  ingest:
    max-spans-per-request: 5000
    max-request-body-bytes: 10485760
    jdbc:
      batch-size: 1000
```

### 검증 결과

* 요청당 최대 5,000 Span 제한 확인
* 최대 요청 본문 크기 10 MiB 제한 확인
* 제한 초과 시 HTTP 413 반환
* 제한 초과 요청에서 부분 저장 없음
* 잘못된 JSON은 HTTP 400
* 잘못된 Trace ID는 HTTP 400
* 지원하지 않는 Content-Type은 HTTP 415
* 정상 성공 응답은 `{}`

---

## 3. JDBC 단건 저장과 Batch 저장 비교

### 목적

Telemetry 수집 경로에서 JPA 단건 저장보다 JDBC batch가 적합한지 실제 처리량으로 확인한다.

### 측정 결과

* JDBC batch가 단건 저장보다 중앙값 기준 약 2.9~3.1배 빠름
* Batch 저장 처리량은 약 3.1k~3.5k spans/s 범위로 관찰됨

### Batch 크기별 5,000 Span 처리 결과

| Batch 크기 |      총 처리시간 |          처리량 |
| -------: | ----------: | -----------: |
|       50 | 2207.706 ms | 2265 spans/s |
|      100 | 1679.864 ms | 2976 spans/s |
|      250 | 1950.707 ms | 2563 spans/s |
|      500 | 1634.554 ms | 3059 spans/s |
|     1000 | 1712.393 ms | 2920 spans/s |
|     2000 | 1711.864 ms | 2921 spans/s |
|     5000 | 1698.537 ms | 2944 spans/s |

### 결론

* 가장 빠른 단일 결과만 보면 batch 500이 우세했다.
* batch 500, 1000, 2000, 5000 사이의 차이는 크지 않았다.
* 한 번에 DB로 전달되는 데이터와 메모리 사용량을 제한하면서 안정적인 운영값을 사용하기 위해 batch size 1000을 선택했다.
* batch size 1000은 절대적인 최적값이 아니라 현재 환경의 초기 운영값이다.
* `reWriteBatchedInserts=true`는 일관된 개선 효과가 확인되지 않아 적용하지 않았다.

---

## 4. 멀티테넌트 Project API Key

### 데이터 모델

`project_api_keys` 테이블에 다음 정보를 저장한다.

* API Key 행 ID
* Tenant ID
* Project ID
* Key 이름
* 공개 식별자인 `key_id`
* SHA-256 `secret_hash`
* 생성 시각
* 만료 시각
* 폐기 시각

### API Key 형식

```text
atr_<16-character-key-id>.<43-character-secret>
```

### 보안 설계

* Secret은 32바이트 SecureRandom으로 생성
* Base64 URL-safe, padding 없이 인코딩
* DB에는 원문 Secret을 저장하지 않음
* 인코딩된 Secret 문자열의 SHA-256 hash만 저장
* hash 비교에 `MessageDigest.isEqual` 사용
* DTO와 record에서 byte array 방어적 복사
* `toString()`에서 민감정보 노출 방지
* 존재하지 않는 Key 조회에도 dummy hash 비교 적용
* 로그와 metric tag에 원문 API Key와 key ID를 기록하지 않음

### 인증 결과 분류

* success
* missing_credentials
* invalid_authorization
* malformed_key
* unknown_key
* secret_mismatch
* expired
* revoked
* lookup_error

### HTTP 인증

* `POST /v1/traces`에만 API Key 인증 Filter 적용
* `Authorization: Bearer <api-key>` 사용
* Header가 없거나 잘못된 경우 HTTP 401
* 인증 실패 응답에 `WWW-Authenticate` 적용
* Tenant ID와 Project ID를 클라이언트 Header에서 신뢰하지 않음
* 인증된 API Key의 DB 소유권에서 Tenant와 Project를 결정
* 클라이언트가 Tenant/Project UUID Header를 위조해도 저장 대상에 영향을 주지 않음
* 폐기된 Key는 HTTP 401

### 검증 결과

* 정상 API Key 인증 성공
* Header 누락 401
* 잘못된 API Key 401
* 잘못된 Key 형식 401
* 만료 Key 거부
* 폐기 Key 거부
* Tenant/Project 위조 Header 무시
* 인증된 Project 소속으로 Span 저장

---

## 5. 인증 관측 지표

### Micrometer 지표

```text
aerotrace.auth.api_key.attempts
aerotrace.auth.api_key.lookup.duration
```

### 설계 원칙

* 고정된 outcome과 reason만 tag로 사용
* tenantId, projectId, keyId, API Key, IP 주소를 tag에 사용하지 않음
* 인증 Header 단계 실패와 DB 조회 실패를 분리
* DB 조회를 하지 않은 요청은 lookup timer에 포함하지 않음

### Actuator

* `/actuator/health` 노출
* `/actuator/metrics` 노출
* 로컬 개발 환경에서 metric 조회 확인

### 남은 운영 위험

* Actuator endpoint는 현재 인증되지 않은 로컬 개발 구성
* 공개 배포 전 관리 포트 분리, 방화벽 또는 Spring Security 적용 필요
* 현재 metric은 애플리케이션 메모리에만 존재해 재시작 시 초기화됨

---

## 6. DB 장애 응답 처리

### 목적

Collector가 일시적인 DB 장애를 영구 실패로 처리하지 않고 재시도할 수 있게 한다.

### 구현 내용

* API Key 조회 DB 장애를 `ProjectApiKeyLookupUnavailableException`으로 변환
* DB 연결 및 일시적 자원 오류만 retryable 장애로 분류
* SQL 문법 오류와 프로그래밍 오류를 503으로 숨기지 않음
* 인증 DB 장애는 HTTP 503 반환
* Span 저장 DB 장애도 HTTP 503 반환
* DB 장애 응답에는 `WWW-Authenticate`를 넣지 않음
* Hikari connection timeout을 3초로 제한

### 테스트

* 가짜 Credential Store에서 DB 장애를 발생시키는 Filter 단위 테스트 작성
* Filter가 HTTP 503을 반환하는지 검증
* Filter chain이 호출되지 않는지 검증
* `WWW-Authenticate`가 없는지 검증
* 전체 Gradle 테스트 성공

### 실제 장애 검증

* TimescaleDB 중지
* 동일 API Key로 `/v1/traces` 요청
* Backend가 HTTP 503 반환
* 인증 lookup error metric 증가 확인
* TimescaleDB 복구
* 같은 API Key로 다시 요청
* API Key 재발급 없이 HTTP 200 복구 확인

### 미측정

* 실제 HTTP 503 응답시간은 기록하지 못함

---

## 7. Collector에서 AeroTrace로 Trace 전달

### 구성

* OpenTelemetry Collector Contrib `0.157.0`
* OTLP HTTP exporter 사용
* JSON encoding 사용
* 압축은 `none`
* 환경 변수로 API Key 주입
* 실제 Secret 파일은 Git에서 제외
* Backend endpoint는 Docker Desktop의 `host.docker.internal:8080` 사용

### 전달 경로

```text
OTLP Client
→ Collector OTLP Receiver
→ Batch Processor
→ Persistent Sending Queue
→ OTLP HTTP JSON Exporter
→ AeroTrace Backend
→ TimescaleDB
```

### 검증 결과

* Collector의 `localhost:4318/v1/traces`가 요청 수신
* Collector 요청에는 API Key가 없어도 됨
* Exporter가 Backend 요청에 Bearer API Key 추가
* Backend 인증 성공
* TimescaleDB에 검증 Span 1개 저장
* 저장된 Tenant와 Project가 API Key 소속과 일치
* `duration_nano = 5,000,000` 확인

### 운영 위험

* Collector receiver 4317/4318은 현재 인증이 없는 로컬 개발 구성
* 인터넷에 직접 공개하면 안 됨

---

## 8. Collector Persistent Queue 구성

### 구성

* `file_storage/aerotrace` extension
* Docker named volume `aerotrace-otelcol-data`
* Collector 비-root UID를 위한 storage init 컨테이너
* persistent sending queue 적용
* retry 활성화
* retry 최대 경과시간 제한 없음
* queue size 50,000
* queue sizer는 `items`
* Trace의 `items`는 Span 수 기준
* queue consumer 2개

### 설정값

```yaml
sending_queue:
  enabled: true
  num_consumers: 2
  sizer: items
  queue_size: 50000
  block_on_overflow: false
  storage: file_storage/aerotrace
```

### 100 Span 장애 복구 실험

실험 절차:

1. 대상 데이터 삭제 및 DB 행 수 0 확인
2. TimescaleDB 중지
3. Collector에 100 Span 전송
4. Collector HTTP 응답 200
5. Backend가 503 반환
6. Collector가 retry 수행
7. TimescaleDB가 중지된 상태에서 Collector 재시작
8. Collector가 persistent queue metadata를 다시 로드
9. TimescaleDB 복구
10. 실행기를 다시 실행하지 않고 DB 결과 확인

최종 결과:

```text
total_rows        = 100
distinct_span_ids = 100
first_span_name   = persistent-queue-span-001
last_span_name    = persistent-queue-span-100
```

결론:

* DB 장애 중 100 Span이 Collector에 보관됨
* Collector 재시작 후 queue 데이터가 복구됨
* DB 복구 후 100 Span이 자동 재전송됨
* 최종 DB 결과에서 데이터 유실이 관찰되지 않음
* 최종 DB 결과에서 중복 행이 관찰되지 않음

### 실험 중 발견한 문제

첫 번째 실행에서는 다음 결과가 발생했다.

```text
total_rows        = 200
distinct_span_ids = 100
```

Collector 재시작 로그에도 총 200 Span, queue item 2개가 표시됐다.

원인:

* 검증 실행기가 두 번 실행됐거나
* 이전 queue 항목이 남은 상태에서 새로운 100 Span을 추가함
* 검증 실행기는 실행 때마다 새로운 `start_time`을 생성하므로 동일한 span_id라도 DB unique identity가 달라짐

조치:

* DB와 queue가 비워진 상태에서 재실험
* 실행기를 정확히 한 번만 실행
* 최종 100행, 고유 span_id 100개 확인

---

## 9. Collector 내부 지표

### Endpoint

```text
http://localhost:8888/metrics
```

Docker port는 로컬에서만 접근하도록 바인딩했다.

```yaml
127.0.0.1:8888:8888
```

### 확인한 지표

```text
otelcol_receiver_accepted_spans
otelcol_receiver_refused_spans
otelcol_exporter_sent_spans
otelcol_exporter_queue_size
otelcol_exporter_queue_capacity
```

정상 요청 확인:

```text
receiver_accepted_spans > 0
receiver_refused_spans = 0
exporter_sent_spans > 0
```

실패가 발생하지 않은 실행에서는 다음 지표가 출력되지 않을 수 있음을 확인했다.

```text
otelcol_exporter_send_failed_spans
otelcol_exporter_enqueue_failed_spans
```

이 경우 metric이 없거나 0이면 정상으로 판단한다.

### 100 Span queue 지표 실험

DB 장애 중:

```text
queue_capacity = 50000
queue_size     = 100
```

DB 복구 후:

```text
queue_size = 0
```

결론:

* DB 장애 중 queue가 실제로 증가함
* DB 복구 후 자동으로 queue가 비워짐
* enqueue 실패는 관찰되지 않음

---

## 10. 10,000 Span Queue 실험

### 실험 목적

queue capacity 50,000 중 20%에 해당하는 10,000 Span을 장애 중 저장할 수 있는지 확인한다.

### 확인된 결과

```text
queue_capacity = 50000
DB 장애 중 queue_size = 10000
DB 복구 후 queue_size = 0
enqueue_failed_spans = 없거나 0
```

### 결론

* Collector가 DB 장애 중 10,000 Span을 queue에 수용함
* queue capacity 초과 없음
* enqueue 실패가 관찰되지 않음
* DB 복구 후 queue가 자동으로 비워짐

### 확인 필요

다음 결과는 대화에 실제 출력값이 남아 있지 않아 완료로 단정하지 않는다.

```text
최종 DB total_rows
최종 DB distinct_span_ids
```

확인 쿼리:

```sql
SELECT COUNT(*) AS total_rows,
       COUNT(DISTINCT span_id) AS distinct_span_ids,
       MIN(name) AS first_span_name,
       MAX(name) AS last_span_name
FROM spans
WHERE service_name =
      'collector-queue-load-verification';
```

### 미측정

* Collector 수신 소요시간
* Collector 수신 처리량
* persistent queue 저장소 실험 전 크기
* persistent queue 저장소 실험 후 크기
* Span당 추정 queue 디스크 크기
* 정확한 queue drain 시간
* queue drain 처리량

정확한 drain 시간은 DB 복구 후 첫 metric 조회 시 이미 queue size가 0이어서 측정하지 못했다. 자동 복구는 확인했지만 시간을 추측해서 기록하지 않는다.

---

## 11. 장애 처리 중 발견한 운영 특성

### Backend 미실행

Collector는 정상적으로 Span을 수신했지만 Backend가 실행되지 않았을 때 다음 오류가 발생했다.

```text
connect: connection refused
```

동작:

* Collector receiver는 요청을 수신
* Backend 연결 실패
* persistent queue에 데이터 유지
* exporter가 retry 반복
* Backend 실행 후 자동 전송

### 교훈

* Collector 수신 HTTP 200은 DB 저장 완료를 의미하지 않음
* receiver metric, exporter metric, queue metric, DB 최종 결과를 함께 확인해야 함
* `send_failed_spans`가 없다고 retry가 없었던 것은 아님
* 일시적인 retry 성공은 최종 실패 Counter로 남지 않을 수 있음
* queue size와 enqueue failure가 데이터 유실 위험 판단에 더 직접적인 지표임

---

## 12. 현재 검증 범위와 남은 위험

### 검증 완료

* JDBC batch 저장
* 요청 단위 transaction
* 요청 크기와 Span 개수 제한
* API Key 기반 멀티테넌트 인증
* Tenant/Project 위조 Header 방지
* API Key 폐기와 만료 처리
* 인증 성공·실패 metric
* DB 장애의 HTTP 503 변환
* Collector OTLP HTTP JSON 전달
* Collector retry
* persistent queue
* Collector 재시작 후 queue 복구
* DB 복구 후 100 Span 자동 저장
* queue size 100과 10,000 관찰
* queue 복구 후 size 0 확인

### 아직 검증하지 않음

* queue 50,000 초과 시 동작
* `block_on_overflow: false`에서 실제 drop 수량
* 디스크 공간 부족
* file storage 쓰기 오류
* Docker 호스트 강제 종료
* 실제 전원 차단
* 장시간 DB 장애
* 장시간 고유입률 상황
* 정확한 queue drain 처리량
* 실제 평균 Span 크기
* queue의 Span당 디스크 사용량
* retention과 compression
* 운영 알림
* Prometheus/Grafana 연동

---

## 2026-08-03 — TimescaleDB Columnstore 정책 검증

### 목적

최근 telemetry는 쓰기 성능이 유리한 rowstore에 유지하고, 오래된 telemetry는 columnstore로 자동 전환되는지 검증한다.

### 환경

* TimescaleDB: 2.28.3
* Hypertable: `public.spans`
* Partition column: `start_time`
* Chunk interval: 1일
* Columnstore 전환 기준: 2일
* Segment 기준: `tenant_id, project_id`
* 정렬 기준: `start_time DESC`

### 검증 절차

1. Columnstore 자동 정책을 일시 중지
2. 현재 시각보다 4일 전인 테스트 Span 전송
3. 테스트 Span이 과거 1일 chunk에 저장됐는지 확인
4. 정책 실행 전 `is_compressed = false` 확인
5. `run_job`으로 columnstore 정책 수동 실행
6. 정책 실행 성공 여부 확인
7. 대상 chunk가 `is_compressed = true`로 변경됐는지 확인
8. 전환된 Span이 기존 hypertable 조회로 계속 검색되는지 확인
9. 현재 시각의 최근 Span 신규 전송
10. 최근 Span이 rowstore chunk에 저장되는지 확인
11. 자동 정책 스케줄 재활성화

### 결과

* 4일 전 테스트 Span 저장 성공
* 대상 과거 chunk의 전환 전 상태: rowstore
* Columnstore 정책 수동 실행 성공
* 대상 과거 chunk의 전환 후 상태: columnstore
* Columnstore 전환 후 테스트 Span 조회 성공
* 최근 Span 신규 저장 성공
* 최근 Span이 저장된 chunk는 rowstore 상태 유지
* Columnstore 정책 자동 스케줄 재활성화 완료

### 저장 크기 측정

Columnstore 전환 전후 크기는 조회했지만 정확한 byte 값은 대화 기록에 남기지 않았다.

이번 실험은 Span 1개가 들어 있는 작은 chunk를 사용했으므로 압축률 평가가 아니라 정책 동작과 데이터 조회 정합성 검증을 목적으로 한다.

실제 압축률은 운영 환경과 유사한 대량 telemetry를 적재한 뒤 다시 측정한다.

### 확인된 데이터 흐름

```text
최근 Span
→ 현재 1일 chunk
→ rowstore

2일보다 오래된 완성 chunk
→ columnstore 정책 대상
→ columnstore 전환

전환된 데이터
→ public.spans를 통한 기존 조회 방식 유지
```

### 남은 검증

* 대량 Span이 저장된 chunk의 실제 압축률
* Columnstore 전환 전후 조회 성능
* Columnstore 정책 실패 시 운영 경보
* 장시간 정책 미실행 시 저장 공간 영향
* Retention 정책과 Columnstore 정책의 연계 동작

---

## 2026-08-03 — TimescaleDB Retention 실제 삭제 검증

### 목적

`start_time` 기준 30일 보존 정책이 만료된 chunk만 삭제하고, 보존기간 내 데이터는 유지하는지 검증한다.

### 환경과 정책

* TimescaleDB: 2.28.3
* Hypertable: `public.spans`
* Partition column: `start_time`
* Chunk interval: 1일
* Columnstore 전환 기준: 2일
* Retention 기준: 30일
* Retention 실행 주기: 1일

### 검증 데이터

* 35일 전 Span: retention 삭제 대상
* 4일 전 Span: columnstore 상태의 보존 대상
* 현재 시각 Span: rowstore 상태의 보존 대상

### 안전 검증

Retention은 개별 행이 아닌 chunk 전체를 제거하므로 정책 실행 전에 다음을 확인했다.

* 35일 전 테스트 Span이 별도 chunk에 저장됨
* 대상 chunk에 테스트 Span만 존재함
* 대상 chunk의 테스트 외 행 수가 0임
* `show_chunks(... older_than => INTERVAL '30 days')` 결과가 테스트 chunk 하나뿐임
* 다른 30일 초과 chunk가 삭제 후보에 포함되지 않음

### 실행 절차

1. Columnstore와 retention background job 일시 중지
2. 35일 전 테스트 Span 한 개 전송
3. 테스트 Span의 물리 chunk 확인
4. 삭제 대상 chunk의 전체 행과 테스트 외 행 확인
5. 삭제 대상 전체 chunk 미리 보기
6. 보존 대상 데이터 수량 저장
7. Retention job 수동 실행
8. 정책 실행 성공 여부 확인
9. 삭제 전후 데이터 수량 비교
10. 두 background job 자동 실행 재활성화

### 결과

* Retention job 수동 실행 성공
* 35일 전 테스트 Span 삭제 확인
* 삭제 대상 chunk 제거 확인
* 4일 전 columnstore Span 보존 확인
* 현재 시각의 최근 Span 보존 확인
* 전체 감소 행 수가 삭제 대상 테스트 행 수와 일치
* 30일 초과 삭제 후보 chunk가 남지 않음
* Columnstore 정책 자동 실행 복구
* Retention 정책 자동 실행 복구

### 확인된 수명주기

```text
0일 이상 2일 미만
→ rowstore
→ 최근 수집과 장애 분석

2일 이상 30일 미만
→ columnstore
→ 장기 조회와 저장 공간 절감

30일 이상
→ retention 정책
→ chunk 단위 삭제
```

### 중요한 운영 특성

* Retention은 `ingested_at`이 아니라 `start_time` 기준으로 동작한다.
* 늦게 도착한 오래된 Span은 저장 직후 다음 retention 실행 대상이 될 수 있다.
* Retention은 행 단위 `DELETE`가 아니라 chunk 단위 제거다.
* 하나의 오래된 chunk에 여러 Tenant 데이터가 있으면 함께 제거된다.
* 현재 공유 hypertable 구조에서는 전역 보존기간이 적용된다.

### 미측정 항목

* 대량 데이터에서 columnstore 압축률
* Columnstore 전환 전후 조회 속도
* Retention 실행 시간
* Retention 실행 중 CPU와 디스크 I/O
* 실제 운영 환경의 일일 저장 증가량
* 30일 보관에 필요한 예상 디스크 크기

이 값들은 운영과 유사한 데이터가 쌓인 뒤 재측정한다.

---

## 2026-08-03 — 인증된 Trace 목록·상세 조회 구현

### 구현 범위

인증된 Project의 Trace를 조회하기 위한 다음 API를 구현했다.

```text
GET /api/v1/traces
GET /api/v1/traces/{traceId}
```

목록 API는 다음 조건을 지원한다.

* `from`
* `to`
* `limit`
* `cursor`
* `serviceName`
* `errorOnly`
* `minSpanDurationNano`

상세 API는 Trace ID에 속한 Span을 시작 시각 순서로 반환한다.

### 멀티테넌트 경계

클라이언트가 Tenant ID나 Project ID를 요청 파라미터 또는 Header로 지정하지 않는다.

API Key 인증 결과인 `AuthenticatedProject`에서 다음 값을 가져와 Repository에 전달한다.

```text
tenantId
projectId
```

모든 목록·상세 SQL은 두 값을 함께 조건으로 사용한다.

서로 다른 Project에 동일한 Trace ID와 Span ID를 저장한 통합 테스트를 구성해 다음을 확인했다.

* Project A 조회에 Project B 전용 Trace가 포함되지 않음
* Project B 조회에 Project A 전용 Trace가 포함되지 않음
* 동일한 Trace ID의 Span 집계가 Project 간 합쳐지지 않음
* Trace 상세 Span도 Project 간 섞이지 않음

### Trace 목록 집계

목록에서는 Trace ID 단위로 다음 값을 계산한다.

* 최초 Span 시작 시각
* 전체 Span 수
* 고유 서비스 수
* 가장 긴 Span duration

서비스, 오류, duration 필터는 Trace를 선택하는 조건으로 사용하지만 집계값은 Trace 전체를 기준으로 유지한다.

통합 테스트에서 서비스 조건과 오류 조건이 서로 다른 Span에서 충족되는 Trace도 정상적으로 조회되는 것을 확인했다.

### Cursor Pagination

정렬 기준:

```text
traceStartTime DESC
traceId DESC
```

Cursor에는 다음 값이 포함된다.

```text
traceStartTime
traceId
queryFingerprint
```

사용자 요청 limit보다 한 건 더 조회한다.

* 조회 결과가 limit 이하이면 마지막 페이지
* 조회 결과가 limit보다 많으면 마지막 반환 항목으로 다음 Cursor 생성

Cursor fingerprint에는 Tenant, Project, 시간 범위와 모든 목록 필터를 포함한다.

다음 조건을 변경한 뒤 이전 Cursor를 사용하면 요청을 거부한다.

* 시간 범위
* 서비스명
* 오류 필터
* 최소 Span duration
* 인증 Project

### Trace 상세 제한

Trace 상세 조회는 최대 5,000개 Span을 응답한다.

내부적으로 5,001개까지 조회해 다음을 구분한다.

* 5,000개 이하: 정상 응답
* 5,001개 조회: 허용 크기를 초과한 Trace로 판단

응답 계약:

* 존재하지 않는 Trace: `404`
* 잘못된 Trace ID: `400`
* 5,000개를 초과한 Trace: `422`
* 인증 누락 또는 실패: `401`

### 입력 제한

* 목록 최대 반환 수: 200
* 최대 조회 시간 범위: 30일
* 서비스명: 공백 불가, 최대 255자
* `errorOnly`: `true` 또는 `false`
* `minSpanDurationNano`: 0 이상의 정수
* Trace ID: 0이 아닌 32자리 소문자 16진수

### 수행한 자동 검증

* Service 단위 테스트
* Controller HTTP 계약 테스트
* 인증 Filter 경로 테스트
* Cursor encode/decode 테스트
* Cursor 조회 조건 fingerprint 테스트
* 실제 TimescaleDB Repository 통합 테스트
* Project 간 동일 Trace ID 격리 테스트
* Cursor 페이지 중복 방지 테스트
* 서비스·오류·duration 조합 필터 테스트
* 전체 Gradle 회귀 테스트

### 수행한 실제 HTTP 검증

통제된 fixture Trace를 직접 삽입한 뒤 다음을 확인했다.

* 첫 페이지의 `nextCursor` 생성
* 두 번째 페이지에서 다른 Trace 반환
* 페이지 간 Trace 중복 없음
* 마지막 페이지의 `nextCursor = null`
* `errorOnly` 변경 후 이전 Cursor 사용 시 `400`
* `serviceName` 변경 후 이전 Cursor 사용 시 `400`
* `minSpanDurationNano` 변경 후 이전 Cursor 사용 시 `400`
* 검증 후 fixture 데이터 삭제

### 구현 중 발생한 문제

#### Cursor 범위 테스트가 예상 예외보다 먼저 실패

Query fingerprint를 계산하면서 Mockito Mock의 Tenant ID와 Project ID를 먼저 읽었다.

테스트에서 두 값을 stub하지 않아 기대했던 Cursor 범위 오류보다 `tenantId must not be null` 오류가 먼저 발생했다.

검증 순서를 다음처럼 변경했다.

```text
기본 요청 검증
→ 서비스명·duration 검증
→ Cursor 시간 범위 검증
→ Query fingerprint 계산
→ Cursor fingerprint 비교
→ Repository 조회
```

이를 통해 범위를 벗어난 Cursor는 불필요한 fingerprint 계산 전에 차단된다.

#### 실제 Pagination 검증 데이터 부족

기존 데이터에서 검색 조건에 맞는 Trace가 없어서 첫 페이지가 비어 있었고 `nextCursor`가 생성되지 않았다.

원인은 구현이 아니라 수동 검증 절차가 데이터 전제를 명시하지 않은 것이었다.

동일 조건에 맞는 Trace 두 개를 직접 삽입하는 fixture 절차로 수정하고, 검증 완료 후 삭제했다.

### 현재 확인되지 않은 항목

다음 값은 아직 측정하지 않았다.

* Trace 수 증가에 따른 목록 쿼리 실행시간
* 서비스·오류·duration 필터별 실행계획 차이
* Rowstore와 Columnstore 구간의 조회 성능 차이
* Buffer hit와 실제 disk read
* 동시 조회 시 DB connection 사용량
* API 응답 p50, p95, p99
* 추가 인덱스 적용 효과
* Trace summary 테이블 또는 Continuous Aggregate의 효과

### 다음 작업

대표 데이터셋을 생성하고 다음 쿼리의 성능 기준선을 측정한다.

1. 필터 없는 최근 Trace 목록
2. 서비스 필터
3. 오류 필터
4. 최소 Span duration 필터
5. 세 필터 조합
6. 첫 페이지와 Cursor 다음 페이지
7. Trace 상세 조회

측정 전에는 새로운 인덱스를 추가하지 않는다.
