package com.huning.aerotrace.ingest.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Component
public class OtlpTraceRequestInspector {

  public TraceRequestSummary inspect(JsonNode root) {
    requireObject(root, "$");

    JsonNode resourceSpans = root.get("resourceSpans");

    // Protobuf의 repeated 필드는 생략될 수 있다.
    // 빈 telemetry 요청도 OTLP에서는 성공 응답 대상이다.
    if (isMissing(resourceSpans)) {
      return new TraceRequestSummary(0, 0, 0);
    }

    requireArray(resourceSpans, "$.resourceSpans");

    int resourceSpanCount = resourceSpans.size();
    int scopeSpanCount = 0;
    int spanCount = 0;

    for (int resourceIndex = 0; resourceIndex < resourceSpans.size(); resourceIndex++) {
      JsonNode resourceSpan = resourceSpans.get(resourceIndex);
      String resourcePath = "$.resourceSpans[" + resourceIndex + "]";

      requireObject(resourceSpan, resourcePath);

      JsonNode scopeSpans = resourceSpan.get("scopeSpans");

      if (isMissing(scopeSpans)) {
        continue;
      }

      String scopeSpansPath = resourcePath + ".scopeSpans";
      requireArray(scopeSpans, scopeSpansPath);

      scopeSpanCount += scopeSpans.size();

      for (int scopeIndex = 0; scopeIndex < scopeSpans.size(); scopeIndex++) {
        JsonNode scopeSpan = scopeSpans.get(scopeIndex);
        String scopePath = scopeSpansPath + "[" + scopeIndex + "]";

        requireObject(scopeSpan, scopePath);

        JsonNode spans = scopeSpan.get("spans");

        if (isMissing(spans)) {
          continue;
        }

        String spansPath = scopePath + ".spans";
        requireArray(spans, spansPath);

        for (int spanIndex = 0; spanIndex < spans.size(); spanIndex++) {
          JsonNode span = spans.get(spanIndex);

          requireObject(
                  span,
                  spansPath + "[" + spanIndex + "]"
          );

          spanCount++;
        }
      }
    }

    return new TraceRequestSummary(
            resourceSpanCount,
            scopeSpanCount,
            spanCount
    );
  }

  private static boolean isMissing(JsonNode node) {
    return node == null || node.isNull();
  }

  private static void requireObject(JsonNode node, String path) {
    if (node == null || !node.isObject()) {
      throw invalidRequest(path + " must be a JSON object");
    }
  }

  private static void requireArray(JsonNode node, String path) {
    if (!node.isArray()) {
      throw invalidRequest(path + " must be a JSON array");
    }
  }

  private static ResponseStatusException invalidRequest(String message) {
    return new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            message
    );
  }
}