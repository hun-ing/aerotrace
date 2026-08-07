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
import java.util.regex.Pattern;

public final class ProjectApiKeyProvisioner {

  private static final String TENANT_NAME_ENV =
          "AEROTRACE_PROVISION_TENANT_NAME";

  private static final String TENANT_SLUG_ENV =
          "AEROTRACE_PROVISION_TENANT_SLUG";

  private static final String PROJECT_NAME_ENV =
          "AEROTRACE_PROVISION_PROJECT_NAME";

  private static final String PROJECT_SLUG_ENV =
          "AEROTRACE_PROVISION_PROJECT_SLUG";

  private static final String API_KEY_NAME_ENV =
          "AEROTRACE_PROVISION_API_KEY_NAME";

  private static final String EXPIRATION_DAYS_ENV =
          "AEROTRACE_PROVISION_API_KEY_EXPIRATION_DAYS";

  private static final Pattern SLUG_PATTERN =
          Pattern.compile(
                  "^[a-z0-9]+(?:-[a-z0-9]+)*$"
          );

  private static final int MAX_NAME_LENGTH = 100;

  private static final int MAX_EXPIRATION_DAYS = 3650;

  private ProjectApiKeyProvisioner() {
  }

  public static void main(
          String[] args
  ) {
    ProvisioningRequest request =
            readProvisioningRequest();

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

      UUID tenantId =
              ensureTenant(
                      jdbcTemplate,
                      request.tenantName(),
                      request.tenantSlug()
              );

      UUID projectId =
              ensureProject(
                      jdbcTemplate,
                      tenantId,
                      request.projectName(),
                      request.projectSlug()
              );

      assertNoActiveApiKey(
              jdbcTemplate,
              tenantId,
              projectId,
              request.apiKeyName()
      );

      ProjectApiKeyIssueService issueService =
              context.getBean(
                      ProjectApiKeyIssueService.class
              );

      Instant expiresAt =
              Instant.now().plus(
                      request.expirationDays(),
                      ChronoUnit.DAYS
              );

      IssuedProjectApiKey issued =
              issueService.issue(
                      tenantId,
                      projectId,
                      request.apiKeyName(),
                      expiresAt
              );

      System.out.println(
              "AEROTRACE_PROVISIONED_TENANT_ID="
                      + tenantId
      );

      System.out.println(
              "AEROTRACE_PROVISIONED_PROJECT_ID="
                      + projectId
      );

      System.out.println(
              "AEROTRACE_PROVISIONED_API_KEY_ID="
                      + issued.id()
      );

      System.out.println(
              "AEROTRACE_PROVISIONED_KEY_ID="
                      + issued.keyId()
      );

      System.out.println(
              "AEROTRACE_PROVISIONED_EXPIRES_AT="
                      + issued.expiresAt()
      );

      /*
       * 원문 API Key는 이 실행에서 한 번만 출력된다.
       * DB에는 Secret Hash만 저장된다.
       */
      System.out.println(
              "AEROTRACE_PROVISIONED_API_KEY="
                      + issued.rawKey()
      );
    }
  }

  private static ProvisioningRequest readProvisioningRequest() {
    String tenantName =
            requiredName(
                    TENANT_NAME_ENV
            );

    String tenantSlug =
            requiredSlug(
                    TENANT_SLUG_ENV
            );

    String projectName =
            requiredName(
                    PROJECT_NAME_ENV
            );

    String projectSlug =
            requiredSlug(
                    PROJECT_SLUG_ENV
            );

    String apiKeyName =
            requiredName(
                    API_KEY_NAME_ENV
            );

    int expirationDays =
            requiredExpirationDays();

    return new ProvisioningRequest(
            tenantName,
            tenantSlug,
            projectName,
            projectSlug,
            apiKeyName,
            expirationDays
    );
  }

  private static String requiredName(
          String environmentName
  ) {
    String value =
            requiredEnvironmentValue(
                    environmentName
            );

    if (value.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
              environmentName
                      + " must not exceed "
                      + MAX_NAME_LENGTH
                      + " characters"
      );
    }

    return value;
  }

  private static String requiredSlug(
          String environmentName
  ) {
    String value =
            requiredEnvironmentValue(
                    environmentName
            );

    if (
            value.length() > MAX_NAME_LENGTH
                    || !SLUG_PATTERN
                    .matcher(value)
                    .matches()
    ) {
      throw new IllegalArgumentException(
              environmentName
                      + " must match "
                      + SLUG_PATTERN.pattern()
      );
    }

    return value;
  }

  private static int requiredExpirationDays() {
    String rawValue =
            requiredEnvironmentValue(
                    EXPIRATION_DAYS_ENV
            );

    final int expirationDays;

    try {
      expirationDays =
              Integer.parseInt(
                      rawValue
              );
    } catch (
            NumberFormatException exception
    ) {
      throw new IllegalArgumentException(
              EXPIRATION_DAYS_ENV
                      + " must be an integer",
              exception
      );
    }

    if (
            expirationDays < 1
                    || expirationDays
                    > MAX_EXPIRATION_DAYS
    ) {
      throw new IllegalArgumentException(
              EXPIRATION_DAYS_ENV
                      + " must be between 1 and "
                      + MAX_EXPIRATION_DAYS
      );
    }

    return expirationDays;
  }

  private static String requiredEnvironmentValue(
          String environmentName
  ) {
    String value =
            System.getenv(
                    environmentName
            );

    if (
            value == null
                    || value.isBlank()
    ) {
      throw new IllegalArgumentException(
              "Missing required environment variable: "
                      + environmentName
      );
    }

    return value.trim();
  }

  private static UUID ensureTenant(
          JdbcTemplate jdbcTemplate,
          String tenantName,
          String tenantSlug
  ) {
    jdbcTemplate.update(
            """
            INSERT INTO tenants (
                name,
                slug
            )
            VALUES (?, ?)
            ON CONFLICT (slug)
            DO NOTHING
            """,
            tenantName,
            tenantSlug
    );

    NamedResource tenant =
            jdbcTemplate.queryForObject(
                    """
                    SELECT id,
                           name
                    FROM tenants
                    WHERE slug = ?
                    """,
                    (
                            resultSet,
                            rowNumber
                    ) ->
                            new NamedResource(
                                    resultSet.getObject(
                                            "id",
                                            UUID.class
                                    ),
                                    resultSet.getString(
                                            "name"
                                    )
                            ),
                    tenantSlug
            );

    if (tenant == null) {
      throw new IllegalStateException(
              "Tenant could not be created or loaded"
      );
    }

    assertExistingNameMatches(
            "Tenant",
            tenantName,
            tenant.name()
    );

    return tenant.id();
  }

  private static UUID ensureProject(
          JdbcTemplate jdbcTemplate,
          UUID tenantId,
          String projectName,
          String projectSlug
  ) {
    jdbcTemplate.update(
            """
            INSERT INTO projects (
                tenant_id,
                name,
                slug
            )
            VALUES (?, ?, ?)
            ON CONFLICT (
                tenant_id,
                slug
            )
            DO NOTHING
            """,
            tenantId,
            projectName,
            projectSlug
    );

    NamedResource project =
            jdbcTemplate.queryForObject(
                    """
                    SELECT id,
                           name
                    FROM projects
                    WHERE tenant_id = ?
                      AND slug = ?
                    """,
                    (
                            resultSet,
                            rowNumber
                    ) ->
                            new NamedResource(
                                    resultSet.getObject(
                                            "id",
                                            UUID.class
                                    ),
                                    resultSet.getString(
                                            "name"
                                    )
                            ),
                    tenantId,
                    projectSlug
            );

    if (project == null) {
      throw new IllegalStateException(
              "Project could not be created or loaded"
      );
    }

    assertExistingNameMatches(
            "Project",
            projectName,
            project.name()
    );

    return project.id();
  }

  private static void assertExistingNameMatches(
          String resourceType,
          String requestedName,
          String existingName
  ) {
    if (!requestedName.equals(existingName)) {
      throw new IllegalStateException(
              resourceType
                      + " slug already exists with "
                      + "a different name. Existing name: "
                      + existingName
      );
    }
  }

  private static void assertNoActiveApiKey(
          JdbcTemplate jdbcTemplate,
          UUID tenantId,
          UUID projectId,
          String apiKeyName
  ) {
    Integer activeKeyCount =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM project_api_keys
                    WHERE tenant_id = ?
                      AND project_id = ?
                      AND name = ?
                      AND revoked_at IS NULL
                      AND (
                          expires_at IS NULL
                          OR expires_at > CURRENT_TIMESTAMP
                      )
                    """,
                    Integer.class,
                    tenantId,
                    projectId,
                    apiKeyName
            );

    if (
            activeKeyCount != null
                    && activeKeyCount > 0
    ) {
      throw new IllegalStateException(
              "An active API Key already exists "
                      + "with this name: "
                      + apiKeyName
      );
    }
  }

  private record NamedResource(
          UUID id,
          String name
  ) {
  }

  private record ProvisioningRequest(
          String tenantName,
          String tenantSlug,
          String projectName,
          String projectSlug,
          String apiKeyName,
          int expirationDays
  ) {
  }
}