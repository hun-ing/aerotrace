package com.huning.aerotrace.auth.infrastructure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "aerotrace.auth.enabled",
        havingValue = "true"
)
public class RequestScopedOAuth2AuthorizedClientRepository
        implements OAuth2AuthorizedClientRepository {

  private static final String ATTRIBUTE =
          RequestScopedOAuth2AuthorizedClientRepository.class
                  .getName()
                  + ".AUTHORIZED_CLIENT";

  @Override
  @SuppressWarnings("unchecked")
  public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
          String clientRegistrationId,
          Authentication principal,
          HttpServletRequest request
  ) {
    Object value = request.getAttribute(ATTRIBUTE);

    if (!(value instanceof OAuth2AuthorizedClient client)) {
      return null;
    }

    if (
            !client.getClientRegistration()
                    .getRegistrationId()
                    .equals(clientRegistrationId)
                    || !client.getPrincipalName()
                    .equals(principal.getName())
    ) {
      return null;
    }

    return (T) client;
  }

  @Override
  public void saveAuthorizedClient(
          OAuth2AuthorizedClient authorizedClient,
          Authentication principal,
          HttpServletRequest request,
          HttpServletResponse response
  ) {
    if (
            !authorizedClient.getPrincipalName()
                    .equals(principal.getName())
    ) {
      throw new IllegalArgumentException(
              "OAuth authorized-client principal mismatch"
      );
    }

    request.setAttribute(
            ATTRIBUTE,
            authorizedClient
    );
  }

  @Override
  public void removeAuthorizedClient(
          String clientRegistrationId,
          Authentication principal,
          HttpServletRequest request,
          HttpServletResponse response
  ) {
    request.removeAttribute(ATTRIBUTE);
  }
}
