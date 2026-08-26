package com.huning.aerotrace.auth.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class MembershipPermissionService {

  private final MembershipAuthorizationStore authorizationStore;

  public MembershipPermissionService(
          MembershipAuthorizationStore authorizationStore
  ) {
    this.authorizationStore = authorizationStore;
  }

  @Transactional(readOnly = true)
  public AuthorizationDecision authorizeTenant(
          UUID userId,
          UUID tenantId,
          TenantPermission permission
  ) {
    validateRequest(
            userId,
            tenantId,
            permission
    );

    return decide(
            authorizationStore.findActiveTenantMembership(
                    userId,
                    tenantId
            ),
            permission
    );
  }

  @Transactional(readOnly = true)
  public AuthorizationDecision authorizeProject(
          UUID userId,
          UUID tenantId,
          UUID projectId,
          TenantPermission permission
  ) {
    validateRequest(
            userId,
            tenantId,
            permission
    );

    Objects.requireNonNull(
            projectId,
            "Project ID must not be null"
    );

    return decide(
            authorizationStore.findActiveProjectMembership(
                    userId,
                    tenantId,
                    projectId
            ),
            permission
    );
  }

  @Transactional(readOnly = true)
  public AuthorizationDecision authorizeInvite(
          UUID userId,
          UUID tenantId,
          TenantRole invitedRole
  ) {
    Objects.requireNonNull(
            invitedRole,
            "Invited tenant role must not be null"
    );

    TenantPermission requiredPermission =
            invitedRole == TenantRole.OWNER
                    ? TenantPermission.OWNER_MANAGE
                    : TenantPermission.MEMBER_MANAGE;

    return authorizeTenant(
            userId,
            tenantId,
            requiredPermission
    );
  }

  private AuthorizationDecision decide(
          Optional<MembershipAuthorizationStore.ActiveMembership>
                  membership,
          TenantPermission permission
  ) {
    if (membership.isEmpty()) {
      return new AuthorizationDecision(
              AuthorizationDecision.AuthorizationOutcome.NOT_FOUND,
              null
      );
    }

    TenantRole role =
            membership.orElseThrow().role();

    AuthorizationDecision.AuthorizationOutcome outcome =
            allows(
                    role,
                    permission
            )
                    ? AuthorizationDecision.AuthorizationOutcome.ALLOWED
                    : AuthorizationDecision.AuthorizationOutcome.FORBIDDEN;

    return new AuthorizationDecision(
            outcome,
            role
    );
  }

  private boolean allows(
          TenantRole role,
          TenantPermission permission
  ) {
    return switch (permission) {
      case TENANT_READ, PROJECT_TRACE_READ -> true;
      case PROJECT_MANAGE,
           API_KEY_METADATA_READ,
           API_KEY_MANAGE,
           MEMBER_MANAGE -> role == TenantRole.OWNER
              || role == TenantRole.ADMIN;
      case OWNER_MANAGE, TENANT_MANAGE ->
              role == TenantRole.OWNER;
    };
  }

  private void validateRequest(
          UUID userId,
          UUID tenantId,
          TenantPermission permission
  ) {
    Objects.requireNonNull(
            userId,
            "User ID must not be null"
    );

    Objects.requireNonNull(
            tenantId,
            "Tenant ID must not be null"
    );

    Objects.requireNonNull(
            permission,
            "Tenant permission must not be null"
    );
  }
}
