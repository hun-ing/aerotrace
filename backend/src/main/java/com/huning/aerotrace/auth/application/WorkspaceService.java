package com.huning.aerotrace.auth.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WorkspaceService {
  private final WorkspaceStore store;
  private final MembershipPermissionService permissions;

  public WorkspaceService(WorkspaceStore store, MembershipPermissionService permissions) {
    this.store = store;
    this.permissions = permissions;
  }

  public List<AccessibleProject> projects(UUID userId, UUID tenantId) {
    if (permissions.authorizeTenant(userId, tenantId, TenantPermission.TENANT_READ)
            .outcome() != AuthorizationDecision.AuthorizationOutcome.ALLOWED) {
      throw new WorkspaceUnavailableException();
    }
    return store.findAccessibleProjects(userId, tenantId);
  }

  public AccessibleProject project(UUID userId, UUID projectId) {
    // The tenant is obtained from the project + active membership join, not from the browser.
    // OWNER, ADMIN and VIEWER all have PROJECT_TRACE_READ in Phase C.
    return store.findAccessibleProject(userId, projectId)
            .orElseThrow(WorkspaceUnavailableException::new);
  }

  public static class WorkspaceUnavailableException extends RuntimeException {
    public WorkspaceUnavailableException() {
      super("Resource is unavailable");
    }
  }
}
