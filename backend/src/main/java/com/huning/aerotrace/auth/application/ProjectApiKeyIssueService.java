package com.huning.aerotrace.auth.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProjectApiKeyIssueService {

  private static final int MAX_NAME_LENGTH = 100;

  private final ProjectApiKeyTokenService tokenService;
  private final ProjectApiKeyStore apiKeyStore;

  public ProjectApiKeyIssueService(
          ProjectApiKeyTokenService tokenService,
          ProjectApiKeyStore apiKeyStore
  ) {
    this.tokenService = tokenService;
    this.apiKeyStore = apiKeyStore;
  }

  @Transactional
  public IssuedProjectApiKey issue(
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

    Instant createdAt =
            Instant.now();

    if (
            expiresAt != null
                    && !expiresAt.isAfter(createdAt)
    ) {
      throw new IllegalArgumentException(
              "API Key expiration time must be "
                      + "after the current time"
      );
    }

    GeneratedProjectApiKey generated =
            tokenService.generate();

    UUID id =
            UUID.randomUUID();

    apiKeyStore.save(
            new ProjectApiKeyStore.NewProjectApiKey(
                    id,
                    tenantId,
                    projectId,
                    normalizedName,
                    generated.keyId(),
                    generated.secretHash(),
                    createdAt,
                    expiresAt
            )
    );

    /*
     * DB 저장이 성공한 뒤에만 원문 Key를 반환한다.
     * 트랜잭션이 실패하면 이 메서드도 예외로 종료된다.
     */
    return new IssuedProjectApiKey(
            id,
            tenantId,
            projectId,
            normalizedName,
            generated.keyId(),
            generated.rawKey(),
            createdAt,
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

    String normalized =
            name.trim();

    if (normalized.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
              "API Key name must not exceed "
                      + MAX_NAME_LENGTH
                      + " characters"
      );
    }

    return normalized;
  }
}