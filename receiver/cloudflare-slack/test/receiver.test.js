import assert from "node:assert/strict";
import test from "node:test";

import {
  acceptWebhookRequest,
  processDeadLetterBatch,
  processDeliveryMessage,
  reconcilePendingEvents,
} from "../src/index.js";
import {
  ProtocolError,
  canonicalJson,
  createWebhookSignature,
  sha256Hex,
  verifyWebhookSignature,
} from "../src/protocol.js";

const SIGNING_SECRET = "receiver-test-signing-secret-32-bytes";
const NOW_MS = Date.parse("2026-08-21T03:00:00.000Z");

function buildEvent(overrides = {}) {
  return {
    schema_version: 1,
    event_id: "test-event-1",
    event: "ALERT",
    alert_required: true,
    previous_status: "OK",
    current_status: "CRITICAL",
    checker_exit_code: 2,
    state_file: "/var/lib/aerotrace-monitoring/collector-queue-alert.json",
    evaluated_at: "2026-08-21T02:59:50+00:00",
    checker_output: {
      stdout: "status=CRITICAL\nqueue_size=50000",
      stderr: "",
    },
    ...overrides,
  };
}

async function signedRequest(payload, overrides = {}) {
  const body = JSON.stringify(payload);
  const bodyBytes = new TextEncoder().encode(body);
  const timestamp = String(Math.floor(NOW_MS / 1000));
  const signature = await createWebhookSignature(
    SIGNING_SECRET,
    timestamp,
    bodyBytes,
  );

  return new Request(
    "https://receiver.example/v1/notifications",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-AeroTrace-Event-Id": payload.event_id,
        "X-AeroTrace-Timestamp": timestamp,
        "X-AeroTrace-Signature": signature,
        ...overrides,
      },
      body,
    },
  );
}

class MemoryRepository {
  constructor() {
    this.events = new Map();
  }

  async accept({ payload, payloadHash, payloadJson, nowIso }) {
    const existing = this.events.get(payload.event_id);

    if (existing !== undefined) {
      return {
        kind: existing.payloadHash === payloadHash ? "duplicate" : "conflict",
        event: structuredClone(existing),
      };
    }

    const event = {
      eventId: payload.event_id,
      payloadHash,
      payloadJson,
      payload: structuredClone(payload),
      eventType: payload.event,
      currentStatus: payload.current_status,
      evaluatedAt: payload.evaluated_at,
      deliveryState: "accepted",
      deliveryAttempts: 0,
      acceptedAt: nowIso,
      enqueuedAt: null,
      lastAttemptAt: null,
      leaseExpiresAt: null,
      deliveredAt: null,
      payloadRedactedAt: null,
      lastHttpStatus: null,
      lastError: null,
      updatedAt: nowIso,
    };
    this.events.set(payload.event_id, event);

    return { kind: "new", event: structuredClone(event) };
  }

  async get(eventId) {
    const event = this.events.get(eventId);
    return event === undefined ? null : structuredClone(event);
  }

  async markQueued(eventId, nowIso) {
    const event = this.events.get(eventId);

    if (event?.deliveryState !== "accepted") {
      return false;
    }

    event.deliveryState = "queued";
    event.enqueuedAt = nowIso;
    event.updatedAt = nowIso;
    return true;
  }

  async claim(eventId, nowIso, leaseExpiresAtIso) {
    const event = this.events.get(eventId);

    if (event === undefined) {
      return null;
    }

    const leaseExpired = (
      event.deliveryState === "delivering"
      && event.leaseExpiresAt <= nowIso
    );

    if (!(["accepted", "queued"].includes(event.deliveryState) || leaseExpired)) {
      return null;
    }

    event.deliveryState = "delivering";
    event.deliveryAttempts += 1;
    event.lastAttemptAt = nowIso;
    event.leaseExpiresAt = leaseExpiresAtIso;
    event.updatedAt = nowIso;
    return structuredClone(event);
  }

