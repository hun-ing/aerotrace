package com.huning.aerotrace.auth.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.session.web.http.CookieSerializer;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AeroTraceSecurityConfigurationTest {

  @Test
  void githubRegistrationPinsPkceCallbackAndMinimalScope() {
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

    ClientRegistrationRepository repository =
            new AeroTraceSecurityConfiguration()
                    .clientRegistrationRepository(properties);

    ClientRegistration registration =
            repository.findByRegistrationId("github");

    assertThat(registration).isNotNull();
    assertThat(registration.getRedirectUri())
            .isEqualTo(
                    "https://app.example.com/login/oauth2/code/github"
            );
    assertThat(registration.getScopes())
            .isEqualTo(Set.of("read:user"));
    assertThat(
            registration.getClientSettings()
                    .isRequireProofKey()
    ).isTrue();
  }

  @Test
  void productionSessionCookieUsesHostPrefixAndSecureAttributes() {
    AeroTraceAuthProperties properties =
            productionProperties();
    CookieSerializer serializer =
            new AeroTraceSecurityConfiguration()
                    .sessionCookieSerializer(properties);
    MockHttpServletRequest request =
            new MockHttpServletRequest();
    request.setSecure(true);
    MockHttpServletResponse response =
            new MockHttpServletResponse();

    serializer.writeCookieValue(
            new CookieSerializer.CookieValue(
                    request,
                    response,
                    "opaque-session-id"
            )
    );

    assertThat(response.getHeader("Set-Cookie"))
            .startsWith("__Host-aerotrace_session=")
            .contains("Path=/")
            .contains("Secure")
            .contains("HttpOnly")
            .contains("SameSite=Lax")
            .doesNotContain("Domain=");
  }

  private AeroTraceAuthProperties productionProperties() {
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
}
