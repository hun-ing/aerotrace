package com.huning.aerotrace.auth.application;

import java.util.Objects;

public final class GeneratedProjectApiKey {

  private static final int SHA_256_BYTES = 32;

  private final String keyId;
  private final String rawKey;
  private final byte[] secretHash;

  public GeneratedProjectApiKey(
          String keyId,
          String rawKey,
          byte[] secretHash
  ) {
    if (keyId == null || keyId.isBlank()) {
      throw new IllegalArgumentException(
              "API Key ID must not be blank"
      );
    }

    if (rawKey == null || rawKey.isBlank()) {
      throw new IllegalArgumentException(
              "Raw API Key must not be blank"
      );
    }

    Objects.requireNonNull(
            secretHash,
            "Secret hash must not be null"
    );

    if (secretHash.length != SHA_256_BYTES) {
      throw new IllegalArgumentException(
              "Secret hash must be exactly "
                      + SHA_256_BYTES
                      + " bytes"
      );
    }

    this.keyId = keyId;
    this.rawKey = rawKey;
    this.secretHash = secretHash.clone();
  }

  public String keyId() {
    return keyId;
  }

  /**
   * 발급 시 한 번만 사용자에게 전달해야 한다.
   * 로그 또는 DB에 저장하면 안 된다.
   */
  public String rawKey() {
    return rawKey;
  }

  public byte[] secretHash() {
    return secretHash.clone();
  }

  @Override
  public String toString() {
    return "GeneratedProjectApiKey["
            + "keyId="
            + keyId
            + ", rawKey=<redacted>"
            + ", secretHash=<redacted>"
            + "]";
  }
}