# AeroTrace 설계 결정 기록

> 마지막 업데이트: 2026-08-04  
> 현재 결정 수: 20  
> 상태: 채택 / 보류 / 재검토 필요

이 문서는 AeroTrace의 주요 설계 결정, 검토한 대안, 선택 이유, 위험, 재검토 조건을 기록한다.

---

## D-001. Java 21과 Spring Boot 4 사용

### 상태

채택

### 해결하려는 문제

장기적으로 유지 가능한 Java 백엔드를 구성하고, 높은 동시성의 OTLP I/O 요청을 비교적 적은 플랫폼 Thread로 처리할 기반이 필요했다.

### 검토한 대안

1. Java 17
2. Java 21
3. Java 25 이상
4. Spring Boot 3
5. Spring Boot 4

### 선택

- Java 21
- Spring Boot 4.1.0
- Virtual Threads 활성화

### 선택 이유

- Java 21은 LTS다.
- Virtual Threads를 안정적으로 사용할 수 있다.
- Spring Boot 4의 최소 Java 요구사항을 만족한다.
- OTLP 수신처럼 I/O 중심 요청에서 Virtual Threads를 직접 검증할 수 있다.
- 초기 프로젝트에서 더 높은 Java 버전으로 생태계 호환성 위험을 늘릴 필요가 없다.

### 단점과 위험

- Spring Boot 4와 Jackson 3 변경점을 학습해야 한다.
- 서드파티 라이브러리 호환성을 확인해야 한다.
- Virtual Threads는 DB Connection 수를 늘리지 않으므로 HikariCP 병목은 별도로 관리해야 한다.

### 재검토 조건

- Java LTS 마이그레이션 시점
- 라이브러리 지원 정책 변화
- Virtual Threads 관련 운영 문제가 측정될 때

---

## D-002. 모듈형 모놀리스로 시작

### 상태

채택

### 해결하려는 문제

초기 MVP에서 배포와 운영 복잡도를 낮추면서 수집, Tenant 관리, 조회 기능의 책임을 구분해야 했다.

### 검토한 대안

1. 단일 패키지 구조
2. 모듈형 모놀리스
3. 초기 마이크로서비스
4. 메시지 브로커 기반 분산 파이프라인

### 선택

하나의 Spring Boot 애플리케이션 안에서 책임별 패키지를 분리한다.

```text
ingest
tenant
query
common
```

### 선택 이유

- 현재 규모에서 서비스 분리는 운영 부담이 더 크다.
- Transaction과 로컬 호출을 단순하게 유지할 수 있다.
- 실제 병목이 생기면 모듈 경계를 기준으로 분리할 수 있다.
- N100 홈서버와 Oracle Cloud 무료 환경에 적합하다.

### 단점과 위험

- 기능이 증가하면 모듈 간 의존성 관리가 필요하다.
- 한 프로세스 장애가 전체 기능에 영향을 준다.
- 수집과 조회 부하가 같은 프로세스와 DB에서 경쟁한다.

### 재검토 조건

- 수집과 조회가 서로 다른 확장 요구를 가질 때
- 배포 독립성이 실제로 필요할 때
- 단일 프로세스 자원 경합이 측정될 때

---

## D-003. TimescaleDB를 Trace 원본 저장소로 사용

### 상태

채택

### 해결하려는 문제

시간 범위 기반 대량 Span을 저장하고 조회하면서 PostgreSQL 생태계와 단순한 운영 구성을 유지해야 했다.

### 검토한 대안

1. 일반 PostgreSQL
2. TimescaleDB
3. Elasticsearch
4. ClickHouse
5. 별도 시계열 DB

### 선택

PostgreSQL 15 기반 TimescaleDB Hypertable을 사용한다.

- 시간 컬럼: `start_time`
- Chunk Interval: 1일

### 선택 이유

- PostgreSQL SQL, JSONB, 외래키, Transaction을 사용할 수 있다.
- Chunk, Columnstore, Retention을 사용할 수 있다.
- 별도 검색 Cluster를 운영하지 않아도 된다.
- Tenant와 Project Control Plane을 같은 DB에서 시작할 수 있다.
- 제한된 홈서버 환경에 적합하다.

### 단점과 위험

- Trace 전문 검색 엔진보다 자유로운 검색 성능이 낮을 수 있다.
- JSONB 검색 요구가 커지면 Index 비용이 증가한다.
- 수집량이 커지면 CPU, Disk, WAL, Connection이 병목이 될 수 있다.

### 재검토 조건

- 단일 노드 처리량 한계 도달
- Attribute 검색 요구 급증
- 30일 저장 비용이 운영 한계를 초과
- 다른 저장소의 이점이 측정으로 확인될 때

---

## D-004. Tenant와 Project를 Span의 복합 외래키로 검증

### 상태

채택

### 해결하려는 문제

존재하는 Tenant ID와 Project ID가 서로 다른 소유 관계로 잘못 조합될 수 있다.

### 검토한 대안

1. 애플리케이션에서만 검증
2. `project_id`만 외래키
3. Tenant와 Project 각각 별도 외래키
4. `(tenant_id, project_id)` 복합 외래키

### 선택

```sql
FOREIGN KEY (tenant_id, project_id)
REFERENCES projects (tenant_id, id)
```

### 선택 이유

- DB가 Project 소유 관계를 최종 보장한다.
- 애플리케이션 버그가 있어도 잘못된 멀티테넌트 조합을 차단한다.
- 저장과 조회에서 Tenant 범위를 명확히 유지한다.

### 단점과 위험

- 외래키와 Index 유지 비용이 발생한다.
- 대량 수집에서 FK 검증 비용을 측정해야 한다.

### 재검토 조건

- FK가 실제 수집 병목으로 측정될 때
- Tenant별 물리 DB 분리 구조로 변경할 때

---

## D-005. Collector 재전송 중복을 Unique Index로 방지

### 상태

채택

### 해결하려는 문제

Collector Retry로 같은 Span이 재전송되면 Trace 집계와 저장량이 왜곡된다.

### 검토한 대안

1. 중복 허용
2. INSERT 전 선조회
3. Redis Deduplication
4. DB Unique Index와 `ON CONFLICT DO NOTHING`

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

저장:

```sql
ON CONFLICT DO NOTHING
```

### 선택 이유

- 선조회와 INSERT 사이 경쟁 조건을 피한다.
- 별도 저장소가 필요 없다.
- DB가 원자적으로 중복을 차단한다.
- Collector 재전송을 Idempotent 성공으로 처리할 수 있다.
- TimescaleDB Unique Index 규칙에 따라 시간 파티션 컬럼을 포함한다.

### 단점과 위험

- Unique Index 쓰기 비용과 저장 공간이 발생한다.
- `start_time`까지 동일해야 중복으로 판단된다.
- Timestamp가 다르면 같은 Span ID가 별도 행으로 저장될 수 있다.

### 재검토 조건

- 다른 중복 형태가 실험에서 발견될 때
- Index 비용이 병목으로 측정될 때
- 식별자 저장 방식을 변경할 때

---

## D-006. 자주 조회하는 값은 컬럼, 나머지는 JSONB로 저장

### 상태

채택

### 해결하려는 문제

OpenTelemetry Attribute는 동적이지만 모든 조회를 JSONB에 의존하면 반복 파싱과 Index 비용이 커질 수 있다.

### 검토한 대안

1. 모든 값을 일반 컬럼
2. 모든 값을 JSONB
3. 핵심 필드는 컬럼, 동적 데이터는 JSONB
4. EAV Attribute 테이블

### 선택

일반 컬럼:

- `service_name`
- `trace_id`
- `span_id`
- `parent_span_id`
- `name`
- `span_kind`
- `status_code`
- `start_time`
- `end_time`
- `duration_nano`
- Tenant / Project 식별자

JSONB:

- Resource Attributes
- Span Attributes
- Events
- Links

### 선택 이유

- 주요 목록과 상세 필드를 빠르게 사용할 수 있다.
- 동적 Attribute 구조를 보존할 수 있다.
- 신규 Attribute에 Migration이 필요 없다.
- EAV보다 저장과 조회 구현이 단순하다.

### 단점과 위험

- 일부 값이 컬럼과 JSONB에 중복될 수 있다.
- 임의 Attribute 검색은 Index 없이 느릴 수 있다.
- JSONB 크기가 커지면 저장량과 WAL이 증가한다.

### 재검토 조건

- Attribute 검색 API 구현
- JSONB 실행계획과 크기 측정
- GIN Index 비교
- 평균 Span 크기 측정

---

## D-007. OTLP 요청을 엄격하게 검증

### 상태

채택

### 해결하려는 문제

잘못된 ID, 시간, AnyValue, Event, Link가 저장되면 Trace 손상과 조회 오류가 발생할 수 있다.

### 선택

Parser에서 다음을 검증한다.

- Trace ID / Span ID
- all-zero ID
- `service.name`
- 시작 / 종료 시각
- Span Kind / Status Code
- `uint32`
- AnyValue
- 중복 Attribute Key
- Event / Link 구조
- Link 식별자

### 선택 이유

- 영구적으로 잘못된 요청을 DB 전에 거부한다.
- 오류 위치를 구체적으로 표현할 수 있다.
- 요청 일부만 저장되는 상황을 피한다.
- DB 제약조건을 최종 방어선으로 유지한다.

### 단점과 위험

- OTLP 표준보다 엄격한 AeroTrace 정책이 포함될 수 있다.
- `service.name` 필수 정책은 호환성에 영향을 준다.
- 한 Span 오류로 요청 전체가 거부된다.

### 재검토 조건

- Partial Success 도입
- 실제 사용자 호환성 문제
- Collector에서 보완 가능한 필드가 확인될 때

---

## D-008. Telemetry 저장 경로는 Spring JDBC를 사용

### 상태

채택

### 해결하려는 문제

대량 Span 저장에서 ORM Entity 관리와 개별 INSERT 비용을 피하고 SQL과 Batch를 직접 제어해야 했다.

### 검토한 대안

1. Spring Data JPA
2. Spring JDBC
3. R2DBC
4. PostgreSQL COPY
5. 외부 메시지 브로커

### 선택

- Telemetry 저장과 현재 Query Repository: Spring JDBC
- Control Plane: 필요할 때 JPA 검토

### 선택 이유

- SQL과 Batch를 직접 제어한다.
- `ON CONFLICT DO NOTHING`을 명시적으로 사용한다.
- JSONB와 TimescaleDB SQL을 다루기 쉽다.
- 현재 규모에서 R2DBC와 메시지 브로커가 필요하지 않다.

### 단점과 위험

- SQL과 Parameter 순서를 직접 관리한다.
- Schema 변경 시 SQL과 Binding을 함께 수정해야 한다.
- 대형 Batch의 메모리와 JDBC 한계를 직접 관리해야 한다.

### 재검토 조건

- JDBC가 병목으로 측정될 때
- COPY 비교가 필요할 때
- 수집과 DB 장애를 더 강하게 분리해야 할 때

---

## D-009. Span 저장에 JDBC Batch 사용

### 상태

채택

### 해결하려는 문제

Span별 `JdbcTemplate.update()`는 JDBC 호출과 Statement 실행 비용을 반복한다.

### 검토한 대안

1. Span별 단건 INSERT
2. 요청 전체 Batch
3. 고정 크기 Chunk Batch
4. PostgreSQL COPY
5. 비동기 Message Queue

### 측정 조건

- 단건과 Batch 모두 요청당 Transaction 1개
- JSON 직렬화, Transaction, JDBC 저장 포함
- 저장 검증과 데이터 삭제 제외
- Warm-up 후 반복
- 중앙값 비교

### 측정 결과

| Span 수 | 단건 중앙값 | Batch 중앙값 | 개선 |
|---:|---:|---:|---:|
| 100 | 91.084ms | 30.460ms | 약 2.99배 |
| 1,000 | 874.985ms | 299.167ms | 약 2.92배 |
| 5,000 | 4,566.945ms | 1,485.885ms | 약 3.07배 |

### 선택

JDBC Batch를 유지한다.

### 선택 이유

- 모든 구간에서 약 2.9~3.1배 높은 처리량을 확인했다.
- 단건 저장은 약 1,095~1,143 spans/s였다.
- Batch 저장은 약 3,283~3,365 spans/s였다.

### 단점과 위험

- 요청 Span을 메모리에 준비해야 한다.
- 큰 요청은 메모리와 Transaction에 부담이 된다.
- HTTP와 Collector를 포함하지 않은 Persistence-only 결과다.

### 재검토 조건

- 운영 장비 측정
- End-to-End 부하 테스트
- 메모리 사용량 측정
- COPY 비교

---

## D-010. `reWriteBatchedInserts` 활성화 보류

### 상태

보류

### 해결하려는 문제

pgJDBC가 Batch INSERT를 Multi-values INSERT로 재작성할 때 추가 성능 개선이 있는지 확인해야 했다.

### 검토한 대안

1. 기본값 OFF
2. `reWriteBatchedInserts=true`

### 측정 결과

| Span 수 | OFF Batch | ON Batch | 변화 |
|---:|---:|---:|---:|
| 100 | 30.460ms | 32.836ms | 약 7.8% 악화 |
| 1,000 | 299.167ms | 288.642ms | 약 3.5% 개선 |
| 5,000 | 1,485.885ms | 1,593.576ms | 약 7.2% 악화 |

### 결정

현재 설정에는 적용하지 않는다.

### 결정 이유

- Span 수에 따라 결과 방향이 달랐다.
- 일관된 개선 효과가 없다.
- 운영 복잡도를 늘릴 근거가 부족하다.

### 재검토 조건

- N100 재측정
- 원격 DB
- pgJDBC 버전 변경
- INSERT SQL 또는 Batch 크기 변경

---

## D-011. JDBC Batch 크기를 1,000으로 설정

### 상태

채택

### 해결하려는 문제

Batch가 너무 작으면 JDBC 실행 비용이 증가하고, 너무 크면 메모리, Transaction, 장애 재처리 범위가 커진다.

### 측정 결과

5,000 Span 처리:

| Batch 크기 | 총 처리시간 | 처리량 |
|---:|---:|---:|
| 50 | 2,207.706ms | 2,265 spans/s |
| 100 | 1,679.864ms | 2,976 spans/s |
| 250 | 1,950.707ms | 2,563 spans/s |
| 500 | 1,634.554ms | 3,059 spans/s |
| 1,000 | 1,712.393ms | 2,920 spans/s |
| 2,000 | 1,711.864ms | 2,921 spans/s |
| 5,000 | 1,698.537ms | 2,944 spans/s |

### 선택

```yaml
aerotrace:
  ingest:
    jdbc:
      batch-size: 1000
```

### 선택 이유

- 500이 가장 빠른 단일 결과였지만 500~5,000 차이가 크지 않았다.
- 1,000은 충분한 처리량을 유지한다.
- 한 번의 JDBC 실행 크기와 메모리를 제한한다.
- 절대 최적값이 아니라 초기 운영값이다.

### 재검토 조건

- 운영 장비 측정
- 평균 Span 크기
- DB CPU / Connection 병목
- Batch 지연 증가

---

## D-012. Project API Key 원문을 저장하지 않음

### 상태

채택

### 해결하려는 문제

DB 유출 시 원문 API Key가 노출되면 Telemetry 위조와 Quota 소모에 즉시 악용될 수 있다.

### 검토한 대안

1. 원문 저장
2. 암호화 저장
3. Secret Hash만 저장

### 선택

- 공개 식별자 `key_id`
- Secret SHA-256 Hash
- 입력 Secret Hash 후 상수시간 비교
- 원문은 발급 시에만 반환

### 선택 이유

- DB만 유출됐을 때 원문 Key를 직접 사용할 수 없다.
- `key_id`로 빠르게 행을 조회할 수 있다.
- 긴 무작위 Secret이라는 전제에서 단순하고 현실적이다.

### 단점과 위험

- 원문 Key를 잃으면 복구할 수 없다.
- 재발급과 Rotation 기능이 필요하다.
- 발급 응답, 로그, 화면에서 원문 노출을 방지해야 한다.

### 재검토 조건

- 관리 UI
- Rotation
- Key Scope
- Audit Log

---

## D-013. Tenant와 Project는 API Key 소유권에서 결정

### 상태

채택

### 해결하려는 문제

클라이언트가 Tenant / Project Header를 조작해 다른 데이터 영역에 저장하거나 조회할 위험이 있다.

### 선택

- 클라이언트 Tenant / Project UUID Header를 신뢰하지 않는다.
- API Key DB 소유권에서 Tenant와 Project를 결정한다.
- 인증 결과를 저장과 조회 Repository에 전달한다.

### 선택 이유

- 멀티테넌트 신뢰 경계를 서버 DB에 둔다.
- UUID를 알아도 다른 Project에 저장하거나 조회할 수 없다.
- SaaS와 On-premise가 같은 구조를 사용할 수 있다.

### 단점과 위험

- 매 요청 API Key DB 조회 비용이 발생한다.
- DB 장애가 인증 장애로 이어진다.
- Cache 도입 시 폐기 Key 반영 지연을 관리해야 한다.

### 재검토 조건

- API Key 조회가 병목으로 측정될 때
- Cache 도입
- Key 폐기 전파 SLA 정의

---

## D-014. 일시적인 DB 장애에 HTTP 503 반환

### 상태

채택

### 해결하려는 문제

DB 장애를 401 또는 일반 500으로 반환하면 Collector가 자격증명 오류 또는 영구 실패로 오해할 수 있다.

### 선택

- DB 연결 실패와 일시적 자원 오류를 Retryable로 분류
- 인증 DB 장애와 Span 저장 DB 장애에 503
- SQL 문법 오류와 프로그래밍 오류는 503으로 숨기지 않음
- Hikari Connection Timeout 3초

### 선택 이유

- Collector Retry와 Persistent Queue를 활용할 수 있다.
- 잘못된 API Key의 401과 서버 장애의 503을 구분한다.
- 자격증명 문제로 오인하지 않는다.

### 단점과 위험

- 예외 오분류는 영구 오류의 무한 Retry를 만들 수 있다.
- 무제한 Retry와 결합하면 Queue가 계속 증가한다.
- Queue와 Disk 감시가 필요하다.

### 재검토 조건

- Retry Storm
- Queue 증가 속도가 복구 속도 초과
- Circuit Breaker 필요
- 장애 유형별 응답 정책 변경

---

## D-015. Collector File Storage Persistent Queue 사용

### 상태

채택

### 해결하려는 문제

Backend나 DB 장애 중 Telemetry가 Memory Queue에만 있으면 Collector 재시작 시 유실될 수 있다.

### 검토한 대안

1. Memory Queue
2. File Storage Persistent Queue
3. Kafka

### 선택

- OpenTelemetry Collector Contrib
- `file_storage`
- Docker Named Volume
- Exporter Persistent Sending Queue
- Retry
- Kafka 미도입

### 선택 이유

- 현재 규모에서 Kafka 없이 재시작 내구성을 제공한다.
- Docker Compose와 홈서버에서 단순하다.
- SaaS MVP와 On-premise에 같은 구성을 적용할 수 있다.
- 100 Span 실험에서 Collector 재시작 후 복구를 확인했다.

### 단점과 위험

- Host Disk 손상 시 Queue도 손실될 수 있다.
- Queue가 가득 차면 신규 데이터가 거부될 수 있다.
- 다중 Collector Queue는 Instance별로 분리된다.
- File Storage와 Disk를 관찰해야 한다.

### 재검토 조건

- 단일 Collector 병목
- 여러 Collector의 공유 Buffer 필요
- 장시간 장애에서 Disk 부족
- Kafka를 정당화할 실제 요구

---

## D-016. Persistent Queue 크기를 50,000 Span으로 시작

### 상태

채택

### 해결하려는 문제

무제한 Queue는 Disk를 소진하고, 너무 작은 Queue는 짧은 장애에도 데이터를 잃는다.

### 선택

```yaml
sending_queue:
  sizer: items
  queue_size: 50000
  block_on_overflow: false
```

### 선택 이유

- MVP 장애 실험에 충분한 유한 크기다.
- 100 Span과 10,000 Span 수용을 확인했다.
- 10,000 Span은 용량의 20%다.
- 측정 없이 과도하게 큰 값을 설정하지 않는다.

### 단점과 위험

- 실제 몇 분을 버티는지는 유입률에 따라 다르다.
- 평균 Span 크기와 Disk 사용량이 미측정이다.
- Overflow 시 신규 데이터가 거부될 수 있다.
- 10,000 Span 최종 DB 정합성 출력은 문서에 남아 있지 않다.

### 재검토 조건

- 실제 spans/s
- Span당 Queue Bytes
- 허용 DB 장애 시간
- Queue 사용률 경보
- Overflow 실험

---

## D-017. 0~2일 Rowstore, 2~30일 Columnstore, 30일 초과 Retention

