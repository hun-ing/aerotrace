package com.huning.aerotrace.trace.query;

import com.huning.aerotrace.auth.application
        .AuthenticatedProject;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class TraceDetailQueryService {

  private final JdbcTraceDetailQueryRepository repository;

  public TraceDetailQueryService(
          JdbcTraceDetailQueryRepository repository
  ) {
    this.repository =
            Objects.requireNonNull(
                    repository,
                    "repository must not be null"
            );
  }

  public List<TraceSpanDetail> findTraceSpans(
          AuthenticatedProject authenticatedProject,
          String traceId
  ) {
    Objects.requireNonNull(
            authenticatedProject,
            "authenticatedProject must not be null"
    );

    List<TraceSpanDetail> spans =
            repository.findTraceSpans(
                    authenticatedProject.tenantId(),
                    authenticatedProject.projectId(),
                    traceId,
                    JdbcTraceDetailQueryRepository
                            .QUERY_LIMIT_WITH_OVERFLOW_DETECTION
            );

    if (spans.isEmpty()) {
      throw new TraceNotFoundException();
    }

    if (
            spans.size()
                    > JdbcTraceDetailQueryRepository
                    .MAX_SPAN_LIMIT
    ) {
      throw new TraceSpanLimitExceededException(
              JdbcTraceDetailQueryRepository
                      .MAX_SPAN_LIMIT
      );
    }

    return List.copyOf(spans);
  }
}