  async markDelivered(eventId, status, nowIso) {
    return this.#finish(eventId, "delivered", status, null, nowIso);
  }

  async markRetryable(eventId, status, error, nowIso) {
    return this.#finish(eventId, "queued", status, error, nowIso);
  }

  async markPermanent(eventId, status, error, nowIso) {
    return this.#finish(eventId, "failed_permanent", status, error, nowIso);
  }

  async markExhausted(eventId, nowIso) {
    const event = this.events.get(eventId);

    if (event === undefined || event.deliveryState === "delivered") {
      return false;
    }

    event.deliveryState = "failed_exhausted";
    event.lastError = "queue_retry_exhausted";
    event.leaseExpiresAt = null;
    event.updatedAt = nowIso;
    return true;
  }

  async healthSummary(staleBeforeIso) {
    const events = Array.from(this.events.values());

    return {
      failedPermanent: events.filter(
        (event) => event.deliveryState === "failed_permanent",
      ).length,
      failedExhausted: events.filter(
        (event) => event.deliveryState === "failed_exhausted",
      ).length,
      staleInFlight: events.filter((event) => (
        ["accepted", "queued", "delivering"].includes(event.deliveryState)
        && event.updatedAt <= staleBeforeIso
      )).length,
    };
  }

  async listForReconciliation(nowIso, staleBeforeIso, limit) {
    return Array.from(this.events.values())
      .filter((event) => (
        event.deliveryState === "accepted"
        || (
          event.deliveryState === "delivering"
          && event.leaseExpiresAt <= nowIso
        )
        || (
          event.deliveryState === "queued"
          && event.deliveryAttempts === 0
          && event.updatedAt <= staleBeforeIso
        )
      ))
      .slice(0, limit)
      .map((event) => structuredClone(event));
  }

  async markReconciledQueued(eventId, observedUpdatedAt, nowIso) {
    const event = this.events.get(eventId);

    if (
      event === undefined
      || event.updatedAt !== observedUpdatedAt
      || ["delivered", "failed_permanent", "failed_exhausted"].includes(
        event.deliveryState,
      )
    ) {
      return false;
    }

    event.deliveryState = "queued";
    event.enqueuedAt = nowIso;
    event.leaseExpiresAt = null;
    event.updatedAt = nowIso;
    return true;
  }

  #finish(eventId, state, status, error, nowIso) {
    const event = this.events.get(eventId);

    if (event?.deliveryState !== "delivering") {
      return false;
    }

    event.deliveryState = state;
    event.lastHttpStatus = status;
    event.lastError = error;
    event.leaseExpiresAt = null;
    event.updatedAt = nowIso;

    if (state === "delivered") {
      event.deliveredAt = nowIso;
      event.payload = {};
      event.payloadJson = "{}";
      event.payloadRedactedAt = nowIso;
    }

    return true;
  }
}

class MemoryQueue {
  constructor({ fail = false } = {}) {
    this.messages = [];
    this.fail = fail;
  }

  async send(body) {
    if (this.fail) {
      throw new Error("queue unavailable");
    }

    this.messages.push(structuredClone(body));
  }
}

function queueMessage(eventId, attempts = 1) {
  return {
    body: { eventId },
    attempts,
    acknowledged: false,
    retried: null,
    ack() {
      this.acknowledged = true;
    },
    retry(options) {
      this.retried = options;
    },
  };
}

function receiverEnv() {
  return {
    AEROTRACE_SIGNING_SECRET: SIGNING_SECRET,
    SLACK_WEBHOOK_URL: "https://hooks.slack.com/services/T000/B000/SECRET",
    SIGNATURE_MAX_AGE_SEC: "300",
    DELIVERY_LEASE_SEC: "30",
    SLACK_TIMEOUT_MS: "5000",
    RECONCILE_STALE_SEC: "600",
    RECONCILE_BATCH_SIZE: "100",
    HEALTH_STALE_SEC: "600",
  };
}

