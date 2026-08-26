package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.MembershipAuthorizationStore;
import com.huning.aerotrace.auth.application.TenantRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcMembershipAuthorizationStore
        implements MembershipAuthorizationStore {

  private static final String SELECT_TENANT_MEMBERSHIP_SQL = """
          SELECT membership.user_id,
                 membership.tenant_id,
                 membership.role
          FROM tenant_memberships membership
          JOIN app_users app_user
            ON app_user.id = membership.user_id
          WHERE membership.user_id = ?
            AND membership.tenant_id = ?
            AND membership.status = 'ACTIVE'
            AND app_user.status = 'ACTIVE'
          """;

  private static final String SELECT_PROJECT_MEMBERSHIP_SQL = """
          SELECT membership.user_id,
                 membership.tenant_id,
                 membership.role
          FROM tenant_memberships membership
          JOIN app_users app_user
            ON app_user.id = membership.user_id
          JOIN projects project
            ON project.tenant_id = membership.tenant_id
           AND project.id = ?
          WHERE membership.user_id = ?
            AND membership.tenant_id = ?
            AND membership.status = 'ACTIVE'
            AND app_user.status = 'ACTIVE'
          """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcMembershipAuthorizationStore(
          JdbcTemplate jdbcTemplate
  ) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<ActiveMembership> findActiveTenantMembership(
          UUID userId,
          UUID tenantId
  ) {
    return oneOrEmpty(
            jdbcTemplate.query(
                    SELECT_TENANT_MEMBERSHIP_SQL,
                    (
                            resultSet,
                            rowNumber
                    ) -> new ActiveMembership(
                            resultSet.getObject(
                                    "user_id",
                                    UUID.class
                            ),
                            resultSet.getObject(
                                    "tenant_id",
                                    UUID.class
                            ),
                            TenantRole.valueOf(
                                    resultSet.getString(
                                            "role"
                                    )
                            )
                    ),
                    userId,
                    tenantId
            )
    );
  }

  @Override
  public Optional<ActiveMembership> findActiveProjectMembership(
          UUID userId,
          UUID tenantId,
          UUID projectId
  ) {
    return oneOrEmpty(
            jdbcTemplate.query(
                    SELECT_PROJECT_MEMBERSHIP_SQL,
                    (
                            resultSet,
                            rowNumber
                    ) -> new ActiveMembership(
                            resultSet.getObject(
                                    "user_id",
                                    UUID.class
                            ),
                            resultSet.getObject(
                                    "tenant_id",
                                    UUID.class
                            ),
                            TenantRole.valueOf(
                                    resultSet.getString(
                                            "role"
                                    )
                            )
                    ),
                    projectId,
                    userId,
                    tenantId
            )
    );
  }

  private Optional<ActiveMembership> oneOrEmpty(
          List<ActiveMembership> memberships
  ) {
    if (memberships.size() > 1) {
      throw new IllegalStateException(
              "Authorization lookup returned duplicate memberships"
      );
    }

    return memberships.stream().findFirst();
  }
}
