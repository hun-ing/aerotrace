package com.huning.aerotrace.ingest.api;

import com.huning.aerotrace.ingest.application.OtlpTraceRequestParser;
import com.huning.aerotrace.ingest.application.ParsedTraceRequest;
import com.huning.aerotrace.ingest.domain.ParsedSpan;
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

  private final OtlpTraceRequestParser requestParser;

  public OtlpTraceController(
          OtlpTraceRequestParser requestParser
  ) {
    this.requestParser = requestParser;
  }

  @PostMapping(
          path = "/v1/traces",
          consumes = MediaType.APPLICATION_JSON_VALUE,
          produces = MediaType.APPLICATION_JSON_VALUE
  )
  public ResponseEntity<Map<String, Object>> exportTraces(
          @RequestBody JsonNode request
  ) {
    ParsedTraceRequest parsedRequest =
            requestParser.parse(request);

    if (parsedRequest.spans().isEmpty()) {
      log.info("Empty OTLP trace request accepted");
    } else {
      ParsedSpan firstSpan = parsedRequest.spans().getFirst();

      log.info(
              "OTLP trace request parsed: spans={}, firstService={}, firstSpan={}, resourceAttributes={}, spanAttributes={}",
              parsedRequest.spanCount(),
              firstSpan.serviceName(),
              firstSpan.name(),
              firstSpan.resourceAttributes().size(),
              firstSpan.spanAttributes().size()
      );
    }

    return ResponseEntity
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of());
  }
}