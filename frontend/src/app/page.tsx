const overviewItems = [
  {
    label: "Traces",
    value: "—",
    description: "선택한 기간의 Trace",
  },
  {
    label: "Error traces",
    value: "—",
    description: "오류 Span을 포함한 Trace",
  },
  {
    label: "Longest span",
    value: "—",
    description: "가장 긴 Span duration",
  },
  {
    label: "Services",
    value: "—",
    description: "관측된 서비스 수",
  },
] as const;

const navigationItems = [
  {
    label: "Traces",
    active: true,
  },
  {
    label: "Services",
    active: false,
  },
  {
    label: "Settings",
    active: false,
  },
] as const;

export default function Home() {
  return (
      <div className="app-shell">
        <aside className="sidebar">
          <div className="brand">
            <div className="brand-mark" aria-hidden="true">
              A
            </div>

            <div>
              <p className="brand-name">AeroTrace</p>
              <p className="brand-description">OpenTelemetry APM</p>
            </div>
          </div>

          <nav className="navigation" aria-label="주요 메뉴">
            {navigationItems.map((item) => (
                <button
                    className={`navigation-item ${
                        item.active ? "navigation-item-active" : ""
                    }`}
                    disabled={!item.active}
                    key={item.label}
                    type="button"
                >
                  <span className="navigation-dot" aria-hidden="true" />
                  {item.label}
                </button>
            ))}
          </nav>

          <div className="sidebar-footer">
            <div className="connection-status">
              <span className="status-indicator" aria-hidden="true" />

              <div>
                <p className="status-title">Backend not connected</p>
                <p className="status-description">API 연결 전 단계</p>
              </div>
            </div>
          </div>
        </aside>

        <main className="main-content">
          <header className="topbar">
            <div>
              <p className="eyebrow">Trace explorer</p>
              <h1>Traces</h1>
              <p className="page-description">
                수집된 요청의 실행 흐름과 오류, 지연시간을 조회합니다.
              </p>
            </div>

            <div className="environment-badge">
              <span aria-hidden="true" />
              Local development
            </div>
          </header>

          <section className="overview-grid" aria-label="Trace 조회 요약">
            {overviewItems.map((item) => (
                <article className="overview-card" key={item.label}>
                  <p className="overview-label">{item.label}</p>
                  <p className="overview-value">{item.value}</p>
                  <p className="overview-description">{item.description}</p>
                </article>
            ))}
          </section>

          <section className="panel">
            <div className="panel-header">
              <div>
                <h2>Filters</h2>
                <p>백엔드 연결 후 조회 조건이 활성화됩니다.</p>
              </div>

              <button className="secondary-button" disabled type="button">
                Reset
              </button>
            </div>

            <div className="filter-grid">
              <label className="field">
                <span>From</span>
                <input
                    aria-label="조회 시작 시각"
                    disabled
                    type="datetime-local"
                />
              </label>

              <label className="field">
                <span>To</span>
                <input
                    aria-label="조회 종료 시각"
                    disabled
                    type="datetime-local"
                />
              </label>

              <label className="field">
                <span>Service</span>
                <input
                    disabled
                    placeholder="service.name"
                    type="text"
                />
              </label>

              <label className="field">
                <span>Minimum span duration</span>
                <input
                    disabled
                    placeholder="예: 250 ms"
                    type="text"
                />
              </label>

              <label className="checkbox-field">
                <input disabled type="checkbox" />
                <span>Error traces only</span>
              </label>

              <button className="primary-button" disabled type="button">
                Search traces
              </button>
            </div>
          </section>

          <section className="panel trace-panel">
            <div className="panel-header">
              <div>
                <h2>Trace results</h2>
                <p>최신 Trace부터 표시됩니다.</p>
              </div>

              <span className="result-count">0 results</span>
            </div>

            <div className="trace-table-wrapper">
              <table className="trace-table">
                <thead>
                <tr>
                  <th scope="col">Trace ID</th>
                  <th scope="col">Started at</th>
                  <th scope="col">Spans</th>
                  <th scope="col">Services</th>
                  <th scope="col">Longest span</th>
                </tr>
                </thead>

                <tbody>
                <tr>
                  <td className="empty-cell" colSpan={5}>
                    <div className="empty-state">
                      <div className="empty-icon" aria-hidden="true">
                        ↗
                      </div>

                      <h3>No traces loaded</h3>

                      <p>
                        다음 단계에서 AeroTrace 백엔드 조회 API와 연결합니다.
                      </p>
                    </div>
                  </td>
                </tr>
                </tbody>
              </table>
            </div>
          </section>
        </main>
      </div>
  );
}