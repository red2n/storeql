package com.storeql.iam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.service.OutboxRow;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The shape producer and consumer share for {@code PasswordChanged}, and who it is sent for. */
class PasswordChangedEventTest {

  private static final Instant AT = Instant.parse("2026-09-30T10:15:30Z");

  private static JsonObject payload(OutboxRow row) {
    return Json.createReader(new StringReader(row.payload())).readObject();
  }

  @Test
  void onlyALoginWithAnAddressAndNotThePlatformAdministratorIsAnnounced() {
    assertTrue(PasswordChangedEvent.announces("a@example.com", false));
    assertFalse(PasswordChangedEvent.announces("a@example.com", true));
    assertFalse(PasswordChangedEvent.announces(null, false));
    assertFalse(PasswordChangedEvent.announces("  ", false));
  }

  @Test
  void aShopperEventCarriesNoBusinessAndBelongsToNone() {
    UUID user = Ids.newId();
    OutboxRow row =
        PasswordChangedEvent.row(
            user, "a@example.com", false, null, PasswordChangedEvent.VIA_CHANGE, AT);
    assertEquals("PasswordChanged", row.eventType());
    assertEquals("storeql.iam.password-changed", row.topic());
    assertNull(row.tenantId());
    JsonObject o = payload(row);
    assertEquals("SHOPPER", o.getString("kind"));
    assertFalse(o.containsKey("businessName"));
    assertEquals(user.toString(), o.getString("userId"));
    assertEquals("a@example.com", o.getString("email"));
    assertEquals("CHANGE", o.getString("via"));
    assertEquals("2026-09-30T10:15:30Z", o.getString("changedAt"));
    assertTrue(o.isNull("language"));
    assertTrue(Ids.isV7(Ids.parse(o.getString("eventId"))));
  }

  @Test
  void aStaffEventNamesItsBusinessOrSaysNullNeverOmitsIt() {
    JsonObject named =
        payload(
            PasswordChangedEvent.row(
                Ids.newId(), "s@example.com", true, "Corner Stores Ltd", "RESET", AT));
    assertEquals("STAFF", named.getString("kind"));
    assertEquals("Corner Stores Ltd", named.getString("businessName"));
    assertEquals("RESET", named.getString("via"));

    JsonObject unnamed =
        payload(PasswordChangedEvent.row(Ids.newId(), "s@example.com", true, null, "RESET", AT));
    assertTrue(unnamed.containsKey("businessName"));
    assertTrue(unnamed.isNull("businessName"));
  }

  @Test
  void everyEventHasItsOwnIdAndNoSecretFields() {
    UUID user = Ids.newId();
    OutboxRow a = PasswordChangedEvent.row(user, "a@example.com", false, null, "CHANGE", AT);
    OutboxRow b = PasswordChangedEvent.row(user, "a@example.com", false, null, "CHANGE", AT);
    assertFalse(payload(a).getString("eventId").equals(payload(b).getString("eventId")));
    String text = a.payload().toLowerCase();
    assertFalse(text.contains("password\""), text);
    assertFalse(text.contains("hash"), text);
    assertFalse(text.contains("token"), text);
    assertFalse(text.contains("link"), text);
  }

  @Test
  void theLanguageIsCarriedForAChangeAndForAResetAndAbsentStaysNull() {
    UUID u = Ids.newId();
    assertEquals(
        "pl",
        payload(PasswordChangedEvent.row(u, "a@example.com", false, null, "CHANGE", "pl", AT))
            .getString("language"));
    assertEquals(
        "ro",
        payload(PasswordChangedEvent.row(u, "a@example.com", false, null, "RESET", "ro", AT))
            .getString("language"));
    assertTrue(
        payload(PasswordChangedEvent.row(u, "a@example.com", false, null, "CHANGE", null, AT))
            .isNull("language"));
  }
}
