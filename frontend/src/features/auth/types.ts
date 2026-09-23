export type Membership = Readonly<{
  tenantId: string; tenantName: string; tenantSlug: string; role: "OWNER" | "ADMIN" | "VIEWER";
}>;

export type CurrentUser = Readonly<{
  userId: string; displayName: string; memberships: readonly Membership[];
}>;

export type Project = Readonly<{
  projectId: string; tenantId: string; name: string; slug: string;
}>;

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isProject(value: unknown): value is Project {
  if (!value || typeof value !== "object") return false;
  const p = value as Project;
  return typeof p.projectId === "string" && UUID_PATTERN.test(p.projectId) &&
    typeof p.tenantId === "string" && UUID_PATTERN.test(p.tenantId) &&
    typeof p.name === "string" && typeof p.slug === "string";
}

export function isCurrentUser(value: unknown): value is CurrentUser {
  if (!value || typeof value !== "object") return false;
  const u = value as CurrentUser;
  return typeof u.userId === "string" && UUID_PATTERN.test(u.userId) &&
    typeof u.displayName === "string" && Array.isArray(u.memberships) &&
    u.memberships.every(m => m && typeof m.tenantId === "string" && UUID_PATTERN.test(m.tenantId) &&
      typeof m.tenantName === "string" && typeof m.tenantSlug === "string" &&
      ["OWNER", "ADMIN", "VIEWER"].includes(m.role));
}
