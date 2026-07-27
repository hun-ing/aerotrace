package com.huning.aerotrace.ingest.application;

import com.huning.aerotrace.ingest.domain.ParsedSpan;
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
  public int ingest(
          UUID tenantId,
          UUID projectId,
          ParsedTraceRequest request
  ) {
    int insertedCount = 0;

    for (ParsedSpan span : request.spans()) {
      boolean inserted = spanWriter.insert(
              tenantId,
              projectId,
              span
      );

      if (inserted) {
        insertedCount++;
      }
    }

    return insertedCount;
  }
}