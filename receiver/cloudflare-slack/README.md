# AeroTrace Cloudflare Slack Receiver

이 Worker는 AeroTrace Webhook event를 HMAC으로 검증하고 D1에 원자적으로 저장한 뒤 Cloudflare Queue를 통해 Slack Incoming Webhook으로 전달한다.

2026-08-21 현재 production sender가 이 receiver를 사용한다. Remote D1 migration, Queue/DLQ, private Slack Incoming Webhook, isolated synthetic, controlled production smoke, UptimeRobot health monitor와 sender rollback rehearsal을 완료했다.

## Architecture

```text
AeroTrace filesystem outbox
  -> signed HTTPS POST
  -> Cloudflare Worker
  -> D1 durable event_id claim
  -> Cloudflare Queue
  -> Slack Incoming Webhook
```

`POST /v1/notifications`의 2xx는 D1 insert와 Queue write가 끝났다는 뜻이다. Slack 화면 표시 완료를 뜻하지 않는다. Queue는 retryable Slack failure를 재시도하고, D1은 최종 결과를 보존한다.

## Guarantees and limits

- HMAC-SHA256은 timestamp와 exact request bytes를 함께 서명한다.
- 서명 timestamp 허용 오차는 기본 300초다.
- D1 primary key와 canonical payload hash로 중복과 conflict를 구분한다.
- 최초 durable acceptance는 `202`, 동일 payload duplicate는 `204`, 동일 ID의 다른 payload는 `409`다.
- Slack 전달은 at-least-once다. Slack 성공 후 D1 갱신 전 중단되면 사용자-visible duplicate가 생길 수 있다.
- Slack 408, 429, 5xx와 network/timeout은 Queue retry 대상이다.
- 그 밖의 Slack non-2xx는 `failed_permanent`다.
- retry limit 도달은 DLQ consumer가 `failed_exhausted`로 기록한다.
- 성공한 event의 payload 원문은 D1에서 `{}`로 redaction하고 hash와 metadata만 보존한다.
- 실패 event 원문은 운영 재처리를 위해 보존한다.

## Endpoints

```text
POST /v1/notifications
GET  /health
```

`/health`는 D1을 읽는다. `failed_permanent`, `failed_exhausted`, 10분 이상 갱신되지 않은 in-flight event 중 하나라도 있으면 payload나 event ID를 노출하지 않고 HTTP 503을 반환한다. 정상은 HTTP 200이다. Production에서는 UptimeRobot Free HTTP(S) monitor가 5분 간격으로 이 URL을 확인하고 독립 email로 알린다.

## Local verification

요구 사항은 Node.js 22 이상이다.

```bash
cd /home/huning/aerotrace/receiver/cloudflare-slack
npm ci
npm test
npm run check
npm run deploy:dry-run
npm run db:migrate:local
```

`npm run deploy:dry-run`과 local migration은 원격 Cloudflare resource를 생성하지 않는다.

## Secrets

두 secret은 서로 다른 목적이다.

```text
AEROTRACE_SIGNING_SECRET
  AeroTrace sender와 Worker가 공유하는 32-byte 이상의 random HMAC secret

SLACK_WEBHOOK_URL
  Slack app을 설치하며 발급된 Incoming Webhook URL
```

```bash
cd /home/huning/aerotrace/receiver/cloudflare-slack
cp .dev.vars.example .dev.vars
chmod 0600 .dev.vars
```

그 후 editor나 password manager를 사용해 placeholder를 실제 값으로 교체한다. `.dev.vars`는 Git에서 무시된다. 값 자체를 terminal 인자, shell history, issue, journal에 출력하지 않는다.

## Remote deployment — operator action only

아래 명령은 Cloudflare 계정의 Worker, D1, Queue를 생성하거나 변경한다. 최초 bootstrap은 완료됐으며, 새 환경을 만들거나 재배포할 때 계정과 Slack channel을 관리하는 운영자가 명시적으로 실행한다.

1. Slack app에서 Incoming Webhooks를 활성화하고 알림 전용 private channel에 app을 설치한다.
2. `.dev.vars`에 동일 HMAC secret과 Slack Webhook URL을 넣는다.
3. Cloudflare에 로그인하고 dry-run을 다시 확인한다. Browser가 없는 SSH host에서는 device flow를 사용한다.
4. Queue 목록에서 config가 참조하는 DLQ가 없으면 먼저 한 번 생성한다.
5. 두 secret을 code와 함께 한 번에 upload한다.
6. 자동 생성된 D1 ID가 `wrangler.jsonc`에 기록됐는지 검토한다.
7. Sender endpoint를 연결하기 전에 remote migration을 적용한다.

```bash
cd /home/huning/aerotrace/receiver/cloudflare-slack
npx wrangler login --device
npm run deploy:dry-run
npx wrangler queues list
```