### 상태

채택

### 해결하려는 문제

최근 Telemetry의 쓰기와 장애 분석 성능을 유지하면서 제한된 저장 공간의 무기한 증가를 막아야 한다.

### 검토한 대안

1. 모든 데이터 Rowstore
2. 수집 직후 Columnstore
3. 일정 기간 후 Columnstore
4. Retention 없음
5. 7일 보존
6. 30일 보존
7. Tenant별 보존

### 선택

- Chunk Interval 1일
- 0~2일 Rowstore
- 2~30일 Columnstore
- 30일 초과 Retention
- Segment: `tenant_id, project_id`
- Order: `start_time DESC`

### 선택 이유

- 최근 쓰기와 조회는 Rowstore에 둔다.
- 과거 데이터는 Columnstore로 전환한다.
- 무기한 저장을 막는다.
- 행 DELETE가 아니라 Chunk 제거를 사용한다.
- 실제 정책 실행과 데이터 보존 / 삭제를 검증했다.

### 단점과 위험

- 2일과 30일은 실제 운영 트래픽으로 산정한 값이 아니다.
- 작은 Chunk에서는 압축률을 평가할 수 없다.
- 잘못된 `start_time`은 예상과 다른 생명주기를 만든다.
- 모든 Tenant에 동일한 보존기간이 적용된다.
- 정책 실패 경보가 없다.

### Tenant별 Retention을 지금 구현하지 않는 이유

공유 Hypertable의 같은 시간 Chunk에 여러 Tenant 데이터가 포함될 수 있다. TimescaleDB Retention은 Chunk 전체를 제거하므로 Tenant별 기간을 직접 적용할 수 없다.

### 재검토 조건

- 요금제별 보존기간 요구
- 30일 데이터가 Disk 한도 초과
- 실제 조회가 7일 이하에 집중
- 장기 보관 요구
- 압축률과 일일 저장량 측정 완료

---

## D-018. Trace 목록에 Keyset Cursor와 Trace 전체 집계 필터 사용

### 상태

채택

### 해결하려는 문제

실시간으로 데이터가 추가되는 Trace 목록에서 안정적인 페이지 이동, 멀티테넌트 격리, 필터와 전체 Trace 집계의 일관성을 함께 보장해야 했다.

### 검토한 대안

1. Offset Pagination
2. Filter를 Span `WHERE`에 적용
3. Keyset Pagination과 `HAVING` 집계 필터

### 선택

정렬:

```sql
ORDER BY trace_start_time DESC,
         trace_id DESC
```

다음 페이지:

```sql
trace_start_time < cursor_time
OR (
    trace_start_time = cursor_time
    AND trace_id < cursor_trace_id
)
```

필터 의미:

- `serviceName`: 해당 Service를 포함한 Trace
- `errorOnly`: Error Span을 포함한 Trace
- `minSpanDurationNano`: 기준 이상 Span을 포함한 Trace

집계값은 전체 Trace Span을 기준으로 유지한다.

Cursor에는 마지막 Trace 시작 시각, Trace ID, 조회 조건 SHA-256 Fingerprint를 포함한다.

Fingerprint:

- Tenant ID
- Project ID
- From / To
- Service
- Error
- Minimum Duration

### 선택 이유

- Offset보다 실시간 추가 데이터의 중복과 누락 위험이 작다.
- 동일 시작 시각은 Trace ID로 결정적으로 정렬한다.
- 필터를 적용해도 목록과 상세의 집계 의미가 일치한다.
- 다른 Project와 조건에서 Cursor 재사용을 차단한다.
- `limit + 1`로 별도 Count 없이 다음 페이지를 판단한다.

### 단점과 위험

- Cursor Fingerprint에 HMAC이 없다.
- Base64는 암호화가 아니다.
- Cursor는 권한 경계가 아니다.
- Filter는 전체 Trace 집계가 필요해 데이터 증가 시 비용이 커진다.
- Cursor가 Raw Span 작업량을 줄이지 않는다.

### 재검토 조건

- 목록 지연시간이 운영 목표를 지속 초과
- Aggregate / Chunk Scan이 주요 병목
- Cursor 위조 방지가 필요
- 임의 페이지 이동 요구
- Trace Summary가 더 경제적인 규모

---

## D-019. Trace 목록은 현재 Raw Span 전체 집계 SQL 유지

### 상태

채택

### 해결하려는 문제

후보 Trace를 먼저 찾으면 선택도가 낮을 때 빠를 수 있지만, 후보가 많으면 원본 데이터를 두 번 처리해 더 느려질 수 있다.

### 검토한 대안

1. Raw Span 전체 집계
2. 후보 Trace ID 선조회 후 재집계
3. Duration 값에 따른 SQL 분기
4. Trace Summary 테이블

### 측정 조건

- Trace 20,000
- Span 109,998
- Warm Cache
- Local Docker
- TimescaleDB 2.28.3
- PostgreSQL 15.18

### 측정 결과

후보 우선:

- 1%, 200 Trace: 19.819ms
- 5%, 1,000 Trace: 34.795ms
- 100%, 20,000 Trace: 345.467ms

100% 후보와 동일 조건의 기존 SQL:

- 98.048ms

### 선택

- 현재 Raw Span 전체 집계 SQL 유지
- 후보 우선 SQL 자동 분기 보류
- Duration Index 보류
- Trace Summary 조기 도입 보류

### 선택 이유

- 현재 SQL은 모든 Filter에서 정확하다.
- 현재 규모의 반복 실행시간은 대체로 90~110ms다.
- 후보 우선 방식은 데이터 분포에 따라 성능 편차가 크다.
- Duration 값만으로 실제 선택도를 판단할 수 없다.
- 신규 Index는 수집 비용과 저장 공간을 증가시킨다.

### 단점과 위험

- 데이터가 증가하면 Raw Span 수에 비례해 비용이 증가한다.
- Limit과 Cursor가 집계량을 줄이지 못한다.
- Planner의 집계 후 Filter 선택도 예측이 부정확할 수 있다.

### 재검토 조건

- 실제 사용자 데이터에서 지연 목표 초과
- 30일 범위 CPU / Connection 문제
- 조회가 수집 성능에 영향
- 홈서버 / Oracle Cloud 한계 초과

---

## D-020. Next.js 서버 전용 BFF로 Trace API 호출

### 상태

채택 — 로컬 MVP와 제한된 PoC 범위

### 해결하려는 문제

브라우저가 Spring Boot를 직접 호출하면 Project API Key가 Network 요청이나 공개 환경변수에 노출될 수 있다. Frontend와 Backend가 다른 Origin이면 CORS와 인증 전달도 별도로 관리해야 한다.

### 검토한 대안

1. Browser에서 Spring Boot 직접 호출
2. `NEXT_PUBLIC_` 환경변수로 API Key 전달
3. Next.js Route Handler BFF
4. 사용자 로그인과 세션을 즉시 구현

### 선택

- Browser는 Same-origin `/api/traces` 호출
- Next.js Route Handler가 Spring Boot 호출
- Backend URL과 Project API Key는 Server-only 환경변수
- 목록과 상세 응답은 `Cache-Control: no-store`
- 전달 Query Parameter를 Allowlist로 제한

### 선택 이유

- API Key가 Browser에 노출되지 않는다.
- 직접 CORS 구성이 필요 없다.
- Backend Status와 Error Message를 중계할 수 있다.
- 로컬 MVP에서 Trace Explorer를 단순하게 검증할 수 있다.

### 단점과 위험

- 사용자 로그인과 세션이 없다.
- Frontend 접근자는 설정된 Project 데이터를 조회할 수 있다.
- 서버당 하나의 Project API Key를 사용한다.
- 공개 SaaS 인증 구조가 아니다.
- Next.js 서버가 추가 Network Hop과 운영 구성 요소가 된다.

### 재검토 조건

- 인터넷 공개
- 다중 사용자
- 다중 Project 선택
- 조직 / Role 관리
- API Key 관리 UI
- 사용자 세션과 권한 검사 도입

---

## ADR: 최초 운영 배포 환경 역할 분리

### 상태

채택

### 해결하려는 문제

AeroTrace를 외부에서 접근 가능한 SaaS 형태로 검증하는 동시에, 제한된 비용 안에서 장기간 데이터 저장과 온프레미스 배포 가능성을 검증해야 한다.

현재 사용할 수 있는 환경은 다음 두 가지다.

* Oracle Cloud Ampere A1
* Intel N100 기반 Ubuntu 홈서버

두 환경 중 하나만 선택하면 다음 문제가 발생한다.

* Oracle Cloud 무료 인스턴스만 사용하면 무료 용량 제한, 리전 용량 부족, 유휴 인스턴스 회수 가능성에 영향을 받는다.
* 홈서버만 사용하면 주거용 네트워크, 정전, 공유기 설정, 동적 IP, 외부 공개에 따른 보안 위험을 함께 해결해야 한다.

### 검토한 대안

#### 대안 1: Oracle Cloud만 사용

장점:

* 공인 네트워크 환경에서 외부 접근 검증이 쉽다.
* 홈 네트워크를 직접 공개하지 않아도 된다.
* ARM64 환경에서 멀티플랫폼 배포를 검증할 수 있다.

단점:

* 무료 인스턴스 생성 용량을 확보하지 못할 수 있다.
* 유휴 Always Free 인스턴스가 회수될 가능성이 있다.
* 무료 Block Volume 용량 안에서 운영해야 한다.
* 무료 인스턴스를 실제 사용자 데이터의 유일한 저장 위치로 사용하기 어렵다.

#### 대안 2: N100 홈서버만 사용

장점:

* 사용자가 장비와 데이터를 직접 통제할 수 있다.
* 16GB RAM과 512GB SSD를 사용할 수 있다.
* 온프레미스 배포 구조를 실제로 검증할 수 있다.
* 저장량 및 장기 운영 실험에 적합하다.

단점:

* 인터넷 공개 시 홈 네트워크 보안 위험이 발생한다.
* 정전, 공유기 장애, 인터넷 장애의 영향을 받는다.
* 공인 IP, 포트 포워딩, TLS, 도메인 연결 문제가 추가된다.
* SaaS 공개 환경과 온프레미스 환경의 검증 목적이 섞인다.

#### 대안 3: 두 환경의 역할 분리

Oracle Cloud는 외부 공개 검증에 사용하고, N100 홈서버는 장기 실행과 온프레미스 검증에 사용한다.

두 환경은 동일한 코드베이스와 Docker Compose 구조를 사용하지만 데이터베이스를 공유하거나 자동 복제하지 않는다.

### 선택한 방식

두 환경의 역할을 분리한다.

#### Oracle Cloud Ampere A1

* 최초 외부 공개 검증 환경
* SaaS Dashboard 접근 검증
* 외부 서비스의 OTLP 전송 검증
* 회사 PoC 및 포트폴리오 데모
* ARM64 이미지 실행 검증
* 별도의 배포용 DB와 API Key 사용

#### N100 Ubuntu 홈서버

* 장기 실행 기준 환경
* 온프레미스 설치 구조 검증
* 처리량과 저장량 측정
* Retention 및 Compression 실험
* Collector Persistent Queue 장애 실험
* 백업과 복원 실험
* amd64 이미지 실행 검증

### 선택 이유

AeroTrace는 SaaS와 온프레미스를 모두 지원하는 것이 목표다.

Oracle Cloud와 N100 홈서버에 같은 코드베이스를 배포하면 다음을 함께 검증할 수 있다.

* ARM64와 amd64 호환성
* 공개 SaaS와 온프레미스 배포 가능성
* 환경변수 기반 설정 분리
* Docker Compose 기반 재현성
* 환경별 독립적인 데이터와 Secret 관리

무료 클라우드 인스턴스의 회수나 용량 부족을 데이터 유실 문제로 연결하지 않기 위해 Oracle Cloud를 유일한 데이터 저장 환경으로 사용하지 않는다.

### 운영 원칙

* 로컬, Oracle Cloud, 홈서버에서 서로 다른 DB 비밀번호를 사용한다.
* 각 환경에서 서로 다른 AeroTrace API Key를 발급한다.
* `.env`, `otel-collector.env`, `frontend.env`를 서버마다 별도로 생성한다.
* Secret 파일을 Git으로 전송하지 않는다.
* OCI DB와 홈서버 DB 사이의 자동 복제를 MVP에 추가하지 않는다.
* 실제 사용자 데이터를 받기 전 백업 및 복원 테스트를 완료한다.
* 실제 사용자에게 가용성을 약속하기 전 무료 OCI 인스턴스의 회수 위험을 제거하거나 유료 운영 환경으로 전환한다.

### 단점과 위험

* 두 환경의 설정과 데이터를 별도로 관리해야 한다.
* 자동 배포가 없으면 환경별 버전이 달라질 수 있다.
* OCI 환경이 삭제되면 공개 데모가 중단될 수 있다.
* 홈서버 장애 시 장기 측정 데이터가 손실될 수 있다.
* 현재는 자동 백업과 외부 백업이 없다.

### 향후 재검토 조건

다음 조건 중 하나가 충족되면 배포 구조를 다시 검토한다.

* 실제 외부 사용자가 발생
* 회사 PoC 서비스가 연결됨
* 무료 OCI 인스턴스가 회수됨
* 무료 저장 공간이 부족해짐
* 홈서버를 외부에 공개해야 함
* 월간 가용성 목표가 필요해짐
* 자동 백업과 장애 복구 목표가 정의됨
* 단일 서버가 측정된 병목이 됨

---

### ADR — Edge Gateway에서 Docker upstream을 동적으로 해석

#### 해결하려는 문제

AeroTrace Frontend 컨테이너 장애 후 Docker가 컨테이너를 자동 복구했지만 IP가 변경되면서 Edge Gateway Nginx가 기존 IP를 계속 사용해 `504 Gateway Timeout`이 발생했다.

#### 검토한 방식

정적 `proxy_pass http://aerotrace-web:3000` 구성을 그대로 유지하고 Nginx를 재시작하거나 reload하는 방식과 Docker embedded DNS를 이용해 upstream을 동적으로 재해석하는 방식을 검토했다.

#### 선택

Docker embedded DNS `127.0.0.11`과 Nginx dynamic upstream resolution을 사용한다.

```nginx
resolver 127.0.0.11 valid=5s ipv6=off;

server aerotrace-web:3000 resolve;
```

#### 선택 이유

컨테이너 장애가 발생할 때마다 운영자가 Nginx를 수동 reload하는 방식은 자동 복구라는 운영 목표에 맞지 않는다.

Docker가 새로운 컨테이너 주소를 DNS에 반영하면 Nginx도 이를 자동으로 따라가도록 만들어 Gateway와 애플리케이션의 복구 수명주기를 분리한다.

#### 단점과 위험

Docker embedded DNS에 의존한다.

Resolver 동작과 Nginx 버전의 dynamic upstream 지원 여부가 운영 환경에 영향을 준다.

DNS 갱신 주기 동안 짧은 복구 지연이 발생할 수 있다.

#### 재검토 조건

향후 Kubernetes, Service Discovery 시스템, 외부 Load Balancer 등 Docker Compose가 아닌 배포 환경으로 변경할 경우 upstream discovery 방식을 다시 검토한다.

---

---

## ADR — pgJDBC `reWriteBatchedInserts`를 Telemetry 저장 경로에 활성화

### 상태

채택

### 결정일

2026-08-13

### 해결하려는 문제

N100 홈서버에서 3,250 spans/s sustained telemetry ingest를 처리할 때 60초 동안 195,000 Span 전량 저장과 failed request 0은 유지했지만 TimescaleDB CPU 사용률이 높은 수준까지 증가했다.

또한 일부 1,050-span Backend JDBC writer 실행시간이 Collector의 다음 size-trigger batch가 도착할 것으로 예상되는 약 323.08ms보다 길었다.

단순히 JDBC batch size를 변경하거나 더 복잡한 인프라를 도입하기 전에 실제 병목이 다음 중 어디에 있는지 확인해야 했다.

```text
OTLP parsing
JSON serialization
PreparedSpanRow 생성
PreparedStatement parameter binding
JdbcTemplate
pgJDBC
PostgreSQL INSERT
Unique Index / ON CONFLICT
TimescaleDB 저장
```

### 검토한 대안

#### 대안 1 — 현재 pgJDBC batch 실행 방식 유지

장점:

- 기존 inserted / duplicate update count 의미 유지
- 변경 위험 없음
- 현재 데이터 정합성 이미 검증됨

단점:

- 3,250 spans/s에서 DB CPU headroom이 매우 작음
- 일부 writer가 Collector batch cadence보다 느림
- 더 높은 실제 사용자 부하에 대한 여유가 작음

#### 대안 2 — JDBC batch size 변경

장점:

- 현재 구조를 그대로 유지 가능
- 구현 변경이 작음

단점:

- 기존 batch-size benchmark에서 500~5,000 사이 차이가 크지 않았음
- 현재 계측 결과는 JSON preparation이나 chunk 분할보다 `batchUpdate` 내부 bind 이후 구간이 병목임을 보여줌
- 원인을 확인하지 않은 batch-size 조정은 추측 기반 tuning이 됨

#### 대안 3 — JSON 직렬화 / PreparedStatement binding 최적화

장점:

- Java 코드 안에서 해결 가능
- DB 설정 변경 없음

단점:

측정 결과 주요 병목이 아니었다.

1,050-span baseline 요청:

```text
prepareRows median = 1.64ms
bind median        = 1.19ms
batchUpdate median = 286.84ms
```

parameter binding 비중:

```text
batchUpdate median 기준 약 0.40%
```

따라서 우선순위가 낮다.

#### 대안 4 — pgJDBC `reWriteBatchedInserts=true`

장점:

- 기존 Spring JDBC 구조 유지
- 별도 인프라 불필요
- 애플리케이션 API 구조 변경 없음
- 실제 N100 sustained workload에서 큰 성능 개선 가능성 확인

단점:

- rewritten batch의 개별 update count를 항상 정확하게 복원할 수 없음
- 신규와 duplicate가 섞인 batch가 `SUCCESS_NO_INFO`로 분류될 수 있음
- `insertedCount` / `duplicateCount` 정확성이 낮아질 수 있음

#### 대안 5 — PostgreSQL COPY

장점:

- 대량 삽입에서 높은 처리량 가능성

단점:

- 현재 `ON CONFLICT DO NOTHING` 기반 idempotency 구조와 직접 동일하지 않음
- 중복 처리 전략을 다시 설계해야 함
- 현재 문제를 해결하기 위해 즉시 필요한 복잡도보다 큼
- 먼저 더 단순한 JDBC 최적화를 검증하는 것이 합리적임

#### 대안 6 — Kafka 또는 별도 비동기 ingestion 계층

장점:

- Backend와 DB write 부하 분리 가능
- buffering과 비동기 처리 확장 가능

단점:

- 현재 규모에서 과도한 운영 복잡도
- Collector persistent queue가 이미 장애 buffering 역할을 수행
- 실제 측정된 병목을 단순 설정으로 크게 개선할 수 있는 상황에서 도입 근거 부족

### 병목 분석

1,050-span baseline 요청 185개에서:

```text
writer_total median       = 288.75ms
prepare_rows median         = 1.64ms
batch_update median        = 286.84ms
bind median                  = 1.19ms
batch_after_bind median    = 285.54ms
```

`batchUpdate` 내부 비율:

```text
bind share median        = 0.40%
after-bind share median = 99.60%
```

따라서 주요 병목은 다음 범위로 좁혀졌다.

```text
JdbcTemplate.batchUpdate
└── parameter binding 이후
    ├── JdbcTemplate batch 처리
    ├── pgJDBC batch 처리
    ├── PostgreSQL protocol / network
    ├── INSERT
    ├── Unique Index
    ├── ON CONFLICT
    ├── TimescaleDB write
    └── update count 처리
```

### 성능 적용 전 Correctness 검증

`reWriteBatchedInserts=true`를 임시로 활성화한 뒤 다음 시나리오를 검증했다.

```text
1. 신규 Span 50개
2. 같은 50개 재전송
3. 기존 25개 + 신규 25개
```

결과:

```text
신규 50개
received=50
inserted=0
duplicates=0
unknown=50

동일 50개 재전송
received=50
inserted=0
duplicates=50
unknown=0

기존 25 + 신규 25
received=50
inserted=0
duplicates=0
unknown=50
```

최종 DB row:

```text
75
```

예상값과 정확히 일치했다.

따라서 다음은 유지된다.

```text
Unique Index 기반 중복 방지
ON CONFLICT DO NOTHING
Collector retry idempotency
실제 DB 데이터 정합성
```

반면 다음 내부 분류의 정확성은 항상 유지되지 않는다.

```text
insertedCount
duplicateCount
```

