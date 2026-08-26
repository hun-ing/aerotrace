package com.huning.aerotrace.auth.application;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MembershipPermissionServiceTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID TENANT_ID = UUID.randomUUID();
  private static final UUID PROJECT_ID = UUID.randomUUID();

  @Test
  void defaultsToNotFoundWithoutAnActiveMembership() {
    MembershipPermissionService service =
            new MembershipPermissionService(
                    new FixedAuthorizationStore(null)
            );

    AuthorizationDecision tenantDecision =
            service.authorizeTenant(
                    USER_ID,
                    TENANT_ID,
                    TenantPermission.TENANT_READ
            );

    AuthorizationDecision projectDecision =
            service.authorizeProject(
                    USER_ID,
                    TENANT_ID,
                    PROJECT_ID,
                    TenantPermission.PROJECT_TRACE_READ
            );

    assertFalse(tenantDecision.allowed());
    assertFalse(projectDecision.allowed());

    assertEquals(
            AuthorizationDecision.AuthorizationOutcome.NOT_FOUND,
            tenantDecision.outcome()
    );

    assertEquals(
            AuthorizationDecision.AuthorizationOutcome.NOT_FOUND,
            projectDecision.outcome()
    );
  }

  @Test
  void appliesTheCompleteOwnerRoleMatrix() {
    assertAllowedPermissions(
            TenantRole.OWNER,
            EnumSet.allOf(TenantPermission.class)
    );
  }

  @Test
  void appliesTheCompleteAdminRoleMatrix() {
    assertAllowedPermissions(
            TenantRole.ADMIN,
            EnumSet.of(
                    TenantPermission.TENANT_READ,
                    TenantPermission.PROJECT_TRACE_READ,
                    TenantPermission.PROJECT_MANAGE,
                    TenantPermission.API_KEY_METADATA_READ,
                    TenantPermission.API_KEY_MANAGE,
                    TenantPermission.MEMBER_MANAGE
            )
    );
  }

  @Test
  void appliesTheCompleteViewerRoleMatrix() {
    assertAllowedPermissions(
            TenantRole.VIEWER,
            EnumSet.of(
                    TenantPermission.TENANT_READ,
                    TenantPermission.PROJECT_TRACE_READ
            )
    );
  }

  @Test
  void onlyOwnerCanInviteAnotherOwner() {
    MembershipPermissionService ownerService =
            serviceFor(TenantRole.OWNER);

    MembershipPermissionService adminService =
            serviceFor(TenantRole.ADMIN);

    assertTrue(
            ownerService.authorizeInvite(
                    USER_ID,
                    TENANT_ID,
                    TenantRole.OWNER
            ).allowed()
    );

    assertEquals(
            AuthorizationDecision.AuthorizationOutcome.FORBIDDEN,
            adminService.authorizeInvite(
                    USER_ID,
                    TENANT_ID,
                    TenantRole.OWNER
            ).outcome()
    );

    assertTrue(
            adminService.authorizeInvite(
                    USER_ID,
                    TENANT_ID,
                    TenantRole.ADMIN
            ).allowed()
    );
  }

  private static void assertAllowedPermissions(
          TenantRole role,
          EnumSet<TenantPermission> allowedPermissions
  ) {
    MembershipPermissionService service = serviceFor(role);

    for (TenantPermission permission : TenantPermission.values()) {
      AuthorizationDecision decision =
              service.authorizeTenant(
                      USER_ID,
                      TENANT_ID,
                      permission
              );

      assertEquals(
              allowedPermissions.contains(permission),
              decision.allowed(),
              () -> role + " decision differed for " + permission
      );

      assertEquals(
              role,
              decision.role()
      );
    }
  }

  private static MembershipPermissionService serviceFor(
          TenantRole role
  ) {
    return new MembershipPermissionService(
            new FixedAuthorizationStore(role)
    );
  }

  private record FixedAuthorizationStore(
          TenantRole role
  ) implements MembershipAuthorizationStore {

    @Override
    public Optional<ActiveMembership> findActiveTenantMembership(
            UUID userId,
            UUID tenantId
    ) {
      return membership(userId, tenantId);
    }

    @Override
    public Optional<ActiveMembership> findActiveProjectMembership(
            UUID userId,
            UUID tenantId,
            UUID projectId
    ) {
      return membership(userId, tenantId);
    }

    private Optional<ActiveMembership> membership(
            UUID userId,
            UUID tenantId
    ) {
      return role == null
              ? Optional.empty()
              : Optional.of(
                      new ActiveMembership(
                              userId,
                              tenantId,
                              role
                      )
              );
    }
  }
}
