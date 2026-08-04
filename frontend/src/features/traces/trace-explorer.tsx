"use client";

import {
    type FormEvent,
    useEffect,
    useMemo,
    useRef,
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

type TraceFilterDraft = {
    fromLocal: string;
    toLocal: string;
    serviceName: string;
    errorOnly: boolean;
    minDurationMs: string;
};

type TraceQueryBuildResult = Readonly<{
    query: URLSearchParams | null;
    errorMessage: string | null;
}>;

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
const MAXIMUM_RANGE_DAYS = 30;
const INITIAL_LIMIT = 50;

const MILLISECONDS_PER_DAY =
    24 * 60 * 60 * 1_000;

const EMPTY_FILTER_DRAFT: TraceFilterDraft = {
    fromLocal: "",
    toLocal: "",
    serviceName: "",
    errorOnly: false,
    minDurationMs: "",
};

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

function appendTraceItems(
    existingItems: readonly TraceListItem[],
    nextItems: readonly TraceListItem[],
): readonly TraceListItem[] {
    const existingTraceIds = new Set(
        existingItems.map((item) => item.traceId),
    );

    for (const item of nextItems) {
        if (existingTraceIds.has(item.traceId)) {
            throw new Error(
                "다음 페이지 응답에 중복 Trace가 포함되어 있습니다.",
            );
        }

        existingTraceIds.add(item.traceId);
    }

    return [
        ...existingItems,
        ...nextItems,
    ];
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

function padTwoDigits(value: number): string {
    return String(value).padStart(2, "0");
}

function formatDateTimeLocal(
    date: Date,
): string {
    return [
        date.getFullYear(),
        "-",
        padTwoDigits(date.getMonth() + 1),
        "-",
        padTwoDigits(date.getDate()),
        "T",
        padTwoDigits(date.getHours()),
        ":",
        padTwoDigits(date.getMinutes()),
    ].join("");
}

function createDefaultFilterDraft(
    now = new Date(),
): TraceFilterDraft {
    const from = new Date(
        now.getTime() -
        INITIAL_RANGE_DAYS *
        MILLISECONDS_PER_DAY,
    );

    return {
        fromLocal: formatDateTimeLocal(from),
        toLocal: formatDateTimeLocal(now),
        serviceName: "",
        errorOnly: false,
        minDurationMs: "",
    };
}

function parseQueryDateTime(
    value: string | null,
    fallback: string,
): string {
    if (!value) {
        return fallback;
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return fallback;
    }

    return formatDateTimeLocal(date);
}

function parseDurationNanoAsMilliseconds(
    value: string | null,
): string {
    if (!value) {
        return "";
    }

    const durationNano = Number(value);

    if (
        !Number.isSafeInteger(durationNano) ||
        durationNano < 0
    ) {
        return "";
    }

    return String(
        durationNano / 1_000_000,
    );
}

function createInitialFilterDraft(
    searchParameters: URLSearchParams,
): TraceFilterDraft {
    const defaults =
        createDefaultFilterDraft();

    return {
        fromLocal: parseQueryDateTime(
            searchParameters.get("from"),
            defaults.fromLocal,
        ),
        toLocal: parseQueryDateTime(
            searchParameters.get("to"),
            defaults.toLocal,
        ),
        serviceName:
            searchParameters.get("serviceName") ??
            "",
        errorOnly:
            searchParameters.get("errorOnly") ===
            "true",
        minDurationMs:
            parseDurationNanoAsMilliseconds(
                searchParameters.get(
                    "minSpanDurationNano",
                ),
            ),
    };
}

function buildTraceQuery(
    draft: TraceFilterDraft,
): TraceQueryBuildResult {
    if (
        !draft.fromLocal ||
        !draft.toLocal
    ) {
        return {
            query: null,
            errorMessage:
                "조회 시작 시각과 종료 시각을 입력하세요.",
        };
    }

    const from = new Date(draft.fromLocal);
    const to = new Date(draft.toLocal);

    if (
        Number.isNaN(from.getTime()) ||
        Number.isNaN(to.getTime())
    ) {
        return {
            query: null,
            errorMessage:
                "조회 기간의 날짜 형식이 올바르지 않습니다.",
        };
    }

    if (from.getTime() >= to.getTime()) {
        return {
            query: null,
            errorMessage:
                "조회 시작 시각은 종료 시각보다 이전이어야 합니다.",
        };
    }

    const rangeMilliseconds =
        to.getTime() - from.getTime();

    if (
        rangeMilliseconds >
        MAXIMUM_RANGE_DAYS *
        MILLISECONDS_PER_DAY
    ) {
        return {
            query: null,
            errorMessage:
                `조회 기간은 최대 ${MAXIMUM_RANGE_DAYS}일까지 가능합니다.`,
        };
    }

    const query = new URLSearchParams({
        from: from.toISOString(),
        to: to.toISOString(),
        limit: String(INITIAL_LIMIT),
    });

    const serviceName =
        draft.serviceName.trim();

    if (serviceName) {
        query.set(
            "serviceName",
            serviceName,
        );
    }

    if (draft.errorOnly) {
        query.set("errorOnly", "true");
    }

    const durationInput =
        draft.minDurationMs.trim();

    if (durationInput) {
        const durationMilliseconds =
            Number(durationInput);

        if (
            !Number.isFinite(
                durationMilliseconds,
            ) ||
            durationMilliseconds < 0
        ) {
            return {
                query: null,
                errorMessage:
                    "최소 Span 시간은 0 이상의 숫자여야 합니다.",
            };
        }

        const durationNano = Math.round(
            durationMilliseconds * 1_000_000,
        );

        if (
            !Number.isSafeInteger(durationNano)
        ) {
            return {
                query: null,
                errorMessage:
                    "최소 Span 시간이 너무 큽니다.",
            };
        }

        query.set(
            "minSpanDurationNano",
            String(durationNano),
        );
    }

    return {
        query,
        errorMessage: null,
    };
}

function replaceBrowserQuery(
    query: URLSearchParams,
): void {
    const queryString = query.toString();

    const browserUrl = queryString
        ? `${window.location.pathname}?${queryString}`
        : window.location.pathname;

    window.history.replaceState(
        null,
        "",
        browserUrl,
    );
}

function formatTimestamp(
    value: string,
): string {
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
    const [filterDraft, setFilterDraft] =
        useState<TraceFilterDraft>(
            EMPTY_FILTER_DRAFT,
        );

    const [activeQuery, setActiveQuery] =
        useState<string | null>(null);

    const [
        filterErrorMessage,
        setFilterErrorMessage,
    ] = useState("");

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

    const [
        isLoadingMore,
        setIsLoadingMore,
    ] = useState(false);

    const [
        paginationErrorMessage,
        setPaginationErrorMessage,
    ] = useState("");

    const activeQueryRef =
        useRef<string | null>(null);

    const loadMoreAbortControllerRef =
        useRef<AbortController | null>(null);

    useEffect(() => {
        let cancelled = false;

        queueMicrotask(() => {
            if (cancelled) {
                return;
            }

            const initialDraft =
                createInitialFilterDraft(
                    new URLSearchParams(
                        window.location.search,
                    ),
                );

            let queryResult =
                buildTraceQuery(initialDraft);

            let resolvedDraft = initialDraft;

            if (!queryResult.query) {
                resolvedDraft =
                    createDefaultFilterDraft();

                queryResult =
                    buildTraceQuery(resolvedDraft);
            }

            if (!queryResult.query) {
                setLoadingState("error");
                setErrorMessage(
                    "초기 조회 조건을 만들지 못했습니다.",
                );
                return;
            }

            const initialQueryString =
                queryResult.query.toString();

            activeQueryRef.current =
                initialQueryString;

            setFilterDraft(resolvedDraft);
            setActiveQuery(initialQueryString);

            replaceBrowserQuery(
                queryResult.query,
            );
        });

        return () => {
            cancelled = true;
        };
    }, []);

    useEffect(() => {
        return () => {
            loadMoreAbortControllerRef.current
                ?.abort();

            loadMoreAbortControllerRef.current =
                null;
        };
    }, []);

    useEffect(() => {
        if (activeQuery === null) {
            return;
        }

        const abortController =
            new AbortController();

        async function loadTraces(): Promise<void> {
            setLoadingState("loading");
            setErrorMessage("");

            try {
                const response = await fetch(
                    `/api/traces?${activeQuery}`,
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
                    parseTraceListResponse(
                        responseBody,
                    );

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
    }, [activeQuery, reloadSequence]);

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
            description: "현재 페이지",
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
            description:
                "Trace 한 개의 최대 서비스 수",
        },
    ] as const;

    const connectionStatus =
        loadingState === "loading"
            ? {
                title: "Loading traces",
                description:
                    "Backend 응답 대기 중",
                className:
                    "status-indicator status-indicator-loading",
            }
            : loadingState === "success"
                ? {
                    title: "Backend connected",
                    description:
                        "Trace API 연결됨",
                    className:
                        "status-indicator status-indicator-success",
                }
                : {
                    title: "Backend unavailable",
                    description:
                        "Trace API 호출 실패",
                    className:
                        "status-indicator status-indicator-error",
                };

    const filtersReady =
        filterDraft.fromLocal.length > 0 &&
        filterDraft.toLocal.length > 0;

    function cancelLoadMoreRequest(): void {
        loadMoreAbortControllerRef.current
            ?.abort();

        loadMoreAbortControllerRef.current =
            null;

        setIsLoadingMore(false);
        setPaginationErrorMessage("");
    }

    function updateFilterField<
        Key extends keyof TraceFilterDraft,
    >(
        field: Key,
        value: TraceFilterDraft[Key],
    ): void {
        setFilterDraft((currentDraft) => ({
            ...currentDraft,
            [field]: value,
        }));
    }

    function applyFilterQuery(
        draft: TraceFilterDraft,
    ): boolean {
        const queryResult =
            buildTraceQuery(draft);

        if (!queryResult.query) {
            setFilterErrorMessage(
                queryResult.errorMessage ??
                "조회 조건이 올바르지 않습니다.",
            );

            return false;
        }

        setFilterErrorMessage("");

        const queryString =
            queryResult.query.toString();

        cancelLoadMoreRequest();

        activeQueryRef.current =
            queryString;

        setActiveQuery(queryString);

        replaceBrowserQuery(
            queryResult.query,
        );

        setReloadSequence(
            (currentValue) =>
                currentValue + 1,
        );

        return true;
    }

    function submitFilters(
        event: FormEvent<HTMLFormElement>,
    ): void {
        event.preventDefault();

        applyFilterQuery(filterDraft);
    }

    function resetFilters(): void {
        const defaultDraft =
            createDefaultFilterDraft();

        setFilterDraft(defaultDraft);
        applyFilterQuery(defaultDraft);
    }

    async function loadMoreTraces(): Promise<void> {
        if (
            loadingState !== "success" ||
            activeQuery === null ||
            traceResponse.nextCursor === null ||
            isLoadingMore ||
            loadMoreAbortControllerRef.current !==
            null
        ) {
            return;
        }

        const requestBaseQuery = activeQuery;
        const requestCursor =
            traceResponse.nextCursor;

        const query =
            new URLSearchParams(
                requestBaseQuery,
            );

        query.set(
            "cursor",
            requestCursor,
        );

        const abortController =
            new AbortController();

        loadMoreAbortControllerRef.current =
            abortController;

        setIsLoadingMore(true);
        setPaginationErrorMessage("");

        try {
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
                parseTraceListResponse(
                    responseBody,
                );

            if (
                parsedResponse.nextCursor ===
                requestCursor
            ) {
                throw new Error(
                    "Backend가 동일한 nextCursor를 다시 반환했습니다.",
                );
            }

            const appendedItems =
                appendTraceItems(
                    traceResponse.items,
                    parsedResponse.items,
                );

            if (
                activeQueryRef.current !==
                requestBaseQuery
            ) {
                return;
            }

            setTraceResponse({
                items: appendedItems,
                nextCursor:
                parsedResponse.nextCursor,
            });
        } catch (error) {
            if (
                error instanceof DOMException &&
                error.name === "AbortError"
            ) {
                return;
            }

            if (
                activeQueryRef.current !==
                requestBaseQuery
            ) {
                return;
            }

            const message =
                error instanceof Error
                    ? error.message
                    : "다음 Trace 페이지를 불러오지 못했습니다.";

            setPaginationErrorMessage(message);
        } finally {
            if (
                loadMoreAbortControllerRef.current ===
                abortController
            ) {
                loadMoreAbortControllerRef.current =
                    null;

                setIsLoadingMore(false);
            }
        }
    }

    function reloadTraces(): void {
        cancelLoadMoreRequest();

        setReloadSequence(
            (currentValue) =>
                currentValue + 1,
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
                                loadingState === "loading" ||
                                isLoadingMore ||
                                activeQuery === null
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
                                검색 버튼을 눌렀을 때
                                조회 조건이 적용됩니다.
                            </p>
                        </div>

                        <button
                            className="secondary-button"
                            disabled={
                                !filtersReady ||
                                isLoadingMore
                            }
                            onClick={resetFilters}
                            type="button"
                        >
                            Reset
                        </button>
                    </div>

                    <form
                        className="filter-grid"
                        noValidate
                        onSubmit={submitFilters}
                    >
                        <label className="field">
                            <span>From</span>
                            <input
                                aria-label="조회 시작 시각"
                                disabled={!filtersReady}
                                onChange={(event) =>
                                    updateFilterField(
                                        "fromLocal",
                                        event.target.value,
                                    )
                                }
                                step="60"
                                type="datetime-local"
                                value={
                                    filterDraft.fromLocal
                                }
                            />
                        </label>

                        <label className="field">
                            <span>To</span>
                            <input
                                aria-label="조회 종료 시각"
                                disabled={!filtersReady}
                                onChange={(event) =>
                                    updateFilterField(
                                        "toLocal",
                                        event.target.value,
                                    )
                                }
                                step="60"
                                type="datetime-local"
                                value={filterDraft.toLocal}
                            />
                        </label>

                        <label className="field">
                            <span>Service</span>
                            <input
                                disabled={!filtersReady}
                                onChange={(event) =>
                                    updateFilterField(
                                        "serviceName",
                                        event.target.value,
                                    )
                                }
                                placeholder="service.name exact match"
                                type="text"
                                value={
                                    filterDraft.serviceName
                                }
                            />
                        </label>

                        <label className="field">
              <span>
                Minimum span duration
              </span>
                            <input
                                disabled={!filtersReady}
                                min="0"
                                onChange={(event) =>
                                    updateFilterField(
                                        "minDurationMs",
                                        event.target.value,
                                    )
                                }
                                placeholder="예: 250"
                                step="0.001"
                                type="number"
                                value={
                                    filterDraft.minDurationMs
                                }
                            />
                            <small>밀리초(ms) 단위</small>
                        </label>

                        <label className="checkbox-field">
                            <input
                                checked={
                                    filterDraft.errorOnly
                                }
                                disabled={!filtersReady}
                                onChange={(event) =>
                                    updateFilterField(
                                        "errorOnly",
                                        event.target.checked,
                                    )
                                }
                                type="checkbox"
                            />
                            <span>Error traces only</span>
                        </label>

                        <button
                            className="primary-button"
                            disabled={
                                !filtersReady ||
                                loadingState === "loading" ||
                                isLoadingMore
                            }
                            type="submit"
                        >
                            {loadingState === "loading"
                                ? "Searching..."
                                : "Search traces"}
                        </button>
                    </form>

                    {filterErrorMessage && (
                        <p
                            className="filter-error-message"
                            role="alert"
                        >
                            {filterErrorMessage}
                        </p>
                    )}
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
                                현재 적용된 조건의 최신
                                Trace부터 표시됩니다.
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
                                                    현재 조회 조건에 맞는
                                                    Trace가 없습니다.
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

                    {loadingState === "success" &&
                        traceResponse.items.length > 0 && (
                            <div className="pagination-footer">
                                <p className="pagination-summary">
                                    {traceResponse.items.length.toLocaleString()}{" "}
                                    traces loaded
                                </p>

                                <div
                                    className="pagination-actions"
                                    aria-live="polite"
                                >
                                    {paginationErrorMessage && (
                                        <p
                                            className="pagination-error-message"
                                            role="alert"
                                        >
                                            {paginationErrorMessage}
                                        </p>
                                    )}

                                    {traceResponse.nextCursor ? (
                                        <button
                                            className="secondary-button load-more-button"
                                            disabled={isLoadingMore}
                                            onClick={() => {
                                                void loadMoreTraces();
                                            }}
                                            type="button"
                                        >
                                            {isLoadingMore
                                                ? "Loading more..."
                                                : paginationErrorMessage
                                                    ? "Retry load more"
                                                    : "Load more"}
                                        </button>
                                    ) : (
                                        <span className="pagination-complete">
            All matching traces loaded
          </span>
                                    )}
                                </div>
                            </div>
                        )}
                </section>
            </main>
        </div>
    );
}