package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.AeroTracePrincipal;
import com.huning.aerotrace.auth.application.GithubOAuthIdentity;
import com.huning.aerotrace.auth.application.OAuthLoginProvisioningException;
import com.huning.aerotrace.auth.application.OAuthLoginProvisioningService;
import com.huning.aerotrace.auth.application.OnboardingInviteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class AuthPhaseBProvisioningIntegrationTest {

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private OnboardingInviteService inviteService;

  @Autowired
  private OAuthLoginProvisioningService provisioningService;

  private final List<UUID> tenantIds =
          new CopyOnWriteArrayList<>();
  private final List<String> providerSubjects =
          new CopyOnWriteArrayList<>();

  @AfterEach
  void cleanUp() {
    for (UUID tenantId : tenantIds) {
      jdbcTemplate.update(
              "DELETE FROM security_audit_events WHERE tenant_id = ?",
              tenantId
      );
    }

    for (String providerSubject : providerSubjects) {
      jdbcTemplate.update(
              """
              DELETE FROM security_audit_events
              WHERE actor_user_id IN (
                  SELECT user_id
                  FROM user_identities
                  WHERE provider = 'GITHUB'
                    AND provider_subject = ?
              )
              """,
              providerSubject
      );
    }

    for (UUID tenantId : tenantIds) {
      jdbcTemplate.update(
              "DELETE FROM tenants WHERE id = ?",
              tenantId
      );
    }

    for (String providerSubject : providerSubjects) {
      jdbcTemplate.update(
              """
              DELETE FROM app_users
              WHERE id IN (
                  SELECT user_id
                  FROM user_identities
                  WHERE provider = 'GITHUB'
                    AND provider_subject = ?
              )
              """,
              providerSubject
      );
    }
  }

  @Test
  void newGithubIdentityRequiresAndConsumesInviteAtomically() {
    UUID tenantId = insertTenant();
    String providerSubject = providerSubject();
    OnboardingInviteService.IssuedOnboardingInvite invite =
            inviteService.issueBootstrap(
                    tenantId,
                    Duration.ofHours(24),
                    UUID.randomUUID()
            );
    UUID inviteId = inviteService.resolveUsableInviteId(
            invite.rawToken()
    );

    AeroTracePrincipal principal =
            provisioningService.provisionGithubLogin(
                    identity(
                            providerSubject,
                            "phase-b-new",
                            "Phase B New User"
                    ),
                    inviteId,
                    UUID.randomUUID()
            );

    assertThat(principal.getName())
            .isEqualTo(principal.userId().toString());
    assertThat(
            count(
                    """
                    SELECT COUNT(*)
                    FROM user_identities
                    WHERE provider = 'GITHUB'
                      AND provider_subject = ?
                    """,
                    providerSubject
            )
    ).isEqualTo(1);
    assertThat(
            count(
                    """
                    SELECT COUNT(*)
                    FROM tenant_memberships
                    WHERE tenant_id = ?
                      AND user_id = ?
                      AND role = 'OWNER'
                      AND status = 'ACTIVE'
                    """,
                    tenantId,
                    principal.userId()
            )
    ).isEqualTo(1);
    assertThat(
            count(
                    """
                    SELECT COUNT(*)
                    FROM onboarding_invites
                    WHERE id = ?
                      AND consumed_by_user_id = ?
                      AND consumed_at IS NOT NULL
                    """,
                    inviteId,
                    principal.userId()
            )
    ).isEqualTo(1);
    assertThat(
            count(
                    """
                    SELECT COUNT(*)
                    FROM security_audit_events
                    WHERE actor_user_id = ?
                      AND action = 'OAUTH_LOGIN_SUCCEEDED'
                      AND result = 'SUCCESS'
                    """,
                    principal.userId()
            )
    ).isEqualTo(1);
  }

  @Test
  void existingIdentityCanLoginWithoutInviteAndJoinAnotherTenant() {
    String providerSubject = providerSubject();
    UUID firstTenantId = insertTenant();
    UUID firstInviteId = issueInviteId(firstTenantId);

    AeroTracePrincipal firstLogin =
            provisioningService.provisionGithubLogin(
                    identity(
                            providerSubject,
                            "phase-b-old-login",
                            "Old Name"
                    ),
                    firstInviteId,
                    UUID.randomUUID()
            );

    AeroTracePrincipal repeatLogin =
            provisioningService.provisionGithubLogin(
                    identity(
                            providerSubject,
                            "phase-b-new-login",
                            "New Name"
                    ),
                    null,
                    UUID.randomUUID()
            );

    assertThat(repeatLogin.userId())
            .isEqualTo(firstLogin.userId());

    UUID secondTenantId = insertTenant();
    UUID secondInviteId = issueInviteId(secondTenantId);

    AeroTracePrincipal joinedLogin =
            provisioningService.provisionGithubLogin(
                    identity(
                            providerSubject,
                            "phase-b-new-login",
                            "New Name"
                    ),
                    secondInviteId,
                    UUID.randomUUID()
            );

    assertThat(joinedLogin.userId())
            .isEqualTo(firstLogin.userId());
    assertThat(
            count(
                    """
                    SELECT COUNT(*)
                    FROM tenant_memberships
                    WHERE user_id = ?
                      AND status = 'ACTIVE'
                    """,
                    firstLogin.userId()
            )
    ).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                    """
                    SELECT provider_login
                    FROM user_identities
                    WHERE provider = 'GITHUB'
                      AND provider_subject = ?
                    """,
                    String.class,
                    providerSubject
            )
    ).isEqualTo("phase-b-new-login");
  }

  @Test
  void disabledUserCannotRefreshLoginMetadata() {
    String providerSubject = providerSubject();
    UUID tenantId = insertTenant();
    AeroTracePrincipal firstLogin =
            provisioningService.provisionGithubLogin(
                    identity(
                            providerSubject,
                            "phase-b-enabled",
                            "Enabled User"
                    ),
                    issueInviteId(tenantId),
                    UUID.randomUUID()
            );

    jdbcTemplate.update(
            "UPDATE app_users SET status = 'DISABLED' WHERE id = ?",
            firstLogin.userId()
    );

    assertThatThrownBy(
            () -> provisioningService.provisionGithubLogin(
                    identity(
                            providerSubject,
                            "phase-b-disabled-change",
                            "Disabled User"
                    ),
                    null,
                    UUID.randomUUID()
            )
    ).isInstanceOf(OAuthLoginProvisioningException.class)
            .hasMessage("OAuth login is invalid or unavailable");

    assertThat(
            jdbcTemplate.queryForObject(
                    """
                    SELECT provider_login
                    FROM user_identities
                    WHERE provider = 'GITHUB'
                      AND provider_subject = ?
                    """,
                    String.class,
                    providerSubject
            )
    ).isEqualTo("phase-b-enabled");
  }

  @Test
  void invalidInviteRollsBackNewUserAndIdentity() {
    String providerSubject = providerSubject();

    assertThatThrownBy(
            () -> provisioningService.provisionGithubLogin(
                    identity(
                            providerSubject,
                            "phase-b-invalid-invite",
                            "Invalid Invite"
                    ),
                    UUID.randomUUID(),
                    UUID.randomUUID()
            )
    ).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invite is invalid or unavailable");

    assertThat(
            count(
                    """
                    SELECT COUNT(*)
                    FROM user_identities
                    WHERE provider = 'GITHUB'
                      AND provider_subject = ?
                    """,
                    providerSubject
            )
    ).isZero();
  }

  @Test
  void concurrentSameIdentityAndInviteCreatesOnlyOneUser()
          throws Exception {
    String providerSubject = providerSubject();
    UUID tenantId = insertTenant();
    UUID inviteId = issueInviteId(tenantId);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    Callable<Boolean> attempt = () -> {
      ready.countDown();

      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException(
                "Timed out waiting for concurrent OAuth login"
        );
      }

      try {
        provisioningService.provisionGithubLogin(
                identity(
                        providerSubject,
                        "phase-b-concurrent",
                        "Concurrent User"
                ),
                inviteId,
                UUID.randomUUID()
        );
        return true;
      } catch (RuntimeException exception) {
        return false;
      }
    };

    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<Boolean> first = executor.submit(attempt);
      Future<Boolean> second = executor.submit(attempt);

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(
              List.of(
                      first.get(10, TimeUnit.SECONDS),
                      second.get(10, TimeUnit.SECONDS)
              )
      ).containsExactlyInAnyOrder(true, false);
    } finally {
      executor.shutdownNow();
      assertThat(
              executor.awaitTermination(10, TimeUnit.SECONDS)
      ).isTrue();
    }

    assertThat(
            count(
                    """
                    SELECT COUNT(*)
                    FROM user_identities
                    WHERE provider = 'GITHUB'
                      AND provider_subject = ?
                    """,
                    providerSubject
            )
    ).isEqualTo(1);
    assertThat(
            count(
                    """
                    SELECT COUNT(*)
                    FROM tenant_memberships
                    WHERE tenant_id = ?
                      AND status = 'ACTIVE'
                    """,
                    tenantId
            )
    ).isEqualTo(1);
  }

  private UUID insertTenant() {
    UUID tenantId = UUID.randomUUID();
    tenantIds.add(tenantId);

    jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug)
            VALUES (?, ?, ?)
            """,
            tenantId,
            "Phase B Tenant " + tenantId,
            "phase-b-" + tenantId
    );

    return tenantId;
  }

  private UUID issueInviteId(UUID tenantId) {
    OnboardingInviteService.IssuedOnboardingInvite invite =
            inviteService.issueBootstrap(
                    tenantId,
                    Duration.ofHours(24),
                    UUID.randomUUID()
            );

    return inviteService.resolveUsableInviteId(
            invite.rawToken()
    );
  }

  private String providerSubject() {
    String subject = Long.toUnsignedString(
            UUID.randomUUID().getMostSignificantBits()
    );
    providerSubjects.add(subject);
    return subject;
  }

  private GithubOAuthIdentity identity(
          String providerSubject,
          String login,
          String displayName
  ) {
    return new GithubOAuthIdentity(
            providerSubject,
            login,
            displayName,
            "https://avatars.githubusercontent.com/u/"
                    + providerSubject
    );
  }

  private int count(
          String sql,
          Object... arguments
  ) {
    Integer value = jdbcTemplate.queryForObject(
            sql,
            Integer.class,
            arguments
    );

    return value == null ? 0 : value;
  }
}
