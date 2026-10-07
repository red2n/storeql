package com.storeql.iam.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.web.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The password policy to NIST SP 800-63B-4: fifteen characters where the password is the only
 * factor, any character, no composition rule; not the login; screened against breaches through a
 * range lookup that sees five characters of the hash; a screen that cannot be reached lets the
 * password through rather than locking people out.
 */
class PasswordPolicyTest {

  private static final String BREACHED = "correct horse battery staple";
  private HttpServer server;
  private String base;
  private int calls;

  @BeforeEach
  void serveRanges() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    String sha1 = PasswordPolicy.sha1Hex(BREACHED);
    server.createContext(
        "/range/",
        ex -> {
          calls++;
          String prefix = ex.getRequestURI().getPath().replace("/range/", "");
          String body =
              prefix.equals(sha1.substring(0, 5))
                  ? "0018A45C4D1DEF81644B54AB7F969B88D65:0\r\n" + sha1.substring(5) + ":31337\r\n"
                  : "0018A45C4D1DEF81644B54AB7F969B88D65:0\r\n";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort() + "/range/";
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static String code(Runnable r) {
    ApiException e = assertThrows(ApiException.class, r::run);
    return e.code();
  }

  @Test
  void fifteenCharactersAnyOfThemAndNoCompositionRule() {
    PasswordPolicy p = PasswordPolicy.of(15, 128, false, base);
    assertEquals("PASSWORD_TOO_SHORT", code(() -> p.check("fourteen chars", "a@b.io")));
    assertDoesNotThrow(() -> p.check("fifteen charact", "a@b.io"));
    assertDoesNotThrow(
        () -> p.check("all lower case words no digit", "a@b.io"), "no composition rule");
    assertDoesNotThrow(() -> p.check("ünïcödé pässwörd ok", "a@b.io"), "any character");
    assertEquals("PASSWORD_TOO_LONG", code(() -> p.check("x".repeat(129), "a@b.io")));
    assertDoesNotThrow(() -> p.check("y".repeat(128), "a@b.io"));
  }

  @Test
  void theLoginIsNotAPassword() {
    PasswordPolicy p = PasswordPolicy.of(15, 128, false, base);
    assertEquals(
        "PASSWORD_IS_IDENTITY",
        code(() -> p.check("Margaret.Hale@mill.example", "margaret.hale@mill.example")));
    assertEquals(
        "PASSWORD_IS_IDENTITY",
        code(() -> p.check("margaret.hale forever", "margaret.hale@mill.example")));
    assertDoesNotThrow(() -> p.check("a phrase with no login in it", "margaret.hale@mill.example"));
  }

  @Test
  void aBreachedPasswordIsRefusedThroughFiveCharactersOfItsHash() {
    PasswordPolicy p = PasswordPolicy.of(15, 128, true, base);
    assertEquals("PASSWORD_BREACHED", code(() -> p.check(BREACHED, "a@b.io")));
    assertDoesNotThrow(() -> p.check("a phrase nobody has leaked yet 7", "a@b.io"));
    assertEquals(Optional.of(true), p.breached(BREACHED));
    int before = calls;
    p.breached(BREACHED);
    assertEquals(before, calls, "the range is cached, not asked for twice");
  }

  @Test
  void aScreenThatCannotBeReachedLetsThePasswordThrough() {
    server.stop(0);
    PasswordPolicy p = PasswordPolicy.of(15, 128, true, base);
    assertEquals(Optional.empty(), p.breached(BREACHED));
    assertDoesNotThrow(() -> p.check(BREACHED, "a@b.io"));
  }

  @Test
  void theScreenCanBeSwitchedOff() {
    PasswordPolicy p = PasswordPolicy.of(15, 128, false, base);
    assertDoesNotThrow(() -> p.check(BREACHED, "a@b.io"));
    assertEquals(0, calls);
  }

  @Test
  void theRangeCacheIsBoundedAndStillAnswersRight() {
    PasswordPolicy p = PasswordPolicy.of(15, 128, true, base);
    p.cacheMaxRanges = 3;
    for (int i = 0; i < 40; i++) {
      assertEquals(Optional.of(false), p.breached("a phrase nobody leaked number " + i));
      assertEquals(true, p.cachedRanges() <= 3, "never more than the configured ranges");
    }
    assertEquals(3, p.cachedRanges());
    assertEquals(Optional.of(true), p.breached(BREACHED), "an evicted range is fetched again");
  }
}
