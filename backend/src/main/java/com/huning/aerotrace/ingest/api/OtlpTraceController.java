package com.huning.aerotrace.ingest.api;

import com.huning.aerotrace.ingest.application.OtlpTraceRequestParser;
import com.huning.aerotrace.ingest.application.ParsedTraceRequest;
import com.huning.aerotrace.ingest.application.TraceIngestionService;
import com.huning.aerotrace.ingest.domain.ParsedSpan;
import com.huning.aerotrace.ingest.application.SpanWriteResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.huning.aerotrace.auth.application.AuthenticatedProject;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;

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
          @RequestAttribute(
                  OtlpRequestAttributes.AUTHENTICATED_PROJECT
          )
          AuthenticatedProject authenticatedProject,

          @RequestBody
          JsonNode request
  ) {
    long parseStartedAt = System.nanoTime();

    ParsedTraceRequest parsedRequest =
            requestParser.parse(request);

    long parseNanos =
            System.nanoTime() - parseStartedAt;

    long ingestEnvelopeStartedAt =
            System.nanoTime();

    SpanWriteResult writeResult =
            ingestionService.ingest(
                    authenticatedProject.tenantId(),
                    authenticatedProject.projectId(),
                    parsedRequest
            );

    long ingestEnvelopeNanos =
            System.nanoTime() - ingestEnvelopeStartedAt;

    log.info(
            "OTLP trace timing: received={}, parseNanos={}, ingestEnvelopeNanos={}",
            writeResult.requestedCount(),
            parseNanos,
            ingestEnvelopeNanos
    );

    if (parsedRequest.spans().isEmpty()) {
      log.info(
              "OTLP trace request stored: received=0, inserted=0, duplicates=0, unknown=0"
      );
    } else {
      ParsedSpan firstSpan =
              parsedRequest.spans().getFirst();

      log.info(
              "OTLP trace request stored: received={}, inserted={}, duplicates={}, unknown={}, firstEvents={}, firstLinks={}",
              writeResult.requestedCount(),
              writeResult.insertedCount(),
              writeResult.duplicateCount(),
              writeResult.unknownSuccessCount(),
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