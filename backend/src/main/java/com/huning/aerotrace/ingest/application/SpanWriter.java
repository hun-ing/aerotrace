package com.huning.aerotrace.ingest.application;

import com.huning.aerotrace.ingest.domain.ParsedSpan;

import java.util.UUID;

public interface SpanWriter {

  boolean insert(
          UUID tenantId,
          UUID projectId,
          ParsedSpan span
  );
}