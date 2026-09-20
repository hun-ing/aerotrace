import { notFound, redirect } from "next/navigation";
import { loadWorkspace } from "@/lib/server/workspace";
import TraceExplorer from "@/features/traces/trace-explorer";
import WorkspaceHeader from "@/features/auth/workspace-header";
import WorkspaceUnavailable from "@/features/auth/workspace-unavailable";

export const dynamic = "force-dynamic";

export default async function ProjectPage({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  const workspace = await loadWorkspace(projectId);
  if (workspace.kind === "login") redirect("/login");
  if (workspace.kind === "hidden") notFound();
  if (workspace.kind !== "ready" || !workspace.project) return <WorkspaceUnavailable />;
  return <>
    <WorkspaceHeader user={workspace.user} tenantId={workspace.tenantId} projects={workspace.projects} projectId={workspace.project.projectId} />
    <TraceExplorer key={workspace.project.projectId} projectId={workspace.project.projectId} projectName={workspace.project.name} />
  </>;
}
