package com.huning.aerotrace.auth.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class OnboardingInviteService {

  public static final Duration DEFAULT_VALIDITY =
          Duration.ofHours(24);

  private static final Duration MAX_VALIDITY =
          Duration.ofDays(7);

  private static final String INVALID_INVITE_MESSAGE =
          "Invite is invalid or unavailable";

  private final OnboardingInviteTokenService tokenService;
  private final OnboardingInviteStore inviteStore;
  private final SecurityAuditEventStore auditEventStore;

  public OnboardingInviteService(
          OnboardingInviteTokenService tokenService,
          OnboardingInviteStore inviteStore,
          SecurityAuditEventStore auditEventStore
  ) {
    this.tokenService = tokenService;
    this.inviteStore = inviteStore;
    this.auditEventStore = auditEventStore;
  }

  @Transactional
  public IssuedOnboardingInvite issueBootstrap(
          UUID tenantId,
          Duration validity,
          UUID correlationId
  ) {
    Objects.requireNonNull(
            tenantId,
            "Tenant ID must not be null"
    );

    validateValidity(validity);

    Objects.requireNonNull(
            correlationId,
            "Correlation ID must not be null"
    );

    Instant createdAt = Instant.now();
    Instant expiresAt = createdAt.plus(validity);

    inviteStore.lockTenant(tenantId);

    if (inviteStore.countActiveOwners(tenantId) > 0) {
      throw new IllegalStateException(
              "Bootstrap invite is only allowed before the first OWNER exists"
      );
    }

    if (
            inviteStore.countUsableBootstrapInvites(
                    tenantId,
                    createdAt
            ) > 0
    ) {
      throw new IllegalStateException(
              "A usable bootstrap invite already exists for the tenant"
      );
    }

    OnboardingInviteTokenService.GeneratedInviteToken generated =
            tokenService.generate();

    UUID inviteId = UUID.randomUUID();

    inviteStore.save(
            new OnboardingInviteStore.NewOnboardingInvite(
                    inviteId,
                    tenantId,
                    TenantRole.OWNER,
                    generated.tokenHash(),
                    null,
                    createdAt,
                    expiresAt
            )
    );

    auditEventStore.save(
            new SecurityAuditEventStore.NewSecurityAuditEvent(
                    UUID.randomUUID(),
                    null,
                    tenantId,
                    null,
                    "BOOTSTRAP_INVITE_ISSUED",
                    SecurityAuditEventStore.AuditResult.SUCCESS,
                    createdAt,
                    correlationId
            )
    );

    return new IssuedOnboardingInvite(
            inviteId,
            tenantId,
            TenantRole.OWNER,
            generated.rawToken(),
            createdAt,
            expiresAt
    );
  }

  @Transactional
  public ConsumedOnboardingInvite consume(
          String rawToken,
          UUID userId,
          UUID correlationId
  ) {
    Objects.requireNonNull(
            userId,
            "User ID must not be null"
    );

    Objects.requireNonNull(
            correlationId,
            "Correlation ID must not be null"
    );

    byte[] tokenHash =
            tokenService.hash(rawToken)
                    .orElseThrow(
                            this::invalidInvite
                    );

    Instant consumedAt = Instant.now();

    OnboardingInviteStore.StoredOnboardingInvite invite =
            inviteStore.findByTokenHashForUpdate(tokenHash)
                    .orElseThrow(
                            this::invalidInvite
                    );

    return consumeLockedInvite(
            invite,
            userId,
            correlationId,
            consumedAt
    );
  }

  @Transactional(readOnly = true)
  public UUID resolveUsableInviteId(
          String rawToken
  ) {
    byte[] tokenHash =
            tokenService.hash(rawToken)
                    .orElseThrow(
                            this::invalidInvite
                    );

    Instant checkedAt = Instant.now();

    return inviteStore.findUsableByTokenHash(
                    tokenHash,
                    checkedAt
            )
            .map(
                    OnboardingInviteStore.StoredOnboardingInvite::id
            )
            .orElseThrow(this::invalidInvite);
  }

  @Transactional
  public ConsumedOnboardingInvite consumeById(
          UUID inviteId,
          UUID userId,
          UUID correlationId
  ) {
    Objects.requireNonNull(
            inviteId,
            "Invite ID must not be null"
    );

    Objects.requireNonNull(
            userId,
            "User ID must not be null"
    );

    Objects.requireNonNull(
            correlationId,
            "Correlation ID must not be null"
    );

    Instant consumedAt = Instant.now();

    OnboardingInviteStore.StoredOnboardingInvite invite =
            inviteStore.findByIdForUpdate(inviteId)
                    .orElseThrow(
                            this::invalidInvite
                    );

    return consumeLockedInvite(
            invite,
            userId,
            correlationId,
            consumedAt
    );
  }

  private ConsumedOnboardingInvite consumeLockedInvite(
          OnboardingInviteStore.StoredOnboardingInvite invite,
          UUID userId,
          UUID correlationId,
          Instant consumedAt
  ) {

    if (
            invite.consumedAt() != null
                    || invite.revokedAt() != null
                    || !invite.expiresAt().isAfter(consumedAt)
    ) {
      throw invalidInvite();
    }

    if (!inviteStore.activeUserExists(userId)) {
      throw invalidInvite();
    }

    if (
            inviteStore.membershipExists(
                    invite.tenantId(),
                    userId
            )
    ) {
      throw invalidInvite();
    }

    int insertedMemberships =
            inviteStore.createMembership(
                    invite.tenantId(),
                    userId,
                    invite.role(),
                    consumedAt
            );

    requireExactlyOneRow(
            insertedMemberships,
            "Invite membership creation"
    );

    int consumedInvites =
            inviteStore.markConsumed(
                    invite.id(),
                    userId,
                    consumedAt
            );

    requireExactlyOneRow(
            consumedInvites,
            "Invite consumption"
    );

    auditEventStore.save(
            new SecurityAuditEventStore.NewSecurityAuditEvent(
                    UUID.randomUUID(),
                    userId,
                    invite.tenantId(),
                    null,
                    "ONBOARDING_INVITE_CONSUMED",
                    SecurityAuditEventStore.AuditResult.SUCCESS,
                    consumedAt,
                    correlationId
            )
    );

    return new ConsumedOnboardingInvite(
            invite.id(),
            invite.tenantId(),
            userId,
            invite.role(),
            consumedAt
    );
  }

  @Transactional
  public BootstrapInviteRevocation revokeBootstrap(
          UUID tenantId,
          UUID inviteId,
          UUID correlationId
  ) {
    Objects.requireNonNull(
            tenantId,
            "Tenant ID must not be null"
    );

    Objects.requireNonNull(
            inviteId,
            "Invite ID must not be null"
    );

    Objects.requireNonNull(
            correlationId,
            "Correlation ID must not be null"
    );

    inviteStore.lockTenant(tenantId);

    OnboardingInviteStore.StoredOnboardingInvite invite =
            inviteStore.findByIdForUpdate(
                    tenantId,
                    inviteId
            ).orElseThrow(this::invalidInvite);

    if (invite.createdByUserId() != null) {
      throw invalidInvite();
    }

    if (invite.consumedAt() != null) {
      throw new IllegalStateException(
              "A consumed bootstrap invite cannot be revoked"
      );
    }

    if (invite.revokedAt() != null) {
      return new BootstrapInviteRevocation(
              BootstrapInviteRevocationResult.ALREADY_REVOKED,
              invite.id(),
              invite.tenantId(),
              invite.revokedAt()
      );
    }

    Instant revokedAt = Instant.now();

    int revokedInvites =
            inviteStore.markRevoked(
                    invite.id(),
                    revokedAt
            );

    requireExactlyOneRow(
            revokedInvites,
            "Bootstrap invite revocation"
    );

    auditEventStore.save(
            new SecurityAuditEventStore.NewSecurityAuditEvent(
                    UUID.randomUUID(),
                    null,
                    invite.tenantId(),
                    null,
                    "BOOTSTRAP_INVITE_REVOKED",
                    SecurityAuditEventStore.AuditResult.SUCCESS,
                    revokedAt,
                    correlationId
            )
    );

    return new BootstrapInviteRevocation(
            BootstrapInviteRevocationResult.REVOKED,
            invite.id(),
            invite.tenantId(),
            revokedAt
    );
  }

  private void validateValidity(
          Duration validity
  ) {
    Objects.requireNonNull(
            validity,
            "Invite validity must not be null"
    );

    if (
            validity.isZero()
                    || validity.isNegative()
                    || validity.compareTo(MAX_VALIDITY) > 0
    ) {
      throw new IllegalArgumentException(
              "Invite validity must be greater than zero and at most 7 days"
      );
    }
  }

  private IllegalArgumentException invalidInvite() {
    return new IllegalArgumentException(
            INVALID_INVITE_MESSAGE
    );
  }

  private void requireExactlyOneRow(
          int changedRows,
          String operation
  ) {
    if (changedRows != 1) {
      throw new IllegalStateException(
              operation + " did not change exactly one row"
      );
    }
  }

  public record IssuedOnboardingInvite(
          UUID id,
          UUID tenantId,
          TenantRole role,
          String rawToken,
          Instant createdAt,
          Instant expiresAt
  ) {

    @Override
    public String toString() {
      return "IssuedOnboardingInvite["
              + "id="
              + id
              + ", tenantId="
              + tenantId
              + ", role="
              + role
              + ", rawToken=<redacted>"
              + ", createdAt="
              + createdAt
              + ", expiresAt="
              + expiresAt
              + "]";
    }
  }

  public record ConsumedOnboardingInvite(
          UUID inviteId,
          UUID tenantId,
          UUID userId,
          TenantRole role,
          Instant consumedAt
  ) {
  }

  public record BootstrapInviteRevocation(
          BootstrapInviteRevocationResult result,
          UUID inviteId,
          UUID tenantId,
          Instant revokedAt
  ) {
  }

  public enum BootstrapInviteRevocationResult {
    REVOKED,
    ALREADY_REVOKED
  }
}
