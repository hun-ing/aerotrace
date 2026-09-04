package com.huning.aerotrace.auth.api;

import com.huning.aerotrace.auth.application.AeroTracePrincipal;
import com.huning.aerotrace.auth.application.CurrentUserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
        name = "aerotrace.auth.enabled",
        havingValue = "true"
)
public class CurrentUserController {

  private final CurrentUserService currentUserService;

  public CurrentUserController(
          CurrentUserService currentUserService
  ) {
    this.currentUserService = currentUserService;
  }

  @GetMapping("/api/v1/me")
  public ResponseEntity<CurrentUserService.CurrentUser> currentUser(
          @AuthenticationPrincipal AeroTracePrincipal principal
  ) {
    return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                    currentUserService.load(
                            principal.userId()
                    )
            );
  }
}
