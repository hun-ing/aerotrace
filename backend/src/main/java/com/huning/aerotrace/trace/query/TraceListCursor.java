package com.huning.aerotrace.trace.query;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record TraceListCursor(
        Instant traceStartTime,
        String traceId
) {

  private static final Pattern TRACE_ID_PATTERN =
          Pattern.compile("^[0-9a-f]{32}$");

  private static final String ZERO_TRACE_ID =
          "00000000000000000000000000000000";

  public TraceListCursor {
    Objects.requireNonNull(
            traceStartTime,
            "traceStartTime must not be null"
    );

    Objects.requireNonNull(
            traceId,
            "traceId must not be null"
    );

    if (
            !TRACE_ID_PATTERN
                    .matcher(traceId)
                    .matches()
                    || ZERO_TRACE_ID.equals(traceId)
    ) {
      throw new IllegalArgumentException(
              "traceId must be a non-zero "
                      + "32-character lowercase "
                      + "hexadecimal value"
      );
    }
  }
}