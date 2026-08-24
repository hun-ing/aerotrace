# AeroTrace Notification Data Retention Policy

> 마지막 업데이트: 2026-08-24
> 상태: Production 초기 정책, 자동 purge 도입 전 manual enforcement
> 첫 정식 검토일: 2026-09-20 KST

## 1. 목적과 범위

이 문서는 notification pipeline이 만드는 sender outbox, receipt, failure-state, Cloudflare D1 row, Queue/DLQ message, Worker log, Slack message와 incident evidence의 보존·redaction 원칙을 정한다.

Telemetry trace 본문의 30일 retention은 별도 데이터 수명주기 결정이며 이 문서의 범위가 아니다.

관련 문서:

- [Notification SLO](NOTIFICATION_SLO.md)
- [Webhook Receiver Contract](WEBHOOK_RECEIVER_CONTRACT.md)
- [Notification Operations Runbook](OPERATIONS_RUNBOOK.md)
- [Notification Incident Template](NOTIFICATION_INCIDENT_TEMPLATE.md)

## 2. 데이터 최소화 원칙

- Delivery와 incident 복구에 필요하지 않은 payload를 장기 보존하지 않는다.
- Secret, HMAC signature, Slack Webhook URL과 environment file 원문을 운영 evidence로 복사하지 않는다.
- Delivered event의 payload 원문은 Slack 2xx와 D1 finalization 직후 redaction한다.
- Event ID, canonical payload hash, event/status, timestamp와 delivery state는 dedup과 SLO 근거로 payload보다 오래 보존할 수 있다.
- Pending 또는 unresolved failure evidence를 단순 TTL 때문에 자동 삭제하지 않는다.

## 3. 보존 기준

| Data | Current handling | Retention target | Deletion/redaction condition |
|---|---|---:|---|
| Sender pending outbox event | Full event payload | 성공 또는 승인된 disposition까지 | Receipt durable 저장 또는 incident 승인 없이는 삭제 금지 |
| Sender Webhook failure-state | Event ID와 failure metadata | 복구 완료까지 | 성공 finalization 뒤 processor가 삭제 |
| Sender delivery receipt 원문 | 현재 full event payload와 receiver acceptance proxy timestamp | 최대 30일 목표 | Pending/`ACK_EXISTING` 필요와 incident hold가 없으면 checker output·state file 최소화 |
| Sender delivery receipt metadata | Event ID, evaluated/delivered timestamp, transport | 90일 목표 | Rolling SLO와 incident hold 종료 후 purge |
| D1 delivered payload | `payload_json='{}'` | 즉시 redaction | Slack 2xx와 D1 `delivered` update 시 자동 |
| D1 failed payload | Requeue용 full payload | 최대 30일 | Incident 해결 즉시 redaction, 미해결 30일 도달 시 private evidence 보존 후 disposition 승인 |
| D1 delivery metadata/hash | Dedup과 SLI metadata | 90일 | Rolling 30일 SLO와 incident hold 종료 후 purge |
| Synthetic/smoke metadata | Liveness evidence | 30일 | Production SLI와 분리 후 purge |
| Queue/DLQ message | Cloudflare 관리 | Free plan 24시간 | Provider retention; 장기 evidence로 사용하지 않음 |
| Worker logs | Payload/secret 제외 structured metadata | Provider plan 범위 | Provider retention을 따름 |
| Slack message | Channel-visible formatted payload | Workspace 정책 | Workspace owner 정책과 incident hold를 따름 |
| UptimeRobot/email evidence | Health status와 timestamp | 90일 권장 | Mailbox/monitor 정책에 따라 삭제 |
| Private incident record | Redacted operational evidence | 1년 권장 | Security/legal hold가 없고 follow-up 종료 후 삭제 |

`90일`과 `1년`은 초기 low-volume 운영 목표다. 현재 자동 purge job은 없으므로 “설정됨”이 아니라 operator review target이다.

## 4. Delivered payload

Receiver는 Slack delivery 성공 시 다음 필드를 보존한다.

