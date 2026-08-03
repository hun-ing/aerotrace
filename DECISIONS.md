# AeroTrace 설계 결정 기록

> 마지막 업데이트: 2026-07-27

이 문서는 AeroTrace의 주요 설계 결정과 선택 이유, 단점, 재검토 조건을 기록한다.

---

## D-001. Java 21과 Spring Boot 4 사용

### 상태

채택

### 해결하려는 문제

장기적으로 유지 가능한 Java 기반 백엔드를 구성하고, 높은 동시성의 OTLP 요청을 비교적 적은 플랫폼 스레드로 처리할 기반이 필요했다.

### 검토한 대안

1. Java 17
2. Java 21
3. Java 25 이상
4. Spring Boot 3
5. Spring Boot 4

### 선택

* Java 21
* Spring Boot 4.1.0
* Virtual Threads 활성화

### 선택 이유

* Java 21은 LTS 버전이다.
* Virtual Threads를 안정적으로 사용할 수 있다.
* Spring Boot 4에서 Java 21이 공식 지원된다.
* 최신 Java 생태계를 사용하면서도 초기 프로젝트 위험을 제한할 수 있다.
* OTLP 수신처럼 다수의 I/O 요청을 처리하는 서비스에서 Virtual Threads를 직접 검증할 수 있다.

### 단점과 위험

* Spring Boot 4와 Jackson 3의 변경점을 학습해야 한다.
* 일부 서드파티 라이브러리의 Boot 4 호환성을 확인해야 한다.
* Virtual Threads가 DB connection 수 자체를 증가시키는 것은 아니므로 HikariCP 병목은 별도로 관리해야 한다.

### 재검토 조건

* Java 25 LTS 마이그레이션 검토 시점
* Spring Boot 또는 사용 라이브러리의 지원 정책 변화
* Virtual Threads 관련 운영 문제가 확인될 때

---

## D-002. 모듈형 모놀리스로 시작

### 상태

채택

### 해결하려는 문제

초기 MVP에서 배포와 운영 복잡도를 낮추면서도 수집, Tenant 관리, 조회 기능의 책임을 구분해야 했다.

### 검토한 대안

1. 단일 패키지 구조
2. 모듈형 모놀리스
3. 초기 마이크로서비스
4. 메시지 브로커 기반 수집 파이프라인

### 선택

하나의 Spring Boot 애플리케이션 안에서 책임별 패키지를 분리한다.

```text
ingest
tenant
query
common
```

### 선택 이유

* 현재 규모에서 서비스 분리는 운영 부담이 더 크다.
* 트랜잭션과 로컬 호출을 단순하게 유지할 수 있다.
* 추후 실제 병목이 확인되면 모듈 경계를 기준으로 분리할 수 있다.
* N100 홈서버와 Oracle Cloud 무료 환경에 적합하다.

### 단점과 위험

* 기능이 증가하면 모듈 간 의존성 관리가 필요하다.
* 한 프로세스 장애가 전체 기능에 영향을 줄 수 있다.
* 수집과 조회 부하가 경쟁할 수 있다.

### 재검토 조건

* 수집과 조회가 서로 다른 확장 요구를 가질 때
* 배포 독립성이 실제로 필요해질 때
* 단일 프로세스 자원 경합이 측정될 때

---

## D-003. TimescaleDB를 Trace 원본 저장소로 사용

### 상태

채택

### 해결하려는 문제

시간 범위 기반으로 대량의 Span을 저장하고 조회하면서 PostgreSQL 생태계를 유지할 저장소가 필요했다.

### 검토한 대안

1. 일반 PostgreSQL
2. TimescaleDB
3. Elasticsearch
4. ClickHouse
5. 별도 시계열 데이터베이스

### 선택

PostgreSQL 15 기반 TimescaleDB hypertable을 사용한다.

* 시간 컬럼: `start_time`
* 초기 chunk interval: 1일

### 선택 이유

* PostgreSQL SQL, JSONB, FK, transaction을 그대로 사용할 수 있다.
* 시계열 chunk, retention, compression을 추후 사용할 수 있다.
* MVP에서 별도 검색 클러스터를 운영하지 않아도 된다.
* Tenant와 Project 같은 control-plane 데이터와 같은 DB에서 시작할 수 있다.
* 제한된 홈서버 환경에서 운영 구성이 단순하다.

### 단점과 위험

