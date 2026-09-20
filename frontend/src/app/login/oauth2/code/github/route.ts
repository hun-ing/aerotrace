import { proxySessionRequest } from "@/lib/server/session-proxy";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const GET = proxySessionRequest;
export const HEAD = proxySessionRequest;
