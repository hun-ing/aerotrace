"use client";

import { useState, type FormEvent } from "react";
import { sessionPost } from "./session-client";

export default function LoginForm({ failed }: { failed: boolean }) {
  const [invite, setInvite] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(failed ? "로그인하지 못했거나 세션이 만료되었습니다. 다시 시도해 주세요." : "");

  async function acceptInvite(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await sessionPost("/api/v1/onboarding/intents", { invite: invite.trim() });
      setInvite("");
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination -- OAuth starts a browser navigation, not an RSC transition.
      window.location.assign("/oauth2/authorization/github");
    } catch (error) {
      setError(error instanceof Error ? error.message : "로그인을 시작하지 못했습니다.");
      setBusy(false);
    }
  }

  return <main className="auth-page">
    <section className="auth-card" aria-labelledby="login-title">
      <p className="eyebrow">AeroTrace · OpenTelemetry APM</p>
      <h1 id="login-title">실행 흐름을 함께 살펴보세요</h1>
      <p>GitHub 계정으로 로그인하고 소속 조직의 프로젝트를 조회합니다.</p>
      {error && <p className="auth-error" role="alert">{error}</p>}
      <a className="primary-button auth-action" href="/oauth2/authorization/github" aria-disabled={busy}
        onClick={event => { if (busy) event.preventDefault(); }}>GitHub으로 로그인</a>
      <p className="auth-note">기존 멤버는 바로 로그인할 수 있습니다. 처음 참여하거나 새 조직에 합류하려면 운영자에게 받은 초대 코드를 입력하세요.</p>
      <form onSubmit={acceptInvite}>
        <label htmlFor="invite">초대 코드</label>
        <input id="invite" name="invite" type="password" autoComplete="off" spellCheck={false}
          value={invite} onChange={event => setInvite(event.target.value)} required maxLength={1024} disabled={busy} />
        <button className="secondary-button auth-action" type="submit" disabled={busy || !invite.trim()}>
          {busy ? "로그인 준비 중…" : "초대 코드로 참여하기"}
        </button>
      </form>
      <p className="auth-note">초대 코드는 URL·브라우저 저장소에 보관하지 않습니다. GitHub 이메일 주소나 저장소 접근 권한은 요청하지 않습니다.</p>
    </section>
  </main>;
}
