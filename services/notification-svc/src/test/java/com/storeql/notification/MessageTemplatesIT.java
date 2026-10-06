package com.storeql.notification;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.storeql.ids.Ids;
import com.storeql.notification.service.Messages;
import com.storeql.notification.service.Notifier;
import com.storeql.notification.template.Catalogue;
import com.storeql.notification.template.Values;
import com.storeql.test.PostgresSupport;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Message templates over HTTP and Postgres (13.x): what there is to write, a business's words saved
 * and versioned, the platform's words back when retired, a preview, the business's settings — and
 * what is refused: a value a message does not have, a recall notice without a part the law needs, a
 * template that does not parse or does not fit, a language that is not one, a cashier, two saves at
 * once both winning. And the words reaching a message: saved in Polish, sent in Polish, recorded
 * so.
 *
 * <p>Requests carry the identity headers the gateway would have stamped from a verified token.
 */
@HelidonTest
class MessageTemplatesIT {

  private static final PostgresSupport PG;

  static {
    PG = PostgresSupport.start();
    System.setProperty("storeql.db.url", PG.jdbcUrl());
    System.setProperty("storeql.db.migration-url", PG.jdbcUrl());
    System.setProperty("storeql.db.user", PG.username());
    System.setProperty("storeql.db.password", PG.password());
    System.setProperty("storeql.db.schema", "notification");
    System.setProperty("storeql.consul.enabled", "false");
    System.setProperty("storeql.kafka.enabled", "false");
  }

  @Inject WebTarget target;
  @Inject Messages messages;
  @Inject Notifier notifier;

  @AfterAll
  static void stop() {
    PG.stop();
  }

  private record Answer(int status, JsonObject body) {
    JsonObject data() {
      return body.getJsonObject("data");
    }

    String code() {
      return body.containsKey("code") ? body.getString("code") : null;
    }

    List<String> details() {
      return body.containsKey("details")
          ? body.getJsonArray("details").getValuesAs(JsonString.class).stream()
              .map(JsonString::getString)
              .toList()
          : List.of();
    }
  }

  private Answer call(String method, String path, UUID tenant, String roles, String json) {
    Invocation.Builder b =
        target
            .path("/admin/notifications" + path)
            .request()
            .header("X-Tenant-Id", tenant)
            .header("X-User-Id", Ids.newId())
            .header("X-Roles", roles);
    Response r =
        switch (method) {
          case "GET" -> b.get();
          case "DELETE" -> b.delete();
          case "PUT" -> b.put(Entity.entity(json, MediaType.APPLICATION_JSON));
          default -> b.post(Entity.entity(json, MediaType.APPLICATION_JSON));
        };
    String text = r.readEntity(String.class);
    return new Answer(
        r.getStatus(),
        text == null || text.isBlank()
            ? JsonObject.EMPTY_JSON_OBJECT
            : Json.createReader(new StringReader(text)).readObject());
  }

  private static String words(String subject, String body) {
    return Json.createObjectBuilder().add("subject", subject).add("body", body).build().toString();
  }

  private static final String ORDER_PL = "/templates/ORDER_CONFIRMED/EMAIL/pl";

  // ── what there is to write ─────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Every message, its forms, its values and its required parts; the defaults first")
  void theCatalogueAndTheDefaults() {
    UUID tenant = Ids.newId();
    Answer all = call("GET", "/templates", tenant, "MANAGER", null);
    assertThat(all.body().toString(), all.status(), is(200));
    var types = all.body().getJsonArray("data");
    assertThat(types.size(), is(Catalogue.all().size()));
    JsonObject recall =
        types.getValuesAs(JsonObject.class).stream()
            .filter(t -> t.getString("type").equals("RECALL_NOTICE"))
            .findFirst()
            .orElseThrow();
    assertThat(recall.getJsonArray("forms").size(), is(3));
    assertThat(
        recall.getJsonArray("forms").getJsonObject(0).getJsonArray("required").size(), is(6));

    Answer order = call("GET", ORDER_PL, tenant, "OWNER", null);
    assertThat(order.data().getString("source"), is("DEFAULT"));
    assertThat(order.data().getString("body"), containsString("{{total}}"));
    assertThat(order.data().getJsonArray("history").size(), is(0));
  }

