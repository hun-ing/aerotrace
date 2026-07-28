package com.huning.aerotrace.auth.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class ProjectApiKeyAuthenticationMetrics {

  static final String AUTHENTICATION_ATTEMPTS =
          "aerotrace.auth.api_key.attempts";

  static final String LOOKUP_DURATION =
          "aerotrace.auth.api_key.lookup.duration";

  private final Map<AuthenticationResult, Counter>
          authenticationCounters;

  private final Map<LookupResult, Timer>
          lookupTimers;

  public ProjectApiKeyAuthenticationMetrics(
          MeterRegistry meterRegistry
  ) {
    this.authenticationCounters =
            new EnumMap<>(
                    AuthenticationResult.class
            );

    this.lookupTimers =
            new EnumMap<>(
                    LookupResult.class
            );

    for (
            AuthenticationResult result
            : AuthenticationResult.values()
    ) {
      Counter counter =
              Counter.builder(
                              AUTHENTICATION_ATTEMPTS
                      )
                      .description(
                              "Project API Key "
                                      + "authentication attempts"
                      )
                      .tag(
                              "outcome",
                              result.outcome()
                      )
                      .tag(
                              "reason",
                              result.reason()
                      )
                      .register(meterRegistry);

      authenticationCounters.put(
              result,
              counter
      );
    }

    for (
            LookupResult result
            : LookupResult.values()
    ) {
      Timer timer =
              Timer.builder(
                              LOOKUP_DURATION
                      )
                      .description(
                              "Project API Key credential "
                                      + "database lookup duration"
                      )
                      .tag(
                              "result",
                              result.tagValue()
                      )
                      .register(meterRegistry);

      lookupTimers.put(
              result,
              timer
      );
    }
  }

  public void recordAuthentication(
          AuthenticationResult result
  ) {
    authenticationCounters
            .get(result)
            .increment();
  }

  public void recordLookup(
          long elapsedNanoseconds,
          LookupResult result
  ) {
    lookupTimers
            .get(result)
            .record(
                    Math.max(
                            0L,
                            elapsedNanoseconds
                    ),
                    TimeUnit.NANOSECONDS
            );
  }

  public enum AuthenticationResult {

    SUCCESS(
            "success",
            "none"
    ),

    MISSING_CREDENTIALS(
            "failure",
            "missing_credentials"
    ),

    INVALID_AUTHORIZATION(
            "failure",
            "invalid_authorization"
    ),

    MALFORMED_KEY(
            "failure",
            "malformed_key"
    ),

    UNKNOWN_KEY(
            "failure",
            "unknown_key"
    ),

    SECRET_MISMATCH(
            "failure",
            "secret_mismatch"
    ),

    EXPIRED(
            "failure",
            "expired"
    ),

    REVOKED(
            "failure",
            "revoked"
    ),

    LOOKUP_ERROR(
            "error",
            "lookup_error"
    );

    private final String outcome;
    private final String reason;

    AuthenticationResult(
            String outcome,
            String reason
    ) {
      this.outcome = outcome;
      this.reason = reason;
    }

    public String outcome() {
      return outcome;
    }

    public String reason() {
      return reason;
    }
  }

  public enum LookupResult {

    FOUND("found"),
    NOT_FOUND("not_found"),
    ERROR("error");

    private final String tagValue;

    LookupResult(
            String tagValue
    ) {
      this.tagValue = tagValue;
    }

    public String tagValue() {
      return tagValue;
    }
  }
}