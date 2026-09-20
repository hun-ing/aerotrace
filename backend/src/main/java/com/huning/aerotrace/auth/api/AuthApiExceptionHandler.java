package com.huning.aerotrace.auth.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;

import com.huning.aerotrace.auth.application.CurrentUserService;

@RestControllerAdvice(
        assignableTypes = {
                OnboardingIntentController.class,
                CurrentUserController.class,
                WorkspaceController.class
        }
)
@ConditionalOnProperty(
        name = "aerotrace.auth.enabled",
        havingValue = "true"
)
public class AuthApiExceptionHandler {

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  public ResponseEntity<AuthErrorResponse> temporarilyUnavailable() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore())
            .body(new AuthErrorResponse("Service is temporarily unavailable"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<AuthErrorResponse> invalidRequest() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .cacheControl(CacheControl.noStore())
            .body(
                    new AuthErrorResponse(
                            "Request is invalid or unavailable"
                    )
            );
  }

  @ExceptionHandler({
          HttpMessageNotReadableException.class,
          HttpMediaTypeNotSupportedException.class
  })
  public ResponseEntity<AuthErrorResponse> unreadableRequest() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .cacheControl(CacheControl.noStore())
            .body(
                    new AuthErrorResponse(
                            "Request is invalid or unavailable"
                    )
            );
  }

  @ExceptionHandler(
          CurrentUserService.CurrentUserUnavailableException.class
  )
  public ResponseEntity<AuthErrorResponse> unavailableUser() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .cacheControl(CacheControl.noStore())
            .body(
                    new AuthErrorResponse(
                            "Authentication is invalid or unavailable"
                    )
            );
  }

  public record AuthErrorResponse(
          String message
  ) {
  }
}
