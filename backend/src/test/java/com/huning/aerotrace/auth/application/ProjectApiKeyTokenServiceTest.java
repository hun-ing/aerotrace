package com.huning.aerotrace.auth.application;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectApiKeyTokenServiceTest {

  private final ProjectApiKeyTokenService tokenService =
          new ProjectApiKeyTokenService();

  @Test
  void generatesApiKeyWithExpectedFormat() {
    GeneratedProjectApiKey generated =
            tokenService.generate();

    assertNotNull(generated.keyId());
    assertNotNull(generated.rawKey());
    assertNotNull(generated.secretHash());

    assertEquals(
            16,
            generated.keyId().length()
    );

    assertEquals(
            32,
            generated.secretHash().length
    );

    assertTrue(
            generated.rawKey().startsWith(
                    "atr_"
            )
    );

    Optional<ParsedProjectApiKey> parsed =
            tokenService.parse(
                    generated.rawKey()
            );

    assertTrue(parsed.isPresent());

    assertEquals(
            generated.keyId(),
            parsed.orElseThrow().keyId()
    );
  }

  @Test
  void generatedSecretMatchesStoredHash() {
    GeneratedProjectApiKey generated =
            tokenService.generate();

    ParsedProjectApiKey parsed =
            tokenService.parse(
                    generated.rawKey()
            ).orElseThrow();

    assertTrue(
            tokenService.matchesSecret(
                    parsed.secret(),
                    generated.secretHash()
            )
    );
  }

  @Test
  void differentSecretDoesNotMatchStoredHash() {
    GeneratedProjectApiKey generated =
            tokenService.generate();

    assertFalse(
            tokenService.matchesSecret(
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                    generated.secretHash()
            )
    );
  }

  @Test
  void malformedApiKeysAreRejected() {
    assertTrue(
            tokenService.parse(null).isEmpty()
    );

    assertTrue(
            tokenService.parse("").isEmpty()
    );

    assertTrue(
            tokenService.parse("invalid").isEmpty()
    );

    assertTrue(
            tokenService.parse(
                    "atr_short.secret"
            ).isEmpty()
    );

    assertTrue(
            tokenService.parse(
                    "other_1234567890123456."
                            + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            ).isEmpty()
    );
  }

  @Test
  void secretHashIsDefensivelyCopied() {
    GeneratedProjectApiKey generated =
            tokenService.generate();

    byte[] firstRead =
            generated.secretHash();

    byte[] originalHash =
            firstRead.clone();

    firstRead[0] =
            (byte) (firstRead[0] ^ 0xFF);

    assertArrayEquals(
            originalHash,
            generated.secretHash()
    );
  }

  @Test
  void toStringDoesNotExposeSecrets() {
    GeneratedProjectApiKey generated =
            tokenService.generate();

    ParsedProjectApiKey parsed =
            tokenService.parse(
                    generated.rawKey()
            ).orElseThrow();

    assertFalse(
            generated.toString().contains(
                    generated.rawKey()
            )
    );

    assertFalse(
            parsed.toString().contains(
                    parsed.secret()
            )
    );

    assertTrue(
            generated.toString().contains(
                    "<redacted>"
            )
    );

    assertTrue(
            parsed.toString().contains(
                    "<redacted>"
            )
    );
  }

  @Test
  void generatedKeysUseDifferentRandomValues() {
    GeneratedProjectApiKey first =
            tokenService.generate();

    GeneratedProjectApiKey second =
            tokenService.generate();

    assertNotEquals(
            first.keyId(),
            second.keyId()
    );

    assertNotEquals(
            first.rawKey(),
            second.rawKey()
    );
  }
}