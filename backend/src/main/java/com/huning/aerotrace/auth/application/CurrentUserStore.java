package com.huning.aerotrace.auth.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface CurrentUserStore {

  boolean activeUserExists(UUID userId);

  Optional<StoredCurrentUser> findActiveUser(UUID userId);

  List<ActiveTenantMembership> findActiveMemberships(
          UUID userId
  );

  record StoredCurrentUser(
          UUID userId,
          String displayName,
          String avatarUrl
  ) {

    public StoredCurrentUser {
      Objects.requireNonNull(
              userId,
              "User ID must not be null"
      );

      if (displayName == null || displayName.isBlank()) {
        throw new IllegalArgumentException(
                "Display name must not be blank"
        );
      }
    }
  }

  record ActiveTenantMembership(
          UUID tenantId,
          String tenantName,
          String tenantSlug,
          TenantRole role
  ) {

    public ActiveTenantMembership {
      Objects.requireNonNull(
              tenantId,
              "Tenant ID must not be null"
      );

      if (tenantName == null || tenantName.isBlank()) {
        throw new IllegalArgumentException(
                "Tenant name must not be blank"
        );
      }

      if (tenantSlug == null || tenantSlug.isBlank()) {
        throw new IllegalArgumentException(
                "Tenant slug must not be blank"
        );
      }

      Objects.requireNonNull(
              role,
              "Tenant role must not be null"
      );
    }
  }
}
