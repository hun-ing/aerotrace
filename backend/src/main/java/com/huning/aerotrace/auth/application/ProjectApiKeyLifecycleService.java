package com.huning.aerotrace.auth.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProjectApiKeyLifecycleService {

  private final ProjectApiKeyLifecycleStore lifecycleStore;

  public ProjectApiKeyLifecycleService(
          ProjectApiKeyLifecycleStore lifecycleStore
  ) {
    this.lifecycleStore = lifecycleStore;
  }

  @Transactional(readOnly = true)
  public ProjectApiKeyInventory list(
          UUID tenantId,
          UUID projectId
  ) {
    return list(
            tenantId,
            projectId,
            Instant.now()
    );
  }

  ProjectApiKeyInventory list(
          UUID tenantId,
          UUID projectId,
          Instant listedAt
  ) {
    validateProject(
            tenantId,
            projectId
    );

    Objects.requireNonNull(
            listedAt,
            "API Key listing time must not be null"
    );

    List<ProjectApiKeyMetadata> keys =
            lifecycleStore.findByProject(
                            tenantId,
                            projectId
                    )
                    .stream()
                    .map(
                            stored -> toMetadata(
                                    stored,
                                    listedAt
                            )
                    )
                    .toList();

    long activeKeyCount =
            keys.stream()
                    .filter(
                            key -> key.status()
                                    == ProjectApiKeyStatus.ACTIVE
                    )
                    .count();

    return new ProjectApiKeyInventory(
            listedAt,
            keys,
            activeKeyCount
    );
  }

  @Transactional
  public ProjectApiKeyRevocation revoke(
          UUID tenantId,
          UUID projectId,
          UUID apiKeyId,
          String expectedName,
          boolean allowLastActive
  ) {
    return revoke(
            tenantId,
            projectId,
            apiKeyId,
            expectedName,
            allowLastActive,
            Instant.now()
    );
  }

  ProjectApiKeyRevocation revoke(
          UUID tenantId,
          UUID projectId,
          UUID apiKeyId,
          String expectedName,
          boolean allowLastActive,
          Instant revokedAt
  ) {
    validateProject(
            tenantId,
            projectId
    );

    Objects.requireNonNull(
            apiKeyId,
            "API Key ID must not be null"
    );

    Objects.requireNonNull(
            revokedAt,
            "API Key revocation time must not be null"
    );

    String normalizedExpectedName =
            normalizeExpectedName(expectedName);

    lifecycleStore.lockProject(
            tenantId,
            projectId
    );

    List<ProjectApiKeyLifecycleStore.StoredProjectApiKeyMetadata>
            storedKeys =
            lifecycleStore.findByProjectForUpdate(
                    tenantId,
                    projectId
            );

    ProjectApiKeyLifecycleStore.StoredProjectApiKeyMetadata target =
            storedKeys.stream()
                    .filter(
                            key -> key.id().equals(apiKeyId)
                    )
                    .findFirst()
                    .orElseThrow(
                            () -> new IllegalArgumentException(
                                    "API Key was not found in the "
                                            + "requested project"
                            )
                    );

    if (!target.name().equals(normalizedExpectedName)) {
      throw new IllegalArgumentException(
              "API Key name confirmation does not match"
      );
    }

    if (target.revokedAt() != null) {
      return new ProjectApiKeyRevocation(
              ProjectApiKeyRevocationResult.ALREADY_REVOKED,
              toMetadata(
                      target,
                      revokedAt
              ),
              activeKeyCount(
                      storedKeys,
                      revokedAt
              )
      );
    }

    ProjectApiKeyStatus targetStatus =
            statusAt(
                    target,
                    revokedAt
            );

    long activeKeyCountBefore =
            activeKeyCount(
                    storedKeys,
                    revokedAt
            );

    if (
            targetStatus == ProjectApiKeyStatus.ACTIVE
                    && activeKeyCountBefore <= 1
                    && !allowLastActive
    ) {
      throw new IllegalStateException(
              "Refusing to revoke the last active API Key. "
                      + "Issue and verify a replacement first, "
                      + "or explicitly allow last-active revocation."
      );
    }

    int updatedRows =
            lifecycleStore.revoke(
                    tenantId,
                    projectId,
                    apiKeyId,
                    revokedAt
            );

    if (updatedRows != 1) {
      throw new IllegalStateException(
              "API Key revocation did not update exactly one row"
      );
    }

    ProjectApiKeyLifecycleStore.StoredProjectApiKeyMetadata revoked =
            new ProjectApiKeyLifecycleStore.StoredProjectApiKeyMetadata(
                    target.id(),
                    target.tenantId(),
                    target.projectId(),
                    target.name(),
                    target.createdAt(),
                    target.expiresAt(),
                    revokedAt
            );

    long activeKeyCountAfter =
            targetStatus == ProjectApiKeyStatus.ACTIVE
                    ? activeKeyCountBefore - 1
                    : activeKeyCountBefore;

    return new ProjectApiKeyRevocation(
            ProjectApiKeyRevocationResult.REVOKED,
            toMetadata(
                    revoked,
                    revokedAt
            ),
            activeKeyCountAfter
    );
  }

  private void validateProject(
          UUID tenantId,
          UUID projectId
  ) {
    Objects.requireNonNull(
            tenantId,
            "Tenant ID must not be null"
    );

    Objects.requireNonNull(
            projectId,
            "Project ID must not be null"
    );
  }

  private String normalizeExpectedName(
          String expectedName
  ) {
    if (expectedName == null || expectedName.isBlank()) {
      throw new IllegalArgumentException(
              "Expected API Key name must not be blank"
      );
    }

    return expectedName.trim();
  }

  private long activeKeyCount(
          List<ProjectApiKeyLifecycleStore.StoredProjectApiKeyMetadata>
                  storedKeys,
          Instant evaluatedAt
  ) {
    return storedKeys.stream()
            .filter(
                    key -> statusAt(
                            key,
                            evaluatedAt
                    ) == ProjectApiKeyStatus.ACTIVE
            )
            .count();
  }

  private ProjectApiKeyMetadata toMetadata(
          ProjectApiKeyLifecycleStore.StoredProjectApiKeyMetadata
                  stored,
          Instant evaluatedAt
  ) {
    return new ProjectApiKeyMetadata(
            stored.id(),
            stored.name(),
            stored.createdAt(),
            stored.expiresAt(),
            stored.revokedAt(),
            statusAt(
                    stored,
                    evaluatedAt
            )
    );
  }

  private ProjectApiKeyStatus statusAt(
          ProjectApiKeyLifecycleStore.StoredProjectApiKeyMetadata
                  stored,
          Instant evaluatedAt
  ) {
    if (stored.revokedAt() != null) {
      return ProjectApiKeyStatus.REVOKED;
    }

    if (
            stored.expiresAt() != null
                    && !stored.expiresAt().isAfter(evaluatedAt)
    ) {
      return ProjectApiKeyStatus.EXPIRED;
    }

    return ProjectApiKeyStatus.ACTIVE;
  }

  public enum ProjectApiKeyStatus {
    ACTIVE,
    EXPIRED,
    REVOKED
  }

  public enum ProjectApiKeyRevocationResult {
    REVOKED,
    ALREADY_REVOKED
  }

  public record ProjectApiKeyMetadata(
          UUID id,
          String name,
          Instant createdAt,
          Instant expiresAt,
          Instant revokedAt,
          ProjectApiKeyStatus status
  ) {
  }

  public record ProjectApiKeyInventory(
          Instant listedAt,
          List<ProjectApiKeyMetadata> keys,
          long activeKeyCount
  ) {

    public ProjectApiKeyInventory {
      keys = List.copyOf(keys);
    }
  }

  public record ProjectApiKeyRevocation(
          ProjectApiKeyRevocationResult result,
          ProjectApiKeyMetadata apiKey,
          long activeKeyCountAfter
  ) {
  }
}
