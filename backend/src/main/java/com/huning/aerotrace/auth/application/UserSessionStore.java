package com.huning.aerotrace.auth.application;

import java.util.UUID;

public interface UserSessionStore {

  int deleteByUserId(UUID userId);

  int deleteAll();
}
