CREATE TABLE notification_events (
    event_id TEXT NOT NULL PRIMARY KEY
        CHECK (length(event_id) BETWEEN 1 AND 255),
    payload_hash TEXT NOT NULL
        CHECK (length(payload_hash) = 64),
    payload_json TEXT NOT NULL,
    event_type TEXT NOT NULL,
    current_status TEXT NOT NULL,
    evaluated_at TEXT NOT NULL,
    delivery_state TEXT NOT NULL
        CHECK (
            delivery_state IN (
                'accepted',
                'queued',
                'delivering',
                'delivered',
                'failed_permanent',
                'failed_exhausted'
            )
        ),
    delivery_attempts INTEGER NOT NULL DEFAULT 0
        CHECK (delivery_attempts >= 0),
    accepted_at TEXT NOT NULL,
    enqueued_at TEXT,
    last_attempt_at TEXT,
    lease_expires_at TEXT,
    delivered_at TEXT,
    payload_redacted_at TEXT,
    last_http_status INTEGER,
    last_error TEXT,
    updated_at TEXT NOT NULL
);

CREATE INDEX notification_events_reconcile_idx
ON notification_events (
    delivery_state,
    updated_at
);
