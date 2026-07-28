package com.huning.aerotrace.ingest.api;

import com.huning.aerotrace.ingest.application.OtlpInvalidRequestException;
import com.huning.aerotrace.ingest.application.OtlpSpanLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

@RestControllerAdvice
public class OtlpExceptionHandler {

  @ExceptionHandler(
          OtlpInvalidRequestException.class
  )
  public ResponseEntity<OtlpHttpStatusResponse>
  handleInvalidOtlpRequest(
          OtlpInvalidRequestException exception
  ) {
    return response(
            HttpStatus.BAD_REQUEST,
            exception.getMessage()
    );
  }

  @ExceptionHandler(
          HttpMessageNotReadableException.class
  )
  public ResponseEntity<OtlpHttpStatusResponse>
  handleUnreadableMessage() {
    return response(
            HttpStatus.BAD_REQUEST,
            "OTLP request body is not valid JSON"
    );
  }

  @ExceptionHandler(
          OtlpSpanLimitExceededException.class
  )
  public ResponseEntity<OtlpHttpStatusResponse>
  handleSpanLimitExceeded(
          OtlpSpanLimitExceededException exception
  ) {
    return response(
            HttpStatus.CONTENT_TOO_LARGE,
            exception.getMessage()
    );
  }

  @ExceptionHandler(
          MissingRequestHeaderException.class
  )
  public ResponseEntity<OtlpHttpStatusResponse>
  handleMissingRequestHeader(
          MissingRequestHeaderException exception
  ) {
    return response(
            HttpStatus.BAD_REQUEST,
            "Required request header is missing: "
                    + exception.getHeaderName()
    );
  }

  @ExceptionHandler(
          MethodArgumentTypeMismatchException.class
  )
  public ResponseEntity<OtlpHttpStatusResponse>
  handleArgumentTypeMismatch(
          MethodArgumentTypeMismatchException exception
  ) {
    return response(
            HttpStatus.BAD_REQUEST,
            "Request value has an invalid format: "
                    + exception.getName()
    );
  }

  @ExceptionHandler(
          HttpMediaTypeNotSupportedException.class
  )
  public ResponseEntity<OtlpHttpStatusResponse>
  handleUnsupportedMediaType(
          HttpMediaTypeNotSupportedException exception
  ) {
    return response(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Unsupported OTLP Content-Type"
    );
  }

  @ExceptionHandler({
          DataAccessResourceFailureException.class,
          RecoverableDataAccessException.class,
          TransientDataAccessException.class
  })
  public ResponseEntity<OtlpHttpStatusResponse>
  handleTemporaryStorageFailure(
          RuntimeException exception
  ) {
    return response(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Telemetry storage is temporarily unavailable"
    );
  }

  private ResponseEntity<OtlpHttpStatusResponse>
  response(
          HttpStatus status,
          String message
  ) {
    return ResponseEntity
            .status(status)
            .body(
                    new OtlpHttpStatusResponse(
                            message
                    )
            );
  }
}