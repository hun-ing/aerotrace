package com.huning.aerotrace.auth.application;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnboardingInviteTokenServiceTest {

  @Test
  void generatesA256BitTokenAndStoresOnlyItsHashMaterial() {
    OnboardingInviteTokenService service =
            new OnboardingInviteTokenService(
                    new SecureRandom()
            );

    OnboardingInviteTokenService.GeneratedInviteToken first =
            service.generate();

    OnboardingInviteTokenService.GeneratedInviteToken second =
            service.generate();

    assertTrue(
            first.rawToken().matches(
                    "^ati_[A-Za-z0-9_-]{43}$"
            )
    );

    assertNotEquals(
            first.rawToken(),
            second.rawToken()
    );

    assertArrayEquals(
            first.tokenHash(),
            service.hash(first.rawToken()).orElseThrow()
    );

    assertFalse(
            Arrays.equals(
                    first.tokenHash(),
                    first.rawToken().getBytes(
                            StandardCharsets.UTF_8
                    )
            )
    );

    assertFalse(service.hash("invalid").isPresent());

    assertFalse(
            first.toString().contains(first.rawToken())
    );

    assertTrue(
            first.toString().contains("rawToken=<redacted>")
    );

    assertTrue(
            first.toString().contains("tokenHash=<redacted>")
    );
  }

  @Test
  void protectsTheReturnedHashWithDefensiveCopies() {
    OnboardingInviteTokenService.GeneratedInviteToken generated =
            new OnboardingInviteTokenService().generate();

    byte[] firstRead = generated.tokenHash();
    byte originalFirstByte = firstRead[0];
    firstRead[0] = (byte) (firstRead[0] ^ 0x7f);

    assertNotEquals(
            firstRead[0],
            generated.tokenHash()[0]
    );

    assertArrayEquals(
            generated.tokenHash(),
            new OnboardingInviteTokenService()
                    .hash(generated.rawToken())
                    .orElseThrow()
    );

    assertNotEquals(
            firstRead[0],
            originalFirstByte
    );
  }
}
