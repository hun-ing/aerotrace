package com.huning.aerotrace.trace.query;

import com.huning.aerotrace.auth.application.AccessibleProject;
import com.huning.aerotrace.auth.application.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.huning.aerotrace.trace.query.TraceQueryParameters.*;

@Service
@Transactional(readOnly = true)
public class SessionTraceQueryService {
  private final WorkspaceService workspaces;
  private final TraceQueryService traces;
  private final TraceDetailQueryService details;

  public SessionTraceQueryService(WorkspaceService workspaces, TraceQueryService traces,
                                 TraceDetailQueryService details) {
    this.workspaces = workspaces;
    this.traces = traces;
    this.details = details;
  }

  public TraceListResponse list(UUID userId, UUID projectId, String from, String to,
                                String limit, String cursor, String serviceName,
                                String errorOnly, String minSpanDurationNano) {
    AccessibleProject scope = workspaces.project(userId, projectId);
    TraceListPage page = traces.findTracePage(scope,
            parseInstant(from, "from"), parseInstant(to, "to"),
            cursor == null ? null : TraceListCursorCodec.decode(cursor), serviceName,
            parseBoolean(errorOnly, "errorOnly"),
            parseOptionalNonNegativeLong(minSpanDurationNano, "minSpanDurationNano"),
            parseLimit(limit));
    return TraceListResponse.from(page.items(),
            page.nextCursor() == null ? null : TraceListCursorCodec.encode(page.nextCursor()));
  }

  public TraceDetailResponse detail(UUID userId, UUID projectId, String traceId) {
    AccessibleProject scope = workspaces.project(userId, projectId);
    return TraceDetailResponse.from(traceId, details.findTraceSpans(scope, traceId));
  }
}
