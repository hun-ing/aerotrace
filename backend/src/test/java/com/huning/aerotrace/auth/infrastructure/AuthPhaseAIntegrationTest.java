package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.AuthorizationDecision;
import com.huning.aerotrace.auth.application.MembershipPermissionService;
import com.huning.aerotrace.auth.application.OnboardingInviteService;
import com.huning.aerotrace.auth.application.OnboardingInviteTokenService;
import com.huning.aerotrace.auth.application.TenantMembershipService;
import com.huning.aerotrace.auth.application.TenantPermission;
import com.huning.aerotrace.auth.application.TenantRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
class AuthPhaseAIntegrationTest {

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private MembershipPermissionService permissionService;

  @Autowired
  private OnboardingInviteService inviteService;

  @Autowired
  private OnboardingInviteTokenService inviteTokenService;

  @Autowired
  private TenantMembershipService membershipService;

  private UUID tenantAId;
  private UUID tenantBId;
  private UUID projectAId;
  private UUID projectBId;
  private UUID firstUserId;
  private UUID secondUserId;

  @BeforeEach
  void setUp() {
    tenantAId = UUID.randomUUID();
    tenantBId = UUID.randomUUID();
    projectAId = UUID.randomUUID();
    projectBId = UUID.randomUUID();
    firstUserId = UUID.randomUUID();
    secondUserId = UUID.randomUUID();

    insertTenant(
            tenantAId,
            "Auth Integration Tenant A"
    );

    insertTenant(
            tenantBId,
            "Auth Integration Tenant B"
    );

    insertProject(
            projectAId,
            tenantAId,
            "Auth Integration Project A"
    );

    insertProject(
            projectBId,
            tenantBId,
            "Auth Integration Project B"
    );

    insertUser(
            firstUserId,
            "Auth Integration User A"
    );

    insertUser(
            secondUserId,
            "Auth Integration User B"
    );
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update(
            """
            DELETE FROM security_audit_events
            WHERE tenant_id IN (?, ?)
               OR actor_user_id IN (?, ?)
            """,
            tenantAId,
            tenantBId,
            firstUserId,
            secondUserId
    );

    jdbcTemplate.update(
            "DELETE FROM projects WHERE id IN (?, ?)",
            projectAId,
            projectBId
    );

    jdbcTemplate.update(
            "DELETE FROM tenants WHERE id IN (?, ?)",
            tenantAId,
            tenantBId
    );

    jdbcTemplate.update(
            "DELETE FROM app_users WHERE id IN (?, ?)",
            firstUserId,
            secondUserId
    );
  }

  @Test
  void authorizationRequiresActiveUserMembershipAndProjectOwnership() {
    insertMembership(
            tenantAId,
            firstUserId,
            TenantRole.VIEWER
    );

    AuthorizationDecision ownProject =
            permissionService.authorizeProject(
                    firstUserId,
                    tenantAId,
                    projectAId,
                    TenantPermission.PROJECT_TRACE_READ
            );

    AuthorizationDecision crossTenantProject =
            permissionService.authorizeProject(
                    firstUserId,
                    tenantAId,
                    projectBId,
                    TenantPermission.PROJECT_TRACE_READ
            );

    AuthorizationDecision writeAttempt =
            permissionService.authorizeProject(
                    firstUserId,
                    tenantAId,
                    projectAId,
                    TenantPermission.PROJECT_MANAGE
            );

    assertThat(ownProject.outcome())
            .isEqualTo(
                    AuthorizationDecision.AuthorizationOutcome.ALLOWED
            );

    assertThat(crossTenantProject.outcome())
            .isEqualTo(
                    AuthorizationDecision.AuthorizationOutcome.NOT_FOUND
            );

    assertThat(writeAttempt.outcome())
            .isEqualTo(
                    AuthorizationDecision.AuthorizationOutcome.FORBIDDEN
            );

    jdbcTemplate.update(
            "UPDATE app_users SET status = 'DISABLED' WHERE id = ?",
            firstUserId
    );

    assertThat(
            permissionService.authorizeProject(
                    firstUserId,
                    tenantAId,
                    projectAId,
                    TenantPermission.PROJECT_TRACE_READ
            ).outcome()
    ).isEqualTo(
            AuthorizationDecision.AuthorizationOutcome.NOT_FOUND
    );

    jdbcTemplate.update(
            "UPDATE app_users SET status = 'ACTIVE' WHERE id = ?",
            firstUserId
    );

    jdbcTemplate.update(
            """
            UPDATE tenant_memberships
            SET status = 'REVOKED',
                revoked_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = ?
              AND user_id = ?
            """,
            tenantAId,
            firstUserId
    );

    assertThat(
            permissionService.authorizeProject(
                    firstUserId,
                    tenantAId,
                    projectAId,
                    TenantPermission.PROJECT_TRACE_READ
            ).outcome()
    ).isEqualTo(
            AuthorizationDecision.AuthorizationOutcome.NOT_FOUND
    );
  }

