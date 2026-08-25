package com.huning.aerotrace.auth.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectApiKeyLifecycleServiceTest {

  private static final Instant NOW =
          Instant.parse(
                  "2026-08-25T00:00:00Z"
          );

  private static final UUID TENANT_ID =
          UUID.fromString(
                  "11111111-1111-1111-1111-111111111111"
          );

  private static final UUID PROJECT_ID =
          UUID.fromString(
                  "22222222-2222-2222-2222-222222222222"
          );

  private static final UUID FIRST_KEY_ID =
          UUID.fromString(
                  "33333333-3333-3333-3333-333333333333"
          );

  private static final UUID SECOND_KEY_ID =
          UUID.fromString(
                  "44444444-4444-4444-4444-444444444444"
          );

  @Test
  void listsActiveExpiredAndRevokedMetadata() {
    FakeLifecycleStore store =
            new FakeLifecycleStore(
                    List.of(
                            storedKey(
                                    FIRST_KEY_ID,
                                    "active",
                                    NOW.plusSeconds(60),
                                    null
                            ),
                            storedKey(
                                    SECOND_KEY_ID,
                                    "expired",
                                    NOW,
                                    null
                            ),
                            storedKey(
                                    UUID.randomUUID(),
                                    "revoked",
                                    NOW.plusSeconds(60),
                                    NOW.minusSeconds(1)
                            )
                    )
            );

    ProjectApiKeyLifecycleService.ProjectApiKeyInventory inventory =
            new ProjectApiKeyLifecycleService(
                    store
            ).list(
                    TENANT_ID,
                    PROJECT_ID,
                    NOW
            );

    assertEquals(
            3,
            inventory.keys().size()
    );

    assertEquals(
            1,
            inventory.activeKeyCount()
    );

    assertEquals(
            List.of(
                    ProjectApiKeyLifecycleService.ProjectApiKeyStatus.ACTIVE,
                    ProjectApiKeyLifecycleService.ProjectApiKeyStatus.EXPIRED,
                    ProjectApiKeyLifecycleService.ProjectApiKeyStatus.REVOKED
            ),
            inventory.keys()
                    .stream()
                    .map(
                            ProjectApiKeyLifecycleService.ProjectApiKeyMetadata::status
                    )
                    .toList()
    );
  }

  @Test
  void revokesActiveKeyWhenReplacementIsActive() {
    FakeLifecycleStore store =
            storeWithTwoActiveKeys();

    ProjectApiKeyLifecycleService.ProjectApiKeyRevocation result =
            new ProjectApiKeyLifecycleService(
                    store
            ).revoke(
                    TENANT_ID,
                    PROJECT_ID,
                    FIRST_KEY_ID,
                    "old-runtime",
                    false,
                    NOW
            );

    assertEquals(
            ProjectApiKeyLifecycleService.ProjectApiKeyRevocationResult.REVOKED,
            result.result()
    );

    assertEquals(
            ProjectApiKeyLifecycleService.ProjectApiKeyStatus.REVOKED,
            result.apiKey().status()
    );

    assertEquals(
            1,
            result.activeKeyCountAfter()
    );

    assertEquals(
            1,
            store.revokeCalls
    );

    assertEquals(
            1,
            store.lockCalls
    );
  }

  @Test
  void refusesToRevokeLastActiveKeyByDefault() {
    FakeLifecycleStore store =
            new FakeLifecycleStore(
                    List.of(
                            storedKey(
                                    FIRST_KEY_ID,
                                    "only-runtime",
                                    NOW.plusSeconds(60),
                                    null
                            )
                    )
            );

    IllegalStateException exception =
            assertThrows(
                    IllegalStateException.class,
                    () -> new ProjectApiKeyLifecycleService(
                            store
                    ).revoke(
                            TENANT_ID,
                            PROJECT_ID,
                            FIRST_KEY_ID,
                            "only-runtime",
                            false,
                            NOW
                    )
            );

    assertEquals(
            "Refusing to revoke the last active API Key. "
                    + "Issue and verify a replacement first, "
                    + "or explicitly allow last-active revocation.",
            exception.getMessage()
    );

    assertEquals(
            0,
            store.revokeCalls
    );
  }

  @Test
  void allowsExplicitLastActiveRevocation() {
    FakeLifecycleStore store =
            new FakeLifecycleStore(
                    List.of(
                            storedKey(
                                    FIRST_KEY_ID,
                                    "compromised",
                                    NOW.plusSeconds(60),
                                    null
                            )
                    )
            );

    ProjectApiKeyLifecycleService.ProjectApiKeyRevocation result =
            new ProjectApiKeyLifecycleService(
                    store
            ).revoke(
                    TENANT_ID,
                    PROJECT_ID,
                    FIRST_KEY_ID,
                    "compromised",
                    true,
                    NOW
            );

    assertEquals(
            0,
            result.activeKeyCountAfter()
    );

    assertEquals(
            1,
            store.revokeCalls
    );
  }

  @Test
  void repeatedRevocationIsIdempotent() {
    FakeLifecycleStore store =
            new FakeLifecycleStore(
                    List.of(
                            storedKey(
                                    FIRST_KEY_ID,
                                    "old-runtime",
                                    NOW.plusSeconds(60),
                                    NOW.minusSeconds(1)
                            ),
                            storedKey(
                                    SECOND_KEY_ID,
                                    "new-runtime",
                                    NOW.plusSeconds(60),
                                    null
                            )
                    )
            );

    ProjectApiKeyLifecycleService.ProjectApiKeyRevocation result =
            new ProjectApiKeyLifecycleService(
                    store
            ).revoke(
                    TENANT_ID,
                    PROJECT_ID,
                    FIRST_KEY_ID,
                    "old-runtime",
                    false,
                    NOW
            );

    assertEquals(
            ProjectApiKeyLifecycleService.ProjectApiKeyRevocationResult.ALREADY_REVOKED,
            result.result()
    );

    assertEquals(
            1,
            result.activeKeyCountAfter()
    );

    assertEquals(
            0,
            store.revokeCalls
    );
  }

  @Test
  void requiresExpectedNameToMatch() {
    FakeLifecycleStore store =
            storeWithTwoActiveKeys();

    assertThrows(
            IllegalArgumentException.class,
            () -> new ProjectApiKeyLifecycleService(
                    store
            ).revoke(
                    TENANT_ID,
                    PROJECT_ID,
                    FIRST_KEY_ID,
                    "wrong-name",
                    false,
                    NOW
            )
    );

    assertEquals(
            0,
            store.revokeCalls
    );
  }

  @Test
  void rejectsUnknownKeyId() {
    FakeLifecycleStore store =
            storeWithTwoActiveKeys();

    assertThrows(
            IllegalArgumentException.class,
            () -> new ProjectApiKeyLifecycleService(
                    store
            ).revoke(
                    TENANT_ID,
                    PROJECT_ID,
                    UUID.randomUUID(),
                    "old-runtime",
                    false,
                    NOW
            )
    );

    assertEquals(
            0,
            store.revokeCalls
    );
  }

  @Test
  void expiredKeyCanBeRevokedWithoutActiveReplacement() {
    FakeLifecycleStore store =
            new FakeLifecycleStore(
                    List.of(
                            storedKey(
                                    FIRST_KEY_ID,
                                    "expired",
                                    NOW,
                                    null
                            )
                    )
            );

    ProjectApiKeyLifecycleService.ProjectApiKeyRevocation result =
            new ProjectApiKeyLifecycleService(
                    store
            ).revoke(
                    TENANT_ID,
                    PROJECT_ID,
                    FIRST_KEY_ID,
                    "expired",
                    false,
                    NOW
            );

    assertEquals(
            ProjectApiKeyLifecycleService.ProjectApiKeyRevocationResult.REVOKED,
            result.result()
    );

    assertEquals(
            0,
            result.activeKeyCountAfter()
    );
  }

  private static FakeLifecycleStore storeWithTwoActiveKeys() {
    return new FakeLifecycleStore(
            List.of(
                    storedKey(
                            FIRST_KEY_ID,
                            "old-runtime",
                            NOW.plusSeconds(60),
                            null
                    ),
                    storedKey(
                            SECOND_KEY_ID,
                            "new-runtime",
                            NOW.plusSeconds(60),
                            null
                    )
            )
    );
  }

  private static ProjectApiKeyLifecycleStore.StoredProjectApiKeyMetadata
  storedKey(
          UUID id,
          String name,
          Instant expiresAt,
          Instant revokedAt
  ) {
    return new ProjectApiKeyLifecycleStore.StoredProjectApiKeyMetadata(
            id,
            TENANT_ID,
            PROJECT_ID,
            name,
            NOW.minusSeconds(60),
            expiresAt,
            revokedAt
    );
  }

  private static final class FakeLifecycleStore
          implements ProjectApiKeyLifecycleStore {

    private final List<StoredProjectApiKeyMetadata> keys;
    private int revokeCalls;
    private int lockCalls;

    private FakeLifecycleStore(
            List<StoredProjectApiKeyMetadata> keys
    ) {
      this.keys = new ArrayList<>(keys);
    }

    @Override
    public void lockProject(
            UUID tenantId,
            UUID projectId
    ) {
      lockCalls++;
    }

    @Override
    public List<StoredProjectApiKeyMetadata> findByProject(
            UUID tenantId,
            UUID projectId
    ) {
      return List.copyOf(keys);
    }

    @Override
    public List<StoredProjectApiKeyMetadata> findByProjectForUpdate(
            UUID tenantId,
            UUID projectId
    ) {
      if (lockCalls == 0) {
        throw new IllegalStateException(
                "Project must be locked first"
        );
      }

      return List.copyOf(keys);
    }

    @Override
    public int revoke(
            UUID tenantId,
            UUID projectId,
            UUID apiKeyId,
            Instant revokedAt
    ) {
      for (int index = 0; index < keys.size(); index++) {
        StoredProjectApiKeyMetadata key =
                keys.get(index);

        if (
                key.id().equals(apiKeyId)
                        && key.revokedAt() == null
        ) {
          keys.set(
                  index,
                  new StoredProjectApiKeyMetadata(
                          key.id(),
                          key.tenantId(),
                          key.projectId(),
                          key.name(),
                          key.createdAt(),
                          key.expiresAt(),
                          revokedAt
                  )
          );

          revokeCalls++;
          return 1;
        }
      }

      return 0;
    }
  }
}
