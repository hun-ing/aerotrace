package com.huning.aerotrace.auth.application;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AeroTracePrincipal(
        UUID userId,
        Instant authenticatedAt
) implements Principal, Serializable {

  @Serial
  private static final long serialVersionUID = 1L;

  public AeroTracePrincipal {
    Objects.requireNonNull(
            userId,
            "User ID must not be null"
    );

    Objects.requireNonNull(
            authenticatedAt,
            "Authentication time must not be null"
    );
  }

  @Override
  public String getName() {
    return userId.toString();
  }
}
