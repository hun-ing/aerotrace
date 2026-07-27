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
