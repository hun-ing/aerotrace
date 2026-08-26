package com.huning.aerotrace.auth.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface TenantMembershipStore {

  void lockTenant(
          UUID tenantId
  );

  List<StoredTenantMembership> findByTenantForUpdate(
          UUID tenantId
  );

  int updateRole(
          UUID tenantId,
          UUID userId,
          TenantRole role,
          Instant updatedAt
  );

  int revoke(
          UUID tenantId,
          UUID userId,
          Instant revokedAt
  );

  record StoredTenantMembership(
          UUID tenantId,
          UUID userId,
          TenantRole role,
          MembershipStatus status,
          Instant createdAt,
          Instant updatedAt,
          Instant revokedAt
  ) {

    public StoredTenantMembership {
      Objects.requireNonNull(
              tenantId,
              "Tenant ID must not be null"
      );

      Objects.requireNonNull(
              userId,
              "User ID must not be null"
      );

      Objects.requireNonNull(
              role,
              "Tenant role must not be null"
      );

      Objects.requireNonNull(
              status,
              "Membership status must not be null"
      );

      Objects.requireNonNull(
              createdAt,
              "Membership creation time must not be null"
      );

      Objects.requireNonNull(
              updatedAt,
              "Membership update time must not be null"
      );

      if (updatedAt.isBefore(createdAt)) {
        throw new IllegalArgumentException(
                "Membership update time must not be before creation"
        );
      }

      if (
              status == MembershipStatus.ACTIVE
                      && revokedAt != null
      ) {
        throw new IllegalArgumentException(
                "An active membership must not have a revocation time"
        );
      }

      if (
              status == MembershipStatus.REVOKED
                      && (
                      revokedAt == null
                              || revokedAt.isBefore(createdAt)
              )
      ) {
        throw new IllegalArgumentException(
                "A revoked membership requires a valid revocation time"
        );
      }
    }
  }

  enum MembershipStatus {
    ACTIVE,
    REVOKED
  }
}
