package com.huning.aerotrace.auth.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface SecurityAuditEventStore {

  void save(
          NewSecurityAuditEvent event
  );

  record NewSecurityAuditEvent(
          UUID id,
          UUID actorUserId,
          UUID tenantId,
          UUID projectId,
          String action,
          AuditResult result,
          Instant occurredAt,
          UUID correlationId
  ) {

    public NewSecurityAuditEvent {
      Objects.requireNonNull(
              id,
              "Audit event ID must not be null"
      );

      if (action == null || action.isBlank()) {
        throw new IllegalArgumentException(
                "Audit action must not be blank"
        );
      }

      Objects.requireNonNull(
              result,
              "Audit result must not be null"
      );

      Objects.requireNonNull(
              occurredAt,
              "Audit event time must not be null"
      );

      Objects.requireNonNull(
              correlationId,
              "Audit correlation ID must not be null"
      );

      action = action.trim();
    }
  }

  enum AuditResult {
    SUCCESS,
    DENIED,
    FAILURE
  }
}
