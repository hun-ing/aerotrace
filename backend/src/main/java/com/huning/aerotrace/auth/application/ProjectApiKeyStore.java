package com.huning.aerotrace.auth.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface ProjectApiKeyStore {

  void save(NewProjectApiKey apiKey);

  record NewProjectApiKey(
          UUID id,
          UUID tenantId,
          UUID projectId,
          String name,
          String keyId,
          byte[] secretHash,
          Instant createdAt,
          Instant expiresAt
  ) {

    private static final int SHA_256_BYTES = 32;

    public NewProjectApiKey {
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

      Objects.requireNonNull(
              createdAt,
              "Created time must not be null"
      );

      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException(
                "API Key name must not be blank"
        );
      }

      if (keyId == null || keyId.isBlank()) {
        throw new IllegalArgumentException(
                "API Key public ID must not be blank"
        );
      }

      Objects.requireNonNull(
              secretHash,
              "Secret hash must not be null"
      );

      if (secretHash.length != SHA_256_BYTES) {
        throw new IllegalArgumentException(
                "Secret hash must be exactly "
                        + SHA_256_BYTES
                        + " bytes"
        );
      }

      if (
              expiresAt != null
                      && !expiresAt.isAfter(createdAt)
      ) {
        throw new IllegalArgumentException(
                "API Key expiration time must be "
                        + "after its creation time"
        );
      }

      name = name.trim();
      secretHash = secretHash.clone();
    }

    @Override
    public byte[] secretHash() {
      return secretHash.clone();
    }

    @Override
    public String toString() {
      return "NewProjectApiKey["
              + "id="
              + id
              + ", tenantId="
              + tenantId
              + ", projectId="
              + projectId
              + ", name="
              + name
              + ", keyId="
              + keyId
              + ", secretHash=<redacted>"
              + ", createdAt="
              + createdAt
              + ", expiresAt="
              + expiresAt
              + "]";
    }
  }
}