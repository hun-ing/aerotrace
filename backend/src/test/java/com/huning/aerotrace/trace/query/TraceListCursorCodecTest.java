package com.huning.aerotrace.trace.query;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceListCursorCodecTest {

  private static final String QUERY_FINGERPRINT =
          "a".repeat(43);

  @Test
  void encodesAndDecodesCursor() {
    TraceListCursor cursor =
            new TraceListCursor(
                    Instant.parse(
                            "2026-08-03T06:00:00.123456789Z"
                    ),
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    QUERY_FINGERPRINT
            );

    String encoded =
            TraceListCursorCodec.encode(cursor);

    TraceListCursor decoded =
            TraceListCursorCodec.decode(encoded);

    assertThat(encoded)
            .isNotBlank();

    assertThat(encoded)
            .doesNotContain("=");

    assertThat(decoded)
            .isEqualTo(cursor);
  }

  @Test
  void rejectsCursorWithoutQueryFingerprint() {
    TraceListCursor cursor =
            new TraceListCursor(
                    Instant.parse(
                            "2026-08-03T06:00:00Z"
                    ),
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            );

    assertThatThrownBy(
            () -> TraceListCursorCodec.encode(
                    cursor
            )
    )
            .isInstanceOf(
                    IllegalArgumentException.class
            )
            .hasMessage(
                    "cursor query fingerprint is missing"
            );
  }

  @Test
  void rejectsMalformedBase64Cursor() {
    assertThatThrownBy(
            () -> TraceListCursorCodec.decode(
                    "%%%not-base64%%%"
            )
    )
            .isInstanceOf(
                    IllegalArgumentException.class
            )
            .hasMessage(
                    "cursor is invalid"
            );
  }

  @Test
  void rejectsVersionOneCursor() {
    String versionOnePayload =
            String.join(
                    "|",
                    "1",
                    "1785736800",
                    "0",
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            );

    String encoded =
            encodePayload(
                    versionOnePayload
            );

    assertThatThrownBy(
            () -> TraceListCursorCodec.decode(
                    encoded
            )
    )
            .isInstanceOf(
                    IllegalArgumentException.class
            )
            .hasMessage(
                    "cursor is invalid"
            );
  }

  @Test
  void rejectsCursorWithMalformedTraceId() {
    String payload =
            String.join(
                    "|",
                    "2",
                    "1785736800",
                    "0",
                    "invalid-trace-id",
                    QUERY_FINGERPRINT
            );

    String encoded =
            encodePayload(payload);

    assertThatThrownBy(
            () -> TraceListCursorCodec.decode(
                    encoded
            )
    )
            .isInstanceOf(
                    IllegalArgumentException.class
            )
            .hasMessage(
                    "cursor is invalid"
            );
  }

  @Test
  void rejectsCursorWithMalformedFingerprint() {
    String payload =
            String.join(
                    "|",
                    "2",
                    "1785736800",
                    "0",
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "invalid-fingerprint"
            );

    String encoded =
            encodePayload(payload);

    assertThatThrownBy(
            () -> TraceListCursorCodec.decode(
                    encoded
            )
    )
            .isInstanceOf(
                    IllegalArgumentException.class
            )
            .hasMessage(
                    "cursor is invalid"
            );
  }

  private static String encodePayload(
          String payload
  ) {
    return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                    payload.getBytes(
                            StandardCharsets.UTF_8
                    )
            );
  }
}