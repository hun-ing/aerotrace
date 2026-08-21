# AeroTrace Notification SLO

> 마지막 업데이트: 2026-08-21
> 상태: Draft — production Webhook endpoint와 운영 요구 확정 전 미채택
> 범위: Collector queue notification event 생성부터 receiver durable acceptance까지

---

## 1. 문서 상태

현재 production notification transport는 local-file이다. 실제 외부 receiver, 사용자 영향, provider rate limit과 incident owner가 정해지지 않았으므로 수치 SLO를 임의로 채택하지 않는다.

이 문서는 다음을 제공하는 결정 초안이다.

- SLI의 정확한 의미와 계산 기준
- 현재 retry/timer가 만드는 전달 시간 특성
- 확정해야 할 SLO와 threshold 항목
- Error budget과 incident ownership 틀
- Webhook production activation gate

`TBD` 값은 production 약속이 아니며 경보 configuration에 사용하지 않는다.

## 2. 사용자 관점의 목표

Notification pipeline의 목적은 Collector queue와 telemetry 전달 장애를 운영자가 충분히 빠르게 인지하고 복구할 수 있게 하는 것이다.

사용자 관점 결과:

```text
notification-required event 발생
→ 외부 receiver가 durable하게 수락
→ 운영자 channel에 중복 없이 전달
→ 상태 복구 시 RECOVERY 전달
```

단순히 evaluator timer가 성공했거나 Webhook adapter process가 exit code 0을 반환한 것은 사용자 결과 달성을 의미하지 않는다.

## 3. 범위와 경계

SLO 시작 경계:

```text
event.evaluated_at
```

이는 evaluator가 notification-required event를 결정한 시각이다.

SLO 종료 경계:

```text
receiver durable accepted_at
```

Receiver가 synchronous하게 side effect를 끝내는 경우 처리 완료 시각이고, async receiver인 경우 restart-safe durable queue에 event를 저장한 시각이다.

Sender-side 보조 종료 시각:

```text
receipt.delivered_at
```

이는 sender가 receiver HTTP 2xx를 확인하고 local receipt를 만든 시각이다. Receiver 내부의 최종 사용자 메시지 표시 시각과 같다고 가정하지 않는다.

범위에 포함:

- `ALERT`
- `STATUS_CHANGE`
- `RECOVERY`
- `REMINDER`
- Outbox 대기 시간
- Webhook retryable/permanent failure
- Receiver durable acceptance
- Duplicate suppression

범위 밖:

- Collector queue checker가 측정하는 telemetry 자체의 ingest latency
- 최종 사용자가 메시지를 실제로 읽은 시각
- Provider 자체의 화면 렌더링 지연
- Local-file transport를 외부 사용자 전달로 간주하는 것

## 4. Current Runtime Baseline

2026-08-21 현재:

```text
installed transport=local-file
notification timer=active (waiting)
pending_events=0
active_failure=false
/etc/aerotrace/notification.env 없음
```

따라서 아직 production Webhook SLI 데이터와 error budget consumption은 없다.

Local-file receipt는 pipeline wiring과 durable finalization 검증 근거이지만 외부 notification delivery SLO 성공으로 계산하지 않는다.

## 5. 현재 Delivery Mechanics

### Evaluator cadence

Repository timer:

```text
aerotrace-collector-queue-alert.timer
OnUnitInactiveSec=5s
```

Evaluator reminder interval:

```text
--repeat-after-sec 300
```

### Notification processor cadence

```text
aerotrace-notification-outbox.timer
OnUnitInactiveSec=5s
```

`OnUnitInactiveSec`이므로 정확한 fixed-rate 5초가 아니다. 이전 oneshot 종료 후 약 5초 뒤 다음 invocation이 예약되며 `AccuracySec=1s`, process 실행 시간과 scheduler 지연이 추가될 수 있다.

### Webhook request timeout

```text
--webhook-timeout-sec 5
```

### Retryable backoff

```text
failure_count 1  → 5초
failure_count 2  → 10초
failure_count 3  → 20초
failure_count 4  → 40초
failure_count 5  → 80초
failure_count 6  → 160초
failure_count 7+ → 300초
```

