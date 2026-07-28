package com.huning.aerotrace.ingest.api;

import com.huning.aerotrace.auth.application.AuthenticatedProject;
import com.huning.aerotrace.auth.application.GeneratedProjectApiKey;
import com.huning.aerotrace.auth.application.ProjectApiKeyAuthenticationService;
import com.huning.aerotrace.auth.application.ProjectApiKeyCredentialStore;
import com.huning.aerotrace.auth.application.ProjectApiKeyTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import com.huning.aerotrace.auth.application.ProjectApiKeyAuthenticationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpApiKeyAuthenticationFilterTest {

  private static final UUID API_KEY_ID =
          UUID.fromString(
                  "33333333-3333-3333-3333-333333333333"
          );

  private static final UUID TENANT_ID =
          UUID.fromString(
                  "11111111-1111-1111-1111-111111111111"
          );

  private static final UUID PROJECT_ID =
          UUID.fromString(
                  "22222222-2222-2222-2222-222222222222"
          );

  @Test
  void rejectsRequestWithoutAuthorizationHeader()
          throws Exception {
    Fixture fixture =
            createFixture();

    MockHttpServletRequest request =
            traceRequest();

    MockHttpServletResponse response =
            new MockHttpServletResponse();

    AtomicBoolean chainCalled =
            new AtomicBoolean(false);

    fixture.filter().doFilter(
            request,
            response,
            (
                    servletRequest,
                    servletResponse
            ) ->
                    chainCalled.set(true)
    );

    assertEquals(
            401,
            response.getStatus()
    );

    assertEquals(
            "Bearer realm=\"aerotrace\"",
            response.getHeader(
                    HttpHeaders.WWW_AUTHENTICATE
            )
    );

    assertTrue(
            response.getContentAsString()
                    .contains(
                            "Authentication credentials "
                                    + "are missing or invalid"
                    )
    );

    assertFalse(chainCalled.get());
  }

  @Test
  void rejectsMalformedAuthorizationHeader()
          throws Exception {
    Fixture fixture =
            createFixture();

    MockHttpServletRequest request =
            traceRequest();

    request.addHeader(
            HttpHeaders.AUTHORIZATION,
            "ApiKey " + fixture.generated().rawKey()
    );

    MockHttpServletResponse response =
            new MockHttpServletResponse();

    AtomicBoolean chainCalled =
            new AtomicBoolean(false);

    fixture.filter().doFilter(
            request,
            response,
            (
                    servletRequest,
                    servletResponse
            ) ->
                    chainCalled.set(true)
    );

    assertEquals(
            401,
            response.getStatus()
    );

    assertFalse(chainCalled.get());
  }

  @Test
  void authenticatesAndAddsProjectToRequest()
          throws Exception {
    Fixture fixture =
            createFixture();

    MockHttpServletRequest request =
            traceRequest();

    request.addHeader(
            HttpHeaders.AUTHORIZATION,
            "Bearer "
                    + fixture.generated().rawKey()
    );

    /*
     * 과거 임시 헤더를 보내더라도 인증 결과에는
     * 영향을 주지 않아야 한다.
     */
    request.addHeader(
            "X-AeroTrace-Tenant-Id",
            "99999999-9999-9999-9999-999999999999"
    );

    request.addHeader(
            "X-AeroTrace-Project-Id",
            "88888888-8888-8888-8888-888888888888"
    );

    MockHttpServletResponse response =
            new MockHttpServletResponse();

    AtomicBoolean chainCalled =
            new AtomicBoolean(false);

    fixture.filter().doFilter(
            request,
            response,
            (
                    servletRequest,
                    servletResponse
            ) -> {
              chainCalled.set(true);

              Object attribute =
                      servletRequest.getAttribute(
                              OtlpRequestAttributes
                                      .AUTHENTICATED_PROJECT
                      );

              AuthenticatedProject authenticated =
                      assertInstanceOf(
                              AuthenticatedProject.class,
                              attribute
                      );

              assertEquals(
                      TENANT_ID,
                      authenticated.tenantId()
              );

              assertEquals(
                      PROJECT_ID,
                      authenticated.projectId()
              );
            }
    );

    assertTrue(chainCalled.get());
    assertEquals(200, response.getStatus());
  }

  private Fixture createFixture() {
    ProjectApiKeyTokenService tokenService =
            new ProjectApiKeyTokenService();

    GeneratedProjectApiKey generated =
            tokenService.generate();

    ProjectApiKeyCredentialStore credentialStore =
            keyId -> {
              if (
                      !generated.keyId()
                              .equals(keyId)
              ) {
                return Optional.empty();
              }

              return Optional.of(
                      new ProjectApiKeyCredentialStore
                              .StoredProjectApiKey(
                              API_KEY_ID,
                              TENANT_ID,
                              PROJECT_ID,
                              generated.keyId(),
                              generated.secretHash(),
                              Instant.now()
                                      .plusSeconds(3600),
                              null
                      )
              );
            };

    SimpleMeterRegistry meterRegistry =
            new SimpleMeterRegistry();

    ProjectApiKeyAuthenticationMetrics
            authenticationMetrics =
            new ProjectApiKeyAuthenticationMetrics(
                    meterRegistry
            );

    ProjectApiKeyAuthenticationService
            authenticationService =
            new ProjectApiKeyAuthenticationService(
                    tokenService,
                    credentialStore,
                    authenticationMetrics
            );

    OtlpApiKeyAuthenticationFilter filter =
            new OtlpApiKeyAuthenticationFilter(
                    authenticationService,
                    new ObjectMapper(),
                    authenticationMetrics
            );

    return new Fixture(
            filter,
            generated
    );
  }

  private MockHttpServletRequest traceRequest() {
    return new MockHttpServletRequest(
            "POST",
            "/v1/traces"
    );
  }

  private record Fixture(
          OtlpApiKeyAuthenticationFilter filter,
          GeneratedProjectApiKey generated
  ) {
  }
}