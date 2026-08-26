package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.BackendApplication;
import com.huning.aerotrace.auth.application.OnboardingInviteService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class BootstrapInviteRevocationOperator {

  private static final String TENANT_SLUG_ENV =
          "AEROTRACE_BOOTSTRAP_TENANT_SLUG";

  private static final String INVITE_ID_ENV =
          "AEROTRACE_BOOTSTRAP_INVITE_ID";

  private static final String CONFIRM_ENV =
          "AEROTRACE_BOOTSTRAP_INVITE_CONFIRM";

  private static final Pattern SLUG_PATTERN =
          Pattern.compile(
                  "^[a-z0-9]+(?:-[a-z0-9]+)*$"
          );

  private BootstrapInviteRevocationOperator() {
  }

  public static void main(
          String[] args
  ) {
    RevocationRequest request = readRequest();

    try (
            ConfigurableApplicationContext context =
                    new SpringApplicationBuilder(
                            BackendApplication.class
                    )
                            .web(WebApplicationType.NONE)
                            .properties(
                                    "spring.main.banner-mode=off",
                                    "logging.level.root=WARN"
                            )
                            .run(args)
    ) {
      UUID tenantId =
              resolveTenantId(
                      context.getBean(JdbcTemplate.class),
                      request.tenantSlug()
              );

      OnboardingInviteService.BootstrapInviteRevocation result =
              context.getBean(OnboardingInviteService.class)
                      .revokeBootstrap(
                              tenantId,
                              request.inviteId(),
                              UUID.randomUUID()
                      );

      System.out.println(
              "AEROTRACE_BOOTSTRAP_INVITE_REVOKE_RESULT="
                      + result.result()
      );

      System.out.println(
              "AEROTRACE_BOOTSTRAP_INVITE_ID="
                      + result.inviteId()
      );

      System.out.println(
              "AEROTRACE_BOOTSTRAP_INVITE_REVOKED_AT="
                      + result.revokedAt()
      );
    }
  }

  private static RevocationRequest readRequest() {
    String tenantSlug =
            requiredEnvironmentValue(TENANT_SLUG_ENV);

    if (!SLUG_PATTERN.matcher(tenantSlug).matches()) {
      throw new IllegalArgumentException(
              TENANT_SLUG_ENV
                      + " must match "
                      + SLUG_PATTERN.pattern()
      );
    }

    UUID inviteId;

    try {
      inviteId = UUID.fromString(
              requiredEnvironmentValue(INVITE_ID_ENV)
      );
    } catch (
            IllegalArgumentException exception
    ) {
      throw new IllegalArgumentException(
              INVITE_ID_ENV + " must be a UUID",
              exception
      );
    }

    if (
            !"REVOKE".equals(
                    requiredEnvironmentValue(CONFIRM_ENV)
            )
    ) {
      throw new IllegalArgumentException(
              CONFIRM_ENV + " must equal REVOKE"
      );
    }

    return new RevocationRequest(
            tenantSlug,
            inviteId
    );
  }

  private static UUID resolveTenantId(
          JdbcTemplate jdbcTemplate,
          String tenantSlug
  ) {
    List<UUID> tenantIds =
            jdbcTemplate.query(
                    """
                    SELECT id
                    FROM tenants
                    WHERE slug = ?
                    """,
                    (
                            resultSet,
                            rowNumber
                    ) -> resultSet.getObject(
                            "id",
                            UUID.class
                    ),
                    tenantSlug
            );

    if (tenantIds.size() != 1) {
      throw new IllegalArgumentException(
              "The requested tenant was not found"
      );
    }

    return tenantIds.getFirst();
  }

  private static String requiredEnvironmentValue(
          String environmentName
  ) {
    String value = System.getenv(environmentName);

    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
              "Missing required environment variable: "
                      + environmentName
      );
    }

    return value.trim();
  }

  private record RevocationRequest(
          String tenantSlug,
          UUID inviteId
  ) {
  }
}
