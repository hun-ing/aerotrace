# AeroTrace Notification Operations Review

> 마지막 업데이트: 2026-08-25
> Activation: 2026-08-21 16:18 KST
> 범위: 첫 7일 daily health review와 첫 30일 SLI/Free tier calibration

이 문서는 측정 결과만 누적한다. Secret, endpoint, account ID, exact event ID와 payload 원문은 기록하지 않는다. 실행 절차와 장애 대응은 [operations runbook](OPERATIONS_RUNBOOK.md)을 따른다.

## 1. Review schedule

| Checkpoint | Due KST | 상태 |
|---|---|---|
| Activation baseline | 2026-08-21 | 완료 |
| D+1 | 2026-08-22 | 당시 별도 snapshot 없음 |
| D+2 | 2026-08-23 | 당시 별도 snapshot 없음 |
| D+3 | 2026-08-24 | 완료 — host/D1/SLI와 UptimeRobot Up 확인 |
| D+4 | 2026-08-25 | 완료 — host/D1/SLI, direct health와 UptimeRobot Up 확인 |
| D+5 | 2026-08-26 | 예정 |
| D+6 | 2026-08-27 | 예정 |
| D+7 / weekly review 1 | 2026-08-28 | 예정 |
| Weekly review 2 | 2026-09-04 | 예정 |
| Weekly review 3 | 2026-09-11 | 예정 |
| Weekly review 4 | 2026-09-18 | 예정 |
| 30-day SLO/retention review | 2026-09-20 | 예정 |

실행하지 않은 날짜를 사후 추정으로 PASS 처리하지 않는다. Systemd journal, D1 timestamp나 monitor history가 남아 있어도 “당일 snapshot”과 “사후 조사”를 구분한다.

## 2. Daily read-only procedure

### Host state

```bash
systemctl is-active \
  aerotrace-collector-queue-alert.timer \
  aerotrace-notification-outbox.timer

systemctl show aerotrace-notification-outbox.service \
  -p Result -p ExecMainCode -p ExecMainStatus

python3 scripts/check-notification-outbox.py \
  --outbox-dir /var/lib/aerotrace-monitoring/notification-outbox \
  --warn-count 5 \
  --critical-count 20 \
  --warn-age-sec 60 \
  --critical-age-sec 300

python3 scripts/check-notification-failure-state.py \
  --failure-state-file /var/lib/aerotrace-monitoring/notification-failure.json \
  --warn-count 4 \
  --critical-count 7 \
  --warn-duration-sec 60 \
  --critical-duration-sec 300
```

### Sender Boundary A

`receipt.delivered_at`은 receiver가 D1+Queue durable acceptance 후 반환한 2xx를 sender가 확인하고 receipt를 만든 시각이다.

```bash
python3 scripts/report-notification-sli.py \
  --outbox-dir /var/lib/aerotrace-monitoring/notification-outbox \
  --receipt-dir /var/lib/aerotrace-monitoring/notification-receipts \
  --activation-at 2026-08-21T07:18:00Z \
  --window-days 30 \
  --acceptance-target-sec 60 \
  --objective-percent 99
```

`status=NO_DATA`는 eligible production event가 0이라는 뜻이며 PASS나 100%가 아니다. `pending_with_receipt > 0`은 `ACK_EXISTING` crash-window recovery가 아직 남아 있음을 뜻한다.

### Receiver Boundary B

다음 package script는 tracked SQL에서 mutation keyword를 거부한 뒤 Wrangler `--command --json`으로 remote D1 SELECT만 실행한다.

```bash
cd /home/huning/aerotrace/receiver/cloudflare-slack
npm run sli:remote
```

`queue_durable_evidence_at`은 다음 순서의 첫 관측 시각이다.

```text
COALESCE(enqueued_at, last_attempt_at, delivered_at)
```

`enqueued_at`이 crash/race로 비어 있어도 Queue consumer가 event를 관측한 `last_attempt_at`은 durable acceptance evidence가 된다. 다만 Slack latency는 `delivered_at - enqueued_at`으로만 계산하며, `enqueued_at` 없는 event는 good으로 세지 않고 `missing_enqueued_at` measurement gap으로 조사한다.

### Public health fallback

```bash
curl -sS -w '\nhttp_status=%{http_code}\n' \
  https://<worker-host>/health
```

정상은 HTTP 200과 세 failure count 0이다. UptimeRobot UI에서 monitor가 `Up`, method가 `GET`, URL suffix가 `/health`인지 함께 확인한다.

## 3. Activation baseline — 2026-08-21

사용자가 직접 확인한 결과:

```text
notification timer=active
pending_events=0
active_failure=false
receiver /health=HTTP 200, all failure counts 0
isolated synthetic=202 and one Slack message
controlled production smoke=one receipt and one Slack message
D1 delivered rows=2
unredacted delivered rows=0
local-file rollback and Webhook restoration=PASS
UptimeRobot DOWN/UP email path=PASS
```

두 D1 row는 activation synthetic과 controlled smoke이며 final activation boundary 이전이다. Production SLI 분모에서 제외한다.

