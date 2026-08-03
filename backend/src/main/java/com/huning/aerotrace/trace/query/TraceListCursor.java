package com.huning.aerotrace.trace.query;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record TraceListCursor(
        Instant traceStartTime,
        String traceId,
        String queryFingerprint
) {

  private static final Pattern TRACE_ID_PATTERN =
          Pattern.compile("^[0-9a-f]{32}$");

  private static final Pattern
          QUERY_FINGERPRINT_PATTERN =
          Pattern.compile(
                  "^[A-Za-z0-9_-]{43}$"
          );

  private static final String ZERO_TRACE_ID =
          "00000000000000000000000000000000";

  /*
   * Repository의 직접 SQL 테스트처럼
   * HTTP Cursor binding이 필요하지 않은 내부 호출을 위해
   * 기존 두 인자 생성자를 유지한다.
   */
  public TraceListCursor(
          Instant traceStartTime,
          String traceId
  ) {
    this(
            traceStartTime,
            traceId,
            null
    );
  }

  public TraceListCursor {
    Objects.requireNonNull(
            traceStartTime,
            "traceStartTime must not be null"
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
                      + "32-character lowercase "
                      + "hexadecimal value"
      );
    }

    if (
            queryFingerprint != null
                    && !QUERY_FINGERPRINT_PATTERN
                    .matcher(queryFingerprint)
                    .matches()
    ) {
      throw new IllegalArgumentException(
              "queryFingerprint is invalid"
      );
    }
  }
}