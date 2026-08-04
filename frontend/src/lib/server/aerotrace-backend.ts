import "server-only";

export type AeroTraceBackendConfig = Readonly<{
    baseUrl: string;
    apiKey: string;
}>;

function requireEnvironmentVariable(name: string): string {
    const value = process.env[name]?.trim();

    if (!value) {
        throw new Error(`${name} 환경변수가 설정되지 않았습니다.`);
    }

    return value;
}

export function getAeroTraceBackendConfig(): AeroTraceBackendConfig {
    const rawBaseUrl = requireEnvironmentVariable(
        "AEROTRACE_BACKEND_BASE_URL",
    );

    const apiKey = requireEnvironmentVariable(
        "AEROTRACE_API_KEY",
    );

    let parsedBaseUrl: URL;

    try {
        parsedBaseUrl = new URL(rawBaseUrl);
    } catch {
        throw new Error(
            "AEROTRACE_BACKEND_BASE_URL은 올바른 URL이어야 합니다.",
        );
    }

    if (
        parsedBaseUrl.protocol !== "http:" &&
        parsedBaseUrl.protocol !== "https:"
    ) {
        throw new Error(
            "AEROTRACE_BACKEND_BASE_URL은 http 또는 https URL이어야 합니다.",
        );
    }

    if (!apiKey.startsWith("atr_")) {
        throw new Error(
            "AEROTRACE_API_KEY 형식이 올바르지 않습니다.",
        );
    }

    return {
        baseUrl: parsedBaseUrl
            .toString()
            .replace(/\/+$/, ""),
        apiKey,
    };
}