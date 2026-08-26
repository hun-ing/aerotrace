package com.huning.aerotrace.auth.application;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public final class OnboardingInviteTokenService {

  private static final String PREFIX = "ati_";
  private static final int RANDOM_BYTES = 32;
  private static final int ENCODED_LENGTH = 43;

  private static final Pattern TOKEN_PATTERN =
          Pattern.compile(
                  "^ati_[A-Za-z0-9_-]{"
                          + ENCODED_LENGTH
                          + "}$"
          );

  private static final Base64.Encoder BASE64_URL_ENCODER =
          Base64.getUrlEncoder().withoutPadding();

  private final SecureRandom secureRandom;

  public OnboardingInviteTokenService() {
    this(new SecureRandom());
  }

  OnboardingInviteTokenService(
          SecureRandom secureRandom
  ) {
    this.secureRandom =
            Objects.requireNonNull(
                    secureRandom,
                    "SecureRandom must not be null"
            );
  }

  public GeneratedInviteToken generate() {
    byte[] randomBytes =
            new byte[RANDOM_BYTES];

    secureRandom.nextBytes(randomBytes);

    String rawToken =
            PREFIX
                    + BASE64_URL_ENCODER
                    .encodeToString(randomBytes);

    return new GeneratedInviteToken(
            rawToken,
            hash(rawToken).orElseThrow()
    );
  }

  public Optional<byte[]> hash(
          String rawToken
  ) {
    if (
            rawToken == null
                    || !TOKEN_PATTERN.matcher(rawToken).matches()
    ) {
      return Optional.empty();
    }

    try {
      MessageDigest digest =
              MessageDigest.getInstance("SHA-256");

      return Optional.of(
              digest.digest(
                      rawToken.getBytes(
                              StandardCharsets.UTF_8
                      )
              )
      );
    } catch (
            NoSuchAlgorithmException exception
    ) {
      throw new IllegalStateException(
              "SHA-256 is not available",
              exception
      );
    }
  }

  public record GeneratedInviteToken(
          String rawToken,
          byte[] tokenHash
  ) {

    public GeneratedInviteToken {
      if (
              rawToken == null
                      || !TOKEN_PATTERN.matcher(rawToken).matches()
      ) {
        throw new IllegalArgumentException(
                "Generated invite token has an invalid format"
        );
      }

      if (tokenHash == null || tokenHash.length != 32) {
        throw new IllegalArgumentException(
                "Generated invite token hash must be 32 bytes"
        );
      }

      tokenHash = tokenHash.clone();
    }

    @Override
    public byte[] tokenHash() {
      return tokenHash.clone();
    }

    @Override
    public String toString() {
      return "GeneratedInviteToken["
              + "rawToken=<redacted>"
              + ", tokenHash=<redacted>"
              + "]";
    }
  }
}
