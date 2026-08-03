package com.huning.aerotrace.trace.query;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceListCursorCodecTest {

  @Test
  void encodesAndDecodesCursor() {
    TraceListCursor cursor =
            new TraceListCursor(
                    Instant.parse(
                            "2026-08-03T06:00:00.123456789Z"
                    ),
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
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
  void rejectsUnsupportedCursorVersion() {
    String unsupportedPayload =
            String.join(
                    "|",
                    "2",
                    "1785736800",
                    "0",
                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            );

    String encoded =
            Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            unsupportedPayload.getBytes(
                                    StandardCharsets.UTF_8
                            )
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
                    "1",
                    "1785736800",
                    "0",
                    "invalid-trace-id"
            );

    String encoded =
            Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            payload.getBytes(
                                    StandardCharsets.UTF_8
                            )
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
}