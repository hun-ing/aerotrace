# AeroTrace Webhook Receiver Contract

> 마지막 업데이트: 2026-08-21
> Contract version: 2
> 상태: Slack + Cloudflare Worker/D1/Queue 기준 확정, production 미활성

---

## 1. 목적과 범위

이 문서는 AeroTrace notification sender와 Cloudflare receiver 사이의 HTTP contract, 그리고 receiver가 Slack Incoming Webhook으로 비동기 전달할 때 지켜야 할 내구성과 중복 처리 규칙을 정의한다.

선택한 구조:

```text
AeroTrace filesystem outbox
  -> HMAC-signed HTTPS POST
  -> Cloudflare Worker
  -> D1 durable event_id claim
  -> Cloudflare Queue
  -> Slack Incoming Webhook
```

구현 위치:

```text
Sender:   scripts/process-notification-outbox.py
Receiver: receiver/cloudflare-slack/
```

범위 밖:

- OpenTelemetry ingest와 Trace Query API
- Slack 사용자가 메시지를 실제로 읽은 시각
- Cloudflare와 Slack 계정 생성 자체
- 조직 전체의 24x7 on-call 정책

## 2. 보장 수준

Sender에서 receiver durable acceptance까지는 filesystem outbox, HMAC, D1 unique key, Cloudflare Queue를 결합한 at-least-once 전달이다.

```text
exactly-once HTTP request = 보장하지 않음
동일 event_id durable claim = 보장
동일 event_id와 다른 payload 탐지 = 보장
Slack user-visible exactly-once = 보장하지 않음
```

Slack이 2xx를 반환한 뒤 Worker가 D1의 `delivered` 갱신 전에 중단되면 Queue retry로 Slack 메시지가 중복될 수 있다. Slack Incoming Webhook은 AeroTrace `event_id`를 provider-side idempotency key로 받지 않으므로 이 crash window를 제거할 수 없다.

따라서 정확한 표현은 “durable receiver deduplication과 at-least-once Slack delivery”다.

## 3. Endpoint

Production endpoint:

```http
POST https://<worker-host>/v1/notifications
```

Sender configuration:

```text
AEROTRACE_WEBHOOK_URL
```

Sender URL 규칙:

- Production은 HTTPS만 사용한다.
- Host가 있어야 한다.
- URL userinfo와 fragment를 거부한다.
- Redirect를 따라가지 않는다.
- Query parameter는 sender가 기술적으로 허용하지만 선택한 receiver URL에는 사용하지 않는다.

Receiver는 `/v1/notifications` 이외의 path를 404로 처리하고, 이 path의 POST 이외 method는 405로 처리한다.

## 4. HTTP request

### Headers

```http
Content-Type: application/json
Accept: application/json
User-Agent: AeroTrace-Notification/1
X-AeroTrace-Event-Id: <event_id>
X-AeroTrace-Timestamp: <unix-seconds>
X-AeroTrace-Signature: v1=<lowercase-sha256-hex>
```

Header 이름은 case-insensitive다. `X-AeroTrace-Event-Id`와 body의 `event_id`는 정확히 같아야 한다.

### Encoding and size

- Body는 UTF-8 JSON object다.
- Receiver는 invalid UTF-8을 replacement character로 복구하지 않고 400으로 거부한다.
- 최대 request body는 64 KiB다.
- Sender의 compact JSON 공백과 key 순서는 payload contract가 아니다.
- HMAC은 재직렬화한 JSON이 아니라 전송된 exact body bytes를 사용한다.

## 5. HMAC authentication

공유 secret:

```text
AEROTRACE_WEBHOOK_SIGNING_SECRET   sender
AEROTRACE_SIGNING_SECRET           receiver
```

두 값은 같은 32 UTF-8 byte 이상의 random secret이어야 한다. 빈 값, 32 bytes 미만, 앞뒤 공백이 있는 값은 configuration error다.

서명 대상:

```text
ASCII("v1")
+ ASCII(".")
+ ASCII(X-AeroTrace-Timestamp)
+ ASCII(".")
+ exact request body bytes
```

계산:

```text
hex(HMAC-SHA256(shared_secret, signed_content))
```

Receiver 검증 순서:

```text
1. body size 제한
2. timestamp 형식과 허용 window 확인
3. HMAC constant-time 검증
4. UTF-8/JSON parse
5. payload schema 확인
6. D1 durable acceptance
```

기본 replay window는 receiver 시각 기준 ±300초다. Sender host와 Cloudflare의 clock 차이가 이 범위를 넘으면 유효한 요청도 401로 거부된다.

인증 실패:

```text
401 malformed/missing/stale signature metadata
403 signature mismatch
```

Sender는 이를 permanent failure로 latch한다. Secret이나 clock을 수정한 운영자가 explicit permanent retry를 수행해야 한다.

