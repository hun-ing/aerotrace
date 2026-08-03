# CAREER_LOG.md

> 마지막 업데이트: 2026-07-27

## 1. 현재까지 직접 확보한 실무 경험

* Spring Boot 4와 Java 21 프로젝트 초기 구성
* Virtual Threads 실제 요청 적용 검증
* TimescaleDB hypertable 설계와 Flyway migration
* 멀티테넌트 Tenant·Project 데이터 모델
* 복합 외래키를 이용한 데이터 소유 관계 보장
* Collector retry를 고려한 Span 중복 저장 방지
* OTLP/HTTP JSON Trace 수신
* OpenTelemetry AnyValue 파싱
* Resource와 Span Attributes JSONB 저장
* Span Event와 Link 구조 이해 및 저장
* OTLP `uint32`, timestamp, ID 형식 검증
* JDBC 단건 저장과 batch 저장 비교
* 재현 가능한 반복 성능 벤치마크 구성
* 성능 옵션을 무조건 적용하지 않고 측정 후 보류
* IntelliJ HTTP Client와 Database Tool을 이용한 개발 흐름
* 테스트 데이터 충돌과 셸 escaping 문제 해결

## 2. 포트폴리오 핵심 강조점

### 단순 구현이 아닌 근거 기반 설계

AeroTrace는 기술을 많이 사용하는 프로젝트가 아니라 다음 과정을 보여주는 프로젝트다.

```text
문제 정의
→ 대안 검토
→ 최소 구현
→ 직접 실행
→ 실패 원인 분석
→ 반복 검증
→ 성능 측정
→ 설계 결정 기록
```

### 멀티테넌트 데이터 정합성

* Tenant와 Project 복합 외래키
* 모든 Span에 Tenant와 Project 적용
* 잘못된 소유 관계를 DB에서 차단
* 향후 API Key에서 Tenant와 Project를 결정할 구조

### Collector retry와 중복 방지

* 유일 인덱스
* `ON CONFLICT DO NOTHING`
* 신규와 중복 건수 분리
* 동일 요청 재전송 검증
* 중복 저장으로 인한 집계 왜곡 방지

### 성능 측정

* 단건과 batch가 같은 SQL과 바인딩 코드를 사용
* 워밍업
* 반복 측정
* 중앙값 사용
* 실행 순서 교차
* 저장 행 수 검증
* 옵션이 효과가 없다는 결과도 보존

## 3. 이력서 성과 문장 초안

### JDBC batch 성능 개선

OpenTelemetry Span 저장 과정에서 Span별 JDBC 호출 비용을 검증하기 위해 동일 데이터·트랜잭션 조건의 반복 벤치마크를 구성하고 요청 단위 JDBC batch를 적용하여, 100~5,000 Span 구간에서 저장 처리량을 약 2.9~3.1배 개선

### 멀티테넌트 데이터 격리

멀티테넌트 APM의 데이터 소유 관계를 보장하기 위해 Tenant·Project 복합 외래키를 설계하고 모든 Span 저장에 Tenant와 Project 범위를 적용하여 잘못된 테넌트 조합이 DB에 저장되는 문제를 방지

### 중복 Span 방지

OpenTelemetry Collector 재전송 과정에서 발생할 수 있는 중복 Span 저장을 방지하기 위해 Tenant, Project, Trace ID, Span ID, 시작 시각 기반 유일 인덱스와 idempotent insert를 구성하고 동일 요청 재전송 시 데이터가 한 건으로 유지되는 것을 검증

### OTLP 수신 파이프라인

OTLP/HTTP JSON Trace 요청의 Resource, Scope, Span, Attributes, Events, Links를 파싱하고 형식·시간·식별자·uint32 범위를 검증한 뒤 TimescaleDB JSONB와 일반 컬럼에 저장하는 수집 파이프라인 구현

### 근거 기반 최적화

pgJDBC의 `reWriteBatchedInserts` 옵션을 Span 크기별로 반복 측정한 결과 일관된 개선 효과가 없음을 확인하고, 불필요한 설정을 도입하지 않는 결정을 기술 문서에 기록

## 4. 현재 이력서 문장에 넣으면 안 되는 표현

아직 다음 표현을 사용하지 않는다.

* 초당 수만 개 Span 처리
* 무중단 APM 구축
* 데이터 유실 0%
* 대규모 트래픽 처리
* 상용 서비스 운영
* 실사용자 확보
* 장애 예측 시스템 완성
* Collector부터 DB까지 3배 개선
* 네트워크 왕복을 1회로 감소
* N100에서 안정적 운영

