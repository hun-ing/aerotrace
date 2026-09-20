import "server-only";

export type BackendConfig = Readonly<{
  baseUrl: string;
  publicOrigin: string;
  publicHost: string;
  cookieName: string;
  secureCookie: boolean;
}>;

function origin(name: string): URL {
  const value = process.env[name]?.trim();
  if (!value) throw new Error("Missing server configuration");
  const url = new URL(value);
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password ||
      url.pathname !== "/" || url.search || url.hash) {
    throw new Error("Invalid server origin configuration");
  }
  return url;
}

export function getAeroTraceBackendConfig(): BackendConfig {
  const backend = origin("AEROTRACE_BACKEND_BASE_URL");
  const publicUrl = origin("AEROTRACE_PUBLIC_ORIGIN");
  const insecure = process.env.AEROTRACE_AUTH_ALLOW_INSECURE_LOCAL === "true";
  if (insecure) {
    if (publicUrl.protocol !== "http:" || !["localhost", "127.0.0.1", "[::1]"].includes(publicUrl.hostname)) {
      throw new Error("Insecure authentication is limited to explicit HTTP loopback origins");
    }
  } else if (publicUrl.protocol !== "https:") {
    throw new Error("Authentication requires HTTPS");
  }
  return {
    baseUrl: backend.origin,
    publicOrigin: publicUrl.origin,
    publicHost: publicUrl.host,
    cookieName: insecure ? "aerotrace_session" : "__Host-aerotrace_session",
    secureCookie: !insecure,
  };
}
