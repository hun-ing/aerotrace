package com.huning.aerotrace.trace.query;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record TraceListResponse(
        List<TraceItem> items,
        String nextCursor
) {

  public TraceListResponse {
    Objects.requireNonNull(
            items,
            "items must not be null"
    );

    items = List.copyOf(items);

    if (
            nextCursor != null
                    && nextCursor.isBlank()
    ) {
      throw new IllegalArgumentException(
              "nextCursor must not be blank"
      );
    }
  }

  /*
   * 기존 호출 코드나 테스트와의 호환을 위해 유지한다.
   */
  public static TraceListResponse from(
          List<TraceListItem> traceListItems
  ) {
    return from(
            traceListItems,
            null
    );
  }

  public static TraceListResponse from(
          List<TraceListItem> traceListItems,
          String nextCursor
  ) {
    Objects.requireNonNull(
            traceListItems,
            "traceListItems must not be null"
    );

    List<TraceItem> items =
            traceListItems.stream()
                    .map(TraceItem::from)
                    .toList();

    return new TraceListResponse(
            items,
            nextCursor
    );
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