import {
    type NextRequest,
    NextResponse,
} from "next/server";

import { getAeroTraceBackendConfig } from "@/lib/server/aerotrace-backend";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const FORWARDED_QUERY_PARAMETERS = [
    "from",
    "to",
    "limit",
    "cursor",
    "serviceName",
    "errorOnly",
    "minSpanDurationNano",
] as const;

const NO_STORE_HEADERS = {
    "Cache-Control": "no-store, max-age=0",
} as const;

type BackendErrorResponse = Readonly<{
    message?: unknown;
}>;

function copyAllowedQueryParameters(
    source: URLSearchParams,
    target: URLSearchParams,
): void {
    for (const parameterName of FORWARDED_QUERY_PARAMETERS) {
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
        const parsedBody: unknown = JSON.parse(responseBody);

        if (
            typeof parsedBody !== "object" ||
            parsedBody === null
        ) {
            return null;
        }

        const errorResponse = parsedBody as BackendErrorResponse;

        if (typeof errorResponse.message !== "string") {
            return null;
        }

        const message = errorResponse.message.trim();

        return message.length > 0 ? message : null;
    } catch {
        return null;
    }
}

function logProxyError(error: unknown): void {
    const message =
        error instanceof Error
            ? error.message
            : "알 수 없는 오류";

    console.error(
        `[AeroTrace trace proxy] ${message}`,
    );
}

export async function GET(
    request: NextRequest,
): Promise<NextResponse> {
    try {
        const config = getAeroTraceBackendConfig();

        const backendUrl = new URL(
            "/api/v1/traces",
            `${config.baseUrl}/`,
        );

        copyAllowedQueryParameters(
            request.nextUrl.searchParams,
            backendUrl.searchParams,
        );

        const backendResponse = await fetch(
            backendUrl,
            {
                method: "GET",
                headers: {
                    Accept: "application/json",
                    Authorization: `Bearer ${config.apiKey}`,
                },
                cache: "no-store",
                signal: AbortSignal.timeout(5_000),
            },
        );

        const responseBody =
            await backendResponse.text();

        if (!backendResponse.ok) {
            const backendMessage =
                extractBackendErrorMessage(responseBody);

            return NextResponse.json(
                {
                    message:
                        backendMessage ??
                        "Trace 조회 요청을 처리하지 못했습니다.",
                },
                {
                    status: backendResponse.status,
                    headers: NO_STORE_HEADERS,
                },
            );
        }

        if (!responseBody) {
            return NextResponse.json(
                {
                    items: [],
                    nextCursor: null,
                },
                {
                    status: 200,
                    headers: NO_STORE_HEADERS,
                },
            );
        }

        let responsePayload: unknown;

        try {
            responsePayload = JSON.parse(responseBody);
        } catch {
            console.error(
                "[AeroTrace trace proxy] Backend가 올바르지 않은 JSON을 반환했습니다.",
            );

            return NextResponse.json(
                {
                    message:
                        "Backend 응답 형식이 올바르지 않습니다.",
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