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

  public static String create(
          UUID tenantId,
          UUID projectId,
          Instant from,
          Instant to,
          String serviceName,
          boolean errorOnly
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

    String encodedServiceName =
            serviceName == null
                    ? "-"
                    : BASE64_ENCODER.encodeToString(
                    serviceName.getBytes(
                            StandardCharsets.UTF_8
                    )
            );

    /*
     * 각 시간은 epochSecond와 nano를 분리해
     * Instant의 나노초 정밀도를 보존한다.
     *
     * serviceName은 Base64로 변환해 구분자 충돌을 막는다.
     */
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
      /*
       * SHA-256은 Java 표준 구현에서 필수 알고리즘이다.
       * 발생한다면 실행 환경 자체의 심각한 구성 오류다.
       */
      throw new IllegalStateException(
              "SHA-256 is not available",
              exception
      );
    }
  }
}