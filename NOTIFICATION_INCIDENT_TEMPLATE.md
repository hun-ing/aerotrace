# AeroTrace Notification Incident Template

> 마지막 업데이트: 2026-08-24
> 사용 범위: Sender outbox, HMAC Webhook, Cloudflare receiver, Queue/DLQ, Slack과 health fallback 사고

이 문서는 사고마다 복사해 private incident record로 사용한다. Secret, Webhook URL, payload 원문과 민감한 checker output은 public issue나 이 repository에 기록하지 않는다. Public postmortem에는 event ID와 운영자 식별 정보도 필요한 만큼 redaction한다.

관련 절차:

- [Notification Operations Runbook](OPERATIONS_RUNBOOK.md)
- [Notification SLO](NOTIFICATION_SLO.md)
- [Webhook Receiver Contract](WEBHOOK_RECEIVER_CONTRACT.md)
- [Notification Data Retention Policy](DATA_RETENTION_POLICY.md)

---

## 1. Incident metadata

```text
incident_id=
title=
status=investigating|identified|monitoring|resolved
severity=SEV-1|SEV-2|SEV-3
detected_at_utc=
detected_at_kst=
resolved_at_utc=
resolved_at_kst=
detection_source=Slack|UptimeRobot|sender-checker|operator|other
incident_primary=AeroTrace service operator
change_approver=AeroTrace service operator
secondary_owner=none
record_visibility=private
```

Severity 기준:

```text
SEV-1
  승인 없는 event loss, secret/payload 노출, 지속적인 duplicate storm,
  notification 전체 경로 장기 중단 또는 잘못된 RECOVERY

SEV-2
  sender critical threshold, receiver /health 503,
  failed_permanent/failed_exhausted, 300초 초과 delivery

SEV-3
  warning threshold, 단일 synthetic 실패, 짧은 지연,
  사용자 영향 없는 설정 또는 문서 오류
```

## 2. Detection and impact

```text
first_signal=
affected_boundary=sender->receiver|receiver->Slack|health-fallback|multiple
affected_event_count=
affected_event_types=
oldest_affected_evaluated_at=
last_known_good_at=
operator_notification_received=yes|no|unknown
production_user_impact=
synthetic_or_smoke_only=yes|no
```

확인 질문:

- 실제 production event인가, synthetic/smoke인가?
- ALERT만 누락됐는가, RECOVERY도 누락됐는가?
- Receiver 2xx 이후 Slack에서 실패했는가?
- Timeout 때문에 receiver acceptance 여부가 모호한가?
- 같은 event가 Slack에 이미 표시됐을 가능성이 있는가?

## 3. Initial safety snapshot

Mutation이나 재시도 전에 다음 값을 기록한다.

```text
evaluator_timer_active=
notification_timer_active=
notification_service_result=
notification_service_exec_main_status=
pending_events=
oldest_pending_age_sec=
active_failure=
failure_kind=
failure_reason=
failure_count=
failure_duration_sec=
receiver_health_http_status=
receiver_failed_permanent=
receiver_failed_exhausted=
receiver_stale_in_flight=
```

Event ID는 private record에서만 다음 형식으로 기록한다.

```text
sender_event_id=
receipt_present=yes|no
receiver_row_present=yes|no
receiver_delivery_state=
receiver_delivery_attempts=
receiver_last_http_status=
payload_redacted=yes|no
slack_message_observed=yes|no|unknown
duplicate_count=0|number|unknown
```

## 4. Timeline

모든 항목에 UTC를 우선 기록하고 필요하면 KST를 병기한다.

| Time UTC | Time KST | Actor | Observation or action | Result/evidence |
|---|---|---|---|---|
|  |  |  |  |  |

관측과 변경을 구분한다. 추정 시각에는 `inferred`를 표시한다.

## 5. Diagnosis

```text
confirmed_failure_domain=sender|network|Worker|D1|Queue|Slack|UptimeRobot|unknown
trigger=
root_cause=
contributing_factors=
why_automatic_recovery_did_or_did_not_work=
head_of_line_blocking=yes|no
timeout_ambiguity=yes|no
```

다음 상태를 혼동하지 않는다.

```text
sender receipt
  receiver가 D1+Queue durable acceptance를 2xx로 확인했다는 의미

D1 delivered
  Slack 2xx 이후 receiver final state가 저장됐다는 의미

Slack message observed
  운영자가 channel side effect를 확인했다는 의미
```

## 6. Containment and recovery authorization

```text
timer_stop_required=yes|no
local_file_rollback_required=yes|no
sender_permanent_retry_required=yes|no
receiver_requeue_required=yes|no
secret_rotation_required=yes|no
approved_by=
approved_at_utc=
```

수행 전 확인:

- Pending과 receipt를 삭제하지 않았다.
- Receiver final failure payload가 아직 redaction되지 않았다.
- Slack에 이미 표시됐을 가능성을 검토했다.
- Exact event ID와 compare-and-set 조건을 확인했다.
- Secret을 command argument, journal, issue에 노출하지 않는다.
- Rollback unit이 `--transport local-file`과 `AF_UNIX` 기준선인지 확인했다.

실제 명령은 이 문서에 복사하지 않고 runbook의 승인 절차를 따른다.

## 7. Recovery verification

```text
evaluator_timer_active=yes|no
notification_timer_active=yes|no
latest_service_success=yes|no
pending_events_final=
active_failure_final=
expected_receipt_present=yes|no
receiver_final_state=
receiver_health_http_status_final=
slack_expected_message_count=
required_recovery_message_received=yes|no|not_applicable
duplicate_delivery_observed=yes|no
```

종료 조건은 runbook의 `Incident 종료 조건`을 모두 만족해야 한다. 원인이 설명된 승인된 pending이나 final failure가 남아 있으면 incident를 resolved로 닫지 않는다.

## 8. SLO impact

```text
eligible_production_events=
receiver_boundary_good_events=
receiver_boundary_miss_events=
slack_boundary_good_events=
slack_boundary_miss_events=
maximum_receiver_acceptance_latency_sec=
maximum_slack_delivery_latency_sec=
correctness_incident=yes|no
error_budget_note=
```

Synthetic과 controlled smoke는 production compliance 분모에서 제외한다. Event loss, payload/secret 노출, conflict overwrite와 duplicate Slack side effect는 latency error budget과 별도 correctness incident로 기록한다.

## 9. Data handling

```text
failed_payload_still_required_for_requeue=yes|no
legal_or_incident_hold=yes|no
payload_redaction_completed_at=
metadata_purge_due_at=
private_evidence_location=
```

Failed payload 보존과 redaction은 retention policy를 따른다. Public postmortem에는 raw payload, Slack URL, HMAC signature/secret, Worker account 정보와 전체 event ID를 넣지 않는다.

## 10. Follow-up actions

| Action | Owner | Due date | Evidence | Status |
|---|---|---|---|---|
|  |  |  |  | open |

필수 검토 항목:

- Regression test가 이 failure mode를 재현하는가?
- Runbook command나 closure condition이 부족했는가?
- Threshold 또는 retry budget을 바꿔야 하는가?
- Data retention 예외가 생겼는가?
- Secondary owner가 없어 복구가 지연됐는가?
- 같은 사고가 primary Slack과 email fallback을 동시에 막을 수 있는가?

## 11. Closure summary

```text
customer_or_operator_impact=
root_cause_one_sentence=
recovery_one_sentence=
corrective_action_one_sentence=
incident_duration_sec=
final_status=resolved
closed_by=
closed_at_utc=
```
