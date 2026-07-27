# AeroTrace 프로젝트 컨텍스트

> 마지막 업데이트: 2026-07-27
> 현재 상태: 개발 진행 중
> 현재 Phase: Phase 3 — OTLP Trace 수신 및 저장
> 다음 작업: JDBC batch chunk 크기 비교 및 운영 기본값 결정

## 1. 프로젝트 개요

AeroTrace는 OpenTelemetry 기반의 초경량 멀티테넌트 APM 서비스다.

사이드 프로젝트, 개인 서비스, 소규모 개발팀처럼 기존 상용 APM의 비용이나 운영 복잡도가 부담스러운 사용자를 대상으로 한다.

초기에는 SaaS 형태로 제공하고, 이후 동일한 코드베이스를 사용한 온프레미스 배포를 지원하는 것을 목표로 한다.

## 2. 프로젝트 목표

1. 실제 사용자가 사용할 수 있는 안정적인 SaaS MVP 완성
2. 사이드 프로젝트와 소규모 서비스를 위한 간단한 APM 제공
3. 추후 온프레미스 배포가 가능한 구조 유지
4. 사내 서비스에 PoC와 도입을 제안할 수 있는 수준 달성
5. 백엔드 개발자 이직에 활용할 수 있는 포트폴리오 구축
6. 기술 블로그, GitHub, LinkedIn에 공유할 실무 경험 축적
7. OpenTelemetry, APM, 관측 가능성, 성능 측정, 장애 대응 경험 확보

## 3. 타깃 사용자

* 사이드 프로젝트 개발자
* 개인 개발자
* 소규모 서비스 운영자
* 소규모 개발팀
* 사내 PoC 대상 서비스

## 4. 기술 및 운영 제약

### 백엔드

* Java 21
* Spring Boot 4.1.0
* Virtual Threads
* Spring JDBC
* Flyway
* PostgreSQL JDBC Driver
* 추후 Spring Data JPA 도입 예정

### 데이터베이스

* PostgreSQL 15.18
* TimescaleDB 2.28.3
* TimescaleDB hypertable
* JSONB

### Telemetry

* OpenTelemetry Protocol
* 현재 OTLP/HTTP JSON Trace 수신 지원
* OpenTelemetry Collector
* 추후 OTLP Protobuf와 gzip 지원 예정

### 프론트엔드

* Next.js 예정
* 아직 구현하지 않음

### 실행 환경

* Docker Compose
* Windows 개발 PC와 Docker Desktop
* Oracle Cloud 무료 인스턴스 예정
* N100, RAM 16GB, SSD 512GB 홈서버 예정

## 5. 현재 시스템 구조

```text
Instrumented Application
        │
        │ OTLP
        ▼
OpenTelemetry Collector
        │
        │ OTLP/HTTP JSON
        ▼
AeroTrace Spring Boot Backend
        │
        │ JDBC batch
        ▼
TimescaleDB
```

현재 Collector와 Backend의 실제 end-to-end 연동은 아직 완료하지 않았다.

## 6. 백엔드 패키지 방향

```text
com.huning.aerotrace
├── ingest
│   ├── api
│   ├── application
│   ├── domain
│   └── infrastructure
├── tenant
├── query
└── common
```

현재는 OTLP 수신과 저장 경로를 `ingest` 모듈 아래에 구성하고 있다.

마이크로서비스로 분리하지 않고 모듈형 모놀리스로 시작한다.

## 7. 데이터 소유 구조

```text
Tenant
└── Project
    └── Service
        └── Trace
            └── Span
```

* Tenant는 개인, 팀 또는 회사의 데이터 소유 경계다.
* Project는 Tenant 내부의 telemetry 구분 단위다.
* 하나의 Project에는 여러 `service.name`이 포함될 수 있다.
* 모든 Span은 `tenant_id`, `project_id`에 귀속된다.

## 8. 현재 데이터베이스 Migration

### V1 — TimescaleDB 확장 활성화

```text
V1__enable_timescaledb.sql
```

* TimescaleDB extension 활성화
* Flyway schema history 관리 시작

### V2 — Tenant와 Project 생성

```text
V2__create_tenants_and_projects.sql
```

