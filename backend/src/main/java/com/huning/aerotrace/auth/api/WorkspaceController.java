package com.huning.aerotrace.auth.api;

import com.huning.aerotrace.auth.application.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "aerotrace.auth.enabled", havingValue = "true")
public class WorkspaceController {
  private final CurrentUserService users;
  private final WorkspaceService workspaces;

  public WorkspaceController(CurrentUserService users, WorkspaceService workspaces) {
    this.users = users;
    this.workspaces = workspaces;
  }

  @GetMapping("/api/v1/tenants")
  public ResponseEntity<List<CurrentUserStore.ActiveTenantMembership>> tenants(
          @AuthenticationPrincipal AeroTracePrincipal principal) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(users.load(principal.userId()).memberships());
  }

  @GetMapping("/api/v1/tenants/{tenantId}/projects")
  public ResponseEntity<List<AccessibleProject>> projects(
          @AuthenticationPrincipal AeroTracePrincipal principal, @PathVariable UUID tenantId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(workspaces.projects(principal.userId(), tenantId));
  }

  @GetMapping("/api/v1/projects/{projectId}")
  public ResponseEntity<AccessibleProject> project(
          @AuthenticationPrincipal AeroTracePrincipal principal, @PathVariable UUID projectId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(workspaces.project(principal.userId(), projectId));
  }
}
