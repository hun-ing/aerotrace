package com.huning.aerotrace.trace.query;

import java.time.Instant;
import java.util.Objects;

public record TraceSpanDetail(
        String traceId,
        String spanId,
        String parentSpanId,
        String serviceName,
        String scopeName,
        String scopeVersion,
        String name,
        short spanKind,
        short statusCode,
        String statusMessage,
        Instant startTime,
        Instant endTime,
        long durationNano
) {

  public TraceSpanDetail {
    Objects.requireNonNull(
            traceId,
            "traceId must not be null"
    );

    Objects.requireNonNull(
            spanId,
            "spanId must not be null"
    );

    Objects.requireNonNull(
            serviceName,
            "serviceName must not be null"
    );

    Objects.requireNonNull(
            scopeName,
            "scopeName must not be null"
    );

    Objects.requireNonNull(
            scopeVersion,
            "scopeVersion must not be null"
    );

    Objects.requireNonNull(
            name,
            "name must not be null"
    );

    Objects.requireNonNull(
            statusMessage,
            "statusMessage must not be null"
    );

    Objects.requireNonNull(
            startTime,
            "startTime must not be null"
    );

    Objects.requireNonNull(
            endTime,
            "endTime must not be null"
    );

    if (endTime.isBefore(startTime)) {
      throw new IllegalArgumentException(
              "endTime must not be earlier than startTime"
      );
    }

    if (durationNano < 0) {
      throw new IllegalArgumentException(
              "durationNano must not be negative"
      );
    }
  }
}