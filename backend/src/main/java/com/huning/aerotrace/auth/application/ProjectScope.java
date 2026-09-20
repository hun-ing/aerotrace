package com.huning.aerotrace.auth.application;

import java.util.UUID;

/** A project scope resolved by an authentication/authorization boundary, never by request input alone. */
public interface ProjectScope {
  UUID tenantId();
  UUID projectId();
}
