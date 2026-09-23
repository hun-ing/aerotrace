import Link from "next/link";

export default function WorkspaceUnavailable() {
  return <main className="auth-page"><section className="auth-card">
    <h1>서비스에 연결할 수 없습니다</h1>
    <p>일시적인 연결 문제이거나 인증 설정이 준비되지 않았습니다. 잠시 후 다시 시도해 주세요.</p>
    <Link href="/" prefetch={false} className="secondary-button auth-action">다시 시도</Link>
  </section></main>;
}
