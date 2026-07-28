package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.BackendApplication;
import com.huning.aerotrace.auth.application.IssuedProjectApiKey;
import com.huning.aerotrace.auth.application.ProjectApiKeyIssueService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public final class LocalProjectApiKeySeeder {

  private static final UUID TENANT_ID =
          UUID.fromString(
                  "11111111-1111-1111-1111-111111111111"
          );

  private static final UUID PROJECT_ID =
          UUID.fromString(
                  "22222222-2222-2222-2222-222222222222"
          );

  private static final String KEY_NAME =
          "local-http-auth";

  private LocalProjectApiKeySeeder() {
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

      int deletedExistingRows =
              jdbcTemplate.update(
                      """
                      DELETE FROM project_api_keys
                      WHERE tenant_id = ?
                        AND project_id = ?
                        AND name = ?
                      """,
                      TENANT_ID,
                      PROJECT_ID,
                      KEY_NAME
              );

      ProjectApiKeyIssueService issueService =
              context.getBean(
                      ProjectApiKeyIssueService.class
              );

      IssuedProjectApiKey issued =
              issueService.issue(
                      TENANT_ID,
                      PROJECT_ID,
                      KEY_NAME,
                      Instant.now().plus(
                              30,
                              ChronoUnit.DAYS
                      )
              );

      System.out.println();
      System.out.println(
              "AeroTrace 로컬 HTTP 인증 Key 발급"
      );
      System.out.println(
              "- 기존 Key 정리 행 수: "
                      + deletedExistingRows
      );
      System.out.println(
              "- API Key row ID: "
                      + issued.id()
      );
      System.out.println(
              "- keyId: "
                      + issued.keyId()
      );

      /*
       * 테스트 소스에서 로컬 검증을 위해 한 번만 출력한다.
       * 문서, Git, 운영 로그에 저장하면 안 된다.
       */
      System.out.println(
              "- 원문 API Key: "
                      + issued.rawKey()
      );
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
}