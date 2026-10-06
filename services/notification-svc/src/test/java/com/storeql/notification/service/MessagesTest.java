package com.storeql.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.notification.template.Catalogue;
import com.storeql.notification.template.TemplateStore;
import com.storeql.notification.template.Values;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which words a message goes out in: the business's in the reader's language, then the business's
 * in its own language, then the platform's — and what the money looks like in each.
 */
class MessagesTest {

  private static final UUID TENANT = Ids.newId();

  private MessagesTestSupport.MemoryStore store;
  private Messages messages;

  @BeforeEach
  void setUp() {
    store = new MessagesTestSupport.MemoryStore();
    messages = MessagesTestSupport.with(store, "GB", "Hollins Grocers");
  }

  private Messages.Composed order(String language) {
    return messages.compose(
        TENANT,
        new Messages.Message(
            "ORDER_CONFIRMED",
            Catalogue.Form.EMAIL,
            language,
            Values.of().text("order", "A-1").money("total", new BigDecimal("12.5"), "GBP")));
  }

  @Test
  @DisplayName("Nobody has written anything: the platform's words, signed by the shop")
  void thePlatformsWords() {
    Messages.Composed m = order("pl");
    assertEquals("Your order is confirmed", m.subject());
    assertEquals(
        "Thanks for your order!\n\nOrder A-1\nTotal: £12.50\n\n— Hollins Grocers", m.body());
    assertEquals("en", m.language());
    assertEquals("default", m.template());
  }

  @Test
  @DisplayName("The reader's language when the business wrote it; its own language when not")
  void theReadersLanguageThenTheBusinesss() {
    store.put(
        "ORDER_CONFIRMED",
        Catalogue.Form.EMAIL,
        "pl",
        "Zamówienie {{order}} potwierdzone",
        "Dziękujemy! Razem: {{total}}. — {{shop}}");
    store.put(
        "ORDER_CONFIRMED",
        Catalogue.Form.EMAIL,
        "cy",
        "Archeb {{order}} wedi'i gadarnhau",
        "Diolch! Cyfanswm: {{total}}");
    store.settings = new TemplateStore.Settings("cy", "Siop Hollins");

    Messages.Composed polish = order("pl");
    assertEquals("Zamówienie A-1 potwierdzone", polish.subject());
    assertEquals(
        "Dziękujemy! Razem: 12,50 GBP. — Siop Hollins", polish.body().replace('\u00a0', ' '));
    assertEquals("pl", polish.language());
    assertEquals("v1", polish.template());

    Messages.Composed unknown = order("de");
    assertEquals("Archeb A-1 wedi'i gadarnhau", unknown.subject(), "German unwritten: the house's");
    assertEquals("cy", unknown.language());

    Messages.Composed nobodySaid = order(null);
    assertEquals("cy", nobodySaid.language());
  }

