package com.huning.aerotrace.ingest.api;

public record OtlpHttpStatusResponse(
        String message
) {

  public OtlpHttpStatusResponse {
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException(
              "OTLP error message must not be blank"
      );
    }
  }
}