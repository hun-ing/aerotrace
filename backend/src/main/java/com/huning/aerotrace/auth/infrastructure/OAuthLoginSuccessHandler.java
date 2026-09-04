package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.api.OnboardingIntentController;
import com.huning.aerotrace.auth.application.AeroTracePrincipal;
import com.huning.aerotrace.auth.application.GithubOAuthIdentity;
import com.huning.aerotrace.auth.application.OAuthLoginProvisioningService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "aerotrace.auth.enabled",
        havingValue = "true"
)
public class OAuthLoginSuccessHandler
        implements AuthenticationSuccessHandler {

  private static final String GITHUB_REGISTRATION_ID = "github";

  private final OAuthLoginProvisioningService provisioningService;
  private final RequestScopedOAuth2AuthorizedClientRepository
          authorizedClientRepository;
  private final OAuthLoginFailureHandler failureHandler;
  private final HttpSessionSecurityContextRepository
          sessionSecurityContextRepository;
  private final AeroTraceAuthProperties.ValidatedSettings settings;

  public OAuthLoginSuccessHandler(
          OAuthLoginProvisioningService provisioningService,
          RequestScopedOAuth2AuthorizedClientRepository
                  authorizedClientRepository,
          OAuthLoginFailureHandler failureHandler,
          AeroTraceAuthProperties properties
  ) {
    this.provisioningService = provisioningService;
    this.authorizedClientRepository = authorizedClientRepository;
    this.failureHandler = failureHandler;
    this.sessionSecurityContextRepository =
            new HttpSessionSecurityContextRepository();
    this.settings = properties.validateEnabled();
  }

  @Override
  public void onAuthenticationSuccess(
          HttpServletRequest request,
          HttpServletResponse response,
          Authentication authentication
  ) throws IOException {
    try {
      OAuth2AuthenticationToken oauthAuthentication =
              requireGithubAuthentication(authentication);

      OAuth2AuthorizedClient authorizedClient =
              authorizedClientRepository.loadAuthorizedClient(
                      GITHUB_REGISTRATION_ID,
                      oauthAuthentication,
                      request
              );

      authorizedClientRepository.removeAuthorizedClient(
              GITHUB_REGISTRATION_ID,
              oauthAuthentication,
              request,
              response
      );

      validateAuthorizedClient(authorizedClient);

      UUID inviteId = takeInviteId(request);
      GithubOAuthIdentity identity = githubIdentity(
              oauthAuthentication.getPrincipal()
      );

      AeroTracePrincipal principal =
              provisioningService.provisionGithubLogin(
                      identity,
                      inviteId,
                      UUID.randomUUID()
              );

      Authentication localAuthentication =
              new UsernamePasswordAuthenticationToken(
                      principal,
                      null,
                      List.of()
              );

      SecurityContext context =
              SecurityContextHolder.createEmptyContext();
      context.setAuthentication(localAuthentication);
      SecurityContextHolder.setContext(context);

      sessionSecurityContextRepository.saveContext(
              context,
              request,
              response
      );
      clearOauthAttempt(request);

      response.setStatus(HttpServletResponse.SC_FOUND);
      response.setHeader(
              HttpHeaders.LOCATION,
              settings.successRedirectPath()
      );
      response.setHeader(
              HttpHeaders.CACHE_CONTROL,
              "no-store"
      );
      response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    } catch (RuntimeException exception) {
      failureHandler.onAuthenticationFailure(
              request,
              response,
              new AuthenticationServiceException(
                      "OAuth login failed"
              )
      );
    }
  }

  private void clearOauthAttempt(
          HttpServletRequest request
  ) {
    HttpSession session = request.getSession(false);

    if (session != null) {
      session.removeAttribute(
              AuthenticationRateLimitFilter
                      .OAUTH_ATTEMPT_ATTRIBUTE
      );
    }
  }

  private OAuth2AuthenticationToken requireGithubAuthentication(
          Authentication authentication
  ) {
    if (
            !(authentication
                    instanceof OAuth2AuthenticationToken oauth)
                    || !GITHUB_REGISTRATION_ID.equals(
                    oauth.getAuthorizedClientRegistrationId()
            )
    ) {
      throw new IllegalArgumentException(
              "Unexpected OAuth authentication"
      );
    }

    return oauth;
  }

  private void validateAuthorizedClient(
          OAuth2AuthorizedClient authorizedClient
  ) {
    if (
            authorizedClient == null
                    || authorizedClient.getRefreshToken() != null
                    || !authorizedClient.getAccessToken()
                    .getScopes()
                    .equals(settings.allowedOauthScopes())
    ) {
      throw new IllegalArgumentException(
              "Unexpected OAuth authorized client"
      );
    }
  }

  private UUID takeInviteId(
          HttpServletRequest request
  ) {
    HttpSession session = request.getSession(false);

    if (session == null) {
      return null;
    }

    Object value = session.getAttribute(
            OnboardingIntentController.SESSION_ATTRIBUTE
    );

    session.removeAttribute(
            OnboardingIntentController.SESSION_ATTRIBUTE
    );

    return value instanceof UUID inviteId
            ? inviteId
            : null;
  }

  private GithubOAuthIdentity githubIdentity(
          OAuth2User oauthUser
  ) {
    Map<String, Object> attributes = oauthUser.getAttributes();

    return new GithubOAuthIdentity(
            requiredString(attributes.get("id")),
            requiredString(attributes.get("login")),
            optionalString(attributes.get("name")),
            optionalString(attributes.get("avatar_url"))
    );
  }

  private String requiredString(Object value) {
    if (value == null) {
      throw new IllegalArgumentException(
              "Required GitHub identity attribute is unavailable"
      );
    }

    String text = String.valueOf(value).trim();

    if (text.isEmpty()) {
      throw new IllegalArgumentException(
              "Required GitHub identity attribute is unavailable"
      );
    }

    return text;
  }

  private String optionalString(Object value) {
    if (value == null) {
      return null;
    }

    String text = String.valueOf(value).trim();
    return text.isEmpty() ? null : text;
  }
}
