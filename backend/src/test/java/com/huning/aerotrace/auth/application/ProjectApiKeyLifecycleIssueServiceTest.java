package com.huning.aerotrace.auth.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectApiKeyLifecycleIssueServiceTest {

  private static final UUID TENANT_ID =
          UUID.fromString(
                  "11111111-1111-1111-1111-111111111111"
          );

  private static final UUID PROJECT_ID =
          UUID.fromString(
                  "22222222-2222-2222-2222-222222222222"
          );

  @Test
  void refusesDuplicateActiveNameAfterLockingProject() {
    Instant now =
            Instant.now();

    FakeLifecycleStore lifecycleStore =
            new FakeLifecycleStore(
                    List.of(
                            storedKey(
                                    "runtime-next",
                                    now.minusSeconds(60),
                                    now.plusSeconds(60),
                                    null
                            )
                    )
            );

    CapturingApiKeyStore apiKeyStore =
            new CapturingApiKeyStore();

    ProjectApiKeyLifecycleIssueService service =
            service(
                    lifecycleStore,
                    apiKeyStore
            );

    assertThrows(
            IllegalStateException.class,
            () -> service.issueForExistingProject(
                    TENANT_ID,
                    PROJECT_ID,
                    "runtime-next",
                    now.plusSeconds(3600)
            )
    );

    assertEquals(
            1,
            lifecycleStore.lockCalls
    );

    assertTrue(
            apiKeyStore.saved.isEmpty()
    );
  }

  @Test
  void issuesReplacementWhenSameNameIsExpired() {
    Instant now =
            Instant.now();

    FakeLifecycleStore lifecycleStore =
            new FakeLifecycleStore(
                    List.of(
                            storedKey(
                                    "runtime-next",
                                    now.minusSeconds(120),
                                    now.minusSeconds(60),
                                    null
                            )
                    )
            );

    CapturingApiKeyStore apiKeyStore =
            new CapturingApiKeyStore();

    IssuedProjectApiKey issued =
            service(
                    lifecycleStore,
                    apiKeyStore
            ).issueForExistingProject(
                    TENANT_ID,
                    PROJECT_ID,
                    " runtime-next ",
                    now.plusSeconds(3600)
            );

    assertEquals(
            "runtime-next",
            issued.name()
    );

    assertTrue(
            issued.rawKey().matches(
                    "^atr_[A-Za-z0-9_-]{16}\\."
                            + "[A-Za-z0-9_-]{43}$"
            )
    );

    assertEquals(
            1,
            lifecycleStore.lockCalls
    );

    assertEquals(
            1,
            apiKeyStore.saved.size()
    );
  }

  @Test
  void issuesReplacementWhenSameNameWasRevoked() {
    Instant now =
            Instant.now();

    FakeLifecycleStore lifecycleStore =
            new FakeLifecycleStore(
                    List.of(
                            storedKey(
                                    "runtime-next",
                                    now.minusSeconds(120),
                                    now.plusSeconds(3600),
                                    now.minusSeconds(60)
                            )
                    )
            );

    CapturingApiKeyStore apiKeyStore =
            new CapturingApiKeyStore();

    service(
            lifecycleStore,
            apiKeyStore
    ).issueForExistingProject(
            TENANT_ID,
            PROJECT_ID,
            "runtime-next",
            now.plusSeconds(3600)
    );

    assertEquals(
            1,
            apiKeyStore.saved.size()
    );
  }

  private static ProjectApiKeyLifecycleIssueService service(
          FakeLifecycleStore lifecycleStore,
          CapturingApiKeyStore apiKeyStore
  ) {
    ProjectApiKeyIssueService issueService =
            new ProjectApiKeyIssueService(
                    new ProjectApiKeyTokenService(),
                    apiKeyStore
            );

    return new ProjectApiKeyLifecycleIssueService(
            lifecycleStore,
            issueService
    );
  }

  private static ProjectApiKeyLifecycleStore.StoredProjectApiKeyMetadata
  storedKey(
          String name,
          Instant createdAt,
          Instant expiresAt,
          Instant revokedAt
  ) {
    return new ProjectApiKeyLifecycleStore.StoredProjectApiKeyMetadata(
            UUID.randomUUID(),
            TENANT_ID,
            PROJECT_ID,
            name,
            createdAt,
            expiresAt,
            revokedAt
    );
  }

  private static final class FakeLifecycleStore
          implements ProjectApiKeyLifecycleStore {

    private final List<StoredProjectApiKeyMetadata> keys;
    private int lockCalls;

    private FakeLifecycleStore(
            List<StoredProjectApiKeyMetadata> keys
    ) {
      this.keys = keys;
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
      throw new UnsupportedOperationException();
    }
  }

  private static final class CapturingApiKeyStore
          implements ProjectApiKeyStore {

    private final List<NewProjectApiKey> saved =
            new ArrayList<>();

    @Override
    public void save(
            NewProjectApiKey apiKey
    ) {
      saved.add(apiKey);
    }
  }
}
