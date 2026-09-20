import { proxySessionRequest } from "@/lib/server/session-proxy";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const GET = proxySessionRequest;
export const POST = proxySessionRequest;
export const HEAD = proxySessionRequest;
export const OPTIONS = proxySessionRequest;
export const PUT = proxySessionRequest;
export const PATCH = proxySessionRequest;
export const DELETE = proxySessionRequest;