* Trace 전문 검색 엔진보다 자유로운 검색 성능이 낮을 수 있다.
* JSONB 검색이 증가하면 인덱스 비용이 커질 수 있다.
* 수집량이 커지면 단일 DB의 CPU, 디스크, WAL이 병목이 될 수 있다.

### 재검토 조건

* TimescaleDB 단일 노드 저장 처리량 한계 도달
* Attribute 검색 요구가 크게 증가
* 장기 저장 비용이 감당하기 어려워질 때
* 실제 조회 패턴에서 다른 저장소가 명확히 유리할 때

---

## D-004. Tenant와 Project를 Span의 복합 외래키로 검증

### 상태

채택

### 해결하려는 문제

존재하는 Tenant ID와 Project ID를 각각 전달하더라도 서로 다른 Tenant와 Project가 잘못 조합될 수 있다.

### 검토한 대안

1. 애플리케이션 코드에서만 검증
2. `project_id`만 외래키로 검증
3. `tenant_id`, `project_id` 각각 별도 외래키
4. `(tenant_id, project_id)` 복합 외래키

### 선택

```sql
FOREIGN KEY (tenant_id, project_id)
REFERENCES projects (tenant_id, id)
```

### 선택 이유

* DB가 Tenant와 Project 소유 관계를 최종적으로 보장한다.
* 애플리케이션 버그가 발생해도 잘못된 멀티테넌트 조합 저장을 차단한다.
* 조회와 저장 모두 Tenant 범위를 명확히 유지할 수 있다.

### 단점과 위험

* 인덱스와 외래키 유지 비용이 발생한다.
* 대량 수집 시 FK 검증 비용을 측정해야 한다.

### 재검토 조건

* FK 검증이 실제 수집 병목으로 측정될 때
* Tenant별 물리적 DB 분리 구조로 변경할 때

---

## D-005. Collector 재전송 중복을 유일 인덱스로 방지

### 상태

채택

### 해결하려는 문제

Collector는 네트워크나 서버 오류 시 같은 Span을 다시 전송할 수 있다. 중복 저장이 발생하면 Trace 조회, 집계, 저장량이 왜곡된다.

### 검토한 대안

1. 중복 허용
2. 애플리케이션에서 선조회 후 INSERT
3. Redis 등 외부 deduplication 저장소
4. DB unique index와 `ON CONFLICT DO NOTHING`

### 선택

```sql
UNIQUE (
    tenant_id,
    project_id,
    trace_id,
    span_id,
    start_time
)
```

저장 시:

```sql
ON CONFLICT DO NOTHING
```

### 선택 이유

* 선조회와 INSERT 사이의 경쟁 조건을 피할 수 있다.
* 별도 저장소 없이 DB가 원자적으로 중복을 차단한다.
* Collector 재전송 시 오류 대신 idempotent 성공 처리가 가능하다.
* TimescaleDB unique index 규칙상 시간 파티션 컬럼을 포함해야 한다.

### 단점과 위험

* Unique index 저장 공간과 쓰기 비용이 발생한다.
* `start_time`까지 동일해야 중복으로 판단된다.
* 잘못된 timestamp 변환이 발생하면 같은 Span이 다른 행으로 저장될 수 있다.

### 재검토 조건

* Collector retry 실험에서 다른 중복 형태가 발견될 때
* 인덱스 비용이 실제 병목으로 측정될 때
* Protobuf 원본 식별자 보존 방식을 변경할 때

---

## D-006. 자주 조회하는 값은 컬럼, 나머지는 JSONB로 저장

### 상태

채택

### 해결하려는 문제

OpenTelemetry Attributes는 구조와 키가 동적으로 변하지만, 모든 조회를 JSONB 기반으로 수행하면 반복 파싱과 인덱스 비용이 발생할 수 있다.

### 검토한 대안

1. 모든 데이터를 일반 컬럼으로 저장
2. 모든 데이터를 JSONB로 저장
3. 핵심 필드는 일반 컬럼, 동적 데이터는 JSONB
4. EAV 형태의 Attribute 별도 테이블

### 선택

일반 컬럼:

* `service_name`
* `trace_id`
* `span_id`
* `name`
* `span_kind`
* `status_code`
* `start_time`
* `end_time`
* `duration_nano`
* Tenant와 Project 식별자

JSONB:

* Resource Attributes
* Span Attributes
* Events
* Links

### 선택 이유

