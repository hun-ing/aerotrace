package com.huning.aerotrace.auth.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class TenantMembershipService {

  private final TenantMembershipStore membershipStore;

  public TenantMembershipService(
          TenantMembershipStore membershipStore
  ) {
    this.membershipStore = membershipStore;
  }

  @Transactional
  public MembershipChange changeRole(
          UUID tenantId,
          UUID userId,
          TenantRole newRole
  ) {
    return changeRole(
            tenantId,
            userId,
            newRole,
            Instant.now()
    );
  }

  MembershipChange changeRole(
          UUID tenantId,
          UUID userId,
          TenantRole newRole,
          Instant changedAt
  ) {
    validateChange(
            tenantId,
            userId,
            changedAt
    );

    Objects.requireNonNull(
            newRole,
            "New tenant role must not be null"
    );

    LockedMemberships locked =
            lockAndFindTarget(
                    tenantId,
                    userId
            );

    TenantMembershipStore.StoredTenantMembership target =
            locked.target();

    requireActive(target);

    if (target.role() == newRole) {
      return new MembershipChange(
              MembershipChangeResult.UNCHANGED,
              target.role(),
              newRole,
              locked.activeOwnerCount()
      );
    }

    if (
            target.role() == TenantRole.OWNER
                    && newRole != TenantRole.OWNER
                    && locked.activeOwnerCount() <= 1
    ) {
      throw lastOwnerException();
    }

    int updatedRows =
            membershipStore.updateRole(
                    tenantId,
                    userId,
                    newRole,
                    changedAt
            );

    requireExactlyOneRow(
            updatedRows,
            "Membership role change"
    );

    long activeOwnerCountAfter =
            locked.activeOwnerCount()
                    + ownerDelta(
                    target.role(),
                    newRole
            );

    return new MembershipChange(
            MembershipChangeResult.ROLE_CHANGED,
            target.role(),
            newRole,
            activeOwnerCountAfter
    );
  }

  @Transactional
  public MembershipChange revoke(
          UUID tenantId,
          UUID userId
  ) {
    return revoke(
            tenantId,
            userId,
            Instant.now()
    );
  }

  MembershipChange revoke(
          UUID tenantId,
          UUID userId,
          Instant revokedAt
  ) {
    validateChange(
            tenantId,
            userId,
            revokedAt
    );

    LockedMemberships locked =
            lockAndFindTarget(
                    tenantId,
                    userId
            );

    TenantMembershipStore.StoredTenantMembership target =
            locked.target();

    if (
            target.status()
                    == TenantMembershipStore.MembershipStatus.REVOKED
    ) {
      return new MembershipChange(
              MembershipChangeResult.ALREADY_REVOKED,
              target.role(),
              target.role(),
              locked.activeOwnerCount()
      );
    }

    if (
            target.role() == TenantRole.OWNER
                    && locked.activeOwnerCount() <= 1
    ) {
      throw lastOwnerException();
    }

    int updatedRows =
            membershipStore.revoke(
                    tenantId,
                    userId,
                    revokedAt
            );

    requireExactlyOneRow(
            updatedRows,
            "Membership revocation"
    );

    long activeOwnerCountAfter =
            target.role() == TenantRole.OWNER
                    ? locked.activeOwnerCount() - 1
                    : locked.activeOwnerCount();

    return new MembershipChange(
            MembershipChangeResult.REVOKED,
            target.role(),
            target.role(),
            activeOwnerCountAfter
    );
  }

  private LockedMemberships lockAndFindTarget(
          UUID tenantId,
          UUID userId
  ) {
    membershipStore.lockTenant(tenantId);

    List<TenantMembershipStore.StoredTenantMembership> memberships =
            membershipStore.findByTenantForUpdate(tenantId);

    TenantMembershipStore.StoredTenantMembership target =
            memberships.stream()
                    .filter(
                            membership -> membership.userId()
                                    .equals(userId)
                    )
                    .findFirst()
                    .orElseThrow(
                            () -> new IllegalArgumentException(
                                    "Membership was not found in the requested tenant"
                            )
                    );

    long activeOwnerCount =
            memberships.stream()
                    .filter(
                            membership -> membership.status()
                                    == TenantMembershipStore.MembershipStatus.ACTIVE
                                    && membership.role()
                                    == TenantRole.OWNER
                    )
                    .count();

    return new LockedMemberships(
            target,
            activeOwnerCount
    );
  }

  private void requireActive(
          TenantMembershipStore.StoredTenantMembership membership
  ) {
    if (
            membership.status()
                    != TenantMembershipStore.MembershipStatus.ACTIVE
    ) {
      throw new IllegalStateException(
              "A revoked membership cannot change role"
      );
    }
  }

  private IllegalStateException lastOwnerException() {
    return new IllegalStateException(
            "Refusing to revoke or demote the last active OWNER. "
                    + "Promote another active member first."
    );
  }

  private long ownerDelta(
          TenantRole previousRole,
          TenantRole newRole
  ) {
    if (
            previousRole != TenantRole.OWNER
                    && newRole == TenantRole.OWNER
    ) {
      return 1;
    }

    if (
            previousRole == TenantRole.OWNER
                    && newRole != TenantRole.OWNER
    ) {
      return -1;
    }

    return 0;
  }

  private void validateChange(
          UUID tenantId,
          UUID userId,
          Instant changedAt
  ) {
    Objects.requireNonNull(
            tenantId,
            "Tenant ID must not be null"
    );

    Objects.requireNonNull(
            userId,
            "User ID must not be null"
    );

    Objects.requireNonNull(
            changedAt,
            "Membership change time must not be null"
    );
  }

  private void requireExactlyOneRow(
          int updatedRows,
          String operation
  ) {
    if (updatedRows != 1) {
      throw new IllegalStateException(
              operation + " did not update exactly one row"
      );
    }
  }

  private record LockedMemberships(
          TenantMembershipStore.StoredTenantMembership target,
          long activeOwnerCount
  ) {
  }

  public record MembershipChange(
          MembershipChangeResult result,
          TenantRole previousRole,
          TenantRole currentRole,
          long activeOwnerCountAfter
  ) {
  }

  public enum MembershipChangeResult {
    ROLE_CHANGED,
    REVOKED,
    ALREADY_REVOKED,
    UNCHANGED
  }
}
