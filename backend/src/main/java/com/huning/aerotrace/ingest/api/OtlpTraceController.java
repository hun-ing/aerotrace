package com.huning.aerotrace.ingest.api;

import com.huning.aerotrace.ingest.application.OtlpTraceRequestParser;
import com.huning.aerotrace.ingest.application.ParsedTraceRequest;
import com.huning.aerotrace.ingest.application.TraceIngestionService;
import com.huning.aerotrace.ingest.domain.ParsedSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;

@RestController
public class OtlpTraceController {

  private static final Logger log =
          LoggerFactory.getLogger(OtlpTraceController.class);

  private final OtlpTraceRequestParser requestParser;
  private final TraceIngestionService ingestionService;

  public OtlpTraceController(
          OtlpTraceRequestParser requestParser,
          TraceIngestionService ingestionService
  ) {
    this.requestParser = requestParser;
    this.ingestionService = ingestionService;
  }

  @PostMapping(
          path = "/v1/traces",
          consumes = MediaType.APPLICATION_JSON_VALUE,
          produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Map<String, Object>> exportTraces(
          @RequestHeader("X-AeroTrace-Tenant-Id")
          UUID tenantId,

          @RequestHeader("X-AeroTrace-Project-Id")
          UUID projectId,

          @RequestBody
          JsonNode request
  ) {
    ParsedTraceRequest parsedRequest =
            requestParser.parse(request);

    int insertedCount = ingestionService.ingest(
            tenantId,
            projectId,
            parsedRequest
    );

    int duplicateCount =
            parsedRequest.spanCount() - insertedCount;

    if (parsedRequest.spans().isEmpty()) {
      log.info(
              "OTLP trace request stored: received=0, inserted=0, duplicates=0"
      );
    } else {
      ParsedSpan firstSpan = parsedRequest.spans().getFirst();

      log.info(
              "OTLP trace request stored: received={}, inserted={}, duplicates={}, firstEvents={}, firstLinks={}",
              parsedRequest.spanCount(),
              insertedCount,
              duplicateCount,
              firstSpan.events().size(),
              firstSpan.links().size()
      );
    }

    return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of());
  }
}