* 주요 대시보드와 Trace 조회 필드를 빠르게 필터링할 수 있다.
* OpenTelemetry의 동적 Attribute 구조를 보존할 수 있다.
* 신규 Attribute가 추가될 때 migration이 필요하지 않다.
* EAV 구조보다 저장과 조회 구현이 단순하다.

### 단점과 위험

* 일부 값이 일반 컬럼과 JSONB에 중복 저장된다.
* 임의 Attribute 검색은 GIN 인덱스 없이는 느릴 수 있다.
* JSONB 크기가 증가하면 저장량과 WAL이 증가한다.

### 재검토 조건

* 실제 Attribute 검색 API 구현
* JSONB 조회 성능 측정
* GIN 인덱스 적용 전후 비교
* 평균 Span 크기와 저장량 측정

---

## D-007. OTLP 요청을 엄격하게 검증

### 상태

채택

### 해결하려는 문제

잘못된 식별자, 시간, AnyValue, Event, Link 데이터가 DB까지 전달되면 저장 실패, Trace 손상, 조회 오류가 발생할 수 있다.

### 선택

다음 항목을 애플리케이션 파서에서 검증한다.

* Trace ID와 Span ID 길이 및 16진수 형식
* 전부 0인 ID 거부
* `service.name` 필수
* 시작 시각과 종료 시각 순서
* Span kind와 status code 범위
* OTLP `uint32` 범위
* AnyValue 자료형
* 중복 Attribute key
* Event와 Link 구조
* Link 식별자

### 선택 이유

* 영구적으로 잘못된 요청은 DB 저장 전에 거부할 수 있다.
* 오류 위치를 JSON path로 표현할 수 있다.
* 일부만 저장되는 상황을 피할 수 있다.
* DB 제약조건은 최종 방어선으로 유지한다.

### 단점과 위험

* OTLP 표준보다 엄격한 AeroTrace 정책이 포함될 수 있다.
* `service.name` 누락 요청을 거부하는 정책은 호환성에 영향을 줄 수 있다.
* 하나의 잘못된 Span 때문에 요청 전체가 거부된다.

### 재검토 조건

* Partial Success 응답 도입
* 실제 사용자 서비스에서 호환성 문제가 발생
* Collector에서 이미 보완 가능한 필드가 확인될 때

---

## D-008. Telemetry 저장 경로는 JDBC를 사용

### 상태

채택

### 해결하려는 문제

Span은 요청당 다량으로 저장되며, ORM Entity 관리와 개별 INSERT 비용은 telemetry hot path에 불필요할 수 있다.

### 검토한 대안

1. Spring Data JPA
2. `JdbcTemplate`
3. R2DBC
4. COPY protocol
5. 외부 메시지 브로커

### 선택

* Telemetry 저장: Spring JDBC
* Tenant, Project, API Key 등 control-plane: 추후 JPA 검토

### 선택 이유

* SQL과 batch 동작을 직접 제어할 수 있다.
* `ON CONFLICT DO NOTHING`을 명시적으로 사용할 수 있다.
* JSONB와 TimescaleDB SQL을 직접 다루기 쉽다.
* 현재 규모에서 R2DBC나 메시지 브로커가 필요하지 않다.

### 단점과 위험

* SQL과 파라미터 순서를 직접 관리해야 한다.
* Column 추가 시 INSERT SQL과 바인딩 코드 수정이 필요하다.
* 큰 batch의 메모리와 JDBC 한계를 직접 관리해야 한다.

### 재검토 조건

* JDBC가 측정된 병목이 될 때
* PostgreSQL COPY 방식 비교가 필요할 때
* 데이터 수집과 DB 장애를 분리할 필요가 생길 때

---

## D-009. Span 요청 단위 JDBC batch 사용

### 상태

채택

### 해결하려는 문제

Span마다 `JdbcTemplate.update()`를 호출하면 JDBC 호출과 PreparedStatement 실행 비용이 반복된다.

### 검토한 대안

1. Span별 단건 INSERT
2. 요청 전체 JDBC batch
3. 고정 크기 chunk batch
4. PostgreSQL COPY
5. 메시지 큐 기반 비동기 저장

### 선택

현재는 하나의 OTLP 요청에 포함된 Span을 `JdbcTemplate.batchUpdate()`로 저장한다.

### 측정 조건

* 단건과 batch 모두 요청당 트랜잭션 1개
* JSON 직렬화, 트랜잭션, JDBC 저장 시간을 동일하게 포함
* 저장 결과 검증과 데이터 삭제는 측정에서 제외
* 워밍업 후 반복 측정
* 중앙값 기준 비교