  @Test
  void concurrentInviteConsumptionCreatesExactlyOneMembership()
          throws Exception {
    OnboardingInviteService.IssuedOnboardingInvite issued =
            inviteService.issueBootstrap(
                    tenantAId,
                    Duration.ofHours(24),
                    UUID.randomUUID()
            );

    byte[] storedHash =
            jdbcTemplate.queryForObject(
                    """
                    SELECT token_hash
                    FROM onboarding_invites
                    WHERE id = ?
                    """,
                    byte[].class,
                    issued.id()
            );

    assertThat(storedHash).hasSize(32);
    assertThat(storedHash).isNotEqualTo(
            issued.rawToken().getBytes(
                    StandardCharsets.UTF_8
            )
    );

    assertThat(issued.toString())
            .doesNotContain(issued.rawToken())
            .contains("rawToken=<redacted>");

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    Callable<Boolean> firstAttempt =
            consumeAttempt(
                    issued.rawToken(),
                    firstUserId,
                    ready,
                    start
            );

    Callable<Boolean> secondAttempt =
            consumeAttempt(
                    issued.rawToken(),
                    secondUserId,
                    ready,
                    start
            );

    List<Boolean> results =
            runConcurrently(
                    firstAttempt,
                    secondAttempt,
                    ready,
                    start
            );

    assertThat(results)
            .containsExactlyInAnyOrder(true, false);

    Integer membershipCount =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM tenant_memberships
                    WHERE tenant_id = ?
                      AND status = 'ACTIVE'
                      AND role = 'OWNER'
                    """,
                    Integer.class,
                    tenantAId
            );

    Integer consumedCount =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM onboarding_invites
                    WHERE id = ?
                      AND consumed_at IS NOT NULL
                      AND consumed_by_user_id IS NOT NULL
                    """,
                    Integer.class,
                    issued.id()
            );

