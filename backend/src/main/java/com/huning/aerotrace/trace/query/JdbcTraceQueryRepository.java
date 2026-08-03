package com.huning.aerotrace.trace.query;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class JdbcTraceQueryRepository {

  public static final int MAX_LIMIT = 200;

  static final int INTERNAL_MAX_LIMIT =
          MAX_LIMIT + 1;

  private static final int MAX_SERVICE_NAME_LENGTH =
          255;

  /*
   * serviceName과 errorOnly 사용 여부를 boolean parameter로 받아
   * 필터 조합마다 SQL을 별도로 만들지 않는다.
   *
   * HAVING을 사용하므로 선택된 Trace의 집계값은
   * 필터에 일치한 Span이 아닌 Trace 전체를 기준으로 계산된다.
   */
  private static final String FIND_TRACE_LIST_SQL = """
        SELECT trace_id,
               MIN(start_time) AS trace_start_time,
               COUNT(*) AS span_count,
               COUNT(DISTINCT service_name)
                   AS service_count,
               MAX(duration_nano)
                   AS longest_span_duration_nano
        FROM public.spans
        WHERE tenant_id = ?
          AND project_id = ?
          AND start_time >= ?
          AND start_time < ?
        GROUP BY trace_id
        HAVING (
                   ? = FALSE
                   OR BOOL_OR(service_name = ?)
               )
           AND (
                   ? = FALSE
                   OR BOOL_OR(status_code = 2)
               )
           AND (
                   ? = FALSE
                   OR MAX(duration_nano) >= ?
               )
        ORDER BY trace_start_time DESC,
                 trace_id DESC
        LIMIT ?
        """;

  private static final String
          FIND_TRACE_LIST_AFTER_CURSOR_SQL = """
        WITH trace_summaries AS (
            SELECT trace_id,
                   MIN(start_time)
                       AS trace_start_time,
                   COUNT(*)
                       AS span_count,
                   COUNT(DISTINCT service_name)
                       AS service_count,
                   MAX(duration_nano)
                       AS longest_span_duration_nano
            FROM public.spans
            WHERE tenant_id = ?
              AND project_id = ?
              AND start_time >= ?
              AND start_time < ?
            GROUP BY trace_id
            HAVING (
                       ? = FALSE
                       OR BOOL_OR(service_name = ?)
                   )
               AND (
                       ? = FALSE
                       OR BOOL_OR(status_code = 2)
                   )
               AND (
                       ? = FALSE
                       OR MAX(duration_nano) >= ?
                   )
        )
        SELECT trace_id,
               trace_start_time,
               span_count,
               service_count,
               longest_span_duration_nano
        FROM trace_summaries
        WHERE trace_start_time < ?
           OR (
                trace_start_time = ?
                AND trace_id < ?
           )
        ORDER BY trace_start_time DESC,
                 trace_id DESC
        LIMIT ?
        """;

  private final JdbcTemplate jdbcTemplate;

  public JdbcTraceQueryRepository(
          JdbcTemplate jdbcTemplate
  ) {
    this.jdbcTemplate =
            Objects.requireNonNull(
                    jdbcTemplate,
                    "jdbcTemplate must not be null"
            );
  }

  public List<TraceListItem> findTraceList(
          UUID tenantId,
          UUID projectId,
          Instant from,
          Instant to,
          int limit
  ) {
    return findTraceList(
            tenantId,
            projectId,
            from,
            to,
            null,
            null,
            false,
            limit
    );
  }

  public List<TraceListItem> findTraceList(
          UUID tenantId,
          UUID projectId,
          Instant from,
          Instant to,
          TraceListCursor cursor,
          int limit
  ) {
    return findTraceList(
            tenantId,
            projectId,
            from,
            to,
            cursor,
            null,
            false,
            limit
    );
  }

  public List<TraceListItem> findTraceList(
          UUID tenantId,
          UUID projectId,
          Instant from,
          Instant to,
          TraceListCursor cursor,
          String serviceName,
          int limit
  ) {
    return findTraceList(
            tenantId,
            projectId,
            from,
            to,
            cursor,
            serviceName,
            false,
            limit
    );
  }

  public List<TraceListItem> findTraceList(
          UUID tenantId,
          UUID projectId,
          Instant from,
          Instant to,
          TraceListCursor cursor,
          String serviceName,
          boolean errorOnly,
          int limit
  ) {
    return findTraceList(
            tenantId,
            projectId,
            from,
            to,
            cursor,
            serviceName,
            errorOnly,
            null,
            limit
    );
  }

  public List<TraceListItem> findTraceList(
          UUID tenantId,
          UUID projectId,
          Instant from,
          Instant to,
          TraceListCursor cursor,
          String serviceName,
          boolean errorOnly,
          Long minSpanDurationNano,
          int limit
  ) {
    validateQuery(
            tenantId,
            projectId,
            from,
            to,
            serviceName,
            minSpanDurationNano,
            limit
    );

    if (cursor == null) {
      return queryFirstPage(
              tenantId,
              projectId,
              from,
              to,
              serviceName,
              errorOnly,
              minSpanDurationNano,
              limit
      );
    }

    return queryAfterCursor(
            tenantId,
            projectId,
            from,
            to,
            cursor,
            serviceName,
            errorOnly,
            minSpanDurationNano,
            limit
    );
  }

  private List<TraceListItem> queryFirstPage(
          UUID tenantId,
          UUID projectId,
          Instant from,
          Instant to,
          String serviceName,
          boolean errorOnly,
          Long minSpanDurationNano,
          int limit
  ) {
    return jdbcTemplate.query(
            FIND_TRACE_LIST_SQL,
            preparedStatement -> {
              setCommonParameters(
                      preparedStatement,
                      tenantId,
                      projectId,
                      from,
                      to
              );

              setFilterParameters(
                      preparedStatement,
                      5,
                      serviceName,
                      errorOnly,
                      minSpanDurationNano
              );

              preparedStatement.setInt(
                      10,
                      limit
              );
            },
            JdbcTraceQueryRepository::mapTraceListItem
    );
  }

  private List<TraceListItem> queryAfterCursor(
          UUID tenantId,
          UUID projectId,
          Instant from,
          Instant to,
          TraceListCursor cursor,
          String serviceName,
          boolean errorOnly,
          Long minSpanDurationNano,
          int limit
  ) {
    return jdbcTemplate.query(
            FIND_TRACE_LIST_AFTER_CURSOR_SQL,
            preparedStatement -> {
              setCommonParameters(
                      preparedStatement,
                      tenantId,
                      projectId,
                      from,
                      to
              );

              setFilterParameters(
                      preparedStatement,
                      5,
                      serviceName,
                      errorOnly,
                      minSpanDurationNano
              );

              OffsetDateTime cursorTime =
                      toOffsetDateTime(
                              cursor.traceStartTime()
                      );

              preparedStatement.setObject(
                      10,
                      cursorTime
              );

              preparedStatement.setObject(
                      11,
                      cursorTime
              );

              preparedStatement.setString(
                      12,
                      cursor.traceId()
              );

              preparedStatement.setInt(
                      13,
                      limit
              );
            },
            JdbcTraceQueryRepository::mapTraceListItem
    );
  }

  private static void setCommonParameters(
          PreparedStatement preparedStatement,
          UUID tenantId,
          UUID projectId,
          Instant from,
          Instant to
  ) throws SQLException {
    preparedStatement.setObject(
            1,
            tenantId
    );

    preparedStatement.setObject(
            2,
            projectId
    );

    preparedStatement.setObject(
            3,
            toOffsetDateTime(from)
    );

    preparedStatement.setObject(
            4,
            toOffsetDateTime(to)
    );
  }

  private static void setFilterParameters(
          PreparedStatement preparedStatement,
          int startIndex,
          String serviceName,
          boolean errorOnly,
          Long minSpanDurationNano
  ) throws SQLException {
    boolean serviceFilterEnabled =
            serviceName != null;

    boolean durationFilterEnabled =
            minSpanDurationNano != null;

    preparedStatement.setBoolean(
            startIndex,
            serviceFilterEnabled
    );

    preparedStatement.setString(
            startIndex + 1,
            serviceFilterEnabled
                    ? serviceName
                    : ""
    );

    preparedStatement.setBoolean(
            startIndex + 2,
            errorOnly
    );

    preparedStatement.setBoolean(
            startIndex + 3,
            durationFilterEnabled
    );

    preparedStatement.setLong(
            startIndex + 4,
            durationFilterEnabled
                    ? minSpanDurationNano
                    : 0L
    );
  }

  private static OffsetDateTime toOffsetDateTime(
          Instant instant
  ) {
    return OffsetDateTime.ofInstant(
            instant,
            ZoneOffset.UTC
    );
  }

  private static TraceListItem mapTraceListItem(
          ResultSet resultSet,
          int rowNumber
  ) throws SQLException {
    String traceId =
            resultSet.getString("trace_id");

    if (traceId == null) {
      throw new IllegalStateException(
              "Database returned a null trace_id"
      );
    }

    Instant traceStartTime =
            resultSet.getObject(
                    "trace_start_time",
                    OffsetDateTime.class
            ).toInstant();

    return new TraceListItem(
            traceId,
            traceStartTime,
            resultSet.getLong("span_count"),
            resultSet.getLong("service_count"),
            resultSet.getLong(
                    "longest_span_duration_nano"
            )
    );
  }

  private static void validateQuery(
          UUID tenantId,
          UUID projectId,
          Instant from,
          Instant to,
          String serviceName,
          Long minSpanDurationNano,
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
            from,
            "from must not be null"
    );

    Objects.requireNonNull(
            to,
            "to must not be null"
    );

    if (!from.isBefore(to)) {
      throw new IllegalArgumentException(
              "from must be earlier than to"
      );
    }

    if (serviceName != null) {
      if (serviceName.isBlank()) {
        throw new IllegalArgumentException(
                "serviceName must not be blank"
        );
      }

      if (
              serviceName.length()
                      > MAX_SERVICE_NAME_LENGTH
      ) {
        throw new IllegalArgumentException(
                "serviceName must not exceed "
                        + MAX_SERVICE_NAME_LENGTH
                        + " characters"
        );
      }
    }

    if (
            minSpanDurationNano != null
                    && minSpanDurationNano < 0
    ) {
      throw new IllegalArgumentException(
              "minSpanDurationNano must not be negative"
      );
    }

    if (
            limit < 1
                    || limit > INTERNAL_MAX_LIMIT
    ) {
      throw new IllegalArgumentException(
              "limit must be between 1 and "
                      + INTERNAL_MAX_LIMIT
      );
    }
  }
}