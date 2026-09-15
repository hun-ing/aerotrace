package com.huning.aerotrace.auth.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@ConfigurationProperties("aerotrace.auth")
public class AeroTraceAuthProperties {

  private static final Set<String> REQUIRED_SCOPES =
          Set.of("read:user");

  private boolean enabled;
  private boolean allowInsecureLocal;
  private boolean secureCookie = true;
  private String cookieName = "__Host-aerotrace_session";
  private String publicOrigin = "";
  private String successRedirectPath = "/";
  private Duration absoluteSessionLifetime =
          Duration.ofDays(7);
  private Set<String> allowedOauthScopes =
          new LinkedHashSet<>(REQUIRED_SCOPES);
  private Github github = new Github();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isAllowInsecureLocal() {
    return allowInsecureLocal;
  }

  public void setAllowInsecureLocal(
          boolean allowInsecureLocal
  ) {
    this.allowInsecureLocal = allowInsecureLocal;
  }

  public boolean isSecureCookie() {
    return secureCookie;
  }

  public void setSecureCookie(boolean secureCookie) {
    this.secureCookie = secureCookie;
  }

  public String getCookieName() {
    return cookieName;
  }

  public void setCookieName(String cookieName) {
    this.cookieName = cookieName;
  }

  public String getPublicOrigin() {
    return publicOrigin;
  }

  public void setPublicOrigin(String publicOrigin) {
    this.publicOrigin = publicOrigin;
  }

  public String getSuccessRedirectPath() {
    return successRedirectPath;
  }

  public void setSuccessRedirectPath(
          String successRedirectPath
  ) {
    this.successRedirectPath = successRedirectPath;
  }

  public Duration getAbsoluteSessionLifetime() {
    return absoluteSessionLifetime;
  }

  public void setAbsoluteSessionLifetime(
          Duration absoluteSessionLifetime
  ) {
    this.absoluteSessionLifetime = absoluteSessionLifetime;
  }

  public Set<String> getAllowedOauthScopes() {
    return Set.copyOf(allowedOauthScopes);
  }

  public void setAllowedOauthScopes(
          Set<String> allowedOauthScopes
  ) {
    this.allowedOauthScopes =
            allowedOauthScopes == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(allowedOauthScopes);
  }

  public Github getGithub() {
    return github;
  }

  public void setGithub(Github github) {
    this.github = Objects.requireNonNull(
            github,
            "GitHub OAuth properties must not be null"
    );
  }

  public ValidatedSettings validateEnabled() {
    if (!enabled) {
      throw new IllegalStateException(
              "User authentication is disabled"
      );
    }

    URI origin = parseOrigin(publicOrigin);
    validateOriginAndCookie(origin);

    String validatedCookieName = validateCookieName();

    String redirectPath =
            validateRedirectPath(successRedirectPath);

    Duration absoluteLifetime =
            Objects.requireNonNull(
                    absoluteSessionLifetime,
                    "Absolute session lifetime must not be null"
            );

    if (
            absoluteLifetime.isZero()
                    || absoluteLifetime.isNegative()
                    || absoluteLifetime.compareTo(
                    Duration.ofDays(30)
            ) > 0
    ) {
      throw new IllegalStateException(
              "Absolute session lifetime must be greater than zero and at most 30 days"
      );
    }

    Set<String> scopes = Set.copyOf(allowedOauthScopes);

    if (!scopes.equals(REQUIRED_SCOPES)) {
      throw new IllegalStateException(
              "GitHub OAuth scopes must be exactly read:user"
      );
    }

    String clientId = requireSecretSetting(
            github.getClientId(),
            "GitHub OAuth client ID"
    );

    String clientSecret = requireSecretSetting(
            github.getClientSecret(),
            "GitHub OAuth client secret"
    );

    return new ValidatedSettings(
            origin,
            redirectPath,
            absoluteLifetime,
            scopes,
            clientId,
            clientSecret,
            validatedCookieName,
            secureCookie
    );
  }

  private String validateCookieName() {
    String requiredName = secureCookie
            ? "__Host-aerotrace_session"
            : "aerotrace_session";

    if (!requiredName.equals(cookieName)) {
      throw new IllegalStateException(
              "Authentication session cookie name does not match the security profile"
      );
    }

    return requiredName;
  }

