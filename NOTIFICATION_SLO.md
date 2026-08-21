# AeroTrace Notification SLO

> 마지막 업데이트: 2026-08-21
> 상태: 2026-08-21 production 활성, 첫 30일 calibration 진행
> 범위: notification event 생성부터 receiver durable acceptance와 Slack delivery까지

---

## 1. 상태와 적용 시점

Notification channel은 Slack, receiver는 Cloudflare Worker + D1 + Queue다. 이 문서의 수치는 초기 운영 기준이며 2026-08-21 16:18 KST의 최종 Webhook 재활성화 시각부터 적용한다.

```text
Cloudflare remote receiver acceptance PASS
Slack private channel synthetic PASS
UptimeRobot `/health` 이메일 monitor 활성
production sender가 Webhook으로 전환됨
activation timestamp 기록
```

현재 installed production transport는 `webhook`이다. 다만 activation synthetic과 controlled smoke는 production SLI에서 분리하며, eligible non-synthetic production event가 아직 없으면 compliance와 error budget을 `NO_DATA`로 기록한다. 과거 local-file receipt도 외부 notification 성공으로 계산하지 않는다.

## 2. 사용자 결과와 두 delivery 경계

```text
notification-required event 발생
-> receiver가 D1과 Queue에 durable acceptance
-> Slack channel에 운영자 메시지 전달
-> 상태 복구 시 RECOVERY 전달
```

비동기 receiver이므로 성공을 두 경계로 나눈다.

```text
Boundary A: event.evaluated_at -> receiver.accepted_at
Boundary B: receiver.accepted_at -> receiver.delivered_at
```

Sender receipt의 `delivered_at`은 receiver HTTP 2xx 확인 시각이다. Slack 표시 완료 시각이 아니므로 Boundary A의 proxy로만 사용한다.

## 3. 초기 SLO

| Objective | Target | Window | 상태 |
|---|---:|---|---|
| Receiver durable acceptance latency | unique event의 99%가 60초 이내 | rolling 30 days | active, calibrating |
| Slack delivery latency | durable accepted event의 99%가 추가 300초 이내 `delivered` | rolling 30 days | active, calibrating |
| ALERT/STATUS_CHANGE/RECOVERY/REMINDER | 같은 목표 적용 | rolling 30 days | active, calibrating |
| Receiver duplicate/conflict correctness | 같은 ID의 다른 payload를 0건 덮어씀 | continuous | invariant |
| 승인 없는 event loss | 0건 | continuous | invariant |
| Duplicate Slack side effect | 목표 0건 | continuous | best-effort invariant |

Slack provider가 `event_id` idempotency를 지원하지 않아 “duplicate Slack side effect 0”은 의도한 correctness 목표지만 exactly-once 보장은 아니다. 실제 duplicate는 latency error budget과 별도 incident로 기록한다.

## 4. SLI 정의

### SLI-1 — Receiver durable acceptance latency

```text
receiver.accepted_at - event.evaluated_at
```

분모는 `alert_required=true`로 outbox에 durable하게 생성된 unique `event_id`다.

Good event:

```text
60초 이내 receiver D1 insert와 Queue write 완료
```

다음은 good으로 계산하지 않는다.

- Local-file receipt
- Sender `DEFERRED_RETRYABLE_FAILURE`
- Sender `BLOCKED_PERMANENT_FAILURE`
- D1 insert 전에 반환된 잘못된 2xx
- 60초가 지난 뒤의 최종 성공

### SLI-2 — Slack delivery latency

```text
receiver.delivered_at - receiver.accepted_at
```

Good event는 300초 이내 Slack 2xx와 D1 `delivered` 갱신이 완료된 unique event다.

`failed_permanent`, `failed_exhausted`, 아직 queued/delivering인 event는 good이 아니다.

### SLI-3 — Pending age

```text
now - oldest sender outbox event.evaluated_at
```

Source:

```text
scripts/check-notification-outbox.py
oldest_pending_age_sec
```

File mtime을 사용하지 않는다.

### SLI-4 — Sender transport failure duration

```text
now - failure_state.first_failed_at
```

Source:

```text
scripts/check-notification-failure-state.py
failure_duration_sec
failure_count
last_failure_age_sec
```

Deferred timer invocation은 `failure_count`에 포함하지 않는다.

### SLI-5 — Receiver delivery state

