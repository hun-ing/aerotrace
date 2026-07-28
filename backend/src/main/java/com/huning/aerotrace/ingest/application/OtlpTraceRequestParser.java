package com.huning.aerotrace.ingest.application;

import com.huning.aerotrace.ingest.domain.ParsedSpan;
import com.huning.aerotrace.ingest.domain.ParsedSpanEvent;
import com.huning.aerotrace.ingest.domain.ParsedSpanLink;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.Map;

@Component
public class OtlpTraceRequestParser {

  private static final BigInteger MAX_UNSIGNED_INT = new BigInteger("4294967295");
  private static final long NANOS_PER_SECOND = 1_000_000_000L;
  private final OtlpAnyValueParser anyValueParser;
  private final int maxSpansPerRequest;

  public OtlpTraceRequestParser(
          OtlpAnyValueParser anyValueParser,
          @Value(
                  "${aerotrace.ingest.max-spans-per-request:5000}"
          )
          int maxSpansPerRequest
  ) {
    if (maxSpansPerRequest <= 0) {
      throw new IllegalArgumentException(
              "Maximum Span count per OTLP request "
                      + "must be greater than zero"
      );
    }

    this.anyValueParser = anyValueParser;
    this.maxSpansPerRequest =
            maxSpansPerRequest;
  }

  private static final Pattern TRACE_ID_PATTERN =
          Pattern.compile("^[0-9a-fA-F]{32}$");

  private static final Pattern SPAN_ID_PATTERN =
          Pattern.compile("^[0-9a-fA-F]{16}$");

  public ParsedTraceRequest parse(JsonNode root) {
    requireObject(root, "$");

    JsonNode resourceSpans = root.get("resourceSpans");

    if (isMissing(resourceSpans)) {
      return new ParsedTraceRequest(List.of());
    }

    requireArray(resourceSpans, "$.resourceSpans");

    List<ParsedSpan> parsedSpans = new ArrayList<>();

    for (int resourceIndex = 0;
         resourceIndex < resourceSpans.size();
         resourceIndex++) {

      JsonNode resourceSpan = resourceSpans.get(resourceIndex);
      String resourcePath = "$.resourceSpans[" + resourceIndex + "]";

      requireObject(resourceSpan, resourcePath);

      ResourceDetails resourceDetails = parseResource(
              resourceSpan.get("resource"),
              resourcePath + ".resource"
      );

      JsonNode scopeSpans = resourceSpan.get("scopeSpans");

      if (isMissing(scopeSpans)) {
        continue;
      }

      String scopeSpansPath = resourcePath + ".scopeSpans";
      requireArray(scopeSpans, scopeSpansPath);

      parseScopeSpans(
              scopeSpans,
              scopeSpansPath,
              resourceDetails,
              parsedSpans
      );
    }

    return new ParsedTraceRequest(parsedSpans);
  }

  private void parseScopeSpans(
          JsonNode scopeSpans,
          String scopeSpansPath,
          ResourceDetails resourceDetails,
          List<ParsedSpan> parsedSpans
  ) {
    for (int scopeIndex = 0;
         scopeIndex < scopeSpans.size();
         scopeIndex++) {

      JsonNode scopeSpan = scopeSpans.get(scopeIndex);
      String scopePath = scopeSpansPath + "[" + scopeIndex + "]";

      requireObject(scopeSpan, scopePath);

      ScopeDetails scopeDetails = parseScope(
              scopeSpan.get("scope"),
              scopePath + ".scope"
      );

      JsonNode spans = scopeSpan.get("spans");

      if (isMissing(spans)) {
        continue;
      }

      String spansPath = scopePath + ".spans";
      requireArray(spans, spansPath);

      for (int spanIndex = 0;
           spanIndex < spans.size();
           spanIndex++) {

        String spanPath = spansPath + "[" + spanIndex + "]";
        JsonNode span = spans.get(spanIndex);

        requireObject(span, spanPath);

        if (resourceDetails.serviceName() == null) {
          throw invalidRequest(
                  spanPath
                          + " requires resource attribute service.name"
          );
        }

        checkSpanLimit(
                parsedSpans.size()
        );

        parsedSpans.add(
                parseSpan(
                        span,
                        spanPath,
                        resourceDetails,
                        scopeDetails
                )
        );
      }
    }
  }

