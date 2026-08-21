function changes(result) {
  return Number(result?.meta?.changes ?? 0);
}

function mapRow(row) {
  if (row === null) {
    return null;
  }

  return {
    eventId: row.event_id,
    payloadHash: row.payload_hash,
    payloadJson: row.payload_json,
    payload: JSON.parse(row.payload_json),
    eventType: row.event_type,
    currentStatus: row.current_status,
    evaluatedAt: row.evaluated_at,
    deliveryState: row.delivery_state,
    deliveryAttempts: row.delivery_attempts,
    acceptedAt: row.accepted_at,
    enqueuedAt: row.enqueued_at,
    lastAttemptAt: row.last_attempt_at,
    leaseExpiresAt: row.lease_expires_at,
    deliveredAt: row.delivered_at,
    payloadRedactedAt: row.payload_redacted_at,
    lastHttpStatus: row.last_http_status,
    lastError: row.last_error,
    updatedAt: row.updated_at,
  };
}

const EVENT_COLUMNS = `
  event_id,
  payload_hash,
  payload_json,
  event_type,
  current_status,
  evaluated_at,
  delivery_state,
  delivery_attempts,
  accepted_at,
  enqueued_at,
  last_attempt_at,
  lease_expires_at,
  delivered_at,
  payload_redacted_at,
  last_http_status,
  last_error,
  updated_at
`;

export class D1EventRepository {
  constructor(database) {
    this.database = database;
  }

  async accept({ payload, payloadHash, payloadJson, nowIso }) {
    const result = await this.database.prepare(`
      INSERT OR IGNORE INTO notification_events (
        event_id,
        payload_hash,
        payload_json,
        event_type,
        current_status,
        evaluated_at,
        delivery_state,
        accepted_at,
        updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, 'accepted', ?, ?)
    `).bind(
      payload.event_id,
      payloadHash,
      payloadJson,
      payload.event,
      payload.current_status,
      payload.evaluated_at,
      nowIso,
      nowIso,
    ).run();

    if (changes(result) === 1) {
      return {
        kind: "new",
        event: await this.get(payload.event_id),
      };
    }

    const existing = await this.get(payload.event_id);

    if (existing === null) {
      throw new Error("event disappeared after duplicate insert");
    }

    if (existing.payloadHash !== payloadHash) {
      return {
        kind: "conflict",
        event: existing,
      };
    }

    return {
      kind: "duplicate",
      event: existing,
    };
  }

  async get(eventId) {
    const row = await this.database.prepare(`
      SELECT ${EVENT_COLUMNS}
      FROM notification_events
      WHERE event_id = ?
    `).bind(eventId).first();

    return mapRow(row);
  }

  async markQueued(eventId, nowIso) {
    const result = await this.database.prepare(`
      UPDATE notification_events
      SET
        delivery_state = 'queued',
        enqueued_at = ?,
        updated_at = ?
      WHERE event_id = ?
        AND delivery_state = 'accepted'
    `).bind(nowIso, nowIso, eventId).run();

    return changes(result) === 1;
  }

  async claim(eventId, nowIso, leaseExpiresAtIso) {
    const result = await this.database.prepare(`
      UPDATE notification_events
      SET
        delivery_state = 'delivering',
        delivery_attempts = delivery_attempts + 1,
        last_attempt_at = ?,
        lease_expires_at = ?,
        updated_at = ?
      WHERE event_id = ?
        AND (
          delivery_state IN ('accepted', 'queued')
          OR (
            delivery_state = 'delivering'
            AND lease_expires_at <= ?
          )
        )
    `).bind(
      nowIso,
      leaseExpiresAtIso,
      nowIso,
      eventId,
      nowIso,
    ).run();

    if (changes(result) !== 1) {
      return null;
    }

    return this.get(eventId);
  }

