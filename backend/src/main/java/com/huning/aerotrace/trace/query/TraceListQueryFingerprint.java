package com.huning.aerotrace.trace.query;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

public final class TraceListQueryFingerprint {

  private static final Base64.Encoder BASE64_ENCODER =
          Base64.getUrlEncoder()
                  .withoutPadding();

  private TraceListQueryFingerprint() {
  }

  /*
   * 기존 호출과 기존 cursor의 호환을 유지한다.
   *
   * minSpanDurationNano가 없으면 기존과 동일한
   * canonical query를 사용한다.
   */
  public static String create(
          UUID tenantId,
          UUID projectId,
          Instant from,
          Instant to,
          String serviceName,
          boolean errorOnly
  ) {
    return create(
            tenantId,
            projectId,
            from,
            to,
            serviceName,
            errorOnly,
            null
    );
  }

  public static String create(
          UUID tenantId,
          UUID projectId,
          Instant from,
          Instant to,
          String serviceName,
          boolean errorOnly,
          Long minSpanDurationNano
  ) {
    Objects.requireNonNull(
            tenantId,
            "tenantId must not be null"
    );

    Objects.requireNonNull(
            projectId,
            "projectId must not be null"
    );

    Objects.requireNonNull(
            from,
            "from must not be null"
    );

    Objects.requireNonNull(
            to,
            "to must not be null"
    );

    if (
            minSpanDurationNano != null
                    && minSpanDurationNano < 0
    ) {
      throw new IllegalArgumentException(
              "minSpanDurationNano must not be negative"
      );
    }

    String encodedServiceName =
            serviceName == null
                    ? "-"
                    : BASE64_ENCODER.encodeToString(
                    serviceName.getBytes(
                            StandardCharsets.UTF_8
                    )
            );

    String canonicalQuery =
            String.join(
                    "\n",
                    "tenantId=" + tenantId,
                    "projectId=" + projectId,
                    "fromEpochSecond="
                            + from.getEpochSecond(),
                    "fromNano="
                            + from.getNano(),
                    "toEpochSecond="
                            + to.getEpochSecond(),
                    "toNano="
                            + to.getNano(),
                    "serviceName="
                            + encodedServiceName,
                    "errorOnly="
                            + errorOnly
            );

    /*
     * duration 필터가 없을 때는 기존 fingerprint와
     * 동일한 값을 유지한다.
     */
    if (minSpanDurationNano != null) {
      canonicalQuery =
              canonicalQuery
                      + "\nminSpanDurationNano="
                      + minSpanDurationNano;
    }

    byte[] digest =
            sha256(
                    canonicalQuery.getBytes(
                            StandardCharsets.UTF_8
                    )
            );

    return BASE64_ENCODER.encodeToString(
            digest
    );
  }

  private static byte[] sha256(
          byte[] value
  ) {
    try {
      MessageDigest messageDigest =
              MessageDigest.getInstance(
                      "SHA-256"
              );

      return messageDigest.digest(value);
    } catch (
            NoSuchAlgorithmException exception
    ) {
      throw new IllegalStateException(
              "SHA-256 is not available",
              exception
      );
    }
  }
}