  private void checkSpanLimit(
          int currentSpanCount
  ) {
    if (
            currentSpanCount
                    >= maxSpansPerRequest
    ) {
      throw new OtlpSpanLimitExceededException(
              maxSpansPerRequest
      );
    }
  }

  private ParsedSpan parseSpan(
          JsonNode span,
          String path,
          ResourceDetails resourceDetails,
          ScopeDetails scopeDetails
  ) {
    Map<String, Object> spanAttributes =
            anyValueParser.parseAttributes(
                    span.get("attributes"),
                    path + ".attributes"
            );

    List<ParsedSpanEvent> events = parseEvents(
            span.get("events"),
            path + ".events"
    );

    List<ParsedSpanLink> links = parseLinks(
            span.get("links"),
            path + ".links"
    );

    long droppedAttributesCount = optionalUnsignedInt(
            span,
            "droppedAttributesCount",
            path,
            0
    );

    long droppedEventsCount = optionalUnsignedInt(
            span,
            "droppedEventsCount",
            path,
            0
    );

    long droppedLinksCount = optionalUnsignedInt(
            span,
            "droppedLinksCount",
            path,
            0
    );

    String traceId = requiredHexId(
            span,
            "traceId",
            path,
            TRACE_ID_PATTERN,
            32
    );

    String spanId = requiredHexId(
            span,
            "spanId",
            path,
            SPAN_ID_PATTERN,
            16
    );

    String parentSpanId = optionalParentSpanId(span, path);

    String traceState = optionalString(
            span,
            "traceState",
            path,
            ""
    );

    long flags = optionalUnsignedInt(
            span,
            "flags",
            path,
            0
    );

    String name = requiredNonBlankString(
            span,
            "name",
            path
    );

    short spanKind = optionalCode(
            span,
            "kind",
            path,
            0,
            5,
            (short) 0
    );

    StatusDetails status = parseStatus(
            span.get("status"),
            path + ".status"
    );

    long startTimeUnixNano = requiredNanoTimestamp(
            span,
            "startTimeUnixNano",
            path
    );

    long endTimeUnixNano = requiredNanoTimestamp(
            span,
            "endTimeUnixNano",
            path
    );

    if (endTimeUnixNano < startTimeUnixNano) {
      throw invalidRequest(
              path + ".endTimeUnixNano must be greater than "
                      + "or equal to startTimeUnixNano"
      );
    }

    final long durationNano;

    try {
      durationNano = Math.subtractExact(
              endTimeUnixNano,
              startTimeUnixNano
      );
    } catch (ArithmeticException exception) {
      throw invalidRequest(
              path + " duration exceeds the supported range"
      );
    }

    return new ParsedSpan(
            resourceDetails.serviceName(),
            resourceDetails.attributes(),
            spanAttributes,
            events,
            links,
            scopeDetails.name(),
            scopeDetails.version(),
            traceId,
            spanId,
            parentSpanId,
            traceState,
            flags,
            name,
            spanKind,
            status.code(),
            status.message(),
            toInstant(startTimeUnixNano),
            toInstant(endTimeUnixNano),
            durationNano,
            droppedAttributesCount,
            droppedEventsCount,
            droppedLinksCount
    );
  }

  private List<ParsedSpanEvent> parseEvents(
          JsonNode eventsNode,
          String path
  ) {
    if (isMissing(eventsNode)) {
      return List.of();
    }

    requireArray(eventsNode, path);

    List<ParsedSpanEvent> events = new ArrayList<>();

    for (int index = 0; index < eventsNode.size(); index++) {
      JsonNode eventNode = eventsNode.get(index);
      String eventPath = path + "[" + index + "]";

      requireObject(eventNode, eventPath);

      long timeUnixNano = requiredNanoTimestamp(
              eventNode,
              "timeUnixNano",
              eventPath
      );

      String name = requiredNonBlankString(
              eventNode,
              "name",
              eventPath
      );

      Map<String, Object> attributes =
              anyValueParser.parseAttributes(
                      eventNode.get("attributes"),
                      eventPath + ".attributes"
              );

      long droppedAttributesCount = optionalUnsignedInt(
              eventNode,
              "droppedAttributesCount",
              eventPath,
              0
      );

      events.add(
              new ParsedSpanEvent(
                      timeUnixNano,
                      name,
                      attributes,
                      droppedAttributesCount
              )
      );
    }

    return List.copyOf(events);
  }

