"use client";

import {
    useEffect,
    useMemo,
    useState,
} from "react";

type TraceSpan = Readonly<{
    spanId: string;
    parentSpanId: string | null;
    serviceName: string;
    scopeName: string;
    scopeVersion: string;
    name: string;
    spanKind: number;
    statusCode: number;
    statusMessage: string;
    startTime: string;
    endTime: string;
    durationNano: number;
}>;

type TraceDetailResponse = Readonly<{
    traceId: string;
    spanCount: number;
    spans: readonly TraceSpan[];
}>;

type TraceDetailPanelProps = Readonly<{
    traceId: string;
    activeQuery: string;
    onClose: () => void;
}>;

type LoadingState =
    | "loading"
    | "success"
    | "error";

type TimelineSpan = Readonly<{
    span: TraceSpan;
    leftPercent: number;
    widthPercent: number;
}>;

const TRACE_ID_PATTERN =
    /^[0-9a-f]{32}$/;

const SPAN_ID_PATTERN =
    /^[0-9a-f]{16}$/;

const SPAN_KIND_LABELS: Readonly<
    Record<number, string>
> = {
    0: "Unspecified",
    1: "Internal",
    2: "Server",
    3: "Client",
    4: "Producer",
    5: "Consumer",
};

const STATUS_CODE_LABELS: Readonly<
    Record<number, string>
> = {
    0: "Unset",
    1: "OK",
    2: "Error",
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
    allowEmpty = false,
): string {
    if (typeof value !== "string") {
        throw new Error(
            `${fieldName} 응답 형식이 올바르지 않습니다.`,
        );
    }

    if (
        !allowEmpty &&
        value.trim().length === 0
    ) {
        throw new Error(
            `${fieldName} 값이 비어 있습니다.`,
        );
    }

    return value;
}

function requireHexString(
    value: unknown,
    fieldName: string,
    pattern: RegExp,
): string {
    const stringValue =
        requireString(value, fieldName)
            .toLowerCase();

    if (!pattern.test(stringValue)) {
        throw new Error(
            `${fieldName} 형식이 올바르지 않습니다.`,
        );
    }

    return stringValue;
}

function requireNullableSpanId(
    value: unknown,
    fieldName: string,
): string | null {
    if (value === null) {
        return null;
    }

    return requireHexString(
        value,
        fieldName,
        SPAN_ID_PATTERN,
    );
}

function requireIntegerInRange(
    value: unknown,
    fieldName: string,
    minimum: number,
    maximum: number,
): number {
    if (
        typeof value !== "number" ||
        !Number.isInteger(value) ||
        value < minimum ||
        value > maximum
    ) {
        throw new Error(
            `${fieldName} 응답 형식이 올바르지 않습니다.`,
        );
    }

    return value;
}

function requireNonNegativeSafeInteger(
    value: unknown,
    fieldName: string,
): number {
    if (
        typeof value !== "number" ||
        !Number.isSafeInteger(value) ||
        value < 0
    ) {
        throw new Error(
            `${fieldName} 응답 형식이 올바르지 않습니다.`,
        );
    }

    return value;
}

function requireIsoTimestamp(
    value: unknown,
    fieldName: string,
): string {
    const timestamp =
        requireString(value, fieldName);

    if (
        Number.isNaN(
            new Date(timestamp).getTime(),
        )
    ) {
        throw new Error(
            `${fieldName} 날짜 형식이 올바르지 않습니다.`,
        );
    }

    return timestamp;
}

function parseTraceSpan(
    value: unknown,
    index: number,
): TraceSpan {
    if (!isRecord(value)) {
        throw new Error(
            `spans[${index}] 응답 형식이 올바르지 않습니다.`,
        );
    }

    const startTime =
        requireIsoTimestamp(
            value.startTime,
            `spans[${index}].startTime`,
        );

    const endTime =
        requireIsoTimestamp(
            value.endTime,
            `spans[${index}].endTime`,
        );

    if (
        new Date(endTime).getTime() <
        new Date(startTime).getTime()
    ) {
        throw new Error(
            `spans[${index}]의 종료 시각이 시작 시각보다 빠릅니다.`,
        );
    }

    return {
        spanId: requireHexString(
            value.spanId,
            `spans[${index}].spanId`,
            SPAN_ID_PATTERN,
        ),
        parentSpanId:
            requireNullableSpanId(
                value.parentSpanId,
                `spans[${index}].parentSpanId`,
            ),
        serviceName: requireString(
            value.serviceName,
            `spans[${index}].serviceName`,
        ),
        scopeName: requireString(
            value.scopeName,
            `spans[${index}].scopeName`,
            true,
        ),
        scopeVersion: requireString(
            value.scopeVersion,
            `spans[${index}].scopeVersion`,
            true,
        ),
        name: requireString(
            value.name,
            `spans[${index}].name`,
        ),
        spanKind: requireIntegerInRange(
            value.spanKind,
            `spans[${index}].spanKind`,
            0,
            5,
        ),
        statusCode:
            requireIntegerInRange(
                value.statusCode,
                `spans[${index}].statusCode`,
                0,
                2,
            ),
        statusMessage: requireString(
            value.statusMessage,
            `spans[${index}].statusMessage`,
            true,
        ),
        startTime,
        endTime,
        durationNano:
            requireNonNegativeSafeInteger(
                value.durationNano,
                `spans[${index}].durationNano`,
            ),
    };
}