현재 측정은 Windows 개발 PC와 Docker Desktop의 persistence-only 조건이다.

## 5. 면접에서 설명할 수 있는 사례

### 사례 1 — 중복 방지 테스트가 Attribute 저장을 막은 문제

상황:

* Attribute 요청은 `200`을 반환했지만 기대한 데이터가 조회되지 않았다.

원인:

* 기존 요청과 trace ID, span ID, start time이 같아 unique index가 중복으로 판단했다.

해결:

* 테스트 목적별로 고유한 식별자를 사용했다.
* 신규 저장과 중복 재전송 요청을 분리했다.
* 서버 로그와 DB 행을 함께 검증했다.

면접 포인트:

* 성공 응답만으로 저장 성공을 판단하지 않은 점
* Idempotency가 테스트에도 영향을 주는 것을 파악한 점
* 중복 방지 기능 자체가 정상 동작했음을 확인한 점

### 사례 2 — `reWriteBatchedInserts`를 채택하지 않은 결정

상황:

* PostgreSQL batch 성능을 높일 수 있다고 알려진 옵션을 검토했다.

행동:

* OFF와 ON을 같은 조건으로 반복 실행했다.
* 100, 1,000, 5,000 Span 조건을 비교했다.
* 단건 측정값을 환경 편차 기준으로 함께 확인했다.

결과:

* 작은 크기에서는 0.5~1% 수준 차이
* 5,000 Span에서는 약 4.2% 악화
* 일관된 개선이 없어 채택 보류

면접 포인트:

* 유명한 옵션을 무조건 적용하지 않은 점
* 성능 최적화에서 실측을 우선한 점
* 효과가 없는 결과도 투명하게 남긴 점

### 사례 3 — 멀티테넌트 복합 외래키

질문:

“애플리케이션에서 Tenant를 검증하면 되는데 왜 DB 외래키까지 필요했나요?”

답변 방향:

* 애플리케이션 검증 누락 가능성
* 각각 존재하는 Tenant와 Project가 잘못 조합될 위험
* DB를 최종 데이터 정합성 방어선으로 사용
* FK 비용은 이후 부하 테스트로 검증할 계획

## 6. 예상 면접 질문

* OpenTelemetry의 Trace, Span, Resource, Scope 차이는 무엇인가?
* `service.name`은 왜 Resource Attribute인가?
* OTLP JSON에서 64비트 정수가 문자열로 전달되는 이유는 무엇인가?
* Span ID와 Trace ID를 문자열로 저장한 이유는 무엇인가?
* TimescaleDB를 선택한 이유는 무엇인가?
* 왜 Elasticsearch나 ClickHouse를 사용하지 않았는가?
* Hypertable chunk를 1일로 설정한 근거는 무엇인가?
* Unique index에 `start_time`이 포함된 이유는 무엇인가?
* Collector retry가 중복 데이터를 만드는 이유는 무엇인가?
* `ON CONFLICT DO NOTHING`의 장단점은 무엇인가?
* JPA 대신 JDBC를 사용한 이유는 무엇인가?
* Batch가 단건보다 빨랐던 이유는 무엇인가?
* `reWriteBatchedInserts` 효과가 없었던 이유를 어떻게 분석할 것인가?
* Virtual Threads를 사용해도 DB connection pool이 필요한 이유는 무엇인가?
* 요청 중 한 Span이 잘못된 경우 전체 요청을 거부할 것인가?
* DB 장애 시 Collector와 Backend는 어떻게 동작해야 하는가?
* 멀티테넌트 데이터 유출을 어떻게 테스트할 것인가?
* Span 저장량을 어떻게 계산할 것인가?
* Retention과 compression 기준을 어떻게 결정할 것인가?

## 7. 보존해야 할 증거

### 코드

* Flyway V1~V4
* `spans` hypertable 스키마
* 복합 외래키
* 유일 인덱스
* OTLP parser
* AnyValue parser
* Event와 Link parser
* JDBC 단건과 batch 구현
* 벤치마크 코드
* HTTP Client 요청
* SQL fixture

### 로그

* Virtual Thread `isVirtual=true`
* Flyway migration 적용 로그
* Hypertable 확인 결과
* 동일 Span 재전송 `duplicates=1`
* Batch 신규 3건 저장
* Batch 중복 3건
* Batch 신규·중복 혼합 결과
* 잘못된 요청 `400`
* 헤더 누락 `400`

### 성능 자료

