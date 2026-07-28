package com.huning.aerotrace.ingest.application;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OtlpAnyValueParser {

  public Map<String, Object> parseAttributes(
          JsonNode attributesNode,
          String path
  ) {
    if (isMissing(attributesNode)) {
      return Map.of();
    }

    requireArray(attributesNode, path);

    Map<String, Object> attributes = new LinkedHashMap<>();

    for (int index = 0; index < attributesNode.size(); index++) {
      JsonNode attributeNode = attributesNode.get(index);
      String attributePath = path + "[" + index + "]";

      requireObject(attributeNode, attributePath);

      String key = requiredNonBlankString(
              attributeNode,
              "key",
              attributePath
      );

      if (attributes.containsKey(key)) {
        throw invalidRequest(
                attributePath + ".key contains duplicate key: " + key
        );
      }

      JsonNode valueNode = attributeNode.get("value");

      if (isMissing(valueNode)) {
        throw invalidRequest(
                attributePath + ".value is required"
        );
      }

      attributes.put(
              key,
              parseAnyValue(
                      valueNode,
                      attributePath + ".value"
              )
      );
    }

    return Map.copyOf(attributes);
  }

  private Object parseAnyValue(
          JsonNode valueNode,
          String path
  ) {
    requireObject(valueNode, path);

    Object parsedValue = null;
    int recognizedFieldCount = 0;

    JsonNode stringValue = valueNode.get("stringValue");

    if (!isMissing(stringValue)) {
      recognizedFieldCount++;
      parsedValue = parseStringValue(
              stringValue,
              path + ".stringValue"
      );
    }

    JsonNode boolValue = valueNode.get("boolValue");

    if (!isMissing(boolValue)) {
      recognizedFieldCount++;
      parsedValue = parseBooleanValue(
              boolValue,
              path + ".boolValue"
      );
    }

    JsonNode intValue = valueNode.get("intValue");

    if (!isMissing(intValue)) {
      recognizedFieldCount++;
      parsedValue = parseIntegerValue(
              intValue,
              path + ".intValue"
      );
    }

    JsonNode doubleValue = valueNode.get("doubleValue");

    if (!isMissing(doubleValue)) {
      recognizedFieldCount++;
      parsedValue = parseDoubleValue(
              doubleValue,
              path + ".doubleValue"
      );
    }

    JsonNode bytesValue = valueNode.get("bytesValue");

    if (!isMissing(bytesValue)) {
      recognizedFieldCount++;
      parsedValue = parseBytesValue(
              bytesValue,
              path + ".bytesValue"
      );
    }

    JsonNode arrayValue = valueNode.get("arrayValue");

    if (!isMissing(arrayValue)) {
      recognizedFieldCount++;
      parsedValue = parseArrayValue(
              arrayValue,
              path + ".arrayValue"
      );
    }

    JsonNode kvlistValue = valueNode.get("kvlistValue");

    if (!isMissing(kvlistValue)) {
      recognizedFieldCount++;
      parsedValue = parseKeyValueList(
              kvlistValue,
              path + ".kvlistValue"
      );
    }

    if (recognizedFieldCount == 0) {
      throw invalidRequest(
              path + " does not contain a supported AnyValue field"
      );
    }

    if (recognizedFieldCount > 1) {
      throw invalidRequest(
              path + " must contain exactly one AnyValue field"
      );
    }

    return parsedValue;
  }

  private String parseStringValue(
          JsonNode node,
          String path
  ) {
    if (!node.isString()) {
      throw invalidRequest(
              path + " must be a JSON string"
      );
    }

    return node.asString();
  }

  private boolean parseBooleanValue(
          JsonNode node,
          String path
  ) {
    if (!node.isBoolean()) {
      throw invalidRequest(
              path + " must be a JSON boolean"
      );
    }

    return node.booleanValue();
  }

  private long parseIntegerValue(
          JsonNode node,
          String path
  ) {
    if (!node.isString()) {
      throw invalidRequest(
              path + " must be a decimal string"
      );
    }

    try {
      return Long.parseLong(node.asString());
    } catch (NumberFormatException exception) {
      throw invalidRequest(
              path + " must fit in a signed 64-bit integer"
      );
    }
  }

  private Object parseDoubleValue(
          JsonNode node,
          String path
  ) {
    if (node.isNumber()) {
      return node.doubleValue();
    }

    if (node.isString()) {
      String value = node.asString();

      if (
              "NaN".equals(value)
                      || "Infinity".equals(value)
                      || "-Infinity".equals(value)
      ) {
        // PostgreSQL JSONB는 비유한 실수 값을 숫자로 저장하지
        // 못하므로 OTLP 표현을 문자열 그대로 보존한다.
        return value;
      }
    }

    throw invalidRequest(
            path + " must be a JSON number or a supported special value"
    );
  }

  private String parseBytesValue(
          JsonNode node,
          String path
  ) {
    if (!node.isString()) {
      throw invalidRequest(
              path + " must be a Base64 string"
      );
    }

    String value = node.asString();

    try {
      Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException exception) {
      throw invalidRequest(
              path + " must contain valid Base64 data"
      );
    }

    return value;
  }

  private List<Object> parseArrayValue(
          JsonNode arrayValue,
          String path
  ) {
    requireObject(arrayValue, path);

    JsonNode values = arrayValue.get("values");

    if (isMissing(values)) {
      return List.of();
    }

    requireArray(values, path + ".values");

    List<Object> parsedValues = new ArrayList<>();

    for (int index = 0; index < values.size(); index++) {
      parsedValues.add(
              parseAnyValue(
                      values.get(index),
                      path + ".values[" + index + "]"
              )
      );
    }

    return List.copyOf(parsedValues);
  }

  private Map<String, Object> parseKeyValueList(
          JsonNode kvlistValue,
          String path
  ) {
    requireObject(kvlistValue, path);

    return parseAttributes(
            kvlistValue.get("values"),
            path + ".values"
    );
  }

  private String requiredNonBlankString(
          JsonNode object,
          String fieldName,
          String path
  ) {
    JsonNode node = object.get(fieldName);

    if (node == null || !node.isString()) {
      throw invalidRequest(
              path + "." + fieldName + " must be a JSON string"
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
    if (node == null || !node.isArray()) {
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
}