현재는 active HMAC secret 하나만 지원한다. Rotation은 sender timer를 중지한 change window에서 receiver와 sender 값을 함께 교체한다.

## 6. Event schema version 1

예:

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

Required fields:

| Field | Type and rule |
|---|---|
| `schema_version` | integer `1` |
| `event_id` | non-empty string, 최대 255 characters |
| `event` | `ALERT`, `STATUS_CHANGE`, `RECOVERY`, `REMINDER` |
| `alert_required` | boolean `true` |
| `previous_status` | `null` 또는 valid status |
| `current_status` | `OK`, `WARNING`, `CRITICAL`, `UNKNOWN` |
| `checker_exit_code` | integer 또는 `null` |
| `state_file` | non-empty string; receiver local path가 아님 |
| `evaluated_at` | timezone 포함 ISO-8601 string |
| `checker_output.stdout` | string, empty 허용 |
| `checker_output.stderr` | string, empty 허용 |

같은 schema version에 추가된 field는 forward compatibility를 위해 허용하고 canonical payload hash에 포함한다.

새 schema version이 필요한 변경:

- Required field 삭제 또는 type 변경
- Event/status 의미 변경
- `event_id` idempotency 의미 변경
- HMAC version 또는 signed content 변경

## 7. Durable acceptance and idempotency

Receiver는 인증과 schema 검증을 통과한 payload를 deterministic key ordering의 canonical JSON으로 변환해 SHA-256 hash를 계산한다.

D1 table의 `event_id` primary key에 `INSERT OR IGNORE`를 사용하므로 Worker restart와 동시 duplicate에도 process-local memory가 아닌 durable unique claim을 사용한다.

### New event

```text
D1 state=accepted insert
-> Queue send가 disk에 기록됨
-> D1 state=queued 표시
-> HTTP 202
```

Queue send가 실패하면 D1의 `accepted` row를 유지하고 HTTP 503을 반환한다. Sender retry 또는 5분 scheduled reconciliation이 같은 event를 Queue에 다시 넣는다.

### Duplicate with same payload

```text
same event_id + same canonical payload hash
```

- 이미 Queue에 들어갔거나 최종 상태면 추가 publish 없이 204를 반환한다.
- D1 state가 아직 `accepted`면 Queue publish를 다시 시도한 후 204를 반환한다.
- 사용자-visible Slack side effect를 HTTP handler에서 직접 수행하지 않는다.

### Same ID with different payload

```text
HTTP 409 event_id_conflict
```

기존 row를 덮어쓰거나 새 Slack 메시지를 만들지 않는다. Sender는 permanent failure로 latch하고 운영자가 sender/receiver 기록을 대조한다.

## 8. Receiver HTTP response semantics

| Status | Meaning | Sender behavior |
|---:|---|---|
| `202` | New event가 D1과 Queue에 durable acceptance됨 | receipt 저장, failure-state 삭제, pending ACK |
| `204` | 동일 payload duplicate가 이미 durable하거나 재queue됨 | 동일하게 성공 처리 |
| `400` | JSON/payload/event ID contract 오류 | permanent latch |
| `401` | timestamp/signature metadata 오류 | permanent latch |
| `403` | HMAC mismatch | permanent latch |
| `409` | 같은 event ID의 다른 payload | permanent latch |
| `413` | 64 KiB 초과 | permanent latch |
| `415` | JSON Content-Type 아님 | permanent latch |
| `503` | D1/Queue/Worker가 durable acceptance를 확정하지 못함 | pending 유지, bounded retry |

Sender 공통 분류:

```text
retryable = 408, 429, 500-599, connection error, timeout
permanent = 3xx, 나머지 4xx, 그 밖의 non-2xx
success   = 200-299
```

Sender retryable backoff:

```text
5 -> 10 -> 20 -> 40 -> 80 -> 160 -> 300초 상한
```

Sender는 receiver의 `Retry-After`를 현재 해석하지 않는다.

## 9. Slack delivery state machine

Receiver D1 state:

```text
accepted
  -> queued
  -> delivering
     -> delivered
     -> queued             retryable Slack failure
     -> failed_permanent   non-retryable Slack response
     -> failed_exhausted   Queue retry budget exhausted
```

Queue consumer는 하나의 message씩 처리하고 event row를 30초 lease로 claim한다. Duplicate Queue message가 최종 상태를 만나면 Slack을 호출하지 않고 ACK한다.

Slack response 분류:

```text
success   = 200-299
retryable = 408, 429, 500-599, network error, 5초 timeout
permanent = 나머지 non-2xx
```

Retry delay:

```text
5 -> 10 -> 20 -> 40 -> 80 -> 160 -> 300초 상한
```

숫자 형식의 Slack `Retry-After`가 있으면 최대 86400초까지 우선 적용한다. Queue consumer `max_retries=10` 소진 후 DLQ consumer가 event를 `failed_exhausted`로 기록한다.

