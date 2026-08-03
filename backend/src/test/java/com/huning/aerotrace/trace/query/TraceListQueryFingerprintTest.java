package com.huning.aerotrace.trace.query;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TraceListQueryFingerprintTest {

  private static final UUID TENANT_ID =
          UUID.fromString(
                  "11111111-1111-1111-1111-111111111111"
          );

  private static final UUID PROJECT_ID =
          UUID.fromString(
                  "22222222-2222-2222-2222-222222222222"
          );

  private static final Instant FROM =
          Instant.parse(
                  "2026-08-01T00:00:00Z"
          );

  private static final Instant TO =
          Instant.parse(
                  "2026-08-03T06:00:00Z"
          );

  @Test
  void createsDeterministicFingerprint() {
    String first =
            fingerprint(
                    PROJECT_ID,
                    "orders-service",
                    true
            );

    String second =
            fingerprint(
                    PROJECT_ID,
                    "orders-service",
                    true
            );

    assertThat(first)
            .isEqualTo(second);

    assertThat(first)
            .hasSize(43);

    assertThat(first)
            .matches(
                    "^[A-Za-z0-9_-]{43}$"
            );
  }

  @Test
  void changesWhenServiceFilterChanges() {
    String ordersService =
            fingerprint(
                    PROJECT_ID,
                    "orders-service",
                    true
            );

    String paymentService =
            fingerprint(
                    PROJECT_ID,
                    "payment-service",
                    true
            );

    assertThat(ordersService)
            .isNotEqualTo(paymentService);
  }

  @Test
  void changesWhenErrorFilterChanges() {
    String errorOnly =
            fingerprint(
                    PROJECT_ID,
                    "orders-service",
                    true
            );

    String allTraces =
            fingerprint(
                    PROJECT_ID,
                    "orders-service",
                    false
            );

    assertThat(errorOnly)
            .isNotEqualTo(allTraces);
  }

  @Test
  void changesWhenProjectChanges() {
    UUID otherProjectId =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    String firstProject =
            fingerprint(
                    PROJECT_ID,
                    null,
                    false
            );

    String secondProject =
            fingerprint(
                    otherProjectId,
                    null,
                    false
            );

    assertThat(firstProject)
            .isNotEqualTo(secondProject);
  }

  private static String fingerprint(
          UUID projectId,
          String serviceName,
          boolean errorOnly
  ) {
    return TraceListQueryFingerprint.create(
            TENANT_ID,
            projectId,
            FROM,
            TO,
            serviceName,
            errorOnly
    );
  }
}