* `tenants`
* `projects`
* Tenant 내부 Project slug 유일성
* Project가 있는 Tenant의 삭제 제한
* Tenant와 Project의 기본 무결성 제약조건

### V3 — Span hypertable 생성

```text
V3__create_spans.sql
```

* `spans` hypertable 생성
* `start_time` 기준 1일 chunk
* Tenant와 Project 복합 외래키
* Trace ID와 Span ID 형식 검증
* 시간 순서와 duration 검증
* JSONB 자료형 검증
* 중복 Span 방지 유일 인덱스
* 최근 Span 조회 인덱스
* Trace 조회 인덱스

### V4 — Span dropped count 추가

```text
V4__add_span_drop_counts.sql
```

* `dropped_attributes_count`
* `dropped_events_count`
* `dropped_links_count`
* OTLP `uint32` 범위에 맞춘 CHECK 제약조건

## 9. 현재 구현 및 검증 완료 항목

다음 항목은 사용자가 직접 코드를 적용하고 실행해 정상 동작을 확인했다.

### 백엔드 기반

* Spring Boot 4.1.0 프로젝트 생성
* Java 21.0.9 실행 확인
* Actuator health endpoint 확인
* Virtual Threads 활성화
* HTTP 요청이 Virtual Thread에서 처리되는 것 확인
* Gradle test와 애플리케이션 실행 확인

### 데이터베이스

* HikariCP와 TimescaleDB 연결
* Flyway V1~V4 적용
* Migration 재실행 방지 확인
* TimescaleDB hypertable 생성 확인
* 1일 chunk 설정 확인
* FK, UNIQUE, CHECK 제약조건 확인
* Tenant·Project·Span 관계 삽입 확인

### OTLP 수신

* `POST /v1/traces`
* `application/json` 수신
* 정상 요청에 `200 OK`, `{}` 반환
* 빈 OTLP 요청 허용
* 잘못된 JSON 구조 거부
* 잘못된 Content-Type 거부

### OTLP 파싱

* `service.name`
* scope name, version
* trace ID, span ID, parent span ID
* Span name과 kind
* status code와 message
* start/end timestamp
* duration 계산
* trace state와 flags
* dropped attributes/events/links count
* Resource Attributes
* Span Attributes
* Span Events
* Span Links
* AnyValue 문자열, 정수, 실수, 불리언, bytes, 배열, 중첩 key-value

### 데이터 저장

* JDBC 기반 Span 저장
* Resource와 Span Attributes JSONB 저장
* Events와 Links JSONB 저장
* 요청 단위 트랜잭션
* Tenant와 Project 복합 외래키를 통한 데이터 격리
* `ON CONFLICT DO NOTHING` 기반 중복 방지
* 다중 Span JDBC batch 저장
* 신규·중복·영향 행 수 미확인 결과 분류

### 개발 및 검증 도구

* IntelliJ HTTP Client용 `.http` 요청 파일
* IntelliJ Database Tool 연결
* 로컬 개발용 SQL fixture
* JDBC 단건 저장과 batch 저장 비교 벤치마크
* pgJDBC `reWriteBatchedInserts` ON/OFF 비교

## 10. 현재 성능 측정 결과

측정 환경:

* Windows 개발 PC
* Docker Desktop
* PostgreSQL 15 기반 TimescaleDB
* HTTP와 OTLP JSON 파싱을 제외한 persistence-only 측정
* 단건과 batch 모두 요청당 트랜잭션 1개
* JSON 직렬화, 트랜잭션, JDBC 저장 시간을 동일하게 포함
* 저장 결과 검증과 테스트 데이터 삭제는 측정에서 제외
* 워밍업 후 반복 측정
* 실행 순서를 교차하여 편향 완화
* 중앙값 기준 비교

### 주요 결과

| Span 수 |      단건 중앙값 | JDBC batch 중앙값 | Batch 개선 |
| -----: | ----------: | -------------: | -------: |
|    100 |    91.084ms |       30.460ms |  약 2.99배 |
|  1,000 |   874.985ms |      299.167ms |  약 2.92배 |
|  5,000 | 4,566.945ms |    1,485.885ms |  약 3.07배 |

측정된 처리량 범위:

