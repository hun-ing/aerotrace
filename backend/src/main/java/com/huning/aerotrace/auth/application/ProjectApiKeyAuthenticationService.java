package com.huning.aerotrace.auth.application;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import static com.huning.aerotrace.auth.application
        .ProjectApiKeyAuthenticationMetrics
        .AuthenticationResult;

import static com.huning.aerotrace.auth.application
        .ProjectApiKeyAuthenticationMetrics
        .LookupResult;

@Service
public class ProjectApiKeyAuthenticationService {

  private static final byte[] UNKNOWN_KEY_DUMMY_HASH =
          new byte[32];

  private final ProjectApiKeyTokenService tokenService;

  private final ProjectApiKeyCredentialStore
          credentialStore;

  private final ProjectApiKeyAuthenticationMetrics
          authenticationMetrics;

  public ProjectApiKeyAuthenticationService(
          ProjectApiKeyTokenService tokenService,
          ProjectApiKeyCredentialStore credentialStore,
          ProjectApiKeyAuthenticationMetrics
                  authenticationMetrics
  ) {
    this.tokenService = tokenService;
    this.credentialStore = credentialStore;
    this.authenticationMetrics =
            authenticationMetrics;
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
      authenticationMetrics.recordAuthentication(
              AuthenticationResult.MALFORMED_KEY
      );

      return Optional.empty();
    }

    ParsedProjectApiKey parsed =
            parsedOptional.orElseThrow();

    long lookupStartedAt =
            System.nanoTime();

    Optional<ProjectApiKeyCredentialStore.StoredProjectApiKey>
            storedOptional;

    try {
      storedOptional =
              credentialStore.findByKeyId(
                      parsed.keyId()
              );
    } catch (RuntimeException exception) {
      long elapsedNanoseconds =
              System.nanoTime()
                      - lookupStartedAt;

      authenticationMetrics.recordLookup(
              elapsedNanoseconds,
              LookupResult.ERROR
      );

      authenticationMetrics.recordAuthentication(
              AuthenticationResult.LOOKUP_ERROR
      );

      throw exception;
    }

    long elapsedNanoseconds =
            System.nanoTime()
                    - lookupStartedAt;

    authenticationMetrics.recordLookup(
            elapsedNanoseconds,
            storedOptional.isPresent()
                    ? LookupResult.FOUND
                    : LookupResult.NOT_FOUND
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

    boolean secretMatches =
            tokenService.matchesSecret(
                    parsed.secret(),
                    expectedSecretHash
            );

    /*
     * 존재하지 않는 Key도 위에서 더미 해시와
     * 비교한 뒤 동일한 인증 실패를 반환한다.
     */
    if (storedOptional.isEmpty()) {
      authenticationMetrics.recordAuthentication(
              AuthenticationResult.UNKNOWN_KEY
      );

      return Optional.empty();
    }

    if (!secretMatches) {
      authenticationMetrics.recordAuthentication(
              AuthenticationResult.SECRET_MISMATCH
      );

      return Optional.empty();
    }

    ProjectApiKeyCredentialStore.StoredProjectApiKey
            stored =
            storedOptional.orElseThrow();

    if (stored.revokedAt() != null) {
      authenticationMetrics.recordAuthentication(
              AuthenticationResult.REVOKED
      );

      return Optional.empty();
    }

    if (
            stored.expiresAt() != null
                    && !stored.expiresAt()
                    .isAfter(authenticatedAt)
    ) {
      authenticationMetrics.recordAuthentication(
              AuthenticationResult.EXPIRED
      );

      return Optional.empty();
    }

    authenticationMetrics.recordAuthentication(
            AuthenticationResult.SUCCESS
    );

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