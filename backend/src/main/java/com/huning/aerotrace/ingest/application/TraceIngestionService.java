package com.huning.aerotrace.ingest.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TraceIngestionService {

  private final SpanWriter spanWriter;

  public TraceIngestionService(SpanWriter spanWriter) {
    this.spanWriter = spanWriter;
  }

  @Transactional
  public SpanWriteResult ingest(
          UUID tenantId,
          UUID projectId,
          ParsedTraceRequest request
  ) {
    return spanWriter.insertBatch(
            tenantId,
            projectId,
            request.spans()
    );
  }
}