package com.huning.aerotrace.ingest.domain;

import java.time.Instant;
import java.util.Map;

public record ParsedSpan(
        String serviceName,
        Map<String, Object> resourceAttributes,
        Map<String, Object> spanAttributes,
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

  public ParsedSpan {
    resourceAttributes = Map.copyOf(resourceAttributes);
    spanAttributes = Map.copyOf(spanAttributes);
  }
}