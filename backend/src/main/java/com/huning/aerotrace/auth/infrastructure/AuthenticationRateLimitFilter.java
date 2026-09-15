package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.api.OnboardingIntentController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.time.Clock;

@Component
@ConditionalOnProperty(
        name = "aerotrace.auth.enabled",
        havingValue = "true"
)
public class AuthenticationRateLimitFilter
        extends OncePerRequestFilter {

  static final int MAX_ATTEMPTS = 10;
  static final long WINDOW_SECONDS = 10 * 60L;

  private static final String SESSION_ATTRIBUTE =
          AuthenticationRateLimitFilter.class.getName()
                  + ".WINDOW";

  static final String OAUTH_ATTEMPT_ATTRIBUTE =
          AuthenticationRateLimitFilter.class.getName()
                  + ".OAUTH_ATTEMPT_STARTED";

  private final Clock clock;

  public AuthenticationRateLimitFilter(Clock clock) {
    this.clock = clock;
  }

  @Override
  protected boolean shouldNotFilter(
          HttpServletRequest request
  ) {
    boolean onboarding =
            "POST".equals(request.getMethod())
                    && "/api/v1/onboarding/intents".equals(
                    request.getRequestURI()
            );
    boolean loginStart =
            "GET".equals(request.getMethod())
                    && "/oauth2/authorization/github".equals(
                    request.getRequestURI()
            );

    return !onboarding && !loginStart;
  }

  @Override
  protected void doFilterInternal(
          HttpServletRequest request,
          HttpServletResponse response,
          FilterChain filterChain
  ) throws ServletException, IOException {
    HttpSession session = request.getSession(true);
    long now = clock.instant().getEpochSecond();
    AttemptWindow updated;

    if (isOnboardingRequest(request)) {
      session.removeAttribute(
              OnboardingIntentController.SESSION_ATTRIBUTE
      );
    }

    synchronized (session) {
      Object stored = session.getAttribute(
              SESSION_ATTRIBUTE
      );
      AttemptWindow current =
              stored instanceof AttemptWindow window
                      ? window
                      : null;

      if (
              current == null
                      || now < current.startedAtEpochSecond()
                      || now - current.startedAtEpochSecond()
                      >= WINDOW_SECONDS
      ) {
        updated = new AttemptWindow(now, 1);
      } else if (current.attempts() >= MAX_ATTEMPTS) {
        reject(response, current, now);
        return;
      } else {
        updated = new AttemptWindow(
                current.startedAtEpochSecond(),
                current.attempts() + 1
        );
      }

      session.setAttribute(
              SESSION_ATTRIBUTE,
              updated
      );

      if (isLoginStartRequest(request)) {
        session.setAttribute(
                OAUTH_ATTEMPT_ATTRIBUTE,
                Boolean.TRUE
        );
      }
    }

    filterChain.doFilter(request, response);
  }

  private boolean isOnboardingRequest(
          HttpServletRequest request
  ) {
    return "POST".equals(request.getMethod())
            && "/api/v1/onboarding/intents".equals(
            request.getRequestURI()
    );
  }

  private boolean isLoginStartRequest(
          HttpServletRequest request
  ) {
    return "GET".equals(request.getMethod())
            && "/oauth2/authorization/github".equals(
            request.getRequestURI()
    );
  }

  private void reject(
          HttpServletResponse response,
          AttemptWindow window,
          long now
  ) {
    long retryAfter = Math.max(
            1,
            WINDOW_SECONDS
                    - (now - window.startedAtEpochSecond())
    );

    response.setStatus(429);
    response.setHeader(
            HttpHeaders.CACHE_CONTROL,
            "no-store"
    );
    response.setHeader(
            HttpHeaders.RETRY_AFTER,
            Long.toString(retryAfter)
    );
  }

  private record AttemptWindow(
          long startedAtEpochSecond,
          int attempts
  ) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
  }
}
