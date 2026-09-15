package com.huning.aerotrace.auth.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(
        name = "aerotrace.auth.enabled",
        havingValue = "true"
)
public class UserSessionRevocationService {

  private final UserSessionStore userSessionStore;

  public UserSessionRevocationService(
          UserSessionStore userSessionStore
  ) {
    this.userSessionStore = userSessionStore;
  }

  @Transactional
  public int revokeUserSessions(UUID userId) {
    Objects.requireNonNull(
            userId,
            "User ID must not be null"
    );

    return userSessionStore.deleteByUserId(userId);
  }

  @Transactional
  public int revokeAllSessions() {
    return userSessionStore.deleteAll();
  }
}
