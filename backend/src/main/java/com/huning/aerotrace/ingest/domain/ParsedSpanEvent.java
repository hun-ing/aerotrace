package com.huning.aerotrace.ingest.domain;

import java.util.Map;

public record ParsedSpanEvent(
        long timeUnixNano,
        String name,
        Map<String, Object> attributes,
        long droppedAttributesCount
) {

  public ParsedSpanEvent {
    attributes = Map.copyOf(attributes);
  }
}