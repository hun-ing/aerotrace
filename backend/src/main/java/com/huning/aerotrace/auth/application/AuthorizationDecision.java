package com.huning.aerotrace.auth.application;

import java.util.Objects;

public record AuthorizationDecision(
        AuthorizationOutcome outcome,
        TenantRole role
) {

  public AuthorizationDecision {
    Objects.requireNonNull(
            outcome,
            "Authorization outcome must not be null"
    );

    if (
            outcome == AuthorizationOutcome.NOT_FOUND
                    && role != null
    ) {
      throw new IllegalArgumentException(
              "A hidden resource must not expose a tenant role"
      );
    }

    if (
            outcome != AuthorizationOutcome.NOT_FOUND
                    && role == null
    ) {
      throw new IllegalArgumentException(
              "An evaluated membership must include its tenant role"
      );
    }
  }

  public boolean allowed() {
    return outcome == AuthorizationOutcome.ALLOWED;
  }

  public enum AuthorizationOutcome {
    ALLOWED,
    FORBIDDEN,
    NOT_FOUND
  }
}
