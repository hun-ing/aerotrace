package com.huning.aerotrace.auth.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface OnboardingInviteStore {

  void lockTenant(
          UUID tenantId
  );

  long countActiveOwners(
          UUID tenantId
  );

  long countUsableBootstrapInvites(
          UUID tenantId,
          Instant now
  );

  void save(
          NewOnboardingInvite invite
  );

  Optional<StoredOnboardingInvite> findByTokenHashForUpdate(
          byte[] tokenHash
  );

  Optional<StoredOnboardingInvite> findUsableByTokenHash(
          byte[] tokenHash,
          Instant now
  );

  Optional<StoredOnboardingInvite> findByIdForUpdate(
          UUID inviteId
  );

  Optional<StoredOnboardingInvite> findByIdForUpdate(
          UUID tenantId,
          UUID inviteId
  );

  boolean activeUserExists(
          UUID userId
  );

  boolean membershipExists(
          UUID tenantId,
          UUID userId
  );

  int createMembership(
          UUID tenantId,
          UUID userId,
          TenantRole role,
          Instant createdAt
  );

  int markConsumed(
          UUID inviteId,
          UUID userId,
          Instant consumedAt
  );

  int markRevoked(
          UUID inviteId,
          Instant revokedAt
  );

  record NewOnboardingInvite(
          UUID id,
          UUID tenantId,
          TenantRole role,
          byte[] tokenHash,
          UUID createdByUserId,
          Instant createdAt,
          Instant expiresAt
  ) {

    public NewOnboardingInvite {
      Objects.requireNonNull(
              id,
              "Invite ID must not be null"
      );

      Objects.requireNonNull(
              tenantId,
              "Invite tenant ID must not be null"
      );

      Objects.requireNonNull(
              role,
              "Invite role must not be null"
      );

      if (tokenHash == null || tokenHash.length != 32) {
        throw new IllegalArgumentException(
                "Invite token hash must be 32 bytes"
        );
      }

      Objects.requireNonNull(
              createdAt,
              "Invite creation time must not be null"
      );

      Objects.requireNonNull(
              expiresAt,
              "Invite expiration time must not be null"
      );

      if (!expiresAt.isAfter(createdAt)) {
        throw new IllegalArgumentException(
                "Invite expiration must be after creation"
        );
      }

      tokenHash = tokenHash.clone();
    }

    @Override
    public byte[] tokenHash() {
      return tokenHash.clone();
    }
  }

  record StoredOnboardingInvite(
          UUID id,
          UUID tenantId,
          TenantRole role,
          UUID createdByUserId,
          Instant createdAt,
          Instant expiresAt,
          Instant consumedAt,
          UUID consumedByUserId,
          Instant revokedAt
  ) {

    public StoredOnboardingInvite {
      Objects.requireNonNull(id, "Invite ID must not be null");
      Objects.requireNonNull(tenantId, "Invite tenant ID must not be null");
      Objects.requireNonNull(role, "Invite role must not be null");
      Objects.requireNonNull(createdAt, "Invite creation time must not be null");
      Objects.requireNonNull(expiresAt, "Invite expiration time must not be null");
    }
  }
}