D1 집계:

```text
accepted
queued
delivering
delivered
failed_permanent
failed_exhausted
```

`GET /health`는 최종 실패나 600초 이상 stale in-flight event를 HTTP 503으로 노출한다.

### SLI-6 — Pipeline liveness

```text
evaluator timer active
notification timer active
최근 oneshot invocation 성공
receiver /health HTTP 200
weekly synthetic event 202 + Slack 1회
```

Timer가 active라는 사실만으로 delivery 성공을 판단하지 않는다.

## 5. Thresholds

### Sender outbox

```text
warning  pending count >= 5 OR oldest age >= 60초
critical pending count >= 20 OR oldest age >= 300초
```

권장 checker invocation:

```bash
python3 scripts/check-notification-outbox.py \
  --outbox-dir /var/lib/aerotrace-monitoring/notification-outbox \
  --warn-count 5 \
  --critical-count 20 \
  --warn-age-sec 60 \
  --critical-age-sec 300
```

Count는 low-volume MVP의 초기값이다. 정상 event burst가 반복되면 age 기준은 유지하고 count만 측정 근거로 조정한다.

### Sender failure state

```text
warning  retryable count >= 4 OR duration >= 60초
critical retryable count >= 7 OR duration >= 300초
critical permanent failure는 즉시
```

권장 checker invocation:

```bash
python3 scripts/check-notification-failure-state.py \
  --failure-state-file /var/lib/aerotrace-monitoring/notification-failure.json \
  --warn-count 4 \
  --critical-count 7 \
  --warn-duration-sec 60 \
  --critical-duration-sec 300
```

`failure_count=4`는 backoff 하한상 약 35초 이후 도달할 수 있고, count 또는 duration 중 먼저 충족한 severity를 사용한다.

### Receiver

```text
warning  별도 상태 없음
critical /health HTTP 503 즉시
```

HTTP 503 조건:

```text
failed_permanent > 0
failed_exhausted > 0
stale_in_flight > 0 at 600초
D1 health query failure
```

UptimeRobot Free HTTP(S) monitor는 `/health`를 5분 간격으로 조회하고 primary Slack과 독립된 email로 503/timeout을 전달한다. Slack contact는 fallback에 연결하지 않는다.

## 6. Retry budgets

### Sender -> receiver

```text
retryable request count limit = 없음
retryable duration limit      = 없음
delay                         = 5, 10, 20, 40, 80, 160, 300초 상한
permanent automatic retry     = 없음
```

Sender는 local pending을 성공처럼 삭제하지 않고 endpoint가 회복될 때까지 보존한다. Unlimited retry는 무료 Cloudflare endpoint와 최대 300초 간격이라는 현재 조건에서 선택한 보존 정책이다.

### Receiver -> Slack

```text
Cloudflare Queue max_retries = 10
delay                        = 5, 10, 20, 40, 80, 160, 300초 상한
Retry-After                  = numeric value 우선, 최대 86400초
budget exhausted             = DLQ -> D1 failed_exhausted
permanent response           = D1 failed_permanent
```

Queue budget 소진은 event 삭제가 아니다. D1 payload와 실패 상태를 보존하고 `/health`를 503으로 만든다. 원인 수정 후 승인된 compare-and-set requeue가 필요하다.

Cloudflare의 `max_retries`와 per-message delay를 적용하면 Retry-After가 없는 지속 장애는 대략 20분 이상 후 exhaustion 구간에 들어간다. Provider 구현 세부에 의존하는 정확한 wall-clock을 SLO로 사용하지 않는다.

## 7. Error budget

Rolling 30-day receiver objective:

```text
eligible_events = 생성된 notification-required unique events
good_events     = 60초 이내 durable accepted unique events
compliance      = good_events / eligible_events
target          = 99%
```

Slack objective도 accepted event와 300초 이내 delivered event로 같은 방식으로 별도 계산한다.

Eligible event가 0이면 100%로 계산하지 않고 `NO_DATA`로 보고한다. Weekly synthetic은 liveness 근거지만 synthetic과 production event의 compliance를 보고서에서 구분한다.

다음은 error budget으로 상쇄하지 않는 correctness incident다.

- 승인 없는 pending/D1/Queue event 삭제
- 같은 event ID의 다른 payload 덮어쓰기
- Durable acceptance 전 2xx
- Secret 또는 payload 원문 노출
- Duplicate Slack message

