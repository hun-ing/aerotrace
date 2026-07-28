package com.huning.aerotrace.auth.application;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public final class ProjectApiKeyTokenService {

  private static final String PREFIX = "atr_";

  private static final int KEY_ID_RANDOM_BYTES = 12;
  private static final int SECRET_RANDOM_BYTES = 32;
  private static final int SHA_256_BYTES = 32;

  private static final int KEY_ID_ENCODED_LENGTH = 16;
  private static final int SECRET_ENCODED_LENGTH = 43;

  private static final Base64.Encoder BASE64_URL_ENCODER =
          Base64.getUrlEncoder()
                  .withoutPadding();

  private static final Pattern API_KEY_PATTERN =
          Pattern.compile(
                  "^atr_"
                          + "([A-Za-z0-9_-]{"
                          + KEY_ID_ENCODED_LENGTH
                          + "})"
                          + "\\."
                          + "([A-Za-z0-9_-]{"
                          + SECRET_ENCODED_LENGTH
                          + "})"
                          + "$"
          );

  private final SecureRandom secureRandom;

  public ProjectApiKeyTokenService() {
    this(new SecureRandom());
  }

  ProjectApiKeyTokenService(
          SecureRandom secureRandom
  ) {
    this.secureRandom =
            Objects.requireNonNull(
                    secureRandom,
                    "SecureRandom must not be null"
            );
  }

  public GeneratedProjectApiKey generate() {
    String keyId =
            generateRandomToken(
                    KEY_ID_RANDOM_BYTES
            );

    String secret =
            generateRandomToken(
                    SECRET_RANDOM_BYTES
            );

    String rawKey =
            PREFIX
                    + keyId
                    + "."
                    + secret;

    byte[] secretHash =
            hashSecret(secret);

    return new GeneratedProjectApiKey(
            keyId,
            rawKey,
            secretHash
    );
  }

  public Optional<ParsedProjectApiKey> parse(
          String rawKey
  ) {
    if (rawKey == null || rawKey.isBlank()) {
      return Optional.empty();
    }

    Matcher matcher =
            API_KEY_PATTERN.matcher(rawKey);

    if (!matcher.matches()) {
      return Optional.empty();
    }

    return Optional.of(
            new ParsedProjectApiKey(
                    matcher.group(1),
                    matcher.group(2)
            )
    );
  }

  public boolean matchesSecret(
          String presentedSecret,
          byte[] expectedSecretHash
  ) {
    if (
            presentedSecret == null
                    || presentedSecret.isBlank()
                    || expectedSecretHash == null
                    || expectedSecretHash.length
                    != SHA_256_BYTES
    ) {
      return false;
    }

    byte[] actualSecretHash =
            hashSecret(presentedSecret);

    return MessageDigest.isEqual(
            actualSecretHash,
            expectedSecretHash
    );
  }

  byte[] hashSecret(
          String secret
  ) {
    Objects.requireNonNull(
            secret,
            "API Key secret must not be null"
    );

    try {
      MessageDigest digest =
              MessageDigest.getInstance(
                      "SHA-256"
              );

      return digest.digest(
              secret.getBytes(
                      StandardCharsets.UTF_8
              )
      );
    } catch (
            NoSuchAlgorithmException exception
    ) {
      /*
       * SHA-256은 Java 플랫폼이 반드시 제공해야 하는
       * 표준 알고리즘이므로 이 오류는 런타임 환경 문제다.
       */
      throw new IllegalStateException(
              "SHA-256 is not available",
              exception
      );
    }
  }

  private String generateRandomToken(
          int randomByteLength
  ) {
    byte[] randomBytes =
            new byte[randomByteLength];

    secureRandom.nextBytes(randomBytes);

    return BASE64_URL_ENCODER
            .encodeToString(randomBytes);
  }
}