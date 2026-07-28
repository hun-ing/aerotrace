package com.huning.aerotrace.auth.application;

import io.micrometer.core.instrument.simple
        .SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static com.huning.aerotrace.auth.application
        .ProjectApiKeyAuthenticationMetrics
        .AuthenticationResult;
import static com.huning.aerotrace.auth.application
        .ProjectApiKeyAuthenticationMetrics
        .LookupResult;
import static org.junit.jupiter.api.Assertions
        .assertEquals;

class ProjectApiKeyAuthenticationMetricsTest {

  @Test
  void recordsAuthenticationResultWithFixedTags() {
    SimpleMeterRegistry registry =
            new SimpleMeterRegistry();

    ProjectApiKeyAuthenticationMetrics metrics =
            new ProjectApiKeyAuthenticationMetrics(
                    registry
            );

    metrics.recordAuthentication(
            AuthenticationResult.SUCCESS
    );

    metrics.recordAuthentication(
            AuthenticationResult.UNKNOWN_KEY
    );

    assertEquals(
            1.0,
            registry.get(
                            ProjectApiKeyAuthenticationMetrics
                                    .AUTHENTICATION_ATTEMPTS
                    )
                    .tag(
                            "outcome",
                            "success"
                    )
                    .tag(
                            "reason",
                            "none"
                    )
                    .counter()
                    .count()
    );

    assertEquals(
            1.0,
            registry.get(
                            ProjectApiKeyAuthenticationMetrics
                                    .AUTHENTICATION_ATTEMPTS
                    )
                    .tag(
                            "outcome",
                            "failure"
                    )
                    .tag(
                            "reason",
                            "unknown_key"
                    )
                    .counter()
                    .count()
    );
  }

  @Test
  void recordsCredentialLookupDuration() {
    SimpleMeterRegistry registry =
            new SimpleMeterRegistry();

    ProjectApiKeyAuthenticationMetrics metrics =
            new ProjectApiKeyAuthenticationMetrics(
                    registry
            );

    metrics.recordLookup(
            1_000_000L,
            LookupResult.FOUND
    );

    assertEquals(
            1L,
            registry.get(
                            ProjectApiKeyAuthenticationMetrics
                                    .LOOKUP_DURATION
                    )
                    .tag(
                            "result",
                            "found"
                    )
                    .timer()
                    .count()
    );
  }
}