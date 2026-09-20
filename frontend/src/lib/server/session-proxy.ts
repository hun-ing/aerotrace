import "server-only";

import { getAeroTraceBackendConfig, type BackendConfig } from "./auth-config";

const UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
const PROJECTS = new RegExp(`^/api/v1/tenants/${UUID}/projects$`);
const PROJECT = new RegExp(`^/api/v1/projects/${UUID}$`);
const TRACES = new RegExp(`^/api/v1/projects/${UUID}/traces$`);
const DETAIL = new RegExp(`^/api/v1/projects/${UUID}/traces/[0-9a-f]{32}$`);
const TRACE_QUERY = ["from", "to", "limit", "cursor", "serviceName", "errorOnly", "minSpanDurationNano"];
const OAUTH_START = "/oauth2/authorization/github";
const OAUTH_CALLBACK = "/login/oauth2/code/github";
const NO_STORE = { "Cache-Control": "no-store, max-age=0", "Referrer-Policy": "no-referrer" };

export class SessionApiError extends Error {
  constructor(public readonly status: number) { super("Session request unavailable"); }
}

function fail(status: number): never { throw new SessionApiError(status); }

function validateHost(headers: Headers, config: BackendConfig) {
  // Never derive authority from browser-supplied Forwarded/X-Forwarded-* headers.
  if (headers.get("host") !== config.publicHost) fail(403);
}

function sessionCookie(headers: Headers, config: BackendConfig): string | null {
  const matches = (headers.get("cookie") ?? "").split(";").map(v => v.trim())
    .filter(v => v.split("=", 1)[0] === config.cookieName);
  if (matches.length > 1) fail(400);
  if (!matches.length) return null;
  const value = matches[0].slice(config.cookieName.length + 1);
  if (!/^[A-Za-z0-9+/_=-]{1,4096}$/.test(value)) fail(400);
  return `${config.cookieName}=${value}`;
}

function safeCookies(upstream: Response, config: BackendConfig): string[] {
  return upstream.headers.getSetCookie().map(cookie => {
    const parts = cookie.split(";").map(p => p.trim());
    const [name, ...value] = parts[0].split("=");
    if (name !== config.cookieName || !/^[A-Za-z0-9+/_=-]{0,4096}$/.test(value.join("="))) fail(502);
    const attributes = parts.slice(1).map(p => p.toLowerCase());
    const attributeNames = attributes.map(p => p.split("=", 1)[0].trim());
    if (new Set(attributeNames).size !== attributeNames.length ||
        attributeNames.some(p => !["path", "httponly", "samesite", "secure", "max-age", "expires"].includes(p))) fail(502);
    if (!attributes.includes("path=/") || !attributes.includes("httponly") ||
        !attributes.includes("samesite=lax") || attributes.some(p => p.startsWith("domain=")) ||
        attributes.includes("secure") !== config.secureCookie) fail(502);
    return cookie;
  });
}

async function boundedText(stream: ReadableStream<Uint8Array> | null, maxBytes: number, status: number): Promise<string> {
  if (!stream) return "";
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxBytes) { await reader.cancel(); fail(status); }
      chunks.push(value);
    }
    try {
      return new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks));
    } catch {
      fail(status === 413 ? 400 : status);
    }
  } finally { reader.releaseLock(); }
}

function forwardedHeaders(incoming: Headers, config: BackendConfig): Headers {
  const headers = new Headers({ Accept: "application/json" });
  const cookie = sessionCookie(incoming, config);
  if (cookie) headers.set("Cookie", cookie);
  return headers;
}

async function backendFetch(config: BackendConfig, path: string, headers: Headers, method = "GET", body?: string) {
  return fetch(new URL(path, config.baseUrl), {
    method, headers, body, redirect: "manual", cache: "no-store", signal: AbortSignal.timeout(5_000),
  });
}

// RSC/DAL reads go directly to Backend. No cross-request cache, no duplicated BFF HTTP hop.
export async function sessionRead<T>(path: string, incoming: Headers): Promise<T> {
  if (!(path === "/api/v1/me" || path === "/api/v1/tenants" || PROJECTS.test(path) || PROJECT.test(path))) fail(404);
  const config = getAeroTraceBackendConfig();
  validateHost(incoming, config);
  const response = await backendFetch(config, path, forwardedHeaders(incoming, config));
  if (response.status !== 200) {
    await response.body?.cancel();
    fail(response.status >= 500 ? 503 : response.status);
  }
  return JSON.parse(await boundedText(response.body, 1_048_576, 502)) as T;
}

