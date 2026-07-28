package com.huning.aerotrace.auth.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface ProjectApiKeyCredentialStore {

  Optional<StoredProjectApiKey> findByKeyId(
          String keyId
  );

  record StoredProjectApiKey(
          UUID id,
          UUID tenantId,
          UUID projectId,
          String keyId,
          byte[] secretHash,
          Instant expiresAt,
          Instant revokedAt
  ) {

    private static final int SHA_256_BYTES = 32;

    public StoredProjectApiKey {
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

      secretHash = secretHash.clone();
    }

    @Override
    public byte[] secretHash() {
      return secretHash.clone();
    }

    @Override
    public String toString() {
      return "StoredProjectApiKey["
              + "id="
              + id
              + ", tenantId="
              + tenantId
              + ", projectId="
              + projectId
              + ", keyId="
              + keyId
              + ", secretHash=<redacted>"
              + ", expiresAt="
              + expiresAt
              + ", revokedAt="
              + revokedAt
              + "]";
    }
  }
}