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
    validateQuery(
            authenticatedProject,
            from,
            to,
            limit
    );

    validateCursor(
            cursor,
            from,
            to
    );

    int internalLimit =
            Math.addExact(
                    limit,
                    1
            );

    List<TraceListItem> fetchedItems =
            repository.findTraceList(
                    authenticatedProject.tenantId(),
                    authenticatedProject.projectId(),
                    from,
                    to,
                    cursor,
                    internalLimit
            );

    return createPage(
            fetchedItems,
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
    validateQuery(
            authenticatedProject,
            from,
            to,
            limit
    );

    validateCursor(
            cursor,
            from,
            to
    );

    String normalizedServiceName =
            normalizeServiceName(serviceName);

    if (normalizedServiceName == null) {
      return findTracePage(
              authenticatedProject,
              from,
              to,
              cursor,
              limit
      );
    }

    int internalLimit =
            Math.addExact(
                    limit,
                    1
            );

    List<TraceListItem> fetchedItems =
            repository.findTraceList(
                    authenticatedProject.tenantId(),
                    authenticatedProject.projectId(),
                    from,
                    to,
                    cursor,
                    normalizedServiceName,
                    internalLimit
            );

    return createPage(
            fetchedItems,
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
    /*
     * 기존 테스트와 호출 계약을 유지하기 위해
     * errorOnly=false는 기존 overload로 위임한다.
     */
    if (!errorOnly) {
      if (serviceName == null) {
        return findTracePage(
                authenticatedProject,
                from,
                to,
                cursor,
                limit
        );
      }

      return findTracePage(
              authenticatedProject,
              from,
              to,
              cursor,
              serviceName,
              limit
      );
    }

    validateQuery(
            authenticatedProject,
            from,
            to,
            limit
    );

    validateCursor(
            cursor,
            from,
            to
    );

    String normalizedServiceName =
            normalizeServiceName(serviceName);

    int internalLimit =
            Math.addExact(
                    limit,
                    1
            );

    List<TraceListItem> fetchedItems =
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

    return createPage(
            fetchedItems,
            limit
    );
  }

  private static TraceListPage createPage(
          List<TraceListItem> fetchedItems,
          int requestedLimit
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
                      lastReturnedItem.traceId()
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

  private static void validateCursor(
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