rewritten batch에서 JDBC driver가 row별 결과를 정확히 알 수 없는 경우 `Statement.SUCCESS_NO_INFO`가 반환될 수 있기 때문이다.

AeroTrace는 이미 이를 다음 값으로 표현한다.

```text
unknownSuccessCount
```

### 성능 A/B

동일 조건:

```text
Target = 3,250 spans/s
Duration = 60초
Expected = 195,000 spans
Sender batch = 50
JDBC batch = 1,000
주요 Backend request = 1,050 spans
```

에서 baseline과 rewrite를 각각 총 3회 측정했다.

조건별 run 중앙값:

| 지표 | rewrite=false | rewrite=true | 변화 |
|---|---:|---:|---:|
| Writer median | 288.75ms | 107.07ms | -62.9% |
| BatchUpdate median | 286.84ms | 104.81ms | -63.5% |
| After-bind median | 285.54ms | 103.47ms | -63.8% |
| DB CPU avg | 94.36% | 37.55% | -60.2% |
| DB CPU median | 94.05% | 37.63% | -60.0% |

Collector size-trigger 예상 주기:

```text
≈ 323.08ms
```

이를 초과한 1,050-span writer:

```text
rewrite=false
57 / 555

rewrite=true
0 / 554
```

모든 sustained run에서:

```text
195000 / 195000 Span 저장
failed request = 0
final Collector queue = 0
final in-flight = 0
```

을 확인했다.

### 선택

Telemetry 저장용 PostgreSQL JDBC URL에 다음 옵션을 활성화한다.

```text
reWriteBatchedInserts=true
```

적용 위치:

```text
backend/src/main/resources/application.yaml
docker-compose.yaml
```

### 선택 이유

- 실제 운영 후보 N100 환경에서 반복 가능한 개선이 확인됐다.
- 동일 3,250 spans/s workload에서 JDBC writer 중앙값이 약 63% 감소했다.
- 동일 workload에서 TimescaleDB CPU가 약 60% 감소했다.
- Collector batch cadence를 초과하던 writer가 반복 rewrite run에서 관찰되지 않았다.
- 새로운 DB나 메시지 브로커를 추가하지 않고 기존 Spring JDBC 구조를 유지할 수 있다.
- Collector persistent queue, JDBC batch, TimescaleDB라는 현재 단순한 아키텍처를 유지하면서 병목을 크게 완화한다.
- 현재 AeroTrace의 MVP 규모와 제한된 홈서버 환경에서 비용 대비 효과가 가장 크다.

### Trade-off

`reWriteBatchedInserts=true`에서는 다음 값이 정확한 row-level 통계가 아닐 수 있다.

```text
insertedCount
duplicateCount
```

특히 신규 row가 하나 이상 포함된 rewritten batch에서 여러 row가 `unknownSuccessCount`로 분류될 수 있다.

하지만 현재:

```text
DB Unique Index가 실제 중복을 차단
ON CONFLICT DO NOTHING으로 idempotency 유지
correctness 테스트에서 최종 DB row 정확성 검증
OTLP HTTP response에는 inserted / duplicate count 미노출
SpanWriteResult에 unknownSuccessCount 존재
```

하므로 이 trade-off를 허용한다.

### 외부 API 계약에 미치는 영향

현재 OTLP ingest API는 성공 시 빈 JSON body를 반환한다.

```json
{}
```

따라서 다음 내부 통계는 사용자 API 계약이 아니다.

```text
inserted
duplicates
unknown
```

현재는 운영 로그와 내부 저장 결과 분석에만 사용한다.

### 이전 Rewrite 보류 결정과의 관계

초기 Windows Docker persistence-only benchmark에서는 `reWriteBatchedInserts=true`가 일관된 개선을 보여주지 않았기 때문에 당시에는 적용을 보류했다.

이번 결정은 이전 측정을 무시하거나 잘못됐다고 판단한 것이 아니다.

측정 환경이 달랐다.

초기:

```text
Windows 개발 PC
Docker Desktop
Persistence-only benchmark
Collector 없음
실제 sustained ingest 아님
DB saturation 낮음
```

이번:

```text
N100 운영 후보 서버
Collector 포함
3,250 spans/s sustained ingest
실제 1,050-span Backend request 분포
높은 TimescaleDB CPU
반복 A/B
```

따라서 성능 결정은 실제 운영 후보 workload에서 다시 측정한 결과를 우선했다.

이는 측정 환경과 workload가 달라지면 최적화 효과도 달라질 수 있다는 사례로 기록한다.

### 성능 수치 표현 원칙

이번 테스트로 최대 처리량을 다시 측정하지 않았기 때문에 다음처럼 표현하지 않는다.

```text
처리량 63% 증가
최대 처리량 63% 향상
```

대신 다음처럼 표현한다.

> 동일한 3,250 spans/s sustained workload에서 JDBC writer 중앙값 약 63%, TimescaleDB CPU 약 60% 감소를 조건별 3회 반복 측정으로 확인했다.

### 운영 적용 검증

정식 적용 후 Runtime JDBC URL:

```text
AEROTRACE_DB_URL=jdbc:postgresql://timescaledb:5432/aerotrace?reWriteBatchedInserts=true
```

1-span smoke:

```text
Requested spans: 1
Accepted spans: 1
Failed requests: 0
```

Backend 저장:

```text
received=1
inserted=1
duplicates=0
unknown=0
```

Backend:

```text
status=running
health=healthy
restart=0
```

### 임시 계측 제거

성능 분석을 위해 추가했던 요청 경로의 `System.nanoTime()`과 timing INFO 로그는 원인 분석 완료 후 제거했다.

Benchmark 클래스의 timing 코드는 benchmark 목적이므로 유지했다.

관련 Commit:

```text
e8a1408 PostgreSQL 배치 INSERT 재작성 최적화 적용
5152cc1 성능 분석용 임시 계측 코드 제거
```

### 재검토 조건

다음 조건 중 하나가 발생하면 이 결정을 다시 검토한다.

- Billing이 실제 inserted row 수에 의존
- Tenant quota 계산이 실제 inserted row 수에 의존
- 사용자 API에 inserted / duplicate 수를 노출
- 정확한 row-level ingestion metric이 필요
- pgJDBC upgrade 후 batch rewrite 또는 update count semantics 변경
- 데이터 중복 형태가 현재 Unique Identity로 처리되지 않음
- PostgreSQL COPY가 동일 correctness 조건에서 더 높은 효율을 보인다는 측정 결과 확보
- 현재 단일 TimescaleDB 저장 구조가 지속적인 bottleneck으로 확인
- 더 높은 ingest rate에서 Collector backlog가 지속적으로 증가

---

## Collector Queue Overflow 시 Backpressure 우선 정책

### 해결하려는 문제

OpenTelemetry Collector가 Backend 장애 또는 저장 지연으로 인해 exporter queue를 모두 사용했을 때 telemetry를 어떻게 처리할지 결정해야 했다.

기존 설정은 다음과 같았다.

```yaml
sending_queue:
  enabled: true
  num_consumers: 2
  sizer: items
  queue_size: 50000
  block_on_overflow: false
  storage: file_storage/aerotrace
```

`block_on_overflow=false`에서는 queue가 더 이상 데이터를 받을 수 없을 때 새 telemetry가 queue 진입에 실패할 수 있다.

AeroTrace는 장애 분석을 위한 telemetry를 저장하는 시스템이므로, 호출자에게 성공처럼 보이는 동안 Span이 조용히 유실되는 동작을 운영 기본 정책으로 허용할지 검증이 필요했다.

### 검토한 방식

다음 두 설정을 동일한 장애 조건에서 비교했다.

```text
block_on_overflow=false
block_on_overflow=true
```

공통 조건:

```text
Collector sending queue size = 50,000 items
Collector consumers = 2
Backend pause로 exporter downstream 차단
2,000 spans/s sustained workload
70,000 requested spans
```

### block_on_overflow=false 결과

Backend가 중단된 상태에서 queue overflow를 발생시킨 결과:

```text
requested spans        = 70,000
sender accepted        = 70,000
receiver accepted Δ    = 70,000
enqueue_failed Δ       = 20,250
sent Δ                 = 49,750
DB stored              = 49,750
missing                = 20,250
```

Sender는 70,000 Span을 Collector가 받은 것으로 판단했지만 최종 DB에는 49,750 Span만 저장됐다.

20,250 Span은 exporter queue 진입 단계에서 유실됐다.

따라서 이 조건에서는 호출자 관점의 성공과 실제 telemetry 보존 여부가 일치하지 않았다.

### block_on_overflow=true 결과

동일한 70,000 Span 장애 실험에서:

```text
enqueue_failed Δ = 0
sent Δ           = 70,000
receiver accepted Δ = 70,000
DB stored        = 70,000
missing          = 0
```

queue saturation 동안 upstream에 backpressure가 전파됐으며 Sender 측에서는 다음 현상이 관찰됐다.

```text
Producer backpressure events = 312
Request latency p99          = 170.502 ms
Producer lag p99             = 7,854.452 ms
Send-start lag p99           = 8,654.874 ms
Sustained-rate validity      = FAIL
```

즉 telemetry를 조용히 버리는 대신 전송 지연이 호출자 쪽으로 전달됐다.

### 장기 장애와 Timeout 검증

queue 포화 상태를 15초 유지한 추가 실험에서는 Sender가 일부 요청을 timeout으로 판단했다.

```text
Requested spans  = 70,000
Accepted spans   = 69,600
Failed requests  = 8
Sender 실패 판단 = 400 spans
```

하지만 Collector와 DB 최종 결과는 다음과 같았다.

```text
enqueue_failed Δ = 0
sent Δ           = 70,000
receiver accepted Δ = 70,000
DB stored        = 70,000
missing          = 0
```

따라서 client timeout이 발생했다고 해서 해당 telemetry가 Collector에 수락되지 않았거나 DB에 저장되지 않았다고 단정할 수 없음을 확인했다.

### Ambiguous Timeout 재현

이 현상을 독립적으로 검증하기 위해 동일 payload를 다시 사용할 수 있는 8개 OTLP 요청을 생성했다.

```text
8 requests
50 spans/request
400 spans total
```

Backend를 pause하고 Collector queue를 포화시켰다.

측정된 포화 상태:

```text
queue_saturated     = 49,550
in_flight_saturated = 2
```

그 상태에서 8개 요청을 전송한 결과 모든 요청이 client timeout으로 종료됐다.

```text
request 01 ~ 08
curl_rc = 28
HTTP    = 000
```

그러나 Backend 복구 후:

```text
probe DB total = 400 / 400
각 request DB  = 50 / 50
final queue    = 0
final in-flight= 0
```

이었다.

즉 client가 실패로 판단한 모든 telemetry가 실제로는 Collector에 남아 있었고 이후 DB에 저장됐다.

### Timeout Retry 중복 검증

Ambiguous timeout 후 실제 client retry 상황을 검증하기 위해 timeout이 발생했던 8개 payload를 그대로 다시 전송했다.

Retry 전:

```text
row count       = 400
distinct identity = 400
```

동일 payload 8개 재전송:

```text
request 01 ~ 08
curl_rc = 0
HTTP    = 200
```

Retry 후:

```text
row count         = 400
distinct identity = 400
row growth        = 0
```

`ingested_at`의 최대값도 변경되지 않았다.

```text
before = 2026-08-14 03:35:59.609128+00
after  = 2026-08-14 03:35:59.609128+00
```

현재 Span 저장 계층은 다음 identity에 대해 Unique Index를 사용한다.

```text
tenant_id
project_id
trace_id
span_id
start_time
```

저장 SQL은 다음 방식이다.

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

따라서 동일 OTLP Span payload가 retry되더라도 새로운 DB row를 만들지 않는 것을 실제 장애 조건에서 검증했다.

### 최종 결정

AeroTrace의 Collector exporter queue는 다음을 기본 정책으로 사용한다.

```yaml
block_on_overflow: true
```

선택 이유:

```text
silent telemetry loss보다 upstream backpressure를 우선
queue overflow 시 enqueue 단계 데이터 유실 방지
client timeout이 발생하더라도 Collector가 이미 보유한 telemetry 보존
ambiguous timeout 후 동일 payload retry는 DB idempotency로 중복 방지
멀티테넌트 identity를 포함한 Unique Index로 tenant/project 간 충돌 방지
```

AeroTrace는 장애 분석을 위한 관측 데이터를 다루므로, 정상처럼 보이면서 telemetry를 조용히 잃는 것보다 지연과 timeout이 호출자에게 명시적으로 전파되는 방식을 선택한다.

### Trade-off

`block_on_overflow=true`는 데이터 유실을 없애는 무료 옵션이 아니다.

downstream 장애가 충분히 길어지면 다음 현상이 발생할 수 있다.

```text
OTLP 요청 latency 증가
upstream backpressure
Sender cadence 붕괴
client timeout
SDK 또는 상위 Collector retry 증가
persistent queue 사용량 증가
```

따라서 `block_on_overflow=true`만으로 무한 장애를 견딜 수 있다고 판단하지 않는다.

queue와 persistent storage 용량을 초과하는 장기 장애에 대해서는 별도의 용량 정책과 데이터 유실 허용 기준이 필요하다.

### 정식 적용 검증

실험용 `/tmp` overlay가 아닌 저장소의 정식 설정에 다음 변경을 적용했다.

```yaml
block_on_overflow: true
```

Runtime mount:

```text
source=/home/huning/aerotrace/otel-collector-config.yaml
destination=/etc/otel-collector-config.yaml
```

정식 설정으로 Collector를 재기동한 후 200 Span smoke 결과:

```text
Requested spans = 200
Accepted spans  = 200
Failed requests = 0
sender_rc       = 0
DB              = 200 / 200
queue           = 0
in_flight       = 0
```

### 재검토 조건

다음 조건이 발생하면 이 결정을 다시 검토한다.

- 실제 사용자 서비스에서 OTLP latency가 애플리케이션 성능에 영향을 줌
- SDK 또는 upstream Collector의 timeout/retry 정책과 충돌
- persistent queue 디스크 사용량이 운영 한계를 초과
- 장시간 DB 장애에서 허용할 수 없는 memory/disk pressure 발생
- tenant별 ingest quota 또는 rate limit을 도입
- sampling 정책으로 overload를 사전에 제어하게 됨
- queue 크기 또는 `num_consumers` 변경 후 새로운 병목 확인
- Collector 버전 변경으로 queue 또는 retry semantics가 변경
- 현재 DB idempotency identity로 처리되지 않는 duplicate 형태 발견

---

## Collector Persistent Queue 용량 및 장애 복구 정책

### 해결하려는 문제

AeroTrace는 OpenTelemetry Collector의 exporter queue에 persistent storage를 연결하고 있다.

현재 구조:

```yaml
sending_queue:
  enabled: true
  num_consumers: 2
  sizer: items
  queue_size: 200000
  block_on_overflow: true
  storage: file_storage/aerotrace

file_storage/aerotrace:
  directory: /var/lib/otelcol/storage
  create_directory: true
  directory_permissions: "0750"
  timeout: 1s
  max_size: 536870912
  fsync: true
  compaction:
    on_start: true
    cleanup_on_start: true
    directory: /var/lib/otelcol/compaction
```

`block_on_overflow=true`를 통해 queue overflow 시 silent telemetry loss 대신 backpressure를 선택했지만, 실제 운영에서는 다음 질문에 대한 검증이 추가로 필요했다.

```text
persistent queue가 실제 디스크에 데이터를 저장하는가?
Collector restart 후 telemetry가 복구되는가?
Collector가 SIGKILL로 비정상 종료되어도 복구되는가?
queue_size=50,000은 실제 장애를 얼마나 버틸 수 있는가?
queue를 확대했을 때 디스크 비용은 현실적인가?
```

### Persistent Storage 구조

Collector의 file storage는 Docker named volume을 사용한다.

```text
Docker volume:
aerotrace-otelcol-data

Container mount:
/var/lib/otelcol

file_storage directory:
/var/lib/otelcol/storage

Host volume source:
/var/lib/docker/volumes/aerotrace-otelcol-data/_data
```

호스트 filesystem 측정 당시:

```text
Filesystem = /dev/sda2
Size       ≈ 468 GiB
Available  ≈ 369 GiB
Usage      = 17%
```

따라서 persistent queue 데이터는 Collector container lifecycle과 분리된 Docker volume에 저장된다.

### 20,000 Span Persistent Storage 측정

Backend를 pause하여 exporter downstream을 차단하고 2,000 spans/s로 10초 동안 20,000 Span을 전송했다.

Sender:

```text
Requested spans = 20,000
Accepted spans  = 20,000
Failed requests = 0
sender_rc       = 0
```

Backend 장애 중:

```text
queue       = 20,000
in_flight   = 2
DB          = 0 / 20,000
```

filesystem 측정:

```text
before apparent   = 77,824 bytes
during apparent   = 4,206,592 bytes
apparent growth   = 4,128,768 bytes

before allocated  = 57,344 bytes
during allocated  = 2,678,784 bytes
allocated growth  = 2,621,440 bytes
```

Collector persistent queue metadata에서는 동일 20,000 Span에 대해:

```text
itemsSize = 20,000
bytesSize = 2,234,800
```

를 기록했다.

따라서 다음 값들은 서로 같은 의미가 아니다.

```text
queue logical payload bytes
bbolt database file size
filesystem allocated bytes
```

capacity planning에서는 실제 filesystem 사용량도 함께 측정해야 한다.

### Graceful Restart 복구 검증

Backend가 pause된 상태에서 20,000 Span을 persistent queue에 저장한 뒤 `docker restart`로 Collector를 재시작했다.

Restart 직전:

```text
queue     = 20,000
in_flight = 2
DB        = 0 / 20,000
```

Restart 후 Backend가 여전히 중단된 상태:

```text
queue     = 20,000
in_flight = 2
DB        = 0 / 20,000
```

Collector startup log:

```text
Loaded queue metadata

itemsSize       = 20000
bytesSize       = 2234800
dispatchedItems = 2
```

이어 진행 중이던 두 item에 대해:

```text
Fetching items left for dispatch by consumers
numberOfItems = 2

Moved items for dispatching back to queue
numberOfItems = 2
```

가 기록됐다.

Backend 복구 후:

```text
DB        = 20,000 / 20,000
queue     = 0
in_flight = 0
```

이었다.

따라서 graceful Collector restart를 가로질러 persistent queue에 저장된 telemetry가 복구되는 것을 실제로 확인했다.

### Shutdown 중 Dropping Data 로그 해석

Graceful restart 과정에서 다음 로그가 두 번 발생했다.

```text
Exporting failed. Dropping data.
dropped_items: 1050
```

로그만 보면 2,100 Span의 영구 유실로 해석할 수 있지만 실제 결과는:

```text
restart 후 itemsSize = 20,000
final DB              = 20,000 / 20,000
```

이었다.

이번 실험에서는 shutdown 시 진행 중이던 export 작업이 중단되면서 queue sender가 drop 로그를 기록했지만 persistent queue metadata에는 항목이 보존됐고 restart 시 다시 queue로 복귀했다.

따라서 `Dropping data` 로그만으로 실제 영구 데이터 유실량을 단정하지 않는다.

다음 정보를 함께 확인한다.

```text
persistent queue metadata
queue size
enqueue failure metric
sent metric
최종 DB row 수
```

단, 다른 failure path의 `Dropping data`까지 항상 안전하다고 일반화하지 않는다.

### SIGKILL Crash Recovery 검증

Graceful shutdown이 아닌 프로세스 비정상 종료 상황을 검증하기 위해 다시 20,000 Span을 queue에 저장했다.

SIGKILL 직전:

```text
queue     = 20,000
in_flight = 2
DB        = 0 / 20,000
```

Collector 강제 종료:

```text
docker kill --signal=KILL aerotrace-otel-collector

exit code = 137
```

Collector가 정상 shutdown hook을 실행할 기회 없이 종료된 뒤 다시 시작했다.

Startup log:

```text
Loaded queue metadata

itemsSize       = 20000
bytesSize       = 2234800
dispatchedItems = 2
```

그리고:

```text
Fetching items left for dispatch by consumers
numberOfItems = 2

Moved items for dispatching back to queue
numberOfItems = 2
```

가 다시 확인됐다.

Backend가 여전히 pause된 상태:

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

이었다.

따라서 AeroTrace의 현재 persistent queue 구성은 실제 실험 범위에서 다음 두 장애를 모두 통과했다.

```text
Collector graceful restart → 20,000 / 20,000 복구
Collector SIGKILL          → 20,000 / 20,000 복구
```

### 기존 queue_size=50,000의 장애 흡수 시간

기존 설정:

```yaml
queue_size: 50000
```

Backend 처리량이 완전히 0이 된다고 가정하면 이론적 outage budget은:

```text
50,000 / incoming spans per second
```

이다.

실제 테스트 workload 기준:

