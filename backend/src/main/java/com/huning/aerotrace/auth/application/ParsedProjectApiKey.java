package com.huning.aerotrace.auth.application;

public final class ParsedProjectApiKey {

  private final String keyId;
  private final String secret;

  public ParsedProjectApiKey(
          String keyId,
          String secret
  ) {
    if (keyId == null || keyId.isBlank()) {
      throw new IllegalArgumentException(
              "API Key ID must not be blank"
      );
    }

    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException(
              "API Key secret must not be blank"
      );
    }

    this.keyId = keyId;
    this.secret = secret;
  }

  public String keyId() {
    return keyId;
  }

  public String secret() {
    return secret;
  }

  @Override
  public String toString() {
    return "ParsedProjectApiKey["
            + "keyId="
            + keyId
            + ", secret=<redacted>"
            + "]";
  }
}