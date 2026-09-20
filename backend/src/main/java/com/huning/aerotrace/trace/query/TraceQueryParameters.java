package com.huning.aerotrace.trace.query;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

final class TraceQueryParameters {
  private TraceQueryParameters() {}

  static Instant parseInstant(
          String value,
          String parameterName
  ) {
    if (
            value == null
                    || value.isBlank()
    ) {
      throw new IllegalArgumentException(
              parameterName
                      + " must not be blank"
      );
    }

    try {
      return OffsetDateTime.parse(value)
              .toInstant();
    } catch (
            DateTimeParseException exception
    ) {
      throw new IllegalArgumentException(
              parameterName
                      + " must be an ISO-8601 "
                      + "date-time with an offset",
              exception
      );
    }
  }

  static int parseLimit(
          String value
  ) {
    try {
      return Integer.parseInt(value);
    } catch (
            NumberFormatException exception
    ) {
      throw new IllegalArgumentException(
              "limit must be an integer",
              exception
      );
    }
  }

  static boolean parseBoolean(
          String value,
          String parameterName
  ) {
    if (value == null) {
      throw new IllegalArgumentException(
              parameterName
                      + " must be true or false"
      );
    }

    String normalized =
            value.strip();

    if ("true".equalsIgnoreCase(normalized)) {
      return true;
    }

    if ("false".equalsIgnoreCase(normalized)) {
      return false;
    }

    throw new IllegalArgumentException(
            parameterName
                    + " must be true or false"
    );
  }

  static Long parseOptionalNonNegativeLong(
          String value,
          String parameterName
  ) {
    if (value == null) {
      return null;
    }

    String normalized =
            value.strip();

    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(
              parameterName
                      + " must not be blank"
      );
    }

    long parsedValue;

    try {
      parsedValue =
              Long.parseLong(normalized);
    } catch (
            NumberFormatException exception
    ) {
      throw new IllegalArgumentException(
              parameterName
                      + " must be an integer",
              exception
      );
    }

    if (parsedValue < 0) {
      throw new IllegalArgumentException(
              parameterName
                      + " must not be negative"
      );
    }

    return parsedValue;
  }
}
