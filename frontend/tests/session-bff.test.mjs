import assert from "node:assert/strict";
import { createServer, request as httpRequest } from "node:http";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { setTimeout as delay } from "node:timers/promises";
import test from "node:test";

const tenantA = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const tenantB = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
const projectA = "11111111-1111-1111-1111-111111111111";
const projectB = "22222222-2222-2222-2222-222222222222";
const projects = {
  [projectA]: { projectId: projectA, tenantId: tenantA, name: "Alpha project", slug: "alpha" },
  [projectB]: { projectId: projectB, tenantId: tenantB, name: "Beta project", slug: "beta" },
};
const cookieValue = "test-session-not-a-production-secret";
const inviteValue = "test-invite-not-a-production-secret";
const codeValue = "test-oauth-code-not-a-production-secret";

async function listen(server) {
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  return server.address().port;
}

async function startFrontend(backendPort, secure = false) {
  const reservation = createServer();
  const port = await listen(reservation);
  await new Promise(resolve => reservation.close(resolve));
  const host = secure ? "app.example.test" : `127.0.0.1:${port}`;
  const publicOrigin = `${secure ? "https" : "http"}://${host}`;
  const child = spawn(process.execPath, [".next/standalone/server.js"], {
    cwd: process.cwd(), stdio: ["ignore", "pipe", "pipe"],
    env: { ...process.env, NODE_ENV: "production", NEXT_TELEMETRY_DISABLED: "1",
      HOSTNAME: "127.0.0.1", PORT: String(port),
      AEROTRACE_BACKEND_BASE_URL: `http://127.0.0.1:${backendPort}`, AEROTRACE_PUBLIC_ORIGIN: publicOrigin,
      AEROTRACE_AUTH_ALLOW_INSECURE_LOCAL: secure ? "false" : "true",
      AEROTRACE_API_KEY: "atr_canary_must_never_be_forwarded" },
  });
  let logs = "";
  child.stdout.on("data", data => { logs += data; });
  child.stderr.on("data", data => { logs += data; });
  const url = `http://127.0.0.1:${port}`;
  // Native HTTP deliberately controls Host. Node fetch may replace that header with the URL host.
  const request = (path, init = {}) => new Promise((resolve, reject) => {
    const request = httpRequest(url + path, {
      method: init.method ?? "GET", headers: { Host: host, ...init.headers }, signal: AbortSignal.timeout(10_000),
    }, response => {
      const chunks = [];
      response.on("data", chunk => chunks.push(chunk));
      response.on("error", reject);
      response.on("end", () => {
        const headers = new Headers();
        for (let i = 0; i < response.rawHeaders.length; i += 2) headers.append(response.rawHeaders[i], response.rawHeaders[i + 1]);
        const noBody = init.method === "HEAD" || [204, 205, 304].includes(response.statusCode);
        resolve(new Response(noBody ? null : Buffer.concat(chunks), { status: response.statusCode, headers }));
      });
    });
    request.on("error", reject);
    request.end(init.body);
  });
  for (let attempt = 0; attempt < 100; attempt++) {
    if (child.exitCode !== null) throw new Error("Next.js test server exited during startup");
    try { if ((await request("/health")).ok) return { child, request, publicOrigin, logs: () => logs }; } catch { /* wait for startup */ }
    await delay(100);
  }
  child.kill("SIGTERM");
  throw new Error("Next.js test server did not become ready");
}