```text
단건 저장: 약 1,095~1,143 spans/sec
Batch 저장: 약 3,283~3,365 spans/sec
```

`reWriteBatchedInserts=true`는 현재 환경에서 일관된 개선을 보이지 않았다.

* 100 Span: 약 7.8% 악화
* 1,000 Span: 약 3.5% 개선
* 5,000 Span: 약 7.2% 악화

따라서 JDBC batch는 유지하고, `reWriteBatchedInserts`는 현재 활성화하지 않는다.

## 11. 현재 허용한 기술 부채

* 로컬 DB 비밀번호가 Compose에 직접 작성되어 있음
* TimescaleDB와 Collector 이미지가 `latest` 태그 사용
* Docker Compose의 obsolete `version` 속성 존재
* API Key 인증 미구현
* 임시 Tenant·Project UUID 헤더 사용
* 사용자·멤버십·API Key control-plane 미구현
* 요청 크기 제한 미구현
* Tenant별 rate limit과 quota 미구현
* OTLP Protobuf 미지원
* gzip 미지원
* 표준 OTLP 오류 응답 미구현
* Testcontainers 미도입
* 테스트가 로컬 TimescaleDB 실행 상태에 일부 의존
* 요청 전체 Span을 하나의 batch로 처리
* Collector persistent queue 미구성
* retention과 compression 미적용
* 백업과 복구 절차 미구현
* Metrics와 Logs 수신 미구현
* Query API와 Dashboard 미구현

## 12. 운영 전에 반드시 필요한 항목

* API Key 인증
* Tenant와 Project 데이터 격리 통합 테스트
* 요청 본문 크기 제한
* Tenant별 rate limit과 quota
* Collector retry와 persistent queue
* DB 장애 시 응답 정책
* 중복 및 재시도 시나리오 테스트
* retention 정책
* TimescaleDB compression
* 백업 및 복구 테스트
* TLS와 안전한 네트워크 연결
* 비밀번호와 Secret 환경변수 분리
* 이미지 버전 고정
* 애플리케이션 자체 Metrics
* 수집 성공·실패·중복·지연시간 지표
* 운영 배포용 healthcheck
* 데이터 유실 시나리오 검증

## 13. 현재 검증이 필요한 항목

* 적절한 JDBC batch chunk 크기
* 요청당 최대 Span 수
* 요청 크기별 메모리 사용량
* OTLP HTTP 파싱을 포함한 전체 처리량
* Collector를 포함한 end-to-end 처리량
* 동시 요청 처리량
* HikariCP connection pool 적정 크기
* Virtual Threads와 DB connection pool의 관계
* N100 홈서버 성능
* 일일 DB 증가량
* 평균 Span 크기
* 인덱스 크기
* JSONB Attribute 양에 따른 처리량 변화
* retention과 compression 효과
* DB 장애 중 데이터 유실 여부

## 14. 다음 작업

### 바로 진행할 작업

1. 총 Span 수를 고정한 batch chunk 크기 비교
2. 운영 기본 batch 크기 결정
3. 요청 크기 및 Span 개수 제한 설계
4. 표준화된 예외 처리와 OTLP 오류 응답
5. API Key 기반 Tenant·Project 식별

### 이후 작업

1. Collector와 Backend 실제 연결
2. Collector retry와 persistent queue
3. OTLP Protobuf와 gzip
4. Trace 조회 API
5. Trace 상세 조회
6. Service와 Endpoint 집계
7. TimescaleDB retention과 compression
8. Next.js Dashboard
9. AeroTrace 자체 관측
10. Oracle Cloud와 N100 홈서버 배포

## 15. 프로젝트 진행 원칙

* 측정되지 않은 성능 수치를 만들지 않는다.
* 실제 병목이 확인되기 전 Kafka, Kubernetes, Elasticsearch 등을 추가하지 않는다.
* 사용자가 직접 적용하고 실행한 작업만 완료로 기록한다.
* SaaS와 온프레미스를 하나의 코드베이스로 지원할 수 있는 구조를 유지한다.
* 기능을 독립적으로 실행하고 검증할 수 있는 작은 단계로 구현한다.
* 보안, 멀티테넌시, 데이터 유실, 중복 저장 문제를 우선적으로 검토한다.
