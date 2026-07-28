package com.huning.aerotrace.ingest.application;

public final class OtlpSpanLimitExceededException
        extends RuntimeException {

  private final int maxSpansPerRequest;

  public OtlpSpanLimitExceededException(
          int maxSpansPerRequest
  ) {
    super(
            "OTLP trace request exceeds the maximum "
                    + "allowed Span count: "
                    + maxSpansPerRequest
    );

    this.maxSpansPerRequest =
            maxSpansPerRequest;
  }

  public int maxSpansPerRequest() {
    return maxSpansPerRequest;
  }
}