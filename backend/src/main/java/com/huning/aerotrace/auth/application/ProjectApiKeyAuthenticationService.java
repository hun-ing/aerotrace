package com.huning.aerotrace.auth.application;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Service
public class ProjectApiKeyAuthenticationService {

  private static final byte[] UNKNOWN_KEY_DUMMY_HASH =
          new byte[32];

  private final ProjectApiKeyTokenService tokenService;
  private final ProjectApiKeyCredentialStore credentialStore;

  public ProjectApiKeyAuthenticationService(
          ProjectApiKeyTokenService tokenService,
          ProjectApiKeyCredentialStore credentialStore
  ) {
    this.tokenService = tokenService;
    this.credentialStore = credentialStore;
  }

  public Optional<AuthenticatedProject> authenticate(
          String rawKey
  ) {
    return authenticate(
            rawKey,
            Instant.now()
    );
  }

  Optional<AuthenticatedProject> authenticate(
          String rawKey,
          Instant authenticatedAt
  ) {
    Objects.requireNonNull(
            authenticatedAt,
            "Authentication time must not be null"
    );

    Optional<ParsedProjectApiKey> parsedOptional =
            tokenService.parse(rawKey);

    if (parsedOptional.isEmpty()) {
      return Optional.empty();
    }

    ParsedProjectApiKey parsed =
            parsedOptional.orElseThrow();

    Optional<ProjectApiKeyCredentialStore.StoredProjectApiKey>
            storedOptional =
            credentialStore.findByKeyId(
                    parsed.keyId()
            );

    byte[] expectedSecretHash =
            storedOptional
                    .map(
                            ProjectApiKeyCredentialStore
                                    .StoredProjectApiKey
                                    ::secretHash
                    )
                    .orElse(
                            UNKNOWN_KEY_DUMMY_HASH
                    );

    /*
     * 존재하지 않는 keyId도 더미 해시와 비교한다.
     * 단, 이 처리만으로 전체 인증 시간이 완전히
     * 일정해진다고 보장할 수는 없다.
     */
    boolean secretMatches =
            tokenService.matchesSecret(
                    parsed.secret(),
                    expectedSecretHash
            );

    if (
            storedOptional.isEmpty()
                    || !secretMatches
    ) {
      return Optional.empty();
    }

    ProjectApiKeyCredentialStore.StoredProjectApiKey
            stored =
            storedOptional.orElseThrow();

    if (stored.revokedAt() != null) {
      return Optional.empty();
    }

    if (
            stored.expiresAt() != null
                    && !stored.expiresAt()
                    .isAfter(authenticatedAt)
    ) {
      return Optional.empty();
    }

    return Optional.of(
            new AuthenticatedProject(
                    stored.id(),
                    stored.tenantId(),
                    stored.projectId(),
                    stored.keyId()
            )
    );
  }
}