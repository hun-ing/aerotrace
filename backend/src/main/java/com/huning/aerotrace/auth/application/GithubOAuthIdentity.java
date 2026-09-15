package com.huning.aerotrace.auth.application;

import java.net.URI;

public record GithubOAuthIdentity(
        String providerSubject,
        String login,
        String displayName,
        String avatarUrl
) {

  public GithubOAuthIdentity {
    if (
            providerSubject == null
                    || !providerSubject.matches("^[0-9]+$")
                    || providerSubject.length() > 100
    ) {
      throw new IllegalArgumentException(
              "GitHub provider subject must be a numeric identifier"
      );
    }

    if (
            login == null
                    || login.isBlank()
                    || login.length() > 100
    ) {
      throw new IllegalArgumentException(
              "GitHub login must not be blank or exceed 100 characters"
      );
    }

    login = login.trim();
    displayName = normalizeOptional(displayName);
    avatarUrl = normalizeAvatarUrl(avatarUrl);
  }

  private static String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }

    return value.trim();
  }

  private static String normalizeAvatarUrl(String value) {
    String normalized = normalizeOptional(value);

    if (normalized == null) {
      return null;
    }

    URI uri;

    try {
      uri = URI.create(normalized);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
              "GitHub avatar URL must be a valid HTTPS URL",
              exception
      );
    }

    if (
            !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
    ) {
      throw new IllegalArgumentException(
              "GitHub avatar URL must be a valid HTTPS URL"
      );
    }

    return uri.toString();
  }
}
