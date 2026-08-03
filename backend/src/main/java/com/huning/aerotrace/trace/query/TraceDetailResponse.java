package com.huning.aerotrace.trace.query;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record TraceDetailResponse(
        String traceId,
        int spanCount,
        List<SpanItem> spans
) {

  public TraceDetailResponse {
    Objects.requireNonNull(
            traceId,
            "traceId must not be null"
    );

    Objects.requireNonNull(
            spans,
            "spans must not be null"
    );

    spans = List.copyOf(spans);

    if (spanCount != spans.size()) {
      throw new IllegalArgumentException(
              "spanCount must match spans size"
      );
    }
  }

  public static TraceDetailResponse from(
          String traceId,
          List<TraceSpanDetail> spanDetails
  ) {
    Objects.requireNonNull(
            spanDetails,
            "spanDetails must not be null"
    );

    List<SpanItem> spans =
            spanDetails.stream()
                    .map(SpanItem::from)
                    .toList();

    return new TraceDetailResponse(
            traceId,
            spans.size(),
            spans
    );
  }

  public record SpanItem(
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

    private static SpanItem from(
            TraceSpanDetail span
    ) {
      return new SpanItem(
              span.spanId(),
              span.parentSpanId(),
              span.serviceName(),
              span.scopeName(),
              span.scopeVersion(),
              span.name(),
              span.spanKind(),
              span.statusCode(),
              span.statusMessage(),
              span.startTime(),
              span.endTime(),
              span.durationNano()
      );
    }
  }
}