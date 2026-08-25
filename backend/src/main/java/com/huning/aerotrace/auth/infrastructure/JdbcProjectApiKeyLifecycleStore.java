package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.ProjectApiKeyLifecycleStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcProjectApiKeyLifecycleStore
        implements ProjectApiKeyLifecycleStore {

  private static final String LOCK_PROJECT_SQL = """
            SELECT id
            FROM projects
            WHERE tenant_id = ?
              AND id = ?
            FOR UPDATE
            """;

  private static final String SELECT_BY_PROJECT_SQL = """
            SELECT id,
                   tenant_id,
                   project_id,
                   name,
                   created_at,
                   expires_at,
                   revoked_at
            FROM project_api_keys
            WHERE tenant_id = ?
              AND project_id = ?
            ORDER BY created_at DESC,
                     id ASC
            """;

  private static final String SELECT_BY_PROJECT_FOR_UPDATE_SQL =
          SELECT_BY_PROJECT_SQL + "FOR UPDATE";

  private static final String REVOKE_SQL = """
            UPDATE project_api_keys
            SET revoked_at = ?
            WHERE tenant_id = ?
              AND project_id = ?
              AND id = ?
              AND revoked_at IS NULL
            """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcProjectApiKeyLifecycleStore(
          JdbcTemplate jdbcTemplate
  ) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void lockProject(
          UUID tenantId,
          UUID projectId
  ) {
    jdbcTemplate.queryForObject(
            LOCK_PROJECT_SQL,
            UUID.class,
            tenantId,
            projectId
    );
  }

  @Override
  public List<StoredProjectApiKeyMetadata> findByProject(
          UUID tenantId,
          UUID projectId
  ) {
    return queryByProject(
            SELECT_BY_PROJECT_SQL,
            tenantId,
            projectId
    );
  }

  @Override
  public List<StoredProjectApiKeyMetadata> findByProjectForUpdate(
          UUID tenantId,
          UUID projectId
  ) {
    return queryByProject(
            SELECT_BY_PROJECT_FOR_UPDATE_SQL,
            tenantId,
            projectId
    );
  }

  @Override
  public int revoke(
          UUID tenantId,
          UUID projectId,
          UUID apiKeyId,
          Instant revokedAt
  ) {
    return jdbcTemplate.update(
            REVOKE_SQL,
            OffsetDateTime.ofInstant(
                    revokedAt,
                    ZoneOffset.UTC
            ),
            tenantId,
            projectId,
            apiKeyId
    );
  }

  private List<StoredProjectApiKeyMetadata> queryByProject(
          String sql,
          UUID tenantId,
          UUID projectId
  ) {
    return jdbcTemplate.query(
            sql,
            (
                    resultSet,
                    rowNumber
            ) -> {
              OffsetDateTime expiresAt =
                      resultSet.getObject(
                              "expires_at",
                              OffsetDateTime.class
                      );

              OffsetDateTime revokedAt =
                      resultSet.getObject(
                              "revoked_at",
                              OffsetDateTime.class
                      );

              return new StoredProjectApiKeyMetadata(
                      resultSet.getObject(
                              "id",
                              UUID.class
                      ),
                      resultSet.getObject(
                              "tenant_id",
                              UUID.class
                      ),
                      resultSet.getObject(
                              "project_id",
                              UUID.class
                      ),
                      resultSet.getString(
                              "name"
                      ),
                      resultSet.getObject(
                              "created_at",
                              OffsetDateTime.class
                      ).toInstant(),
                      expiresAt == null
                              ? null
                              : expiresAt.toInstant(),
                      revokedAt == null
                              ? null
                              : revokedAt.toInstant()
              );
            },
            tenantId,
            projectId
    );
  }
}
