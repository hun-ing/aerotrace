"use client";

export async function sessionFetch(input: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(input, { ...init, credentials: "same-origin", cache: "no-store" });
  if (response.status === 401) {
    // Full navigation drops project data and client/router caches after session expiry.
    window.location.replace("/login?error=session");
    throw new Error("세션이 만료되었습니다. 다시 로그인해 주세요.");
  }
  return response;
}

export async function sessionPost(path: "/api/v1/onboarding/intents" | "/api/v1/logout", body?: { invite: string }) {
  const csrfResponse = await fetch("/api/v1/auth/csrf", { credentials: "same-origin", cache: "no-store" });
  if (!csrfResponse.ok) throw new Error("요청을 준비하지 못했습니다. 잠시 후 다시 시도해 주세요.");
  const csrf: unknown = await csrfResponse.json();
  if (!csrf || typeof csrf !== "object" || !("token" in csrf) || typeof csrf.token !== "string" ||
      !("headerName" in csrf) || csrf.headerName !== "X-CSRF-TOKEN") {
    throw new Error("인증 응답을 확인하지 못했습니다.");
  }
  const headers: Record<string, string> = { "X-CSRF-TOKEN": csrf.token };
  if (body) headers["Content-Type"] = "application/json";
  const response = await fetch(path, {
    method: "POST", headers, credentials: "same-origin", cache: "no-store",
    body: body ? JSON.stringify(body) : undefined,
  });
  if (path === "/api/v1/logout" && response.status === 401) return;
  if (response.status !== 204) {
    throw new Error(response.status === 429 ? "요청이 많습니다. 잠시 후 다시 시도해 주세요." :
      "요청을 처리하지 못했습니다. 초대 코드 또는 세션 상태를 확인해 주세요.");
  }
}
