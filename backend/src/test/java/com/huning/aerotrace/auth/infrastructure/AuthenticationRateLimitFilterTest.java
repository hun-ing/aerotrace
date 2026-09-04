package com.huning.aerotrace.auth.infrastructure;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationRateLimitFilterTest {

  @Test
  void eleventhAuthAttemptInWindowIsRejected()
          throws Exception {
    Clock clock = Clock.fixed(
            Instant.parse("2026-08-26T12:00:00Z"),
            ZoneOffset.UTC
    );
    AuthenticationRateLimitFilter filter =
            new AuthenticationRateLimitFilter(clock);
    MockHttpSession session = new MockHttpSession();
    AtomicInteger continued = new AtomicInteger();
    FilterChain chain = (
            request,
            response
    ) -> continued.incrementAndGet();

    for (
            int attempt = 1;
            attempt <= AuthenticationRateLimitFilter.MAX_ATTEMPTS;
            attempt++
    ) {
      MockHttpServletRequest request = onboardingRequest(
              session
      );
      MockHttpServletResponse response =
              new MockHttpServletResponse();

      filter.doFilter(request, response, chain);

      assertThat(response.getStatus()).isEqualTo(200);
    }

    MockHttpServletRequest rejectedRequest =
            onboardingRequest(session);
    MockHttpServletResponse rejectedResponse =
            new MockHttpServletResponse();

    filter.doFilter(
            rejectedRequest,
            rejectedResponse,
            chain
    );

    assertThat(continued)
            .hasValue(
                    AuthenticationRateLimitFilter.MAX_ATTEMPTS
            );
    assertThat(rejectedResponse.getStatus()).isEqualTo(429);
    assertThat(rejectedResponse.getHeader("Retry-After"))
            .isEqualTo("600");
    assertThat(rejectedResponse.getHeader("Cache-Control"))
            .isEqualTo("no-store");
  }

  private MockHttpServletRequest onboardingRequest(
          MockHttpSession session
  ) {
    MockHttpServletRequest request =
            new MockHttpServletRequest(
                    "POST",
                    "/api/v1/onboarding/intents"
            );
    request.setSession(session);
    return request;
  }
}