  // ── saving, versions, retiring ─────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Saved, saved again as the next version, retired back to the platform's words")
  void versions() {
    UUID tenant = Ids.newId();
    Answer v1 =
        call(
            "PUT",
            ORDER_PL,
            tenant,
            "OWNER",
            words("Zamówienie {{order}}", "Dziękujemy! Razem: {{total}}"));
    assertThat(v1.body().toString(), v1.status(), is(200));
    assertThat(v1.data().getString("source"), is("BUSINESS"));
    assertThat(v1.data().getInt("version"), is(1));

    Answer v2 =
        call(
            "PUT",
            ORDER_PL,
            tenant,
            "MANAGER",
            words("Zamówienie {{order}} potwierdzone", "Dziękujemy! Razem: {{total}}. — {{shop}}"));
    assertThat(v2.data().getInt("version"), is(2));
    var history = v2.data().getJsonArray("history").getValuesAs(JsonObject.class);
    assertThat(history.size(), is(2));
    assertThat(history.get(1).containsKey("retiredAt"), is(true));
    assertThat(
        "the live one has no retirement", history.get(0).containsKey("retiredAt"), is(false));

    var catalogue = call("GET", "/templates", tenant, "OWNER", null).body().getJsonArray("data");
    assertThat(catalogue.toString(), containsString("\"language\":\"pl\",\"version\":2"));

    assertThat(call("DELETE", ORDER_PL, tenant, "OWNER", null).status(), is(200));
    assertThat(
        call("GET", ORDER_PL, tenant, "OWNER", null).data().getString("source"), is("DEFAULT"));
    Answer again = call("DELETE", ORDER_PL, tenant, "OWNER", null);
    assertThat(again.status(), is(404));
    assertThat(again.code(), is("TEMPLATE_NOT_WRITTEN"));
    Answer v3 = call("PUT", ORDER_PL, tenant, "OWNER", words("Z {{order}}", "Razem {{total}}"));
    assertThat("history continues after a retirement", v3.data().getInt("version"), is(3));
  }