function parseTraceDetailResponse(
    value: unknown,
): TraceDetailResponse {
    if (!isRecord(value)) {
        throw new Error(
            "Trace 상세 응답 형식이 올바르지 않습니다.",
        );
    }

    if (!Array.isArray(value.spans)) {
        throw new Error(
            "Trace 상세의 spans가 배열이 아닙니다.",
        );
    }

    const spans =
        value.spans.map(parseTraceSpan);

    const spanCount =
        requireNonNegativeSafeInteger(
            value.spanCount,
            "spanCount",
        );

    if (spanCount !== spans.length) {
        throw new Error(
            "spanCount와 실제 spans 개수가 일치하지 않습니다.",
        );
    }

    return {
        traceId: requireHexString(
            value.traceId,
            "traceId",
            TRACE_ID_PATTERN,
        ),
        spanCount,
        spans,
    };
}

async function extractErrorMessage(
    response: Response,
): Promise<string> {
    try {
        const body: unknown =
            await response.json();

        if (
            isRecord(body) &&
            typeof body.message === "string" &&
            body.message.trim().length > 0
        ) {
            return body.message;
        }
    } catch {
        // JSON이 아닌 오류 응답은 기본 메시지로 처리한다.
    }

    return "Trace 상세를 불러오지 못했습니다.";
}

function createDetailQuery(
    activeQuery: string,
): URLSearchParams {
    const activeParameters =
        new URLSearchParams(activeQuery);

    const detailQuery =
        new URLSearchParams();

    for (
        const parameterName
        of ["from", "to"] as const
        ) {
        const value =
            activeParameters.get(
                parameterName,
            );

        if (value !== null) {
            detailQuery.set(
                parameterName,
                value,
            );
        }
    }

    return detailQuery;
}

function formatTimestamp(
    value: string,
): string {
    const date = new Date(value);

    return new Intl.DateTimeFormat(
        "ko-KR",
        {
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit",
            fractionalSecondDigits: 3,
            hour12: false,
        },
    ).format(date);
}

