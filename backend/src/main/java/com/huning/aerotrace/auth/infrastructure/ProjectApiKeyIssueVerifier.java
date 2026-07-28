package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.BackendApplication;
import com.huning.aerotrace.auth.application.IssuedProjectApiKey;
import com.huning.aerotrace.auth.application.ParsedProjectApiKey;
import com.huning.aerotrace.auth.application.ProjectApiKeyIssueService;
import com.huning.aerotrace.auth.application.ProjectApiKeyTokenService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public final class ProjectApiKeyIssueVerifier {

  private static final UUID TENANT_ID =
          UUID.fromString(
                  "11111111-1111-1111-1111-111111111111"
          );

  private static final UUID PROJECT_ID =
          UUID.fromString(
                  "22222222-2222-2222-2222-222222222222"
          );

  private ProjectApiKeyIssueVerifier() {
  }

  public static void main(
          String[] args
  ) {
    try (
            ConfigurableApplicationContext context =
                    new SpringApplicationBuilder(
                            BackendApplication.class
                    )
                            .web(
                                    WebApplicationType.NONE
                            )
                            .properties(
                                    "spring.main.banner-mode=off",
                                    "logging.level.root=WARN"
                            )
                            .run(args)
    ) {
      JdbcTemplate jdbcTemplate =
              context.getBean(
                      JdbcTemplate.class
              );

      verifyFixtureExists(
              jdbcTemplate
      );

      ProjectApiKeyIssueService issueService =
              context.getBean(
                      ProjectApiKeyIssueService.class
              );

      ProjectApiKeyTokenService tokenService =
              context.getBean(
                      ProjectApiKeyTokenService.class
              );

      IssuedProjectApiKey issued = null;

      try {
        issued =
                issueService.issue(
                        TENANT_ID,
                        PROJECT_ID,
                        "local-verification",
                        Instant.now().plus(
                                30,
                                ChronoUnit.DAYS
                        )
                );

        StoredApiKeyRow stored =
                findStoredRow(
                        jdbcTemplate,
                        issued.id()
                );

        ParsedProjectApiKey parsed =
                tokenService.parse(
                        issued.rawKey()
                ).orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Generated API Key "
                                                + "could not be parsed"
                                )
                );

        boolean keyIdMatches =
                issued.keyId()
                        .equals(
                                stored.keyId()
                        )
                        && parsed.keyId()
                        .equals(
                                stored.keyId()
                        );

        boolean secretMatches =
                tokenService.matchesSecret(
                        parsed.secret(),
                        stored.secretHash()
                );

        System.out.println();
        System.out.println(
                "AeroTrace Project API Key 발급 검증"
        );
        System.out.println(
                "- API Key row ID: "
                        + issued.id()
        );
        System.out.println(
                "- keyId: "
                        + issued.keyId()
        );
        System.out.println(
                "- DB hash bytes: "
                        + stored.secretHash().length
        );
        System.out.println(
                "- keyId 일치: "
                        + keyIdMatches
        );
        System.out.println(
                "- Secret 해시 일치: "
                        + secretMatches
        );
        System.out.println(
                "- revoked: "
                        + stored.revoked()
        );

        /*
         * 이 값은 로컬 검증용으로만 한 번 출력한다.
         * 운영 로그에 동일한 출력을 추가하면 안 된다.
         */
        System.out.println(
                "- 원문 API Key(로컬 검증용): "
                        + issued.rawKey()
        );

        if (!keyIdMatches) {
          throw new IllegalStateException(
                  "Stored keyId does not match"
          );
        }

        if (!secretMatches) {
          throw new IllegalStateException(
                  "Stored Secret hash does not match"
          );
        }

        if (stored.secretHash().length != 32) {
          throw new IllegalStateException(
                  "Stored Secret hash is not 32 bytes"
          );
        }

        if (stored.revoked()) {
          throw new IllegalStateException(
                  "New API Key must not be revoked"
          );
        }
      } finally {
        if (issued != null) {
          int deletedRows =
                  jdbcTemplate.update(
                          """
                          DELETE FROM project_api_keys
                          WHERE id = ?
                          """,
                          issued.id()
                  );

          System.out.println(
                  "- 정리된 검증 행 수: "
                          + deletedRows
          );
        }
      }
    }
  }

  private static void verifyFixtureExists(
          JdbcTemplate jdbcTemplate
  ) {
    Integer count =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM projects
                    WHERE tenant_id = ?
                      AND id = ?
                    """,
                    Integer.class,
                    TENANT_ID,
                    PROJECT_ID
            );

    if (count == null || count != 1) {
      throw new IllegalStateException(
              "Local Tenant와 Project가 없습니다. "
                      + "local-dev-fixtures.sql을 먼저 실행하세요."
      );
    }
  }

  private static StoredApiKeyRow findStoredRow(
          JdbcTemplate jdbcTemplate,
          UUID apiKeyId
  ) {
    return jdbcTemplate.queryForObject(
            """
            SELECT key_id,
                   secret_hash,
                   revoked_at IS NOT NULL AS revoked
            FROM project_api_keys
            WHERE id = ?
            """,
            (
                    resultSet,
                    rowNumber
            ) ->
                    new StoredApiKeyRow(
                            resultSet.getString(
                                    "key_id"
                            ),
                            resultSet.getBytes(
                                    "secret_hash"
                            ),
                            resultSet.getBoolean(
                                    "revoked"
                            )
                    ),
            apiKeyId
    );
  }

  private record StoredApiKeyRow(
          String keyId,
          byte[] secretHash,
          boolean revoked
  ) {

    private StoredApiKeyRow {
      secretHash =
              secretHash.clone();
    }

    @Override
    public byte[] secretHash() {
      return secretHash.clone();
    }
  }
}