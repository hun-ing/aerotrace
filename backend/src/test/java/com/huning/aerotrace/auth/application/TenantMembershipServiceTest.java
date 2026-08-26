package com.huning.aerotrace.auth.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantMembershipServiceTest {

  private static final UUID TENANT_ID = UUID.randomUUID();
  private static final UUID FIRST_USER_ID = UUID.randomUUID();
  private static final UUID SECOND_USER_ID = UUID.randomUUID();
  private static final Instant CREATED_AT =
          Instant.parse("2026-08-26T00:00:00Z");

  @Test
  void refusesToDemoteOrRevokeTheLastActiveOwner() {
    FakeMembershipStore store =
            new FakeMembershipStore(
                    List.of(
                            activeMembership(
                                    FIRST_USER_ID,
                                    TenantRole.OWNER
                            )
                    )
            );

    TenantMembershipService service =
            new TenantMembershipService(store);

    assertThrows(
            IllegalStateException.class,
            () -> service.changeRole(
                    TENANT_ID,
                    FIRST_USER_ID,
                    TenantRole.ADMIN,
                    CREATED_AT.plusSeconds(60)
            )
    );

    assertThrows(
            IllegalStateException.class,
            () -> service.revoke(
                    TENANT_ID,
                    FIRST_USER_ID,
                    CREATED_AT.plusSeconds(60)
            )
    );

    assertEquals(0, store.updateRoleCalls);
    assertEquals(0, store.revokeCalls);
  }

  @Test
  void allowsOwnershipTransferBeforeDemotion() {
    FakeMembershipStore store =
            new FakeMembershipStore(
                    List.of(
                            activeMembership(
                                    FIRST_USER_ID,
                                    TenantRole.OWNER
                            ),
                            activeMembership(
                                    SECOND_USER_ID,
                                    TenantRole.ADMIN
                            )
                    )
            );

    TenantMembershipService service =
            new TenantMembershipService(store);

    TenantMembershipService.MembershipChange promotion =
            service.changeRole(
                    TENANT_ID,
                    SECOND_USER_ID,
                    TenantRole.OWNER,
                    CREATED_AT.plusSeconds(60)
            );

    TenantMembershipService.MembershipChange demotion =
            service.changeRole(
                    TENANT_ID,
                    FIRST_USER_ID,
                    TenantRole.ADMIN,
                    CREATED_AT.plusSeconds(120)
            );

    assertEquals(2, promotion.activeOwnerCountAfter());
    assertEquals(1, demotion.activeOwnerCountAfter());
    assertEquals(2, store.updateRoleCalls);
  }

  private static TenantMembershipStore.StoredTenantMembership
  activeMembership(
          UUID userId,
          TenantRole role
  ) {
    return new TenantMembershipStore.StoredTenantMembership(
            TENANT_ID,
            userId,
            role,
            TenantMembershipStore.MembershipStatus.ACTIVE,
            CREATED_AT,
            CREATED_AT,
            null
    );
  }

  private static final class FakeMembershipStore
          implements TenantMembershipStore {

    private final List<StoredTenantMembership> memberships;
    private int updateRoleCalls;
    private int revokeCalls;

    private FakeMembershipStore(
            List<StoredTenantMembership> memberships
    ) {
      this.memberships = new ArrayList<>(memberships);
    }

    @Override
    public void lockTenant(UUID tenantId) {
    }

    @Override
    public List<StoredTenantMembership> findByTenantForUpdate(
            UUID tenantId
    ) {
      return List.copyOf(memberships);
    }

    @Override
    public int updateRole(
            UUID tenantId,
            UUID userId,
            TenantRole role,
            Instant updatedAt
    ) {
      updateRoleCalls++;

      for (int index = 0; index < memberships.size(); index++) {
        StoredTenantMembership membership = memberships.get(index);

        if (membership.userId().equals(userId)) {
          memberships.set(
                  index,
                  new StoredTenantMembership(
                          membership.tenantId(),
                          membership.userId(),
                          role,
                          membership.status(),
                          membership.createdAt(),
                          updatedAt,
                          membership.revokedAt()
                  )
          );
          return 1;
        }
      }

      return 0;
    }

    @Override
    public int revoke(
            UUID tenantId,
            UUID userId,
            Instant revokedAt
    ) {
      revokeCalls++;
      return 1;
    }
  }
}
