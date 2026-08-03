package com.huning.aerotrace.trace.query;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record TraceListResponse(
        List<TraceItem> items
) {

  public TraceListResponse {
    Objects.requireNonNull(
            items,
            "items must not be null"
    );

    items = List.copyOf(items);
  }

  public static TraceListResponse from(
          List<TraceListItem> traceListItems
  ) {
    Objects.requireNonNull(
            traceListItems,
            "traceListItems must not be null"
    );

    List<TraceItem> items =
            traceListItems.stream()
                    .map(TraceItem::from)
                    .toList();

    return new TraceListResponse(items);
  }

  public record TraceItem(
          String traceId,
          Instant traceStartTime,
          long spanCount,
          long serviceCount,
          long longestSpanDurationNano
  ) {

    private static TraceItem from(
            TraceListItem item
    ) {
      return new TraceItem(
              item.traceId(),
              item.traceStartTime(),
              item.spanCount(),
              item.serviceCount(),
              item.longestSpanDurationNano()
      );
    }
  }
}