import { D1EventRepository } from "./d1-repository.js";
import {
  MAX_REQUEST_BODY_BYTES,
  ProtocolError,
  buildSlackPayload,
  calculateRetryDelaySec,
  canonicalJson,
  classifySlackStatus,
  sha256Hex,
  validateAeroTraceEvent,
  verifyWebhookSignature,
} from "./protocol.js";

const DELIVERY_QUEUE_NAME = "aerotrace-notification-delivery";
const DELIVERY_DLQ_NAME = "aerotrace-notification-delivery-dlq";

function jsonResponse(status, body) {
  return new Response(
    body === null ? null : JSON.stringify(body),
    {
      status,
      headers: {
        "Cache-Control": "no-store",
        ...(body === null
          ? {}
          : { "Content-Type": "application/json; charset=utf-8" }),
      },
    },
  );
}

function integerSetting(env, name, defaultValue, minimum, maximum) {
  const rawValue = env[name] ?? String(defaultValue);

  if (!/^\d+$/.test(rawValue)) {
    throw new Error(`${name} must be an integer`);
  }

  const value = Number(rawValue);

  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be between ${minimum} and ${maximum}`);
  }

  return value;
}

function ensureSlackWebhookUrl(value) {
  let url;

  try {
    url = new URL(value);
  } catch {
    throw new Error("SLACK_WEBHOOK_URL is invalid");
  }

  if (
    url.protocol !== "https:"
    || url.hostname !== "hooks.slack.com"
    || !url.pathname.startsWith("/services/")
    || url.search
    || url.hash
  ) {
    throw new Error("SLACK_WEBHOOK_URL must be a Slack HTTPS incoming webhook URL");
  }

  return url.toString();
}

async function readRequestBody(request) {
  const contentLength = request.headers.get("Content-Length");

  if (
    contentLength !== null
    && /^\d+$/.test(contentLength)
    && Number(contentLength) > MAX_REQUEST_BODY_BYTES
  ) {
    throw new ProtocolError(
      413,
      "payload_too_large",
      "request body exceeds 64 KiB",
    );
  }

  const bodyBytes = new Uint8Array(await request.arrayBuffer());

  if (bodyBytes.length > MAX_REQUEST_BODY_BYTES) {
    throw new ProtocolError(
      413,
      "payload_too_large",
      "request body exceeds 64 KiB",
    );
  }

  return bodyBytes;
}

async function enqueueEvent(queue, repository, eventId, nowIso) {
  await queue.send({ eventId });
  await repository.markQueued(eventId, nowIso);
}

export async function acceptWebhookRequest(
  request,
  env,
  {
    repository = new D1EventRepository(env.DB),
    queue = env.DELIVERY_QUEUE,
    nowMs = Date.now(),
  } = {},
) {
  const url = new URL(request.url);

  if (request.method === "GET" && url.pathname === "/health") {
    const healthStaleSec = integerSetting(
      env,
      "HEALTH_STALE_SEC",
      600,
      60,
      86400,
    );
    const staleBeforeIso = new Date(
      nowMs - (healthStaleSec * 1000),
    ).toISOString();
    const summary = await repository.healthSummary(staleBeforeIso);
    const degraded = (
      summary.failedPermanent > 0
      || summary.failedExhausted > 0
      || summary.staleInFlight > 0
    );

    return jsonResponse(degraded ? 503 : 200, {
      status: degraded ? "degraded" : "ok",
      failed_permanent: summary.failedPermanent,
      failed_exhausted: summary.failedExhausted,
      stale_in_flight: summary.staleInFlight,
    });
  }

  if (url.pathname !== "/v1/notifications") {
    return jsonResponse(404, { error: "not_found" });
  }

  if (request.method !== "POST") {
    return new Response(null, {
      status: 405,
      headers: { Allow: "POST" },
    });
  }

  const contentType = request.headers.get("Content-Type") ?? "";

  if (contentType.split(";", 1)[0].trim().toLowerCase() !== "application/json") {
    throw new ProtocolError(
      415,
      "unsupported_media_type",
      "Content-Type must be application/json",
    );
  }

  const bodyBytes = await readRequestBody(request);
  const signatureMaxAgeSec = integerSetting(
    env,
    "SIGNATURE_MAX_AGE_SEC",
    300,
    1,
    3600,
  );

  await verifyWebhookSignature({
    secret: env.AEROTRACE_SIGNING_SECRET,
    timestampHeader: request.headers.get("X-AeroTrace-Timestamp"),
    signatureHeader: request.headers.get("X-AeroTrace-Signature"),
    bodyBytes,
    nowEpochSec: Math.floor(nowMs / 1000),
    maxAgeSec: signatureMaxAgeSec,
  });

  let payload;

  try {
    payload = JSON.parse(
      new TextDecoder("utf-8", { fatal: true }).decode(bodyBytes),
    );
  } catch {
    throw new ProtocolError(
      400,
      "invalid_json",
      "request body must be valid UTF-8 JSON",
    );
  }

  validateAeroTraceEvent(
    payload,
    request.headers.get("X-AeroTrace-Event-Id"),
  );

  const payloadJson = canonicalJson(payload);
  const payloadHash = await sha256Hex(payloadJson);
  const nowIso = new Date(nowMs).toISOString();
  const acceptance = await repository.accept({
    payload,
    payloadHash,
    payloadJson,
    nowIso,
  });

  if (acceptance.kind === "conflict") {
    throw new ProtocolError(
      409,
      "event_id_conflict",
      "event_id already exists with a different payload",
    );
  }

  if (
    acceptance.kind === "duplicate"
    && acceptance.event.deliveryState !== "accepted"
  ) {
    return jsonResponse(204, null);
  }

  try {
    await enqueueEvent(
      queue,
      repository,
      payload.event_id,
      nowIso,
    );
  } catch (error) {
    console.error(JSON.stringify({
      event: "receiver_enqueue_failed",
      event_id: payload.event_id,
      error: error?.name ?? "Error",
    }));

    throw new ProtocolError(
      503,
      "queue_unavailable",
      "event is durable but queue publication failed; retry required",
    );
  }

  if (acceptance.kind === "duplicate") {
    return jsonResponse(204, null);
  }

  return jsonResponse(202, {
    accepted: true,
    event_id: payload.event_id,
  });
}

async function updateOrThrow(updated, operation) {
  if (!updated) {
    throw new Error(`${operation} did not update the claimed event`);
  }
}

export async function processDeliveryMessage(
  message,
  env,
  {
    repository = new D1EventRepository(env.DB),
    fetchImpl = fetch,
    nowMs = Date.now(),
  } = {},
) {
  const eventId = message?.body?.eventId;

  if (typeof eventId !== "string" || eventId.length === 0) {
    console.error(JSON.stringify({ event: "invalid_queue_message" }));
    message.ack();
    return;
  }

  const leaseSec = integerSetting(env, "DELIVERY_LEASE_SEC", 30, 5, 300);
  const slackTimeoutMs = integerSetting(env, "SLACK_TIMEOUT_MS", 5000, 1000, 15000);
  const nowIso = new Date(nowMs).toISOString();
  const leaseExpiresAtIso = new Date(nowMs + (leaseSec * 1000)).toISOString();
  const claimed = await repository.claim(eventId, nowIso, leaseExpiresAtIso);

  if (claimed === null) {
    message.ack();
    return;
  }

  let response;

  try {
    response = await fetchImpl(
      ensureSlackWebhookUrl(env.SLACK_WEBHOOK_URL),
      {
        method: "POST",
        redirect: "manual",
        headers: {
          "Content-Type": "application/json; charset=utf-8",
        },
        body: JSON.stringify(buildSlackPayload(claimed.payload)),
        signal: AbortSignal.timeout(slackTimeoutMs),
      },
    );
  } catch (error) {
    await updateOrThrow(
      await repository.markRetryable(
        eventId,
        null,
        error?.name === "TimeoutError" ? "slack_timeout" : "slack_network_error",
        nowIso,
      ),
      "markRetryable",
    );
    message.retry({
      delaySeconds: calculateRetryDelaySec(message.attempts),
    });
    return;
  }

  const classification = classifySlackStatus(response.status);

  if (classification === "success") {
    await updateOrThrow(
      await repository.markDelivered(eventId, response.status, nowIso),
      "markDelivered",
    );
    message.ack();
    return;
  }

  if (classification === "retryable") {
    await updateOrThrow(
      await repository.markRetryable(
        eventId,
        response.status,
        `slack_http_${response.status}`,
        nowIso,
      ),
      "markRetryable",
    );
    message.retry({
      delaySeconds: calculateRetryDelaySec(
        message.attempts,
        response.headers.get("Retry-After"),
      ),
    });
    return;
  }

  await updateOrThrow(
    await repository.markPermanent(
      eventId,
      response.status,
      `slack_http_${response.status}`,
      nowIso,
    ),
    "markPermanent",
  );
  message.ack();
}

export async function processDeadLetterBatch(
  batch,
  env,
  { repository = new D1EventRepository(env.DB), nowMs = Date.now() } = {},
) {
  const nowIso = new Date(nowMs).toISOString();

  for (const message of batch.messages) {
    const eventId = message?.body?.eventId;

    if (typeof eventId !== "string" || eventId.length === 0) {
      message.ack();
      continue;
    }

    const event = await repository.get(eventId);

    if (
      event === null
      || [
        "delivered",
        "failed_permanent",
        "failed_exhausted",
      ].includes(event.deliveryState)
    ) {
      message.ack();
      continue;
    }

    await updateOrThrow(
      await repository.markExhausted(eventId, nowIso),
      "markExhausted",
    );
    message.ack();
  }
}

export async function reconcilePendingEvents(
  env,
  {
    repository = new D1EventRepository(env.DB),
    queue = env.DELIVERY_QUEUE,
    nowMs = Date.now(),
  } = {},
) {
  const staleSec = integerSetting(env, "RECONCILE_STALE_SEC", 600, 60, 86400);
  const batchSize = integerSetting(env, "RECONCILE_BATCH_SIZE", 100, 1, 100);
  const nowIso = new Date(nowMs).toISOString();
  const staleBeforeIso = new Date(nowMs - (staleSec * 1000)).toISOString();
  const events = await repository.listForReconciliation(
    nowIso,
    staleBeforeIso,
    batchSize,
  );

  let enqueued = 0;

  for (const event of events) {
    await queue.send({ eventId: event.eventId });
    const updated = await repository.markReconciledQueued(
      event.eventId,
      event.updatedAt,
      nowIso,
    );

    if (updated) {
      enqueued += 1;
    }
  }

  console.log(JSON.stringify({
    event: "receiver_reconciliation",
    candidates: events.length,
    enqueued,
  }));
}

async function fetchHandler(request, env) {
  try {
    return await acceptWebhookRequest(request, env);
  } catch (error) {
    if (error instanceof ProtocolError) {
      return jsonResponse(error.status, {
        error: error.code,
        message: error.message,
      });
    }

    console.error(JSON.stringify({
      event: "receiver_request_failed",
      error: error?.name ?? "Error",
    }));

    return jsonResponse(503, {
      error: "receiver_unavailable",
    });
  }
}

async function queueHandler(batch, env) {
  if (batch.queue === DELIVERY_DLQ_NAME) {
    await processDeadLetterBatch(batch, env);
    return;
  }

  if (batch.queue !== DELIVERY_QUEUE_NAME) {
    throw new Error(`unexpected queue: ${batch.queue}`);
  }

  for (const message of batch.messages) {
    try {
      await processDeliveryMessage(message, env);
    } catch (error) {
      console.error(JSON.stringify({
        event: "receiver_delivery_failed",
        event_id: message?.body?.eventId ?? null,
        error: error?.name ?? "Error",
      }));
      message.retry({
        delaySeconds: calculateRetryDelaySec(message.attempts),
      });
    }
  }
}

export default {
  fetch: fetchHandler,
  queue: queueHandler,
  async scheduled(_controller, env, context) {
    context.waitUntil(reconcilePendingEvents(env));
  },
};