export async function proxySessionRequest(request: Request): Promise<Response> {
  try {
    const config = getAeroTraceBackendConfig();
    validateHost(request.headers, config);
    const source = new URL(request.url);
    const path = source.pathname;
    const oauth = path === OAUTH_START || path === OAUTH_CALLBACK;
    const post = path === "/api/v1/onboarding/intents" || path === "/api/v1/logout";
    const get = oauth || path === "/api/v1/auth/csrf" || path === "/api/v1/me" ||
      path === "/api/v1/tenants" || PROJECTS.test(path) || PROJECT.test(path) || TRACES.test(path) || DETAIL.test(path);
    if (!post && !get) fail(404);
    if (request.method !== (post ? "POST" : "GET")) fail(405);
    const headers = forwardedHeaders(request.headers, config);
    const target = new URL(path, config.baseUrl);
    const allowed = TRACES.test(path) || DETAIL.test(path) ? TRACE_QUERY :
      path === OAUTH_CALLBACK ? ["code", "state", "error", "error_description", "error_uri"] : [];
    if (source.search.length > 8192) fail(400);
    for (const [key, value] of source.searchParams) {
      if (!allowed.includes(key) || source.searchParams.getAll(key).length !== 1) fail(400);
      target.searchParams.set(key, value);
    }
    let body: string | undefined;
    let rejectedBodyStatus: number | undefined;
    if (post) {
      if (request.headers.get("origin") !== config.publicOrigin) fail(403);
      const csrf = request.headers.get("x-csrf-token");
      if (!csrf || csrf.length > 4096) fail(403);
      headers.set("Origin", config.publicOrigin);
      headers.set("X-CSRF-TOKEN", csrf);
      if (path === "/api/v1/onboarding/intents") {
        try {
          const raw = await boundedText(request.body, 4096, 413);
          if (request.headers.get("content-type")?.split(";", 1)[0].trim() !== "application/json") fail(415);
          let parsed: unknown;
          try { parsed = JSON.parse(raw); } catch { fail(400); }
          if (!parsed || typeof parsed !== "object" || Array.isArray(parsed) ||
              Object.keys(parsed).length !== 1 || !("invite" in parsed) ||
              typeof parsed.invite !== "string" || !parsed.invite.trim() || parsed.invite.length > 1024) fail(400);
          body = JSON.stringify({ invite: parsed.invite });
        } catch (error) {
          if (!(error instanceof SessionApiError)) throw error;
          rejectedBodyStatus = error.status;
          // Preserve Backend's clear-before-parse invariant even when the BFF rejects the body.
          // Only a same-origin, Backend-CSRF-valid request can clear a previous invite intent.
          // The oversized/malformed/raw input is never forwarded.
          body = JSON.stringify({ invite: "" });
        }
        headers.set("Content-Type", "application/json");
      } else if (await boundedText(request.body, 4096, 413)) fail(400);
    }
    const upstream = await backendFetch(config, target.pathname + target.search, headers, request.method, body);
    const responseHeaders = new Headers(NO_STORE);
    for (const cookie of safeCookies(upstream, config)) responseHeaders.append("Set-Cookie", cookie);
    const retryAfter = upstream.headers.get("retry-after");
    if (upstream.status === 429 && retryAfter && /^[0-9]{1,3}$/.test(retryAfter) && Number(retryAfter) <= 600) {
      responseHeaders.set("Retry-After", retryAfter);
    }
    if (oauth) {
      let location = "/login?error=authentication";
      if (path === OAUTH_START && upstream.status === 302) {
        const redirect = new URL(upstream.headers.get("location") ?? "", config.publicOrigin);
        if (redirect.origin !== "https://github.com" || redirect.pathname !== "/login/oauth/authorize" ||
            redirect.username || redirect.password || redirect.hash) fail(502);
        location = redirect.toString();
      } else if (path === OAUTH_CALLBACK && upstream.status === 302) {
        // No returnTo / saved request / browser-supplied redirect targets.
        if (upstream.headers.get("location") !== "/") fail(502);
        location = "/";
      }
      await upstream.body?.cancel();
      responseHeaders.set("Location", location);
      return new Response(null, { status: 302, headers: responseHeaders });
    }
    if (rejectedBodyStatus && upstream.status === 204) fail(502);
    if (upstream.status === 204 && post) return new Response(null, { status: 204, headers: responseHeaders });
    if (upstream.status !== 200) {
      await upstream.body?.cancel();
      const status = rejectedBodyStatus && upstream.status === 400 ? rejectedBodyStatus :
        [400, 401, 403, 404, 413, 415, 422, 429, 503].includes(upstream.status) ? upstream.status : 502;
      return Response.json({ message: errorMessage(status) }, { status, headers: responseHeaders });
    }
    if (post || !upstream.headers.get("content-type")?.includes("application/json")) fail(502);
    const payload: unknown = JSON.parse(await boundedText(upstream.body, 8_388_608, 502));
    return Response.json(payload, { headers: responseHeaders });
  } catch (error) {
    const status = error instanceof SessionApiError ? error.status : 502;
    // Never log request URLs, OAuth codes, invites, cookies or upstream exception messages.
    return Response.json({ message: errorMessage(status) }, { status, headers: NO_STORE });
  }
}

function errorMessage(status: number) {
  if (status === 401) return "로그인이 필요합니다. 다시 로그인해 주세요.";
  if (status === 404) return "리소스를 찾을 수 없거나 접근 권한이 없습니다.";
  if (status === 403) return "요청을 확인할 수 없습니다. 페이지를 새로고침해 주세요.";
  if (status === 429) return "요청이 많습니다. 잠시 후 다시 시도해 주세요.";
  if (status < 500) return "요청을 처리할 수 없습니다. 입력과 조회 조건을 확인해 주세요.";
  return "서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.";
}