### 측정 결과

| Span 수 |      단건 중앙값 |   Batch 중앙값 |      개선 |
| -----: | ----------: | ----------: | ------: |
|    100 |    91.084ms |    30.460ms | 약 2.99배 |
|  1,000 |   874.985ms |   299.167ms | 약 2.92배 |
|  5,000 | 4,566.945ms | 1,485.885ms | 약 3.07배 |

### 선택 이유

* 모든 측정 구간에서 batch가 약 2.9~3.1배 높은 처리량을 보였다.
* 단건 저장은 약 1,095~1,143 spans/sec였다.
* Batch 저장은 약 3,283~3,365 spans/sec였다.
* 동일한 SQL, 데이터, 트랜잭션, JSON 직렬화 범위에서 반복 검증했다.

### 단점과 위험

* 요청에 포함된 모든 Span을 한 번에 준비해 메모리에 보관한다.
* 비정상적으로 큰 요청은 메모리와 JDBC batch에 부담이 된다.
* 현재 테스트는 HTTP 파싱과 Collector를 포함하지 않았다.
* `JdbcTemplate.batchUpdate()` 호출 수와 실제 DB 네트워크 왕복 수는 동일한 개념이 아니다.

### 재검토 조건

* Batch chunk 크기 측정 완료
* 요청 최대 크기 설계
* N100 홈서버 측정
* End-to-end 부하 테스트
* 메모리 사용량 측정

---

## D-010. `reWriteBatchedInserts` 활성화 보류

### 상태

보류

### 해결하려는 문제

pgJDBC가 JDBC batch INSERT를 multi-values INSERT로 재작성할 때 추가 성능 개선이 가능한지 확인해야 했다.

### 검토한 대안

1. 기본값 `false`
2. `reWriteBatchedInserts=true`

### 측정 결과

| Span 수 |   OFF Batch |    ON Batch |     ON 변화 |
| -----: | ----------: | ----------: | --------: |
|    100 |    30.460ms |    32.836ms | 약 7.8% 악화 |
|  1,000 |   299.167ms |   288.642ms | 약 3.5% 개선 |
|  5,000 | 1,485.885ms | 1,593.576ms | 약 7.2% 악화 |

### 결정

현재 애플리케이션 설정에는 `reWriteBatchedInserts=true`를 적용하지 않는다.

### 결정 이유

* Span 수에 따라 결과 방향이 달라졌다.
* 100 Span과 5,000 Span에서는 오히려 느려졌다.
* 1,000 Span의 개선 결과도 개별 측정 범위와 겹쳤다.
* 일관된 효과가 확인되지 않은 옵션을 운영 설정에 추가할 근거가 부족하다.

### 재검토 조건

* N100 홈서버에서 재측정
* DB가 별도 원격 서버로 분리될 때
* pgJDBC 버전 변경
* Batch chunk 크기 변경
* INSERT SQL 구조 변경


# Recovered Architecture Decisions — 2026-08-03

## JDBC Batch 크기를 1000으로 설정

### 해결하려는 문제

Telemetry Span을 단건 INSERT하면 DB round trip과 statement 실행 비용이 커져 처리량이 낮아진다. 반대로 batch가 지나치게 크면 메모리 사용량, transaction 크기, 장애 시 재처리 범위가 커질 수 있다.

### 검토한 대안

* 단건 JDBC INSERT
* batch 50
* batch 100
* batch 250
* batch 500
* batch 1000
* batch 2000
* batch 5000

### 선택한 방식

```yaml
aerotrace:
  ingest:
    jdbc:
      batch-size: 1000
```

### 선택 이유

* 단건 저장보다 JDBC batch가 약 2.9~3.1배 높은 처리량을 보였다.
* batch 500이 일부 실험에서 가장 빨랐지만 500~5000의 차이가 크지 않았다.
* batch 1000은 충분한 처리량을 유지하면서 한 번의 JDBC 실행 크기를 제한한다.
* Collector batch 설정과도 이해하기 쉬운 초기 정렬값이다.

### 단점과 위험

* 현재 로컬 개발 환경에서 측정한 값이다.
* 실제 Oracle Cloud 또는 홈서버 환경에서는 결과가 달라질 수 있다.
* Span 크기와 DB connection 수에 따라 재조정이 필요하다.

### 재검토 조건

