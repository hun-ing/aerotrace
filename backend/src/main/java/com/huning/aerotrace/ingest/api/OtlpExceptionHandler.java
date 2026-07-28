package com.huning.aerotrace.ingest.api;

import com.huning.aerotrace.ingest.application.OtlpSpanLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class OtlpExceptionHandler {

  @ExceptionHandler(OtlpSpanLimitExceededException.class)
  @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
  public Map<String, Object> handleSpanLimitExceeded(
          OtlpSpanLimitExceededException exception
  ) {
    return Map.of(
            "code",
            "SPAN_LIMIT_EXCEEDED",
            "message",
            exception.getMessage(),
            "maxSpansPerRequest",
            exception.maxSpansPerRequest()
    );
  }
}