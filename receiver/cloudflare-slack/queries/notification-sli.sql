-- Read-only rolling notification SLI query.
-- Production activation boundary: 2026-08-21 16:18 KST = 07:18 UTC.
WITH parameters AS (
    SELECT
        julianday('2026-08-21T07:18:00Z') AS activation_jd,
        julianday('now', '-30 days') AS rolling_window_jd,
        julianday('now') AS now_jd
),
window AS (
    SELECT
        CASE
            WHEN activation_jd > rolling_window_jd THEN activation_jd
            ELSE rolling_window_jd
        END AS start_jd,
        now_jd
    FROM parameters
),
eligible AS (
    SELECT
        delivery_state,
        evaluated_at,
        accepted_at,
        enqueued_at,
        last_attempt_at,
        delivered_at,
        window.now_jd AS reporting_now_jd,
        COALESCE(enqueued_at, last_attempt_at, delivered_at)
            AS queue_durable_evidence_at
    FROM notification_events, window
    WHERE event_id NOT LIKE 'synthetic-%'
      AND event_id NOT LIKE 'smoke-%'
      AND julianday(evaluated_at) >= window.start_jd
      AND julianday(evaluated_at) <= window.now_jd
),
measured AS (
    SELECT
        *,
        (
            julianday(queue_durable_evidence_at) - julianday(evaluated_at)
        ) * 86400.0
            AS queue_acceptance_latency_sec,
        (julianday(delivered_at) - julianday(enqueued_at)) * 86400.0
            AS slack_delivery_latency_sec
    FROM eligible
)
SELECT
    COUNT(*) AS receiver_claim_rows,
    COALESCE(SUM(queue_durable_evidence_at IS NOT NULL), 0)
        AS durably_accepted_events,
    COALESCE(SUM(queue_durable_evidence_at IS NULL), 0)
        AS missing_queue_durable_evidence,
    COALESCE(SUM(enqueued_at IS NULL), 0)
        AS missing_enqueued_at,
    COALESCE(SUM(
        queue_acceptance_latency_sec BETWEEN 0 AND 60
    ), 0) AS receiver_acceptance_crosscheck_good_events,
    COALESCE(SUM(delivery_state = 'delivered'), 0)
        AS delivered_events,
    COALESCE(SUM(
        delivery_state = 'delivered'
        AND enqueued_at IS NOT NULL
        AND slack_delivery_latency_sec BETWEEN 0 AND 300
    ), 0) AS slack_boundary_good_events,
    COALESCE(SUM(delivery_state = 'failed_permanent'), 0)
        AS failed_permanent_events,
    COALESCE(SUM(delivery_state = 'failed_exhausted'), 0)
        AS failed_exhausted_events,
    COALESCE(SUM(
        queue_acceptance_latency_sec < 0
        OR slack_delivery_latency_sec < 0
        OR julianday(queue_durable_evidence_at) > reporting_now_jd
        OR julianday(delivered_at) > reporting_now_jd
    ), 0) AS clock_anomaly_events,
    ROUND(
        100.0 * SUM(
            delivery_state = 'delivered'
            AND enqueued_at IS NOT NULL
            AND slack_delivery_latency_sec BETWEEN 0 AND 300
        ) / NULLIF(SUM(queue_durable_evidence_at IS NOT NULL), 0),
        3
    ) AS slack_delivery_compliance_percent
FROM measured;
