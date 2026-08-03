package com.huning.aerotrace.trace.query;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind
        .MissingServletRequestParameterException;
import org.springframework.web.bind.annotation
        .ExceptionHandler;
import org.springframework.web.bind.annotation
        .RestControllerAdvice;

@RestControllerAdvice(
        assignableTypes = TraceQueryController.class
)
public class TraceQueryExceptionHandler {

  @ExceptionHandler(
          IllegalArgumentException.class
  )
  public ResponseEntity<TraceQueryErrorResponse>
  handleIllegalArgument(
          IllegalArgumentException exception
  ) {
    return errorResponse(
            HttpStatus.BAD_REQUEST,
            exception.getMessage()
    );
  }

  @ExceptionHandler(
          MissingServletRequestParameterException.class
  )
  public ResponseEntity<TraceQueryErrorResponse>
  handleMissingParameter(
          MissingServletRequestParameterException
                  exception
  ) {
    return errorResponse(
            HttpStatus.BAD_REQUEST,
            "Missing required parameter: "
                    + exception.getParameterName()
    );
  }

  @ExceptionHandler(
          TraceNotFoundException.class
  )
  public ResponseEntity<TraceQueryErrorResponse>
  handleTraceNotFound(
          TraceNotFoundException exception
  ) {
    return errorResponse(
            HttpStatus.NOT_FOUND,
            exception.getMessage()
    );
  }

  @ExceptionHandler(
          TraceSpanLimitExceededException.class
  )
  public ResponseEntity<TraceQueryErrorResponse>
  handleTraceSpanLimitExceeded(
          TraceSpanLimitExceededException exception
  ) {
    return errorResponse(
            HttpStatus.UNPROCESSABLE_ENTITY,
            exception.getMessage()
    );
  }

  private static ResponseEntity<TraceQueryErrorResponse>
  errorResponse(
          HttpStatus status,
          String message
  ) {
    return ResponseEntity
            .status(status)
            .cacheControl(
                    CacheControl.noStore()
            )
            .body(
                    new TraceQueryErrorResponse(
                            message
                    )
            );
  }
}