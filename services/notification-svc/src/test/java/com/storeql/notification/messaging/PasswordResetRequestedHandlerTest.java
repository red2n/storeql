package com.storeql.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.notification.channel.RecordingAccountEmailSender;
import com.storeql.notification.service.OnceRepo;
import com.storeql.notification.service.RecordingChannel;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The reset email is the platform's own words, in the request's language where the platform has it
 * and English otherwise, sent by SMTP alone — never in-app, MQTT or SMS, and never twice.
 */
class PasswordResetRequestedHandlerTest {

  private static final String EMAIL = "forgetful@example.com";
  private static final String LINK_1 = "https://app.example/#/reset-password/tok-shopper-abc123";
  private static final String LINK_2 = "https://app.example/#/reset-password/tok-staff-def456";

  private RecordingAccountEmailSender sender;
  private OnceRepo repo;
  private PasswordResetRequestedHandler handler;

  // Independent of the handler — never wired to it, and never should be: proof by construction
  // that a password reset can only ever reach the app feed or MQTT if someone wires them in.
  private RecordingChannel appFeed;
  private RecordingChannel mqtt;

  @BeforeEach
  void setUp() {
    sender = new RecordingAccountEmailSender();
    repo = new OnceRepo();
    handler = new PasswordResetRequestedHandler();
    handler.sender = sender;
    handler.repo = repo;
    appFeed = new RecordingChannel();
    mqtt = new RecordingChannel();
  }

  private static String shopper(String link) {
    return shopper(link, null);
  }

  private static String shopper(String link, UUID userId) {
    return "{\"kind\":\"SHOPPER\",\"link\":\"" + link + "\"" + userIdField(userId) + "}";
  }

  private static String staff(String businessName, String link) {
    return staff(businessName, link, null);
  }

  private static String staff(String businessName, String link, UUID userId) {
    return "{\"kind\":\"STAFF\",\"businessName\":\""
        + businessName
        + "\",\"link\":\""
        + link
        + "\""
        + userIdField(userId)
        + "}";
  }

  private static String staffSso(String businessName) {
    return "{\"kind\":\"STAFF_SSO\",\"businessName\":\"" + businessName + "\"}";
  }

  private static String userIdField(UUID userId) {
    return userId == null ? "" : ",\"userId\":\"" + userId + "\"";
  }

  private static String event(UUID eventId, String email, String language, String... entries) {
    return "{\"eventType\":\"PasswordResetRequested\",\"eventId\":\""
        + eventId
        + "\",\"requestId\":\""
        + Ids.newId()
        + "\",\"email\":"
        + (email == null ? "null" : "\"" + email + "\"")
        + ","
        + "\"language\":"
        + (language == null ? "null" : "\"" + language + "\"")
        + ",\"expiresAt\":\"2026-09-26T10:30:00Z\","
        + "\"entries\":["
        + String.join(",", entries)
        + "]}";
  }

  @Test
  void aPolishRequestGetsPolishWords() {
    handler.handle(event(Ids.newId(), EMAIL, "pl", shopper(LINK_1)));

    assertEquals(1, sender.sends());
    assertEquals("Zresetuj swoje hasło", sender.subject());
    assertTrue(sender.body().contains("Otrzymaliśmy prośbę"), sender.body());
    assertEquals("pl", repo.language);
  }

  @Test
  void anUnknownOrMissingLanguageReadsEnglish() {
    handler.handle(event(Ids.newId(), EMAIL, "de", shopper(LINK_1)));
    assertEquals("Reset your password", sender.subject());
    assertEquals("en", repo.language);

    handler.handle(event(Ids.newId(), EMAIL, null, shopper(LINK_1)));
    assertEquals("Reset your password", sender.subject());
    assertEquals("en", repo.language);
  }

  @Test
  void everyEntryKindIsWordedAndTheSsoEntryCarriesNoLink() {
    handler.handle(
        event(
            Ids.newId(),
            EMAIL,
            "en",
            shopper(LINK_1),
            staff("Corner Stores Ltd", LINK_2),
            staffSso("Big Retail plc")));

    String body = sender.body();
    assertTrue(body.contains("Shopper account: " + LINK_1), body);
    assertTrue(body.contains("Staff — Corner Stores Ltd: " + LINK_2), body);
    assertTrue(
        body.contains(
            "Staff — Big Retail plc: sign in through your business's own sign-in; its identity"
                + " provider resets your password."),
        body);
  }

  @Test
  void theSsoEntryAloneCarriesNoLinkAtAll() {
    handler.handle(event(Ids.newId(), EMAIL, "en", staffSso("Big Retail plc")));
    assertFalse(sender.body().contains("http"), sender.body());
  }

  @Test
  void twoBusinessesNamesEachAppearOnlyOnTheirOwnEntry() {
    handler.handle(
        event(
            Ids.newId(),
            EMAIL,
            "en",
            staff("Corner Stores Ltd", LINK_1),
            staff("Big Retail plc", LINK_2)));

    String body = sender.body();
    assertTrue(body.contains("Staff — Corner Stores Ltd: " + LINK_1), body);
    assertTrue(body.contains("Staff — Big Retail plc: " + LINK_2), body);
    assertFalse(body.contains("Corner Stores Ltd: " + LINK_2), body);
    assertFalse(body.contains("Big Retail plc: " + LINK_1), body);
  }

