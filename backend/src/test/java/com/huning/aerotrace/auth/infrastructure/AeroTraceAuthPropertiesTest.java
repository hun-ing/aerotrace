package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.api.AuthCsrfController;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AeroTraceAuthPropertiesTest {

  @Test
  void productionSettingsRequireHttpsAndSecureCookie() {
    AeroTraceAuthProperties properties = enabledProperties();
    properties.setPublicOrigin("HTTPS://App.Example.Com/");

    AeroTraceAuthProperties.ValidatedSettings settings =
            properties.validateEnabled();

    assertThat(settings.publicOrigin().toString())
            .isEqualTo("https://app.example.com");
    assertThat(settings.secureCookie()).isTrue();
    assertThat(settings.absoluteSessionLifetime())
            .isEqualTo(Duration.ofDays(7));
  }

  @Test
  void insecureOriginIsLimitedToExplicitLoopbackProfile() {
    AeroTraceAuthProperties properties = enabledProperties();
    properties.setPublicOrigin("http://127.0.0.1:8080");

    assertThatThrownBy(properties::validateEnabled)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("loopback origin");

    properties.setAllowInsecureLocal(true);
    properties.setSecureCookie(false);
    properties.setCookieName("aerotrace_session");

    assertThat(properties.validateEnabled().publicOrigin().toString())
            .isEqualTo("http://127.0.0.1:8080");

    properties.setPublicOrigin("http://example.com:8080");

    assertThatThrownBy(properties::validateEnabled)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("loopback origin");
  }

  @Test
  void scopeRedirectAndLifetimeAreFailClosed() {
    AeroTraceAuthProperties properties = enabledProperties();
    properties.setPublicOrigin("https://app.example.com");

    properties.setAllowedOauthScopes(
            Set.of("read:user", "repo")
    );

    assertThatThrownBy(properties::validateEnabled)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exactly read:user");

    properties.setAllowedOauthScopes(Set.of("read:user"));
    properties.setSuccessRedirectPath("//attacker.example");

    assertThatThrownBy(properties::validateEnabled)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("same-origin");

    properties.setSuccessRedirectPath("/");
    properties.setAbsoluteSessionLifetime(Duration.ofDays(31));

    assertThatThrownBy(properties::validateEnabled)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at most 30 days");
  }

  @Test
  void validatedSettingsNeverRenderOauthCredentials() {
    AeroTraceAuthProperties properties = enabledProperties();
    properties.setPublicOrigin("https://app.example.com");

    String rendered = properties.validateEnabled().toString();

    assertThat(rendered)
            .doesNotContain("test-client-id")
            .doesNotContain("test-client-secret")
            .contains("clientId=<redacted>")
            .contains("clientSecret=<redacted>");
  }

  @Test
  void cookieNameMustMatchTheSelectedSecurityProfile() {
    AeroTraceAuthProperties properties = enabledProperties();
    properties.setPublicOrigin("https://app.example.com");
    properties.setCookieName("aerotrace_session");

    assertThatThrownBy(properties::validateEnabled)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cookie name");
  }

  @Test
  void csrfResponseStringNeverRendersTheToken() {
    AuthCsrfController.CsrfResponse response =
            new AuthCsrfController.CsrfResponse(
                    "X-CSRF-TOKEN",
                    "_csrf",
                    "csrf-token-must-not-be-logged"
            );

    assertThat(response.toString())
            .doesNotContain("csrf-token-must-not-be-logged")
            .contains("token=<redacted>");
  }

  private AeroTraceAuthProperties enabledProperties() {
    AeroTraceAuthProperties properties =
            new AeroTraceAuthProperties();
    properties.setEnabled(true);

    AeroTraceAuthProperties.Github github =
            new AeroTraceAuthProperties.Github();
    github.setClientId("test-client-id");
    github.setClientSecret("test-client-secret");
    properties.setGithub(github);

    return properties;
  }
}
