package com.huning.aerotrace.auth.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface ProjectApiKeyLifecycleStore {

  void lockProject(
          UUID tenantId,
          UUID projectId
  );

  List<StoredProjectApiKeyMetadata> findByProject(
          UUID tenantId,
          UUID projectId
  );

  List<StoredProjectApiKeyMetadata> findByProjectForUpdate(
          UUID tenantId,
          UUID projectId
  );

  int revoke(
          UUID tenantId,
          UUID projectId,
          UUID apiKeyId,
          Instant revokedAt
  );

  record StoredProjectApiKeyMetadata(
          UUID id,
          UUID tenantId,
          UUID projectId,
          String name,
          Instant createdAt,
          Instant expiresAt,
          Instant revokedAt
  ) {

    public StoredProjectApiKeyMetadata {
      Objects.requireNonNull(
              id,
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

      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException(
                "API Key name must not be blank"
        );
      }

      Objects.requireNonNull(
              createdAt,
              "API Key creation time must not be null"
      );

      if (
              expiresAt != null
                      && !expiresAt.isAfter(createdAt)
      ) {
        throw new IllegalArgumentException(
                "API Key expiration time must be "
                        + "after its creation time"
        );
      }

      if (
              revokedAt != null
                      && revokedAt.isBefore(createdAt)
      ) {
        throw new IllegalArgumentException(
                "API Key revocation time must not be "
                        + "before its creation time"
        );
      }

      name = name.trim();
    }
  }
}