function formatDuration(
    durationNano: number,
): string {
    if (durationNano < 1_000) {
        return `${durationNano} ns`;
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

function shortenIdentifier(
    value: string,
): string {
    return `${value.slice(0, 8)}…${value.slice(-4)}`;
}

function getSpanKindLabel(
    spanKind: number,
): string {
    return (
        SPAN_KIND_LABELS[spanKind] ??
        `Unknown (${spanKind})`
    );
}

function getStatusLabel(
    statusCode: number,
): string {
    return (
        STATUS_CODE_LABELS[statusCode] ??
        `Unknown (${statusCode})`
    );
}

function getStatusClassName(
    statusCode: number,
): string {
    if (statusCode === 2) {
        return "span-status span-status-error";
    }

    if (statusCode === 1) {
        return "span-status span-status-ok";
    }

    return "span-status span-status-unset";
}

export default function TraceDetailPanel({
                                             traceId,
                                             activeQuery,
                                             onClose,
                                         }: TraceDetailPanelProps) {
    const [loadingState, setLoadingState] =
        useState<LoadingState>("loading");

    const [detail, setDetail] =
        useState<TraceDetailResponse | null>(
            null,
        );

    const [errorMessage, setErrorMessage] =
        useState("");

    useEffect(() => {
        const abortController =
            new AbortController();

        async function loadTraceDetail(): Promise<void> {
            setLoadingState("loading");
            setErrorMessage("");
            setDetail(null);

            try {
                const query =
                    createDetailQuery(activeQuery);

                const response = await fetch(
                    `/api/traces/${encodeURIComponent(
                        traceId,
                    )}?${query.toString()}`,
                    {
                        method: "GET",
                        headers: {
                            Accept: "application/json",
                        },
                        cache: "no-store",
                        signal:
                        abortController.signal,
                    },
                );

                if (!response.ok) {
                    throw new Error(
                        await extractErrorMessage(
                            response,
                        ),
                    );
                }

                const body: unknown =
                    await response.json();

                const parsedDetail =
                    parseTraceDetailResponse(body);

                if (
                    parsedDetail.traceId !==
                    traceId.toLowerCase()
                ) {
                    throw new Error(
                        "요청한 Trace ID와 응답 Trace ID가 일치하지 않습니다.",
                    );
                }

                setDetail(parsedDetail);
                setLoadingState("success");
            } catch (error) {
                if (
                    error instanceof DOMException &&
                    error.name === "AbortError"
                ) {
                    return;
                }

                setErrorMessage(
                    error instanceof Error
                        ? error.message
                        : "Trace 상세를 불러오지 못했습니다.",
                );

                setLoadingState("error");
            }
        }

        void loadTraceDetail();

        return () => {
            abortController.abort();
        };
    }, [activeQuery, traceId]);

    const timeline = useMemo(() => {
        if (
            detail === null ||
            detail.spans.length === 0
        ) {
            return {
                spans: [] as readonly TimelineSpan[],
                traceStartTime: null as string | null,
                traceEndTime: null as string | null,
                traceDurationNano: 0,
                serviceCount: 0,
            };
        }

        const sortedSpans = [
            ...detail.spans,
        ].sort((left, right) => {
            const timeDifference =
                new Date(left.startTime).getTime() -
                new Date(right.startTime).getTime();

            if (timeDifference !== 0) {
                return timeDifference;
            }

            return left.spanId.localeCompare(
                right.spanId,
            );
        });

        const traceStartMilliseconds =
            Math.min(
                ...sortedSpans.map((span) =>
                    new Date(
                        span.startTime,
                    ).getTime(),
                ),
            );

        const traceEndMilliseconds =
            Math.max(
                ...sortedSpans.map((span) =>
                    new Date(
                        span.endTime,
                    ).getTime(),
                ),
            );

        const traceDurationNano = Math.max(
            1,
            (
                traceEndMilliseconds -
                traceStartMilliseconds
            ) * 1_000_000,
        );

        const timelineSpans =
            sortedSpans.map((span) => {
                const spanStartOffsetNano =
                    Math.max(
                        0,
                        (
                            new Date(
                                span.startTime,
                            ).getTime() -
                            traceStartMilliseconds
                        ) * 1_000_000,
                    );

                const leftPercent =
                    Math.min(
                        100,
                        (
                            spanStartOffsetNano /
                            traceDurationNano
                        ) * 100,
                    );

                const remainingPercent =
                    Math.max(
                        0,
                        100 - leftPercent,
                    );

                const widthPercent =
                    Math.min(
                        remainingPercent,
                        Math.max(
                            1,
                            (
                                span.durationNano /
                                traceDurationNano
                            ) * 100,
                        ),
                    );

                return {
                    span,
                    leftPercent,
                    widthPercent,
                };
            });

        return {
            spans: timelineSpans,
            traceStartTime:
            sortedSpans[0].startTime,
            traceEndTime:
                sortedSpans.reduce(
                    (latestEndTime, span) =>
                        new Date(
                            span.endTime,
                        ).getTime() >
                        new Date(
                            latestEndTime,
                        ).getTime()
                            ? span.endTime
                            : latestEndTime,
                    sortedSpans[0].endTime,
                ),
            traceDurationNano,
            serviceCount: new Set(
                sortedSpans.map(
                    (span) => span.serviceName,
                ),
            ).size,
        };
    }, [detail]);

    return (
        <section
            className="panel trace-detail-panel"
            aria-busy={
                loadingState === "loading"
            }
        >
            <div className="panel-header">
                <div>
                    <p className="detail-eyebrow">
                        Trace detail
                    </p>

                    <h2>
                        <code className="detail-trace-id">
                            {traceId}
                        </code>
                    </h2>
                </div>

                <button
                    className="secondary-button"
                    onClick={onClose}
                    type="button"
                >
                    Close
                </button>
            </div>

            {loadingState === "loading" && (
                <div className="detail-state">
                    <div
                        className="loading-spinner"
                        aria-hidden="true"
                    />

                    <h3>Loading trace detail</h3>

                    <p>
                        Span 타임라인을 불러오고
                        있습니다.
                    </p>
                </div>
            )}

            {loadingState === "error" && (
                <div className="detail-state">
                    <div
                        className="empty-icon error-icon"
                        aria-hidden="true"
                    >
                        !
                    </div>

                    <h3>
                        Trace detail request failed
                    </h3>

                    <p className="error-message">
                        {errorMessage}
                    </p>
                </div>
            )}

            {loadingState === "success" &&
                detail !== null && (
                    <>
                        <div className="detail-summary-grid">
                            <article>
                                <span>Spans</span>
                                <strong>
                                    {detail.spanCount.toLocaleString()}
                                </strong>
                            </article>

                            <article>
                                <span>Services</span>
                                <strong>
                                    {timeline.serviceCount.toLocaleString()}
                                </strong>
                            </article>

                            <article>
                                <span>Trace duration</span>
                                <strong>
                                    {formatDuration(
                                        timeline.traceDurationNano,
                                    )}
                                </strong>
                            </article>

                            <article>
                                <span>Started at</span>
                                <strong>
                                    {timeline.traceStartTime
                                        ? formatTimestamp(
                                            timeline.traceStartTime,
                                        )
                                        : "—"}
                                </strong>
                            </article>
                        </div>

                        <div className="timeline-header">
                            <div>
                                <h3>Span timeline</h3>
                                <p>
                                    Trace 시작 시각을 기준으로
                                    각 Span의 상대 위치를 표시합니다.
                                </p>
                            </div>

                            <span>
                {timeline.traceEndTime
                    ? `Ended ${formatTimestamp(
                        timeline.traceEndTime,
                    )}`
                    : ""}
              </span>
                        </div>

                        <div className="span-timeline">
                            {timeline.spans.map(
                                ({
                                     span,
                                     leftPercent,
                                     widthPercent,
                                 }) => (
                                    <article
                                        className="span-card"
                                        key={span.spanId}
                                    >
                                        <div className="span-card-header">
                                            <div>
                                                <p className="span-service-name">
                                                    {span.serviceName}
                                                </p>

                                                <h4>{span.name}</h4>
                                            </div>

                                            <span
                                                className={
                                                    getStatusClassName(
                                                        span.statusCode,
                                                    )
                                                }
                                            >
                        {getStatusLabel(
                            span.statusCode,
                        )}
                      </span>
                                        </div>

                                        <div
                                            className="span-bar-track"
                                            aria-label={`${span.name} ${formatDuration(
                                                span.durationNano,
                                            )}`}
                                        >
                                            <div
                                                className={`span-bar ${
                                                    span.statusCode === 2
                                                        ? "span-bar-error"
                                                        : ""
                                                }`}
                                                style={{
                                                    left: `${leftPercent}%`,
                                                    width: `${widthPercent}%`,
                                                }}
                                            />
                                        </div>

                                        <dl className="span-metadata">
                                            <div>
                                                <dt>Span ID</dt>
                                                <dd title={span.spanId}>
                                                    {shortenIdentifier(
                                                        span.spanId,
                                                    )}
                                                </dd>
                                            </div>

                                            <div>
                                                <dt>Parent</dt>
                                                <dd
                                                    title={
                                                        span.parentSpanId ??
                                                        "Root span"
                                                    }
                                                >
                                                    {span.parentSpanId
                                                        ? shortenIdentifier(
                                                            span.parentSpanId,
                                                        )
                                                        : "Root"}
                                                </dd>
                                            </div>

                                            <div>
                                                <dt>Kind</dt>
                                                <dd>
                                                    {getSpanKindLabel(
                                                        span.spanKind,
                                                    )}
                                                </dd>
                                            </div>

                                            <div>
                                                <dt>Duration</dt>
                                                <dd>
                                                    {formatDuration(
                                                        span.durationNano,
                                                    )}
                                                </dd>
                                            </div>

                                            <div>
                                                <dt>Started</dt>
                                                <dd>
                                                    {formatTimestamp(
                                                        span.startTime,
                                                    )}
                                                </dd>
                                            </div>

                                            <div>
                                                <dt>Ended</dt>
                                                <dd>
                                                    {formatTimestamp(
                                                        span.endTime,
                                                    )}
                                                </dd>
                                            </div>

                                            <div>
                                                <dt>Scope</dt>
                                                <dd>
                                                    {span.scopeName || "—"}
                                                    {span.scopeVersion
                                                        ? ` ${span.scopeVersion}`
                                                        : ""}
                                                </dd>
                                            </div>

                                            <div>
                                                <dt>Status message</dt>
                                                <dd>
                                                    {span.statusMessage ||
                                                        "—"}
                                                </dd>
                                            </div>
                                        </dl>
                                    </article>
                                ),
                            )}
                        </div>
                    </>
                )}
        </section>
    );
}