package com.huning.aerotrace.ingest.api;

import com.huning.aerotrace.auth.application.AuthenticatedProject;
import com.huning.aerotrace.auth.application
        .ProjectApiKeyAuthenticationMetrics;
import com.huning.aerotrace.auth.application
        .ProjectApiKeyAuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web
        .MockHttpServletRequest;
import org.springframework.mock.web
        .MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceDetailAuthenticationFilterTest {

  private static final String TRACE_ID =
          "dddddddddddddddddddddddddddddddd";

  @Mock
  private ProjectApiKeyAuthenticationService
          authenticationService;

  @Mock
  private ObjectMapper objectMapper;

  @Mock
  private ProjectApiKeyAuthenticationMetrics
          authenticationMetrics;

  @Mock
  private AuthenticatedProject authenticatedProject;

  private OtlpApiKeyAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    filter =
            new OtlpApiKeyAuthenticationFilter(
                    authenticationService,
                    objectMapper,
                    authenticationMetrics
            );
  }

  @Test
  void authenticatesTraceDetailGetRequest()
          throws Exception {
    MockHttpServletRequest request =
            new MockHttpServletRequest(
                    "GET",
                    "/api/v1/traces/" + TRACE_ID
            );

    request.addHeader(
            HttpHeaders.AUTHORIZATION,
            "Bearer valid-api-key"
    );

    MockHttpServletResponse response =
            new MockHttpServletResponse();

    when(
            authenticationService.authenticate(
                    "valid-api-key"
            )
    ).thenReturn(
            Optional.of(authenticatedProject)
    );

    AtomicBoolean filterChainInvoked =
            new AtomicBoolean(false);

    FilterChain filterChain =
            (servletRequest, servletResponse) -> {
              filterChainInvoked.set(true);

              Object requestAttribute =
                      (
                              (HttpServletRequest)
                                      servletRequest
                      ).getAttribute(
                              OtlpRequestAttributes
                                      .AUTHENTICATED_PROJECT
                      );

              assertThat(requestAttribute)
                      .isSameAs(
                              authenticatedProject
                      );
            };

    filter.doFilter(
            request,
            response,
            filterChain
    );

    assertThat(filterChainInvoked.get())
            .isTrue();

    assertThat(
            request.getAttribute(
                    OtlpRequestAttributes
                            .AUTHENTICATED_PROJECT
            )
    ).isSameAs(authenticatedProject);

    verify(authenticationService)
            .authenticate("valid-api-key");
  }
}