  private URI parseOrigin(String rawOrigin) {
    if (rawOrigin == null || rawOrigin.isBlank()) {
      throw new IllegalStateException(
              "Authentication public origin must not be blank"
      );
    }

    final URI origin;

    try {
      origin = URI.create(rawOrigin.trim());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
              "Authentication public origin must be a valid URI",
              exception
      );
    }

    if (
            !origin.isAbsolute()
                    || origin.getHost() == null
                    || origin.getUserInfo() != null
                    || origin.getQuery() != null
                    || origin.getFragment() != null
                    || origin.getPort() == 0
                    || origin.getPort() > 65535
                    || !(
                    origin.getPath() == null
                            || origin.getPath().isEmpty()
                            || "/".equals(origin.getPath())
            )
    ) {
      throw new IllegalStateException(
              "Authentication public origin must contain only scheme, host, and optional port"
      );
    }

    String normalizedHost = origin.getHost()
            .toLowerCase(Locale.ROOT);
    String authorityHost =
            normalizedHost.contains(":")
                    && !normalizedHost.startsWith("[")
                    ? "[" + normalizedHost + "]"
                    : normalizedHost;
    String normalizedAuthority =
            origin.getPort() == -1
                    ? authorityHost
                    : authorityHost + ":" + origin.getPort();

    return URI.create(
            origin.getScheme().toLowerCase(Locale.ROOT)
                    + "://"
                    + normalizedAuthority
    );
  }

  private void validateOriginAndCookie(URI origin) {
    String scheme = origin.getScheme()
            .toLowerCase(Locale.ROOT);

    if ("https".equals(scheme)) {
      if (!secureCookie) {
        throw new IllegalStateException(
                "HTTPS authentication requires a Secure session cookie"
        );
      }
      return;
    }

    if (
            !"http".equals(scheme)
                    || !allowInsecureLocal
                    || secureCookie
                    || !isLoopbackHost(origin.getHost())
    ) {
      throw new IllegalStateException(
              "Insecure authentication is allowed only for an explicitly enabled loopback origin"
      );
    }
  }

  private boolean isLoopbackHost(String host) {
    return "localhost".equalsIgnoreCase(host)
            || "127.0.0.1".equals(host)
            || "[::1]".equals(host)
            || "::1".equals(host);
  }

  private String validateRedirectPath(String rawPath) {
    if (
            rawPath == null
                    || rawPath.isBlank()
                    || !rawPath.startsWith("/")
                    || rawPath.startsWith("//")
                    || rawPath.contains("\\")
                    || rawPath.contains(":")
                    || rawPath.chars().anyMatch(
                    Character::isISOControl
            )
    ) {
      throw new IllegalStateException(
              "Authentication success redirect must be a safe same-origin relative path"
      );
    }

    return rawPath;
  }

  private String requireSecretSetting(
          String value,
          String settingName
  ) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
              settingName + " must not be blank"
      );
    }

    return value.trim();
  }

  public record ValidatedSettings(
          URI publicOrigin,
          String successRedirectPath,
          Duration absoluteSessionLifetime,
          Set<String> allowedOauthScopes,
          String clientId,
          String clientSecret,
          String cookieName,
          boolean secureCookie
  ) {

    public ValidatedSettings {
      allowedOauthScopes = Set.copyOf(
              allowedOauthScopes
      );
    }

    @Override
    public String toString() {
      return "ValidatedSettings["
              + "publicOrigin="
              + publicOrigin
              + ", successRedirectPath="
              + successRedirectPath
              + ", absoluteSessionLifetime="
              + absoluteSessionLifetime
              + ", allowedOauthScopes="
              + allowedOauthScopes
              + ", clientId=<redacted>"
              + ", clientSecret=<redacted>"
              + ", cookieName="
              + cookieName
              + ", secureCookie="
              + secureCookie
              + "]";
    }
  }

  public static class Github {

    private String clientId = "";
    private String clientSecret = "";

    public String getClientId() {
      return clientId;
    }

    public void setClientId(String clientId) {
      this.clientId = clientId;
    }

    public String getClientSecret() {
      return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
      this.clientSecret = clientSecret;
    }
  }
}