```text
2,000 spans/s
→ 약 25초

3,250 spans/s
→ 약 15.4초
```

이다.

3,250 spans/s는 AeroTrace 운영 후보 서버의 실제 sustained ingest 성능 실험에서 사용한 workload이므로, 약 15초의 완전 장애 buffer는 Backend 재기동, DB stall, 배포 또는 일시적인 host resource contention을 흡수하기에 여유가 작다고 판단했다.

### queue_size=200,000 후보 검증

운영 목표를 다음처럼 설정했다.

> 검증된 3,250 spans/s workload에서도 Backend가 약 1분 동안 완전히 응답하지 못하는 상황을 Collector persistent queue가 흡수할 수 있도록 한다.

필요한 queue:

```text
3,250 spans/s × 60 sec
= 195,000 spans
```

따라서 round number로:

```yaml
queue_size: 200000
```

을 후보로 선정했다.

이론적 outage budget:

```text
2,000 spans/s
→ 100초

3,250 spans/s
→ 약 61.5초
```

### 200,000 Queue Storage 성장 검증

정식 설정을 변경하기 전에 임시 Collector config를 사용하여 `queue_size=200000`을 검증했다.

Backend를 pause한 상태에서 다음 checkpoint까지 순차적으로 backlog를 증가시켰다.

```text
50,000
100,000
150,000
190,000
```

모든 sender test:

```text
sender_rc = 0
```

측정 결과:

```text
checkpoint  queue    file_size    apparent_bytes  allocated_bytes
baseline    0        32,768       45,056          32,768
50k         50,000   8,388,608    8,400,896       6,049,792
100k        100,000  16,777,216   16,789,504      11,894,784
150k        150,000  33,599,488   33,611,776      17,547,264
190k        190,000  33,599,488   33,611,776      21,954,560
```

190,000 Span에서 baseline을 제외한 실제 filesystem allocated 증가량:

```text
21,921,792 bytes
≈ 20.9 MiB
```

이번 workload에서 단순 환산하면:

```text
약 115 bytes/span allocated
```

수준이었다.

단, bbolt의 page allocation과 재사용 때문에 file size가 queue item 수와 선형으로 증가하지 않았으므로 이 값을 모든 telemetry payload에 고정적으로 적용하지 않는다.

특히:

```text
150k file_size = 33,599,488
190k file_size = 33,599,488
```

로 파일 논리 크기는 동일한 상태에서 filesystem allocated bytes만 증가했다.

따라서 persistent storage capacity planning은 단일 `bytes/span` 상수보다 실제 workload 측정을 우선한다.

### 190,000 Span End-to-End 복구

Backend가 중단된 동안:

```text
queue = 190,000
DB    = 0 / 190,000
```

이었다.

Backend 복구 후 queue는 drain됐고:

```text
DB        = 190,000 / 190,000
queue     = 0
in_flight = 0
```

을 확인했다.

즉 190,000 Span backlog 전체가 최종 DB까지 보존됐다.

### 최종 결정

AeroTrace의 기본 Collector queue 용량을:

```yaml
queue_size: 50000
```

에서:

```yaml
queue_size: 200000
```

으로 변경한다.

최종 기본 정책:

```yaml
sending_queue:
  enabled: true
  num_consumers: 2
  sizer: items
  queue_size: 200000
  block_on_overflow: true
  storage: file_storage/aerotrace
```

선택 이유:

```text
silent telemetry loss 대신 backpressure 우선
persistent storage를 통한 Collector restart 복구
SIGKILL 이후에도 queue 복구 검증
3,250 spans/s에서 약 1분의 완전 장애 buffer 확보
190,000 Span 실제 backlog에서 약 21 MiB allocated storage 사용
190,000 / 190,000 end-to-end 복구 검증
현재 홈서버 자원에서 충분히 감당 가능한 storage 비용
```

### 정식 설정 적용 검증

Repository의 정식 설정:

```text
otel-collector-config.yaml
```

에 다음을 적용했다.

```yaml
queue_size: 200000
block_on_overflow: true
```

Runtime mount:

```text
source=/home/huning/aerotrace/otel-collector-config.yaml
destination=/etc/otel-collector-config.yaml
```

정식 설정으로 Collector를 재생성한 뒤 200 Span smoke:

```text
Requested spans = 200
Accepted spans  = 200
Failed requests = 0
sender_rc       = 0
DB              = 200 / 200

final queue     = 0
final in_flight = 0
```

### 현재 검증 범위의 한계

이번 실험으로 다음은 검증했다.

```text
Backend 일시 중단
Collector graceful restart
Collector SIGKILL
최대 190,000 Span persistent backlog
Backend 복구 후 queue drain
최종 DB 데이터 보존
```

하지만 다음 상황은 아직 검증하지 않았다.

```text
호스트 OS reboot
호스트 전원 강제 차단
filesystem corruption
Docker volume 손실
file_storage max_size 도달
host disk full
Docker storage filesystem full
장시간 장애 중 queue_size=200000 완전 포화 이후 동작
```

따라서 현재 정책을 “모든 장애에서 데이터 유실 없음”으로 표현하지 않는다.

### 재검토 조건

다음 조건이 발생하면 queue capacity를 다시 측정하고 조정한다.

- 실제 사용자 telemetry rate가 3,250 spans/s를 지속적으로 초과
- 1분 이상의 Backend/DB 완전 장애를 Collector에서 흡수해야 함
- 여러 tenant의 동시 burst로 queue 포화가 반복
- persistent volume 사용량 증가가 운영상 문제가 됨
- telemetry payload 평균 크기가 현재 benchmark보다 크게 증가
- sampling 또는 tenant quota 정책 도입
- `num_consumers` 변경
- Collector batch 설정 변경
- Collector/file_storage 버전 변경
- queue full 이후 실제 데이터 보존 정책 변경 필요

---

### 200,000 Queue 완전 포화 검증

`queue_size=200000` 정식 적용 후 운영 후보 workload인 3,250 spans/s에서 실제 queue 완전 포화 동작을 추가 검증했다.

테스트 조건:

```text
Backend             = paused
incoming rate       = 3,250 spans/s
requested spans     = 211,250
configured queue    = 200,000
block_on_overflow   = true
num_consumers       = 2
```

실제 queue는 다음 상태에서 포화됐다.

```text
queue_saturated     = 199,500
in_flight_saturated = 2
saturation_elapsed  = 62.622 sec
```

설계 시 계산했던 이론적 장애 buffer는:

```text
200,000 / 3,250
≈ 61.5 sec
```

였으며 실제 실험에서도 약 1분 후 queue saturation이 재현됐다.

configured capacity인 200,000보다 500 작은 199,500에서 plateau가 형성된 것은 현재 workload의 Collector batch granularity 영향으로 해석한다.

포화 상태를 추가로 5초 유지한 결과:

```text
queue     = 199,500
in_flight = 2
```

로 유지됐으며 upstream sender에 backpressure가 발생했다.

```text
Backpressure wait total      = 6,953.442 ms
Producer backpressure events = 155

Request latency max = 6,513.931 ms
Producer lag p99    = 5,503.897 ms
Send-start lag p99  = 5,996.734 ms
```

Sender 결과:

```text
Requested spans  = 211,250
Accepted spans   = 211,250
Failed requests  = 0

Delivery success        = PASS
Sustained-rate validity = FAIL
sender_rc               = 22
```

`sender_rc=22`는 telemetry delivery 실패가 아니라 queue saturation으로 backpressure가 발생하면서 3,250 spans/s의 목표 cadence를 유지하지 못해 sustained-rate validity 검증이 실패한 결과다.

Backend 복구 후:

```text
DB        = 211,250 / 211,250
queue     = 0
in_flight = 0
```

이었다.

Collector raw metrics:

```text
otelcol_exporter_sent_spans     = 211,450
otelcol_receiver_accepted_spans = 211,450
otelcol_receiver_refused_spans  = 0
```

동일 Collector lifecycle에서 포화 실험 직전 수행한 정식 설정 smoke가 200 Span이었으므로 이번 실험의 실제 counter delta는:

```text
sent delta     = 211,250
accepted delta = 211,250
refused delta  = 0
```

로 확인됐다.

`otelcol_exporter_enqueue_failed_spans`는 현재 Collector lifecycle에서 metric series 자체가 노출되지 않았으므로 임의로 수치 0으로 변환해 측정값으로 기록하지 않는다.

End-to-end 결과는:

```text
Requested          = 211,250
Sender accepted    = 211,250
Collector accepted = 211,250
Collector sent     = 211,250
DB stored          = 211,250
Missing            = 0
```

이다.

따라서 `queue_size=200000`과 `block_on_overflow=true` 조합은 이번 검증 workload에서 약 1분의 Backend 완전 장애 buffer를 제공했고, queue 포화 이후에는 silent telemetry loss 대신 upstream backpressure를 발생시키며 Backend 복구 후 전체 telemetry를 저장했다.

이 결과를 근거로 기존 `queue_size=200000` 운영 기본값 결정을 유지한다.

---

### Host Reboot Persistent Queue 복구 검증

Collector process 단위의 restart와 SIGKILL 복구 검증에 이어, Docker daemon과 host OS까지 재시작되는 더 넓은 failure boundary에서 persistent queue가 유지되는지 검증했다.

테스트 조건:

```text
Backend             = stopped
incoming rate       = 2,000 spans/s
requested spans     = 20,000
persistent queue    = enabled
queue_size          = 200,000
block_on_overflow   = true
Docker volume       = aerotrace-otelcol-data
```

Host reboot 직전 상태:

```text
queue_before_reboot     = 20,000
in_flight_before_reboot = 2
DB_before_reboot        = 0 / 20,000
persistent file size    = 33,570,816 bytes
```

Backend는 reboot 전에 정상적으로 stop했고 telemetry는 DB에 저장되지 않은 상태로 Collector persistent queue에만 존재하도록 만들었다.

이후 host에서:

```text
sudo reboot
```

를 실행했다.

Host 재부팅 후 container 상태:

```text
Collector    = running
TimescaleDB  = running / healthy
Backend      = exited
```

Backend가 자동으로 실행되지 않았기 때문에 queue가 DB로 drain되기 전에 reboot 직후 persistent state를 독립적으로 확인할 수 있었다.

Collector startup log:

```text
Loaded queue metadata

itemsSize       = 20000
bytesSize       = 2234800
dispatchedItems = 2
```

이어 restart 전에 consumer가 처리 중이던 두 item을 복구하는 로그도 확인됐다.

```text
Fetching items left for dispatch by consumers
numberOfItems = 2

Moved items for dispatching back to queue
numberOfItems = 2
```

Backend가 여전히 stopped인 상태에서 실제 metric:

```text
queue_after_reboot     = 20,000
in_flight_after_reboot = 2
DB_after_reboot        = 0 / 20,000
```

이었다.

따라서 다음 경로를 거친 뒤에도 persistent queue 전체가 유지되는 것을 확인했다.

```text
Collector process 종료
Docker daemon 종료
Host OS reboot
Docker volume 재마운트
Collector 재기동
persistent queue metadata reload
```

Backend를 다시 시작한 후 queue drain:

```text
18,950
15,800
13,700
10,550
6,350
3,150
0
```

최종 결과:

```text
DB        = 20,000 / 20,000
queue     = 0
in_flight = 0
Backend   = running / healthy
```

따라서 현재 AeroTrace persistent queue 구성은 실제 검증 범위에서 다음 failure boundary를 통과했다.

```text
Collector graceful restart → 20,000 / 20,000 복구
Collector SIGKILL          → 20,000 / 20,000 복구
Host OS reboot             → 20,000 / 20,000 복구
```

이 결과는 Docker named volume에 저장된 Collector persistent queue가 단순 container lifecycle뿐 아니라 host reboot 이후에도 telemetry를 복구할 수 있음을 현재 환경에서 확인한 것이다.

단, 이번 테스트는 정상적인 OS reboot이며 다음 상황까지 검증한 것은 아니다.

```text
강제 전원 차단
filesystem corruption
Docker volume 손실
SSD 또는 filesystem failure
host disk full
file_storage write failure
```

따라서 AeroTrace의 현재 데이터 보존 정책을 "모든 host 장애에서 데이터 유실 없음"으로 표현하지 않는다.

기존 persistent queue 운영 결정은 유지한다.

---

## Collector Queue 운영 상태 판정 정책

### 해결하려는 문제

AeroTrace Collector persistent queue는 다음 장애 복구 실험을 통해 telemetry 보존 동작을 검증했다.

```text
Collector graceful restart
Collector SIGKILL
Host OS reboot
200,000 queue saturation
```

그러나 장애 복구가 가능하다는 것만으로는 충분하지 않다.

실제 운영에서는 queue가 포화된 뒤 장애를 발견하는 것이 아니라 다음 상태를 조기에 판단할 수 있어야 한다.

```text
현재 queue 사용량
남은 queue capacity
현재 ingest workload 기준 예상 headroom
in-flight export 상태
Collector send/receive counter
queue 포화 위험 수준
```

기존에는 shell helper와 개별 `curl` 명령으로 확인했기 때문에 반복 가능하고 일관된 운영 상태 판정이 어려웠다.

### 선택한 방식

Repository에 다음 운영 체크 스크립트를 둔다.

```text
scripts/check-collector-queue.py
```

스크립트는 Collector Prometheus metrics endpoint:

```text
http://127.0.0.1:8888/metrics
```

를 직접 조회하고 repository의:

```text
otel-collector-config.yaml
```

에서 `queue_size`를 읽는다.

따라서 현재 운영 설정:

```text
queue_size=200000
```

과 별도의 hard-coded capacity가 서로 달라지는 것을 방지한다.

### Queue 상태 기준

MVP 기본 threshold:

```text
queue utilization < 50%
→ OK

50% <= queue utilization < 80%
→ WARNING

queue utilization >= 80%
→ CRITICAL
```

exit code:

```text
OK       = 0
WARNING  = 1
CRITICAL = 2
UNKNOWN  = 3
```

이를 통해 사람이 실행하는 CLI뿐 아니라 이후 scheduler, systemd, monitoring integration에서도 같은 상태 판정 로직을 재사용할 수 있게 한다.

### Threshold 선택 근거

현재 운영 후보 workload에서 검증한 ingest rate:

```text
3,250 spans/s
```

현재 queue capacity:

```text
200,000 spans
```

Backend 처리량이 완전히 0이 된 단순 worst-case 모델에서는 queue가 비어 있을 때:

```text
200,000 / 3,250
≈ 61.54 sec
```

의 headroom이 있다.

50% 사용 시:

```text
remaining = 100,000 spans

100,000 / 3,250
≈ 30.77 sec
```

80% 사용 시:

```text
remaining = 40,000 spans

40,000 / 3,250
≈ 12.31 sec
```

이다.

따라서 MVP 초기 기준은:

```text
WARNING:
대략 절반의 장애 buffer를 소비한 시점

CRITICAL:
현재 검증 workload 기준 약 12초 수준의
잔여 buffer만 남은 시점
```

으로 설정한다.

이 threshold는 영구 정책이 아니다.

실제 tenant traffic, sampling, burst 패턴, 운영 대응 시간 측정 후 재조정한다.

### Headroom 표시

스크립트는 다음 값을 함께 표시한다.

```text
full_outage_headroom_sec
```

계산:

```text
(queue_capacity - queue_size)
/
reference_spans_per_sec
```

기본 reference workload:

```text
3,250 spans/s
```

이다.

이 값은 다음 가정에서의 단순 추정치다.

```text
Backend 처리량 = 0
incoming rate = 3,250 spans/s로 일정
```

따라서 실제 SLA나 장애 지속 가능 시간을 보장하는 값으로 사용하지 않는다.

### Metric 부재 처리 정책

Collector metric series가 존재하지 않는 경우 이를 자동으로 `0`으로 처리하지 않는다.

대신:

```text
N/A
```

로 출력한다.

이 결정은 앞선 queue saturation 실험에서 발견한 계측 오류를 반영한 것이다.

예를 들어 현재 Collector lifecycle에서 event가 발생하지 않아 다음 series가 존재하지 않을 수 있다.

```text
otelcol_exporter_enqueue_failed_spans
otelcol_exporter_send_failed_spans
otelcol_receiver_accepted_spans
otelcol_receiver_refused_spans
```

`series 없음`과 `실제 값 0`은 서로 다른 상태이므로 구분한다.

### Metric Label 처리

Queue gauge:

```text
otelcol_exporter_queue_size
otelcol_exporter_in_flight_requests
```

는 다음 label을 사용한다.

```text
data_type="traces"
exporter="otlp_http/aerotrace"
```

Exporter span counter는:

```text
exporter="otlp_http/aerotrace"
```

를 기준으로 찾는다.

Receiver span counter는:

```text
receiver="otlp"
transport="http"
```

를 사용한다.

모든 metric에 동일한 label 조건을 적용하지 않는다.

### 실제 동작 검증

정상 상태:

```text
queue_size=0
queue_capacity=200000
queue_utilization_pct=0.00
full_outage_headroom_sec=61.54

status=OK
exit=0
```

Backend를 pause한 뒤 실제 2,000 Span backlog를 생성했다.

```text
queue_size=2000
queue_capacity=200000
queue_utilization_pct=1.00
full_outage_headroom_sec=60.92
```

기본 production threshold에서는:

```text
status=OK
exit=0
```

이었다.

작은 backlog 자체를 장애로 판단하지 않는 것을 확인했다.

동일한 실제 queue 상태에서 테스트용 threshold를 낮춰 WARNING 경로를 검증했다.

```text
warn=0.5%
critical=2%

queue utilization=1%

status=WARNING
exit=1
```

CRITICAL 경로:

```text
warn=0.1%
critical=0.5%

queue utilization=1%

status=CRITICAL
exit=2
```

잘못된 threshold:

```text
warn=90%
critical=80%
```

에 대해서는:

```text
UNKNOWN
exit=3
```

으로 잘못된 운영 설정을 거부했다.

Backend 복구 후:

```text
DB=2000/2000
queue=0
status=OK
exit=0
```

을 확인했다.

### 최종 결정

AeroTrace MVP의 Collector queue 운영 상태 판정은:

```text
scripts/check-collector-queue.py
```

를 단일 판정 로직으로 사용한다.

기본 정책:

```text
OK       < 50%
WARNING  >= 50%
CRITICAL >= 80%

UNKNOWN:
metric/config/threshold를 신뢰할 수 없는 경우
```

향후 alert scheduler나 외부 monitoring system을 붙일 때 queue threshold 계산을 각 시스템에 다시 구현하지 않고 이 정책과 동일한 의미를 유지한다.

### 재검토 조건

다음 조건에서 threshold와 reference workload를 다시 측정한다.

- 실제 production ingest rate 측정
- tenant 수 증가
- burst traffic 패턴 확인
- sampling 적용
- queue_size 변경
- 장애 대응 평균 시간 측정
- Backend 처리량 변경
- Collector batch 또는 consumer 설정 변경
- 실제 WARNING/CRITICAL 발생 이력 축적

---

## Collector Queue Alert 상태 전이 및 중복 억제 정책

### 해결하려는 문제

Collector queue 상태를 주기적으로 검사할 경우 동일한 장애 상태가 지속되는 동안 매 실행마다 알림을 전송하면 alert storm이 발생한다.

예를 들어 5초마다 queue 상태를 확인하고 CRITICAL 상태가 1분 지속되면 단순 구현에서는 동일한 CRITICAL 알림이 약 12회 발생할 수 있다.

따라서 queue 상태 판정과 실제 알림 발생 여부를 분리한다.

구조:

```text
Collector metrics
    ↓
check-collector-queue.py
    ↓
evaluate-collector-queue-alert.py
    ↓
향후 notification adapter
```

`check-collector-queue.py`는 현재 상태를 판정하고, `evaluate-collector-queue-alert.py`는 이전 상태와 비교해 알림 이벤트가 필요한지를 결정한다.

### 상태 전이 정책

기본 상태:

```text
OK
WARNING
CRITICAL
UNKNOWN
```

상태 전이별 이벤트:

```text
최초 OK
→ NONE

최초 non-OK
→ ALERT

OK → WARNING
→ ALERT

WARNING → WARNING
→ NONE

WARNING → CRITICAL
→ STATUS_CHANGE

CRITICAL → CRITICAL
→ NONE
→ repeat interval 이후 REMINDER 가능

WARNING/CRITICAL/UNKNOWN → OK
→ RECOVERY

Collector/checker 상태 확인 불가
→ UNKNOWN
```

동일 상태가 지속될 때는 기본적으로 중복 알림을 발생시키지 않는다.

### Persistent Alert State

상태 전이를 판단하려면 이전 실행 상태가 필요하다.

기본 state file:

```text
~/.local/state/aerotrace/collector-queue-alert.json
```

`XDG_STATE_HOME`이 설정된 환경에서는 해당 경로를 사용한다.

