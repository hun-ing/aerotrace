package com.huning.aerotrace.auth.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface MembershipAuthorizationStore {

  Optional<ActiveMembership> findActiveTenantMembership(
          UUID userId,
          UUID tenantId
  );

  Optional<ActiveMembership> findActiveProjectMembership(
          UUID userId,
          UUID tenantId,
          UUID projectId
  );

  record ActiveMembership(
          UUID userId,
          UUID tenantId,
          TenantRole role
  ) {

    public ActiveMembership {
      Objects.requireNonNull(
              userId,
              "User ID must not be null"
      );

      Objects.requireNonNull(
              tenantId,
              "Tenant ID must not be null"
      );

      Objects.requireNonNull(
              role,
              "Tenant role must not be null"
      );
    }
  }
}
