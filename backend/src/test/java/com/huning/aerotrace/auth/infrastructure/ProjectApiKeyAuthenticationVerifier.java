package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.BackendApplication;
import com.huning.aerotrace.auth.application.AuthenticatedProject;
import com.huning.aerotrace.auth.application.IssuedProjectApiKey;
import com.huning.aerotrace.auth.application.ProjectApiKeyAuthenticationService;
import com.huning.aerotrace.auth.application.ProjectApiKeyIssueService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

public final class ProjectApiKeyAuthenticationVerifier {

  private static final UUID TENANT_ID =
          UUID.fromString(
                  "11111111-1111-1111-1111-111111111111"
          );

  private static final UUID PROJECT_ID =
          UUID.fromString(
                  "22222222-2222-2222-2222-222222222222"
          );

  private ProjectApiKeyAuthenticationVerifier() {
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

      verifyFixtureExists(jdbcTemplate);

      ProjectApiKeyIssueService issueService =
              context.getBean(
                      ProjectApiKeyIssueService.class
              );

      ProjectApiKeyAuthenticationService
              authenticationService =
              context.getBean(
                      ProjectApiKeyAuthenticationService.class
              );

      IssuedProjectApiKey issued = null;

      try {
        issued =
                issueService.issue(
                        TENANT_ID,
                        PROJECT_ID,
                        "authentication-verification",
                        Instant.now().plus(
                                30,
                                ChronoUnit.DAYS
                        )
                );

        Optional<AuthenticatedProject>
                activeResult =
                authenticationService.authenticate(
                        issued.rawKey()
                );

        if (activeResult.isEmpty()) {
          throw new IllegalStateException(
                  "Active API Key authentication failed"
          );
        }

        AuthenticatedProject authenticated =
                activeResult.orElseThrow();

        boolean tenantMatches =
                TENANT_ID.equals(
                        authenticated.tenantId()
                );

        boolean projectMatches =
                PROJECT_ID.equals(
                        authenticated.projectId()
                );

        boolean wrongSecretRejected =
                authenticationService.authenticate(
                        tamperLastCharacter(
                                issued.rawKey()
                        )
                ).isEmpty();

        int revokedRows =
                jdbcTemplate.update(
                        """
                        UPDATE project_api_keys
                        SET revoked_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                          AND revoked_at IS NULL
                        """,
                        issued.id()
                );

        boolean revokedKeyRejected =
                authenticationService.authenticate(
                        issued.rawKey()
                ).isEmpty();

        System.out.println();
        System.out.println(
                "AeroTrace Project API Key 인증 검증"
        );
        System.out.println(
                "- 활성 Key 인증 성공: "
                        + activeResult.isPresent()
        );
        System.out.println(
                "- Tenant 일치: "
                        + tenantMatches
        );
        System.out.println(
                "- Project 일치: "
                        + projectMatches
        );
        System.out.println(
                "- 잘못된 Secret 거부: "
                        + wrongSecretRejected
        );
        System.out.println(
                "- 폐기 처리된 행 수: "
                        + revokedRows
        );
        System.out.println(
                "- 폐기된 Key 거부: "
                        + revokedKeyRejected
        );

        if (!tenantMatches) {
          throw new IllegalStateException(
                  "Authenticated Tenant does not match"
          );
        }

        if (!projectMatches) {
          throw new IllegalStateException(
                  "Authenticated Project does not match"
          );
        }

        if (!wrongSecretRejected) {
          throw new IllegalStateException(
                  "Wrong Secret was accepted"
          );
        }

        if (revokedRows != 1) {
          throw new IllegalStateException(
                  "API Key revoke update failed"
          );
        }

        if (!revokedKeyRejected) {
          throw new IllegalStateException(
                  "Revoked API Key was accepted"
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

  private static String tamperLastCharacter(
          String rawKey
  ) {
    int lastIndex =
            rawKey.length() - 1;

    char current =
            rawKey.charAt(lastIndex);

    char replacement =
            current == 'A'
                    ? 'B'
                    : 'A';

    return rawKey.substring(
            0,
            lastIndex
    ) + replacement;
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
}