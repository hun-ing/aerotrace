package com.huning.aerotrace.auth.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectApiKeyAuthenticationServiceTest {

  private static final Instant NOW =
          Instant.parse(
                  "2026-07-28T00:00:00Z"
          );

  private static final UUID API_KEY_ID =
          UUID.fromString(
                  "33333333-3333-3333-3333-333333333333"
          );

  private static final UUID TENANT_ID =
          UUID.fromString(
                  "11111111-1111-1111-1111-111111111111"
          );

  private static final UUID PROJECT_ID =
          UUID.fromString(
                  "22222222-2222-2222-2222-222222222222"
          );

  private final ProjectApiKeyTokenService tokenService =
          new ProjectApiKeyTokenService();

  @Test
  void authenticatesValidActiveApiKey() {
    GeneratedProjectApiKey generated =
            tokenService.generate();

    ProjectApiKeyAuthenticationService service =
            serviceWith(
                    storedKey(
                            generated,
                            NOW.plusSeconds(3600),
                            null
                    )
            );

    Optional<AuthenticatedProject> result =
            service.authenticate(
                    generated.rawKey(),
                    NOW
            );

    assertTrue(result.isPresent());

    AuthenticatedProject authenticated =
            result.orElseThrow();

    assertEquals(
            API_KEY_ID,
            authenticated.apiKeyId()
    );

    assertEquals(
            TENANT_ID,
            authenticated.tenantId()
    );

    assertEquals(
            PROJECT_ID,
            authenticated.projectId()
    );

    assertEquals(
            generated.keyId(),
            authenticated.keyId()
    );
  }

  @Test
  void rejectsUnknownKeyId() {
    ProjectApiKeyAuthenticationService service =
            serviceWith(null);

    GeneratedProjectApiKey generated =
            tokenService.generate();

    assertTrue(
            service.authenticate(
                    generated.rawKey(),
                    NOW
            ).isEmpty()
    );
  }

  @Test
  void rejectsWrongSecret() {
    GeneratedProjectApiKey storedGenerated =
            tokenService.generate();

    GeneratedProjectApiKey presentedGenerated =
            tokenService.generate();

    String presentedRawKey =
            "atr_"
                    + storedGenerated.keyId()
                    + "."
                    + tokenService.parse(
                            presentedGenerated.rawKey()
                    ).orElseThrow()
                    .secret();

    ProjectApiKeyAuthenticationService service =
            serviceWith(
                    storedKey(
                            storedGenerated,
                            NOW.plusSeconds(3600),
                            null
                    )
            );

    assertTrue(
            service.authenticate(
                    presentedRawKey,
                    NOW
            ).isEmpty()
    );
  }

  @Test
  void rejectsExpiredKey() {
    GeneratedProjectApiKey generated =
            tokenService.generate();

    ProjectApiKeyAuthenticationService service =
            serviceWith(
                    storedKey(
                            generated,
                            NOW,
                            null
                    )
            );

    assertTrue(
            service.authenticate(
                    generated.rawKey(),
                    NOW
            ).isEmpty()
    );
  }

  @Test
  void rejectsRevokedKey() {
    GeneratedProjectApiKey generated =
            tokenService.generate();

    ProjectApiKeyAuthenticationService service =
            serviceWith(
                    storedKey(
                            generated,
                            NOW.plusSeconds(3600),
                            NOW.minusSeconds(1)
                    )
            );

    assertTrue(
            service.authenticate(
                    generated.rawKey(),
                    NOW
            ).isEmpty()
    );
  }

  @Test
  void rejectsMalformedKey() {
    ProjectApiKeyAuthenticationService service =
            serviceWith(null);

    assertTrue(
            service.authenticate(
                    "invalid-api-key",
                    NOW
            ).isEmpty()
    );
  }

  private ProjectApiKeyAuthenticationService serviceWith(
          ProjectApiKeyCredentialStore.StoredProjectApiKey
                  storedApiKey
  ) {
    ProjectApiKeyCredentialStore store =
            keyId -> {
              if (
                      storedApiKey == null
                              || !storedApiKey
                              .keyId()
                              .equals(keyId)
              ) {
                return Optional.empty();
              }

              return Optional.of(storedApiKey);
            };

    return new ProjectApiKeyAuthenticationService(
            tokenService,
            store
    );
  }

  private ProjectApiKeyCredentialStore.StoredProjectApiKey
  storedKey(
          GeneratedProjectApiKey generated,
          Instant expiresAt,
          Instant revokedAt
  ) {
    return new ProjectApiKeyCredentialStore.StoredProjectApiKey(
            API_KEY_ID,
            TENANT_ID,
            PROJECT_ID,
            generated.keyId(),
            generated.secretHash(),
            expiresAt,
            revokedAt
    );
  }
}