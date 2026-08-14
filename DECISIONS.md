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