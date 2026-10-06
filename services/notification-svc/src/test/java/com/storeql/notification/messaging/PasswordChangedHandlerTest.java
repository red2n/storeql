package com.storeql.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.notification.channel.RecordingAccountEmailSender;
import com.storeql.notification.service.OnceRepo;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The "your password was changed" notice: the platform's own words, to the login's own address by
 * SMTP alone, carrying no link, once per event, kept against the login and belonging to no
 * business.
 */
class PasswordChangedHandlerTest {

  private static final String EMAIL = "changed@example.com";

  private RecordingAccountEmailSender sender;
  private OnceRepo repo;
  private PasswordChangedHandler handler;

  @BeforeEach
  void setUp() {
    sender = new RecordingAccountEmailSender();
    repo = new OnceRepo();
    handler = new PasswordChangedHandler();
    handler.sender = sender;
    handler.repo = repo;
  }

  private static String event(
      UUID eventId, UUID userId, String email, String language, String kind, String business) {
    return "{\"eventType\":\"PasswordChanged\",\"eventId\":\""
        + eventId
        + "\",\"email\":"
        + (email == null ? "null" : "\"" + email + "\"")
        + ",\"language\":"
        + (language == null ? "null" : "\"" + language + "\"")
        + ",\"userId\":\""
        + userId
        + "\",\"kind\":\""
        + kind
        + "\""
        + (business == null
            ? ("STAFF".equals(kind) ? ",\"businessName\":null" : "")
            : ",\"businessName\":\"" + business + "\"")
        + ",\"changedAt\":\"2026-09-30T10:15:30Z\",\"via\":\"CHANGE\"}";
  }

  @Test
  void aShopperIsToldWhenAndWhatToDoAndThereIsNoLink() {
    UUID user = Ids.newId();
    handler.handle(event(Ids.newId(), user, EMAIL, null, "SHOPPER", null));

    assertEquals(1, sender.sends());
    assertEquals(EMAIL, sender.recipient());
    assertEquals("Your password was changed", sender.subject());
    String body = sender.body();
    assertTrue(body.contains("September 30, 2026, 10:15 UTC"), body);
    assertTrue(body.contains("Login: shopper account"), body);
    assertTrue(body.contains("If it was not you, choose"), body);
    assertTrue(body.contains("Forgot password?"), body);
    assertFalse(body.contains("http"), body);
    assertFalse(body.contains("://"), body);
  }

  @Test
  void aStaffLoginIsNamedByItsBusinessOrABusinessWhenTheNameIsUnknown() {
    handler.handle(event(Ids.newId(), Ids.newId(), EMAIL, "en", "STAFF", "Corner Stores Ltd"));
    assertTrue(sender.body().contains("Login: staff — Corner Stores Ltd"), sender.body());

    handler.handle(event(Ids.newId(), Ids.newId(), EMAIL, "en", "STAFF", null));
    assertTrue(sender.body().contains("Login: staff — a business"), sender.body());
  }

  @Test
  void theWordsFollowTheLanguageAndFallBackToEnglish() {
    handler.handle(event(Ids.newId(), Ids.newId(), EMAIL, "pl", "SHOPPER", null));
    assertEquals("Twoje hasło zostało zmienione", sender.subject());
    assertEquals("pl", repo.language);

    handler.handle(event(Ids.newId(), Ids.newId(), EMAIL, "de", "SHOPPER", null));
    assertEquals("Your password was changed", sender.subject());
    assertEquals("en", repo.language);
  }

  @Test
  void anEventIsNotifiedOnceWhateverTheRedelivery() {
    String payload = event(Ids.newId(), Ids.newId(), EMAIL, null, "SHOPPER", null);
    handler.handle(payload);
    handler.handle(payload);
    assertEquals(1, sender.sends());
    assertEquals(1, repo.records);
  }

  @Test
  void theRowBelongsToNoBusinessAndIsKeptAgainstTheLogin() {
    UUID user = Ids.newId();
    handler.handle(event(Ids.newId(), user, EMAIL, null, "STAFF", "Corner Stores Ltd"));

    assertNull(repo.tenantId, "no business's log read may find it");
    assertEquals(user, repo.subjectId);
    assertEquals("SMTP", repo.channel, "the channel that carried it, as every email is logged");
    assertEquals(EMAIL, repo.recipient);
    assertEquals("SENT", repo.lastStatus);
  }

  @Test
  void theRowNamesWhateverChannelTheSenderCarriesItOn() {
    sender.channel = "SOMEWHERE-ELSE";
    handler.handle(event(Ids.newId(), Ids.newId(), EMAIL, null, "SHOPPER", null));
    assertEquals("SOMEWHERE-ELSE", repo.channel);
  }

  @Test
  void aRowThatWasNotSentStillNamesTheChannelItWouldHaveGoneOn() {
    sender.live = false;
    handler.handle(event(Ids.newId(), Ids.newId(), EMAIL, null, "SHOPPER", null));
    assertEquals("NOT_SENT", repo.lastStatus);
    assertEquals("SMTP", repo.channel);

    sender.live = true;
    sender.fail = true;
    handler.handle(event(Ids.newId(), Ids.newId(), EMAIL, null, "SHOPPER", null));
    assertEquals("NOT_SENT", repo.lastStatus);
    assertEquals("SMTP", repo.channel);
  }

  @Test
  void sentByEmailAloneAndRecordedNotSentWhenThereIsNoTransportOrItFails() {
    sender.live = false;
    handler.handle(event(Ids.newId(), Ids.newId(), EMAIL, null, "SHOPPER", null));
    assertEquals(0, sender.sends());
    assertEquals("NOT_SENT", repo.lastStatus);

    sender.live = true;
    sender.fail = true;
    handler.handle(event(Ids.newId(), Ids.newId(), EMAIL, null, "SHOPPER", null));
    assertEquals("NOT_SENT", repo.lastStatus);
    assertEquals(2, repo.records);
  }

  @Test
  void aMalformedOrAddresslessEventIsSkippedNotFailed() {
    handler.handle("{not json");
    handler.handle("{\"eventId\":\"x\"}");
    handler.handle(event(Ids.newId(), Ids.newId(), null, null, "SHOPPER", null));
    handler.handle(event(Ids.newId(), Ids.newId(), "  ", null, "SHOPPER", null));
    assertEquals(0, sender.sends());
    assertEquals(0, repo.records);
  }
}
