package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.BackendApplication;
import com.huning.aerotrace.auth.application.OnboardingInviteService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class BootstrapInviteOperator {

  private static final String TENANT_SLUG_ENV =
          "AEROTRACE_BOOTSTRAP_TENANT_SLUG";

  private static final String EXPIRATION_HOURS_ENV =
          "AEROTRACE_BOOTSTRAP_INVITE_EXPIRATION_HOURS";

  private static final String CONFIRM_ENV =
          "AEROTRACE_BOOTSTRAP_INVITE_CONFIRM";

  private static final Pattern SLUG_PATTERN =
          Pattern.compile(
                  "^[a-z0-9]+(?:-[a-z0-9]+)*$"
          );

  private static final int DEFAULT_EXPIRATION_HOURS = 24;
  private static final int MAX_EXPIRATION_HOURS = 168;

  private BootstrapInviteOperator() {
  }

  public static void main(
          String[] args
  ) {
    BootstrapRequest request = readRequest();

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
      JdbcTemplate jdbcTemplate =
              context.getBean(JdbcTemplate.class);

      UUID tenantId =
              resolveTenantId(
                      jdbcTemplate,
                      request.tenantSlug()
              );

      OnboardingInviteService inviteService =
              context.getBean(
                      OnboardingInviteService.class
              );

      OnboardingInviteService.IssuedOnboardingInvite issued =
              inviteService.issueBootstrap(
                      tenantId,
                      Duration.ofHours(
                              request.expirationHours()
                      ),
                      UUID.randomUUID()
              );

      System.out.println(
              "AEROTRACE_BOOTSTRAP_INVITE_RESULT=ISSUED"
      );

      System.out.println(
              "AEROTRACE_BOOTSTRAP_INVITE_TENANT_ID="
                      + issued.tenantId()
      );

      System.out.println(
              "AEROTRACE_BOOTSTRAP_INVITE_ID="
                      + issued.id()
      );

      System.out.println(
              "AEROTRACE_BOOTSTRAP_INVITE_EXPIRES_AT="
                      + issued.expiresAt()
      );

      /*
       * 원문 invite는 이 실행에서 한 번만 출력된다.
       * DB와 audit event에는 SHA-256 hash 또는 metadata만 저장된다.
       */
      System.out.println(
              "AEROTRACE_BOOTSTRAP_INVITE="
                      + issued.rawToken()
      );
    }
  }

  private static BootstrapRequest readRequest() {
    String tenantSlug =
            requiredEnvironmentValue(
                    TENANT_SLUG_ENV
            );

    if (!SLUG_PATTERN.matcher(tenantSlug).matches()) {
      throw new IllegalArgumentException(
              TENANT_SLUG_ENV
                      + " must match "
                      + SLUG_PATTERN.pattern()
      );
    }

    String confirmation =
            requiredEnvironmentValue(CONFIRM_ENV);

    if (!"ISSUE".equals(confirmation)) {
      throw new IllegalArgumentException(
              CONFIRM_ENV + " must equal ISSUE"
      );
    }

    return new BootstrapRequest(
            tenantSlug,
            expirationHours()
    );
  }

  private static int expirationHours() {
    String rawValue =
            System.getenv(EXPIRATION_HOURS_ENV);

    if (rawValue == null || rawValue.isBlank()) {
      return DEFAULT_EXPIRATION_HOURS;
    }

    final int hours;

    try {
      hours = Integer.parseInt(rawValue.trim());
    } catch (
            NumberFormatException exception
    ) {
      throw new IllegalArgumentException(
              EXPIRATION_HOURS_ENV
                      + " must be an integer",
              exception
      );
    }

    if (hours < 1 || hours > MAX_EXPIRATION_HOURS) {
      throw new IllegalArgumentException(
              EXPIRATION_HOURS_ENV
                      + " must be between 1 and "
                      + MAX_EXPIRATION_HOURS
      );
    }

    return hours;
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

  private record BootstrapRequest(
          String tenantSlug,
          int expirationHours
  ) {
  }
}