Retry deadline이 지난 뒤 다음 timer invocation에서 실제 요청하므로 각 지연에는 최대 약 한 timer cadence의 scheduling delay가 추가될 수 있다.

### Earliest retry timeline

Timer와 request 실행 시간을 제외한 이론상 가장 빠른 누적 retry 시각:

```text
첫 실패             T+0초
두 번째 실제 요청   T+5초 이상
세 번째 실제 요청   T+15초 이상
네 번째 실제 요청   T+35초 이상
다섯 번째 실제 요청 T+75초 이상
여섯 번째 실제 요청 T+155초 이상
일곱 번째 실제 요청 T+315초 이상
여덟 번째 실제 요청 T+615초 이상
이후                최소 300초 간격
```

이는 delivery SLO가 아니다. 현재 retry algorithm의 하한 설명이다.

### Permanent failure

Permanent HTTP failure는 첫 실제 요청 후 latch되며 자동 재전송하지 않는다. 복구 시간은 endpoint 원인 수정과 운영자 explicit retry에 의존한다.

따라서 permanent failure의 MTTR 목표와 on-call ownership 없이는 end-to-end notification SLO를 채택할 수 없다.

## 6. SLI 정의

### SLI-1 — End-to-End Durable Delivery Latency

정의:

```text
receiver durable accepted_at
- event.evaluated_at
```

집계 단위:

```text
unique event_id
```

권장 percentile:

```text
p50
p95
p99
maximum
```

Receiver timestamp를 사용할 수 없으면 `receipt.delivered_at`을 sender-side proxy로 사용하되 dashboard와 report에 proxy임을 명시한다.

### SLI-2 — Delivery Within Objective Ratio

정의:

```text
objective 시간 안에 durable acceptance된 unique event 수
/
생성된 notification-required unique event 수
```

분모에는 `alert_required=true`로 outbox에 durable하게 기록된 event만 포함한다.

다음은 성공으로 계산하지 않는다.

- `DEFERRED_RETRYABLE_FAILURE`
- `BLOCKED_PERMANENT_FAILURE`
- Local-file receipt
- Receiver가 durable acceptance 전에 반환한 잘못된 2xx
- 같은 event ID의 duplicate side effect

### SLI-3 — Pending Age

정의:

```text
now - oldest pending event.evaluated_at
```

Source:

```text
scripts/check-notification-outbox.py
oldest_pending_age_sec
```

File mtime을 기준으로 사용하지 않는다.

### SLI-4 — Active Transport Failure Duration

정의:

```text
now - failure_state.first_failed_at
```

Source:

```text
scripts/check-notification-failure-state.py
failure_duration_sec
```

`event.evaluated_at`부터 첫 실제 Webhook failure 전까지의 시간은 포함하지 않는다. Pending age와 함께 봐야 사용자 영향 전체 시간을 알 수 있다.

### SLI-5 — Retry Progress

확인 값:

```text
failure_count
last_failure_age_sec
retry_after_sec
```

의미:

- `failure_count`는 실제 HTTP/network failure 횟수다.
- Deferred timer invocation은 count를 증가시키지 않는다.
- `last_failure_age_sec`가 계속 증가하고 timer가 비활성이라면 retry worker liveness 문제일 수 있다.
- `retry_after_sec`은 adapter의 deferred invocation journal에서 확인한다.

### SLI-6 — Receiver Duplicate Side Effects

정의:

```text
동일 event_id에서 발생한 추가 사용자-visible side effect 수
```

Correctness target:

```text
0
```

중복 HTTP request 수는 0일 필요가 없다. Timeout과 retry 때문에 request duplicate는 정상적으로 발생할 수 있으며, receiver side effect가 중복되지 않는 것이 목표다.

### SLI-7 — Pipeline Liveness

확인 대상:

```text
evaluator timer active
notification timer active
최근 oneshot invocation 존재
outbox/failure-state checker가 UNKNOWN이 아님
```

Timer가 active라는 사실만으로 delivery 성공을 판단하지 않는다.

