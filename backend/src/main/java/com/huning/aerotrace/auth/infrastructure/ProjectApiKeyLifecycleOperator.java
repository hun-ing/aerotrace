package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.BackendApplication;
import com.huning.aerotrace.auth.application.IssuedProjectApiKey;
import com.huning.aerotrace.auth.application.ProjectApiKeyLifecycleIssueService;
import com.huning.aerotrace.auth.application.ProjectApiKeyLifecycleService;
import com.huning.aerotrace.auth.application.ProjectApiKeyLifecycleService.ProjectApiKeyInventory;
import com.huning.aerotrace.auth.application.ProjectApiKeyLifecycleService.ProjectApiKeyMetadata;
import com.huning.aerotrace.auth.application.ProjectApiKeyLifecycleService.ProjectApiKeyRevocation;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ProjectApiKeyLifecycleOperator {

  private static final String ACTION_ENV =
          "AEROTRACE_API_KEY_ACTION";

  private static final String TENANT_SLUG_ENV =
          "AEROTRACE_API_KEY_TENANT_SLUG";

  private static final String PROJECT_SLUG_ENV =
          "AEROTRACE_API_KEY_PROJECT_SLUG";

  private static final String API_KEY_ID_ENV =
          "AEROTRACE_API_KEY_ID";

  private static final String API_KEY_NAME_ENV =
          "AEROTRACE_API_KEY_NAME";

  private static final String EXPIRATION_DAYS_ENV =
          "AEROTRACE_API_KEY_EXPIRATION_DAYS";

  private static final String EXPECTED_NAME_ENV =
          "AEROTRACE_API_KEY_EXPECTED_NAME";

  private static final String CONFIRM_REVOKE_ENV =
          "AEROTRACE_API_KEY_CONFIRM_REVOKE";

  private static final String ALLOW_LAST_ACTIVE_ENV =
          "AEROTRACE_API_KEY_ALLOW_LAST_ACTIVE";

  private static final String REVOKE_CONFIRMATION =
          "REVOKE";

  private static final Pattern SLUG_PATTERN =
          Pattern.compile(
                  "^[a-z0-9]+(?:-[a-z0-9]+)*$"
          );

  private static final int MAX_SLUG_LENGTH = 100;

  private static final int MAX_NAME_LENGTH = 100;

  private static final int MAX_EXPIRATION_DAYS = 3650;

  private ProjectApiKeyLifecycleOperator() {
  }

  public static void main(
          String[] args
  ) {
    LifecycleRequest request =
            readRequest();

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
      ProjectReference project =
              resolveProject(
                      context.getBean(
                              JdbcTemplate.class
                      ),
                      request.tenantSlug(),
                      request.projectSlug()
              );

      ProjectApiKeyLifecycleService service =
              context.getBean(
                      ProjectApiKeyLifecycleService.class
              );

      switch (request.action()) {
        case LIST -> printInventory(
                service.list(
                        project.tenantId(),
                        project.projectId()
                )
        );
        case ISSUE -> issue(
                context,
                project,
                request
        );
        case REVOKE -> printRevocation(
                service.revoke(
                        project.tenantId(),
                        project.projectId(),
                        request.apiKeyId(),
                        request.expectedName(),
                        request.allowLastActive()
                )
        );
      }
    }
  }

  private static LifecycleRequest readRequest() {
    LifecycleAction action =
            requiredAction();

    String tenantSlug =
            requiredSlug(
                    TENANT_SLUG_ENV
            );

    String projectSlug =
            requiredSlug(
                    PROJECT_SLUG_ENV
            );

    if (action == LifecycleAction.LIST) {
      return new LifecycleRequest(
              action,
              tenantSlug,
              projectSlug,
              null,
              null,
              null,
              null,
              false
      );
    }

    if (action == LifecycleAction.ISSUE) {
      return new LifecycleRequest(
              action,
              tenantSlug,
              projectSlug,
              null,
              requiredName(
                      API_KEY_NAME_ENV
              ),
              requiredExpirationDays(),
              null,
              false
      );
    }

    UUID apiKeyId =
            requiredUuid(
                    API_KEY_ID_ENV
            );

    String expectedName =
            requiredEnvironmentValue(
                    EXPECTED_NAME_ENV
            );

    String confirmation =
            requiredEnvironmentValue(
                    CONFIRM_REVOKE_ENV
            );

    if (!REVOKE_CONFIRMATION.equals(confirmation)) {
      throw new IllegalArgumentException(
              CONFIRM_REVOKE_ENV
                      + " must be exactly "
                      + REVOKE_CONFIRMATION
      );
    }

    return new LifecycleRequest(
            action,
            tenantSlug,
            projectSlug,
            apiKeyId,
            null,
            null,
            expectedName,
            optionalBoolean(
                    ALLOW_LAST_ACTIVE_ENV
            )
    );
  }

  private static LifecycleAction requiredAction() {
    String rawAction =
            requiredEnvironmentValue(
                    ACTION_ENV
            ).toUpperCase(
                    Locale.ROOT
            );

    try {
      return LifecycleAction.valueOf(
              rawAction
      );
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
              ACTION_ENV
                      + " must be list, issue, or revoke",
              exception
      );
    }
  }

  private static String requiredSlug(
          String environmentName
  ) {
    String value =
            requiredEnvironmentValue(
                    environmentName
            );

    if (
            value.length() > MAX_SLUG_LENGTH
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

  private static UUID requiredUuid(
          String environmentName
  ) {
    try {
      return UUID.fromString(
              requiredEnvironmentValue(
                      environmentName
              )
      );
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
              environmentName
                      + " must be a UUID",
              exception
      );
    }
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
    } catch (NumberFormatException exception) {
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

  private static boolean optionalBoolean(
          String environmentName
  ) {
    String value =
            System.getenv(
                    environmentName
            );

    if (value == null || value.isBlank()) {
      return false;
    }

    String normalized =
            value.trim();

    if ("true".equals(normalized)) {
      return true;
    }

    if ("false".equals(normalized)) {
      return false;
    }

    throw new IllegalArgumentException(
            environmentName
                    + " must be true or false"
    );
  }

  private static String requiredEnvironmentValue(
          String environmentName
  ) {
    String value =
            System.getenv(
                    environmentName
            );

    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
              "Missing required environment variable: "
                      + environmentName
      );
    }

    return value.trim();
  }

  private static ProjectReference resolveProject(
          JdbcTemplate jdbcTemplate,
          String tenantSlug,
          String projectSlug
  ) {
    ProjectReference project =
            DataAccessUtils.singleResult(
                    jdbcTemplate.query(
                            """
                            SELECT tenants.id AS tenant_id,
                                   projects.id AS project_id
                            FROM tenants
                            JOIN projects
                              ON projects.tenant_id = tenants.id
                            WHERE tenants.slug = ?
                              AND projects.slug = ?
                            """,
                            (
                                    resultSet,
                                    rowNumber
                            ) -> new ProjectReference(
                                    resultSet.getObject(
                                            "tenant_id",
                                            UUID.class
                                    ),
                                    resultSet.getObject(
                                            "project_id",
                                            UUID.class
                                    )
                            ),
                            tenantSlug,
                            projectSlug
                    )
            );

    if (project == null) {
      throw new IllegalArgumentException(
              "The requested tenant/project was not found"
      );
    }

    return project;
  }

  private static void printInventory(
          ProjectApiKeyInventory inventory
  ) {
    System.out.println(
            "AEROTRACE_API_KEY_LIST_RESULT=OK"
    );

    System.out.println(
            "AEROTRACE_API_KEY_LISTED_AT="
                    + inventory.listedAt()
    );

    System.out.println(
            "AEROTRACE_API_KEY_COUNT="
                    + inventory.keys().size()
    );

    System.out.println(
            "AEROTRACE_API_KEY_ACTIVE_COUNT="
                    + inventory.activeKeyCount()
    );

    for (int index = 0; index < inventory.keys().size(); index++) {
      printMetadata(
              "AEROTRACE_API_KEY_" + (index + 1),
              inventory.keys().get(index)
      );
    }
  }

  private static void issue(
          ConfigurableApplicationContext context,
          ProjectReference project,
          LifecycleRequest request
  ) {
    IssuedProjectApiKey issued =
            context.getBean(
                    ProjectApiKeyLifecycleIssueService.class
            ).issueForExistingProject(
                    project.tenantId(),
                    project.projectId(),
                    request.apiKeyName(),
                    Instant.now().plus(
                            request.expirationDays(),
                            ChronoUnit.DAYS
                    )
            );

    System.out.println(
            "AEROTRACE_API_KEY_ISSUE_RESULT=ISSUED"
    );

    System.out.println(
            "AEROTRACE_API_KEY_ISSUED_ID="
                    + issued.id()
    );

    System.out.println(
            "AEROTRACE_API_KEY_ISSUED_NAME_BASE64URL="
                    + encodeName(issued.name())
    );

    System.out.println(
            "AEROTRACE_API_KEY_ISSUED_CREATED_AT="
                    + issued.createdAt()
    );

    System.out.println(
            "AEROTRACE_API_KEY_ISSUED_EXPIRES_AT="
                    + issued.expiresAt()
    );

    /*
     * 원문 API Key는 발급 실행에서 한 번만 출력하며
     * DB에는 Secret Hash만 저장된다.
     */
    System.out.println(
            "AEROTRACE_API_KEY="
                    + issued.rawKey()
    );
  }

  private static void printRevocation(
          ProjectApiKeyRevocation revocation
  ) {
    System.out.println(
            "AEROTRACE_API_KEY_REVOKE_RESULT="
                    + revocation.result()
    );

    printMetadata(
            "AEROTRACE_API_KEY_REVOKED",
            revocation.apiKey()
    );

    System.out.println(
            "AEROTRACE_API_KEY_ACTIVE_COUNT_AFTER="
                    + revocation.activeKeyCountAfter()
    );
  }

  private static void printMetadata(
          String prefix,
          ProjectApiKeyMetadata metadata
  ) {
    System.out.println(
            prefix
                    + "_ID="
                    + metadata.id()
    );

    System.out.println(
            prefix
                    + "_NAME_BASE64URL="
                    + encodeName(metadata.name())
    );

    System.out.println(
            prefix
                    + "_STATUS="
                    + metadata.status()
    );

    System.out.println(
            prefix
                    + "_CREATED_AT="
                    + metadata.createdAt()
    );

    System.out.println(
            prefix
                    + "_EXPIRES_AT="
                    + nullableInstant(
                            metadata.expiresAt(),
                            "NEVER"
                    )
    );

    System.out.println(
            prefix
                    + "_REVOKED_AT="
                    + nullableInstant(
                            metadata.revokedAt(),
                            "NONE"
                    )
    );
  }

  private static String encodeName(
          String name
  ) {
    return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                    name.getBytes(
                            StandardCharsets.UTF_8
                    )
            );
  }

  private static String nullableInstant(
          Object value,
          String nullMarker
  ) {
    return value == null
            ? nullMarker
            : value.toString();
  }

  private enum LifecycleAction {
    LIST,
    ISSUE,
    REVOKE
  }

  private record LifecycleRequest(
          LifecycleAction action,
          String tenantSlug,
          String projectSlug,
          UUID apiKeyId,
          String apiKeyName,
          Integer expirationDays,
          String expectedName,
          boolean allowLastActive
  ) {
  }

  private record ProjectReference(
          UUID tenantId,
          UUID projectId
  ) {
  }
}
