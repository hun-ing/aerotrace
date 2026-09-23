import { notFound, redirect } from "next/navigation";
import { loadWorkspace } from "@/lib/server/workspace";
import WorkspaceHeader from "@/features/auth/workspace-header";
import WorkspaceUnavailable from "@/features/auth/workspace-unavailable";

export const dynamic = "force-dynamic";

export default async function Home({ searchParams }: { searchParams: Promise<{ tenant?: string }> }) {
  const { tenant } = await searchParams;
  const workspace = await loadWorkspace(undefined, typeof tenant === "string" ? tenant : undefined);
  if (workspace.kind === "login") redirect("/login");
  if (workspace.kind === "hidden") notFound();
  if (workspace.kind !== "ready") return <WorkspaceUnavailable />;
  return <>
    <WorkspaceHeader user={workspace.user} tenantId={workspace.tenantId} projects={workspace.projects} />
    <main className="workspace-home">
      <p className="eyebrow">Your workspace</p>
      <h1>프로젝트 선택</h1>
      <p>소속 조직에서 접근 가능한 프로젝트입니다.</p>
      {!workspace.tenantId ? <p>아직 소속 조직이 없습니다. 운영자에게 초대를 요청해 주세요. <a href="/login">초대 코드 입력</a></p> :
        !workspace.projects.length ? <p>이 조직에는 프로젝트가 없습니다. 운영자에게 프로젝트 생성을 요청해 주세요.</p> :
        <div className="project-grid">{workspace.projects.map(project =>
          <a className="project-card" href={`/projects/${project.projectId}`} key={project.projectId}>
            <h2>{project.name}</h2><p>{project.slug}</p><span>Trace 조회 →</span>
          </a>)}</div>}
    </main>
  </>;
}
