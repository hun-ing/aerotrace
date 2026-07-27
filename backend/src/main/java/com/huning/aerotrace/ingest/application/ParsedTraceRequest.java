package com.huning.aerotrace.ingest.application;

import com.huning.aerotrace.ingest.domain.ParsedSpan;

import java.util.List;

public record ParsedTraceRequest(
        List<ParsedSpan> spans
) {

  public ParsedTraceRequest {
    spans = List.copyOf(spans);
  }

  public int spanCount() {
    return spans.size();
  }
}