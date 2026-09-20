package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.CurrentUserStore;
import com.huning.aerotrace.auth.application.TenantRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcCurrentUserStore
        implements CurrentUserStore {

  private final JdbcTemplate jdbcTemplate;

  public JdbcCurrentUserStore(
          JdbcTemplate jdbcTemplate
  ) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public boolean activeUserExists(UUID userId) {
    Long count = jdbcTemplate.queryForObject(
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
  public Optional<StoredCurrentUser> findActiveUser(
          UUID userId
  ) {
    List<StoredCurrentUser> users = jdbcTemplate.query(
            """
            SELECT id,
                   display_name,
                   avatar_url
            FROM app_users
            WHERE id = ?
              AND status = 'ACTIVE'
            """,
            (
                    resultSet,
                    rowNumber
            ) -> new StoredCurrentUser(
                    resultSet.getObject(
                            "id",
                            UUID.class
                    ),
                    resultSet.getString("display_name"),
                    resultSet.getString("avatar_url")
            ),
            userId
    );

    if (users.size() > 1) {
      throw new IllegalStateException(
              "User ID returned duplicate rows"
      );
    }

    return users.stream().findFirst();
  }

  @Override
  public List<ActiveTenantMembership> findActiveMemberships(
          UUID userId
  ) {
    return jdbcTemplate.query(
            """
            SELECT tenants.id AS tenant_id,
                   tenants.name AS tenant_name,
                   tenants.slug AS tenant_slug,
                   memberships.role
            FROM tenant_memberships memberships
            JOIN tenants
              ON tenants.id = memberships.tenant_id
            JOIN app_users ON app_users.id = memberships.user_id
            WHERE memberships.user_id = ?
              AND app_users.status = 'ACTIVE'
              AND memberships.status = 'ACTIVE'
            ORDER BY tenants.slug,
                     tenants.id
            """,
            (
                    resultSet,
                    rowNumber
            ) -> new ActiveTenantMembership(
                    resultSet.getObject(
                            "tenant_id",
                            UUID.class
                    ),
                    resultSet.getString("tenant_name"),
                    resultSet.getString("tenant_slug"),
                    TenantRole.valueOf(
                            resultSet.getString("role")
                    )
            ),
            userId
    );
  }
}
