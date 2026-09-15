package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.AeroTracePrincipal;
import com.huning.aerotrace.auth.application.CurrentUserStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
@ConditionalOnProperty(
        name = "aerotrace.auth.enabled",
        havingValue = "true"
)
public class AuthenticatedSessionValidationFilter
        extends OncePerRequestFilter {

  private final CurrentUserStore currentUserStore;
  private final Clock clock;
  private final Duration absoluteSessionLifetime;

  public AuthenticatedSessionValidationFilter(
          CurrentUserStore currentUserStore,
          Clock clock,
          AeroTraceAuthProperties properties
  ) {
    this.currentUserStore = currentUserStore;
    this.clock = clock;
    this.absoluteSessionLifetime =
            properties.validateEnabled()
                    .absoluteSessionLifetime();
  }

  @Override
  protected boolean shouldNotFilter(
          HttpServletRequest request
  ) {
    return !"/api/v1/me".equals(
            request.getRequestURI()
    );
  }

  @Override
  protected void doFilterInternal(
          HttpServletRequest request,
          HttpServletResponse response,
          FilterChain filterChain
  ) throws ServletException, IOException {
    Authentication authentication =
            SecurityContextHolder.getContext()
                    .getAuthentication();

    if (
            authentication == null
                    || !(authentication.getPrincipal()
                    instanceof AeroTracePrincipal principal)
    ) {
      filterChain.doFilter(request, response);
      return;
    }

    Instant absoluteExpiry = principal.authenticatedAt()
            .plus(absoluteSessionLifetime);

    final boolean activeUser;

    try {
      activeUser = currentUserStore.activeUserExists(
              principal.userId()
      );
    } catch (RuntimeException exception) {
      response.setStatus(
              HttpServletResponse.SC_SERVICE_UNAVAILABLE
      );
      response.setHeader(
              HttpHeaders.CACHE_CONTROL,
              "no-store"
      );
      return;
    }

    if (
            !clock.instant().isBefore(absoluteExpiry)
                    || !activeUser
    ) {
      invalidateAuthentication(request, response);
      return;
    }

    filterChain.doFilter(request, response);
  }

  private void invalidateAuthentication(
          HttpServletRequest request,
          HttpServletResponse response
  ) {
    SecurityContextHolder.clearContext();

    HttpSession session = request.getSession(false);

    if (session != null) {
      session.invalidate();
    }

    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setHeader(
            HttpHeaders.CACHE_CONTROL,
            "no-store"
    );
  }
}