## 7. 채택 전 결정해야 할 SLO

다음 표의 `TBD`를 endpoint owner와 운영 요구에 근거해 확정해야 한다.

| 항목 | 정의 | 값 | 현재 상태 |
|---|---|---:|---|
| ALERT durable delivery | `evaluated_at`부터 receiver durable acceptance | TBD | 미채택 |
| STATUS_CHANGE durable delivery | 동일 | TBD | 미채택 |
| RECOVERY durable delivery | 동일 | TBD | 미채택 |
| REMINDER durable delivery | 동일 | TBD | 미채택 |
| Monthly delivery-within-objective ratio | unique event 기준 | TBD | 미채택 |
| Maximum allowed oldest pending age | Outbox age | TBD | 미채택 |
| Retryable failure warning duration | Transport failure duration | TBD | 미채택 |
| Retryable failure critical duration | Transport failure duration | TBD | 미채택 |
| Permanent failure response time | 최초 latch부터 owner 확인 | TBD | 미채택 |
| Permanent failure recovery time | 최초 latch부터 성공/승인된 종료 | TBD | 미채택 |
| Duplicate user-visible side effects | 동일 event ID | 0 | Contract invariant |

Event 종류별 목표가 같다면 하나의 공통 delivery objective로 합칠 수 있다. 다르게 설정한다면 ALERT/RECOVERY 우선순위 차이와 그 이유를 decision record에 남긴다.

## 8. Threshold Mapping

### Outbox checker

지원 옵션:

```text
--warn-count
--critical-count
--warn-age-sec
--critical-age-sec
```

### Failure-state checker

지원 옵션:

```text
--warn-count
--critical-count
--warn-duration-sec
--critical-duration-sec
```

Permanent failure는 threshold와 관계없이 checker에서 CRITICAL이다.

Retryable failure는 threshold를 지정하지 않으면 `active_failure=true`여도 `status=OK`일 수 있다.

현재 production에는 notification outbox/failure checker threshold를 주기적으로 평가해 별도 외부 channel로 보내는 자동 경보 경로가 없다. Threshold 값만 문서에 정하고 실행 주체를 만들지 않으면 실제 경보가 되지 않는다.

### Threshold 관계

채택 시 다음 관계를 만족시킨다.

```text
warn age < critical age
warn duration < critical duration
critical threshold <= 사용자가 허용한 최대 영향 시간
```

300초 maximum backoff는 장애가 길어졌을 때 실제 retry 사이에 최소 5분이 필요하다는 뜻이다. Critical delivery objective가 이보다 짧다면 현재 backoff나 별도 escalation 경로를 조정해야 한다.

Count threshold는 예상 event volume과 event 평균 크기를 측정한 뒤 정한다. 임의의 pending count를 severity로 채택하지 않는다.

## 9. Error Budget 정의 초안

Delivery objective와 monthly ratio가 확정되면 다음과 같이 계산한다.

```text
eligible_events
= 해당 기간 생성된 notification-required unique event

good_events
= objective 시간 안에 durable acceptance된 unique event

bad_events
= eligible_events - good_events

compliance
= good_events / eligible_events
```

기간 내 eligible event가 0이면 compliance를 100%로 임의 계산하지 않는다. `NO_DATA`로 보고 pipeline synthetic check 또는 liveness SLI로 보완한다.

다음은 별도 correctness violation으로 기록한다.

- Duplicate user-visible side effect
- 서로 다른 payload가 같은 event ID로 처리됨
- Receiver의 non-durable 2xx로 event 유실
- Pending event 또는 failure-state의 승인 없는 삭제

Correctness violation을 단순 latency error budget으로 상쇄하지 않는다.

## 10. Retry Budget 결정

현재 retryable failure는 총 횟수나 총 기간 제한 없이 300초 상한 간격으로 자동 재시도한다.

현재 정책:

```text
retryable retry count limit=없음
retryable retry duration limit=없음
maximum retry interval=300초
permanent automatic retry=없음
```

Production endpoint 연결 전에 다음을 결정한다.

