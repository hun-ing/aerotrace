package com.huning.aerotrace.ingest.infrastructure;

import com.huning.aerotrace.ingest.application.SpanWriter;
import com.huning.aerotrace.ingest.domain.ParsedSpan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
public class JdbcSpanWriter implements SpanWriter {

  private static final String INSERT_SQL = """
        INSERT INTO spans (
            tenant_id,
            project_id,
            trace_id,
            span_id,
            parent_span_id,
            trace_state,
            flags,
            service_name,
            scope_name,
            scope_version,
            name,
            span_kind,
            status_code,
            status_message,
            start_time,
            end_time,
            duration_nano,
            resource_attributes,
            span_attributes,
            events,
            links,
            dropped_attributes_count,
            dropped_events_count,
            dropped_links_count
        )
        VALUES (
            ?, ?, ?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?,
            CAST(? AS jsonb),
            CAST(? AS jsonb),
            CAST(? AS jsonb),
            CAST(? AS jsonb),
            ?, ?, ?
        )
        ON CONFLICT (
            tenant_id,
            project_id,
            trace_id,
            span_id,
            start_time
        )
        DO NOTHING
        """;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JdbcSpanWriter(
          JdbcTemplate jdbcTemplate,
          ObjectMapper objectMapper
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean insert(
          UUID tenantId,
          UUID projectId,
          ParsedSpan span
  ) {
    int affectedRows = jdbcTemplate.update(
            INSERT_SQL,
            statement -> {
              statement.setObject(1, tenantId);
              statement.setObject(2, projectId);

              statement.setString(3, span.traceId());
              statement.setString(4, span.spanId());

              if (span.parentSpanId() == null) {
                statement.setNull(5, Types.VARCHAR);
              } else {
                statement.setString(5, span.parentSpanId());
              }

              statement.setString(6, span.traceState());
              statement.setLong(7, span.flags());

              statement.setString(8, span.serviceName());
              statement.setString(9, span.scopeName());
              statement.setString(10, span.scopeVersion());
              statement.setString(11, span.name());

              statement.setShort(12, span.spanKind());
              statement.setShort(13, span.statusCode());
              statement.setString(14, span.statusMessage());

              statement.setObject(
                      15,
                      OffsetDateTime.ofInstant(
                              span.startTime(),
                              ZoneOffset.UTC
                      )
              );

              statement.setObject(
                      16,
                      OffsetDateTime.ofInstant(
                              span.endTime(),
                              ZoneOffset.UTC
                      )
              );

              statement.setLong(17, span.durationNano());

              statement.setString(
                      18,
                      objectMapper.writeValueAsString(
                              span.resourceAttributes()
                      )
              );

              statement.setString(
                      19,
                      objectMapper.writeValueAsString(
                              span.spanAttributes()
                      )
              );

              statement.setString(
                      20,
                      objectMapper.writeValueAsString(
                              span.events()
                      )
              );

              statement.setString(
                      21,
                      objectMapper.writeValueAsString(
                              span.links()
                      )
              );

              statement.setLong(
                      22,
                      span.droppedAttributesCount()
              );

              statement.setLong(
                      23,
                      span.droppedEventsCount()
              );

              statement.setLong(
                      24,
                      span.droppedLinksCount()
              );
            }
    );

    return affectedRows == 1;
  }
}