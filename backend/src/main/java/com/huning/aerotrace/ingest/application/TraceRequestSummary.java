package com.huning.aerotrace.ingest.application;

public record TraceRequestSummary(
        int resourceSpanCount,
        int scopeSpanCount,
        int spanCount
) {
}