state에는 다음 정보를 저장한다.

```text
current_status
last_evaluated_at
last_changed_at
last_notification_at
last_notification_epoch
```

state file은 임시 파일에 먼저 기록하고 flush + fsync 후 rename하는 방식으로 교체해 부분 기록 가능성을 줄인다.

### Reminder 정책

non-OK 상태가 장시간 지속될 경우 최초 알림만 발생하고 이후 영원히 조용한 것도 운영상 문제가 될 수 있다.

따라서:

```text
--repeat-after-sec
```

를 지원한다.

기본값:

```text
300 sec
```

동일 WARNING, CRITICAL 또는 UNKNOWN 상태가 계속되더라도 마지막 notification 이후 지정 시간이 지나면:

```text
REMINDER
```

이벤트를 다시 발생시킬 수 있다.

실제 notification adapter가 연결된 뒤 운영 상황을 보고 reminder interval을 재조정한다.

### Evaluator Exit Code 정책

queue checker는 상태 자체를 exit code에 표현한다.

```text
OK       = 0
WARNING  = 1
CRITICAL = 2
UNKNOWN  = 3
```

하지만 evaluator가 같은 값을 그대로 반환하면 systemd 같은 scheduler가 WARNING 또는 CRITICAL을 evaluator process failure로 오인할 수 있다.

따라서 evaluator는 다음 정책을 사용한다.

```text
상태 평가 자체 성공
→ exit 0

evaluator 자체 설정/state 처리 실패
→ exit 4
```

WARNING/CRITICAL/UNKNOWN 여부는 process exit code가 아니라:

```text
current_status
alert_required
event
```

출력으로 전달한다.

### UNKNOWN과 Evaluator Failure 구분

두 상태는 반드시 구분한다.

#### 운영 상태 UNKNOWN

예:

```text
Collector metrics endpoint 접근 실패
checker 실행 파일 없음
checker timeout
checker exit/status 불일치
```

이 경우 evaluator 자체는 정상적으로 문제를 감지했다.

결과:

```text
current_status=UNKNOWN
alert_required=true
evaluator exit=0
```

#### Evaluator 자체 실패

예:

```text
잘못된 evaluator argument
손상된 state JSON
state file write 실패
```

이 경우 evaluator가 정상적인 상태 판정을 수행하지 못했다.

결과:

```text
evaluator exit=4
```

### 실제 상태 전이 검증

실제 Collector 정상 상태:

```text
previous_status=NONE
current_status=OK

event=NONE
alert_required=false
checker_exit_code=0
evaluator_rc=0
```

가짜 checker를 사용해 상태 머신을 독립적으로 검증했다.

```text
OK → WARNING

event=ALERT
alert_required=true
checker_exit_code=1
evaluator_rc=0
```

동일 WARNING 반복:

```text
WARNING → WARNING

event=NONE
alert_required=false
evaluator_rc=0
```

WARNING에서 CRITICAL 승격:

```text
WARNING → CRITICAL

event=STATUS_CHANGE
alert_required=true
checker_exit_code=2
```

CRITICAL 복구:

```text
CRITICAL → OK

event=RECOVERY
alert_required=true
checker_exit_code=0
```

최초 UNKNOWN:

```text
previous_status=NONE
current_status=UNKNOWN

event=ALERT
alert_required=true
checker_exit_code=3
evaluator_rc=0
```

checker 실행 파일이 존재하지 않는 경우:

```text
current_status=UNKNOWN
checker_exit_code=N/A
alert_required=true
evaluator_rc=0
```

Evaluator 자체 argument 오류:

```text
--repeat-after-sec -1

evaluator_error=--repeat-after-sec must be >= 0
evaluator_rc=4
```

### 최종 결정

AeroTrace Collector queue alert는 매 실행마다 직접 알림을 전송하지 않는다.

다음 두 단계를 분리한다.

```text
1. 현재 queue 상태 판정
2. 이전 상태와 비교해 notification event 결정
```

향후 systemd, cron 또는 온프레미스 scheduler는 동일 evaluator를 실행할 수 있으며 실제 Slack, email 등의 notification adapter는 evaluator가:

```text
alert_required=true
```

를 반환한 경우에만 동작하도록 구성한다.

이를 통해 실행 환경과 alert state machine을 분리하고 alert storm을 방지한다.

---

## Collector Queue Alert 자동 실행 및 Host Scheduler 정책

### 해결하려는 문제

Collector queue 상태 판정과 alert 상태 전이 로직을 구현했지만 수동 실행만으로는 실제 운영에서 장애를 조기에 감지할 수 없다.

다음 요구사항이 필요했다.

```text
짧은 주기로 자동 실행
동일 장애의 중복 알림 억제
Collector 자체 장애 감지
복구 이벤트 자동 감지
reboot 후 scheduler 자동 활성화
SaaS host와 온프레미스 환경의 실행 방식 분리
```

### 선택한 구조

AeroTrace SaaS host에서는 systemd를 scheduling adapter로 사용한다.

```text
systemd timer
    ↓
systemd oneshot service
    ↓
evaluate-collector-queue-alert.py
    ↓
check-collector-queue.py
    ↓
Collector metrics endpoint
```

systemd에는 queue threshold나 상태 전이 로직을 구현하지 않는다.

실제 판정 로직은 repository의 Python script에 유지한다.

```text
scripts/check-collector-queue.py
scripts/evaluate-collector-queue-alert.py
```

따라서 향후 온프레미스 환경에서 systemd 대신 다른 scheduler를 사용하더라도 핵심 판정 로직을 재사용할 수 있다.

### 실행 주기

Timer:

```text
OnUnitInactiveSec=5s
AccuracySec=1s
```

를 사용한다.

현재 queue 운영 기준:

```text
queue_size = 200,000
reference workload = 3,250 spans/s

WARNING = 50%
CRITICAL = 80%
```

Backend throughput이 0이라는 단순 outage 모델에서:

```text
50% 사용 시 남은 headroom
≈ 30.77 sec

80% 사용 시 남은 headroom
≈ 12.31 sec
```

이므로 1분 단위 scheduler는 현재 threshold와 맞지 않는다.

5초 polling을 사용해 CRITICAL 상태에서도 남은 buffer 내에서 장애를 발견할 가능성을 높인다.

### Overlap 방지

Timer는:

```text
OnUnitInactiveSec=5s
```

를 사용한다.

즉:

```text
oneshot service 실행
→ 완료
→ 5초
→ 다음 실행
```

순서다.

평가 시간이 일시적으로 길어져도 동일 oneshot service가 중첩 실행되지 않도록 한다.

### 정상 상태 로그 억제

5초마다 queue 상태 전체를 journal에 기록하면 정상 상태에서도 불필요한 로그가 계속 쌓인다.

Evaluator에:

```text
--quiet-no-event
```

옵션을 추가했다.

동작:

```text
event=NONE
→ state는 갱신
→ stdout 없음

ALERT
STATUS_CHANGE
RECOVERY
REMINDER
→ stdout 출력
```

실제 정상 상태 검증:

```text
quiet_rc=0
quiet_output_bytes=0
```

state file은 정상적으로 생성 및 갱신됐다.

UNKNOWN event가 발생한 경우에는 `--quiet-no-event` 상태에서도 이벤트 내용이 출력되는 것을 확인했다.

### Persistent State 위치

systemd service에서는:

```text
StateDirectory=aerotrace-monitoring
```

을 사용한다.

실제 경로:

```text
/var/lib/aerotrace-monitoring
```

Alert state:

```text
/var/lib/aerotrace-monitoring/collector-queue-alert.json
```

실제 권한:

```text
directory:
owner=huning
group=huning
mode=750

state file:
owner=huning
group=huning
mode=640
```

runtime state를 repository나 `/tmp`에 저장하지 않는다.

### systemd Service 정책

Service:

```text
Type=oneshot
User=huning
Group=huning
WorkingDirectory=/home/huning/aerotrace
```

기본 hardening:

```text
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=read-only
UMask=0027
```

Collector metrics endpoint:

```text
127.0.0.1:8888
```

접근이 필요하므로 service의 network namespace를 완전히 격리하는 `PrivateNetwork=true`는 사용하지 않는다.

### 자동 실행 검증

Timer:

```text
enabled
active
```

Service:

```text
Result=success
ExecMainStatus=0
```

State의 `last_evaluated_at`이 timer 실행에 따라 반복 갱신되는 것을 확인했다.

첫 검증:

```text
before:
2026-08-18T03:42:28+00:00

after:
2026-08-18T03:42:40+00:00

timer_state_update=PASS
```

두 번째 검증:

```text
timer_repeat=PASS
```

따라서 timer가 단순 active 상태인 것뿐 아니라 실제 evaluator를 반복 실행하는 것을 확인했다.

### 정상 상태 Journal

정상 상태에서는 evaluator 상세 metric 출력이 기록되지 않았다.

Journal에는 systemd lifecycle:

```text
Starting...
Deactivated successfully.
Finished...
```

정도만 반복됐다.

따라서 5초 polling을 유지하면서 queue metric 전체가 정상 상태마다 journal에 누적되는 문제를 방지한다.

### Collector 장애 자동 탐지 검증

실제 Collector container를 중단했다.

```text
running=false
status=exited
```

사람이 evaluator를 직접 실행하지 않은 상태에서 systemd timer가 자동으로 장애를 감지했다.

```text
event=ALERT
alert_required=true
previous_status=OK
current_status=UNKNOWN
checker_exit_code=3

checker_stderr=
UNKNOWN: Connection refused
```

Alert 발생 시각:

```text
2026-08-18 12:46:39 KST
```

Collector가 계속 중단된 상태에서도 추가 ALERT는 발생하지 않았다.

```text
alert_count=1
```

따라서:

```text
OK → UNKNOWN
→ ALERT 1회

UNKNOWN → UNKNOWN
→ 중복 억제
```

가 실제 systemd polling 환경에서도 검증됐다.

### Collector 복구 자동 감지

Collector를 다시 시작하고 metrics endpoint가 복구된 뒤 systemd timer가 자동으로 상태 변화를 감지했다.

```text
event=RECOVERY
alert_required=true
previous_status=UNKNOWN
current_status=OK
checker_exit_code=0
```

Recovery 발생 시각:

```text
2026-08-18 12:47:15 KST
```

최종:

```text
alert_count=1
recovery_count=1
current_status=OK
```

이었다.

### 최종 결정

AeroTrace SaaS host의 Collector queue monitoring은 다음 구조를 사용한다.

```text
5초 systemd timer
→ oneshot evaluator
→ 상태 변화가 있을 때만 event 출력
→ persistent state로 중복 억제
```

systemd는 실행 시점만 담당하고 queue 판단 및 alert state machine은 Python 코드에 유지한다.

외부 notification adapter는 이후:

```text
alert_required=true
```

이벤트만 받아 전달하도록 구현한다.

### 재검토 조건

다음 상황에서 polling 주기 또는 scheduler 방식을 재검토한다.

- 실제 production ingest rate 변화
- queue capacity 변경
- warning/critical threshold 변경
- 실제 장애 대응 시간 측정
- systemd service 실행 시간이 5초에 근접
- 온프레미스에서 systemd가 없는 환경 지원
- monitoring stack 도입으로 별도 alert engine을 사용하게 되는 경우

---

## Collector Queue Alert Threshold 전달 및 실제 Severity 전이 검증

### 해결하려는 문제

Collector queue checker는 production 운영 기준으로 다음 threshold를 사용한다.

```text
WARNING  = 50%
CRITICAL = 80%
```

queue capacity가 200,000이므로 실제 production threshold를 그대로 사용해 WARNING과 CRITICAL을 재현하려면 각각 약 다음 backlog가 필요하다.

```text
WARNING  = 100,000 items
CRITICAL = 160,000 items
```

이미 persistent queue capacity 및 saturation 실험을 통해 대규모 backlog 동작을 검증했기 때문에 alert state machine 검증만을 위해 같은 규모의 telemetry를 반복 생성하는 것은 불필요하다.

또한 테스트를 위해 repository의 production threshold 자체를 변경하면 테스트 설정이 실수로 운영 환경에 남을 위험이 있다.

### 선택한 방식

Evaluator에 반복 지정 가능한 옵션을 추가했다.

```text
--checker-arg
```

이 옵션을 통해 필요한 경우에만 queue checker에 argument를 전달한다.

예:

```text
evaluate-collector-queue-alert.py
  --checker-arg=--warn-ratio
  --checker-arg=0.005
  --checker-arg=--critical-ratio
  --checker-arg=0.02
```

Evaluator는 checker를 shell command 문자열로 조합하지 않고 subprocess argument list로 실행한다.

따라서 전달된 값은 별도의 argv 항목으로 checker에 전달된다.

Production systemd unit에는 `--checker-arg`를 추가하지 않는다.

기본 운영 실행은 기존 checker default를 그대로 사용한다.

```text
WARNING  = 0.50
CRITICAL = 0.80
```

### Checker Argument 전달 검증

기존 evaluator 실행은 변경 후에도 정상 동작했다.

```text
event=NONE
current_status=OK
checker_exit_code=0
base_rc=0
```

테스트 threshold를 전달한 경우에도 queue가 0이면 정상적으로 OK를 유지했다.

```text
current_status=OK
checker_exit_code=0
arg_rc=0
```

잘못된 threshold:

```text
warn=0.9
critical=0.8
```

를 전달하면 checker는 다음과 같이 실패했다.

```text
checker_exit_code=3
checker_stderr=UNKNOWN: warning ratio must be lower than critical ratio.
```

Evaluator는 이를 운영 상태 UNKNOWN으로 변환했다.

```text
event=ALERT
alert_required=true
current_status=UNKNOWN
```

Evaluator 자체 실행은 정상적으로 evaluation을 완료했으므로:

```text
bad_rc=0
```

을 반환했다.

즉 checker 오류와 evaluator 자체 실행 오류를 분리해서 처리한다.

### 테스트 설정 적용 방식

실제 systemd timer 자동 실행 경로까지 검증하기 위해 repository 또는 `/etc/systemd/system`의 production unit을 수정하지 않았다.

대신 다음 runtime-only drop-in을 사용했다.

```text
/run/systemd/system/
aerotrace-collector-queue-alert.service.d/
10-test-thresholds.conf
```

systemd에서 기존 `ExecStart`를 override하기 위해 다음 구조를 사용했다.

```text
ExecStart=
ExecStart=<test command>
```

첫 번째 빈 `ExecStart=`로 기존 명령을 초기화한 뒤 테스트용 명령을 등록한다.

테스트가 끝난 뒤 runtime drop-in을 삭제하고:

```text
systemctl daemon-reload
```

를 수행했다.

최종 확인:

```text
production_execstart=PASS
```

를 통해 production service에 테스트용 `--checker-arg`가 남지 않았음을 확인했다.

### 실제 WARNING 자동 탐지

실제 backlog는 2,000 spans를 사용했다.

```text
queue capacity = 200,000
queue size     = 2,000
utilization    = 1.00%
```

첫 번째 테스트 threshold:

```text
WARNING  = 0.5%
CRITICAL = 2.0%
```

이 기준에서 1% queue는 WARNING이다.

Backend를 pause하고 실제 2,000 spans를 Collector로 전송했다.

Sender 결과:

```text
Requested spans: 2000
Accepted spans: 2000
Failed requests: 0
Observed accepted spans/sec: 999.87
Rate error pct: 0.013
Delivery success: PASS
Sustained-rate validity: PASS
```

Collector queue:

```text
queue_size=2000
queue_capacity=200000
queue_utilization_pct=1.00
```

Production checker를 직접 실행하면 기존 50%/80% threshold가 그대로 적용되므로:

```text
status=OK
```

이었다.

반면 runtime-only test threshold를 사용하는 systemd evaluator는 자동으로 다음 이벤트를 생성했다.

```text
event=ALERT
alert_required=true
previous_status=OK
current_status=WARNING
checker_exit_code=1
queue_size=2000
queue_utilization_pct=1.00
```

동일 WARNING 상태가 유지되는 동안:

```text
warning_alert_count=1
```

로 중복 ALERT가 억제됐다.

Backend 복구 후 queue가 0으로 drain되자 다음 이벤트가 자동 생성됐다.

```text
event=RECOVERY
alert_required=true
previous_status=WARNING
current_status=OK
checker_exit_code=0
```

테스트 span은 DB에 모두 저장됐다.

```text
2000 / 2000
```

### 실제 WARNING → CRITICAL 승격 검증

두 번째 실험에서도 실제 2,000 spans backlog를 사용했다.

Sender 결과:

```text
Requested spans: 2000
Accepted spans: 2000
Failed requests: 0
Observed accepted spans/sec: 999.91
Rate error pct: 0.009
Delivery success: PASS
Sustained-rate validity: PASS
```

Collector queue:

```text
queue_size=2000
queue_utilization_pct=1.00
```

첫 번째 테스트 threshold:

```text
WARNING  = 0.5%
CRITICAL = 2.0%
```

에서 systemd evaluator가 자동으로:

```text
event=ALERT
alert_required=true
previous_status=OK
current_status=WARNING
checker_exit_code=1
```

을 생성했다.

Backend를 pause 상태로 유지해 실제 queue를 그대로 둔 뒤 threshold만 다음과 같이 변경했다.

```text
WARNING  = 0.1%
CRITICAL = 0.5%
```

동일한 1% queue가 이제 CRITICAL 조건을 만족하면서 systemd evaluator는 자동으로 다음 상태 전이를 감지했다.

```text
event=STATUS_CHANGE
alert_required=true
previous_status=WARNING
current_status=CRITICAL
checker_exit_code=2
queue_size=2000
queue_utilization_pct=1.00
```

이벤트 수는 다음과 같았다.

```text
alert_count=1
status_change_count=1
```

따라서 동일 WARNING 반복과 WARNING → CRITICAL severity 상승을 서로 다른 운영 이벤트로 구분할 수 있음을 확인했다.

### CRITICAL Recovery 검증

Backend를 unpause한 뒤 queue는 다음과 같이 drain됐다.

```text
1000
1000
0
```

systemd evaluator는 queue가 0으로 복구된 것을 자동 감지했다.

```text
event=RECOVERY
alert_required=true
previous_status=CRITICAL
current_status=OK
checker_exit_code=0
```

최종 결과:

```text
recovery_count=1
db=2000/2000
queue_size=0
current_status=OK
timer_enabled=enabled
timer_active=active
```

### 최종 결정

Production threshold는 기존 운영 초기값을 유지한다.

```text
WARNING  = 50%
CRITICAL = 80%
```

`--checker-arg`는 테스트나 명시적인 deployment adapter에서 checker option을 전달해야 할 때 사용할 수 있는 범용 실행 기능으로 제공한다.

운영 기본 systemd unit에는 테스트 threshold를 포함하지 않는다.

실제 Collector queue metric을 이용해 다음 severity state machine 전체를 검증했다.

```text
OK
→ WARNING
→ CRITICAL
→ OK
```

각 상태 전이에 대응하는 이벤트는 다음과 같다.

```text
OK → WARNING
= ALERT

WARNING → CRITICAL
= STATUS_CHANGE

CRITICAL → OK
= RECOVERY
```

### 재검토 조건

다음 상황에서 production threshold를 재검토한다.

- 실제 production ingest rate가 현재 기준에서 크게 변하는 경우
- persistent queue capacity를 변경하는 경우
- 실제 장애 대응 시간을 측정한 경우
- WARNING 시점이 운영자가 대응하기에 너무 늦거나 너무 빠른 경우
- queue drain 속도와 ingest rate를 함께 고려한 동적 headroom 판단이 필요한 경우
- tenant별 workload 특성이 크게 달라지는 경우

현재 50%/80% 값은 운영 초기 기준이며 실제 사용자 workload와 장애 대응 데이터를 수집한 뒤 조정한다.

---

## Notification Event Contract와 Durable Outbox 기반 전달 경계

### 해결하려는 문제

Collector queue alert evaluator는 기존에 다음 운영 이벤트를 판단할 수 있었다.

```text
ALERT
STATUS_CHANGE
REMINDER
RECOVERY
```

하지만 이벤트는 stdout과 systemd journal에만 출력됐다.

외부 notification을 추가할 때 evaluator가 Slack, Discord 또는 HTTP webhook을 직접 호출하도록 만들면 다음 책임이 한 프로세스에 결합된다.

```text
Collector 상태 판단
상태 전이 판단
중복 alert 억제
notification transport
HTTP timeout
transport retry
외부 서비스 장애 처리
```

이 구조에서는 notification provider 장애가 Collector monitoring 자체에 영향을 줄 위험이 있다.

또한 evaluator stdout을 notification process에 단순 pipe하는 경우 downstream process가 실패했을 때 notification event를 재처리할 durable source가 없다.

### 선택한 구조

