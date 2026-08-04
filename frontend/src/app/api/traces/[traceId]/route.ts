import {
    type NextRequest,
    NextResponse,
} from "next/server";

import { getAeroTraceBackendConfig } from "@/lib/server/aerotrace-backend";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const TRACE_ID_PATTERN = /^[0-9a-fA-F]{32}$/;

const FORWARDED_QUERY_PARAMETERS = [
    "from",
    "to",
] as const;

const NO_STORE_HEADERS = {
    "Cache-Control": "no-store, max-age=0",
} as const;

type TraceDetailRouteContext = Readonly<{
    params: Promise<{
        traceId: string;
    }>;
}>;

type BackendErrorResponse = Readonly<{
    message?: unknown;
}>;

function copyAllowedQueryParameters(
    source: URLSearchParams,
    target: URLSearchParams,
): void {
    for (
        const parameterName
        of FORWARDED_QUERY_PARAMETERS
        ) {
        const value = source.get(parameterName);

        if (value !== null) {
            target.set(parameterName, value);
        }
    }
}

function extractBackendErrorMessage(
    responseBody: string,
): string | null {
    if (!responseBody) {
        return null;
    }

    try {
        const parsedBody: unknown =
            JSON.parse(responseBody);

        if (
            typeof parsedBody !== "object" ||
            parsedBody === null
        ) {
            return null;
        }

        const errorResponse =
            parsedBody as BackendErrorResponse;

        if (
            typeof errorResponse.message !==
            "string"
        ) {
            return null;
        }

        const message =
            errorResponse.message.trim();

        return message.length > 0
            ? message
            : null;
    } catch {
        return null;
    }
}

function logProxyError(
    error: unknown,
): void {
    const message =
        error instanceof Error
            ? error.message
            : "알 수 없는 오류";

    console.error(
        `[AeroTrace trace detail proxy] ${message}`,
    );
}

export async function GET(
    request: NextRequest,
    context: TraceDetailRouteContext,
): Promise<NextResponse> {
    const { traceId: rawTraceId } =
        await context.params;

    const traceId = rawTraceId.trim();

    if (!TRACE_ID_PATTERN.test(traceId)) {
        return NextResponse.json(
            {
                message:
                    "traceId는 32자리 16진수여야 합니다.",
            },
            {
                status: 400,
                headers: NO_STORE_HEADERS,
            },
        );
    }

    try {
        const config =
            getAeroTraceBackendConfig();

        const backendUrl = new URL(
            `/api/v1/traces/${traceId.toLowerCase()}`,
            `${config.baseUrl}/`,
        );

        copyAllowedQueryParameters(
            request.nextUrl.searchParams,
            backendUrl.searchParams,
        );

        const backendResponse =
            await fetch(
                backendUrl,
                {
                    method: "GET",
                    headers: {
                        Accept: "application/json",
                        Authorization:
                            `Bearer ${config.apiKey}`,
                    },
                    cache: "no-store",
                    signal:
                        AbortSignal.timeout(5_000),
                },
            );

        const responseBody =
            await backendResponse.text();

        if (!backendResponse.ok) {
            const backendMessage =
                extractBackendErrorMessage(
                    responseBody,
                );

            return NextResponse.json(
                {
                    message:
                        backendMessage ??
                        "Trace 상세 요청을 처리하지 못했습니다.",
                },
                {
                    status: backendResponse.status,
                    headers: NO_STORE_HEADERS,
                },
            );
        }

        if (!responseBody) {
            console.error(
                "[AeroTrace trace detail proxy] Backend가 빈 응답을 반환했습니다.",
            );

            return NextResponse.json(
                {
                    message:
                        "Backend가 빈 Trace 상세 응답을 반환했습니다.",
                },
                {
                    status: 502,
                    headers: NO_STORE_HEADERS,
                },
            );
        }

        let responsePayload: unknown;

        try {
            responsePayload =
                JSON.parse(responseBody);
        } catch {
            console.error(
                "[AeroTrace trace detail proxy] Backend가 올바르지 않은 JSON을 반환했습니다.",
            );

            return NextResponse.json(
                {
                    message:
                        "Backend Trace 상세 응답 형식이 올바르지 않습니다.",
                },
                {
                    status: 502,
                    headers: NO_STORE_HEADERS,
                },
            );
        }

        return NextResponse.json(
            responsePayload,
            {
                status: backendResponse.status,
                headers: NO_STORE_HEADERS,
            },
        );
    } catch (error) {
        logProxyError(error);

        return NextResponse.json(
            {
                message:
                    "AeroTrace Backend에 연결할 수 없습니다.",
            },
            {
                status: 502,
                headers: NO_STORE_HEADERS,
            },
        );
    }
}