* 운영 장비에서 처리량 측정
* 평균 Span 크기 측정
* DB CPU 또는 connection pool 병목 발생
* batch 처리 지연 증가
* transaction 크기 문제 발생

---

## 원문 API Key를 저장하지 않음

### 해결하려는 문제

DB 유출 또는 로그 노출 시 Project API Key 원문이 노출되면 공격자가 즉시 telemetry를 위조하거나 quota를 소모할 수 있다.

### 검토한 대안

* 원문 API Key 저장
* 암호화된 API Key 저장
* Secret hash만 저장

### 선택한 방식

* 공개 식별자 `key_id` 저장
* Secret의 SHA-256 hash만 저장
* 인증 시 입력 Secret을 hash한 뒤 상수시간 비교
* 발급 시점에만 원문 Key 반환

### 선택 이유

* 비밀번호 저장 방식과 유사한 최소 노출 구조
* DB만 유출됐을 때 원문 API Key를 직접 사용할 수 없음
* `key_id`로 빠르게 행을 찾은 뒤 Secret을 검증할 수 있음

### 단점과 위험

* 원문 Key를 잃으면 복구할 수 없고 재발급해야 함
* SHA-256 사용은 Secret이 충분히 긴 무작위 값이라는 전제에 의존함
* Key 발급 화면과 로그에서 원문 노출을 방지해야 함

### 재검토 조건

* 관리 UI 구현
* Key rotation 구현
* Key별 권한 범위 도입
* 감사 로그 도입

---

## Tenant와 Project를 요청 Header가 아닌 API Key 소유권에서 결정

### 해결하려는 문제

클라이언트가 Tenant ID 또는 Project ID Header를 임의로 조작하면 다른 Tenant 데이터로 저장될 수 있다.

### 선택한 방식

* 클라이언트의 Tenant/Project UUID Header를 신뢰하지 않음
* API Key 인증 결과에서 Tenant와 Project를 결정
* Controller에는 인증된 Project 정보를 request attribute로 전달

### 선택 이유

* 멀티테넌트 데이터 격리의 신뢰 경계를 서버 DB에 둠
* 클라이언트가 Tenant/Project ID를 알더라도 다른 영역에 데이터를 저장할 수 없음
* SaaS와 온프레미스 모두 동일한 인증 구조 사용 가능

### 단점과 위험

* 모든 telemetry 요청에 API Key DB 조회 비용이 발생함
* DB 장애가 인증 전체 장애로 이어짐
* 이후 cache 도입 시 폐기 Key의 반영 지연을 고려해야 함

### 재검토 조건

* API Key 인증 조회가 병목으로 측정됨
* 로컬 cache 또는 분산 cache 도입 검토
* Key 폐기 전파 요구시간 정의

---

## 일시적인 DB 장애에 HTTP 503 반환

### 해결하려는 문제

DB 장애가 HTTP 500 또는 401로 반환되면 Collector가 영구 오류로 오해하거나 올바른 재시도 정책을 적용하지 못할 수 있다.

### 선택한 방식

* DB 연결 실패와 일시적 자원 오류를 retryable 장애로 분류
* 인증 DB 장애와 Span 저장 DB 장애에 HTTP 503 반환
* SQL 문법 오류와 프로그래밍 오류는 503으로 숨기지 않음

### 선택 이유

* Collector retry와 persistent queue를 활용할 수 있음
* 잘못된 API Key의 401과 서버 장애의 503을 구분 가능
* 장애 원인을 클라이언트 자격증명 문제로 오인하지 않음

### 단점과 위험

* 잘못된 예외 분류는 영구 오류를 무한 재시도하게 만들 수 있음
* `max_elapsed_time: 0`과 결합하면 queue가 계속 증가할 수 있음
* queue 사용률과 디스크 용량 감시가 필요함

### 재검토 조건

* retry storm 발생
* queue 증가 속도가 복구 속도를 초과
* DB 장애 유형별 응답 정책 변경 필요
* circuit breaker 도입 검토

---

## Collector Persistent Queue 사용

### 해결하려는 문제

AeroTrace Backend 또는 TimescaleDB 장애 중 Collector가 받은 telemetry가 메모리에만 있으면 Collector 재시작 시 데이터가 유실될 수 있다.

### 검토한 대안

* memory queue만 사용
* file storage 기반 persistent queue
* Kafka 도입

### 선택한 방식

* OpenTelemetry Collector Contrib 사용
* `file_storage` extension 사용
* Docker named volume에 queue 저장
* exporter `sending_queue.storage`에 연결
* retry 활성화
* Kafka는 도입하지 않음

