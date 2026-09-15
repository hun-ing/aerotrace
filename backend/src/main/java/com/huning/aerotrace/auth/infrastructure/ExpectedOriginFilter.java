package com.huning.aerotrace.auth.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Set;

@Component
@ConditionalOnProperty(
        name = "aerotrace.auth.enabled",
        havingValue = "true"
)
public class ExpectedOriginFilter extends OncePerRequestFilter {

  private static final Set<String> PROTECTED_PATHS = Set.of(
          "/api/v1/onboarding/intents",
          "/api/v1/logout"
  );

  private final String expectedOrigin;

  public ExpectedOriginFilter(
          AeroTraceAuthProperties properties
  ) {
    this.expectedOrigin = properties.validateEnabled()
            .publicOrigin()
            .toString();
  }

  @Override
  protected boolean shouldNotFilter(
          HttpServletRequest request
  ) {
    return !"POST".equals(request.getMethod())
            || !PROTECTED_PATHS.contains(
            request.getRequestURI()
    );
  }

  @Override
  protected void doFilterInternal(
          HttpServletRequest request,
          HttpServletResponse response,
          FilterChain filterChain
  ) throws ServletException, IOException {
    Enumeration<String> origins = request.getHeaders(
            HttpHeaders.ORIGIN
    );

    if (
            origins == null
                    || !origins.hasMoreElements()
                    || !expectedOrigin.equals(origins.nextElement())
                    || origins.hasMoreElements()
    ) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      response.setHeader(
              HttpHeaders.CACHE_CONTROL,
              "no-store"
      );
      return;
    }

    filterChain.doFilter(request, response);
  }
}
