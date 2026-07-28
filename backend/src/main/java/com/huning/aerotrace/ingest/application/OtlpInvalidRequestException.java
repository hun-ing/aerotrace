package com.huning.aerotrace.ingest.application;

public final class OtlpInvalidRequestException
        extends RuntimeException {

  public OtlpInvalidRequestException(
          String message
  ) {
    super(message);
  }
}