### 선택 이유

* 현재 규모에서 Kafka 없이도 재시작 내구성을 제공
* Docker Compose와 홈서버 환경에서 단순하게 운영 가능
* 동일 구성을 SaaS MVP와 온프레미스 배포에 사용할 수 있음
* 100 Span 실험에서 Collector 재시작 후 자동 복구를 확인함

### 단점과 위험

* 호스트 디스크 자체가 손상되면 queue도 손실될 수 있음
* queue가 가득 차면 신규 데이터 유실 가능
* 파일 저장소 compaction과 디스크 사용량을 관찰해야 함
* 다중 Collector에서는 각 인스턴스별 queue가 분리됨

### 재검토 조건

* 단일 Collector가 처리량 병목이 됨
* 여러 Collector 간 안정적인 버퍼 공유 필요
* 장시간 장애에서 로컬 디스크 용량이 부족함
* Kafka 도입을 정당화할 실제 요구가 발생함

---

## Persistent Queue 크기를 50,000 Span으로 시작

### 해결하려는 문제

queue를 무제한으로 두면 홈서버 디스크를 소진할 수 있고, 너무 작게 두면 짧은 장애에도 데이터가 유실될 수 있다.

### 선택한 방식

```yaml
sending_queue:
  sizer: items
  queue_size: 50000
```

### 선택 이유

* 현재 MVP 장애 실험을 수행하기에 충분한 유한 크기
* 100 Span과 10,000 Span queue 수용을 확인함
* 10,000 Span은 현재 queue의 20%에 해당
* 측정 없이 지나치게 큰 값을 설정하지 않음

### 단점과 위험

* 실제 운영에서 몇 분을 버티는지는 Span 유입률에 따라 달라짐
* 평균 Span 크기와 디스크 사용량을 아직 측정하지 못함
* `block_on_overflow: false`이므로 queue가 가득 차면 데이터가 거부될 수 있음

### 재검토 조건

* 실제 서비스의 spans/s 측정
* 평균 queue 저장 바이트/Span 측정
* 허용할 DB 장애 지속시간 정의
* queue 사용률 경보 설계
* overflow 실험 완료

---

## 최근 2일은 Rowstore, 이후 데이터는 Columnstore로 유지

### 해결하려는 문제

APM telemetry는 최근 데이터에 쓰기와 장애 분석 요청이 집중되지만, 시간이 지난 데이터는 주로 장기 분석과 통계 조회에 사용된다.

모든 데이터를 rowstore로 유지하면 장기 저장 비용이 증가하고, 모든 데이터를 즉시 columnstore로 전환하면 최근 데이터 쓰기와 수정에 불필요한 제약이 생길 수 있다.

### 검토한 대안

1. 모든 데이터를 rowstore로 유지
2. 수집 직후 바로 columnstore로 전환
3. 일정 기간 rowstore에 유지한 뒤 자동 columnstore 전환

### 선택한 방식

* Chunk interval: 1일
* 최근 rowstore 기간: 약 2일
* 2일보다 오래된 완성 chunk: 자동 columnstore 전환
* Segment 기준: `tenant_id, project_id`
* 정렬 기준: `start_time DESC`

### 선택 이유

* 최근 장애 분석 데이터는 rowstore에 유지
* 쓰기 진행 중인 현재 chunk를 전환 대상에서 제외
* 과거 telemetry의 저장 비용 절감 가능
* Tenant와 Project 범위 조회에 맞는 segment 구성
* 실제 정책 실행을 통해 rowstore에서 columnstore로 전환되는 동작 확인
* 전환 후에도 기존 hypertable 조회가 유지됨을 확인

### 단점과 위험

* 2일이라는 기준은 아직 운영 부하를 바탕으로 산정된 값이 아님
* 작은 chunk에서는 columnstore 전환 후 저장 크기가 줄지 않을 수 있음
* 잘못된 `start_time`을 가진 telemetry가 예상과 다른 chunk에 저장될 수 있음
* 정책 실패를 감지할 Prometheus 경보가 아직 없음

### 재검토 조건

* 실제 사용자 서비스의 최근 Trace 조회 범위 측정
* 운영 환경의 Span 유입량 측정
* 대량 데이터 압축률 측정
* 최근 데이터 조회가 columnstore 경계를 자주 넘는 경우
* Columnstore 정책 실행 시간이 운영 부하에 영향을 주는 경우
