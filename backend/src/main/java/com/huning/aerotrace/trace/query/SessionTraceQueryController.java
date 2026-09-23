package com.huning.aerotrace.trace.query;

import com.huning.aerotrace.auth.application.AeroTracePrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "aerotrace.auth.enabled", havingValue = "true")
@RequestMapping("/api/v1/projects/{projectId}/traces")
public class SessionTraceQueryController {
  private final SessionTraceQueryService service;

  public SessionTraceQueryController(SessionTraceQueryService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<TraceListResponse> list(
          @AuthenticationPrincipal AeroTracePrincipal principal, @PathVariable UUID projectId,
          @RequestParam String from, @RequestParam String to,
          @RequestParam(defaultValue = "50") String limit,
          @RequestParam(required = false) String cursor,
          @RequestParam(required = false) String serviceName,
          @RequestParam(defaultValue = "false") String errorOnly,
          @RequestParam(required = false) String minSpanDurationNano) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.list(principal.userId(), projectId, from, to, limit, cursor,
                    serviceName, errorOnly, minSpanDurationNano));
  }

  @GetMapping("/{traceId}")
  public ResponseEntity<TraceDetailResponse> detail(
          @AuthenticationPrincipal AeroTracePrincipal principal, @PathVariable UUID projectId,
          @PathVariable String traceId) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.detail(principal.userId(), projectId, traceId));
  }
}