## 4. D+1 and D+2 — 2026-08-22~23

```text
daily snapshot=NOT_RECORDED
incident reported=no known report
```

Snapshot이 없으므로 timer, health와 SLI를 PASS로 소급 기록하지 않는다.

## 5. D+3 — 2026-08-24

검증 시각: 2026-08-24 오전 KST

Host와 deployment:

```text
collector alert timer=active
notification timer=active
latest notification service Result=success
latest notification service ExecMainStatus=0
installed Webhook unit matches repository=yes
pending_events=0
active_failure=false
Cloudflare production deployment traffic=100% current version
repository PR #1=merged
main push Actions run #9=sender/receiver jobs PASS
```

Sender Boundary A:

```text
status=NO_DATA
window_start=2026-08-21T07:18:00Z
eligible_events=0
accepted_events=0
pending_events=0
clock_anomaly_events=0
acceptance_compliance_percent=N/A
```

Receiver Boundary B:

```text
receiver_claim_rows=0
durably_accepted_events=0
missing_queue_durable_evidence=0
missing_enqueued_at=0
receiver_acceptance_crosscheck_good_events=0
delivered_events=0
failed_permanent_events=0
failed_exhausted_events=0
clock_anomaly_events=0
slack_delivery_compliance_percent=N/A
query rows written=0
```

전체 D1 data hygiene:

```text
delivery_state=delivered, rows=2
unredacted_delivered=0
database_size=0.04 MB
```

판정:

```text
host pipeline=OK
receiver durable state=OK
production SLI=NO_DATA
known incident=none
UptimeRobot current Up UI snapshot=confirmed by operator
direct /health HTTP snapshot=not separately captured
```

## 6. D+4 — 2026-08-25

검증 시각: 2026-08-25 08:46 KST부터 시작한 동일 점검 session

Host와 sender:

```text
collector alert timer=active
notification timer=active
latest notification service Result=success
latest notification service ExecMainCode=0
latest notification service ExecMainStatus=0
installed Webhook unit matches repository=yes
pending_events=0
pending_bytes=0
active_failure=false
failure_count=0
```

Sender Boundary A:

```text
status=NO_DATA
window_start=2026-08-21T07:18:00Z
window_end=2026-08-24T23:47:09.777357Z
eligible_events=0
accepted_events=0
pending_events=0
pending_with_receipt=0
good_acceptance_events=0
acceptance_miss_events=0
clock_anomaly_events=0
acceptance_compliance_percent=N/A
```

Receiver Boundary B:

```text
receiver_claim_rows=0
durably_accepted_events=0
missing_queue_durable_evidence=0
missing_enqueued_at=0
receiver_acceptance_crosscheck_good_events=0
delivered_events=0
slack_boundary_good_events=0
failed_permanent_events=0
failed_exhausted_events=0
clock_anomaly_events=0
slack_delivery_compliance_percent=N/A
query changes=0
query changed_db=false
query rows_written=0
```

전체 D1 data hygiene:

```text
total_rows=2
delivered_rows=2
failed_permanent_rows=0
failed_exhausted_rows=0
unredacted_delivered_rows=0
database_size_bytes=36864
aggregate query rows_written=0
```

Public health와 fallback:

```text
direct /health HTTP status=200
receiver status=ok
failed_permanent=0
failed_exhausted=0
stale_in_flight=0
UptimeRobot current status=Up, confirmed by operator
UptimeRobot URL suffix=/health, confirmed by operator
```

첫 remote SLI query는 Cloudflare API code `7403`으로 SQL 실행 전에 거부됐다. 동일 session에서 endpoint나 credential을 바꾸지 않고 Wrangler `whoami`의 OAuth login·D1 permission과 D1 목록 접근을 확인한 뒤 같은 tracked read-only wrapper를 한 번 재시도해 성공했다. 성공 query는 `changed_db=false`, `rows_written=0`이었다. 따라서 이를 receiver health failure나 D1 data failure로 분류하지 않고 transient CLI/API authorization response로 기록한다.

판정:

```text
host pipeline=OK
receiver durable state=OK
production SLI=NO_DATA
direct receiver health=OK
independent fallback monitor=OK
observed incident indicators=none
D+4 review=COMPLETE
```

## 7. Weekly and 30-day review fields

각 checkpoint에 다음을 기록한다.

```text
reviewed_at_utc=
reviewed_at_kst=
local_eligible_events=
local_good_acceptance_events=
local_acceptance_compliance_percent=
remote_durably_accepted_events=
remote_slack_good_events=
remote_slack_compliance_percent=
local_remote_event_count_gap=
failed_permanent_events=
failed_exhausted_events=
clock_anomaly_events=
duplicate_incidents=
UptimeRobot_down_events=
D1_database_size_mb=
D1_rows_read_period=
D1_rows_written_period=
Queue_operations_period=
retention_exception_count=
sample_size_interpretation=
decision=
```

Local accepted count와 remote durable accepted count가 다르면 event ID를 public log에 붙이지 않고 private incident record에서 대조한다.