```text
event_id
payload_hash
event_type
current_status
evaluated_at
accepted_at
enqueued_at
delivered_at
delivery_attempts
last_http_status
payload_redacted_at
```

다음 원문은 D1에서 제거한다.

```text
checker_output
state_file
전체 event payload JSON
```

`payload_json <> '{}'`인 delivered row는 policy violation이며 incident로 조사한다.

## 5. Failed payload

`failed_permanent`와 `failed_exhausted` payload는 승인된 requeue에 필요할 수 있어 자동으로 즉시 redaction하지 않는다.

처리 순서:

1. Failure를 incident record에 등록한다.
2. Slack credential/provider 원인을 수정한다.
3. Duplicate 가능성을 검토하고 승인된 requeue 또는 no-retry disposition을 정한다.
4. Delivered가 되면 정상 redaction을 확인한다.
5. 재전송하지 않기로 하면 필요한 최소 evidence만 private record에 남기고 D1 payload를 redaction한다.

30일을 넘겨 full failed payload를 유지하려면 incident hold 이유, owner와 다음 검토일을 private record에 남긴다. Public repository에는 payload나 exact event ID를 기록하지 않는다.

## 6. SLO와 retention window

Rolling 30-day SLO를 계산하려면 eligible production event의 sender receipt metadata와 D1 delivery metadata가 최소 30일 필요하다. Checker output과 state file 원문은 SLO 계산에 필요하지 않다. Receipt 최소화 기능은 아직 구현되지 않았으므로 첫 30일 review에서 schema compatibility와 `ACK_EXISTING` 안전성을 확인한 뒤 별도 tracked change로 만든다. Purge는 다음 순서를 지킨다.

```text
SLO report 생성
-> eligible/good/miss count와 clock anomaly 확인
-> incident hold 확인
-> dry-run purge 대상 count 검토
-> 승인된 apply
-> 삭제/redaction count 기록
```

Activation synthetic과 controlled smoke는 2026-08-21 16:18 KST activation boundary 이전 data다. 이후 weekly synthetic은 `synthetic-` event ID prefix로 production SLI에서 제외한다.

## 7. Queue retention 한계

Cloudflare Free Queue는 10,000 standard operations/day included이며 정상 message 한 건도 보통 write/read/delete 세 operation을 사용한다. Message retention은 24시간으로 고정된다.

따라서 DLQ message를 장기 incident evidence로 간주하지 않는다. `failed_exhausted` D1 row, health aggregate와 private incident timeline을 evidence로 사용한다.

## 8. Purge 안전 조건

자동 또는 수동 purge 구현은 다음 조건을 모두 만족해야 한다.

- 기본 동작은 dry-run이다.
- Exact cutoff와 대상 state별 count를 먼저 출력한다.
- Pending outbox와 active failure-state는 대상에서 제외한다.
- Unresolved incident/legal/security hold를 제외한다.
- Delivered payload redaction과 metadata row deletion을 구분한다.
- Compare-and-set 또는 state 조건 없이 remote D1을 변경하지 않는다.
- 삭제 결과와 operator approval을 engineering/incident record에 남긴다.
- Backup/export에 payload를 포함했다면 동일한 보존 만료일과 접근 제한을 적용한다.

Codex는 production purge, remote D1 UPDATE/DELETE와 receipt 삭제를 자동 수행하지 않는다. 대상과 evidence를 읽기 전용으로 산출한 뒤 운영자가 승인한다.

## 9. 30일 검토 항목

2026-09-20 KST에 다음을 검토한다.

```text
eligible production event count
sender receipt bytes and count
D1 row/storage/rows-read/rows-written usage
synthetic versus production ratio
failed payload maximum age
incident hold count
90-day metadata target의 비용 적합성
purge automation 필요성
Slack/email workspace retention 적합성
```

Event volume이 너무 적으면 retention을 확정한 것처럼 과장하지 않고 `NO_DATA`와 sample size를 함께 기록한다.