* 단건과 batch 원본 콘솔 결과
* rewrite OFF/ON 원본 콘솔 결과
* Java 버전
* PostgreSQL 버전
* TimescaleDB 버전
* 테스트 PC 사양
* Docker Desktop 자원 설정
* 테스트 날짜
* 반복 횟수
* Span 수와 생성 데이터 조건

### 향후 추가할 증거

* Batch chunk 크기 그래프
* CPU와 메모리 사용량
* HikariCP connection 사용량
* N100 결과
* Collector queue 지표
* DB 장애 중 retry 로그
* 일일 DB 증가량
* retention 전후 저장량
* compression 전후 DB 크기

## 8. 기술 블로그 주제

### 1. 유명한 PostgreSQL JDBC 옵션을 적용하지 않은 이유

핵심 내용:

* `reWriteBatchedInserts` 개념
* 기대 효과
* 실험 조건
* Span 수별 결과
* 결과 편차
* 채택하지 않은 결정
* 성능 옵션은 환경별로 측정해야 한다는 교훈

필요한 자료:

* OFF/ON 결과표
* 개별 측정값
* 벤치마크 코드
* JDBC URL 설정 방식

### 2. OpenTelemetry Collector 재시도와 중복 Span 문제

핵심 내용:

* Collector retry 시나리오
* 중복이 대시보드와 저장량에 미치는 영향
* Unique index 설계
* TimescaleDB 시간 컬럼 제약
* `ON CONFLICT DO NOTHING`
* 재전송 검증 결과

### 3. JPA 대신 JDBC batch를 선택한 과정

핵심 내용:

* Telemetry hot path의 특성
* 단건 구현부터 시작한 이유
* 공정한 비교를 위한 공통 SQL
* 100~5,000 Span 결과
* 약 2.9~3.1배 개선
* 향후 COPY 검토 조건

### 4. JSONB와 일반 컬럼을 함께 사용한 Trace 스키마

핵심 내용:

* OpenTelemetry의 동적 Attribute
* 핵심 필드 컬럼 승격
* JSONB 원본 보존
* 인덱스를 미리 추가하지 않은 이유
* 향후 조회 성능 실험

### 5. 멀티테넌트 APM에서 Project 소유 관계를 DB로 보장하기

핵심 내용:

* 잘못된 Tenant·Project 조합
* 복합 unique와 foreign key
* 애플리케이션 검증만으로 부족한 이유
* FK 비용과 향후 성능 측정

## 9. 다음 Portfolio Checkpoint 조건

다음 중 하나가 완료되면 CAREER_LOG를 갱신한다.

* Batch chunk 크기 결정
* API Key 기반 멀티테넌시 구현
* Collector 실제 연동
* DB 장애와 retry 실험
* Persistent queue 적용
* N100 홈서버 성능 측정
* Retention과 compression 측정
* 실제 사용자 서비스 연결
* 사내 PoC
* 장애 원인 분석 사례 확보

## 10. 다음 한 단계 높은 과제

현재는 저장 기능과 persistence-only 성능을 검증했다.

다음에는 다음 질문에 답할 수 있어야 한다.

```text
Collector가 5,000 Span을 전송하는 중 DB가 30초간 중단되면
Collector queue, Backend 응답, retry, 중복, 데이터 유실은
각각 어떻게 동작하는가?
```

이 실험까지 완료하면 단순 API 구현을 넘어 실제 관측 시스템 운영 경험으로 발전할 수 있다.

# CAREER_LOG.md 추가 내용

## 실무 경험

* OTLP JSON Trace 수신 API를 직접 구현하고 Trace ID, Span ID, timestamp, attributes, events, links의 검증 경계를 정의함
* JPA 단건 저장 대신 Spring JDBC batch를 적용하고 batch 크기별 처리량을 직접 측정함
* 멀티테넌트 환경에서 클라이언트 Header를 신뢰하지 않고 API Key 소유권으로 Tenant와 Project를 결정함
* API Key 원문을 저장하지 않고 공개 식별자와 Secret hash를 분리한 인증 구조를 구현함
* DB 장애를 클라이언트 인증 실패와 구분해 HTTP 503으로 처리하고 Collector retry와 연결함
* OpenTelemetry Collector persistent queue를 구성하고 Collector 재시작을 포함한 장애 복구 실험을 수행함
* Collector receiver, exporter, queue metric을 통해 수신·전송·대기 상태를 직접 관찰함
* 잘못된 실험 입력으로 200행이 생성된 문제를 queue metadata와 DB 결과를 비교해 원인을 분석하고 재실험함

## 포트폴리오에서 강조할 부분

### 1. 측정 기반 JDBC Batch 선택

