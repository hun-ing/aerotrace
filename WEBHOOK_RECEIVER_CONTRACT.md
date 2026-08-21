# AeroTrace Webhook Receiver Contract

> 마지막 업데이트: 2026-08-21
> Contract version: 1
> 상태: Repository adapter 기준 확정, production endpoint와 인증 방식은 미확정

---

## 1. 목적과 범위

이 문서는 AeroTrace notification adapter와 외부 Webhook receiver 사이의 HTTP contract를 정의한다.

범위:

- Collector queue notification event의 HTTP 전달
- Request method, header, payload
- HTTP status별 sender 동작
- `event_id` 기반 receiver idempotency
- Timeout과 중복 전달 의미
- 현재 인증·보안 지원 범위
- Production activation 전 receiver 검증 조건

범위 밖:

- OpenTelemetry ingest API
- 사용자용 Trace Query API
- Receiver 제품 또는 provider 선정
- Notification 지연 SLO와 경보 threshold 값
- Receiver 내부 UI, 메시지 포맷, 보존 기간

## 2. Delivery 보장 수준

현재 Webhook delivery는 exactly-once를 보장하지 않는다.

```text
Sender retry
+
durable outbox
+
event_id
```

를 이용한 at-least-once 성격의 전달이다.

Receiver는 같은 `event_id`가 두 번 이상 도착할 수 있다고 가정해야 한다. 동일 event의 중복 요청을 별개의 사용자 알림으로 처리하면 안 된다.

## 3. Endpoint 계약

Adapter는 하나의 configured URL로 HTTP POST를 전송한다.

```text
Configuration source:
AEROTRACE_WEBHOOK_URL
```

허용 URL scheme:

```text
http
https
```

Production에서는 HTTPS endpoint만 사용한다.

Adapter URL validation:

- Host가 반드시 있어야 한다.
- URL userinfo 형식의 username/password를 거부한다.
- Fragment를 거부한다.
- Redirect를 자동으로 따라가지 않는다.

HTTP 3xx는 새 endpoint로 재전송하지 않고 permanent failure로 처리한다. Endpoint 변경은 운영자가 configuration을 명시적으로 수정해야 한다.

## 4. HTTP Request

### Method

```http
POST <configured-path>
```

### Headers

```http
Content-Type: application/json
Accept: application/json
User-Agent: AeroTrace-Notification/1
X-AeroTrace-Event-Id: <event_id>
```

Header 이름은 HTTP 규칙에 따라 case-insensitive하게 처리한다.

`X-AeroTrace-Event-Id`는 request body의 `event_id`와 반드시 같다. Receiver는 둘이 다르면 contract violation으로 거부해야 한다.

`Content-Length`, connection 관리 header처럼 HTTP client library가 추가할 수 있는 header는 이 contract의 안정적인 application-level field가 아니다. Receiver는 이에 의존하지 않는다.

### Encoding

Request body는 UTF-8 JSON object다.

Adapter는 compact JSON으로 직렬화하지만 공백과 key 순서는 contract가 아니다.

## 5. Event Payload Schema Version 1

현재 producer가 생성하는 payload 예:

```json
{
  "schema_version": 1,
  "event_id": "1787299200000000000-12345",
  "event": "ALERT",
  "alert_required": true,
  "previous_status": "OK",
  "current_status": "CRITICAL",
  "checker_exit_code": 2,
  "state_file": "/var/lib/aerotrace-monitoring/collector-queue-alert.json",
  "evaluated_at": "2026-08-21T03:00:00+00:00",
  "checker_output": {
    "stdout": "status=CRITICAL\nqueue_size=50000",
    "stderr": ""
  }
}
```

### Field 의미

`schema_version`

- 현재 값은 integer `1`이다.
- Receiver는 지원하지 않는 version을 조용히 처리하지 않는다.

`event_id`

- 비어 있지 않은 string이다.
- 현재 producer는 `<time_ns>-<pid>` 형식으로 생성한다.
- Idempotency와 duplicate suppression의 주 key다.
- 형식 자체보다 전체 문자열의 exact match를 사용한다.

`event`

허용 값:

```text
ALERT
STATUS_CHANGE
RECOVERY
REMINDER
```

의미:

```text
ALERT
→ 최초 non-OK 또는 OK에서 non-OK로 전환

STATUS_CHANGE
→ non-OK 상태 사이의 전환

RECOVERY
→ 이전 non-OK에서 OK로 복구

REMINDER
→ 동일 non-OK 상태가 repeat interval 이상 지속
```

`alert_required`

- Outbox Webhook event에서는 boolean `true`다.
- `false` event는 adapter contract에서 거부된다.

`previous_status`

- `null` 또는 status string이다.
- 첫 평가에는 `null`일 수 있다.

`current_status`

- 현재 evaluator 상태다.

허용 status:

```text
OK
WARNING
CRITICAL
UNKNOWN
```

`checker_exit_code`