`failed_permanent`와 `failed_exhausted`는 자동으로 Slack에 다시 보내지 않는다. Slack credential 또는 원인을 수정한 뒤 D1 compare-and-set 재queue가 필요하다.

## 10. Timeout and crash windows

### Sender -> receiver timeout

```text
Receiver durable acceptance 완료
-> sender가 response를 받기 전에 timeout
-> sender가 같은 event_id retry
-> D1 duplicate가 204
```

이 경로는 Slack Queue publish를 중복할 수 있지만 D1 lease/final state가 동시에 같은 event를 전달하지 않게 한다.

### Receiver -> Slack timeout

Slack이 message를 저장했는지 receiver가 알 수 없다. 같은 Queue message retry에서 duplicate Slack message가 생길 수 있다.

Timeout 직후 sender pending, D1 row, Queue, failure state를 임의 삭제하지 않는다.

## 11. Payload retention and privacy

- D1은 async Slack delivery에 필요한 payload 원문을 보존한다.
- Slack delivery 성공 시 `payload_json='{}'`와 `payload_redacted_at`을 기록한다.
- Dedup용 `payload_hash`, event type/status, evaluated/accepted/delivered timestamp는 유지한다.
- 실패 event는 운영 재처리를 위해 payload를 유지한다.
- 자동 row deletion/retention job은 현재 없다.
- `checker_output`을 공개 Slack channel, issue, chat, screenshot에 복사하지 않는다.
- Slack app은 알림 전용 private channel에 최소 권한으로 설치한다.

## 12. Health and observability

```http
GET /health
```

HTTP 200:

```json
{
  "status": "ok",
  "failed_permanent": 0,
  "failed_exhausted": 0,
  "stale_in_flight": 0
}
```

다음 중 하나면 HTTP 503과 `status=degraded`다.

- `failed_permanent > 0`
- `failed_exhausted > 0`
- `accepted|queued|delivering` 상태가 600초 이상 갱신되지 않음
- D1 health query 자체가 실패함

Health response에는 event ID, payload, secret, Slack URL을 넣지 않는다. Primary Slack 경로와 독립된 UptimeRobot Free HTTP(S) monitor의 email notification 대상으로 사용한다. 무료 5분 interval을 사용하고 Slack alert contact는 연결하지 않는다.

Structured Worker log에는 event ID와 결과는 기록할 수 있지만 secret, URL, payload 원문은 기록하지 않는다.

## 13. Security operations

- Production은 HTTPS만 사용하고 TLS 검증을 끄지 않는다.
- HMAC secret, Slack URL, Worker secret file은 Git에 저장하지 않는다.
- Sender secret은 `/etc/aerotrace/notification.env` root:root mode 0600에 둔다.
- Receiver secret은 Cloudflare encrypted secret binding에 둔다.
- `.dev.vars`는 mode 0600과 Git ignore를 사용한다.
- URL이나 secret을 terminal argument와 shell history에 직접 쓰지 않는다.
- Secret rotation 전 sender timer를 중지해 mismatch permanent latch를 피한다.
- Worker endpoint나 secret이 바뀌면 synthetic event로 검증한 뒤 sender를 재개한다.

## 14. Acceptance status

Repository에서 완료:

```text
Python sender regression tests 10/10 PASS
Node receiver tests 14/14 PASS
HMAC exact-body/tamper/stale validation PASS
D1 duplicate/conflict state logic PASS
Queue publication failure durable recovery PASS
Slack success/retryable/permanent classification PASS
DLQ final state and final-state preservation PASS
scheduled reconciliation PASS
health degradation PASS
Wrangler 4.125.0 bundle dry-run PASS
fresh local D1 migration PASS
```

Production 전 남은 acceptance:

```text
Cloudflare remote deploy and migration
Slack private channel Incoming Webhook
isolated synthetic 202 and one Slack message
duplicate signed request and one Slack side effect
real timeout/429 or controlled failure injection
UptimeRobot /health email monitor
HMAC rotation rehearsal
sender local-file rollback rehearsal
```

완료 전 installed production runtime은 `local-file` 기준선을 유지한다.

## 15. Official references

- Cloudflare Wrangler configuration and automatic provisioning: <https://developers.cloudflare.com/workers/wrangler/configuration/>
- Cloudflare Worker secrets: <https://developers.cloudflare.com/workers/configuration/secrets/>
- Cloudflare D1 Worker binding API: <https://developers.cloudflare.com/d1/worker-api/>
- Cloudflare Queues JavaScript API: <https://developers.cloudflare.com/queues/configuration/javascript-apis/>
- Cloudflare Queue retries and DLQ: <https://developers.cloudflare.com/queues/configuration/batching-retries/>
- Slack Incoming Webhooks: <https://api.slack.com/messaging/webhooks>
- UptimeRobot Free plan: <https://help.uptimerobot.com/en/articles/11604710-who-should-use-uptimerobot-s-free-plan>
