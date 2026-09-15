package com.huning.aerotrace.auth.api;

import com.huning.aerotrace.auth.application.OnboardingInviteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@ConditionalOnProperty(
        name = "aerotrace.auth.enabled",
        havingValue = "true"
)
public class OnboardingIntentController {

  public static final String SESSION_ATTRIBUTE =
          "AEROTRACE_ONBOARDING_INVITE_ID";

  private final OnboardingInviteService inviteService;

  public OnboardingIntentController(
          OnboardingInviteService inviteService
  ) {
    this.inviteService = inviteService;
  }

  @PostMapping("/api/v1/onboarding/intents")
  public ResponseEntity<Void> create(
          @RequestBody OnboardingIntentRequest body,
          HttpServletRequest request
  ) {
    clearExistingIntent(request);

    if (body == null) {
      throw new IllegalArgumentException(
              "Invite is invalid or unavailable"
      );
    }

    UUID inviteId = inviteService.resolveUsableInviteId(
            body.invite()
    );

    request.getSession(true).setAttribute(
            SESSION_ATTRIBUTE,
            inviteId
    );

    return ResponseEntity.noContent()
            .cacheControl(CacheControl.noStore())
            .build();
  }

  private void clearExistingIntent(
          HttpServletRequest request
  ) {
    HttpSession existingSession = request.getSession(false);

    if (existingSession != null) {
      existingSession.removeAttribute(SESSION_ATTRIBUTE);
    }
  }

  public record OnboardingIntentRequest(
          String invite
  ) {

    @Override
    public String toString() {
      return "OnboardingIntentRequest[invite=<redacted>]";
    }
  }
}
