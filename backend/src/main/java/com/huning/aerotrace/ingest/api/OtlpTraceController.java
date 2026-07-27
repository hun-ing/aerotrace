package com.huning.aerotrace.ingest.api;

import com.huning.aerotrace.ingest.application.OtlpTraceRequestInspector;
import com.huning.aerotrace.ingest.application.TraceRequestSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@RestController
public class OtlpTraceController {

  private static final Logger log =
          LoggerFactory.getLogger(OtlpTraceController.class);

  private final OtlpTraceRequestInspector requestInspector;

  public OtlpTraceController(
          OtlpTraceRequestInspector requestInspector
  ) {
    this.requestInspector = requestInspector;
  }

  @PostMapping(
          path = "/v1/traces",
          consumes = MediaType.APPLICATION_JSON_VALUE,
          produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Map<String, Object>> exportTraces(
          @RequestBody JsonNode request
  ) {
    TraceRequestSummary summary = requestInspector.inspect(request);

    log.info(
            "OTLP trace request accepted: resourceSpans={}, scopeSpans={}, spans={}",
            summary.resourceSpanCount(),
            summary.scopeSpanCount(),
            summary.spanCount()
    );

    return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of());
  }
}