Low-volume 상태에서는 한 event 실패가 비율을 크게 흔든다. 최초 30일은 목표 위반을 배포 중단 자동 조건으로 사용하지 않고 원인과 sample size를 함께 검토한다.

## 8. Ownership and response

현재는 단일 운영자 구조다.

| Role | Owner |
|---|---|
| Notification pipeline owner | AeroTrace service operator |
| Cloudflare receiver owner | AeroTrace service operator |
| Slack app/credential owner | AeroTrace service operator |
| Incident primary | AeroTrace service operator |
| Change approver | AeroTrace service operator |
| Incident secondary | 없음 — single-owner risk |

초기 응답 목표:

```text
/health critical acknowledgement = operator monitoring 시간 내 30분
permanent sender latch acknowledgement = 30분
critical recovery/approved mitigation = 4시간
```

이는 24x7 인력 SLA가 아니다. 운영자가 offline인 시간에는 best effort이며, 실제 팀 운영으로 전환할 때 primary/secondary schedule을 새로 채택해야 한다.

Primary notification은 Slack이다. Pipeline 자체 장애의 fallback은 `/health`를 감시하는 UptimeRobot email이다. 같은 Worker-to-Slack 경로를 fallback으로 사용하지 않는다.

## 9. Measurement and reporting

Sender sources:

```text
outbox event
delivery receipt
persistent failure-state
systemd journal
outbox/failure checker output
```

Receiver sources:

```text
D1 accepted_at, delivered_at, state, attempts
payload hash and event_id
Worker structured logs
/health aggregate
Cloudflare Queue/DLQ metrics
```

보고서 규칙:

- HTTP request count와 unique event count를 분리한다.
- Sender receipt와 Slack delivered timestamp를 혼동하지 않는다.
- Synthetic과 production event를 구분한다.
- Clock skew를 확인하지 않은 cross-host millisecond 차이를 정밀 latency로 해석하지 않는다.
- D1 redaction 후 payload 원문이 없다는 사실을 정상으로 처리한다.

## 10. Review cadence

Production activation 후:

```text
첫 7일  daily health/failure review
첫 30일 weekly SLI review
30일 후 objective/threshold 정식 review
이후 monthly SLO review
```

다음 조건에서는 즉시 재검토한다.

- Cloudflare/Slack provider 또는 pricing/limit 변경
- Sender timer/backoff 변경
- Event volume 급증
- Outbox head-of-line blocking
- Queue exhaustion 또는 permanent Slack failure
- Duplicate Slack message
- HMAC rotation incident
- `/health` false positive/negative
- Error budget 소진
- 다중 notification channel 또는 팀 on-call 도입

## 11. Activation gate 결과

2026-08-21 완료한 activation gate:

```text
Repository sender/receiver tests=PASS
Wrangler dry-run and local D1 migration=PASS
Cloudflare remote deploy/migration=PASS
Slack private channel isolated synthetic 202 + one message=PASS
UptimeRobot GET /health monitor + email DOWN/UP path=PASS
HMAC secret root-owned storage + sender/receiver exact match=PASS
sender threshold command=PASS
controlled production outbox -> receipt -> D1 delivered -> Slack one message=PASS
local-file rollback + Webhook restoration rehearsal=PASS
final activation timestamp=2026-08-21 16:18 KST
```

UptimeRobot DOWN/UP는 잘못된 root path의 404와 수정 후 `/health` 200, 그리고 built-in Test Notification으로 확인했다. D1 failure를 주입한 `/health` 503 live drill은 아니며 이 경계는 automated receiver test가 검증한다.

다음 항목은 문서와 automated test가 절차·상태 전이를 검증했지만 live production drill은 하지 않았다.

```text
exact duplicate signed replay and payload conflict
Slack 429/permanent response and DLQ exhaustion
real HMAC secret rotation
receiver failed-state compare-and-set requeue
ALERT -> RECOVERY pair
```

실제 secret rotation과 receiver requeue는 healthy production에 고의 mismatch나 failure row를 만들지 않고, 승인된 maintenance window 또는 실제 incident에서 수행한다.

현재 production 기준선:

```text
transport=webhook
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
pending_events=0
active_failure=false
/etc/aerotrace/notification.env=root:root 0600
fallback=UptimeRobot GET /health operator email
```
