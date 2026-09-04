package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.AeroTracePrincipal;
import com.huning.aerotrace.auth.application.CurrentUserStore;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticatedSessionValidationFilterTest {

  private static final Instant NOW = Instant.parse(
          "2026-08-26T12:00:00Z"
  );

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void activeNonExpiredSessionContinues() throws Exception {
    CurrentUserStore store = mock(CurrentUserStore.class);
    UUID userId = UUID.randomUUID();
    when(store.activeUserExists(userId)).thenReturn(true);

    AuthenticatedSessionValidationFilter filter = filter(store);
    authenticate(
            new AeroTracePrincipal(
                    userId,
                    NOW.minusSeconds(60)
            )
    );

    MockHttpServletRequest request =
            currentUserRequest();
    MockHttpServletResponse response =
            new MockHttpServletResponse();
    AtomicBoolean continued = new AtomicBoolean();
    FilterChain chain = (
            servletRequest,
            servletResponse
    ) -> continued.set(true);

    filter.doFilter(request, response, chain);

    assertThat(continued).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void expiredOrDisabledSessionIsInvalidated() throws Exception {
    CurrentUserStore store = mock(CurrentUserStore.class);
    UUID userId = UUID.randomUUID();
    when(store.activeUserExists(userId)).thenReturn(false);

    AuthenticatedSessionValidationFilter filter = filter(store);
    authenticate(
            new AeroTracePrincipal(
                    userId,
                    NOW.minusSeconds(8 * 24 * 60 * 60L)
            )
    );

    MockHttpServletRequest request =
            currentUserRequest();
    MockHttpSession session =
            (MockHttpSession) request.getSession(true);
    MockHttpServletResponse response =
            new MockHttpServletResponse();

    filter.doFilter(
            request,
            response,
            (
                    servletRequest,
                    servletResponse
            ) -> {
              throw new AssertionError(
                      "Expired session must not continue"
              );
            }
    );

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader("Cache-Control"))
            .isEqualTo("no-store");
    assertThat(session.isInvalid()).isTrue();
    assertThat(
            SecurityContextHolder.getContext()
                    .getAuthentication()
    ).isNull();
  }

  private AuthenticatedSessionValidationFilter filter(
          CurrentUserStore store
  ) {
    AeroTraceAuthProperties properties =
            new AeroTraceAuthProperties();
    properties.setEnabled(true);
    properties.setPublicOrigin(
            "https://app.example.com"
    );

    AeroTraceAuthProperties.Github github =
            new AeroTraceAuthProperties.Github();
    github.setClientId("test-client-id");
    github.setClientSecret("test-client-secret");
    properties.setGithub(github);

    return new AuthenticatedSessionValidationFilter(
            store,
            Clock.fixed(NOW, ZoneOffset.UTC),
            properties
    );
  }

  private MockHttpServletRequest currentUserRequest() {
    return new MockHttpServletRequest(
            "GET",
            "/api/v1/me"
    );
  }

  private void authenticate(
          AeroTracePrincipal principal
  ) {
    SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of()
            )
    );
  }
}