Evaluator와 notification transport 사이에 JSON event contract와 filesystem outbox를 추가했다.

```text
check-collector-queue.py
        ↓
evaluate-collector-queue-alert.py
        ↓
JSON event contract
        ↓
persistent outbox
        ↓
process-notification-outbox.py
        ↓
notification transport
```

Evaluator의 책임:

```text
현재 queue 상태 판단
상태 전이 판단
ALERT / STATUS_CHANGE / REMINDER / RECOVERY 결정
duplicate notification eligibility 억제
notification event를 outbox에 handoff
```

Notification adapter의 책임:

```text
pending event 조회
event contract 검증
전송
전송 실패 시 pending 유지
성공 시 pending ACK
중복 event_id 처리
```

외부 transport는 아직 연결하지 않았다.

현재 adapter는 전달 의미와 실패 처리 검증을 위해 local filesystem sink를 사용한다.

### Machine-readable Event Contract

기존 사람이 읽는 text 출력은 기본값으로 유지했다.

```text
--output-format text
```

Notification consumer가 필요한 경우:

```text
--output-format json
```

을 명시한다.

Schema version 1의 기본 구조:

```json
{
  "schema_version": 1,
  "event_id": "...",
  "event": "ALERT",
  "alert_required": true,
  "previous_status": "OK",
  "current_status": "UNKNOWN",
  "checker_exit_code": 3,
  "state_file": "...",
  "evaluated_at": "...",
  "checker_output": {
    "stdout": "...",
    "stderr": "..."
  }
}
```

JSON stdout은 한 이벤트당 정확히 한 줄이다.

이전 상태가 없는 경우 text 표현:

```text
previous_status=NONE
```

과 달리 JSON contract에서는 실제 값:

```json
"previous_status": null
```

을 사용한다.

### Event ID

각 notification event에는 고유한:

```text
event_id
```

를 부여한다.

현재 생성 방식:

```text
time.time_ns() + process id
```

event_id는 notification adapter가 동일 event를 식별하고 중복 ACK를 처리하기 위한 식별자다.

### Outbox 저장 순서

Notification이 필요한 event에서는 evaluator state보다 outbox를 먼저 저장한다.

```text
event 결정
→ outbox 저장
→ evaluator state 저장
```

이 순서를 선택한 이유는 반대 순서에서 발생할 수 있는 alert 유실 때문이다.

잘못된 순서:

```text
state 저장
→ outbox 저장 실패
```

이면 state는 이미 WARNING/UNKNOWN 등으로 진행했지만 최초 ALERT event는 저장되지 않을 수 있다.

다음 cycle에서는 동일 상태이므로:

```text
UNKNOWN → UNKNOWN
```

이 되고 event가 `NONE`이 되어 최초 alert를 잃을 가능성이 있다.

현재 방식에서는 outbox 저장에 실패하면 evaluator가:

```text
rc=4
```

로 종료하고 state를 갱신하지 않는다.

실제 검증:

```text
evaluator_error=event outbox write failed: ...
outbox_failure_rc=4
state_after_outbox_failure=PASS_not_written
```

outbox 문제를 제거한 뒤 같은 상태를 다시 평가하면 ALERT를 다시 생성할 수 있음을 확인했다.

### Notification Adapter Delivery Semantics

새 adapter:

```text
scripts/process-notification-outbox.py
```

는 pending JSON event를 순서대로 처리한다.

정상 전달:

```text
pending
→ local sink fsync
→ delivered directory fsync
→ pending unlink
→ outbox directory fsync
```

정상 결과:

```text
delivery_result=DELIVERED
processed_events=1
remaining_events=0
```

전송 완료 후 pending은 제거된다.

### Crash Window 중복 처리

다음 crash window를 고려했다.

```text
transport delivery 성공
→ process crash
→ pending ACK 수행 전 종료
```

동일 event_id가 delivered sink와 pending에 동시에 존재하는 상태를 인위적으로 만들었다.

Adapter는 delivered payload가 동일하면 재전송하지 않고:

```text
delivery_result=ACK_EXISTING
```

으로 pending만 제거했다.

검증:

```text
pending_before_ack_existing=1
pending_after_ack_existing=0
delivered_after_ack_existing=1
```

### Notification Failure Isolation

Notification delivery 실패는 evaluator state를 rollback하지 않는다.

실제 local adapter 테스트:

```text
adapter_error=delivery failed: ...
delivery_failure_rc=2
pending_after_delivery_failure=1
```

동시에 evaluator state:

```text
current_status=UNKNOWN
```

은 유지됐다.

전송 경로를 복구한 뒤 adapter 재실행 시 기존 pending event가 정상 전달됐다.

```text
delivery_result=DELIVERED
pending_after_retry=0
delivered_after_retry=1
```

### systemd 실행 구조

실제 운영 구조 검증을 위해 runtime-only systemd 환경에서 evaluator와 notification adapter를 별도 timer로 실행했다.

```text
aerotrace-collector-queue-alert.timer
        ↓
evaluator
        ↓
notification-outbox

aerotrace-notification-outbox.timer
        ↓
notification adapter
        ↓
local delivery sink
```

두 timer 모두 약 5초 주기로 독립적으로 실행했다.

정상 상태에서는:

```text
pending=0
delivered=0
```

이며 evaluator의 `--quiet-no-event`와 adapter의 `--quiet-idle`을 사용해 systemd journal에 Python 정상 상태 로그가 반복되지 않도록 했다.

### 실제 ALERT → RECOVERY 자동 E2E

Collector를 실제 중단했다.

```text
running=false
status=exited
```

Evaluator timer가 자동 감지:

```text
event=ALERT
alert_required=true
previous_status=OK
current_status=UNKNOWN
checker_exit_code=3
```

Notification adapter timer가 자동 처리:

```text
delivery_result=DELIVERED
event=ALERT
processed_events=1
remaining_events=0
```

Collector를 다시 시작한 뒤 evaluator가:

```text
RECOVERY
UNKNOWN → OK
```

를 자동 생성하고 adapter가 자동 전달했다.

최종 delivered event sequence:

```text
1. ALERT
   OK → UNKNOWN

2. RECOVERY
   UNKNOWN → OK
```

검증:

```text
delivered_event_sequence=PASS
final_pending=0
final_delivered=2
```

### systemd Notification 장애 격리 검증

Notification delivery directory를 의도적으로 사용할 수 없는 path로 변경해 adapter만 실패시켰다.

Collector를 중단하자 evaluator는 정상적으로:

```text
OK → UNKNOWN
```

을 감지했고 pending ALERT를 생성했다.

```text
state=UNKNOWN
pending=1
pending_alert_after_delivery_failure=PASS
```

Notification journal:

```text
adapter_error=delivery failed: [Errno 17] File exists: ...
failed_event_id=1787035511951348652-1520983
remaining_events=1
```

Notification failure 동안 evaluator는 계속 실행됐다.

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
```

였으며 evaluator 자체는:

```text
Result=success
ExecMainStatus=0
```

을 유지했다.

따라서 notification transport 장애와 monitoring 상태 평가가 독립적으로 동작함을 실제 systemd 환경에서 검증했다.

### Notification 자동 재시도

Notification failure override를 제거한 뒤 adapter를 사람이 직접 실행하지 않았다.

다음 timer trigger에서 기존 pending ALERT가 자동 처리됐다.

```text
pending=0
delivered=3
automatic_pending_retry=PASS
```

재전송된 event:

```text
event=ALERT
previous=OK
current=UNKNOWN
event_id=1787035511951348652-1520983
```

으로 원래 pending event와 동일했다.

Collector 복구 후 RECOVERY도 자동 전달됐다.

```text
state=OK
delivered=4

automatic_recovery_after_notification_failure=PASS
failure_recovery_sequence=PASS
```

최종 sequence:

```text
ALERT
OK → UNKNOWN

RECOVERY
UNKNOWN → OK
```

### systemctl Result 해석

Notification failure 실험 중 다음 조회가:

```text
Result=success
ExecMainStatus=0
```

으로 보인 시점이 있었다.

그러나 같은 실험 구간 journal에는 실제 delivery failure가 기록돼 있었다.

이 notification service는 timer에 의해 반복 실행되는 oneshot unit이므로 `systemctl show`의 Result와 ExecMainStatus는 조회 시점의 최근 invocation 결과를 나타낼 수 있다.

따라서 반복 timer의 과거 실패를 확인할 때는 단순 최신 `systemctl show` 값만 사용하지 않고 다음을 함께 확인한다.

```text
journal의 failure log
event_id
pending event 유지
전송 후 delivered 상태
```

### 운영 설정 복원

모든 notification systemd 검증은 `/run/systemd/system` runtime-only 설정으로 수행했다.

테스트 종료 후:

```text
notification runtime service 제거
notification runtime timer 제거
evaluator outbox runtime override 제거
test outbox/delivered data 제거
systemctl daemon-reload
```

를 수행했다.

최종 확인:

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

evaluator:
current_status=OK
```

### 현재 보장 범위

현재 실험으로 확인한 것은 다음이다.

```text
process 실행 중 outbox write 실패 방지
notification process 실패 시 pending 유지
systemd 반복 실행을 통한 자동 retry
event_id 기반 local duplicate ACK
notification 장애와 evaluator 실행 격리
ALERT와 RECOVERY의 순서 보존
```

아직 다음은 검증하지 않았다.

```text
호스트 전원 차단 직전 outbox directory entry durability
filesystem corruption
disk full
외부 HTTP transport의 ambiguous timeout
외부 provider에서의 실제 duplicate delivery
외부 notification provider 인증 정보 관리
```

Evaluator의 outbox write는 파일 자체를 fsync한 뒤 rename하지만 현재 outbox directory fsync까지 수행하지 않으므로 갑작스러운 power loss까지 포함한 완전한 filesystem crash durability를 주장하지 않는다.

### 상태 필드 의미 주의

현재 evaluator state의:

```text
last_notification_at
last_notification_epoch
```

는 실제 외부 notification 전달 완료 시각이 아니다.

현재 의미는 evaluator가:

```text
notification-required event를 결정하고
outbox handoff를 수행한 시각
```

에 더 가깝다.

따라서 실제 외부 transport를 production에 연결하기 전에 해당 상태 필드의 이름과 책임을 재검토한다.

실제 delivery 성공 시각은 notification adapter의 책임으로 분리하는 것이 적절하다.

---

## Alert Event 발생 시각과 Notification Delivery 시각의 의미 분리

### 문제

Collector queue alert evaluator의 기존 persistent state에는 다음 필드가 있었다.

```text
last_notification_at
last_notification_epoch
```

하지만 이 값은 실제 외부 notification transport가 전송에 성공한 시각이 아니다.

실제 의미는 evaluator가:

```text
ALERT
STATUS_CHANGE
REMINDER
RECOVERY
```

와 같이 `alert_required=true`인 event를 결정한 시각이었다.

외부 webhook transport를 연결하면 다음 두 시각이 달라질 수 있다.

```text
alert event 발생 시각
notification 실제 delivery 성공 시각
```

예:

```text
15:00 ALERT event 생성
15:00 webhook 전송 실패
15:02 retry 후 실제 delivery 성공
```

기존 `last_notification_at`이라는 이름은 이 경우 실제 의미를 잘못 표현하게 된다.

### 결정

Evaluator state의 의미를 다음과 같이 변경한다.

```text
last_notification_at
→ last_alert_event_at

last_notification_epoch
→ last_alert_event_epoch
```

이 필드는 evaluator가 notification이 필요한 event를 발생시킨 시각을 의미한다.

실제 transport delivery 성공 시각은 evaluator state에 저장하지 않고 notification adapter의 별도 delivery receipt 책임으로 분리한다.

### 기존 State 호환성

기존 production state에는 legacy key가 이미 존재하므로 새 key만 읽도록 즉시 변경하지 않는다.

읽기 우선순위:

```text
1. last_alert_event_*
2. 값이 없으면 legacy last_notification_*
```

저장 시에는:

```text
legacy key 제거
→ 새 last_alert_event_* key로 저장
```

한다.

이를 통해 기존 서비스 상태 파일을 별도 수동 migration 없이 첫 evaluator 실행에서 자동 변환한다.

### Migration 검증

Legacy fixture:

```text
last_notification_at
last_notification_epoch
```

를 가진 state를 evaluator가 읽은 뒤:

```text
legacy_state_migration=PASS
```

를 확인했다.

Migration 이후:

```text
last_alert_event_at 존재
last_alert_event_epoch 존재

last_notification_at 없음
last_notification_epoch 없음
```

을 확인했다.

### Repeat Suppression 호환성

기존 `last_notification_epoch`는 non-OK 상태의 REMINDER 반복 억제 기준으로 사용되고 있었다.

Legacy WARNING state에 최근 notification timestamp를 넣은 뒤 evaluator를 실행했다.

결과:

```text
event=NONE
alert_required=false
previous_status=WARNING
current_status=WARNING

legacy_repeat_suppression=PASS
reminder_state_migration=PASS
```

따라서 state field migration 때문에 기존 300초 repeat suppression 기준이 초기화되지 않음을 확인했다.

### Production State Migration

Production migration 전:

```text
last_notification_at
= 2026-08-18T06:45:59+00:00

last_notification_epoch
= 1787035559.0960143
```

Migration 후:

```text
last_alert_event_at
= 2026-08-18T06:45:59+00:00

last_alert_event_epoch
= 1787035559.0960143
```

으로 기존 timestamp가 그대로 보존됐다.

Production state:

```text
production_state_migration=PASS
current_status=OK
```

Evaluator timer 재시작 후에도 새 state field를 유지하면서 정상적으로 반복 평가되는 것을 확인했다.

### 최종 책임 분리

현재 의미는 다음과 같다.

```text
Evaluator
last_alert_event_at
last_alert_event_epoch
→ notification-required event를 발생시킨 시각

Notification Adapter
→ 실제 transport 전송 책임
→ 실제 delivery 성공 시각은 별도 receipt로 관리
```

이 구분을 기준으로 이후 Generic Webhook transport와 delivery receipt를 설계한다.

---

## Notification Delivery Receipt 분리

### 해결하려는 문제

Evaluator의:

```text
evaluated_at
last_alert_event_at
```

은 notification이 필요한 event를 판단한 시각이다.

하지만 실제 notification transport가 성공한 시각은 이와 다를 수 있다.

예:

```text
07:17:50 event 판단
07:17:57 transport 성공
```

따라서 event 발생 시각과 실제 delivery 성공 시각을 같은 상태 값으로 표현하면 안 된다.

### 선택한 구조

Notification adapter의 성공 결과를 별도의 delivery receipt로 저장한다.

기존 local delivered 파일:

```text
<event_id>.json
= event payload
```

대신 다음 receipt contract를 사용한다.

```json
{
  "receipt_schema_version": 1,
  "event_id": "...",
  "transport": "local-file",
  "delivered_at": "...",
  "event": {
    "schema_version": 1,
    "event_id": "...",
    "evaluated_at": "...",
    "event": "ALERT"
  }
}
```

의미는 다음과 같다.

```text
event.evaluated_at
= evaluator가 event를 판단한 시각

receipt.delivered_at
= notification adapter가 transport 성공을 확정한 시각
```

### CLI 변경

Notification adapter의 성공 기록 위치를 의미에 맞게:

```text
--receipt-dir
```

로 표현한다.

기존:

```text
--delivered-dir
```

은 기존 테스트 및 배포 명령과의 호환성을 위해 alias로 유지한다.

```text
--receipt-dir RECEIPT_DIR
--delivered-dir RECEIPT_DIR
```

두 옵션은 동일한 `receipt_dir`을 사용한다.

### Delivery Receipt 검증

실제 ALERT event:

```text
event_id=1787037470416077807-1565940

evaluated_at
= 2026-08-18T07:17:50+00:00

delivered_at
= 2026-08-18T07:17:57+00:00
```

Receipt 검증:

```text
receipt_schema_version=1
transport=local-file
event=ALERT

delivery_receipt_contract=PASS
receipt_identity=PASS
```

`delivered_at >= evaluated_at`임을 확인했다.

### Crash Window 처리

다음 상황을 재현했다.

```text
delivery receipt 저장 성공
→ pending ACK 전에 process 종료
→ 동일 event가 pending에 다시 존재
```

기존 receipt가 있는 동일 event를 다시 처리한 결과:

```text
delivery_result=ACK_EXISTING
ack_existing_rc=0

pending_before_ack_existing=1
pending_after_ack_existing=0

receipt_after_ack_existing=1
```

기존 receipt의 payload가 pending event와 동일한 경우 새 delivery를 생성하지 않고 pending만 ACK한다.

### 최초 Delivery 시각 보존

ACK_EXISTING 전:

```text
delivered_at
= 2026-08-18T07:17:57+00:00
```

ACK_EXISTING 후:

```text
delivered_at
= 2026-08-18T07:17:57+00:00
```

검증:

```text
delivery_timestamp_preserved=PASS
```

따라서 process retry나 crash recovery가 실제 최초 delivery 성공 시각을 덮어쓰지 않는다.

### Receipt 저장 실패

Receipt path를 의도적으로 일반 파일로 만들어 저장을 실패시켰다.

결과:

```text
adapter_error=delivery failed: ...
receipt_failure_rc=2
pending_after_receipt_failure=1
```

Receipt를 영속화하기 전에 pending event를 제거하지 않음을 확인했다.

### 현재 Delivery 의미

현재 `transport=local-file`은 외부 notification provider가 아니라 transport semantics 검증용 구현이다.

따라서 현재 receipt의 `delivered_at`은:

```text
local-file transport 성공 시각
```

을 의미한다.

향후 Generic Webhook을 추가하면:

```text
transport=webhook
```

등 transport별 receipt를 생성하고 HTTP 성공 기준에 따라 `delivered_at`을 기록한다.

### 현재 결정

Notification 상태는 다음처럼 분리한다.

```text
Evaluator
→ event 발생 시각 관리

Notification Adapter
→ transport 실행
→ 성공 receipt 관리

Receipt
→ 실제 성공 처리 시각 보존
```

외부 transport retry가 발생하더라도 기존 성공 receipt가 존재하면 동일 event를 다시 성공 처리하지 않는다.

---

## Generic Webhook Transport의 HTTP Delivery 및 실패 정책

### 해결하려는 문제

Collector queue alert를 실제 운영자에게 전달하려면 local-file test transport를 넘어 외부 HTTP endpoint로 notification event를 전달할 transport가 필요하다.

Webhook transport에서는 단순히 HTTP POST 기능만 구현하면 충분하지 않다.

다음 실패를 명확하게 구분해야 한다.

```text
정상 2xx
HTTP 4xx
HTTP 5xx
rate limit
redirect
connection failure
timeout
```

특히 HTTP timeout에서는 receiver가 요청을 이미 처리했지만 AeroTrace가 응답만 받지 못했을 수 있으므로 delivery 결과가 모호해진다.

### 선택한 Transport

Notification adapter에 다음 transport를 지원한다.

```text
local-file
webhook
```

기본값은 기존 호환성을 위해:

```text
local-file
```

로 유지한다.

Webhook transport는:

```text
--transport webhook
```

으로 명시적으로 활성화한다.

Webhook URL은 다음 두 경로에서 읽을 수 있다.

```text
--webhook-url
AEROTRACE_WEBHOOK_URL
```

CLI argument는 개발 및 수동 테스트 용도로 사용하고 운영 배포에서는 secret 노출을 줄이기 위해 환경변수 또는 보호된 환경 파일 사용을 우선한다.

### Webhook URL 검증

다음 scheme만 허용한다.

```text
http
https
```

다음 형태는 거부한다.

```text
ftp://...
URL에 host 없음
https://user:password@example.com/...
fragment 포함 URL
```

URL userinfo credential을 허용하지 않는 이유는 command line, process metadata, log 등에 인증 정보가 노출되는 위험을 줄이기 위해서다.

### HTTP Request Contract

Webhook notification은 다음 형식으로 전송한다.

```text
Method:
POST

Content-Type:
application/json

Accept:
application/json

User-Agent:
AeroTrace-Notification/1

X-AeroTrace-Event-Id:
<event_id>
```

Request body는 evaluator가 생성한 schema version 1 event JSON이다.

`X-AeroTrace-Event-Id`와 request body의 `event_id`는 동일하다.

Receiver가 idempotency 또는 duplicate suppression을 지원한다면 이 event ID를 사용할 수 있다.

단, header 자체가 receiver 측 deduplication을 보장하지는 않는다.

### 성공 기준

HTTP status:

```text
200 <= status < 300
```

을 성공으로 판단한다.

성공한 경우:

```text
HTTP success
→ webhook delivery receipt 저장
→ receipt fsync
→ receipt directory fsync
→ pending event 삭제
→ outbox directory fsync
```

순서로 처리한다.

Webhook receipt:

```json
{
  "receipt_schema_version": 1,
  "event_id": "...",
  "transport": "webhook",
  "delivered_at": "...",
  "event": {
    "schema_version": 1,
    "event_id": "...",
    "evaluated_at": "..."
  }
}
```

를 생성한다.

### 실제 2xx 검증

Local fake HTTP server가 204를 반환하도록 구성했다.

결과:

```text
webhook_204_rc=0

delivery_result=DELIVERED
adapter_status=OK
processed_events=1
remaining_events=0

pending_after_webhook=0
webhook_receipt_count=1
```

실제 request contract:

```text
POST /aerotrace
Content-Type=application/json
Accept=application/json
User-Agent=AeroTrace-Notification/1

X-AeroTrace-Event-Id
= request body event_id
```

검증:

```text
webhook_request_contract=PASS
webhook_receipt_contract=PASS
```

Receipt:

```text
transport=webhook
evaluated_at=2026-08-18T07:51:54+00:00
delivered_at=2026-08-18T07:52:02+00:00
```

으로 evaluator event 발생 시각과 webhook 성공 시각이 분리됨을 확인했다.

### HTTP Failure Classification

Webhook 실패를 다음 두 종류로 분리한다.

#### Retryable Failure

다음은 일시적 장애 가능성이 있으므로 retryable로 취급한다.

```text
HTTP 408
HTTP 429
HTTP 5xx
connection failure
timeout
```

Adapter exit code:

```text
2
```

정책:

```text
receipt 생성하지 않음
pending 삭제하지 않음
현재 event에서 처리 중단
```

#### Permanent Failure

다음은 URL, 인증, 요청 형식 등 운영 설정 문제일 가능성이 높으므로 permanent failure로 분류한다.

```text
3xx
408/429를 제외한 4xx
```

Adapter exit code:

```text
5
```

하지만 permanent라고 해서 pending event를 삭제하지 않는다.

이유는 최초 ALERT가 permanent failure로 전달되지 않았는데 뒤에 있는 RECOVERY만 전달되는 순서 역전을 방지하기 위해서다.

현재 adapter는 첫 실패 event에서 처리를 중단해 outbox order를 보존한다.

### HTTP 400 검증

Fake server가 HTTP 400을 반환했다.

결과:

```text
adapter_error=permanent delivery failure:
webhook returned permanent HTTP 400

http_400_rc=5
http_400_pending=1
http_400_receipts=0
```

따라서 permanent failure에서도 notification event를 유실하지 않는다.

### HTTP 408 검증

Fake server가 HTTP 408을 반환했다.

결과:

```text
adapter_error=retryable delivery failure:
webhook returned retryable HTTP 408

http_408_rc=2
http_408_pending_after=1
http_408_receipts=0
```

408은 retryable로 분류됐다.

### HTTP 429 검증

결과:

```text
adapter_error=retryable delivery failure:
webhook returned retryable HTTP 429

http_429_rc=2
http_429_pending=1
```

Rate limit은 pending event를 유지하고 재시도 대상으로 남긴다.

현재 Retry-After 기반 scheduling은 아직 구현하지 않았다.

### HTTP 500 검증

결과:

```text
adapter_error=retryable delivery failure:
webhook returned retryable HTTP 500

http_500_rc=2
http_500_pending=1
```

서버 장애도 retryable로 분류한다.

### Redirect 정책

Webhook URL의 redirect는 자동으로 따라가지 않는다.

의도:

```text
configured webhook endpoint
→ 302
→ 예상하지 않은 다른 endpoint
```

로 payload가 전달되는 것을 막는다.

실제 HTTP 302 테스트:

```text
redirect_rc=5
redirect_requests_added=1
redirect_target_requests_added=0
redirect_pending=1
```

으로 redirect target에 실제 POST가 발생하지 않았음을 확인했다.

### Connection Failure

Connection refused를 실제 발생시켰다.

결과:

```text
adapter_error=retryable delivery failure:
webhook request failed: [Errno 111] Connection refused

connection_refused_rc=2
connection_refused_pending=1
```

따라서 endpoint가 일시적으로 내려간 경우 event를 유실하지 않는다.

### Timeout과 Ambiguous Delivery

Timeout은 HTTP webhook에서 가장 중요한 failure mode다.

Receiver는 request body를 읽고 event를 기록한 뒤 응답을 1초 지연하도록 구성했다.

AeroTrace timeout:

```text
0.2초
```

결과:

```text
timeout_rc=2
timeout_server_received=1
timeout_pending=1
timeout_receipts=0
```

Receiver는 요청을 실제로 받았지만 AeroTrace는 성공 응답을 받지 못했다.

따라서 AeroTrace는 성공 receipt를 생성하지 않고 pending event를 유지했다.

같은 pending event를 다시 처리했다.

결과:

```text
timeout_retry_rc=2
timeout_second_request_received=1
```

Receiver는 동일 notification을 두 번째로 받았다.

두 request의 identity:

```text
X-AeroTrace-Event-Id
= body.event_id

first body.event_id
= second body.event_id
```

검증:

```text
timeout_duplicate_identity=PASS
```

### Delivery Semantics 결정

Webhook transport는 exactly-once delivery를 보장한다고 표현하지 않는다.

현재 모델은:

```text
성공 응답 확인
→ receipt 생성
→ pending ACK

성공 여부 불명확
→ receipt 없음
→ pending 유지
→ 동일 event_id로 retry
```

이다.

따라서 timeout과 같은 ambiguous failure에서는 receiver가 동일 event를 여러 번 받을 수 있다.

Receiver가 duplicate-safe 동작을 필요로 한다면:

```text
event_id
```

기반 idempotency 또는 deduplication을 구현해야 한다.

### Response 제한

Webhook response body는 진단 목적으로 최대:

```text
4096 bytes
```

까지만 소비한다.

Notification adapter가 외부 endpoint의 비정상적으로 큰 response body 때문에 불필요하게 메모리를 사용하는 것을 제한하기 위한 방어다.

### 현재 남은 과제

아직 다음은 구현 또는 검증하지 않았다.

```text
Retry-After 기반 429 retry scheduling
exponential backoff
retry budget
dead-letter 정책
outbox 최대 크기 및 최대 age monitoring
webhook authentication header
secret rotation
TLS certificate failure 테스트
실제 systemd webhook deployment
실제 외부 provider E2E
```

현재 MVP에서는 HTTP 실패 시 pending event를 보존하고 systemd의 반복 실행을 통해 retry하는 단순 구조를 유지한다.

---

## Notification Outbox 적체 관측 기준과 Threshold 정책

### 해결하려는 문제

Webhook endpoint가 장기간 실패하면 notification event는 Outbox에 보존되지만, 단순히 파일이 존재한다는 사실만으로는 운영자가 장애의 심각도를 판단하기 어렵다.

다음 정보를 최소 관측 단위로 사용하기로 했다.

- pending event 수
- pending JSON 전체 크기
- 가장 오래된 pending event의 age
- 가장 오래된 event ID
- 가장 오래된 event의 `evaluated_at`

### Pending Age 기준

파일의 mtime이 아니라 notification event 내부의 `evaluated_at`을 age 계산 기준으로 사용한다.

이유는 파일 이동, 복원 또는 파일시스템 변경으로 mtime이 달라져도 실제 alert가 발생한 시점 자체는 변하지 않기 때문이다.

따라서 다음과 같이 계산한다.

```text
oldest_pending_age
= 현재 시각 - event.evaluated_at
```

### 상태 코드

Notification Outbox checker의 상태와 exit code는 다음과 같다.

```text
OK       = 0
WARNING  = 1
CRITICAL = 2
UNKNOWN  = 3
```

손상된 JSON, 잘못된 outbox path, 잘못된 threshold 설정은 `UNKNOWN`으로 처리한다.

### Threshold 정책

다음 threshold를 CLI로 선택적으로 주입할 수 있도록 했다.

```text
--warn-count
--critical-count
--warn-age-sec
--critical-age-sec
```

count와 age는 독립적으로 평가하며 둘 중 더 높은 severity를 전체 status로 사용한다.

예:

```text
count = WARNING
age   = CRITICAL

overall = CRITICAL
```

### 기본값 결정

현재 production 기본 WARNING/CRITICAL threshold는 지정하지 않는다.

Threshold를 전달하지 않은 경우 pending event가 존재하더라도 관측값만 출력하고 상태는 `OK`로 유지한다.

```text
pending event 존재
threshold 미설정
→ status=OK
```

이는 테스트를 위해 임의로 사용한 숫자를 운영 정책으로 고정하지 않기 위한 결정이다.

실제 운영 failure duration, notification 발생 빈도, 허용 가능한 전달 지연 시간을 추가로 측정한 뒤 production threshold를 결정한다.

### Empty Outbox 정책

Outbox directory가 존재하지 않는 경우 pending event가 없는 정상 상태로 취급한다.

```text
status=OK
pending_events=0
pending_bytes=0
oldest_pending_age_sec=N/A
oldest_event_id=N/A
oldest_evaluated_at=N/A
```

Notification adapter가 outbox directory를 필요할 때 생성하는 구조이므로 directory 자체가 없다는 이유만으로 장애 상태로 판단하지 않는다.

### 손상된 Pending Event 정책

Outbox에 JSON 파일이 존재하지만 파싱할 수 없거나 필요한 event metadata가 잘못된 경우 정상 상태로 처리하지 않는다.

다음과 같이 처리한다.

```text
status=UNKNOWN
exit code=3
```

이유는 notification queue 자체의 데이터 손상을 운영자가 인지할 수 있어야 하기 때문이다.

### 실제 장애 기반 상태 전이 검증

Synthetic JSON의 timestamp를 조작하는 방식뿐 아니라 실제 notification delivery 실패를 발생시켜 검증했다.

Webhook endpoint를 다음과 같이 설정해 connection refused를 발생시켰다.

```text
http://127.0.0.1:1/aerotrace
```

결과:

```text
delivery_failure_rc=2
pending_after_failure=1
receipts_after_failure=0
```

동일 pending event의 실제 age가 시간에 따라 증가했다.

```text
event_id=1787182663140772001-653546

5.853s
→ OK
→ rc=0

17.889s
→ WARNING
→ rc=1

31.927s
→ CRITICAL
→ rc=2
```

검증:

```text
actual_pending_age_transition=PASS
pending_event_identity_preserved=PASS
```

최종 상태:

```text
final_pending=1
final_receipts=0
```

따라서 실제 전달 장애가 지속되는 동안 동일 미전송 event를 유지하면서 age 기반 상태가 단계적으로 악화되는 것을 확인했다.

### 테스트 Threshold와 Production Threshold 분리

상태 전이 검증 과정에서 사용한 다음 값들은 production 정책이 아니다.

```text
count 1 / 2 / 3
age 10초 / 25초 / 60초 / 90초
```

테스트 목적은 threshold 동작과 severity 전이를 검증하는 것이다.

실제 production threshold는 다음 근거를 수집한 뒤 결정한다.

- notification 정상 발생 빈도
- 실제 webhook 장애 지속 시간
- 운영자가 허용할 수 있는 notification 지연
- systemd retry 주기
- 장기 장애 시 outbox 증가 속도
- 실제 notification event 평균 크기

### 현재 남은 과제

현재 Outbox checker는 적체 상태를 관측할 수 있지만 notification adapter 자체의 연속 실패 상태는 아직 영속적으로 기록하지 않는다.

다음 단계에서는 다음 값을 별도 persistent failure state로 관리하는 것을 검토한다.

```text
first_failed_at
last_failed_at
failure_count
failure_kind
failed_event_id
```

이를 통해 단순 oldest pending age와 별도로 실제 transport failure duration을 측정할 수 있게 한다.

---

## Notification Webhook Persistent Failure State 정책

### 해결하려는 문제

Notification Outbox의 `oldest_pending_age`는 가장 오래된 미전송 event가 얼마나 오래됐는지는 보여주지만, 실제 transport 장애가 언제 시작됐고 몇 번 연속 실패했는지는 직접 알려주지 않는다.

예를 들어:

```text
10:00 notification event 생성
10:02 첫 webhook 전송 실패
10:03 두 번째 webhook 전송 실패
```

이라면:

```text
oldest pending age
≈ 3분

실제 transport failure duration
≈ 1분
```

으로 서로 다르다.

따라서 webhook transport 자체의 현재 연속 실패 상태를 별도의 persistent state로 관리하기로 했다.

### Failure State Schema

현재 schema version:

```text
failure_state_schema_version=1
```

저장하는 값:

```text
transport
failed_event_id
failure_kind
failure_reason
first_failed_at
last_failed_at
failure_count
```

예:

```json
{
  "failure_state_schema_version": 1,
  "transport": "webhook",
  "failed_event_id": "1787185530153348081-718365",
  "failure_kind": "retryable",
  "failure_reason": "connection_error",
  "first_failed_at": "2026-08-20T00:25:34+00:00",
  "last_failed_at": "2026-08-20T00:25:49+00:00",
  "failure_count": 2
}
```

### 적용 범위

Persistent failure state는 현재:

```text
webhook transport
```

에만 적용한다.

`local-file` transport에는 적용하지 않는다.

따라서:

```text
--failure-state-file
```

옵션은:

```text
--transport webhook
```

과 함께 사용할 때만 허용한다.

`local-file`과 함께 사용하면 configuration error로 처리한다.

실제 검증:

```text
adapter_error=--failure-state-file requires --transport webhook
local_failure_state_rc=4
```

### Optional 정책

`--failure-state-file`은 optional이다.

지정하지 않으면 기존 webhook adapter 동작을 그대로 유지한다.

이는 기존 systemd/local 테스트와의 backward compatibility를 유지하기 위한 결정이다.

### Failure Kind

Failure는 현재 두 종류로 구분한다.

```text
retryable
permanent
```

예:

```text
connection refused
timeout
HTTP 408
HTTP 429
HTTP 5xx
→ retryable
```

```text
HTTP 3xx
HTTP 400 등 기타 4xx
→ permanent
```

### Structured Failure Reason

Persistent state 작성자가 exception message 문자열을 파싱하지 않도록 failure reason을 구조화했다.

예:

```text
connection_error
timeout
http_400
http_408
http_429
http_500
```

다음과 같은 방식에는 의존하지 않는다.

```text
"HTTP 429" in str(exception)
```

이유는 exception message 변경이 persistent state의 의미까지 깨뜨리는 구조를 피하기 위해서다.

### Sensitive Information 저장 금지

Failure state에는 다음을 저장하지 않는다.

```text
webhook URL
URL query
URL path token
credential
raw request body
raw response body
```

Webhook URL에 secret token이 포함될 가능성이 있기 때문이다.

### 연속 실패 Counting 정책

첫 실패:

```text
failure_count=1
first_failed_at=T1
last_failed_at=T1
```

두 번째 연속 실패:

```text
failure_count=2
first_failed_at=T1 유지
last_failed_at=T2
```

세 번째 실패:

```text
failure_count=3
first_failed_at=T1 유지
last_failed_at=T3
```

즉 `first_failed_at`은 현재 연속 장애의 시작 시각이고 `last_failed_at`은 최근 실패 시각이다.

### 실제 Retryable Failure 검증

Webhook endpoint를 사용하지 않는 localhost port로 설정했다.

```text
http://127.0.0.1:1/aerotrace
```

첫 실패:

```text
adapter_error=retryable delivery failure:
webhook request failed: [Errno 111] Connection refused

failure_count=1
failure_kind=retryable
failure_reason=connection_error
failure1_rc=2
```

State:

```text
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

따라서 최초 실패 시각 유지와 최근 실패 시각 갱신을 실제로 확인했다.

### Recovery 시 Failure State 정책

Webhook delivery가 성공하면 현재 연속 장애는 종료된 것으로 판단한다.

따라서 성공 시 failure state를 삭제한다.

실제 HTTP 204 recovery 결과:

```text
failure_state_cleared=true
delivery_result=DELIVERED
adapter_status=OK
remaining_events=0
recovery_rc=0
```

최종:

```text
failure_state_removed=PASS
pending_after_recovery=0
receipts_after_recovery=1
```

### 성공 Finalization 순서

초기 구현에서는 다음 순서였다.

```text
HTTP 2xx
→ receipt 저장
→ pending 삭제
→ main() 복귀
→ failure state 삭제
```

이 구조에서는 failure state 삭제만 실패하면:

```text
receipt 있음
pending 없음
stale failure state 있음
```

이 되어 다음 adapter 실행에서 stale state를 자동 복구할 기회가 사라진다.

따라서 finalization 순서를 다음과 같이 변경했다.

```text
HTTP 2xx
→ receipt durable 저장
→ failure state clear
→ pending ACK
```

Failure state clear가 실패하면:

```text
receipt 있음
failure state 있음
pending 있음
```

상태를 유지한다.

이를 통해 다음 실행에서 receipt를 근거로 외부 HTTP 요청을 다시 보내지 않고 복구할 수 있다.

### Failure State Clear Failure 실험

기존 retryable failure state와 pending event를 만든 뒤 state directory write 권한을 제거했다.

```text
chmod 500 <state-directory>
```

Webhook server는 HTTP 204를 정상 반환했다.

그 뒤 failure state unlink가 실패했다.

결과:

```text
adapter_error=failure state clear failed before pending ACK:
[Errno 13] Permission denied

clear_failure_rc=4
```

중요한 파일 상태:

```text
pending_after_clear_failure=1
receipts_after_clear_failure=1
failure_state_after_clear_failure=1
```

즉 외부 delivery 성공에 대한 receipt는 남았지만 pending ACK는 수행하지 않았다.

### ACK_EXISTING Recovery 정책

위 상태에서 HTTP server를 완전히 종료한 뒤 adapter를 다시 실행했다.

서버가 내려가 있으므로 network POST를 다시 실행했다면 connection refused가 발생해야 했다.

하지만 기존 receipt를 발견해 HTTP 요청 없이 다음 경로로 복구했다.

```text
failure_state_cleared=true
delivery_result=ACK_EXISTING
adapter_status=OK
remaining_events=0
ack_existing_rc=0
```

최종 상태:

```text
final_pending=0
final_receipts=1
final_failure_state=0
```

따라서 외부 delivery 성공 후 내부 bookkeeping 실패가 발생해도 동일 HTTP notification을 불필요하게 재전송하지 않고 복구할 수 있음을 확인했다.

### Permanent Failure State

HTTP 400을 실제 발생시켜 permanent failure state를 검증했다.

결과:

```text
adapter_error=permanent delivery failure:
webhook returned permanent HTTP 400

