package com.huning.aerotrace.trace.query;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

public final class TraceListCursorCodec {

  private static final String VERSION = "1";

  private static final String DELIMITER = "|";

  private static final int PAYLOAD_PART_COUNT = 4;

  private static final int MAX_ENCODED_LENGTH = 256;

  private static final int MAX_DECODED_LENGTH = 128;

  private static final Base64.Encoder ENCODER =
          Base64.getUrlEncoder()
                  .withoutPadding();

  private static final Base64.Decoder DECODER =
          Base64.getUrlDecoder();

  private TraceListCursorCodec() {
  }

  public static String encode(
          TraceListCursor cursor
  ) {
    Objects.requireNonNull(
            cursor,
            "cursor must not be null"
    );

    String payload =
            String.join(
                    DELIMITER,
                    VERSION,
                    Long.toString(
                            cursor.traceStartTime()
                                    .getEpochSecond()
                    ),
                    Integer.toString(
                            cursor.traceStartTime()
                                    .getNano()
                    ),
                    cursor.traceId()
            );

    return ENCODER.encodeToString(
            payload.getBytes(
                    StandardCharsets.UTF_8
            )
    );
  }

  public static TraceListCursor decode(
          String encodedCursor
  ) {
    if (
            encodedCursor == null
                    || encodedCursor.isBlank()
                    || encodedCursor.length()
                    > MAX_ENCODED_LENGTH
    ) {
      throw invalidCursor(null);
    }

    try {
      byte[] decodedBytes =
              DECODER.decode(encodedCursor);

      if (
              decodedBytes.length
                      > MAX_DECODED_LENGTH
      ) {
        throw new IllegalArgumentException(
                "Decoded cursor is too long"
        );
      }

      String payload =
              new String(
                      decodedBytes,
                      StandardCharsets.UTF_8
              );

      String[] parts =
              payload.split(
                      "\\|",
                      -1
              );

      if (
              parts.length
                      != PAYLOAD_PART_COUNT
                      || !VERSION.equals(parts[0])
      ) {
        throw new IllegalArgumentException(
                "Unsupported cursor payload"
        );
      }

      long epochSecond =
              Long.parseLong(parts[1]);

      int nano =
              Integer.parseInt(parts[2]);

      if (
              nano < 0
                      || nano > 999_999_999
      ) {
        throw new IllegalArgumentException(
                "Invalid cursor nanosecond"
        );
      }

      Instant traceStartTime =
              Instant.ofEpochSecond(
                      epochSecond,
                      nano
              );

      return new TraceListCursor(
              traceStartTime,
              parts[3]
      );
    } catch (RuntimeException exception) {
      throw invalidCursor(exception);
    }
  }

  private static IllegalArgumentException
  invalidCursor(
          RuntimeException cause
  ) {
    if (cause == null) {
      return new IllegalArgumentException(
              "cursor is invalid"
      );
    }

    return new IllegalArgumentException(
            "cursor is invalid",
            cause
    );
  }
}