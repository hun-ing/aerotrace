package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.OnboardingInviteStore;
import com.huning.aerotrace.auth.application.TenantRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcOnboardingInviteStore
        implements OnboardingInviteStore {

  private final JdbcTemplate jdbcTemplate;

  public JdbcOnboardingInviteStore(
          JdbcTemplate jdbcTemplate
  ) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void lockTenant(
          UUID tenantId
  ) {
    List<UUID> locked =
            jdbcTemplate.query(
                    """
                    SELECT id
                    FROM tenants
                    WHERE id = ?
                    FOR UPDATE
                    """,
                    (
                            resultSet,
                            rowNumber
                    ) -> resultSet.getObject(
                            "id",
                            UUID.class
                    ),
                    tenantId
            );

    if (locked.size() != 1) {
      throw new IllegalArgumentException(
              "Tenant was not found"
      );
    }
  }

  @Override
  public long countActiveOwners(
          UUID tenantId
  ) {
    Long count =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM tenant_memberships
                    WHERE tenant_id = ?
                      AND role = 'OWNER'
                      AND status = 'ACTIVE'
                    """,
                    Long.class,
                    tenantId
            );

    return count == null ? 0 : count;
  }

  @Override
  public long countUsableBootstrapInvites(
          UUID tenantId,
          Instant now
  ) {
    Long count =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM onboarding_invites
                    WHERE tenant_id = ?
                      AND created_by_user_id IS NULL
                      AND consumed_at IS NULL
                      AND revoked_at IS NULL
                      AND expires_at > ?
                    """,
                    Long.class,
                    tenantId,
                    OffsetDateTime.ofInstant(
                            now,
                            ZoneOffset.UTC
                    )
            );

    return count == null ? 0 : count;
  }

  @Override
  public void save(
          NewOnboardingInvite invite
  ) {
    int insertedRows =
            jdbcTemplate.update(
                    """
                    INSERT INTO onboarding_invites (
                        id,
                        tenant_id,
                        role,
                        token_hash,
                        created_by_user_id,
                        created_at,
                        expires_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    invite.id(),
                    invite.tenantId(),
                    invite.role().name(),
                    invite.tokenHash(),
                    invite.createdByUserId(),
                    OffsetDateTime.ofInstant(
                            invite.createdAt(),
                            ZoneOffset.UTC
                    ),
                    OffsetDateTime.ofInstant(
                            invite.expiresAt(),
                            ZoneOffset.UTC
                    )
            );

    if (insertedRows != 1) {
      throw new IllegalStateException(
              "Invite insert did not create exactly one row"
      );
    }
  }

  @Override
  public Optional<StoredOnboardingInvite> findByTokenHashForUpdate(
          byte[] tokenHash
  ) {
    return queryOneForUpdate(
            """
            SELECT id,
                   tenant_id,
                   role,
                   created_by_user_id,
                   created_at,
                   expires_at,
                   consumed_at,
                   consumed_by_user_id,
                   revoked_at
            FROM onboarding_invites
            WHERE token_hash = ?
            FOR UPDATE
            """,
            tokenHash
    );
  }

  @Override
  public Optional<StoredOnboardingInvite> findUsableByTokenHash(
          byte[] tokenHash,
          Instant now
  ) {
    return queryOneForUpdate(
            """
            SELECT id,
                   tenant_id,
                   role,
                   created_by_user_id,
                   created_at,
                   expires_at,
                   consumed_at,
                   consumed_by_user_id,
                   revoked_at
            FROM onboarding_invites
            WHERE token_hash = ?
              AND consumed_at IS NULL
              AND revoked_at IS NULL
              AND expires_at > ?
            """,
            tokenHash,
            OffsetDateTime.ofInstant(
                    now,
                    ZoneOffset.UTC
            )
    );
  }

  @Override
  public Optional<StoredOnboardingInvite> findByIdForUpdate(
          UUID inviteId
  ) {
    return queryOneForUpdate(
            """
            SELECT id,
                   tenant_id,
                   role,
                   created_by_user_id,
                   created_at,
                   expires_at,
                   consumed_at,
                   consumed_by_user_id,
                   revoked_at
            FROM onboarding_invites
            WHERE id = ?
            FOR UPDATE
            """,
            inviteId
    );
  }

  @Override
  public Optional<StoredOnboardingInvite> findByIdForUpdate(
          UUID tenantId,
          UUID inviteId
  ) {
    return queryOneForUpdate(
            """
            SELECT id,
                   tenant_id,
                   role,
                   created_by_user_id,
                   created_at,
                   expires_at,
                   consumed_at,
                   consumed_by_user_id,
                   revoked_at
            FROM onboarding_invites
            WHERE tenant_id = ?
              AND id = ?
            FOR UPDATE
            """,
            tenantId,
            inviteId
    );
  }

  private Optional<StoredOnboardingInvite> queryOneForUpdate(
          String sql,
          Object... arguments
  ) {
    List<StoredOnboardingInvite> invites =
            jdbcTemplate.query(
                    sql,
                    (
                            resultSet,
                            rowNumber
                    ) -> {
                      OffsetDateTime consumedAt =
                              resultSet.getObject(
                                      "consumed_at",
                                      OffsetDateTime.class
                              );

                      OffsetDateTime revokedAt =
                              resultSet.getObject(
                                      "revoked_at",
                                      OffsetDateTime.class
                              );

                      return new StoredOnboardingInvite(
                              resultSet.getObject(
                                      "id",
                                      UUID.class
                              ),
                              resultSet.getObject(
                                      "tenant_id",
                                      UUID.class
                              ),
                              TenantRole.valueOf(
                                      resultSet.getString("role")
                              ),
                              resultSet.getObject(
                                      "created_by_user_id",
                                      UUID.class
                              ),
                              resultSet.getObject(
                                      "created_at",
                                      OffsetDateTime.class
                              ).toInstant(),
                              resultSet.getObject(
                                      "expires_at",
                                      OffsetDateTime.class
                              ).toInstant(),
                              consumedAt == null
                                      ? null
                                      : consumedAt.toInstant(),
                              resultSet.getObject(
                                      "consumed_by_user_id",
                                      UUID.class
                              ),
                              revokedAt == null
                                      ? null
                                      : revokedAt.toInstant()
                      );
                    },
                    arguments
            );

    if (invites.size() > 1) {
      throw new IllegalStateException(
              "Invite token hash returned duplicate rows"
      );
    }

    return invites.stream().findFirst();
  }

  @Override
  public boolean activeUserExists(
          UUID userId
  ) {
    Long count =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM app_users
                    WHERE id = ?
                      AND status = 'ACTIVE'
                    """,
                    Long.class,
                    userId
            );

    return count != null && count == 1;
  }

  @Override
  public boolean membershipExists(
          UUID tenantId,
          UUID userId
  ) {
    Long count =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM tenant_memberships
                    WHERE tenant_id = ?
                      AND user_id = ?
                    """,
                    Long.class,
                    tenantId,
                    userId
            );

    return count != null && count > 0;
  }

  @Override
  public int createMembership(
          UUID tenantId,
          UUID userId,
          TenantRole role,
          Instant createdAt
  ) {
    OffsetDateTime createdDateTime =
            OffsetDateTime.ofInstant(
                    createdAt,
                    ZoneOffset.UTC
            );

    return jdbcTemplate.update(
            """
            INSERT INTO tenant_memberships (
                tenant_id,
                user_id,
                role,
                status,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, 'ACTIVE', ?, ?)
            """,
            tenantId,
            userId,
            role.name(),
            createdDateTime,
            createdDateTime
    );
  }

  @Override
  public int markConsumed(
          UUID inviteId,
          UUID userId,
          Instant consumedAt
  ) {
    return jdbcTemplate.update(
            """
            UPDATE onboarding_invites
            SET consumed_at = ?,
                consumed_by_user_id = ?
            WHERE id = ?
              AND consumed_at IS NULL
              AND revoked_at IS NULL
            """,
            OffsetDateTime.ofInstant(
                    consumedAt,
                    ZoneOffset.UTC
            ),
            userId,
            inviteId
    );
  }

  @Override
  public int markRevoked(
          UUID inviteId,
          Instant revokedAt
  ) {
    return jdbcTemplate.update(
            """
            UPDATE onboarding_invites
            SET revoked_at = ?
            WHERE id = ?
              AND consumed_at IS NULL
              AND revoked_at IS NULL
            """,
            OffsetDateTime.ofInstant(
                    revokedAt,
                    ZoneOffset.UTC
            ),
            inviteId
    );
  }
}
