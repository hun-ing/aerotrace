package com.huning.aerotrace.trace.query;

import java.util.List;
import java.util.Objects;

public record TraceListPage(
        List<TraceListItem> items,
        TraceListCursor nextCursor
) {

  public TraceListPage {
    Objects.requireNonNull(
            items,
            "items must not be null"
    );

    items = List.copyOf(items);
  }

  public boolean hasNext() {
    return nextCursor != null;
  }
}