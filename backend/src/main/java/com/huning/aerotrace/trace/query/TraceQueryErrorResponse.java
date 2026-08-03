package com.huning.aerotrace.trace.query;

import java.util.Objects;

public record TraceQueryErrorResponse(
        String message
) {

  public TraceQueryErrorResponse {
    Objects.requireNonNull(
            message,
            "message must not be null"
    );

    if (message.isBlank()) {
      throw new IllegalArgumentException(
              "message must not be blank"
      );
    }
  }
}