package com.huning.aerotrace.auth.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceStore {
  List<AccessibleProject> findAccessibleProjects(UUID userId, UUID tenantId);
  Optional<AccessibleProject> findAccessibleProject(UUID userId, UUID projectId);
}