  @Test
  void theStoredRowHoldsNoTokenAndBelongsToNoBusiness() {
    handler.handle(
        event(Ids.newId(), EMAIL, "en", shopper(LINK_1), staff("Corner Stores Ltd", LINK_2)));

    assertNull(repo.tenantId, "the reset belongs to no business");
    assertEquals(EMAIL, repo.recipient);
    assertEquals("EMAIL", repo.channel);
    assertEquals("SENT", repo.lastStatus);
    assertFalse(repo.body.contains("http"), repo.body);
    assertFalse(repo.body.contains("tok-shopper-abc123"), repo.body);
    assertFalse(repo.body.contains("tok-staff-def456"), repo.body);
    assertTrue(repo.body.contains("[link removed]"), repo.body);
    // The sent copy still carries the real links — only the stored copy is redacted.
    assertTrue(sender.body().contains(LINK_1), sender.body());
    // Neither entry named an id: still sent, subject just null.
    assertNull(repo.subjectId);
  }

  /**
   * The base address comes from an operator's setting, and "app.example.com" with no scheme is an
   * easy one to write: the stored copy must still hold no token, because each entry's link is
   * removed as the text it is, not by what a web address usually looks like.
   */
  @Test
  void aLinkWithNoSchemeIsStillNeverStored() {
    String bare = "app.example.com/#/reset-password/tok-bare-777xyz";
    String odd = "intranet:8088/#/reset-password/tok-odd-888qrs";
    handler.handle(
        event(Ids.newId(), EMAIL, "pl", shopper(bare), staff("Sklepy Rogowe sp. z o.o.", odd)));

    assertFalse(repo.body.contains("tok-bare-777xyz"), repo.body);
    assertFalse(repo.body.contains("tok-odd-888qrs"), repo.body);
    assertFalse(repo.body.contains("reset-password"), repo.body);
    assertTrue(repo.body.contains("[link removed]"), repo.body);
    assertTrue(sender.body().contains(bare), "the email itself still carries the link");
  }

  @Test
  void theSubjectIsTheShopperLoginWhenThereIsOne() {
    UUID shopperLogin = Ids.newId();
    UUID staffLogin = Ids.newId();
    handler.handle(
        event(
            Ids.newId(),
            EMAIL,
            "en",
            staff("Corner Stores Ltd", LINK_2, staffLogin),
            shopper(LINK_1, shopperLogin)));

    assertEquals(shopperLogin, repo.subjectId);
  }

  @Test
  void theSubjectIsTheFirstEntrysLoginWhenThereIsNoShopper() {
    UUID first = Ids.newId();
    UUID second = Ids.newId();
    handler.handle(
        event(
            Ids.newId(),
            EMAIL,
            "en",
            staff("Corner Stores Ltd", LINK_1, first),
            staff("Big Retail plc", LINK_2, second)));

    assertEquals(first, repo.subjectId);
  }

  @Test
  void aMissingOrInvalidLoginIdLeavesTheSubjectNullButStillSends() {
    handler.handle(
        event(
            Ids.newId(),
            EMAIL,
            "en",
            "{\"kind\":\"SHOPPER\",\"link\":\"" + LINK_1 + "\",\"userId\":\"not-a-uuid\"}"));

    assertEquals(1, sender.sends());
    assertEquals("SENT", repo.lastStatus);
    assertNull(repo.subjectId);
  }

  @Test
  void sentOnlyThroughTheEmailTransportNeverInAppOrMqtt() {
    handler.handle(event(Ids.newId(), EMAIL, "en", shopper(LINK_1)));

    assertEquals(1, sender.sends());
    assertEquals(0, appFeed.sends());
    assertEquals(0, mqtt.sends());
  }

  @Test
  void withNoEmailTransportNothingIsSentAndTheRowIsNotSent() {
    sender.live = false;
    handler.handle(event(Ids.newId(), EMAIL, "en", shopper(LINK_1)));

    assertEquals(0, sender.sends());
    assertEquals(1, repo.records);
    assertEquals("NOT_SENT", repo.lastStatus);
  }

  @Test
  void anSmtpFailureIsRecordedNotSentWithNoRetry() {
    sender.fail = true;
    String payload = event(Ids.newId(), EMAIL, "en", shopper(LINK_1));
    handler.handle(payload);

    assertEquals(0, sender.sends());
    assertEquals(1, repo.records);
    assertEquals("NOT_SENT", repo.lastStatus);

    // A redelivery of the very same event does not try again — even were the transport fixed by
    // then, this build never re-sends on replay: the row already exists for this event and type.
    sender.fail = false;
    handler.handle(payload);
    assertEquals(0, sender.sends());
    assertEquals(1, repo.records);
  }

  @Test
  void aReplayedEventSendsOnce() {
    String payload = event(Ids.newId(), EMAIL, "en", shopper(LINK_1));
    handler.handle(payload);
    handler.handle(payload);
    handler.handle(payload);

    assertEquals(1, sender.sends());
    assertEquals(1, repo.records);
  }

  @Test
  void malformedOrEmptyPayloadsAreSkipped() {
    handler.handle("{not json");
    handler.handle("{\"eventId\":\"not-a-uuid\"}");
    handler.handle(event(Ids.newId(), null, "en", shopper(LINK_1))); // no email
    handler.handle(event(Ids.newId(), EMAIL, "en")); // no entries

    assertEquals(0, sender.sends());
    assertEquals(0, repo.records);
  }
}
