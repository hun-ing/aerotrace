package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.AccessibleProject;
import com.huning.aerotrace.auth.application.WorkspaceStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWorkspaceStore implements WorkspaceStore {
  private static final String SELECT_ACCESSIBLE = """
          SELECT p.id, p.tenant_id, p.name, p.slug
          FROM projects p
          JOIN tenant_memberships m ON m.tenant_id = p.tenant_id
          JOIN app_users u ON u.id = m.user_id
          WHERE u.id = ? AND u.status = 'ACTIVE' AND m.status = 'ACTIVE'
          """;
  private static final RowMapper<AccessibleProject> MAPPER = (rs, row) ->
          new AccessibleProject(rs.getObject("id", UUID.class),
                  rs.getObject("tenant_id", UUID.class), rs.getString("name"), rs.getString("slug"));
  private final JdbcTemplate jdbc;

  public JdbcWorkspaceStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<AccessibleProject> findAccessibleProjects(UUID userId, UUID tenantId) {
    return jdbc.query(SELECT_ACCESSIBLE + " AND p.tenant_id = ? ORDER BY p.slug, p.id",
            MAPPER, userId, tenantId);
  }

  @Override
  public Optional<AccessibleProject> findAccessibleProject(UUID userId, UUID projectId) {
    return jdbc.query(SELECT_ACCESSIBLE + " AND p.id = ?", MAPPER, userId, projectId)
            .stream().findFirst();
  }
}
