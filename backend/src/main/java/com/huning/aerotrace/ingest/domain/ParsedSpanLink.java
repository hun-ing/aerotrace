package com.huning.aerotrace.ingest.domain;

import java.util.Map;

public record ParsedSpanLink(
        String traceId,
        String spanId,
        String traceState,
        Map<String, Object> attributes,
        long droppedAttributesCount,
        long flags
) {

  public ParsedSpanLink {
    attributes = Map.copyOf(attributes);
  }
}