  @Test
  @DisplayName("The words the editor shows for a language are the ones a message in it goes out in")
  void theEditorAndTheSendAreOfOneMind() {
    store.put(
        "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "pl", "Zamówienie {{order}}", "Razem: {{total}}");
    store.settings = new TemplateStore.Settings("pl", null);

    // German was never written, or was retired: the house language's words go out, and are shown.
    TemplateStore.Stored shown =
        messages.ownWords(TENANT, "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "de").orElseThrow();
    Messages.Composed sent = order("de");
    assertEquals("pl", shown.language());
    assertEquals(shown.language(), sent.language());
    assertEquals("v" + shown.version(), sent.template());

    // Written in German, its own version is the one in both.
    store.put(
        "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "de", "Bestellung {{order}}", "Gesamt: {{total}}");
    assertEquals(
        "de",
        messages
            .ownWords(TENANT, "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "de")
            .orElseThrow()
            .language());
    assertEquals("de", order("de").language());

    // Nothing live in German or in the house language: the platform's words, in both.
    store.live.clear();
    assertTrue(messages.ownWords(TENANT, "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "de").isEmpty());
    assertEquals("default", order("de").template());
  }

  @Test
  @DisplayName("Words that no longer parse are no one's to show or send: the next candidate is")
  void wordsThatNoLongerParseAreSkippedByTheEditorAndTheSendAlike() {
    // Saved while they parsed; the engine's grammar has moved on since (written straight to the
    // store, as a save would have refused them).
    store.put(
        "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "pl", "Zamówienie {{order}}", "Razem: {{total");
    store.put(
        "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "cy", "Archeb {{order}}", "Cyfanswm: {{total}}");
    store.settings = new TemplateStore.Settings("cy", null);

    // The Polish words cannot be written, so a Polish reader gets the business's own Welsh ones —
    // and the editor shows those, not the broken Polish.
    TemplateStore.Stored shown =
        messages.ownWords(TENANT, "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "pl").orElseThrow();
    Messages.Composed sent = order("pl");
    assertEquals("cy", shown.language());
    assertEquals(shown.language(), sent.language());
    assertEquals("v" + shown.version(), sent.template());
    assertEquals("Archeb A-1", sent.subject());

    // A subject that does not parse fails an email, which has one.
    store.put(
        "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "cy", "Archeb {{order", "Cyfanswm: {{total}}");
    assertTrue(messages.ownWords(TENANT, "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "pl").isEmpty());
    assertTrue(messages.ownWords(TENANT, "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "cy").isEmpty());
    for (String language : new String[] {"pl", "cy", "de", null}) {
      Messages.Composed platform = order(language);
      assertEquals("default", platform.template(), "language " + language);
      assertEquals("en", platform.language(), "language " + language);
      assertEquals("Your order is confirmed", platform.subject(), "language " + language);
    }

    // A text has no subject of its own, so a stored one that does not parse is not checked.
    store.put(
        "RECALL_NOTICE",
        Catalogue.Form.SMS,
        "cy",
        "ignored {{",
        "RECALL {{reference}}: {{product}}. {{remedies}}. {{contact}}");
    assertTrue(
        messages.ownWords(TENANT, "RECALL_NOTICE", Catalogue.Form.SMS, "cy").isPresent(),
        "its body parses, and its subject is not used");
    Messages.Composed text =
        messages.compose(
            TENANT,
            new Messages.Message(
                "RECALL_NOTICE",
                Catalogue.Form.SMS,
                "cy",
                Values.of()
                    .text("reference", "R-9")
                    .text("product", "Oat bars")
                    .text("remedies", "a refund")
                    .text("contact", "0800 1")));
    assertEquals("RECALL R-9: Oat bars. a refund. 0800 1", text.body());
    assertEquals("cy", text.language());
    store.put("RECALL_NOTICE", Catalogue.Form.SMS, "cy", "ignored", "RECALL {{reference");
    assertTrue(messages.ownWords(TENANT, "RECALL_NOTICE", Catalogue.Form.SMS, "cy").isEmpty());
  }

  @Test
  @DisplayName("A live version whose words do not parse is told apart from a language with none")
  void anUnusableVersionIsToldApartFromNoVersion() {
    store.put(
        "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "pl", "Zamówienie {{order}}", "Razem: {{total");
    store.put(
        "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "cy", "Archeb {{order}}", "Cyfanswm: {{total}}");
    store.settings = new TemplateStore.Settings("cy", null);

    assertTrue(
        messages.ownWordsUnusable(TENANT, "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "pl"),
        "Polish is written, and cannot be written out");
    assertEquals(
        "cy",
        messages
            .ownWords(TENANT, "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "pl")
            .orElseThrow()
            .language(),
        "and the words that go out in its place are the house language's");
    assertFalse(
        messages.ownWordsUnusable(TENANT, "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "cy"),
        "Welsh parses");
    assertFalse(
        messages.ownWordsUnusable(TENANT, "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "de"),
        "German was never written, or is retired: there is no version to be unusable");
    assertFalse(
        messages.ownWordsUnusable(TENANT, "ORDER_CONFIRMED", Catalogue.Form.PUSH, "pl"),
        "another form of the same message is its own");
    assertFalse(
        messages.ownWordsUnusable(Ids.newId(), "RECALL_NOTICE", Catalogue.Form.SMS, "pl"),
        "nothing written for another message");

    // A text has no subject of its own, so a stored one that does not parse is not checked.
    store.put(
        "RECALL_NOTICE",
        Catalogue.Form.SMS,
        "cy",
        "ignored {{",
        "RECALL {{reference}}: {{product}}. {{remedies}}. {{contact}}");
    assertFalse(messages.ownWordsUnusable(TENANT, "RECALL_NOTICE", Catalogue.Form.SMS, "cy"));
    store.put("RECALL_NOTICE", Catalogue.Form.SMS, "cy", "ignored", "RECALL {{reference");
    assertTrue(messages.ownWordsUnusable(TENANT, "RECALL_NOTICE", Catalogue.Form.SMS, "cy"));

    // A subject that does not parse fails an email, which has one.
    store.put(
        "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "cy", "Archeb {{order", "Cyfanswm: {{total}}");
    assertTrue(messages.ownWordsUnusable(TENANT, "ORDER_CONFIRMED", Catalogue.Form.EMAIL, "cy"));
  }

  @Test
  @DisplayName("A language that is not a language is the business's own")
  void rubbishIsTheBusinesssLanguage() {
    assertEquals("en", order("../../etc").language());
    assertEquals("en", order("POLISH").language());
  }

  @Test
  @DisplayName("A text message has no subject of its own: the log names it with the platform's")
  void aTextMessageKeepsItsLogSubject() {
    store.put(
        "RECALL_NOTICE",
        Catalogue.Form.SMS,
        "en",
        "ignored",
        "RECALL {{reference}}: {{product}}. {{remedies}}. {{contact}}");
    Messages.Composed sms =
        messages.compose(
            TENANT,
            new Messages.Message(
                "RECALL_NOTICE",
                Catalogue.Form.SMS,
                null,
                Values.of()
                    .text("reference", "R-9")
                    .text("product", "Oat bars")
                    .text("remedies", "a refund")
                    .text("contact", "0800 1")));
    assertEquals("Product safety recall — R-9", sms.subject());
    assertEquals("RECALL R-9: Oat bars. a refund. 0800 1", sms.body());
  }
}
