"use client";

import {
    useEffect,
    useMemo,
    useState,
} from "react";

type TraceListItem = Readonly<{
    traceId: string;
    traceStartTime: string;
    spanCount: number;
    serviceCount: number;
    longestSpanDurationNano: number;
}>;

type TraceListResponse = Readonly<{
    items: readonly TraceListItem[];
    nextCursor: string | null;
}>;

type LoadingState =
    | "loading"
    | "success"
    | "error";

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

const INITIAL_RANGE_DAYS = 7;
const INITIAL_LIMIT = 50;

function isRecord(
    value: unknown,
): value is Record<string, unknown> {
    return (
        typeof value === "object" &&
        value !== null &&
        !Array.isArray(value)
    );
}

function requireString(
    value: unknown,
    fieldName: string,
): string {
    if (
        typeof value !== "string" ||
        value.trim().length === 0
    ) {
        throw new Error(
            `${fieldName} 응답 형식이 올바르지 않습니다.`,
        );
    }

    return value;
}

function requireNonNegativeNumber(
    value: unknown,
    fieldName: string,
): number {
    if (
        typeof value !== "number" ||
        !Number.isFinite(value) ||
        value < 0
    ) {
        throw new Error(
            `${fieldName} 응답 형식이 올바르지 않습니다.`,
        );
    }

    return value;
}

function parseTraceListItem(
    value: unknown,
    index: number,
): TraceListItem {
    if (!isRecord(value)) {
        throw new Error(
            `items[${index}] 응답 형식이 올바르지 않습니다.`,
        );
    }

    return {
        traceId: requireString(
            value.traceId,
            `items[${index}].traceId`,
        ),
        traceStartTime: requireString(
            value.traceStartTime,
            `items[${index}].traceStartTime`,
        ),
        spanCount: requireNonNegativeNumber(
            value.spanCount,
            `items[${index}].spanCount`,
        ),
        serviceCount: requireNonNegativeNumber(
            value.serviceCount,
            `items[${index}].serviceCount`,
        ),
        longestSpanDurationNano:
            requireNonNegativeNumber(
                value.longestSpanDurationNano,
                `items[${index}].longestSpanDurationNano`,
            ),
    };
}

function parseTraceListResponse(
    value: unknown,
): TraceListResponse {
    if (!isRecord(value)) {
        throw new Error(
            "Trace 목록 응답 형식이 올바르지 않습니다.",
        );
    }

    if (!Array.isArray(value.items)) {
        throw new Error(
            "Trace 목록의 items가 배열이 아닙니다.",
        );
    }

    if (
        value.nextCursor !== null &&
        value.nextCursor !== undefined &&
        typeof value.nextCursor !== "string"
    ) {
        throw new Error(
            "Trace 목록의 nextCursor 형식이 올바르지 않습니다.",
        );
    }

    return {
        items: value.items.map(parseTraceListItem),
        nextCursor:
            typeof value.nextCursor === "string"
                ? value.nextCursor
                : null,
    };
}

async function extractErrorMessage(
    response: Response,
): Promise<string> {
    try {
        const responseBody: unknown =
            await response.json();

        if (
            isRecord(responseBody) &&
            typeof responseBody.message === "string" &&
            responseBody.message.trim().length > 0
        ) {
            return responseBody.message;
        }
    } catch {
        // JSON이 아닌 오류 응답은 기본 메시지로 처리한다.
    }

    return "Trace 목록을 불러오지 못했습니다.";
}

function createInitialQuery(): URLSearchParams {
    const to = new Date();
    const from = new Date(
        to.getTime() -
        INITIAL_RANGE_DAYS *
        24 *
        60 *
        60 *
        1_000,
    );

    return new URLSearchParams({
        from: from.toISOString(),
        to: to.toISOString(),
        limit: String(INITIAL_LIMIT),
    });
}

function formatTimestamp(value: string): string {
    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return value;
    }

    return new Intl.DateTimeFormat(
        "ko-KR",
        {
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
            hour12: false,
        },
    ).format(date);
}

function formatDuration(
    durationNano: number,
): string {
    if (durationNano < 1_000) {
        return `${durationNano.toFixed(0)} ns`;
    }

    if (durationNano < 1_000_000) {
        return `${(
            durationNano / 1_000
        ).toFixed(2)} μs`;
    }

    if (durationNano < 1_000_000_000) {
        return `${(
            durationNano / 1_000_000
        ).toFixed(2)} ms`;
    }

    return `${(
        durationNano / 1_000_000_000
    ).toFixed(2)} s`;
}