async function seedAccepted(repository, payload = buildEvent()) {
  const payloadJson = canonicalJson(payload);
  await repository.accept({
    payload,
    payloadJson,
    payloadHash: await sha256Hex(payloadJson),
    nowIso: new Date(NOW_MS).toISOString(),
  });
}

test("HMAC signature validates exact body and timestamp", async () => {
  const bodyBytes = new TextEncoder().encode("{\"event_id\":\"one\"}");
  const timestamp = String(Math.floor(NOW_MS / 1000));
  const signature = await createWebhookSignature(
    SIGNING_SECRET,
    timestamp,
    bodyBytes,
  );

  await verifyWebhookSignature({
    secret: SIGNING_SECRET,
    timestampHeader: timestamp,
    signatureHeader: signature,
    bodyBytes,
    nowEpochSec: Math.floor(NOW_MS / 1000),
    maxAgeSec: 300,
  });

  await assert.rejects(
    verifyWebhookSignature({
      secret: SIGNING_SECRET,
      timestampHeader: timestamp,
      signatureHeader: signature,
      bodyBytes: new TextEncoder().encode("{\"event_id\":\"two\"}"),
      nowEpochSec: Math.floor(NOW_MS / 1000),
      maxAgeSec: 300,
    }),
    (error) => error instanceof ProtocolError && error.code === "signature_mismatch",
  );

  await assert.rejects(
    verifyWebhookSignature({
      secret: SIGNING_SECRET,
      timestampHeader: timestamp,
      signatureHeader: signature,
      bodyBytes,
      nowEpochSec: Math.floor(NOW_MS / 1000) + 301,
      maxAgeSec: 300,
    }),
    (error) => error instanceof ProtocolError && error.code === "expired_signature",
  );
});

test("canonical payload hash ignores object key order", async () => {
  const first = canonicalJson({ b: 2, nested: { z: 1, a: true }, a: 1 });
  const second = canonicalJson({ a: 1, nested: { a: true, z: 1 }, b: 2 });

  assert.equal(first, second);
  assert.equal(await sha256Hex(first), await sha256Hex(second));
});

test("receiver durably accepts, deduplicates, and rejects conflicts", async () => {
  const repository = new MemoryRepository();
  const queue = new MemoryQueue();
  const payload = buildEvent();

  const accepted = await acceptWebhookRequest(
    await signedRequest(payload),
    receiverEnv(),
    { repository, queue, nowMs: NOW_MS },
  );

  assert.equal(accepted.status, 202);
  assert.deepEqual(queue.messages, [{ eventId: payload.event_id }]);
  assert.equal((await repository.get(payload.event_id)).deliveryState, "queued");

  const duplicate = await acceptWebhookRequest(
    await signedRequest(payload),
    receiverEnv(),
    { repository, queue, nowMs: NOW_MS + 1000 },
  );

  assert.equal(duplicate.status, 204);
  assert.equal(queue.messages.length, 1);

  await assert.rejects(
    acceptWebhookRequest(
      await signedRequest(buildEvent({ current_status: "WARNING" })),
      receiverEnv(),
      { repository, queue, nowMs: NOW_MS + 2000 },
    ),
    (error) => error instanceof ProtocolError && error.code === "event_id_conflict",
  );
});

test("receiver rejects an invalid signature before durable acceptance", async () => {
  const repository = new MemoryRepository();
  const queue = new MemoryQueue();
  const request = await signedRequest(buildEvent(), {
    "X-AeroTrace-Signature": `v1=${"0".repeat(64)}`,
  });

  await assert.rejects(
    acceptWebhookRequest(
      request,
      receiverEnv(),
      { repository, queue, nowMs: NOW_MS },
    ),
    (error) => error instanceof ProtocolError && error.status === 403,
  );

  assert.equal(repository.events.size, 0);
  assert.equal(queue.messages.length, 0);
});

