export function GET() {
  // Process health only: does not authenticate or claim Backend readiness.
  return Response.json({ status: "ok" }, { headers: { "Cache-Control": "no-store" } });
}