- Provider rate limit과 요청 비용
- 최대 자동 retry 기간
- 최대 실제 request 횟수
- Retry budget 소진 후 pending 보존 방식
- Dead-letter 또는 quarantine 전환 여부
- Budget 소진 시 운영자 escalation
- 장기 장애 중 REMINDER event 증가 처리

Retry budget을 추가하더라도 미전달 event를 성공처럼 pending에서 제거하지 않는다.

## 11. Incident Ownership

다음 owner를 확정하기 전에는 permanent failure 수동 retry를 production 운영 절차로 활성화하지 않는다.

| 역할 | 책임 | 담당 |
|---|---|---|
| Notification pipeline owner | Sender, outbox, systemd, failure-state | TBD |
| Receiver owner | Endpoint, dedup, durable acceptance | TBD |
| Incident primary | 최초 확인과 triage | TBD |
| Incident secondary | 장기 장애 escalation | TBD |
| Credential owner | 발급, rotation, 폐기 | TBD |
| Change approver | Webhook activation/rollback 승인 | TBD |

필수 escalation channel과 응답 시간도 함께 정한다.

```text
Primary channel=TBD
Fallback channel=TBD
Permanent failure acknowledgement time=TBD
Retryable critical escalation time=TBD
```

Notification pipeline 자체 장애를 동일 Webhook 하나로만 알리면 receiver 장애 중 경보도 전달되지 않는다. 독립된 fallback channel 또는 host monitoring 경로가 필요하다.

## 12. Measurement와 Reporting

### Sender source

```text
notification outbox event
delivery receipt
persistent failure-state
systemd journal
outbox checker output
failure-state checker output
```

### Receiver source

```text
durable accepted_at
event_id
payload hash
dedup result
HTTP status
processing duration
user-visible side effect result
```

### Correlation

Sender와 receiver record는 `event_id`로 연결한다.

보고서에는 HTTP request count와 unique event count를 구분한다. Retry와 duplicate request를 unique notification 성공 수로 중복 계산하지 않는다.

### Clock

Evaluator, sender host와 receiver는 신뢰할 수 있는 time synchronization을 사용한다. Clock skew를 측정하지 않은 상태에서 cross-host timestamp latency를 고정밀 값으로 해석하지 않는다.

## 13. SLO 채택 절차

1. Receiver/provider와 owner를 선택한다.
2. [WEBHOOK_RECEIVER_CONTRACT.md](WEBHOOK_RECEIVER_CONTRACT.md)의 acceptance test를 통과한다.
3. Authentication과 credential rotation 방식을 확정한다.
4. 정상 event volume과 receiver processing latency를 측정한다.
5. 운영자가 허용 가능한 ALERT/RECOVERY 지연을 결정한다.
6. Retryable/permanent incident response 시간을 결정한다.
7. 이 문서의 `TBD` 값을 채우고 decision review를 받는다.
8. Checker threshold와 실행 주체를 repository configuration에 반영한다.
9. Synthetic notification으로 monitoring liveness를 검증한다.
10. 승인된 change window에서 Webhook transport를 활성화한다.
11. 초기 관찰 기간 후 실제 SLI로 목표와 threshold를 재검토한다.

## 14. Production Activation Gate

다음 항목이 모두 충족돼야 한다.

```text
Receiver contract acceptance test PASS
Receiver durable event_id deduplication PASS
Authentication과 secret rotation 확정
ALERT/RECOVERY delivery objective 채택
Outbox/failure threshold 채택
Threshold 실행과 escalation 경로 검증
Incident primary/secondary 지정
Retry budget 결정
Rollback plan 검증
Synthetic end-to-end notification PASS
```

완료 전에는 installed production runtime을 local-file 기준선으로 유지한다.

## 15. 재검토 조건

- Receiver/provider 변경
- 인증 방식 변경
- Timer cadence 또는 backoff 변경
- Notification event volume의 유의미한 변화
- Outbox head-of-line blocking 사고
- Duplicate user-visible notification 발생
- Timeout ambiguous delivery 사고
- Permanent failure MTTR 목표 미달
- Error budget 소진
- Dead-letter/quarantine 도입
- 다중 notification channel 도입