export default function TraceExplorer() {
    const [loadingState, setLoadingState] =
        useState<LoadingState>("loading");

    const [traceResponse, setTraceResponse] =
        useState<TraceListResponse>({
            items: [],
            nextCursor: null,
        });

    const [errorMessage, setErrorMessage] =
        useState("");

    const [reloadSequence, setReloadSequence] =
        useState(0);

    useEffect(() => {
        const abortController =
            new AbortController();

        async function loadTraces(): Promise<void> {
            setLoadingState("loading");
            setErrorMessage("");

            try {
                const query = createInitialQuery();

                const response = await fetch(
                    `/api/traces?${query.toString()}`,
                    {
                        method: "GET",
                        headers: {
                            Accept: "application/json",
                        },
                        cache: "no-store",
                        signal: abortController.signal,
                    },
                );

                if (!response.ok) {
                    throw new Error(
                        await extractErrorMessage(response),
                    );
                }

                const responseBody: unknown =
                    await response.json();

                const parsedResponse =
                    parseTraceListResponse(responseBody);

                setTraceResponse(parsedResponse);
                setLoadingState("success");
            } catch (error) {
                if (
                    error instanceof DOMException &&
                    error.name === "AbortError"
                ) {
                    return;
                }

                const message =
                    error instanceof Error
                        ? error.message
                        : "Trace 목록을 불러오지 못했습니다.";

                setErrorMessage(message);
                setLoadingState("error");
            }
        }

        void loadTraces();

        return () => {
            abortController.abort();
        };
    }, [reloadSequence]);

    const longestSpanDurationNano = useMemo(
        () =>
            traceResponse.items.reduce(
                (longestDuration, trace) =>
                    Math.max(
                        longestDuration,
                        trace.longestSpanDurationNano,
                    ),
                0,
            ),
        [traceResponse.items],
    );

    const maximumServiceCount = useMemo(
        () =>
            traceResponse.items.reduce(
                (maximumCount, trace) =>
                    Math.max(
                        maximumCount,
                        trace.serviceCount,
                    ),
                0,
            ),
        [traceResponse.items],
    );

    const overviewItems = [
        {
            label: "Loaded traces",
            value:
                loadingState === "success"
                    ? traceResponse.items.length.toLocaleString()
                    : "—",
            description: `최근 ${INITIAL_RANGE_DAYS}일, 현재 페이지`,
        },
        {
            label: "More results",
            value:
                loadingState === "success"
                    ? traceResponse.nextCursor
                        ? "Yes"
                        : "No"
                    : "—",
            description: "다음 Cursor 존재 여부",
        },
        {
            label: "Longest span",
            value:
                loadingState === "success" &&
                traceResponse.items.length > 0
                    ? formatDuration(
                        longestSpanDurationNano,
                    )
                    : "—",
            description: "현재 페이지 기준",
        },
        {
            label: "Max services",
            value:
                loadingState === "success" &&
                traceResponse.items.length > 0
                    ? maximumServiceCount.toLocaleString()
                    : "—",
            description: "Trace 한 개의 최대 서비스 수",
        },
    ] as const;

    const connectionStatus =
        loadingState === "loading"
            ? {
                title: "Loading traces",
                description: "Backend 응답 대기 중",
                className:
                    "status-indicator status-indicator-loading",
            }
            : loadingState === "success"
                ? {
                    title: "Backend connected",
                    description: "Trace API 연결됨",
                    className:
                        "status-indicator status-indicator-success",
                }
                : {
                    title: "Backend unavailable",
                    description: "Trace API 호출 실패",
                    className:
                        "status-indicator status-indicator-error",
                };

    function reloadTraces(): void {
        setReloadSequence(
            (currentValue) => currentValue + 1,
        );
    }

    return (
        <div className="app-shell">
            <aside className="sidebar">
                <div className="brand">
                    <div
                        className="brand-mark"
                        aria-hidden="true"
                    >
                        A
                    </div>

                    <div>
                        <p className="brand-name">
                            AeroTrace
                        </p>
                        <p className="brand-description">
                            OpenTelemetry APM
                        </p>
                    </div>
                </div>

                <nav
                    className="navigation"
                    aria-label="주요 메뉴"
                >
                    {navigationItems.map((item) => (
                        <button
                            className={`navigation-item ${
                                item.active
                                    ? "navigation-item-active"
                                    : ""
                            }`}
                            disabled={!item.active}
                            key={item.label}
                            type="button"
                        >
              <span
                  className="navigation-dot"
                  aria-hidden="true"
              />
                            {item.label}
                        </button>
                    ))}
                </nav>

                <div className="sidebar-footer">
                    <div className="connection-status">
            <span
                className={
                    connectionStatus.className
                }
                aria-hidden="true"
            />

                        <div>
                            <p className="status-title">
                                {connectionStatus.title}
                            </p>
                            <p className="status-description">
                                {connectionStatus.description}
                            </p>
                        </div>
                    </div>
                </div>
            </aside>

            <main className="main-content">
                <header className="topbar">
                    <div>
                        <p className="eyebrow">
                            Trace explorer
                        </p>
                        <h1>Traces</h1>
                        <p className="page-description">
                            수집된 요청의 실행 흐름과 오류,
                            지연시간을 조회합니다.
                        </p>
                    </div>

                    <div className="topbar-actions">
                        <div className="environment-badge">
                            <span aria-hidden="true" />
                            Local development
                        </div>

                        <button
                            className="secondary-button refresh-button"
                            disabled={
                                loadingState === "loading"
                            }
                            onClick={reloadTraces}
                            type="button"
                        >
                            {loadingState === "loading"
                                ? "Loading..."
                                : "Refresh"}
                        </button>
                    </div>
                </header>

                <section
                    className="overview-grid"
                    aria-label="Trace 조회 요약"
                >
                    {overviewItems.map((item) => (
                        <article
                            className="overview-card"
                            key={item.label}
                        >
                            <p className="overview-label">
                                {item.label}
                            </p>
                            <p className="overview-value">
                                {item.value}
                            </p>
                            <p className="overview-description">
                                {item.description}
                            </p>
                        </article>
                    ))}
                </section>

                <section className="panel">
                    <div className="panel-header">
                        <div>
                            <h2>Filters</h2>
                            <p>
                                다음 단계에서 조회 조건을
                                활성화합니다.
                            </p>
                        </div>

                        <button
                            className="secondary-button"
                            disabled
                            type="button"
                        >
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
              <span>
                Minimum span duration
              </span>
                            <input
                                disabled
                                placeholder="예: 250 ms"
                                type="text"
                            />
                        </label>

                        <label className="checkbox-field">
                            <input
                                disabled
                                type="checkbox"
                            />
                            <span>Error traces only</span>
                        </label>

                        <button
                            className="primary-button"
                            disabled
                            type="button"
                        >
                            Search traces
                        </button>
                    </div>
                </section>

                <section
                    className="panel trace-panel"
                    aria-busy={
                        loadingState === "loading"
                    }
                >
                    <div className="panel-header">
                        <div>
                            <h2>Trace results</h2>
                            <p>
                                최근 {INITIAL_RANGE_DAYS}일의
                                최신 Trace부터 표시됩니다.
                            </p>
                        </div>

                        <span className="result-count">
              {loadingState === "success"
                  ? `${traceResponse.items.length.toLocaleString()} results`
                  : loadingState === "loading"
                      ? "Loading"
                      : "Error"}
            </span>
                    </div>

                    <div className="trace-table-wrapper">
                        <table className="trace-table">
                            <thead>
                            <tr>
                                <th scope="col">
                                    Trace ID
                                </th>
                                <th scope="col">
                                    Started at
                                </th>
                                <th scope="col">
                                    Spans
                                </th>
                                <th scope="col">
                                    Services
                                </th>
                                <th scope="col">
                                    Longest span
                                </th>
                            </tr>
                            </thead>

                            <tbody>
                            {loadingState ===
                                "loading" && (
                                    <tr>
                                        <td
                                            className="empty-cell"
                                            colSpan={5}
                                        >
                                            <div className="empty-state">
                                                <div
                                                    className="loading-spinner"
                                                    aria-hidden="true"
                                                />

                                                <h3>
                                                    Loading traces
                                                </h3>

                                                <p>
                                                    AeroTrace Backend에서
                                                    Trace 목록을 불러오고
                                                    있습니다.
                                                </p>
                                            </div>
                                        </td>
                                    </tr>
                                )}

                            {loadingState === "error" && (
                                <tr>
                                    <td
                                        className="empty-cell"
                                        colSpan={5}
                                    >
                                        <div className="empty-state">
                                            <div
                                                className="empty-icon error-icon"
                                                aria-hidden="true"
                                            >
                                                !
                                            </div>

                                            <h3>
                                                Trace request failed
                                            </h3>

                                            <p className="error-message">
                                                {errorMessage}
                                            </p>

                                            <button
                                                className="secondary-button retry-button"
                                                onClick={reloadTraces}
                                                type="button"
                                            >
                                                Retry
                                            </button>
                                        </div>
                                    </td>
                                </tr>
                            )}

                            {loadingState === "success" &&
                                traceResponse.items.length ===
                                0 && (
                                    <tr>
                                        <td
                                            className="empty-cell"
                                            colSpan={5}
                                        >
                                            <div className="empty-state">
                                                <div
                                                    className="empty-icon"
                                                    aria-hidden="true"
                                                >
                                                    ↗
                                                </div>

                                                <h3>
                                                    No traces found
                                                </h3>

                                                <p>
                                                    최근{" "}
                                                    {INITIAL_RANGE_DAYS}일
                                                    동안 수집된 Trace가
                                                    없습니다.
                                                </p>
                                            </div>
                                        </td>
                                    </tr>
                                )}

                            {loadingState === "success" &&
                                traceResponse.items.map(
                                    (trace) => (
                                        <tr
                                            className="trace-row"
                                            key={trace.traceId}
                                        >
                                            <td>
                                                <code
                                                    className="trace-id"
                                                    title={
                                                        trace.traceId
                                                    }
                                                >
                                                    {trace.traceId}
                                                </code>
                                            </td>

                                            <td>
                                                {formatTimestamp(
                                                    trace.traceStartTime,
                                                )}
                                            </td>

                                            <td className="numeric-cell">
                                                {trace.spanCount.toLocaleString()}
                                            </td>

                                            <td className="numeric-cell">
                                                {trace.serviceCount.toLocaleString()}
                                            </td>

                                            <td className="numeric-cell duration-cell">
                                                {formatDuration(
                                                    trace.longestSpanDurationNano,
                                                )}
                                            </td>
                                        </tr>
                                    ),
                                )}
                            </tbody>
                        </table>
                    </div>
                </section>
            </main>
        </div>
    );
}