* 단건 저장과 JDBC batch 비교
* batch가 약 2.9~3.1배 높은 처리량
* batch 크기 50~5000 비교
* 가장 빠른 단일 수치가 아니라 안정성과 batch 크기 제한을 함께 고려해 1000 선택

### 2. 멀티테넌트 데이터 격리

* Tenant/Project Header 위조 방지
* API Key DB 소유권을 신뢰 기준으로 사용
* Secret hash 저장
* 만료와 폐기 지원
* 고카디널리티 인증 정보를 metric tag에서 제외

### 3. 데이터 유실 방지

* DB 장애 시 Backend가 HTTP 503 반환
* Collector가 retry 수행
* persistent queue에 telemetry 보관
* Collector 재시작 후 queue metadata 복구
* DB 복구 후 별도 수동 전송 없이 자동 저장
* 100 Span 최종 저장 수 100, 고유 Span ID 100 확인

## 보존해야 할 증거

* JDBC batch 크기별 결과 표
* 단건 저장과 batch 저장 비교 결과
* DB 장애 시 HTTP 503 응답
* 인증 lookup error metric
* Collector 503 retry 로그
* Collector 재시작 후 persistent queue metadata 로드 로그
* DB 최종 100행, 고유 Span ID 100개 결과
* queue capacity 50,000 출력
* DB 장애 중 queue size 100 출력
* DB 장애 중 queue size 10,000 출력
* DB 복구 후 queue size 0 출력
* 최초 200행 문제와 재실험 후 100행 결과

## 이력서 문장 초안

* 대량 telemetry 저장의 DB round trip 비용을 줄이기 위해 Spring JDBC batch를 적용하고 batch 크기별 성능을 측정하여, 단건 저장 대비 약 2.9~3.1배 높은 처리량을 확인하고 초기 운영 batch 크기를 1,000으로 결정

* 멀티테넌트 telemetry 수집 과정에서 요청 Header 위조로 인한 데이터 격리 실패를 방지하기 위해 Project API Key의 DB 소유권으로 Tenant와 Project를 결정하고, 원문 Secret 비저장 및 hash 검증 방식의 인증 구조를 구현

* TimescaleDB 장애 시 telemetry 유실을 줄이기 위해 Backend의 retryable 503 응답과 OpenTelemetry Collector persistent queue를 연계하고, Collector 재시작과 DB 복구 이후 100개 Span이 중복 없이 자동 저장되는 장애 복구 시나리오를 검증

* Collector 내부 receiver·exporter·queue 지표를 노출하여 DB 장애 중 queue 증가와 복구 후 소진을 관찰하고, 50,000 Span 용량의 queue에서 10,000 Span 수용 및 enqueue 실패가 발생하지 않음을 확인

## 숫자를 추가하면 안 되는 항목

다음 값은 아직 정확히 측정되지 않았으므로 이력서나 블로그에 숫자로 쓰지 않는다.

* queue drain 처리량
* DB 복구 완료시간
* Span당 queue 디스크 사용량
* 최대 장애 허용시간
* 10,000 Span 최종 DB 저장 수
* queue overflow 시 유실 수량

## 블로그 주제

### OpenTelemetry Collector 재시작에도 Trace를 잃지 않도록 만든 과정

구성:

1. 메모리 queue의 한계
2. Backend DB 장애를 503으로 분류한 이유
3. file storage와 persistent sending queue 구성
4. API Key 인증과 Collector exporter 연결
5. DB 중지 상태에서 100 Span 전송
6. Collector 재시작
7. queue metadata 복구 로그
8. DB 복구 후 자동 재전송
9. 200행 중복 실험이 발생한 원인
10. 재실험으로 100행과 고유 Span ID 100개 확인
11. persistent queue가 보장하지 못하는 장애 범위

## 예상 면접 질문

* Collector가 200을 반환했는데 DB 저장 성공이라고 볼 수 없는 이유는 무엇인가?
* HTTP 401과 503을 Collector 관점에서 어떻게 구분해야 하는가?
* Persistent queue가 있어도 데이터가 유실될 수 있는 상황은 무엇인가?
* API Key 조회 DB가 장애 나면 왜 인증 실패 401이 아니라 503을 반환해야 하는가?
* Queue capacity 50,000은 어떤 기준으로 다시 산정해야 하는가?
* Collector 재시도에서 중복 Span이 발생할 가능성은 어떻게 처리했는가?
* 왜 Kafka를 사용하지 않았는가?
* Batch size 500이 더 빠른 결과가 있었는데 왜 1000을 선택했는가?

---

## 현재 Phase

Phase 7 — Trace 조회 API

## 최근 완료