`aerotrace-notification-delivery-dlq`가 목록에 없을 때만 다음을 실행한다. 이미 있으면 다시 만들지 않는다.

```bash
npx wrangler queues create aerotrace-notification-delivery-dlq
```

그다음 deploy와 migration을 수행한다.

```bash
npx wrangler deploy --secrets-file .dev.vars
npm run db:migrate:remote
```

Wrangler 4.125.0 최초 bootstrap에서는 D1과 primary delivery Queue를 자동 provision했지만 config가 consumer로 참조한 DLQ는 먼저 만들어야 했다. 최초 deploy와 migration 사이에는 data endpoint를 사용하지 않는다. 기존 production 재배포라면 migration compatibility와 sender 영향 범위를 먼저 검토한다.

Cloudflare가 자동으로 resource ID를 `wrangler.jsonc`에 추가하면 secret이 아닌지 확인한 뒤 repository에 반영한다. `.dev.vars`는 절대 stage하지 않는다.

## Isolated acceptance test

배포 출력의 Worker URL에 `/v1/notifications`를 붙인다.

```bash
cd /home/huning/aerotrace/receiver/cloudflare-slack
npm run synthetic -- https://<worker-host>/v1/notifications
```

성공 기준:

```text
receiver_status=202
synthetic_result=DURABLY_ACCEPTED
Slack에 같은 synthetic_event_id가 정확히 한 번 표시됨
GET /health가 HTTP 200과 status=ok 반환
```

Synthetic event는 production server outbox나 `/etc`를 변경하지 않는다.

그다음 UptimeRobot Free monitor를 추가한다.

```text
type=HTTP(s)
URL=https://<worker-host>/health
method=GET
interval=5 minutes
alert contact=operator email only
```

Slack을 이 monitor의 alert contact로 추가하면 primary와 fallback이 다시 같은 channel에 의존하므로 사용하지 않는다.

Activation에서는 `/health`를 빠뜨린 root URL의 404가 DOWN email로 감지됐고, 정확한 `/health`로 고친 뒤 recovery UP email을 받았다. Built-in Test Notification도 수신했다. D1 실패 상태를 일부러 만들어 `/health` 503을 발생시키지는 않았으며 해당 동작은 automated test가 검증한다.

## Read-only inspection

다음 명령은 secret이나 payload 원문을 출력하지 않는다.

```bash
npx wrangler d1 execute DB --remote --command \
  "SELECT delivery_state, COUNT(*) AS events FROM notification_events GROUP BY delivery_state ORDER BY delivery_state"

npx wrangler d1 execute DB --remote --command \
  "SELECT event_id, delivery_state, delivery_attempts, last_http_status, last_error, updated_at FROM notification_events WHERE delivery_state IN ('failed_permanent','failed_exhausted') ORDER BY updated_at"
```

두 번째 명령의 event ID도 운영 metadata다. 공개 issue나 chat에 그대로 붙이지 않는다.

## Retry and recovery

Receiver-side `failed_permanent`와 `failed_exhausted`는 sender failure latch와 별개다. Sender는 receiver의 202를 받은 뒤 local pending을 ACK했으므로, Slack 실패 event는 D1에서 원인을 수정하고 명시적으로 requeue해야 한다.

현재 release에는 public admin/retry endpoint가 없다. 임의 SQL로 상태를 바꾸기 전에 정확한 event ID, 현재 state, Slack credential 복구, payload 존재를 확인하고 change record를 남긴다. 구체적인 승인 절차와 compare-and-set SQL은 [root operations runbook](../../OPERATIONS_RUNBOOK.md)을 따른다.

## Rotation

HMAC secret은 단일 active value다. 무중단 dual-secret rotation은 아직 지원하지 않는다.

```text
1. AeroTrace notification timer 정지
2. Cloudflare AEROTRACE_SIGNING_SECRET 교체
3. /etc/aerotrace/notification.env의 sender secret 교체
4. 격리 synthetic 검증
5. timer 재시작
```

Slack Webhook URL을 rotation할 때는 Cloudflare secret을 먼저 바꾸고 synthetic 전달을 확인한다. 기존 failed event는 자동으로 permanent 재시도되지 않는다.

## Rollback

Receiver rollback은 Worker version rollback과 sender의 local-file 복원을 분리한다.

- Sender 전환 전 receiver 문제: production sender는 그대로 두고 Worker만 수정하거나 제거한다.
- Sender 전환 후 receiver 문제: 먼저 sender timer를 중지하고 root runbook의 local-file rollback 절차를 따른다.
- D1 event나 Queue를 삭제해 rollback하지 않는다. 미전달 증거와 dedup key가 사라진다.
- Slack Webhook과 HMAC secret은 incident 조사와 재처리가 끝나기 전에 폐기하지 않는다.