test("session BFF and server-rendered authorization contract", { timeout: 120_000 }, async t => {
  const seen = [];
  let mode = "normal";
  let secureCookie = false;
  const sessionName = () => secureCookie ? "__Host-aerotrace_session" : "aerotrace_session";
  const setCookie = (value = cookieValue) => `${sessionName()}=${value}; Path=/; HttpOnly; SameSite=Lax${secureCookie ? "; Secure" : ""}${!value ? "; Max-Age=0" : ""}`;
  const backend = createServer(async (request, response) => {
    const url = new URL(request.url, "http://backend.invalid");
    let body = "";
    for await (const chunk of request) body += chunk;
    seen.push({ path: url.pathname, query: url.search, headers: request.headers, method: request.method, body });
    response.setHeader("Content-Type", "application/json");
    if (mode === "upstream-error") { response.writeHead(500).end('{"message":"private SQL details"}'); return; }
    if (mode === "upstream-redirect") { response.writeHead(302, { Location: "https://attacker.invalid/" }).end(); return; }
    if (mode === "bad-json") { response.end("not json"); return; }
    if (mode === "unsafe-cookie") {
      response.setHeader("Set-Cookie", `${sessionName()}=value; Path=/; HttpOnly; SameSite=Lax; Domain=example.test`);
    }
    if (url.pathname === "/api/v1/auth/csrf") {
      if (mode !== "unsafe-cookie") response.setHeader("Set-Cookie", setCookie());
      response.end(JSON.stringify({ headerName: "X-CSRF-TOKEN", parameterName: "_csrf", token: "csrf-test-token" })); return;
    }
    if (url.pathname === "/oauth2/authorization/github") {
      response.writeHead(302, { "Set-Cookie": setCookie(), Location: "https://github.com/login/oauth/authorize?state=test-state&code_challenge=pkce" }).end(); return;
    }
    if (url.pathname === "/login/oauth2/code/github") {
      if (mode === "oauth-failure") response.writeHead(401, { "Set-Cookie": setCookie("") }).end('{"message":"secret failure details"}');
      else response.writeHead(302, { "Set-Cookie": setCookie("rotated-test-session"), Location: "/" }).end();
      return;
    }
    if (url.pathname === "/api/v1/onboarding/intents" || url.pathname === "/api/v1/logout") {
      if (request.headers["x-csrf-token"] !== "csrf-test-token") { response.writeHead(403).end("{}"); return; }
      if (url.pathname.endsWith("intents") && !JSON.parse(body).invite) { response.writeHead(400).end("{}"); return; }
      if (url.pathname.endsWith("logout")) response.setHeader("Set-Cookie", setCookie(""));
      response.writeHead(204).end(); return;
    }
    const cookie = request.headers.cookie ?? "";
    if (!cookie.includes(`${sessionName()}=${cookieValue}`) && !cookie.includes(`${sessionName()}=user-b`)) {
      response.writeHead(401).end("{}"); return;
    }
    const isB = cookie.includes("user-b");
    const tenant = isB ? tenantB : tenantA;
    const project = projects[isB ? projectB : projectA];
    const memberships = mode === "no-memberships" ? [] : [{ tenantId: tenant, tenantName: isB ? "Beta tenant" : "Alpha tenant", tenantSlug: "tenant", role: "VIEWER" }];
    if (url.pathname === "/api/v1/me") {
      response.end(JSON.stringify({ userId: tenant, displayName: isB ? "Bob" : "Alice", memberships })); return;
    }
    if (url.pathname === "/api/v1/tenants") { response.end(JSON.stringify(memberships)); return; }
    if (url.pathname === `/api/v1/tenants/${tenant}/projects`) {
      response.end(JSON.stringify(mode === "empty-projects" ? [] : [project])); return;
    }
    if (url.pathname === `/api/v1/projects/${project.projectId}`) { response.end(JSON.stringify(project)); return; }
    if (url.pathname === `/api/v1/projects/${project.projectId}/traces`) {
      response.end(JSON.stringify({ items: [], nextCursor: null })); return;
    }
    response.writeHead(404).end('{"message":"Resource is unavailable"}');
  });
  const backendPort = await listen(backend);
  const frontends = [];
  t.after(async () => {
    for (const frontend of frontends) {
      if (frontend.child.exitCode === null) {
        const exited = once(frontend.child, "exit");
        frontend.child.kill("SIGTERM");
        await exited;
      }
      for (const secret of [cookieValue, inviteValue, codeValue, "private SQL details"]) assert.ok(!frontend.logs().includes(secret));
    }
    backend.closeAllConnections();
    await new Promise(resolve => backend.close(resolve));
  });
  const local = await startFrontend(backendPort);
  frontends.push(local);
  const auth = { Cookie: `aerotrace_session=${cookieValue}` };

  await t.test("anonymous SSR redirects to login; login and process health reveal no traces", async () => {
    const response = await local.request("/");
    assert.equal(response.status, 307);
    assert.equal(response.headers.get("location"), "/login");
    assert.match(response.headers.get("cache-control"), /no-store/);
    const login = await local.request("/login?error=do-not-echo-me");
    const html = await login.text();
    assert.equal(login.status, 200);
    assert.match(html, /GitHub으로 로그인/);
    assert.match(html, /초대 코드/);
    assert.ok(!html.includes("Alpha project"));
    assert.equal(login.headers.get("referrer-policy"), "no-referrer");
  });

  await t.test("SSR and BFF do not share data across sessions", async () => {
    const alice = await (await local.request("/", { headers: auth })).text();
    const bob = await (await local.request("/", { headers: { Cookie: "aerotrace_session=user-b" } })).text();
    assert.match(alice, /Alpha project/); assert.ok(!alice.includes("Beta project"));
    assert.match(bob, /Beta project/); assert.ok(!bob.includes("Alpha project"));
    const project = await local.request(`/projects/${projectA}`, { headers: auth });
    assert.equal(project.status, 200); assert.match(await project.text(), /Trace explorer/);
    const hidden = await local.request(`/projects/${projectB}`, { headers: auth });
    assert.equal(hidden.status, 404); assert.ok(!(await hidden.text()).includes("Beta project"));
  });

  await t.test("only selected session cookie and allowlisted query parameters reach Backend", async () => {
    const response = await local.request(`/api/v1/projects/${projectA}/traces?from=2026-09-01T00%3A00%3A00Z&to=2026-09-02T00%3A00%3A00Z`, {
      headers: { ...auth, Cookie: `unrelated=secret; ${auth.Cookie}; __Host-aerotrace_session=wrong-mode`,
        Authorization: "Bearer attacker", "X-Forwarded-Host": "attacker.invalid", "X-User-Id": tenantB, Forwarded: "host=attacker.invalid" },
    });
    assert.equal(response.status, 200);
    assert.match(response.headers.get("cache-control"), /no-store/);
    const request = seen.at(-1);
    assert.equal(request.headers.cookie, auth.Cookie);
    for (const name of ["authorization", "x-forwarded-host", "x-user-id", "forwarded"]) assert.equal(request.headers[name], undefined);
    assert.match(request.query, /from=/);
    assert.ok(!JSON.stringify(request).includes("atr_canary"));
  });

  await t.test("unknown routes, methods, duplicate cookies and parameters fail before proxying", async () => {
    for (const [path, init, status] of [
      ["/api/v1/admin", {}, 404], ["/api/v1/me?target=http://attacker.invalid", {}, 400],
      [`/api/v1/projects/${projectA}/traces?limit=1&limit=2`, {}, 400],
      ["/api/v1/me", { method: "POST" }, 405], ["/api/v1/me", { method: "HEAD" }, 405],
      ["/api/v1/me", { headers: { Cookie: `${auth.Cookie}; ${auth.Cookie}` } }, 400],
      ["/api/v1/me", { headers: { Host: "attacker.invalid" } }, 403],
    ]) {
      const count = seen.length;
      const response = await local.request(path, init);
      assert.equal(response.status, status, path);
      assert.equal(seen.length, count, "must not reach backend");
    }
    assert.equal((await local.request("/api/traces")).status, 404);
  });

  await t.test("CSRF bootstrap preserves local cookie and onboarding requires exact Origin + token", async () => {
    const csrf = await local.request("/api/v1/auth/csrf");
    assert.equal(csrf.status, 200);
    assert.match(csrf.headers.get("set-cookie"), /^aerotrace_session=.*Path=\/; HttpOnly; SameSite=Lax$/);
    const body = JSON.stringify({ invite: inviteValue });
    for (const headers of [{}, { Origin: "http://attacker.invalid", "X-CSRF-TOKEN": "csrf-test-token" }, { Origin: local.publicOrigin }]) {
      assert.equal((await local.request("/api/v1/onboarding/intents", { method: "POST", body, headers })).status, 403);
    }
    const response = await local.request("/api/v1/onboarding/intents", { method: "POST", body,
      headers: { ...auth, Origin: local.publicOrigin, "X-CSRF-TOKEN": "csrf-test-token", "Content-Type": "application/json" } });
    assert.equal(response.status, 204);
    assert.deepEqual(JSON.parse(seen.at(-1).body), { invite: inviteValue });
    assert.equal(seen.at(-1).headers.origin, local.publicOrigin);
  });

  await t.test("rejected onboarding bodies send only an invalid sentinel to clear stale Backend intent", async () => {
    const headers = { ...auth, Origin: local.publicOrigin, "X-CSRF-TOKEN": "csrf-test-token", "Content-Type": "application/json" };
    for (const [body, status] of [["x".repeat(4097), 413], ["{", 400], [Buffer.from([0xc3, 0x28]), 400], [JSON.stringify({ invite: "x", tenantId: tenantB }), 400]]) {
      const count = seen.length;
      assert.equal((await local.request("/api/v1/onboarding/intents", { method: "POST", headers, body })).status, status);
      assert.equal(seen.length, count + 1);
      assert.equal(seen.at(-1).body, '{"invite":""}');
    }
  });

  await t.test("OAuth start/callback only allow pinned redirects and preserve rotation/deletion", async () => {
    let response = await local.request("/oauth2/authorization/github");
    assert.equal(response.status, 302);
    assert.match(response.headers.get("location"), /^https:\/\/github.com\/login\/oauth\/authorize\?/);
    response = await local.request(`/login/oauth2/code/github?code=${codeValue}&state=test-state`, { headers: auth });
    assert.equal(response.headers.get("location"), "/");
    assert.match(response.headers.get("set-cookie"), /rotated-test-session/);
    mode = "oauth-failure";
    response = await local.request("/login/oauth2/code/github?error=access_denied", { headers: auth });
    assert.equal(response.headers.get("location"), "/login?error=authentication");
    assert.match(response.headers.get("set-cookie"), /Max-Age=0/);
    mode = "upstream-redirect";
    assert.equal((await local.request("/oauth2/authorization/github")).status, 502);
    assert.equal((await local.request("/login/oauth2/code/github?code=x&state=y")).status, 502);
    mode = "normal";
  });

  await t.test("logout forwards CSRF and clears the session cookie", async () => {
    const response = await local.request("/api/v1/logout", { method: "POST",
      headers: { ...auth, Origin: local.publicOrigin, "X-CSRF-TOKEN": "csrf-test-token" } });
    assert.equal(response.status, 204);
    assert.match(response.headers.get("set-cookie"), /Max-Age=0/);
  });

  await t.test("empty memberships/projects and Backend outages show safe states", async () => {
    mode = "no-memberships";
    assert.match(await (await local.request("/", { headers: auth })).text(), /아직 소속 조직이 없습니다/);
    mode = "empty-projects";
    assert.match(await (await local.request("/", { headers: auth })).text(), /이 조직에는 프로젝트가 없습니다/);
    for (const nextMode of ["upstream-error", "bad-json", "upstream-redirect"]) {
      mode = nextMode;
      const response = await local.request("/api/v1/me", { headers: auth });
      assert.equal(response.status, 502);
      assert.ok(!(await response.text()).includes("private SQL details"));
      const html = await (await local.request("/", { headers: auth })).text();
      assert.match(html, /서비스에 연결할 수 없습니다/);
      assert.ok(!html.includes("Alpha project"));
    }
    mode = "normal";
  });

  await t.test("production cookie keeps __Host, Secure, HttpOnly and no Domain", async () => {
    const production = await startFrontend(backendPort, true);
    frontends.push(production);
    secureCookie = true;
    const response = await production.request("/api/v1/auth/csrf");
    assert.equal(response.status, 200);
    assert.match(response.headers.get("set-cookie"), /^__Host-aerotrace_session=.*; Secure$/);
    mode = "unsafe-cookie";
    const unsafe = await production.request("/api/v1/auth/csrf");
    assert.equal(unsafe.status, 502);
    assert.equal(unsafe.headers.get("set-cookie"), null);
    mode = "normal";
    secureCookie = false;
  });
});
