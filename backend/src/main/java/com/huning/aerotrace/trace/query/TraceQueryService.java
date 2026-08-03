package com.huning.aerotrace.trace.query;

import com.huning.aerotrace.auth.application.AuthenticatedProject;
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