  @Test
  @DisplayName(
      "A language with no live version shows the words a message in it would go out in: the"
          + " house language's, else the platform's")
  void theEditorShowsWhatWouldBeSentForARetiredLanguage() {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId();
    String orderDe = "/templates/ORDER_CONFIRMED/EMAIL/de";
    String orderFr = "/templates/ORDER_CONFIRMED/EMAIL/fr";
    String pushDe = "/templates/ORDER_CONFIRMED/PUSH/de";
    call("PUT", "/template-settings", tenant, "OWNER", "{\"defaultLanguage\":\"pl\"}");
    call("PUT", ORDER_PL, tenant, "OWNER", words("Zamówienie {{order}}", "Razem: {{total}}"));
    call("PUT", orderDe, tenant, "OWNER", words("Bestellung {{order}}", "Gesamt: {{total}}"));
    Values values = Values.of().text("order", "A-77").money("total", new BigDecimal("12.5"), "EUR");
    Messages.Message german =
        new Messages.Message("ORDER_CONFIRMED", Catalogue.Form.EMAIL, "de", values);

    Answer own = call("GET", orderDe, tenant, "OWNER", null);
    assertThat(
        "while it is live the editor shows its own",
        own.data().getString("source"),
        is("BUSINESS"));
    assertThat(own.data().getString("subject"), is("Bestellung {{order}}"));
    assertThat(own.data().getString("wordsLanguage"), is("de"));
    assertThat("its words parse", own.data().getBoolean("storedWordsUnusable"), is(false));
    assertThat(messages.compose(tenant, german).language(), is("de"));

    assertThat(call("DELETE", orderDe, tenant, "OWNER", null).status(), is(200));

    // Retired: a German reader's message now goes out in the Polish house words, and the editor
    // says so — those words, that version, that language — and keeps German's own history.
    Answer retired = call("GET", orderDe, tenant, "OWNER", null);
    assertThat(
        retired.body().toString(), retired.data().getString("source"), is("DEFAULT_LANGUAGE"));
    assertThat(retired.data().getString("language"), is("de"));
    assertThat(retired.data().getString("wordsLanguage"), is("pl"));
    assertThat(retired.data().getInt("version"), is(1));
    assertThat(retired.data().getString("subject"), is("Zamówienie {{order}}"));
    assertThat(retired.data().getString("body"), is("Razem: {{total}}"));
    assertThat(retired.data().getJsonArray("history").size(), is(1));
    assertThat(
        "retired is not unusable: German has no live version",
        retired.data().getBoolean("storedWordsUnusable"),
        is(false));
    Messages.Composed sent = messages.compose(tenant, german);
    assertThat("what is sent is what is shown", sent.language(), is("pl"));
    assertThat(sent.template(), is("v1"));
    assertThat(sent.subject(), is("Zamówienie A-77"));

    // A language the business never wrote goes the same way; the house language itself is its own.
    Answer neverWritten = call("GET", orderFr, tenant, "MANAGER", null);
    assertThat(neverWritten.data().getString("source"), is("DEFAULT_LANGUAGE"));
    assertThat(neverWritten.data().getBoolean("storedWordsUnusable"), is(false));
    assertThat(neverWritten.data().getString("body"), is("Razem: {{total}}"));
    Answer house = call("GET", ORDER_PL, tenant, "MANAGER", null);
    assertThat(house.data().getString("source"), is("BUSINESS"));
    assertThat(house.data().getString("wordsLanguage"), is("pl"));
    // Another form is its own: nothing was written in Polish for the push, so the platform's words.
    Answer push = call("GET", pushDe, tenant, "OWNER", null);
    assertThat(push.data().getString("source"), is("DEFAULT"));
    assertThat(push.data().getString("wordsLanguage"), is("en"));

    // Nothing here is another business's: its owner and manager read the platform's words, and
    // staff below management read nothing, of a retired language or any other.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer theirs = call("GET", orderDe, other, role, null);
      assertThat(role, theirs.data().getString("source"), is("DEFAULT"));
      assertThat(role, theirs.body().toString(), not(containsString("Razem")));
      assertThat(role, theirs.body().toString(), not(containsString("Zamówienie")));
    }
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, call("GET", orderDe, tenant, role, null).status(), is(403));
      assertThat(role, call("GET", orderDe, other, role, null).status(), is(403));
    }
    assertThat(
        "there is nothing of its own to retire in German",
        call("DELETE", orderDe, tenant, "OWNER", null).code(),
        is("TEMPLATE_NOT_WRITTEN"));

    // Retire the house words too: both go back to the platform's, and the editor agrees.
    assertThat(call("DELETE", ORDER_PL, tenant, "OWNER", null).status(), is(200));
    Answer platform = call("GET", orderDe, tenant, "OWNER", null);
    assertThat(platform.data().getString("source"), is("DEFAULT"));
    assertThat(platform.data().getString("wordsLanguage"), is("en"));
    assertThat(platform.data().getString("body"), containsString("{{total}}"));
    Messages.Composed platformSent = messages.compose(tenant, german);
    assertThat(platformSent.template(), is("default"));
    assertThat(platformSent.language(), is("en"));
    assertThat(
        call("GET", ORDER_PL, tenant, "OWNER", null).data().getString("source"), is("DEFAULT"));
  }

  /** Leaves the live version's body one that does not parse: written behind a save's back. */
  private static void breakStoredBody(UUID tenant, String language) throws Exception {
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password())) {
      c.setSchema("notification");
      try (PreparedStatement ps =
          c.prepareStatement(
              "UPDATE message_templates SET body = body || ' {{never closed'"
                  + " WHERE tenant_id = ? AND language = ? AND retired_at IS NULL")) {
        ps.setObject(1, tenant);
        ps.setString(2, language);
        assertThat(language, ps.executeUpdate(), is(1));
      }
    }
  }

  @Test
  @DisplayName(
      "A stored template that no longer parses is passed over by the editor and the send alike,"
          + " which go on to the next words; the editor says so and still lists the version")
  void theEditorAndTheSendAgreeWhenAStoredTemplateNoLongerParses() throws Exception {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId();
    String orderDe = "/templates/ORDER_CONFIRMED/EMAIL/de";
    call("PUT", "/template-settings", tenant, "OWNER", "{\"defaultLanguage\":\"pl\"}");
    call("PUT", ORDER_PL, tenant, "OWNER", words("Zamówienie {{order}}", "Razem: {{total}}"));
    call("PUT", orderDe, tenant, "OWNER", words("Bestellung {{order}}", "Gesamt: {{total}}"));
    Values values = Values.of().text("order", "A-77").money("total", new BigDecimal("12.5"), "EUR");
    Messages.Message german =
        new Messages.Message("ORDER_CONFIRMED", Catalogue.Form.EMAIL, "de", values);
    assertThat(
        call("GET", orderDe, tenant, "OWNER", null).data().getString("source"), is("BUSINESS"));

    breakStoredBody(tenant, "de");

    // German's own words cannot be written: the Polish house words are shown, and are sent.
    Answer shown = call("GET", orderDe, tenant, "OWNER", null);
    assertThat(shown.body().toString(), shown.data().getString("source"), is("DEFAULT_LANGUAGE"));
    assertThat(shown.data().getString("wordsLanguage"), is("pl"));
    assertThat(shown.data().getString("subject"), is("Zamówienie {{order}}"));
    assertThat(
        "German is not told it has none: its own version is there, and cannot be written",
        shown.data().getBoolean("storedWordsUnusable"),
        is(true));
    assertThat(
        "its own history is still there to read",
        shown.data().getJsonArray("history").size(),
        is(1));
    assertThat(
        "and the broken version is still listed as the live one",
        shown.data().getJsonArray("history").getJsonObject(0).containsKey("retiredAt"),
        is(false));
    Messages.Composed sent = messages.compose(tenant, german);
    assertThat(sent.language(), is("pl"));
    assertThat(sent.template(), is("v1"));
    assertThat(sent.subject(), is("Zamówienie A-77"));
    // The house language itself still reads as the business's own, and is not unusable.
    Answer house = call("GET", ORDER_PL, tenant, "MANAGER", null);
    assertThat(house.data().getString("source"), is("BUSINESS"));
    assertThat(house.data().getBoolean("storedWordsUnusable"), is(false));

    breakStoredBody(tenant, "pl");

    // Neither parses now: the platform's words, shown and sent alike, in both languages.
    Answer platform = call("GET", orderDe, tenant, "OWNER", null);
    assertThat(platform.data().getString("source"), is("DEFAULT"));
    assertThat(platform.data().getString("wordsLanguage"), is("en"));
    assertThat(platform.data().getString("body"), containsString("{{total}}"));
    assertThat(platform.data().getBoolean("storedWordsUnusable"), is(true));
    Messages.Composed fallback = messages.compose(tenant, german);
    assertThat(fallback.template(), is("default"));
    assertThat(fallback.language(), is("en"));
    Answer brokenHouse = call("GET", ORDER_PL, tenant, "OWNER", null);
    assertThat(brokenHouse.data().getString("source"), is("DEFAULT"));
    assertThat(brokenHouse.data().getBoolean("storedWordsUnusable"), is(true));
    assertThat(
        "French has no version of its own, so it is not told one is unusable",
        call("GET", "/templates/ORDER_CONFIRMED/EMAIL/fr", tenant, "OWNER", null)
            .data()
            .getBoolean("storedWordsUnusable"),
        is(false));

    // Saving German again retires the broken version, and the editor reads the new one as its own.
    Answer saved =
        call("PUT", orderDe, tenant, "OWNER", words("Bestellung {{order}}", "Gesamt: {{total}}"));
    assertThat(saved.body().toString(), saved.status(), is(200));
    assertThat(saved.data().getString("source"), is("BUSINESS"));
    assertThat(saved.data().getInt("version"), is(2));
    assertThat(saved.data().getBoolean("storedWordsUnusable"), is(false));
    assertThat(saved.data().getJsonArray("history").size(), is(2));
    assertThat(messages.compose(tenant, german).language(), is("de"));

    // Another business never had any of it: its owner and manager read the platform's words, and
    // staff below management read nothing, of either business.
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer theirs = call("GET", orderDe, other, role, null);
      assertThat(role, theirs.data().getString("source"), is("DEFAULT"));
      assertThat(role, theirs.data().getBoolean("storedWordsUnusable"), is(false));
      assertThat(role, theirs.body().toString(), not(containsString("Gesamt")));
      assertThat(role, theirs.body().toString(), not(containsString("never closed")));
    }
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, call("GET", orderDe, tenant, role, null).status(), is(403));
      assertThat(role, call("GET", orderDe, other, role, null).status(), is(403));
    }
  }

  // ── what is refused ────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("A value it does not have, a part the law needs, a broken or overlong template")
  void whatIsRefused() {
    UUID tenant = Ids.newId();
    Answer unknown =
        call(
            "PUT",
            ORDER_PL,
            tenant,
            "OWNER",
            words("{{order}}", "{{order}} {{password}} {{tenant_id}}"));
    assertThat(unknown.status(), is(400));
    assertThat(unknown.code(), is("TEMPLATE_VARIABLE_UNKNOWN"));
    assertThat(unknown.details(), is(List.of("password", "tenant_id")));

    String recallEmail = "/templates/RECALL_NOTICE/EMAIL/en";
    Answer noWhatToDo =
        call(
            "PUT",
            recallEmail,
            tenant,
            "OWNER",
            words(
                "Recall {{reference}}",
                "{{product}}: {{hazard}}. You may have {{remedies}}. Call {{contact}}."));
    assertThat(noWhatToDo.status(), is(422));
    assertThat(noWhatToDo.code(), is("TEMPLATE_PART_REQUIRED"));
    assertThat(noWhatToDo.details(), is(List.of("what_to_do")));
    Answer whole =
        call(
            "PUT",
            recallEmail,
            tenant,
            "OWNER",
            words(
                "Recall {{reference}}",
                "{{#products}}{{name}}{{/products}}: {{#hazard_allergen}}an allergen{{/hazard_allergen}}."
                    + " {{what_to_do}} {{#remedy_refund}}Refund.{{/remedy_refund}} {{contact_phone}}"));
    assertThat("codes and flags satisfy the parts too", whole.status(), is(200));

    Answer broken =
        call("PUT", ORDER_PL, tenant, "OWNER", words("{{order}}", "{{#total}}never closed"));
    assertThat(broken.code(), is("TEMPLATE_INVALID"));
    Answer noSubject = call("PUT", ORDER_PL, tenant, "OWNER", words("  ", "{{order}}"));
    assertThat(noSubject.code(), is("TEMPLATE_SUBJECT_REQUIRED"));
    Answer tooLong =
        call(
            "PUT",
            "/templates/ORDER_CONFIRMED/PUSH/en",
            tenant,
            "OWNER",
            words("{{order}}", "{{order}} " + "x".repeat(300)));
    assertThat(tooLong.code(), is("TEMPLATE_TOO_LONG"));

    assertThat(
        call(
                "PUT",
                "/templates/ORDER_CONFIRMED/EMAIL/POLISH",
                tenant,
                "OWNER",
                words("a", "{{order}}"))
            .code(),
        is("TEMPLATE_LANGUAGE_INVALID"));
    assertThat(
        call("GET", "/templates/NOPE/EMAIL/en", tenant, "OWNER", null).code(),
        is("MESSAGE_UNKNOWN"));
    assertThat(
        call("GET", "/templates/ORDER_CONFIRMED/SMS/en", tenant, "OWNER", null).code(),
        is("MESSAGE_FORM_UNKNOWN"));
    assertThat(call("GET", "/templates", tenant, "CASHIER", null).status(), is(403));
    assertThat(call("PUT", ORDER_PL, tenant, "CASHIER", words("a", "{{order}}")).status(), is(403));
    assertThat(
        call("PUT", ORDER_PL, tenant, "CUSTOMER", words("a", "{{order}}")).status(), is(403));
  }

  /** Flow catalogue MKT-23: a cashier or storekeeper reads, writes, retires or previews nothing. */
  @Test
  @DisplayName("Cashiers and storekeepers can neither read nor write templates or their settings")
  void staffBelowManagerTouchNoTemplate() {
    UUID tenant = Ids.newId();
    assertThat(
        call("PUT", ORDER_PL, tenant, "OWNER", words("Zamówienie {{order}}", "Razem {{total}}"))
            .status(),
        is(200));
    for (String role : new String[] {"CASHIER", "STOREKEEPER", "CUSTOMER"}) {
      assertThat(role, call("GET", "/templates", tenant, role, null).status(), is(403));
      assertThat(role, call("GET", ORDER_PL, tenant, role, null).status(), is(403));
      assertThat(
          role, call("PUT", ORDER_PL, tenant, role, words("x", "{{order}}")).status(), is(403));
      assertThat(role, call("DELETE", ORDER_PL, tenant, role, null).status(), is(403));
      assertThat(
          role,
          call("POST", ORDER_PL + "/preview", tenant, role, words("x", "{{order}}")).status(),
          is(403));
      assertThat(role, call("GET", "/template-settings", tenant, role, null).status(), is(403));
    }
    // Nothing moved: the owner's wording is still version 1.
    assertThat(call("GET", ORDER_PL, tenant, "OWNER", null).data().getInt("version"), is(1));
  }

  /**
   * Flow catalogue MKT-24: one business's wording is never another's, read, retired or replaced.
   */
  @Test
  @DisplayName("Another business, owner or manager, sees only the platform's words, never ours")
  void aBusinessesTemplatesAreItsOwn() {
    UUID ours = Ids.newId();
    UUID theirs = Ids.newId();
    assertThat(
        call("PUT", ORDER_PL, ours, "OWNER", words("Nasze {{order}}", "Nasze {{total}}")).status(),
        is(200));
    for (String role : new String[] {"OWNER", "MANAGER"}) {
      Answer read = call("GET", ORDER_PL, theirs, role, null);
      assertThat(role, read.data().getString("source"), is("DEFAULT"));
      assertThat(role, read.body().toString(), not(containsString("Nasze")));
      assertThat(
          role,
          call("GET", "/templates", theirs, role, null).body().toString(),
          not(containsString("\"language\":\"pl\",\"version\":1")));
      Answer retire = call("DELETE", ORDER_PL, theirs, role, null);
      assertThat(role, retire.status(), is(404));
      assertThat(role, retire.code(), is("TEMPLATE_NOT_WRITTEN"));
    }
    // Their own write lands beside ours as their version 1, and ours is untouched.
    Answer theirWords =
        call("PUT", ORDER_PL, theirs, "OWNER", words("Ich {{order}}", "Ich {{total}}"));
    assertThat(theirWords.data().getInt("version"), is(1));
    Answer ourRead = call("GET", ORDER_PL, ours, "OWNER", null);
    assertThat(ourRead.data().getInt("version"), is(1));
    assertThat(ourRead.body().toString(), containsString("Nasze"));
    assertThat(ourRead.body().toString(), not(containsString("Ich")));
  }

  @Test
  @DisplayName("Five saves at once: one live version, every version numbered once")
  void savesRacingEachOther() throws Exception {
    UUID tenant = Ids.newId();
    ExecutorService pool = Executors.newFixedThreadPool(5);
    List<Callable<Integer>> saves = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      String n = String.valueOf(i);
      saves.add(
          () ->
              call("PUT", ORDER_PL, tenant, "OWNER", words("Z " + n + " {{order}}", "{{total}}"))
                  .status());
    }
    List<Integer> statuses = new ArrayList<>();
    for (Future<Integer> f : pool.invokeAll(saves)) statuses.add(f.get());
    pool.shutdown();
    assertThat(statuses, everyItem(is(in(List.of(200, 409)))));
    assertThat(statuses, hasItem(200));
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password())) {
      c.setSchema("notification");
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT count(*) FILTER (WHERE retired_at IS NULL), count(*), count(DISTINCT version)"
                  + " FROM message_templates WHERE tenant_id = ?")) {
        ps.setObject(1, tenant);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          assertThat("exactly one live", rs.getInt(1), is(1));
          assertThat("each version once", rs.getInt(2), is(rs.getInt(3)));
          assertThat(rs.getInt(2), is((int) statuses.stream().filter(s -> s == 200).count()));
        }
      }
    }
  }

  // ── preview and settings ───────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "A preview writes the draft out with samples, counts the texts, and says what is wrong")
  void preview() {
    UUID tenant = Ids.newId();
    Answer ok =
        call(
            "POST",
            "/templates/RECALL_NOTICE/SMS/pl/preview",
            tenant,
            "MANAGER",
            words(
                "",
                "WYCOFANIE {{reference}}: {{product}}. {{remedies}}. {{contact}}. Ważne do {{bought_on}}"));
    assertThat(ok.body().toString(), ok.status(), is(200));
    assertThat(ok.data().getString("body"), containsString("WYCOFANIE RC-2026-014"));
    assertThat(ok.data().getString("body"), containsString("września 2026"));
    // "ż" is outside the GSM alphabet: every segment is 70 characters, not 160.
    assertThat(ok.data().getInt("smsSegments"), is(3));
    Answer english =
        call(
            "POST",
            "/templates/RECALL_NOTICE/SMS/en/preview",
            tenant,
            "MANAGER",
            words(
                "",
                "RECALL {{reference}}: {{product}}. {{remedies}}. {{contact}}. Valid to {{bought_on}}"));
    assertThat(english.data().getInt("smsSegments"), is(2));
    assertThat(ok.data().getJsonArray("problems").size(), is(0));

    Answer wrong =
        call(
            "POST",
            "/templates/RECALL_NOTICE/SMS/en/preview",
            tenant,
            "OWNER",
            words("", "Recall {{reference}} {{nonsense}}"));
    assertThat(wrong.status(), is(200));
    String problems = wrong.data().getJsonArray("problems").toString();
    assertThat(problems, containsString("TEMPLATE_VARIABLE_UNKNOWN"));
    assertThat(problems, containsString("TEMPLATE_PART_REQUIRED"));
  }

  @Test
  @DisplayName("The business's language and sign-off: what messages go out in, and are signed with")
  void settings() {
    UUID tenant = Ids.newId();
    Answer before = call("GET", "/template-settings", tenant, "OWNER", null);
    assertThat(before.data().getString("defaultLanguage"), is("en"));
    Answer put =
        call(
            "PUT",
            "/template-settings",
            tenant,
            "OWNER",
            "{\"defaultLanguage\":\"pl\",\"signOff\":\"Sklep Hollins\"}");
    assertThat(put.body().toString(), put.status(), is(200));
    assertThat(put.data().getString("signedAs"), is("Sklep Hollins"));
    assertThat(
        call("PUT", "/template-settings", tenant, "OWNER", "{\"defaultLanguage\":\"polish\"}")
            .status(),
        is(400));
    assertThat(
        call("PUT", "/template-settings", tenant, "CASHIER", "{\"defaultLanguage\":\"en\"}")
            .status(),
        is(403));
  }

  // ── the words reaching a message ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "Saved in Polish, sent in Polish to a Polish reader, recorded as such; others unaffected")
  void theWordsReachTheMessage() throws Exception {
    UUID tenant = Ids.newId();
    UUID other = Ids.newId();
    call(
        "PUT",
        "/template-settings",
        tenant,
        "OWNER",
        "{\"defaultLanguage\":\"en\",\"signOff\":\"Sklep Hollins\"}");
    call("PUT", ORDER_PL, tenant, "OWNER", words("Zamówienie {{order}}", "Razem: {{total}}"));
    call(
        "PUT",
        ORDER_PL,
        tenant,
        "OWNER",
        words("Zamówienie {{order}} potwierdzone", "Dziękujemy! Razem: {{total}}. — {{shop}}"));

    Values values = Values.of().text("order", "A-77").money("total", new BigDecimal("12.5"), "EUR");
    UUID event = Ids.newId();
    notifier.notifyOnce(
        event,
        "ORDER_CONFIRMATION",
        tenant,
        null,
        "ola@example.com",
        new Messages.Message("ORDER_CONFIRMED", Catalogue.Form.EMAIL, "pl", values));
    try (Connection c = DriverManager.getConnection(PG.jdbcUrl(), PG.username(), PG.password())) {
      c.setSchema("notification");
      try (PreparedStatement ps =
          c.prepareStatement(
              "SELECT subject, body, language, template FROM notification_log"
                  + " WHERE tenant_id = ? AND event_id = ?")) {
        ps.setObject(1, tenant);
        ps.setObject(2, event);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          assertThat(rs.getString(1), is("Zamówienie A-77 potwierdzone"));
          assertThat(
              rs.getString(2).replace(' ', ' '), is("Dziękujemy! Razem: 12,50 €. — Sklep Hollins"));
          assertThat(rs.getString(3), is("pl"));
          assertThat(rs.getString(4), is("v2"));
        }
      }
    }

    var english =
        messages.compose(
            tenant, new Messages.Message("ORDER_CONFIRMED", Catalogue.Form.EMAIL, "en", values));
    assertThat(english.template(), is("default"));
    assertThat(english.body(), containsString("— Sklep Hollins"));
    var elsewhere =
        messages.compose(
            other, new Messages.Message("ORDER_CONFIRMED", Catalogue.Form.EMAIL, "pl", values));
    assertThat("another business's words are its own", elsewhere.template(), is("default"));
    assertThat(
        call("GET", ORDER_PL, other, "OWNER", null).data().getString("source"), is("DEFAULT"));
  }
}
