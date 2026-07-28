package com.huning.aerotrace.auth.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class IssuedProjectApiKey {

  private final UUID id;
  private final UUID tenantId;
  private final UUID projectId;
  private final String name;
  private final String keyId;
  private final String rawKey;
  private final Instant createdAt;
  private final Instant expiresAt;

  public IssuedProjectApiKey(
          UUID id,
          UUID tenantId,
          UUID projectId,
          String name,
          String keyId,
          String rawKey,
          Instant createdAt,
          Instant expiresAt
  ) {
    this.id =
            Objects.requireNonNull(
                    id,
                    "API Key ID must not be null"
            );

    this.tenantId =
            Objects.requireNonNull(
                    tenantId,
                    "Tenant ID must not be null"
            );

    this.projectId =
            Objects.requireNonNull(
                    projectId,
                    "Project ID must not be null"
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

    if (rawKey == null || rawKey.isBlank()) {
      throw new IllegalArgumentException(
              "Raw API Key must not be blank"
      );
    }

    this.createdAt =
            Objects.requireNonNull(
                    createdAt,
                    "Created time must not be null"
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

    this.name = name;
    this.keyId = keyId;
    this.rawKey = rawKey;
    this.expiresAt = expiresAt;
  }

  public UUID id() {
    return id;
  }

  public UUID tenantId() {
    return tenantId;
  }

  public UUID projectId() {
    return projectId;
  }

  public String name() {
    return name;
  }

  public String keyId() {
    return keyId;
  }

  /**
   * 발급 시 한 번만 호출자에게 전달해야 한다.
   * 로그나 DB에 저장하면 안 된다.
   */
  public String rawKey() {
    return rawKey;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  @Override
  public String toString() {
    return "IssuedProjectApiKey["
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
            + ", rawKey=<redacted>"
            + ", createdAt="
            + createdAt
            + ", expiresAt="
            + expiresAt
            + "]";
  }
}