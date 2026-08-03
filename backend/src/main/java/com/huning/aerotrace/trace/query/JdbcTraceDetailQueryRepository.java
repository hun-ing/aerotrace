package com.huning.aerotrace.trace.query;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Repository
public class JdbcTraceDetailQueryRepository {

  public static final int MAX_SPAN_LIMIT = 5_000;

  private static final Pattern TRACE_ID_PATTERN =
          Pattern.compile("^[0-9a-f]{32}$");

  private static final String ZERO_TRACE_ID =
          "00000000000000000000000000000000";

  private static final String FIND_TRACE_SPANS_SQL = """
            SELECT trace_id,
                   span_id,
                   parent_span_id,
                   service_name,
                   scope_name,
                   scope_version,
                   name,
                   span_kind,
                   status_code,
                   status_message,
                   start_time,
                   end_time,
                   duration_nano
            FROM public.spans
            WHERE tenant_id = ?
              AND project_id = ?
              AND trace_id = ?
            ORDER BY start_time ASC,
                     span_id ASC
            LIMIT ?
            """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcTraceDetailQueryRepository(
          JdbcTemplate jdbcTemplate
  ) {
    this.jdbcTemplate =
            Objects.requireNonNull(
                    jdbcTemplate,
                    "jdbcTemplate must not be null"
            );
  }

  public List<TraceSpanDetail> findTraceSpans(
          UUID tenantId,
          UUID projectId,
          String traceId,
          int limit
  ) {
    validateQuery(
            tenantId,
            projectId,
            traceId,
            limit
    );

    return jdbcTemplate.query(
            FIND_TRACE_SPANS_SQL,
            preparedStatement -> {
              preparedStatement.setObject(
                      1,
                      tenantId
              );

              preparedStatement.setObject(
                      2,
                      projectId
              );

              preparedStatement.setString(
                      3,
                      traceId
              );

              preparedStatement.setInt(
                      4,
                      limit
              );
            },
            JdbcTraceDetailQueryRepository
                    ::mapTraceSpanDetail
    );
  }

  private static TraceSpanDetail mapTraceSpanDetail(
          ResultSet resultSet,
          int rowNumber
  ) throws SQLException {
    Instant startTime =
            resultSet.getObject(
                    "start_time",
                    OffsetDateTime.class
            ).toInstant();

    Instant endTime =
            resultSet.getObject(
                    "end_time",
                    OffsetDateTime.class
            ).toInstant();

    return new TraceSpanDetail(
            resultSet.getString("trace_id"),
            resultSet.getString("span_id"),
            resultSet.getString("parent_span_id"),
            resultSet.getString("service_name"),
            resultSet.getString("scope_name"),
            resultSet.getString("scope_version"),
            resultSet.getString("name"),
            resultSet.getShort("span_kind"),
            resultSet.getShort("status_code"),
            resultSet.getString("status_message"),
            startTime,
            endTime,
            resultSet.getLong("duration_nano")
    );
  }

  private static void validateQuery(
          UUID tenantId,
          UUID projectId,
          String traceId,
          int limit
  ) {
    Objects.requireNonNull(
            tenantId,
            "tenantId must not be null"
    );

    Objects.requireNonNull(
            projectId,
            "projectId must not be null"
    );

    Objects.requireNonNull(
            traceId,
            "traceId must not be null"
    );

    if (
            !TRACE_ID_PATTERN
                    .matcher(traceId)
                    .matches()
                    || ZERO_TRACE_ID.equals(traceId)
    ) {
      throw new IllegalArgumentException(
              "traceId must be a non-zero "
                      + "32-character lowercase hexadecimal value"
      );
    }

    if (
            limit < 1
                    || limit > MAX_SPAN_LIMIT
    ) {
      throw new IllegalArgumentException(
              "limit must be between 1 and "
                      + MAX_SPAN_LIMIT
      );
    }
  }
}