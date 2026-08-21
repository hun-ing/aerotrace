const textEncoder = new TextEncoder();

export const SIGNATURE_VERSION = "v1";
export const MIN_SIGNING_SECRET_BYTES = 32;
export const MAX_REQUEST_BODY_BYTES = 64 * 1024;

export const VALID_EVENTS = new Set([
  "ALERT",
  "STATUS_CHANGE",
  "RECOVERY",
  "REMINDER",
]);

export const VALID_STATUSES = new Set([
  "OK",
  "WARNING",
  "CRITICAL",
  "UNKNOWN",
]);

export class ProtocolError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = "ProtocolError";
    this.status = status;
    this.code = code;
  }
}

function assertPlainObject(value, fieldName) {
  if (
    value === null
    || typeof value !== "object"
    || Array.isArray(value)
  ) {
    throw new ProtocolError(
      400,
      "invalid_payload",
      `${fieldName} must be a JSON object`,
    );
  }
}

function assertString(value, fieldName, { allowEmpty = false } = {}) {
  if (
    typeof value !== "string"
    || (!allowEmpty && value.length === 0)
  ) {
    throw new ProtocolError(
      400,
      "invalid_payload",
      `${fieldName} must be ${allowEmpty ? "a" : "a non-empty"} string`,
    );
  }
}

export function validateAeroTraceEvent(payload, headerEventId) {
  assertPlainObject(payload, "payload");
  assertString(headerEventId, "X-AeroTrace-Event-Id");

  if (payload.schema_version !== 1) {
    throw new ProtocolError(
      400,
      "unsupported_schema_version",
      "schema_version must be 1",
    );
  }

  assertString(payload.event_id, "event_id");

  if (payload.event_id.length > 255) {
    throw new ProtocolError(
      400,
      "invalid_payload",
      "event_id must not exceed 255 characters",
    );
  }

  if (payload.event_id !== headerEventId) {
    throw new ProtocolError(
      400,
      "event_id_mismatch",
      "header and body event_id must match",
    );
  }

  if (!VALID_EVENTS.has(payload.event)) {
    throw new ProtocolError(
      400,
      "invalid_payload",
      "event is not supported",
    );
  }

  if (payload.alert_required !== true) {
    throw new ProtocolError(
      400,
      "invalid_payload",
      "alert_required must be true",
    );
  }

  if (
    payload.previous_status !== null
    && !VALID_STATUSES.has(payload.previous_status)
  ) {
    throw new ProtocolError(
      400,
      "invalid_payload",
      "previous_status is not supported",
    );
  }

  if (!VALID_STATUSES.has(payload.current_status)) {
    throw new ProtocolError(
      400,
      "invalid_payload",
      "current_status is not supported",
    );
  }

  if (
    payload.checker_exit_code !== null
    && !Number.isInteger(payload.checker_exit_code)
  ) {
    throw new ProtocolError(
      400,
      "invalid_payload",
      "checker_exit_code must be an integer or null",
    );
  }

  assertString(payload.state_file, "state_file");
  assertString(payload.evaluated_at, "evaluated_at");

  if (
    !/(?:Z|[+-]\d{2}:\d{2})$/.test(payload.evaluated_at)
    || Number.isNaN(Date.parse(payload.evaluated_at))
  ) {
    throw new ProtocolError(
      400,
      "invalid_payload",
      "evaluated_at must be an ISO-8601 timestamp with timezone",
    );
  }

  assertPlainObject(payload.checker_output, "checker_output");
  assertString(
    payload.checker_output.stdout,
    "checker_output.stdout",
    { allowEmpty: true },
  );
  assertString(
    payload.checker_output.stderr,
    "checker_output.stderr",
    { allowEmpty: true },
  );

  return payload;
}

