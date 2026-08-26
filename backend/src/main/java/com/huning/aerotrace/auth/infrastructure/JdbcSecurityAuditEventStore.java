package com.huning.aerotrace.auth.infrastructure;

import com.huning.aerotrace.auth.application.SecurityAuditEventStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Repository
public class JdbcSecurityAuditEventStore
        implements SecurityAuditEventStore {

  private final JdbcTemplate jdbcTemplate;

  public JdbcSecurityAuditEventStore(
          JdbcTemplate jdbcTemplate
  ) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void save(
          NewSecurityAuditEvent event
  ) {
    int insertedRows =
            jdbcTemplate.update(
                    """
                    INSERT INTO security_audit_events (
                        id,
                        actor_user_id,
                        tenant_id,
                        project_id,
                        action,
                        result,
                        occurred_at,
                        correlation_id
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    event.id(),
                    event.actorUserId(),
                    event.tenantId(),
                    event.projectId(),
                    event.action(),
                    event.result().name(),
                    OffsetDateTime.ofInstant(
                            event.occurredAt(),
                            ZoneOffset.UTC
                    ),
                    event.correlationId()
            );

    if (insertedRows != 1) {
      throw new IllegalStateException(
              "Security audit insert did not create exactly one row"
      );
    }
  }
}
