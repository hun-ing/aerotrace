package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.ProjectApiKeyStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Repository
public class JdbcProjectApiKeyStore
        implements ProjectApiKeyStore {

  private static final String INSERT_SQL = """
            INSERT INTO project_api_keys
            (
                id,
                tenant_id,
                project_id,
                name,
                key_id,
                secret_hash,
                created_at,
                expires_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcProjectApiKeyStore(
          JdbcTemplate jdbcTemplate
  ) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void save(
          NewProjectApiKey apiKey
  ) {
    jdbcTemplate.update(
            connection -> {
              PreparedStatement statement =
                      connection.prepareStatement(
                              INSERT_SQL
                      );

              statement.setObject(
                      1,
                      apiKey.id()
              );

              statement.setObject(
                      2,
                      apiKey.tenantId()
              );

              statement.setObject(
                      3,
                      apiKey.projectId()
              );

              statement.setString(
                      4,
                      apiKey.name()
              );

              statement.setString(
                      5,
                      apiKey.keyId()
              );

              statement.setBytes(
                      6,
                      apiKey.secretHash()
              );

              statement.setObject(
                      7,
                      OffsetDateTime.ofInstant(
                              apiKey.createdAt(),
                              ZoneOffset.UTC
                      )
              );

              if (apiKey.expiresAt() == null) {
                statement.setNull(
                        8,
                        Types.TIMESTAMP_WITH_TIMEZONE
                );
              } else {
                statement.setObject(
                        8,
                        OffsetDateTime.ofInstant(
                                apiKey.expiresAt(),
                                ZoneOffset.UTC
                        )
                );
              }

              return statement;
            }
    );
  }
}