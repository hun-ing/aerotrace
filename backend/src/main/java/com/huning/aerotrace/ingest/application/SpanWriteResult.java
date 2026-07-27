package com.huning.aerotrace.ingest.application;

public record SpanWriteResult(
        int requestedCount,
        int insertedCount,
        int duplicateCount,
        int unknownSuccessCount
) {

  public SpanWriteResult {
    if (
            requestedCount < 0
                    || insertedCount < 0
                    || duplicateCount < 0
                    || unknownSuccessCount < 0
    ) {
      throw new IllegalArgumentException(
              "Span write counts must not be negative"
      );
    }

    int classifiedCount =
            insertedCount
                    + duplicateCount
                    + unknownSuccessCount;

    if (requestedCount != classifiedCount) {
      throw new IllegalArgumentException(
              "Requested count must equal classified result count"
      );
    }
  }

  public static SpanWriteResult empty() {
    return new SpanWriteResult(0, 0, 0, 0);
  }
}