  private List<ParsedSpanLink> parseLinks(
          JsonNode linksNode,
          String path
  ) {
    if (isMissing(linksNode)) {
      return List.of();
    }

    requireArray(linksNode, path);

    List<ParsedSpanLink> links = new ArrayList<>();

    for (int index = 0; index < linksNode.size(); index++) {
      JsonNode linkNode = linksNode.get(index);
      String linkPath = path + "[" + index + "]";

      requireObject(linkNode, linkPath);

      String traceId = requiredHexId(
              linkNode,
              "traceId",
              linkPath,
              TRACE_ID_PATTERN,
              32
      );

      String spanId = requiredHexId(
              linkNode,
              "spanId",
              linkPath,
              SPAN_ID_PATTERN,
              16
      );

      String traceState = optionalString(
              linkNode,
              "traceState",
              linkPath,
              ""
      );

      Map<String, Object> attributes =
              anyValueParser.parseAttributes(
                      linkNode.get("attributes"),
                      linkPath + ".attributes"
              );

      long droppedAttributesCount = optionalUnsignedInt(
              linkNode,
              "droppedAttributesCount",
              linkPath,
              0
      );

      long flags = optionalUnsignedInt(
              linkNode,
              "flags",
              linkPath,
              0
      );

      links.add(
              new ParsedSpanLink(
                      traceId,
                      spanId,
                      traceState,
                      attributes,
                      droppedAttributesCount,
                      flags
              )
      );
    }

    return List.copyOf(links);
  }

  private long optionalUnsignedInt(
          JsonNode object,
          String fieldName,
          String path,
          long defaultValue
  ) {
    JsonNode node = object.get(fieldName);

    if (isMissing(node)) {
      return defaultValue;
    }

    if (!node.isIntegralNumber()) {
      throw invalidRequest(
              path + "." + fieldName
                      + " must be a JSON integer"
      );
    }

    BigInteger value = node.bigIntegerValue();

    if (
            value.signum() < 0
                    || value.compareTo(MAX_UNSIGNED_INT) > 0
    ) {
      throw invalidRequest(
              path + "." + fieldName
                      + " must be between 0 and 4294967295"
      );
    }

    return value.longValue();
  }

  private ResourceDetails parseResource(
          JsonNode resource,
          String path
  ) {
    if (isMissing(resource)) {
      return new ResourceDetails(
              null,
              Map.of()
      );
    }

    requireObject(resource, path);

    Map<String, Object> attributes =
            anyValueParser.parseAttributes(
                    resource.get("attributes"),
                    path + ".attributes"
            );

    Object serviceNameValue =
            attributes.get("service.name");

    if (serviceNameValue == null) {
      return new ResourceDetails(
              null,
              attributes
      );
    }

    if (!(serviceNameValue instanceof String serviceName)) {
      throw invalidRequest(
              path
                      + ".attributes service.name must be a string"
      );
    }

    if (serviceName.isBlank()) {
      throw invalidRequest(
              path
                      + ".attributes service.name must not be blank"
      );
    }

    return new ResourceDetails(
            serviceName,
            attributes
    );
  }

  private ScopeDetails parseScope(
          JsonNode scope,
          String path
  ) {
    if (isMissing(scope)) {
      return new ScopeDetails("", "");
    }

    requireObject(scope, path);

    return new ScopeDetails(
            optionalString(scope, "name", path, ""),
            optionalString(scope, "version", path, "")
    );
  }

  private StatusDetails parseStatus(
          JsonNode status,
          String path
  ) {
    if (isMissing(status)) {
      return new StatusDetails((short) 0, "");
    }

    requireObject(status, path);

    return new StatusDetails(
            optionalCode(
                    status,
                    "code",
                    path,
                    0,
                    2,
                    (short) 0
            ),
            optionalString(
                    status,
                    "message",
                    path,
                    ""
            )
    );
  }

  private String requiredHexId(
          JsonNode object,
          String fieldName,
          String path,
          Pattern pattern,
          int expectedLength
  ) {
    String value = requiredNonBlankString(
            object,
            fieldName,
            path
    );

    if (!pattern.matcher(value).matches()) {
      throw invalidRequest(
              path + "." + fieldName
                      + " must be a "
                      + expectedLength
                      + "-character hexadecimal string"
      );
    }

    String normalized = value.toLowerCase(Locale.ROOT);

    if (normalized.chars().allMatch(character -> character == '0')) {
      throw invalidRequest(
              path + "." + fieldName + " must not be all zeros"
      );
    }

    return normalized;
  }

