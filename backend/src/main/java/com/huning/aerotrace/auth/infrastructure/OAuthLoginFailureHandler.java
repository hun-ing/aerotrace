package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.SecurityAuditEventStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "aerotrace.auth.enabled",
        havingValue = "true"
)
public class OAuthLoginFailureHandler
        implements AuthenticationFailureHandler {

  private static final String FAILURE_BODY =
          "{\"message\":\"Authentication failed\"}";

  private final SecurityAuditEventStore auditEventStore;
  private final Clock clock;

  public OAuthLoginFailureHandler(
          SecurityAuditEventStore auditEventStore,
          Clock clock
  ) {
    this.auditEventStore = auditEventStore;
    this.clock = clock;
  }

  @Override
  public void onAuthenticationFailure(
          HttpServletRequest request,
          HttpServletResponse response,
          AuthenticationException exception
  ) throws IOException {
    fail(request, response);
  }

  void fail(
          HttpServletRequest request,
          HttpServletResponse response
  ) throws IOException {
    SecurityContextHolder.clearContext();

    HttpSession session = request.getSession(false);
    boolean trackedOauthAttempt =
            session != null
                    && Boolean.TRUE.equals(
                    session.getAttribute(
                            AuthenticationRateLimitFilter
                                    .OAUTH_ATTEMPT_ATTRIBUTE
                    )
            );

    if (session != null) {
      session.invalidate();
    }

    if (trackedOauthAttempt) {
      auditFailure();
    }

    response.resetBuffer();
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setHeader(
            HttpHeaders.CACHE_CONTROL,
            "no-store"
    );
    response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(FAILURE_BODY);
  }

  private void auditFailure() {
    try {
      auditEventStore.save(
              new SecurityAuditEventStore.NewSecurityAuditEvent(
                      UUID.randomUUID(),
                      null,
                      null,
                      null,
                      "OAUTH_LOGIN_FAILED",
                      SecurityAuditEventStore.AuditResult.DENIED,
                      clock.instant(),
                      UUID.randomUUID()
              )
      );
    } catch (RuntimeException ignored) {
      // Authentication failure remains fail-closed if audit storage is down.
    }
  }
}
