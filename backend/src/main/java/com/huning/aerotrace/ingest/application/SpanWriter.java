package com.huning.aerotrace.ingest.application;

import com.huning.aerotrace.ingest.domain.ParsedSpan;

import java.util.List;
import java.util.UUID;

public interface SpanWriter {

  SpanWriteResult insertBatch(
          UUID tenantId,
          UUID projectId,
          List<ParsedSpan> spans
  );
}