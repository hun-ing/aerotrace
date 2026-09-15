package com.huning.aerotrace.auth.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface UserAuthenticationStore {

  void lockProviderSubject(
          String provider,
          String providerSubject
  );

  Optional<StoredUserIdentity> findByProviderSubject(
          String provider,
          String providerSubject
  );

  void createUser(NewUser user);

  void createIdentity(NewUserIdentity identity);

  int updateLoginMetadata(
          UUID userId,
          UUID identityId,
          String displayName,
          String avatarUrl,
          String providerLogin,
          Instant seenAt
  );

  record StoredUserIdentity(
          UUID userId,
          UUID identityId,
          UserStatus userStatus,
          String providerSubject
  ) {

    public StoredUserIdentity {
      Objects.requireNonNull(userId, "User ID must not be null");
      Objects.requireNonNull(identityId, "Identity ID must not be null");
      Objects.requireNonNull(userStatus, "User status must not be null");

      if (
              providerSubject == null
                      || providerSubject.isBlank()
      ) {
        throw new IllegalArgumentException(
                "Provider subject must not be blank"
        );
      }
    }
  }

  record NewUser(
          UUID id,
          String displayName,
          String avatarUrl,
          Instant createdAt
  ) {
  }

  record NewUserIdentity(
          UUID id,
          UUID userId,
          String provider,
          String providerSubject,
          String providerLogin,
          Instant createdAt
  ) {
  }

  enum UserStatus {
    ACTIVE,
    DISABLED
  }
}
