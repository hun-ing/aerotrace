package com.huning.aerotrace.auth.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CurrentUserService {

  private final CurrentUserStore currentUserStore;

  public CurrentUserService(
          CurrentUserStore currentUserStore
  ) {
    this.currentUserStore = currentUserStore;
  }

  @Transactional(readOnly = true)
  public CurrentUser load(UUID userId) {
    CurrentUserStore.StoredCurrentUser user =
            currentUserStore.findActiveUser(userId)
                    .orElseThrow(
                            CurrentUserUnavailableException::new
                    );

    return new CurrentUser(
            user.userId(),
            user.displayName(),
            user.avatarUrl(),
            currentUserStore.findActiveMemberships(userId)
    );
  }

  public record CurrentUser(
          UUID userId,
          String displayName,
          String avatarUrl,
          List<CurrentUserStore.ActiveTenantMembership> memberships
  ) {

    public CurrentUser {
      memberships = List.copyOf(memberships);
    }
  }

  public static class CurrentUserUnavailableException
          extends RuntimeException {

    public CurrentUserUnavailableException() {
      super("Current user is unavailable");
    }
  }
}
