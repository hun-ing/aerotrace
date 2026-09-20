package com.huning.aerotrace.auth.api;

import com.huning.aerotrace.auth.application.WorkspaceService.WorkspaceUnavailableException;
import com.huning.aerotrace.trace.query.SessionTraceQueryController;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {WorkspaceController.class, SessionTraceQueryController.class})
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class WorkspaceExceptionHandler {
  @ExceptionHandler({WorkspaceUnavailableException.class, MethodArgumentTypeMismatchException.class})
  public ResponseEntity<AuthApiExceptionHandler.AuthErrorResponse> unavailable() {
    return ResponseEntity.status(404).cacheControl(CacheControl.noStore())
            .body(new AuthApiExceptionHandler.AuthErrorResponse("Resource is unavailable"));
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<AuthApiExceptionHandler.AuthErrorResponse> temporarilyUnavailable() {
    return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
            .body(new AuthApiExceptionHandler.AuthErrorResponse("Service is temporarily unavailable"));
  }
}
