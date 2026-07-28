package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.ProjectApiKeyCredentialStore;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class JdbcProjectApiKeyCredentialStore
        implements ProjectApiKeyCredentialStore {

  private static final String SELECT_BY_KEY_ID_SQL = """
            SELECT id,
                   tenant_id,
                   project_id,
                   key_id,
                   secret_hash,
                   expires_at,
                   revoked_at
            FROM project_api_keys
            WHERE key_id = ?
            """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcProjectApiKeyCredentialStore(
          JdbcTemplate jdbcTemplate
  ) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public Optional<StoredProjectApiKey> findByKeyId(
          String keyId
  ) {
    StoredProjectApiKey apiKey =
            DataAccessUtils.singleResult(
                    jdbcTemplate.query(
                            SELECT_BY_KEY_ID_SQL,
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

                              return new StoredProjectApiKey(
                                      resultSet.getObject(
                                              "id",
                                              java.util.UUID.class
                                      ),
                                      resultSet.getObject(
                                              "tenant_id",
                                              java.util.UUID.class
                                      ),
                                      resultSet.getObject(
                                              "project_id",
                                              java.util.UUID.class
                                      ),
                                      resultSet.getString(
                                              "key_id"
                                      ),
                                      resultSet.getBytes(
                                              "secret_hash"
                                      ),
                                      expiresAt == null
                                              ? null
                                              : expiresAt.toInstant(),
                                      revokedAt == null
                                              ? null
                                              : revokedAt.toInstant()
                              );
                            },
                            keyId
                    )
            );

    return Optional.ofNullable(apiKey);
  }
}