    Integer consumptionAuditCount =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM security_audit_events
                    WHERE tenant_id = ?
                      AND action = 'ONBOARDING_INVITE_CONSUMED'
                      AND result = 'SUCCESS'
                    """,
                    Integer.class,
                    tenantAId
            );

    assertThat(membershipCount).isEqualTo(1);
    assertThat(consumedCount).isEqualTo(1);
    assertThat(consumptionAuditCount).isEqualTo(1);
  }

  @Test
  void concurrentOwnerDemotionPreservesOneActiveOwner()
          throws Exception {
    insertMembership(
            tenantAId,
            firstUserId,
            TenantRole.OWNER
    );

    insertMembership(
            tenantAId,
            secondUserId,
            TenantRole.OWNER
    );

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    Callable<Boolean> firstAttempt =
            demoteAttempt(
                    firstUserId,
                    ready,
                    start
            );

    Callable<Boolean> secondAttempt =
            demoteAttempt(
                    secondUserId,
                    ready,
                    start
            );

    List<Boolean> results =
            runConcurrently(
                    firstAttempt,
                    secondAttempt,
                    ready,
                    start
            );

    assertThat(results)
            .containsExactlyInAnyOrder(true, false);

    Integer activeOwnerCount =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM tenant_memberships
                    WHERE tenant_id = ?
                      AND status = 'ACTIVE'
                      AND role = 'OWNER'
                    """,
                    Integer.class,
                    tenantAId
            );

    Integer activeAdminCount =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM tenant_memberships
                    WHERE tenant_id = ?
                      AND status = 'ACTIVE'
                      AND role = 'ADMIN'
                    """,
                    Integer.class,
                    tenantAId
            );

    assertThat(activeOwnerCount).isEqualTo(1);
    assertThat(activeAdminCount).isEqualTo(1);
  }

  @Test
  void bootstrapInviteRevocationIsIdempotentAndBlocksConsumption() {
    OnboardingInviteService.IssuedOnboardingInvite issued =
            inviteService.issueBootstrap(
                    tenantAId,
                    Duration.ofHours(24),
                    UUID.randomUUID()
            );

    OnboardingInviteService.BootstrapInviteRevocation first =
            inviteService.revokeBootstrap(
                    tenantAId,
                    issued.id(),
                    UUID.randomUUID()
            );

    OnboardingInviteService.BootstrapInviteRevocation second =
            inviteService.revokeBootstrap(
                    tenantAId,
                    issued.id(),
                    UUID.randomUUID()
            );

    assertThat(first.result()).isEqualTo(
            OnboardingInviteService.BootstrapInviteRevocationResult.REVOKED
    );

    assertThat(second.result()).isEqualTo(
            OnboardingInviteService.BootstrapInviteRevocationResult.ALREADY_REVOKED
    );

    assertThatThrownBy(
            () -> inviteService.consume(
                    issued.rawToken(),
                    firstUserId,
                    UUID.randomUUID()
            )
    ).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invite is invalid or unavailable");

    Integer revokeAuditCount =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM security_audit_events
                    WHERE tenant_id = ?
                      AND action = 'BOOTSTRAP_INVITE_REVOKED'
                      AND result = 'SUCCESS'
                    """,
                    Integer.class,
                    tenantAId
            );

    assertThat(revokeAuditCount).isEqualTo(1);
  }

  @Test
  void expiredInviteIsRejectedWithoutCreatingMembership() {
    OnboardingInviteTokenService.GeneratedInviteToken generated =
            inviteTokenService.generate();

    OffsetDateTime createdAt =
            OffsetDateTime.now(ZoneOffset.UTC)
                    .minusHours(2);

    jdbcTemplate.update(
            """
            INSERT INTO onboarding_invites (
                id,
                tenant_id,
                role,
                token_hash,
                created_by_user_id,
                created_at,
                expires_at
            )
            VALUES (?, ?, 'OWNER', ?, NULL, ?, ?)
            """,
            UUID.randomUUID(),
            tenantAId,
            generated.tokenHash(),
            createdAt,
            createdAt.plusHours(1)
    );

    assertThatThrownBy(
            () -> inviteService.consume(
                    generated.rawToken(),
                    firstUserId,
                    UUID.randomUUID()
            )
    ).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invite is invalid or unavailable");

    Integer membershipCount =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM tenant_memberships
                    WHERE tenant_id = ?
                    """,
                    Integer.class,
                    tenantAId
            );

    assertThat(membershipCount).isZero();
  }

  @Test
  void bootstrapIssueIsRejectedWhenAnActiveOwnerExists() {
    insertMembership(
            tenantAId,
            firstUserId,
            TenantRole.OWNER
    );

    assertThatThrownBy(
            () -> inviteService.issueBootstrap(
                    tenantAId,
                    Duration.ofHours(24),
                    UUID.randomUUID()
            )
    ).isInstanceOf(IllegalStateException.class)
            .hasMessage(
                    "Bootstrap invite is only allowed before the first OWNER exists"
            );

    Integer inviteCount =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM onboarding_invites
                    WHERE tenant_id = ?
                    """,
                    Integer.class,
                    tenantAId
            );

    assertThat(inviteCount).isZero();
  }

  @Test
  void migrationProvidesTheConfiguredSpringSessionJdbcShape() {
    List<String> sessionColumns =
            jdbcTemplate.queryForList(
                    """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'aerotrace_session'
                    ORDER BY ordinal_position
                    """,
                    String.class
            );

    List<String> attributeColumns =
            jdbcTemplate.queryForList(
                    """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'aerotrace_session_attributes'
                    ORDER BY ordinal_position
                    """,
                    String.class
            );

    assertThat(sessionColumns).containsExactly(
            "primary_id",
            "session_id",
            "creation_time",
            "last_access_time",
            "max_inactive_interval",
            "expiry_time",
            "principal_name"
    );

    assertThat(attributeColumns).containsExactly(
            "session_primary_id",
            "attribute_name",
            "attribute_bytes"
    );
  }

  private Callable<Boolean> consumeAttempt(
          String rawToken,
          UUID userId,
          CountDownLatch ready,
          CountDownLatch start
  ) {
    return () -> {
      ready.countDown();

      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException(
                "Timed out waiting to start invite consumption"
        );
      }

      try {
        inviteService.consume(
                rawToken,
                userId,
                UUID.randomUUID()
        );
        return true;
      } catch (
              IllegalArgumentException exception
      ) {
        return false;
      }
    };
  }

  private Callable<Boolean> demoteAttempt(
          UUID userId,
          CountDownLatch ready,
          CountDownLatch start
  ) {
    return () -> {
      ready.countDown();

      if (!start.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException(
                "Timed out waiting to start owner demotion"
        );
      }

      try {
        membershipService.changeRole(
                tenantAId,
                userId,
                TenantRole.ADMIN
        );
        return true;
      } catch (
              IllegalStateException exception
      ) {
        return false;
      }
    };
  }

  private List<Boolean> runConcurrently(
          Callable<Boolean> firstAttempt,
          Callable<Boolean> secondAttempt,
          CountDownLatch ready,
          CountDownLatch start
  ) throws Exception {
    ExecutorService executor =
            Executors.newFixedThreadPool(2);

    try {
      Future<Boolean> first = executor.submit(firstAttempt);
      Future<Boolean> second = executor.submit(secondAttempt);

      assertThat(
              ready.await(10, TimeUnit.SECONDS)
      ).isTrue();

      start.countDown();

      return List.of(
              first.get(10, TimeUnit.SECONDS),
              second.get(10, TimeUnit.SECONDS)
      );
    } finally {
      executor.shutdownNow();
      assertThat(
              executor.awaitTermination(10, TimeUnit.SECONDS)
      ).isTrue();
    }
  }

  private void insertTenant(
          UUID tenantId,
          String name
  ) {
    jdbcTemplate.update(
            """
            INSERT INTO tenants (
                id,
                name,
                slug
            )
            VALUES (?, ?, ?)
            """,
            tenantId,
            name,
            "auth-integration-" + tenantId
    );
  }

  private void insertProject(
          UUID projectId,
          UUID tenantId,
          String name
  ) {
    jdbcTemplate.update(
            """
            INSERT INTO projects (
                id,
                tenant_id,
                name,
                slug
            )
            VALUES (?, ?, ?, ?)
            """,
            projectId,
            tenantId,
            name,
            "auth-integration-" + projectId
    );
  }

  private void insertUser(
          UUID userId,
          String displayName
  ) {
    jdbcTemplate.update(
            """
            INSERT INTO app_users (
                id,
                display_name,
                status
            )
            VALUES (?, ?, 'ACTIVE')
            """,
            userId,
            displayName
    );
  }

  private void insertMembership(
          UUID tenantId,
          UUID userId,
          TenantRole role
  ) {
    OffsetDateTime now =
            OffsetDateTime.now(ZoneOffset.UTC);

    jdbcTemplate.update(
            """
            INSERT INTO tenant_memberships (
                tenant_id,
                user_id,
                role,
                status,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, 'ACTIVE', ?, ?)
            """,
            tenantId,
            userId,
            role.name(),
            now,
            now
    );
  }
}