test("receiver rejects invalid UTF-8 before durable acceptance", async () => {
  const repository = new MemoryRepository();
  const queue = new MemoryQueue();
  const bodyBytes = new Uint8Array([0x7b, 0x22, 0xff, 0x22, 0x7d]);
  const timestamp = String(Math.floor(NOW_MS / 1000));
  const signature = await createWebhookSignature(
    SIGNING_SECRET,
    timestamp,
    bodyBytes,
  );
  const request = new Request(
    "https://receiver.example/v1/notifications",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-AeroTrace-Event-Id": "test-event-1",
        "X-AeroTrace-Timestamp": timestamp,
        "X-AeroTrace-Signature": signature,
      },
      body: bodyBytes,
    },
  );

  await assert.rejects(
    acceptWebhookRequest(
      request,
      receiverEnv(),
      { repository, queue, nowMs: NOW_MS },
    ),
    (error) => error instanceof ProtocolError && error.code === "invalid_json",
  );

  assert.equal(repository.events.size, 0);
  assert.equal(queue.messages.length, 0);
});

test("durable acceptance survives queue publication failure and retry", async () => {
  const repository = new MemoryRepository();
  const unavailableQueue = new MemoryQueue({ fail: true });
  const payload = buildEvent();

  await assert.rejects(
    acceptWebhookRequest(
      await signedRequest(payload),
      receiverEnv(),
      { repository, queue: unavailableQueue, nowMs: NOW_MS },
    ),
    (error) => error instanceof ProtocolError && error.code === "queue_unavailable",
  );

  assert.equal(
    (await repository.get(payload.event_id)).deliveryState,
    "accepted",
  );

  const recoveredQueue = new MemoryQueue();
  const response = await acceptWebhookRequest(
    await signedRequest(payload),
    receiverEnv(),
    { repository, queue: recoveredQueue, nowMs: NOW_MS + 1000 },
  );

  assert.equal(response.status, 204);
  assert.deepEqual(recoveredQueue.messages, [{ eventId: payload.event_id }]);
  assert.equal(
    (await repository.get(payload.event_id)).deliveryState,
    "queued",
  );
});

test("Slack success marks the durable event delivered", async () => {
  const repository = new MemoryRepository();
  await seedAccepted(repository);
  const message = queueMessage("test-event-1");
  let slackRequest;

  await processDeliveryMessage(
    message,
    receiverEnv(),
    {
      repository,
      nowMs: NOW_MS,
      fetchImpl: async (url, options) => {
        slackRequest = { url, options };
        return new Response("ok", { status: 200 });
      },
    },
  );

  const event = await repository.get("test-event-1");
  const slackPayload = JSON.parse(slackRequest.options.body);

  assert.equal(slackRequest.url, receiverEnv().SLACK_WEBHOOK_URL);
  assert.match(slackPayload.text, /test-event-1/);
  assert.match(JSON.stringify(slackPayload.blocks), /CRITICAL/);
  assert.equal(event.deliveryState, "delivered");
  assert.equal(event.deliveryAttempts, 1);
  assert.deepEqual(event.payload, {});
  assert.equal(event.payloadRedactedAt, new Date(NOW_MS).toISOString());
  assert.equal(message.acknowledged, true);
  assert.equal(message.retried, null);
});

test("Slack 429 honors Retry-After and keeps the event queued", async () => {
  const repository = new MemoryRepository();
  await seedAccepted(repository);
  const message = queueMessage("test-event-1", 2);

  await processDeliveryMessage(
    message,
    receiverEnv(),
    {
      repository,
      nowMs: NOW_MS,
      fetchImpl: async () => new Response(
        "rate limited",
        {
          status: 429,
          headers: { "Retry-After": "42" },
        },
      ),
    },
  );

  const event = await repository.get("test-event-1");
  assert.equal(event.deliveryState, "queued");
  assert.equal(event.lastHttpStatus, 429);
  assert.deepEqual(message.retried, { delaySeconds: 42 });
  assert.equal(message.acknowledged, false);
});

