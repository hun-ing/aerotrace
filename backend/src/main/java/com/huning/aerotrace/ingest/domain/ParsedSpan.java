package com.huning.aerotrace.ingest.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ParsedSpan(
        String serviceName,
        Map<String, Object> resourceAttributes,
        Map<String, Object> spanAttributes,
        List<ParsedSpanEvent> events,
        List<ParsedSpanLink> links,
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
    events = List.copyOf(events);
    links = List.copyOf(links);
  }
}