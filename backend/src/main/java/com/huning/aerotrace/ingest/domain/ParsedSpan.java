package com.huning.aerotrace.ingest.domain;

import java.time.Instant;

public record ParsedSpan(
        String serviceName,
        String scopeName,
        String scopeVersion,
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        short spanKind,
        short statusCode,
        String statusMessage,
        Instant startTime,
        Instant endTime,
        long durationNano
) {
}