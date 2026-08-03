package com.huning.aerotrace.trace.query;

import java.time.Instant;
import java.util.Objects;

public record TraceListItem(
        String traceId,
        Instant traceStartTime,
        long spanCount,
        long serviceCount,
        long longestSpanDurationNano
) {

  public TraceListItem {
    Objects.requireNonNull(
            traceId,
            "traceId must not be null"
    );
    Objects.requireNonNull(
            traceStartTime,
            "traceStartTime must not be null"
    );

    if (traceId.isBlank()) {
      throw new IllegalArgumentException(
              "traceId must not be blank"
      );
    }

    if (spanCount < 1) {
      throw new IllegalArgumentException(
              "spanCount must be greater than zero"
      );
    }

    if (serviceCount < 1) {
      throw new IllegalArgumentException(
              "serviceCount must be greater than zero"
      );
    }

    if (longestSpanDurationNano < 0) {
      throw new IllegalArgumentException(
              "longestSpanDurationNano must not be negative"
      );
    }
  }
}