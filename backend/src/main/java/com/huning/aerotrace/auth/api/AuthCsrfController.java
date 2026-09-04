package com.huning.aerotrace.auth.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
        name = "aerotrace.auth.enabled",
        havingValue = "true"
)
public class AuthCsrfController {

  @GetMapping("/api/v1/auth/csrf")
  public ResponseEntity<CsrfResponse> csrf(
          CsrfToken csrfToken
  ) {
    return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                    new CsrfResponse(
                            csrfToken.getHeaderName(),
                            csrfToken.getParameterName(),
                            csrfToken.getToken()
                    )
            );
  }

  public record CsrfResponse(
          String headerName,
          String parameterName,
          String token
  ) {

    @Override
    public String toString() {
      return "CsrfResponse["
              + "headerName="
              + headerName
              + ", parameterName="
              + parameterName
              + ", token=<redacted>"
              + "]";
    }
  }
}
