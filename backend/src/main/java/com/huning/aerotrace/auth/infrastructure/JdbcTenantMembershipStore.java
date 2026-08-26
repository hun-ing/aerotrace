package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.TenantMembershipStore;
import com.huning.aerotrace.auth.application.TenantRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcTenantMembershipStore
        implements TenantMembershipStore {

  private final JdbcTemplate jdbcTemplate;

  public JdbcTenantMembershipStore(
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
  public List<StoredTenantMembership> findByTenantForUpdate(
          UUID tenantId
  ) {
    return jdbcTemplate.query(
            """
            SELECT tenant_id,
                   user_id,
                   role,
                   status,
                   created_at,
                   updated_at,
                   revoked_at
            FROM tenant_memberships
            WHERE tenant_id = ?
            ORDER BY user_id
            FOR UPDATE
            """,
            (
                    resultSet,
                    rowNumber
            ) -> {
              OffsetDateTime revokedAt =
                      resultSet.getObject(
                              "revoked_at",
                              OffsetDateTime.class
                      );

              return new StoredTenantMembership(
                      resultSet.getObject(
                              "tenant_id",
                              UUID.class
                      ),
                      resultSet.getObject(
                              "user_id",
                              UUID.class
                      ),
                      TenantRole.valueOf(
                              resultSet.getString("role")
                      ),
                      MembershipStatus.valueOf(
                              resultSet.getString("status")
                      ),
                      resultSet.getObject(
                              "created_at",
                              OffsetDateTime.class
                      ).toInstant(),
                      resultSet.getObject(
                              "updated_at",
                              OffsetDateTime.class
                      ).toInstant(),
                      revokedAt == null
                              ? null
                              : revokedAt.toInstant()
              );
            },
            tenantId
    );
  }

  @Override
  public int updateRole(
          UUID tenantId,
          UUID userId,
          TenantRole role,
          Instant updatedAt
  ) {
    return jdbcTemplate.update(
            """
            UPDATE tenant_memberships
            SET role = ?,
                updated_at = ?
            WHERE tenant_id = ?
              AND user_id = ?
              AND status = 'ACTIVE'
            """,
            role.name(),
            OffsetDateTime.ofInstant(
                    updatedAt,
                    ZoneOffset.UTC
            ),
            tenantId,
            userId
    );
  }

  @Override
  public int revoke(
          UUID tenantId,
          UUID userId,
          Instant revokedAt
  ) {
    OffsetDateTime revokedDateTime =
            OffsetDateTime.ofInstant(
                    revokedAt,
                    ZoneOffset.UTC
            );

    return jdbcTemplate.update(
            """
            UPDATE tenant_memberships
            SET status = 'REVOKED',
                updated_at = ?,
                revoked_at = ?
            WHERE tenant_id = ?
              AND user_id = ?
              AND status = 'ACTIVE'
            """,
            revokedDateTime,
            revokedDateTime,
            tenantId,
            userId
    );
  }
}
