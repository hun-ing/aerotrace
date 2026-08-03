package com.huning.aerotrace.trace.query;

import com.huning.aerotrace.auth.application
        .AuthenticatedProject;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class TraceQueryService {

  private static final Duration MAX_QUERY_RANGE =
          Duration.ofDays(30);

  private static final int MAX_SERVICE_NAME_LENGTH =
          255;

  private final JdbcTraceQueryRepository repository;

  public TraceQueryService(
          JdbcTraceQueryRepository repository
  ) {
    this.repository =
            Objects.requireNonNull(
                    repository,
                    "repository must not be null"
            );
  }

  public List<TraceListItem> findTraceList(
          AuthenticatedProject authenticatedProject,
          Instant from,
          Instant to,
          int limit
  ) {
    validateQuery(
            authenticatedProject,
            from,
            to,
            limit
    );

    return repository.findTraceList(
            authenticatedProject.tenantId(),
            authenticatedProject.projectId(),
            from,
            to,
            limit
    );
  }

  public TraceListPage findTracePage(
          AuthenticatedProject authenticatedProject,
          Instant from,
          Instant to,
          TraceListCursor cursor,
          int limit
  ) {
    return findTracePageInternal(
            authenticatedProject,
            from,
            to,
            cursor,
            null,
            false,
            null,
            limit
    );
  }

  public TraceListPage findTracePage(
          AuthenticatedProject authenticatedProject,
          Instant from,
          Instant to,
          TraceListCursor cursor,
          String serviceName,
          int limit
  ) {
    return findTracePageInternal(
            authenticatedProject,
            from,
            to,
            cursor,
            serviceName,
            false,
            null,
            limit
    );
  }

  public TraceListPage findTracePage(
          AuthenticatedProject authenticatedProject,
          Instant from,
          Instant to,
          TraceListCursor cursor,
          String serviceName,
          boolean errorOnly,
          int limit
  ) {
    return findTracePageInternal(
            authenticatedProject,
            from,
            to,
            cursor,
            serviceName,
            errorOnly,
            null,
            limit
    );
  }

  public TraceListPage findTracePage(
          AuthenticatedProject authenticatedProject,
          Instant from,
          Instant to,
          TraceListCursor cursor,
          String serviceName,
          boolean errorOnly,
          Long minSpanDurationNano,
          int limit
  ) {
    return findTracePageInternal(
            authenticatedProject,
            from,
            to,
            cursor,
            serviceName,
            errorOnly,
            minSpanDurationNano,
            limit
    );
  }

  private TraceListPage findTracePageInternal(
          AuthenticatedProject authenticatedProject,
          Instant from,
          Instant to,
          TraceListCursor cursor,
          String serviceName,
          boolean errorOnly,
          Long minSpanDurationNano,
          int limit
  ) {
    validateQuery(
            authenticatedProject,
            from,
            to,
            limit
    );

    String normalizedServiceName =
            normalizeServiceName(serviceName);

    Long normalizedMinSpanDurationNano =
            normalizeMinSpanDurationNano(
                    minSpanDurationNano
            );

    /*
     * Cursor 시간은 fingerprint 계산 전에 검증한다.
     *
     * 범위를 벗어난 Cursor는 Tenant와 Project ID를 읽거나
     * SHA-256을 계산할 필요 없이 즉시 거부할 수 있다.
     */
    validateCursorTimeRange(
            cursor,
            from,
            to
    );

    String queryFingerprint =
            TraceListQueryFingerprint.create(
                    authenticatedProject.tenantId(),
                    authenticatedProject.projectId(),
                    from,
                    to,
                    normalizedServiceName,
                    errorOnly,
                    normalizedMinSpanDurationNano
            );

    validateCursorQueryFingerprint(
            cursor,
            queryFingerprint
    );

    int internalLimit =
            Math.addExact(
                    limit,
                    1
            );

    List<TraceListItem> fetchedItems;

    if (normalizedMinSpanDurationNano != null) {
      fetchedItems =
              repository.findTraceList(
                      authenticatedProject.tenantId(),
                      authenticatedProject.projectId(),
                      from,
                      to,
                      cursor,
                      normalizedServiceName,
                      errorOnly,
                      normalizedMinSpanDurationNano,
                      internalLimit
              );
    } else if (errorOnly) {
      fetchedItems =
              repository.findTraceList(
                      authenticatedProject.tenantId(),
                      authenticatedProject.projectId(),
                      from,
                      to,
                      cursor,
                      normalizedServiceName,
                      true,
                      internalLimit
              );
    } else if (normalizedServiceName != null) {
      fetchedItems =
              repository.findTraceList(
                      authenticatedProject.tenantId(),
                      authenticatedProject.projectId(),
                      from,
                      to,
                      cursor,
                      normalizedServiceName,
                      internalLimit
              );
    } else {
      fetchedItems =
              repository.findTraceList(
                      authenticatedProject.tenantId(),
                      authenticatedProject.projectId(),
                      from,
                      to,
                      cursor,
                      internalLimit
              );
    }

    return createPage(
            fetchedItems,
            limit,
            queryFingerprint
    );
  }

  private static TraceListPage createPage(
          List<TraceListItem> fetchedItems,
          int requestedLimit,
          String queryFingerprint
  ) {
    boolean hasNext =
            fetchedItems.size()
                    > requestedLimit;

    int returnedItemCount =
            Math.min(
                    fetchedItems.size(),
                    requestedLimit
            );

    List<TraceListItem> returnedItems =
            List.copyOf(
                    fetchedItems.subList(
                            0,
                            returnedItemCount
                    )
            );

    TraceListCursor nextCursor = null;

    if (hasNext) {
      TraceListItem lastReturnedItem =
              returnedItems.getLast();

      nextCursor =
              new TraceListCursor(
                      lastReturnedItem
                              .traceStartTime(),
                      lastReturnedItem.traceId(),
                      queryFingerprint
              );
    }

    return new TraceListPage(
            returnedItems,
            nextCursor
    );
  }

  private static String normalizeServiceName(
          String serviceName
  ) {
    if (serviceName == null) {
      return null;
    }

    String normalized =
            serviceName.strip();

    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(
              "serviceName must not be blank"
      );
    }

    if (
            normalized.length()
                    > MAX_SERVICE_NAME_LENGTH
    ) {
      throw new IllegalArgumentException(
              "serviceName must not exceed "
                      + MAX_SERVICE_NAME_LENGTH
                      + " characters"
      );
    }

    return normalized;
  }

  private static Long normalizeMinSpanDurationNano(
          Long minSpanDurationNano
  ) {
    if (minSpanDurationNano == null) {
      return null;
    }

    if (minSpanDurationNano < 0) {
      throw new IllegalArgumentException(
              "minSpanDurationNano must not be negative"
      );
    }

    return minSpanDurationNano;
  }

  private static void validateCursorTimeRange(
          TraceListCursor cursor,
          Instant from,
          Instant to
  ) {
    if (cursor == null) {
      return;
    }

    Instant cursorTime =
            cursor.traceStartTime();

    boolean isBeforeRange =
            cursorTime.isBefore(from);

    boolean isAtOrAfterRangeEnd =
            !cursorTime.isBefore(to);

    if (
            isBeforeRange
                    || isAtOrAfterRangeEnd
    ) {
      throw new IllegalArgumentException(
              "cursor is outside the requested time range"
      );
    }
  }

  private static void validateCursorQueryFingerprint(
          TraceListCursor cursor,
          String expectedQueryFingerprint
  ) {
    if (cursor == null) {
      return;
    }

    if (
            !Objects.equals(
                    cursor.queryFingerprint(),
                    expectedQueryFingerprint
            )
    ) {
      throw new IllegalArgumentException(
              "cursor does not match the current query"
      );
    }
  }

  private static void validateQuery(
          AuthenticatedProject authenticatedProject,
          Instant from,
          Instant to,
          int limit
  ) {
    Objects.requireNonNull(
            authenticatedProject,
            "authenticatedProject must not be null"
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

    Duration queryRange =
            Duration.between(
                    from,
                    to
            );

    if (
            queryRange.compareTo(
                    MAX_QUERY_RANGE
            ) > 0
    ) {
      throw new IllegalArgumentException(
              "Query range must not exceed 30 days"
      );
    }

    if (
            limit < 1
                    || limit
                    > JdbcTraceQueryRepository.MAX_LIMIT
    ) {
      throw new IllegalArgumentException(
              "limit must be between 1 and "
                      + JdbcTraceQueryRepository.MAX_LIMIT
      );
    }
  }
}