### TimescaleDB 데이터 수명 관리

* `spans` hypertable chunk interval 1일 확인
* Hypercore columnstore 활성화
* `tenant_id, project_id` 기준 segment 설정
* `start_time DESC` 정렬 설정
* 2일 초과 chunk 자동 columnstore 전환 정책
* 30일 초과 chunk 자동 retention 정책
* Columnstore 정책 수동 실행 검증
* 전환 후 과거 Span 조회 검증
* 최근 Span rowstore 저장 검증
* 35일 전 테스트 chunk 삭제 검증
* 4일 전과 최근 데이터 보존 검증
* 두 background job 자동 스케줄 복구

## 현재 데이터 수명주기

```text
0~2일
→ rowstore

2~30일
→ columnstore

30일 초과
→ retention 삭제
```

## 검증이 필요한 항목

* 운영 데이터 기반 columnstore 압축률
* 조회 성능 전후 비교
* 일일 DB 증가량
* 30일 예상 저장 용량
* background job 실패 감시
* Tenant별 보존기간 요구사항

## 현재 기술 부채

* Tenant별 retention 미지원
* Retention과 columnstore job 실패 알림 없음
* 운영 환경의 디스크 용량 산정 미완료
* 조회 API 미구현
* Trace 상세 화면 미구현
* Prometheus/Grafana 미연동

## 다음 작업

1. Trace 목록 조회 요구사항과 SQL 정의
2. 현재 인덱스가 조회 패턴을 지원하는지 확인
3. 인증된 Project 범위의 Trace 목록 Repository 구현
4. 최소 Trace 목록 API 구현
5. Trace ID 기반 상세 조회 구현

# CAREER_LOG.md 추가

## Portfolio Checkpoint — Telemetry 데이터 수명주기

### 직접 경험한 내용

* TimescaleDB hypertable의 chunk interval과 partition column 확인
* 최신 Hypercore columnstore 설정 적용
* 최근 데이터와 과거 데이터의 저장 방식을 분리
* Background job을 중지하고 수동 실행하는 검증 절차 수행
* Retention 실행 전에 실제 삭제 대상 chunk를 미리 확인
* Chunk 전체 삭제가 다른 데이터에 미치는 위험 검토
* 삭제 대상과 보존 대상 데이터를 함께 두고 정합성 검증
* 정책 검증 후 자동 스케줄 복구

### 포트폴리오에서 평가받을 부분

* 단순히 retention 설정만 추가하지 않고 실제 삭제 시나리오를 검증함
* Chunk 단위 삭제의 데이터 유실 위험을 사전 검사함
* Rowstore, columnstore, retention을 하나의 수명주기로 설계함
* 제한된 저장 장비를 고려해 무기한 저장을 방지함
* 아직 측정하지 않은 압축률과 성능 수치를 만들어내지 않음

### 보존할 증거

* Columnstore 전환 전후 `is_compressed` 결과
* Columnstore job 성공 결과
* 전환 후 과거 Span 조회 결과
* 최근 Span rowstore 확인 결과
* Retention 삭제 후보 `show_chunks` 결과
* 대상 chunk의 `non_test_rows = 0` 결과
* 삭제 전후 비교 Boolean 결과
* 35일 전 Span 삭제 결과
* 4일 전과 최근 Span 보존 결과
* 두 정책의 `scheduled = true` 복구 결과

### 이력서 문장 초안

* 제한된 저장 자원에서 telemetry의 무기한 증가를 방지하기 위해 TimescaleDB rowstore·columnstore·retention 수명주기를 설계하고, 최근 2일은 rowstore, 2~30일은 columnstore, 30일 초과 데이터는 chunk 단위로 제거하도록 구성

* Retention 정책 적용 과정에서 다른 데이터의 의도치 않은 삭제를 방지하기 위해 대상 chunk의 비테스트 행과 전체 삭제 후보를 사전 검사하고, 35일 전 데이터만 삭제되며 4일 전 및 최근 데이터는 보존되는 장애 시나리오를 검증

### 예상 면접 질문

* TimescaleDB retention이 행 단위 DELETE보다 유리한 이유는 무엇인가?
* `start_time`과 `ingested_at` 중 어떤 값을 retention 기준으로 사용했는가?
* 늦게 도착한 Span은 retention 정책에서 어떻게 처리되는가?
* Tenant별 보존기간을 현재 공유 hypertable에서 구현하기 어려운 이유는 무엇인가?
* Columnstore 전환 기준을 2일로 선택한 근거는 무엇인가?
* Retention job 실행 전 어떤 안전 검사를 수행했는가?
