"use client";

/* eslint-disable @next/next/no-html-link-for-pages, @next/next/no-location-assign-relative-destination -- Full navigation intentionally clears project state and rechecks Backend authorization. */

import { useEffect, useState } from "react";
import type { CurrentUser, Project } from "./types";
import { sessionPost } from "./session-client";

export default function WorkspaceHeader({ user, tenantId, projects, projectId }: {
  user: CurrentUser; tenantId: string | null; projects: readonly Project[]; projectId?: string;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const membership = user.memberships.find(m => m.tenantId === tenantId);

  useEffect(() => {
    const revalidate = (event: PageTransitionEvent) => {
      if (event.persisted) window.location.reload();
    };
    window.addEventListener("pageshow", revalidate);
    return () => window.removeEventListener("pageshow", revalidate);
  }, []);

  async function logout() {
    setBusy(true);
    setError("");
    try {
      await sessionPost("/api/v1/logout");
      window.location.replace("/login");
    } catch {
      setError("로그아웃하지 못했습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요.");
      setBusy(false);
    }
  }

  return <header className="workspace-header">
    <a href="/" className="workspace-brand">AeroTrace</a>
    <label>조직
      <select aria-label="조직" value={tenantId ?? ""} disabled={!user.memberships.length || busy}
        onChange={event => window.location.assign(`/?tenant=${encodeURIComponent(event.target.value)}`)}>
        {!user.memberships.length && <option value="">소속 조직 없음</option>}
        {user.memberships.map(m => <option key={m.tenantId} value={m.tenantId}>{m.tenantName}</option>)}
      </select>
    </label>
    <label>프로젝트
      <select aria-label="프로젝트" value={projectId ?? ""} disabled={!projects.length || busy}
        onChange={event => { if (event.target.value) window.location.assign(`/projects/${event.target.value}`); }}>
        <option value="" disabled>프로젝트 선택</option>
        {projects.map(p => <option key={p.projectId} value={p.projectId}>{p.name}</option>)}
      </select>
    </label>
    <span className="workspace-identity">{user.displayName}{membership ? ` · ${membership.role}` : ""}</span>
    <button type="button" className="secondary-button" onClick={logout} disabled={busy}>
      {busy ? "로그아웃 중…" : "로그아웃"}
    </button>
    {error && <p className="auth-error" role="alert">{error}</p>}
  </header>;
}
