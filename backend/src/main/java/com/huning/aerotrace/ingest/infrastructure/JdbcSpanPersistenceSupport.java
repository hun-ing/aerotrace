package com.huning.aerotrace.ingest.infrastructure;

import com.huning.aerotrace.ingest.domain.ParsedSpan;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
class JdbcSpanPersistenceSupport {

  static final String INSERT_SQL = """
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

  private final ObjectMapper objectMapper;

  JdbcSpanPersistenceSupport(
          ObjectMapper objectMapper
  ) {
    this.objectMapper = objectMapper;
  }

  List<PreparedSpanRow> prepareRows(
          UUID tenantId,
          UUID projectId,
          List<ParsedSpan> spans
  ) {
    List<PreparedSpanRow> rows =
            new ArrayList<>(spans.size());

    for (ParsedSpan span : spans) {
      rows.add(
              prepareRow(
                      tenantId,
                      projectId,
                      span
              )
      );
    }

    return List.copyOf(rows);
  }

  void bind(
          PreparedStatement statement,
          PreparedSpanRow row
  ) throws SQLException {
    ParsedSpan span = row.span();

    statement.setObject(1, row.tenantId());
    statement.setObject(2, row.projectId());

    statement.setString(3, span.traceId());
    statement.setString(4, span.spanId());

    if (span.parentSpanId() == null) {
      statement.setNull(5, Types.VARCHAR);
    } else {
      statement.setString(
              5,
              span.parentSpanId()
      );
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

    statement.setLong(
            17,
            span.durationNano()
    );

    statement.setString(
            18,
            row.resourceAttributesJson()
    );

    statement.setString(
            19,
            row.spanAttributesJson()
    );

    statement.setString(
            20,
            row.eventsJson()
    );

    statement.setString(
            21,
            row.linksJson()
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

  private PreparedSpanRow prepareRow(
          UUID tenantId,
          UUID projectId,
          ParsedSpan span
  ) {
    try {
      return new PreparedSpanRow(
              tenantId,
              projectId,
              span,
              objectMapper.writeValueAsString(
                      span.resourceAttributes()
              ),
              objectMapper.writeValueAsString(
                      span.spanAttributes()
              ),
              objectMapper.writeValueAsString(
                      span.events()
              ),
              objectMapper.writeValueAsString(
                      span.links()
              )
      );
    } catch (JacksonException exception) {
      throw new IllegalStateException(
              "Failed to serialize parsed Span as JSON",
              exception
      );
    }
  }

  record PreparedSpanRow(
          UUID tenantId,
          UUID projectId,
          ParsedSpan span,
          String resourceAttributesJson,
          String spanAttributesJson,
          String eventsJson,
          String linksJson
  ) {
  }
}