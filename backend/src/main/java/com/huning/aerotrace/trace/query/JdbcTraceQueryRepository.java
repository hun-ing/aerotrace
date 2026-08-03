package com.huning.aerotrace.trace.query;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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

  private static final String FIND_TRACE_LIST_SQL = """
            SELECT trace_id,
                   MIN(start_time) AS trace_start_time,
                   COUNT(*) AS span_count,
                   COUNT(DISTINCT service_name) AS service_count,
                   MAX(duration_nano) AS longest_span_duration_nano
            FROM public.spans
            WHERE tenant_id = ?
              AND project_id = ?
              AND start_time >= ?
              AND start_time < ?
            GROUP BY trace_id
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
    validateQuery(
            tenantId,
            projectId,
            from,
            to,
            limit
    );

    return jdbcTemplate.query(
            FIND_TRACE_LIST_SQL,
            preparedStatement -> {
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
                      OffsetDateTime.ofInstant(
                              from,
                              ZoneOffset.UTC
                      )
              );
              preparedStatement.setObject(
                      4,
                      OffsetDateTime.ofInstant(
                              to,
                              ZoneOffset.UTC
                      )
              );
              preparedStatement.setInt(
                      5,
                      limit
              );
            },
            JdbcTraceQueryRepository::mapTraceListItem
    );
  }

  private static TraceListItem mapTraceListItem(
          ResultSet resultSet,
          int rowNumber
  ) throws SQLException {
    String traceId =
            normalizeIdentifier(
                    resultSet.getString("trace_id")
            );

    Instant traceStartTime =
            resultSet
                    .getObject(
                            "trace_start_time",
                            OffsetDateTime.class
                    )
                    .toInstant();

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

  private static String normalizeIdentifier(
          String identifier
  ) {
    if (identifier == null) {
      throw new IllegalStateException(
              "Database returned a null identifier"
      );
    }

    /*
     * PostgreSQL bytea 값은 JDBC에서 "\\x..." 형태로
     * 반환될 수 있다.
     *
     * trace_id가 문자 컬럼이면 원본 값을 그대로 사용하고,
     * bytea라면 앞의 "\\x"만 제거한다.
     */
    if (identifier.startsWith("\\x")) {
      return identifier.substring(2);
    }

    return identifier;
  }

  private static void validateQuery(
          UUID tenantId,
          UUID projectId,
          Instant from,
          Instant to,
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

    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException(
              "limit must be between 1 and "
                      + MAX_LIMIT
      );
    }
  }
}