import {
  createWebhookSignature,
} from "../src/protocol.js";

const endpoint = process.argv[2];

if (endpoint === undefined) {
  console.error(
    "usage: npm run synthetic -- https://<worker>/v1/notifications",
  );
  process.exit(2);
}

let endpointUrl;

try {
  endpointUrl = new URL(endpoint);
} catch {
  console.error("synthetic_error=receiver URL is invalid");
  process.exit(2);
}

if (
  endpointUrl.protocol !== "https:"
  || endpointUrl.pathname !== "/v1/notifications"
  || endpointUrl.username
  || endpointUrl.password
  || endpointUrl.search
  || endpointUrl.hash
) {
  console.error(
    "synthetic_error=receiver URL must be an HTTPS /v1/notifications URL",
  );
  process.exit(2);
}

const signingSecret = process.env.AEROTRACE_SIGNING_SECRET;

if (signingSecret === undefined) {
  console.error(
    "synthetic_error=AEROTRACE_SIGNING_SECRET is missing from .dev.vars",
  );
  process.exit(2);
}

const now = new Date();
const eventId = `synthetic-${now.getTime()}-${crypto.randomUUID()}`;
const payload = {
  schema_version: 1,
  event_id: eventId,
  event: "ALERT",
  alert_required: true,
  previous_status: "OK",
  current_status: "WARNING",
  checker_exit_code: 1,
  state_file: "synthetic://cloudflare-slack-acceptance",
  evaluated_at: now.toISOString(),
  checker_output: {
    stdout: "synthetic_test=true\nexpected_result=Slack message",
    stderr: "",
  },
};
const body = JSON.stringify(payload);
const bodyBytes = new TextEncoder().encode(body);
const timestamp = String(Math.floor(now.getTime() / 1000));
const signature = await createWebhookSignature(
  signingSecret,
  timestamp,
  bodyBytes,
);

let response;

try {
  response = await fetch(endpointUrl, {
    method: "POST",
    redirect: "error",
    headers: {
      "Content-Type": "application/json",
      "Accept": "application/json",
      "X-AeroTrace-Event-Id": eventId,
      "X-AeroTrace-Timestamp": timestamp,
      "X-AeroTrace-Signature": signature,
    },
    body,
    signal: AbortSignal.timeout(10000),
  });
} catch (error) {
  console.error(`synthetic_error=${error?.name ?? "request_failed"}`);
  process.exit(1);
}

console.log(`synthetic_event_id=${eventId}`);
console.log(`receiver_status=${response.status}`);

if (response.status !== 202) {
  console.log("synthetic_result=FAIL");
  process.exit(1);
}

console.log("synthetic_result=DURABLY_ACCEPTED");
console.log("next_check=confirm the same event_id appears once in Slack");