- integer 또는 `null`이다.
- Collector queue checker process의 결과다.
- Receiver의 HTTP 응답 code와 다른 값이다.

`state_file`

- Event를 생성한 evaluator의 persistent state 경로다.
- Receiver가 local filesystem에서 접근 가능한 경로라고 가정하지 않는다.
- Display와 진단용 metadata로만 취급한다.

`evaluated_at`

- Event 평가 시각의 timezone 포함 ISO-8601 string이다.
- Delivery 완료 시각이 아니다.
- Notification delivery latency 계산의 시작점으로 사용한다.

`checker_output`

- `stdout`과 `stderr` string을 포함한다.
- 여러 줄일 수 있다.
- Receiver는 JSON이나 고정 key 집합으로 재해석하지 말고 진단 text로 취급한다.

### Forward compatibility

Receiver는 같은 `schema_version`에 추가된 알 수 없는 field를 무시할 수 있어야 한다.

다음 변경은 새로운 schema version이 필요하다.

- 기존 required field 삭제
- Field type 변경
- 기존 event/status 의미 변경
- Idempotency key 의미 변경

## 6. Receiver Idempotency 요구사항

### Idempotency key

```text
X-AeroTrace-Event-Id
=
body.event_id
```

Receiver는 endpoint 또는 AeroTrace source 범위에서 `event_id`에 unique constraint와 동등한 원자적 중복 방지 수단을 사용해야 한다.

Process-local memory cache만으로는 restart 후 중복을 막을 수 없으므로 production deduplication 근거로 충분하지 않다.

### 최초 요청

권장 처리 순서:

```text
1. Header/body event_id 일치 검증
2. schema_version과 required field 검증
3. event_id 원자적 claim 또는 durable insert
4. 사용자 알림 side effect를 한 번 수행하거나 durable queue에 기록
5. 완료 또는 durable acceptance 상태 저장
6. HTTP 2xx 반환
```

### 이미 성공한 event의 중복 요청

```text
동일 event_id
+
동일 logical payload
```

이면 사용자 알림 side effect를 다시 수행하지 않고 기존 성공 결과를 근거로 HTTP 2xx를 반환한다.

권장 status:

```text
204 No Content
```

### 같은 event ID와 다른 payload

같은 `event_id`에 기존 payload와 의미가 다른 요청이 오면 idempotent duplicate로 처리하지 않는다.

Receiver는 payload hash 또는 canonical business fields를 비교하고 conflict를 기록한 뒤 non-retryable 4xx로 거부해야 한다.

권장 status:

```text
409 Conflict
```

Sender는 이를 permanent failure로 latch한다. 운영자는 sender와 receiver 기록을 대조해야 한다.

### 동시에 도착한 중복 요청

동시 요청에서도 unique constraint, transaction, compare-and-set과 같은 원자적 처리로 하나의 side effect만 허용한다.

첫 요청이 아직 durable acceptance를 확정하지 못했다면 중복 요청에 성공을 먼저 반환하지 않는다. 일시적으로 처리할 수 없는 경우 retryable 5xx를 반환할 수 있다.

### Async receiver

Receiver가 내부 queue를 사용한다면 HTTP 2xx는 다음을 의미해야 한다.

```text
요청이 durable queue에 저장됨
+
receiver restart 후에도 처리 가능
+
동일 event_id 중복 억제 가능
```

Memory queue에만 넣은 상태에서 2xx를 반환하면 sender가 pending event를 ACK하므로 허용하지 않는다.

## 7. HTTP Response 의미

### Success

```text
200 <= status < 300
```

Sender 해석:

```text
delivery 성공 확정
→ local receipt durable 저장
→ persistent failure-state 삭제
→ pending event ACK
```

Receiver는 2xx를 “sender retry가 더 이상 필요하지 않다”는 확정 응답으로만 사용한다.

Response body는 contract에 포함되지 않는다. Sender는 최대 4096 bytes만 읽고 내용으로 성공 여부를 결정하지 않는다.

### Retryable failure

```text
HTTP 408
HTTP 429
HTTP 500-599
connection failure
timeout
```

Sender 동작:

```text
pending 유지
failure_kind=retryable
failure_count 증가
bounded exponential backoff 후 자동 재시도
```

기본 backoff:

```text
5 → 10 → 20 → 40 → 80 → 160 → 300초 상한
```

현재 sender는 `Retry-After` response header를 해석하지 않는다. Receiver의 rate limit이 300초보다 긴 retry interval을 요구한다면 production activation 전에 sender 정책을 조정해야 한다.

### Permanent failure

```text
HTTP 3xx
408/429를 제외한 HTTP 4xx
그 밖의 retryable 목록에 없는 non-2xx
```

Sender 동작:

```text
첫 실패는 pending과 permanent failure-state 저장
→ 이후 timer 실행은 실제 HTTP 요청 없이 latch
→ 운영자 원인 수정
→ explicit permanent retry 1회
```

Receiver는 일시 장애, dependency timeout, 일시적인 overload에 permanent 4xx를 사용하지 않는다.