  private String optionalParentSpanId(
          JsonNode span,
          String path
  ) {
    JsonNode node = span.get("parentSpanId");

    if (isMissing(node)) {
      return null;
    }

    if (!node.isString()) {
      throw invalidRequest(
              path + ".parentSpanId must be a JSON string"
      );
    }

    String value = node.asString();

    if (value.isBlank()) {
      return null;
    }

    if (!SPAN_ID_PATTERN.matcher(value).matches()) {
      throw invalidRequest(
              path
                      + ".parentSpanId must be a "
                      + "16-character hexadecimal string"
      );
    }

    String normalized = value.toLowerCase(Locale.ROOT);

    if (normalized.chars().allMatch(character -> character == '0')) {
      throw invalidRequest(
              path + ".parentSpanId must not be all zeros"
      );
    }

    return normalized;
  }

  private long requiredNanoTimestamp(
          JsonNode object,
          String fieldName,
          String path
  ) {
    JsonNode node = object.get(fieldName);

    if (node == null || !node.isString()) {
      throw invalidRequest(
              path + "." + fieldName
                      + " must be a decimal string"
      );
    }

    final long value;

    try {
      value = Long.parseLong(node.asString());
    } catch (NumberFormatException exception) {
      throw invalidRequest(
              path + "." + fieldName
                      + " must fit in a signed 64-bit integer"
      );
    }

    if (value <= 0) {
      throw invalidRequest(
              path + "." + fieldName + " must be greater than zero"
      );
    }

    return value;
  }

  private short optionalCode(
          JsonNode object,
          String fieldName,
          String path,
          int minimum,
          int maximum,
          short defaultValue
  ) {
    JsonNode node = object.get(fieldName);

    if (isMissing(node)) {
      return defaultValue;
    }

    if (!node.isNumber()) {
      throw invalidRequest(
              path + "." + fieldName + " must be a JSON number"
      );
    }

    final int value;

    try {
      value = node.intValue();
    } catch (RuntimeException exception) {
      throw invalidRequest(
              path + "." + fieldName + " must be an integer"
      );
    }

    if (value < minimum || value > maximum) {
      throw invalidRequest(
              path + "." + fieldName
                      + " must be between "
                      + minimum
                      + " and "
                      + maximum
      );
    }

    return (short) value;
  }

  private String requiredNonBlankString(
          JsonNode object,
          String fieldName,
          String path
  ) {
    JsonNode node = object.get(fieldName);

    if (node == null || !node.isString()) {
      throw invalidRequest(
              path + "." + fieldName
                      + " must be a JSON string"
      );
    }

    String value = node.asString();

    if (value.isBlank()) {
      throw invalidRequest(
              path + "." + fieldName + " must not be blank"
      );
    }

    return value;
  }

  private String optionalString(
          JsonNode object,
          String fieldName,
          String path,
          String defaultValue
  ) {
    JsonNode node = object.get(fieldName);

    if (isMissing(node)) {
      return defaultValue;
    }

    if (!node.isString()) {
      throw invalidRequest(
              path + "." + fieldName
                      + " must be a JSON string"
      );
    }

    return node.asString();
  }

  private Instant toInstant(long unixNano) {
    long epochSecond = unixNano / NANOS_PER_SECOND;
    long nanoAdjustment = unixNano % NANOS_PER_SECOND;

    return Instant.ofEpochSecond(
            epochSecond,
            nanoAdjustment
    );
  }

  private static boolean isMissing(JsonNode node) {
    return node == null || node.isNull();
  }

  private static void requireObject(
          JsonNode node,
          String path
  ) {
    if (node == null || !node.isObject()) {
      throw invalidRequest(
              path + " must be a JSON object"
      );
    }
  }

  private static void requireArray(
          JsonNode node,
          String path
  ) {
    if (!node.isArray()) {
      throw invalidRequest(
              path + " must be a JSON array"
      );
    }
  }

  private static OtlpInvalidRequestException invalidRequest(
          String message
  ) {
    return new OtlpInvalidRequestException(
            message
    );
  }

  private record ScopeDetails(
          String name,
          String version
  ) {
  }

  private record StatusDetails(
          short code,
          String message
  ) {
  }

  private record ResourceDetails(
          String serviceName,
          Map<String, Object> attributes
  ) {
  }
}