export function canonicalJson(value) {
  if (value === null || typeof value === "boolean" || typeof value === "string") {
    return JSON.stringify(value);
  }

  if (typeof value === "number") {
    if (!Number.isFinite(value)) {
      throw new TypeError("canonical JSON does not allow non-finite numbers");
    }

    return JSON.stringify(value);
  }

  if (Array.isArray(value)) {
    return `[${value.map((item) => canonicalJson(item)).join(",")}]`;
  }

  if (typeof value === "object") {
    const entries = Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`);

    return `{${entries.join(",")}}`;
  }

  throw new TypeError("value is not representable as canonical JSON");
}

function bytesToHex(bytes) {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function hexToBytes(hex) {
  if (!/^[0-9a-f]{64}$/.test(hex)) {
    return null;
  }

  const bytes = new Uint8Array(hex.length / 2);

  for (let index = 0; index < hex.length; index += 2) {
    bytes[index / 2] = Number.parseInt(hex.slice(index, index + 2), 16);
  }

  return bytes;
}

export async function sha256Hex(value) {
  const bytes = typeof value === "string" ? textEncoder.encode(value) : value;
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return bytesToHex(new Uint8Array(digest));
}

function signedContent(timestamp, bodyBytes) {
  const prefix = textEncoder.encode(`${SIGNATURE_VERSION}.${timestamp}.`);
  const content = new Uint8Array(prefix.length + bodyBytes.length);
  content.set(prefix, 0);
  content.set(bodyBytes, prefix.length);
  return content;
}

export async function createWebhookSignature(secret, timestamp, bodyBytes) {
  const secretBytes = textEncoder.encode(secret);

  if (
    typeof secret !== "string"
    || secret.trim() !== secret
    || secretBytes.length < MIN_SIGNING_SECRET_BYTES
  ) {
    throw new TypeError("signing secret must contain at least 32 UTF-8 bytes");
  }

  const key = await crypto.subtle.importKey(
    "raw",
    secretBytes,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "HMAC",
    key,
    signedContent(timestamp, bodyBytes),
  );

  return `${SIGNATURE_VERSION}=${bytesToHex(new Uint8Array(signature))}`;
}

export async function verifyWebhookSignature({
  secret,
  timestampHeader,
  signatureHeader,
  bodyBytes,
  nowEpochSec,
  maxAgeSec,
}) {
  const secretBytes = textEncoder.encode(secret ?? "");

  if (
    typeof secret !== "string"
    || secret.trim() !== secret
    || secretBytes.length < MIN_SIGNING_SECRET_BYTES
  ) {
    throw new ProtocolError(
      500,
      "receiver_not_configured",
      "receiver signing secret is missing or too short",
    );
  }

  if (!/^\d{1,20}$/.test(timestampHeader ?? "")) {
    throw new ProtocolError(
      401,
      "invalid_signature_timestamp",
      "signature timestamp is missing or invalid",
    );
  }

  const timestamp = Number(timestampHeader);

  if (
    !Number.isSafeInteger(timestamp)
    || Math.abs(nowEpochSec - timestamp) > maxAgeSec
  ) {
    throw new ProtocolError(
      401,
      "expired_signature",
      "signature timestamp is outside the accepted window",
    );
  }

  const match = /^v1=([0-9a-f]{64})$/.exec(signatureHeader ?? "");
  const signatureBytes = match === null ? null : hexToBytes(match[1]);

  if (signatureBytes === null) {
    throw new ProtocolError(
      401,
      "invalid_signature",
      "signature is missing or malformed",
    );
  }

  const key = await crypto.subtle.importKey(
    "raw",
    secretBytes,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["verify"],
  );
  const valid = await crypto.subtle.verify(
    "HMAC",
    key,
    signatureBytes,
    signedContent(timestampHeader, bodyBytes),
  );

  if (!valid) {
    throw new ProtocolError(
      403,
      "signature_mismatch",
      "signature verification failed",
    );
  }
}

function escapeSlackText(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function truncate(value, maximumLength) {
  if (value.length <= maximumLength) {
    return value;
  }

  return `${value.slice(0, maximumLength - 1)}…`;
}

export function buildSlackPayload(payload) {
  const statusEmoji = {
    OK: ":large_green_circle:",
    WARNING: ":large_orange_circle:",
    CRITICAL: ":red_circle:",
    UNKNOWN: ":white_circle:",
  }[payload.current_status];
  const transition = `${payload.previous_status ?? "NONE"} → ${payload.current_status}`;
  const diagnosticText = [
    payload.checker_output.stdout,
    payload.checker_output.stderr,
  ].filter(Boolean).join("\n").replaceAll("```", "｀｀｀");
  const fallback = `[AeroTrace] ${payload.event} ${payload.current_status} (${payload.event_id})`;

  return {
    text: fallback,
    blocks: [
      {
        type: "header",
        text: {
          type: "plain_text",
          text: `${statusEmoji} AeroTrace ${payload.current_status}`,
          emoji: true,
        },
      },
      {
        type: "section",
        fields: [
          {
            type: "mrkdwn",
            text: `*Event*\n${escapeSlackText(payload.event)}`,
          },
          {
            type: "mrkdwn",
            text: `*Transition*\n${escapeSlackText(transition)}`,
          },
          {
            type: "mrkdwn",
            text: `*Evaluated at*\n${escapeSlackText(payload.evaluated_at)}`,
          },
          {
            type: "mrkdwn",
            text: `*Checker exit*\n${payload.checker_exit_code ?? "null"}`,
          },
        ],
      },
      ...(diagnosticText
        ? [{
          type: "section",
          text: {
            type: "mrkdwn",
            text: `*Checker output*\n\`\`\`${truncate(escapeSlackText(diagnosticText), 2500)}\`\`\``,
          },
        }]
        : []),
      {
        type: "context",
        elements: [{
          type: "mrkdwn",
          text: `event_id: \`${escapeSlackText(payload.event_id)}\``,
        }],
      },
    ],
  };
}

export function classifySlackStatus(status) {
  if (status >= 200 && status < 300) {
    return "success";
  }

  if (status === 408 || status === 429 || status >= 500) {
    return "retryable";
  }

  return "permanent";
}

export function calculateRetryDelaySec(attempt, retryAfterHeader = null) {
  if (retryAfterHeader !== null && /^\d+$/.test(retryAfterHeader.trim())) {
    const requested = Number(retryAfterHeader.trim());

    if (Number.isSafeInteger(requested) && requested > 0) {
      return Math.min(requested, 86400);
    }
  }

  const normalizedAttempt = Math.max(1, Number.isInteger(attempt) ? attempt : 1);
  return Math.min(5 * (2 ** (normalizedAttempt - 1)), 300);
}
