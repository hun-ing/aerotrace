package com.huning.aerotrace.auth.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProjectApiKeyLifecycleIssueService {

  private final ProjectApiKeyLifecycleStore lifecycleStore;
  private final ProjectApiKeyIssueService issueService;

  public ProjectApiKeyLifecycleIssueService(
          ProjectApiKeyLifecycleStore lifecycleStore,
          ProjectApiKeyIssueService issueService
  ) {
    this.lifecycleStore = lifecycleStore;
    this.issueService = issueService;
  }

  @Transactional
  public IssuedProjectApiKey issueForExistingProject(
          UUID tenantId,
          UUID projectId,
          String name,
          Instant expiresAt
  ) {
    Objects.requireNonNull(
            tenantId,
            "Tenant ID must not be null"
    );

    Objects.requireNonNull(
            projectId,
            "Project ID must not be null"
    );

    String normalizedName =
            normalizeName(name);

    Instant evaluatedAt =
            Instant.now();

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

    boolean duplicateActiveName =
            storedKeys.stream()
                    .anyMatch(
                            key -> key.name().equals(normalizedName)
                                    && isActive(
                                    key,
                                    evaluatedAt
                            )
                    );

    if (duplicateActiveName) {
      throw new IllegalStateException(
              "An active API Key already exists with "
                      + "the requested name"
      );
    }

    return issueService.issue(
            tenantId,
            projectId,
            normalizedName,
            expiresAt
    );
  }

  private String normalizeName(
          String name
  ) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(
              "API Key name must not be blank"
      );
    }

    return name.trim();
  }

  private boolean isActive(
          ProjectApiKeyLifecycleStore.StoredProjectApiKeyMetadata key,
          Instant evaluatedAt
  ) {
    return key.revokedAt() == null
            && (
            key.expiresAt() == null
                    || key.expiresAt().isAfter(evaluatedAt)
    );
  }
}