failure_count=1
failure_kind=permanent
failure_reason=http_400
remaining_events=1
http_400_failure_rc=5
```

Persistent state:

```text
failure_state_schema_version=1
transport=webhook
failure_kind=permanent
failure_reason=http_400
failure_count=1
```

검증:

```text
permanent_failure_state=PASS
```

Permanent failure에서도:

```text
pending=1
receipt=0
```

을 유지한다.

이는 최초 ALERT가 전달되지 않았는데 뒤의 RECOVERY만 전달되는 순서 역전을 방지하기 위한 기존 outbox ordering 정책과 동일하다.

### 손상된 Failure State 정책

Failure state JSON이 손상된 경우 기존 파일을 덮어쓰거나 네트워크 delivery를 먼저 실행하지 않는다.

테스트 state:

```text
{broken json
```

실행 결과:

```text
adapter_error=invalid failure state:
Expecting property name enclosed in double quotes...

corrupt_failure_state_rc=4
```

중요한 점은 state validation이 network delivery보다 먼저 수행된다는 것이다.

따라서 이 테스트에서는 connection-refused URL을 사용했음에도 network failure가 발생하지 않았다.

### 손상 State 보존 검증

실행 전 SHA-256:

```text
c3e7d1b00a65589b59f816c0b0b668d795a3c28123697d5ab9555bdb8aa04604
```

실행 후 SHA-256:

```text
c3e7d1b00a65589b59f816c0b0b668d795a3c28123697d5ab9555bdb8aa04604
```

검증:

```text
corrupt_state_preserved=PASS
```

최종 상태:

```text
corrupt_pending_after=1
corrupt_receipts_after=0
```

따라서 운영 상태 파일이 손상됐을 때 이를 조용히 초기화해 장애 정보를 잃는 대신 명시적인 configuration/state error로 노출한다.

### Persistence 방식

Failure state 작성은 다음 순서로 수행한다.

```text
temporary file 생성
→ JSON write
→ file flush
→ file fsync
→ os.replace
→ parent directory fsync
```

성공적인 state 삭제 후에도 parent directory를 fsync한다.

### 현재 Delivery 보장 수준

현재 notification 구조는 다음 특성을 가진다.

```text
Outbox event 보존
+
delivery receipt
+
persistent transport failure state
+
ACK_EXISTING recovery
```

하지만 외부 HTTP receiver와 로컬 filesystem 사이에 분산 transaction이 존재하지 않으므로 exactly-once delivery를 보장하지는 않는다.

특히 receiver가 request를 처리했지만 HTTP response가 timeout된 경우에는 동일 event가 재전송될 수 있다.

따라서 현재 의미는 여전히:

```text
at-least-once 성격
+
event_id 기반 receiver-side deduplication 가능
```

이다.

### 현재 남은 과제

아직 다음은 구현하지 않았다.

```text
failure state 자체를 조회하는 별도 checker
failure duration threshold
failure count threshold
Retry-After
exponential backoff
retry budget
dead-letter 정책
failure history archive
systemd production failure-state 경로 적용
```

다음 단계에서는 persistent failure state를 실제 운영자가 읽을 수 있도록 checker 또는 기존 Outbox checker와 연결하는 것을 검토한다.

---

## Notification Transport Failure 관측 지표와 Severity 기준

### 해결하려는 문제

Webhook transport의 persistent failure state를 저장할 수 있게 되었지만, 저장된 여러 값이 각각 어떤 운영 의미를 가지는지와 어떤 값을 실제 경보 severity의 주 기준으로 사용할지 결정해야 했다.

현재 관측 가능한 주요 값은 다음과 같다.

```text
failure_kind
failure_count
failure_duration_sec
last_failure_age_sec
oldest_pending_age_sec
pending_events
```

이 값들은 서로 비슷해 보이지만 실제로 답하는 운영 질문이 다르므로 하나의 의미로 합치지 않는다.

### Persistent Failure State Checker

다음 checker를 추가했다.

```text
scripts/check-notification-failure-state.py
```

Checker는 persistent webhook failure state를 읽어 다음 값을 출력한다.

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

Exit code:

```text
OK       = 0
WARNING  = 1
CRITICAL = 2
UNKNOWN  = 3
```

### Failure State가 없는 경우

Failure state 파일이 없으면 현재 연속 webhook transport 장애가 없는 것으로 해석한다.

```text
status=OK
active_failure=false
failure_count=0
failure_duration_sec=N/A
last_failure_age_sec=N/A
```

실제 검증:

```text
missing_failure_state_rc=0
```

### 손상된 Failure State

Failure state JSON이 손상됐거나 path가 일반 파일이 아닌 경우 정상 상태로 숨기지 않는다.

```text
status=UNKNOWN
exit code=3
```

실제 검증:

```text
corrupt_failure_checker_rc=3
failure_state_path_rc=3
```

### Retryable Failure 기본 정책

Retryable failure가 존재한다는 사실만으로 즉시 WARNING 또는 CRITICAL을 발생시키지 않는다.

Threshold가 설정되지 않은 경우:

```text
active_failure=true
failure_kind=retryable
status=OK
```

으로 관측값만 제공한다.

실제 검증 당시:

```text
failure_kind=retryable
failure_reason=connection_error
failure_count=2
failure_duration_sec=201.062
last_failure_age_sec=185.062
status=OK
default_retryable_rc=0
```

이었다.

이는 테스트 값을 production alert 정책으로 암묵적으로 고정하지 않기 위한 결정이다.

### Retryable Threshold

Retryable failure에는 선택적으로 다음 threshold를 설정할 수 있다.

```text
--warn-count
--critical-count
--warn-duration-sec
--critical-duration-sec
```

Count와 duration은 독립적으로 평가하며 더 높은 severity를 전체 status로 사용한다.

테스트 결과:

```text
failure_count=2
warn-count=2
critical-count=3
→ WARNING / rc=1
```

```text
failure_count=2
warn-count=1
critical-count=2
→ CRITICAL / rc=2
```

Duration도 동일하게:

```text
duration threshold 도달
→ WARNING / rc=1
```

```text
critical duration threshold 도달
→ CRITICAL / rc=2
```

로 동작함을 확인했다.

### Permanent Failure 정책

Adapter가 이미 `permanent`로 분류한 transport failure는 duration이나 count threshold를 기다리지 않고 즉시 CRITICAL로 평가한다.

실제 checker fixture:

```text
failure_kind=permanent
failure_reason=http_400
failure_count=1
failure_duration_sec=9.958
```

결과:

```text
failure_kind_status=CRITICAL
status=CRITICAL
permanent_failure_checker_rc=2
```

이는 permanent failure가 단순한 일시적 network hiccup과 달리 동일 요청을 반복하는 것만으로 해결될 가능성이 낮고, Outbox의 다음 event까지 막을 수 있기 때문이다.

### Threshold Configuration Error

잘못된 checker 설정은 failure state 존재 여부보다 먼저 검증한다.

예:

```text
warn-count >= critical-count
warn-duration-sec >= critical-duration-sec
threshold <= 0
```

결과:

```text
status=UNKNOWN
exit code=3
```

Failure state가 존재하지 않아도 설정 자체가 잘못된 경우:

```text
status=UNKNOWN
checker_error=--warn-count must be lower than --critical-count
bad_config_without_state_rc=3
```

으로 처리했다.

Checker configuration 오류를 `state 없음 → OK` 경로로 숨기지 않기 위한 결정이다.

### 실제 반복 장애 측정

Synthetic state만으로 판단하지 않고 실제 connection refused를 연속 4회 발생시켰다.

동일 event:

```text
event_id=1787210007147775125-1271557
```

에 대해 다음 값을 동시에 측정했다.

```text
attempt 1
failure_count=1
failure_duration_sec=0.136
last_failure_age_sec=0.136
oldest_pending_age_sec=5.168
pending_events=1

attempt 2
failure_count=2
failure_duration_sec=4.285
last_failure_age_sec=0.285
oldest_pending_age_sec=9.318
pending_events=1

attempt 3
failure_count=3
failure_duration_sec=8.434
last_failure_age_sec=0.434
oldest_pending_age_sec=13.467
pending_events=1

attempt 4
failure_count=4
failure_duration_sec=12.586
last_failure_age_sec=0.586
oldest_pending_age_sec=17.618
pending_events=1
```

검증:

```text
repeated_failure_measurement=PASS
```

### Oldest Pending Age와 Failure Duration의 의미 분리

실험에서 다음 차이가 거의 일정하게 유지됐다.

```text
oldest_pending_age_sec
-
failure_duration_sec
=
약 5.032 ~ 5.033초
```

측정:

```text
backlog_minus_failure_min_sec=5.032
backlog_minus_failure_max_sec=5.033
```

이 차이는 notification event가 생성된 시각과 실제 첫 webhook transport failure가 발생한 시각 사이의 시간이다.

따라서 두 값은 같은 의미가 아니다.

```text
oldest_pending_age_sec
→ notification 자체가 사용자에게 얼마나 오래 전달되지 않았는가

failure_duration_sec
→ webhook transport가 실제로 얼마나 오래 연속 실패하고 있는가
```

### Failure Count의 의미

`failure_count`는 연속 실패 횟수를 보여주지만 retry cadence에 직접 의존한다.

예를 들어 동일한 60초 장애라도:

```text
5초마다 retry
→ 약 12회

30초마다 retry
→ 약 2회
```

가 될 수 있다.

따라서 retry 주기가 변경되면 같은 장애에서도 count가 크게 달라진다.

결론:

```text
failure_count
→ 진단 및 보조 threshold 지표
```

로 사용하고 retryable severity의 주 시간 기준으로는 사용하지 않는다.

### Failure Duration의 의미

`failure_duration_sec`는 최초 실제 transport failure부터 현재까지의 경과시간이다.

Retry cadence가 달라져도 기본 의미가 유지된다.

따라서 현재 설계 결정은 다음과 같다.

```text
Retryable failure severity의 주 기준
→ failure_duration_sec
```

### Last Failure Age의 의미

반복 장애 실험에서 각 retry 직후 측정한 값:

```text
0.136
0.285
0.434
0.586
```

이었다.

Retry를 중단하고 이후 확인했을 때는:

```text
last_failure_age_sec=18.185
```

까지 증가했다.

따라서 이 값은 장애 severity 자체보다:

```text
마지막 delivery attempt가 얼마나 최근에 실행됐는가
```

를 나타내는 지표로 해석하는 것이 더 적절하다.

향후 실제 systemd retry cadence가 결정되면:

```text
last_failure_age_sec가 예상 retry 간격보다 비정상적으로 큼
→ notification retry worker 또는 timer liveness 문제 가능성
```

을 감지하는 보조 지표로 사용할 수 있다.

### 현재 지표별 역할

현재 결정은 다음과 같다.

```text
failure_kind
→ retryable / permanent 구분

permanent
→ 즉시 CRITICAL

failure_duration_sec
→ retryable 장애 severity의 주 기준

failure_count
→ 연속 실패 횟수와 진단용 보조 지표

last_failure_age_sec
→ 최근 retry 실행 여부 / liveness 후보

oldest_pending_age_sec
→ notification backlog와 사용자 영향

pending_events
→ backlog 크기
```

### Production Threshold 정책

아직 production WARNING/CRITICAL 숫자는 정하지 않는다.

이번 테스트에서 사용한 count나 duration threshold는 기능 검증을 위한 값일 뿐 운영 SLA가 아니다.

실제 production 기준을 결정하기 전에 최소한 다음 조건을 확정하거나 측정한다.

```text
실제 notification retry cadence
운영자가 허용 가능한 notification 지연
일시적인 network hiccup을 허용할 시간
systemd timer 실행 주기
외부 webhook provider의 장애 특성
실제 사용자 또는 사내 PoC의 alert 대응 요구
```

따라서 현재 코드에는 threshold 설정 기능만 제공하고 production 기본 숫자는 두지 않는다.

### 향후 재검토

다음 시점에 threshold 정책을 다시 검토한다.

```text
systemd production notification 실행 주기 확정
실제 외부 webhook provider 연동
Oracle Cloud 또는 홈서버 환경에서 장시간 장애 실험
사내 PoC 운영 요구 확보
실사용자 notification 지연 허용 수준 확인
```

---

## Notification Outbox production systemd 실행 경로

### 해결하려는 문제

Notification Outbox, delivery receipt, webhook adapter는 개별 테스트에서는 검증됐지만 실제 production systemd evaluator와 연결되어 있지 않았다.

기존 production 구성은 다음과 같았다.

```text
Collector queue checker
→ 5초 evaluator
→ journal ALERT / RECOVERY
```

Evaluator에는:

```text
--event-outbox-dir
```

가 없었고 notification adapter를 실행하는 production systemd unit도 존재하지 않았다.

따라서 queue 상태 변화는 감지할 수 있었지만 실제 durable notification pipeline으로 이어지지는 않았다.

### 선택한 구조

기존:

```text
StateDirectory=aerotrace-monitoring
```

경계를 유지하고 다음 경로를 사용한다.

```text
/var/lib/aerotrace-monitoring/
    collector-queue-alert.json
    notification-outbox/
    notification-receipts/
```

별도 StateDirectory를 추가하지 않고 현재 monitoring 운영 상태를 하나의 책임 경계 안에서 관리한다.

### Evaluator Outbox 연결

Production evaluator에 다음 경로를 연결했다.

```text
--event-outbox-dir
/var/lib/aerotrace-monitoring/notification-outbox
```

따라서 alert-required event는 journal 출력뿐 아니라 durable Outbox event로 생성된다.

### Notification Processor

다음 systemd unit을 추가했다.

```text
aerotrace-notification-outbox.service
aerotrace-notification-outbox.timer
```

Timer 주기:

```text
OnUnitInactiveSec=5s
```

Service는 oneshot으로 실행되며 현재 검증 단계에서는:

```text
--transport local-file
```

을 사용한다.

Receipt:

```text
/var/lib/aerotrace-monitoring/notification-receipts
```

에 저장한다.

### Local-file을 먼저 사용한 이유

Webhook을 바로 production unit에 연결하면 다음 문제들이 동시에 테스트된다.

```text
systemd configuration
Outbox 생성
adapter 실행
network
Webhook endpoint
credential
failure-state
```

장애 원인을 분리하기 어렵기 때문에 먼저 외부 network를 제거한 local-file transport로:

```text
Evaluator
→ Outbox
→ Timer
→ Adapter
→ Receipt
→ ACK
```

경로 자체를 검증했다.

Local-file은 최종 외부 notification transport가 아니라 production systemd wiring을 검증하기 위한 기준선이다.

### 실제 ALERT End-to-End 검증

실제 OpenTelemetry Collector를 중지했다.

Evaluator:

```text
2026-08-20 16:41:43 KST
event=ALERT
previous_status=OK
current_status=UNKNOWN
checker_exit_code=3
```

Notification processor:

```text
2026-08-20 16:41:49 KST
delivery_result=DELIVERED
event=ALERT
adapter_status=OK
remaining_events=0
```

Receipt:

```text
event_id=1787211703960728995-1310439
transport=local-file
```

검증:

```text
systemd_alert_receipt=PASS
```

이번 실행에서 evaluator event 생성부터 receipt delivery까지 약 6초가 걸렸다.

### 실제 RECOVERY End-to-End 검증

Collector를 다시 시작했다.

Evaluator:

```text
2026-08-20 16:42:13 KST
event=RECOVERY
previous_status=UNKNOWN
current_status=OK
checker_exit_code=0
```

Notification processor:

```text
2026-08-20 16:42:19 KST
delivery_result=DELIVERED
event=RECOVERY
adapter_status=OK
remaining_events=0
```

Receipt:

```text
event_id=1787211733960954909-1311457
transport=local-file
```

검증:

```text
systemd_recovery_receipt=PASS
```

이번 실행에서도 evaluator event 생성부터 receipt delivery까지 약 6초가 걸렸다.

### 최종 상태

테스트 종료 후:

```text
pending_events=0
production_receipts=2
```

두 timer 모두:

```text
aerotrace-collector-queue-alert.timer
aerotrace-notification-outbox.timer
```

이 `active (waiting)` 상태임을 확인했다.

### 현재 보장하지 않는 것

이 단계에서는 아직 다음을 production에 연결하지 않았다.

```text
실제 Webhook endpoint
Webhook credential
persistent webhook failure state
failure-state checker systemd 실행
production WARNING / CRITICAL threshold
별도 secondary alert channel
```

다음 단계에서 local-file 기준선을 유지한 채 transport 부분만 webhook으로 교체한다.

---

## Production systemd Webhook Transport 장애 복구 정책 검증

### 해결하려는 문제

Notification Outbox와 Webhook adapter의 장애 복구 semantics는 개별 script 테스트에서는 검증됐지만 실제 production systemd timer 환경에서도 동일하게 동작하는지 확인할 필요가 있었다.

검증 대상은 다음 전체 경로다.

```text
Collector 상태 변화
→ evaluator
→ durable Notification Outbox
→ systemd notification timer
→ Webhook
→ receipt

Webhook 장애
→ persistent failure state
→ pending 유지
→ systemd retry
→ transport 복구
→ 동일 event 재전송
→ receipt
→ failure state clear
→ pending ACK
```

### Webhook Configuration 분리

Webhook URL을 repository나 systemd unit에 직접 저장하지 않는다.

다음 runtime 환경파일을 사용하도록 unit을 구성했다.

```text
/etc/aerotrace/notification.env
```

Systemd:

```text
EnvironmentFile=/etc/aerotrace/notification.env
```

테스트 당시 파일 권한:

```text
mode=600
owner=root
group=root
```

Webhook URL 또는 credential이 Git repository에 포함되지 않도록 서버별 runtime configuration으로 분리한다.

### Network Sandbox

기존 local-file processor는:

```text
RestrictAddressFamilies=AF_UNIX
```

만 허용했다.

Webhook HTTP 통신을 위해:

```text
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
```

으로 확장했다.

`NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome=read-only` 등의 기존 sandbox는 유지했다.

### Production Failure State 경로

Webhook failure state는:

```text
/var/lib/aerotrace-monitoring/notification-failure.json
```

에 저장한다.

Notification Outbox와 receipt도 동일한 monitoring StateDirectory 책임 경계에 둔다.

```text
/var/lib/aerotrace-monitoring/
    collector-queue-alert.json
    notification-outbox/
    notification-receipts/
    notification-failure.json
```

### Webhook 성공 경로 검증

localhost HTTP 204 receiver를 실제 systemd Webhook endpoint로 연결했다.

실제 Collector를 중지하여 ALERT를 발생시킨 결과:

```text
2026-08-20 16:52:54 KST
event=ALERT
current_status=UNKNOWN
```

Webhook processor:

```text
2026-08-20 16:53:00 KST
delivery_result=DELIVERED
event=ALERT
remaining_events=0
```

검증:

```text
systemd_webhook_alert=PASS
```

Receiver request의:

```text
X-AeroTrace-Event-Id
HTTP body event_id
receipt event_id
```

가 모두 동일함을 확인했다.

Collector 복구 후 RECOVERY도 같은 경로로 검증했다.

```text
systemd_webhook_recovery=PASS
```

성공 테스트 최종:

```text
webhook receipts=2
receiver requests=2
pending_events=0
active_failure=false
```

### 실제 Webhook Transport 장애 주입

Webhook receiver를 완전히 종료한 상태에서 실제 Collector를 중지했다.

결과:

```text
production_failure_state_created=1

failure_kind=retryable
failure_reason=connection_error
failure_count=1

pending_events=1
receipt 증가 없음
```

Failure state와 pending Outbox의 event identity:

```text
1787212535955638444-1330962
```

가 동일했다.

검증:

```text
failure_pending_identity=PASS
```

### systemd 자동 Retry

Notification timer는 장애 중 동일 pending event를 반복 처리했다.

실제 journal:

```text
16:55:47 failure_count=2
16:55:53 failure_count=3
16:55:59 failure_count=4
16:56:05 failure_count=5
16:56:11 failure_count=6
```

12초 측정 구간에서도:

```text
failure_count 2 → 4
```

증가를 확인했다.

검증:

```text
systemd_webhook_retry=PASS
```

### 실제 Retry Cadence

Timer 설정은:

```text
OnUnitInactiveSec=5s
```

이지만 실제 실패 시도 간격은 약 6초였다.

이는 `OnUnitInactiveSec`가 이전 oneshot 실행이 끝난 이후부터 다음 실행 간격을 계산하기 때문이다.

따라서 향후 failure count 또는 liveness threshold를 계산할 때:

```text
설정값 5초
```

를 정확한 실제 retry cadence로 가정하지 않는다.

실제 systemd 실행시간을 포함한 측정값을 사용한다.

### Transport 자동 복구

Collector는 계속 DOWN 상태로 두고 Webhook receiver만 복구했다.

Adapter를 수동 실행하지 않았다.

Systemd timer가 다음 retry에서 기존 pending ALERT를 자동으로 전송했다.

결과:

```text
automatic_webhook_recovery=1
systemd_failure_recovery_identity=PASS
```

복구된 ALERT event:

```text
1787212535955638444-1330962
```

는 장애 중 persistent failure state와 Outbox에 있던 동일 event였다.

성공 후:

```text
pending_events=0
active_failure=false
failure_count=0
```

으로 복구됐다.

Webhook receipt도:

```text
2 → 3
```

으로 증가했다.

### Transport 장애 지속시간 실측

최초 persistent failure:

```text
first_failed_at=2026-08-20 16:55:41 KST
```

복구된 ALERT receiver 수신:

```text
2026-08-20 16:56:17.940 KST
```

이번 장애 실험에서는 transport failure가 약 37초 지속됐다.

### Collector 최종 복구

Transport가 먼저 정상화되고 기존 ALERT가 전달된 것을 확인한 뒤 Collector를 시작했다.

RECOVERY Webhook도 자동 전달됐다.

Receiver 최종 기록:

```text
ALERT
RECOVERY
ALERT (transport 장애 후 자동 복구)
RECOVERY
```

최종:

```text
pending_events=0
active_failure=false
collector timer=active/waiting
notification timer=active/waiting
```

### Delivery 보장 한계

이번 장애는 connection refused였기 때문에 receiver가 실패한 POST를 실제로 처리하지 않았고, 복구된 event가 receiver에 한 번만 도착했다.

그러나 이를 exactly-once 보장으로 해석하지 않는다.

다음과 같은 ambiguous delivery에서는 중복 가능성이 있다.

```text
receiver가 request 처리 완료
→ response 전달 전에 timeout
→ sender는 실패로 판단
→ 동일 event_id 재시도
```

따라서 현재 보장 수준은 계속:

```text
at-least-once 성격
+
event_id 기반 receiver-side deduplication 가능
```

이다.

### 실제 외부 Endpoint 도입 전 Runtime 정책

현재 테스트 endpoint는 일시적인 localhost receiver이므로 테스트 종료 후 서버 runtime은 검증된 local-file transport로 되돌린다.

Repository에는 Webhook-capable unit을 유지하되 실제 외부 Webhook URL이 결정되기 전까지 테스트용 localhost endpoint를 production configuration으로 남기지 않는다.