## 8. Timeout과 Ambiguous Delivery

다음 순서는 발생할 수 있다.

```text
Receiver가 request와 side effect 처리 완료
→ response가 sender timeout 전에 도착하지 않음
→ sender는 retryable timeout으로 기록
→ 같은 event_id 재시도
```

Sender는 receiver가 첫 요청을 처리했는지 알 수 없다. Timeout 직후 pending을 수동 ACK하거나 failure-state를 삭제하면 안 된다.

Receiver deduplication이 이 ambiguous window의 중복 사용자 알림을 막아야 한다.

Receiver 처리 시간은 sender의 현재 Webhook timeout 5초보다 충분히 짧아야 한다. 긴 작업은 먼저 durable queue에 저장한 뒤 2xx를 반환하는 구조를 사용한다.

## 9. Authentication과 보안

### 현재 지원 범위

현재 adapter는 사용자 정의 authentication header를 지원하지 않는다.

지원하지 않는 예:

```http
Authorization: Bearer <token>
X-Signature: <hmac>
```

URL userinfo credential도 거부한다.

Query parameter가 있는 URL은 기술적으로 허용되지만 secret URL은 process environment와 systemd environment file에 존재하게 된다. Endpoint provider가 secret URL 방식을 사용하는 경우 URL 전체를 credential로 취급한다.

Header token이나 request signing이 필요한 provider를 선택하면 production 전환 전에 adapter 구현, secret rotation, log redaction과 회귀 테스트를 추가해야 한다.

### Secret 취급

- Webhook URL이나 token을 Git에 저장하지 않는다.
- `/etc/aerotrace/notification.env`에 server별로 저장한다.
- Environment file은 root 소유와 mode `0600`을 사용한다.
- URL 전체를 journal, issue, chat, screenshot에 출력하지 않는다.
- Receiver access log에서 query secret을 redact한다.
- Secret rotation 시 old/new credential overlap과 rollback 절차를 준비한다.

### Transport security

- Production은 HTTPS를 사용한다.
- 인증서 검증을 비활성화하지 않는다.
- Receiver는 request body 크기 제한을 둔다.
- Receiver는 JSON field를 shell command, template, query에 검증 없이 사용하지 않는다.
- `checker_output` 전체를 공개 channel에 그대로 노출하지 않는다.

## 10. Observability 요구사항

Receiver는 secret을 제외하고 다음 값을 기록할 수 있어야 한다.

```text
received_at
event_id
event
current_status
schema_version
payload hash
dedup result: new|duplicate|conflict
processing result
HTTP status
processing duration
```

권장 metric:

```text
requests_total{result}
unique_events_total{event}
duplicates_total
conflicts_total
processing_duration_seconds
durable_queue_depth
delivery_side_effect_failures_total
```

Payload 원문과 `checker_output`은 필요한 보존 기간과 접근 권한을 정한 뒤 저장한다.

## 11. Production Activation Acceptance Test

Receiver는 production 연결 전에 격리된 test event로 다음을 통과해야 한다.

Sender-side tracked 회귀 suite:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -v
```

이 suite의 local fake receiver 검증은 실제 provider의 durable deduplication, authentication, timeout과 운영 metric acceptance test를 대신하지 않는다.

### Request contract

- HTTP POST 수신
- UTF-8 JSON parse
- Header/body `event_id` 일치 확인
- Schema version 1 처리
- 모든 event type 처리
- 알 수 없는 additive field 허용

### Success와 deduplication

- 최초 event side effect 1회와 HTTP 2xx
- 동일 event 재전송 시 side effect 증가 없음
- 중복 요청에도 HTTP 2xx
- Receiver restart 후에도 deduplication 유지
- 동시 duplicate 요청에서 side effect 1회
- 같은 event ID와 다른 payload conflict 탐지

### Failure

- HTTP 503 후 sender pending 유지
- Backoff 중 실제 request 증가 없음
- Receiver 복구 후 같은 event ID로 자동 retry와 성공
- HTTP 400 후 permanent latch
- 원인 수정 후 explicit retry 성공
- 3xx가 자동 redirect되지 않음

### Timeout

- Receiver 처리 후 response 지연으로 ambiguous timeout 재현
- 같은 event ID retry 확인
- 최종 사용자 side effect 1회 확인

### Security와 운영

- HTTPS 인증서 검증
- Secret이 Git과 journal에 노출되지 않음
- Receiver log와 metric에서 event ID 추적 가능
- Rollback 시 pending/failure-state 보존

## 12. Production Activation Blocker

다음 항목은 아직 정해지지 않았다.

```text
Receiver provider와 owner
Production endpoint
Authentication 방식
Credential rotation 방식
Receiver durable deduplication 저장소
Notification SLO와 경보 threshold
Incident escalation owner
```

이 항목을 확정하고 acceptance test를 통과하기 전까지 installed production runtime은 local-file 기준선을 유지한다.
