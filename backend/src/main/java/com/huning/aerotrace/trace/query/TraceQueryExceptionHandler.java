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
    return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .cacheControl(
                    CacheControl.noStore()
            )
            .body(
                    new TraceQueryErrorResponse(
                            exception.getMessage()
                    )
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
    return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .cacheControl(
                    CacheControl.noStore()
            )
            .body(
                    new TraceQueryErrorResponse(
                            "Missing required parameter: "
                                    + exception
                                    .getParameterName()
                    )
            );
  }
}