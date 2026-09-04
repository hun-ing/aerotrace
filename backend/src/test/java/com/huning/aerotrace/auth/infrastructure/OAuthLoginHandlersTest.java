package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.api.OnboardingIntentController;
import com.huning.aerotrace.auth.application.AeroTracePrincipal;
import com.huning.aerotrace.auth.application.GithubOAuthIdentity;
import com.huning.aerotrace.auth.application.OAuthLoginProvisioningService;
import com.huning.aerotrace.auth.application.SecurityAuditEventStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthLoginHandlersTest {

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void successReplacesProviderStateWithMinimalLocalPrincipal()
          throws Exception {
    OAuthLoginProvisioningService provisioningService =
            mock(OAuthLoginProvisioningService.class);
    RequestScopedOAuth2AuthorizedClientRepository repository =
            new RequestScopedOAuth2AuthorizedClientRepository();
    OAuthLoginFailureHandler failureHandler =
            failureHandler();
    AeroTraceAuthProperties properties = enabledProperties();

    OAuthLoginSuccessHandler handler =
            new OAuthLoginSuccessHandler(
                    provisioningService,
                    repository,
                    failureHandler,
                    properties
            );

    UUID inviteId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Instant authenticatedAt = Instant.parse(
            "2026-08-26T00:00:00Z"
    );

    when(
            provisioningService.provisionGithubLogin(
                    any(),
                    any(),
                    any()
            )
    ).thenReturn(
            new AeroTracePrincipal(
                    userId,
                    authenticatedAt
            )
    );

    OAuth2AuthenticationToken oauth = oauthAuthentication();
    OAuth2AuthorizedClient authorizedClient =
            authorizedClient(
                    oauth,
                    Set.of("read:user")
            );
    MockHttpServletRequest request =
            new MockHttpServletRequest();
    MockHttpServletResponse response =
            new MockHttpServletResponse();
    MockHttpSession session =
            (MockHttpSession) request.getSession(true);
    session.setAttribute(
            OnboardingIntentController.SESSION_ATTRIBUTE,
            inviteId
    );

    repository.saveAuthorizedClient(
            authorizedClient,
            oauth,
            request,
            response
    );

    handler.onAuthenticationSuccess(
            request,
            response,
            oauth
    );

    assertThat(response.getStatus()).isEqualTo(302);
    assertThat(response.getHeader("Location")).isEqualTo("/");
    assertThat(response.getHeader("Cache-Control"))
            .isEqualTo("no-store");
    assertThat(
            session.getAttribute(
                    OnboardingIntentController.SESSION_ATTRIBUTE
            )
    ).isNull();
    OAuth2AuthorizedClient removedClient =
            repository.loadAuthorizedClient(
                    "github",
                    oauth,
                    request
            );

    assertThat(removedClient).isNull();

    Object storedContext = session.getAttribute(
            HttpSessionSecurityContextRepository
                    .SPRING_SECURITY_CONTEXT_KEY
    );

    assertThat(storedContext)
            .isInstanceOf(SecurityContext.class);

    SecurityContext context = (SecurityContext) storedContext;
    assertThat(context.getAuthentication().getPrincipal())
            .isEqualTo(
                    new AeroTracePrincipal(
                            userId,
                            authenticatedAt
                    )
            );
    assertThat(context.getAuthentication().getPrincipal())
            .isNotInstanceOf(DefaultOAuth2User.class);
    assertThat(Collections.list(session.getAttributeNames()))
            .containsExactly(
                    HttpSessionSecurityContextRepository
                            .SPRING_SECURITY_CONTEXT_KEY
            );

    ArgumentCaptor<GithubOAuthIdentity> identityCaptor =
            ArgumentCaptor.forClass(
                    GithubOAuthIdentity.class
            );

    verify(provisioningService).provisionGithubLogin(
            identityCaptor.capture(),
            org.mockito.ArgumentMatchers.eq(inviteId),
            any()
    );

    assertThat(identityCaptor.getValue().providerSubject())
            .isEqualTo("123456789");
    assertThat(identityCaptor.getValue().login())
            .isEqualTo("aerotrace-user");
  }

  @Test
  void unexpectedScopeFailsAndInvalidatesAnonymousSession()
          throws Exception {
    OAuthLoginProvisioningService provisioningService =
            mock(OAuthLoginProvisioningService.class);
    RequestScopedOAuth2AuthorizedClientRepository repository =
            new RequestScopedOAuth2AuthorizedClientRepository();
    OAuthLoginFailureHandler failureHandler =
            failureHandler();
    OAuthLoginSuccessHandler handler =
            new OAuthLoginSuccessHandler(
                    provisioningService,
                    repository,
                    failureHandler,
                    enabledProperties()
            );

    OAuth2AuthenticationToken oauth = oauthAuthentication();
    MockHttpServletRequest request =
            new MockHttpServletRequest();
    MockHttpServletResponse response =
            new MockHttpServletResponse();
    MockHttpSession session =
            (MockHttpSession) request.getSession(true);

    repository.saveAuthorizedClient(
            authorizedClient(
                    oauth,
                    Set.of("read:user", "repo")
            ),
            oauth,
            request,
            response
    );

    handler.onAuthenticationSuccess(
            request,
            response,
            oauth
    );

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentAsString())
            .isEqualTo(
                    "{\"message\":\"Authentication failed\"}"
            );
    assertThat(response.getContentAsString())
            .doesNotContain("repo")
            .doesNotContain("test-access-token");
    assertThat(session.isInvalid()).isTrue();
  }

  private OAuth2AuthenticationToken oauthAuthentication() {
    DefaultOAuth2User user = new DefaultOAuth2User(
            List.of(
                    new SimpleGrantedAuthority("OAUTH2_USER")
            ),
            Map.of(
                    "id",
                    123456789L,
                    "login",
                    "aerotrace-user",
                    "name",
                    "AeroTrace User",
                    "avatar_url",
                    "https://avatars.githubusercontent.com/u/123456789"
            ),
            "id"
    );

    return new OAuth2AuthenticationToken(
            user,
            user.getAuthorities(),
            "github"
    );
  }

  private OAuth2AuthorizedClient authorizedClient(
          OAuth2AuthenticationToken oauth,
          Set<String> scopes
  ) {
    ClientRegistration registration =
            new AeroTraceSecurityConfiguration()
                    .clientRegistrationRepository(
                            enabledProperties()
                    )
                    .findByRegistrationId("github");

    OAuth2AccessToken accessToken =
            new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    "test-access-token",
                    Instant.now(),
                    Instant.now().plusSeconds(300),
                    scopes
            );

    return new OAuth2AuthorizedClient(
            registration,
            oauth.getName(),
            accessToken
    );
  }

  private AeroTraceAuthProperties enabledProperties() {
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

    return properties;
  }

  private OAuthLoginFailureHandler failureHandler() {
    return new OAuthLoginFailureHandler(
            mock(SecurityAuditEventStore.class),
            Clock.fixed(
                    Instant.parse("2026-08-26T00:00:00Z"),
                    ZoneOffset.UTC
            )
    );
  }
}
