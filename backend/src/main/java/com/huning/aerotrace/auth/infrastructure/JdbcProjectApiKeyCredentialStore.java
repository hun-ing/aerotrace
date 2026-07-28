package com.huning.aerotrace.auth.infrastructure;


import com.huning.aerotrace.auth.application.ProjectApiKeyLookupUnavailableException;
import com.huning.aerotrace.auth.application.ProjectApiKeyCredentialStore;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

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
    try {
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
    } catch (DataAccessException exception) {
      if (isTemporarilyUnavailable(exception)) {
        throw new ProjectApiKeyLookupUnavailableException(
                exception
        );
      }

      /*
       * SQL 오류나 데이터 무결성 문제처럼 일시적 장애로
       * 판단할 수 없는 예외는 그대로 전달한다.
       */
      throw exception;
    }
  }

  private boolean isTemporarilyUnavailable(
          DataAccessException exception
  ) {
    return exception
            instanceof DataAccessResourceFailureException
            || exception
            instanceof TransientDataAccessException
            || exception
            instanceof RecoverableDataAccessException;
  }
}