test("Slack permanent response is stored and acknowledged", async () => {
  const repository = new MemoryRepository();
  await seedAccepted(repository);
  const message = queueMessage("test-event-1");

  await processDeliveryMessage(
    message,
    receiverEnv(),
    {
      repository,
      nowMs: NOW_MS,
      fetchImpl: async () => new Response("invalid", { status: 400 }),
    },
  );

  const event = await repository.get("test-event-1");
  assert.equal(event.deliveryState, "failed_permanent");
  assert.equal(event.lastError, "slack_http_400");
  assert.equal(message.acknowledged, true);

  const healthResponse = await acceptWebhookRequest(
    new Request("https://receiver.example/health"),
    receiverEnv(),
    { repository, nowMs: NOW_MS },
  );
  assert.equal(healthResponse.status, 503);
  assert.deepEqual(await healthResponse.json(), {
    status: "degraded",
    failed_permanent: 1,
    failed_exhausted: 0,
    stale_in_flight: 0,
  });
});

test("Slack redirect is not followed and is stored as permanent", async () => {
  const repository = new MemoryRepository();
  await seedAccepted(repository);
  const message = queueMessage("test-event-1");
  let redirectMode;

  await processDeliveryMessage(
    message,
    receiverEnv(),
    {
      repository,
      nowMs: NOW_MS,
      fetchImpl: async (_url, options) => {
        redirectMode = options.redirect;
        return new Response(null, {
          status: 302,
          headers: { Location: "https://example.com/not-followed" },
        });
      },
    },
  );

  const event = await repository.get("test-event-1");
  assert.equal(redirectMode, "manual");
  assert.equal(event.deliveryState, "failed_permanent");
  assert.equal(event.lastHttpStatus, 302);
  assert.equal(message.acknowledged, true);
});

test("Slack network failure is retryable", async () => {
  const repository = new MemoryRepository();
  await seedAccepted(repository);
  const message = queueMessage("test-event-1", 3);

  await processDeliveryMessage(
    message,
    receiverEnv(),
    {
      repository,
      nowMs: NOW_MS,
      fetchImpl: async () => {
        throw new TypeError("network unavailable");
      },
    },
  );

  const event = await repository.get("test-event-1");
  assert.equal(event.deliveryState, "queued");
  assert.equal(event.lastError, "slack_network_error");
  assert.deepEqual(message.retried, { delaySeconds: 20 });
});

test("dead-letter consumption marks retry exhaustion", async () => {
  const repository = new MemoryRepository();
  await seedAccepted(repository);
  const message = queueMessage("test-event-1", 11);

  await processDeadLetterBatch(
    { messages: [message] },
    receiverEnv(),
    { repository, nowMs: NOW_MS },
  );

  assert.equal(
    (await repository.get("test-event-1")).deliveryState,
    "failed_exhausted",
  );
  assert.equal(message.acknowledged, true);
});

test("duplicate dead-letter delivery preserves an already delivered event", async () => {
  const repository = new MemoryRepository();
  await seedAccepted(repository);
  const deliveryMessage = queueMessage("test-event-1");

  await processDeliveryMessage(
    deliveryMessage,
    receiverEnv(),
    {
      repository,
      nowMs: NOW_MS,
      fetchImpl: async () => new Response("ok", { status: 200 }),
    },
  );

  const deadLetterMessage = queueMessage("test-event-1", 11);
  await processDeadLetterBatch(
    { messages: [deadLetterMessage] },
    receiverEnv(),
    { repository, nowMs: NOW_MS + 1000 },
  );

  assert.equal(
    (await repository.get("test-event-1")).deliveryState,
    "delivered",
  );
  assert.equal(deadLetterMessage.acknowledged, true);
});

test("scheduled reconciliation republishes durable accepted events", async () => {
  const repository = new MemoryRepository();
  const queue = new MemoryQueue();
  await seedAccepted(repository);

  await reconcilePendingEvents(
    receiverEnv(),
    { repository, queue, nowMs: NOW_MS + (15 * 60 * 1000) },
  );

  assert.deepEqual(queue.messages, [{ eventId: "test-event-1" }]);
  assert.equal(
    (await repository.get("test-event-1")).deliveryState,
    "queued",
  );
});
