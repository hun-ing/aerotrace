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

  /*
   * 현재 HTTP Controller와 기존 테스트의 호환을 위해
   * 기존 메서드는 유지한다.
   */
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

    boolean hasNext =
            fetchedItems.size() > limit;

    int returnedItemCount =
            Math.min(
                    fetchedItems.size(),
                    limit
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