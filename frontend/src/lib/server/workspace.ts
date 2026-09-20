import "server-only";

import { headers } from "next/headers";
import { cache } from "react";
import { isCurrentUser, isProject, UUID_PATTERN, type CurrentUser, type Project } from "@/features/auth/types";
import { sessionRead, SessionApiError } from "./session-proxy";

type WorkspaceResult = { kind: "login" | "hidden" | "unavailable" } | {
  kind: "ready"; user: CurrentUser; tenantId: string | null; projects: Project[]; project: Project | null;
};

// React cache is request-scoped, not a shared user/session/permission cache.
export const loadWorkspace = cache(async (projectId?: string, requestedTenant?: string): Promise<WorkspaceResult> => {
  try {
    const incoming = new Headers(await headers());
    const user: unknown = await sessionRead("/api/v1/me", incoming);
    if (!isCurrentUser(user)) throw new SessionApiError(502);
    let project: Project | null = null;
    if (projectId) {
      if (!UUID_PATTERN.test(projectId)) return { kind: "hidden" };
      const value: unknown = await sessionRead(`/api/v1/projects/${projectId}`, incoming);
      if (!isProject(value) || value.projectId.toLowerCase() !== projectId.toLowerCase()) throw new SessionApiError(502);
      project = value;
    }
    const tenantId = project?.tenantId ?? requestedTenant ?? user.memberships[0]?.tenantId ?? null;
    if (tenantId && !user.memberships.some(m => m.tenantId === tenantId)) return { kind: "hidden" };
    const projects: unknown = tenantId ? await sessionRead(`/api/v1/tenants/${tenantId}/projects`, incoming) : [];
    if (!Array.isArray(projects) || !projects.every(p => isProject(p) && p.tenantId === tenantId)) throw new SessionApiError(502);
    return { kind: "ready", user, tenantId, projects, project };
  } catch (error) {
    if (error instanceof SessionApiError && error.status === 401) return { kind: "login" };
    if (error instanceof SessionApiError && error.status === 404) return { kind: "hidden" };
    return { kind: "unavailable" };
  }
});
