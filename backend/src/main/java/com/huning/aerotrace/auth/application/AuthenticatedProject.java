package com.huning.aerotrace.auth.application;

import java.util.Objects;
import java.util.UUID;

public record AuthenticatedProject(
        UUID apiKeyId,
        UUID tenantId,
        UUID projectId,
        String keyId
) {

  public AuthenticatedProject {
    Objects.requireNonNull(
            apiKeyId,
            "API Key ID must not be null"
    );

    Objects.requireNonNull(
            tenantId,
            "Tenant ID must not be null"
    );

    Objects.requireNonNull(
            projectId,
            "Project ID must not be null"
    );

    if (keyId == null || keyId.isBlank()) {
      throw new IllegalArgumentException(
              "API Key public ID must not be blank"
      );
    }
  }
}