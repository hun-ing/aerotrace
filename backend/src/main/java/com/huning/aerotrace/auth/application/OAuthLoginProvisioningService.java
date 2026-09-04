package com.huning.aerotrace.auth.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class OAuthLoginProvisioningService {

  private static final String PROVIDER = "GITHUB";
  private static final int DISPLAY_NAME_MAX_LENGTH = 100;

  private final UserAuthenticationStore authenticationStore;
  private final OnboardingInviteService inviteService;
  private final SecurityAuditEventStore auditEventStore;
  private final Clock clock;

  public OAuthLoginProvisioningService(
          UserAuthenticationStore authenticationStore,
          OnboardingInviteService inviteService,
          SecurityAuditEventStore auditEventStore,
          Clock clock
  ) {
    this.authenticationStore = authenticationStore;
    this.inviteService = inviteService;
    this.auditEventStore = auditEventStore;
    this.clock = clock;
  }

  @Transactional
  public AeroTracePrincipal provisionGithubLogin(
          GithubOAuthIdentity identity,
          UUID inviteId,
          UUID correlationId
  ) {
    Objects.requireNonNull(
            identity,
            "GitHub identity must not be null"
    );

    Objects.requireNonNull(
            correlationId,
            "Correlation ID must not be null"
    );

    Instant authenticatedAt = clock.instant();

    authenticationStore.lockProviderSubject(
            PROVIDER,
            identity.providerSubject()
    );

    UserAuthenticationStore.StoredUserIdentity stored =
            authenticationStore.findByProviderSubject(
                    PROVIDER,
                    identity.providerSubject()
            ).orElse(null);

    UUID userId;
    UUID identityId;

    if (stored == null) {
      if (inviteId == null) {
        throw new OAuthLoginProvisioningException();
      }

      userId = UUID.randomUUID();
      identityId = UUID.randomUUID();

      authenticationStore.createUser(
              new UserAuthenticationStore.NewUser(
                      userId,
                      displayName(identity),
                      identity.avatarUrl(),
                      authenticatedAt
              )
      );

      authenticationStore.createIdentity(
              new UserAuthenticationStore.NewUserIdentity(
                      identityId,
                      userId,
                      PROVIDER,
                      identity.providerSubject(),
                      identity.login(),
                      authenticatedAt
              )
      );

      inviteService.consumeById(
              inviteId,
              userId,
              correlationId
      );
    } else {
      if (
              stored.userStatus()
                      != UserAuthenticationStore.UserStatus.ACTIVE
      ) {
        throw new OAuthLoginProvisioningException();
      }

      userId = stored.userId();
      identityId = stored.identityId();

      if (inviteId != null) {
        inviteService.consumeById(
                inviteId,
                userId,
                correlationId
        );
      }
    }

    int updatedRows =
            authenticationStore.updateLoginMetadata(
                    userId,
                    identityId,
                    displayName(identity),
                    identity.avatarUrl(),
                    identity.login(),
                    authenticatedAt
            );

    if (updatedRows != 2) {
      throw new OAuthLoginProvisioningException();
    }

    auditEventStore.save(
            new SecurityAuditEventStore.NewSecurityAuditEvent(
                    UUID.randomUUID(),
                    userId,
                    null,
                    null,
                    "OAUTH_LOGIN_SUCCEEDED",
                    SecurityAuditEventStore.AuditResult.SUCCESS,
                    authenticatedAt,
                    correlationId
            )
    );

    return new AeroTracePrincipal(
            userId,
            authenticatedAt
    );
  }

  private String displayName(
          GithubOAuthIdentity identity
  ) {
    String candidate =
            identity.displayName() == null
                    ? identity.login()
                    : identity.displayName();

    int codePointCount =
            candidate.codePointCount(
                    0,
                    candidate.length()
            );

    if (codePointCount <= DISPLAY_NAME_MAX_LENGTH) {
      return candidate;
    }

    int endIndex = candidate.offsetByCodePoints(
            0,
            DISPLAY_NAME_MAX_LENGTH
    );

    return candidate.substring(0, endIndex);
  }
}