  async markDelivered(eventId, status, nowIso) {
    const result = await this.database.prepare(`
      UPDATE notification_events
      SET
        delivery_state = 'delivered',
        delivered_at = ?,
        payload_json = '{}',
        payload_redacted_at = ?,
        last_http_status = ?,
        last_error = NULL,
        lease_expires_at = NULL,
        updated_at = ?
      WHERE event_id = ?
        AND delivery_state = 'delivering'
    `).bind(nowIso, nowIso, status, nowIso, eventId).run();

    return changes(result) === 1;
  }

  async markRetryable(eventId, status, error, nowIso) {
    const result = await this.database.prepare(`
      UPDATE notification_events
      SET
        delivery_state = 'queued',
        last_http_status = ?,
        last_error = ?,
        lease_expires_at = NULL,
        updated_at = ?
      WHERE event_id = ?
        AND delivery_state = 'delivering'
    `).bind(status, error, nowIso, eventId).run();

    return changes(result) === 1;
  }

  async markPermanent(eventId, status, error, nowIso) {
    const result = await this.database.prepare(`
      UPDATE notification_events
      SET
        delivery_state = 'failed_permanent',
        last_http_status = ?,
        last_error = ?,
        lease_expires_at = NULL,
        updated_at = ?
      WHERE event_id = ?
        AND delivery_state = 'delivering'
    `).bind(status, error, nowIso, eventId).run();

    return changes(result) === 1;
  }

  async markExhausted(eventId, nowIso) {
    const result = await this.database.prepare(`
      UPDATE notification_events
      SET
        delivery_state = 'failed_exhausted',
        last_error = 'queue_retry_exhausted',
        lease_expires_at = NULL,
        updated_at = ?
      WHERE event_id = ?
        AND delivery_state NOT IN (
          'delivered',
          'failed_permanent'
        )
    `).bind(nowIso, eventId).run();

    return changes(result) === 1;
  }

  async healthSummary(staleBeforeIso) {
    const result = await this.database.prepare(`
      SELECT
        delivery_state AS kind,
        COUNT(*) AS events
      FROM notification_events
      WHERE delivery_state IN ('failed_permanent', 'failed_exhausted')
      GROUP BY delivery_state

      UNION ALL

      SELECT
        'stale_in_flight' AS kind,
        COUNT(*) AS events
      FROM notification_events
      WHERE delivery_state IN ('accepted', 'queued', 'delivering')
        AND updated_at <= ?
    `).bind(staleBeforeIso).all();

    const counts = new Map(
      (result.results ?? []).map((row) => [
        row.kind,
        Number(row.events ?? 0),
      ]),
    );

    return {
      failedPermanent: counts.get("failed_permanent") ?? 0,
      failedExhausted: counts.get("failed_exhausted") ?? 0,
      staleInFlight: counts.get("stale_in_flight") ?? 0,
    };
  }

  async listForReconciliation(nowIso, staleBeforeIso, limit) {
    const result = await this.database.prepare(`
      SELECT ${EVENT_COLUMNS}
      FROM notification_events
      WHERE delivery_state = 'accepted'
        OR (
          delivery_state = 'delivering'
          AND lease_expires_at <= ?
        )
        OR (
          delivery_state = 'queued'
          AND delivery_attempts = 0
          AND updated_at <= ?
        )
      ORDER BY accepted_at ASC
      LIMIT ?
    `).bind(nowIso, staleBeforeIso, limit).all();

    return (result.results ?? []).map(mapRow);
  }

  async markReconciledQueued(eventId, observedUpdatedAt, nowIso) {
    const result = await this.database.prepare(`
      UPDATE notification_events
      SET
        delivery_state = 'queued',
        enqueued_at = ?,
        lease_expires_at = NULL,
        updated_at = ?
      WHERE event_id = ?
        AND updated_at = ?
        AND delivery_state NOT IN (
          'delivered',
          'failed_permanent',
          'failed_exhausted'
        )
    `).bind(
      nowIso,
      nowIso,
      eventId,
      observedUpdatedAt,
    ).run();

    return changes(result) === 1;
  }
}
