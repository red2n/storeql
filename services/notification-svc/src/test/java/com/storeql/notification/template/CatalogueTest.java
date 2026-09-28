package com.storeql.notification.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The platform's own words are held to the same rules as a business's: every default parses, uses
 * only its message's values, keeps its own required parts, and fits its form written out with the
 * sample — so a rule added to the catalogue that the defaults break fails here, not at a till.
 */
class CatalogueTest {

  @Test
  @DisplayName("Every default is a template its own checks would accept")
  void everyDefaultPassesItsOwnChecks() {
    for (Catalogue.MessageType t : Catalogue.all()) {
      for (Catalogue.FormSpec f : t.forms()) {
        String where = t.key() + "/" + f.form();
        Template subject = Template.parse(f.subject());
        Template body = Template.parse(f.body());
        Set<String> used = new TreeSet<>(subject.names());
        used.addAll(body.names());
        assertTrue(t.names().containsAll(used), where + " uses " + used + " of " + t.names());
        for (Set<String> group : f.required()) {
          assertTrue(group.stream().anyMatch(used::contains), where + " leaves out " + group);
          assertTrue(t.names().containsAll(group), where + " requires what it does not have");
        }
        var scope = t.sample().apply("EUR").in(Locale.forLanguageTag("en-GB"));
        String writtenBody = body.render(scope);
        assertFalse(writtenBody.isBlank(), where);
        assertTrue(writtenBody.length() <= f.form().bodyMax, where + " is too long");
        if (f.form().hasSubject()) {
          assertTrue(subject.render(scope).length() <= f.form().subjectMax, where + " subject");
        }
        assertFalse(writtenBody.contains("{{"), where);
      }
    }
  }

  @Test
  @DisplayName("Every sample fills every value its message declares, so a preview shows them all")
  void everySampleIsComplete() {
    for (Catalogue.MessageType t : Catalogue.all()) {
      Values sample = t.sample().apply("EUR");
      for (Catalogue.Variable v : t.variables()) {
        // shop is the business's own; a single remedy's reason exists only when one is offered.
        if (v.name().contains(".") || v.name().equals("shop")) continue;
        if (v.name().equals("single_remedy_reason")) continue;
        if (v.name().startsWith("hazard_") && !v.name().equals("hazard_code")) continue;
        assertTrue(sample.has(v.name()), t.key() + " has no sample for " + v.name());
      }
    }
  }

  @Test
  @DisplayName("A recall notice by email must say every part GPSR art.36(2) names")
  void theRecallNoticeKeepsTheLawsParts() {
    var email = Catalogue.get("RECALL_NOTICE").form(Catalogue.Form.EMAIL).orElseThrow();
    assertEquals(6, email.required().size());
    assertTrue(email.required().stream().anyMatch(g -> g.contains("what_to_do")));
    assertTrue(email.required().stream().anyMatch(g -> g.contains("remedy_refund")));
  }

  @Test
  @DisplayName("The defaults sign a customer's email with the shop, and never with the platform")
  void theShopSignsItsOwnEmail() {
    for (String key : new String[] {"ORDER_CONFIRMED", "RECALL_NOTICE"}) {
      var email = Catalogue.get(key).form(Catalogue.Form.EMAIL).orElseThrow();
      assertTrue(email.body().endsWith("— {{shop}}"), key);
      assertFalse(email.body().contains("StoreQL"), key);
    }
  }

  /**
   * No sample bakes in a currency of its own any more — every one that carries a MONEY value writes
   * it out in whichever currency it is given, so a PLN business's preview never shows a GBP amount
   * and an INR business's never shows a PLN one.
   */
  @Test
  @DisplayName("Every sample with a money value writes it in the currency it is given, not GBP")
  void everyMoneySampleFollowsTheCurrencyItIsGiven() {
    for (Catalogue.MessageType t : Catalogue.all()) {
      boolean hasMoney = t.variables().stream().anyMatch(v -> "MONEY".equals(v.kind()));
      if (!hasMoney) continue;
      String pln = renderedWith(t, "PLN");
      String inr = renderedWith(t, "INR");
      assertFalse(pln.contains("£"), t.key() + " a PLN preview must never show £: " + pln);
      assertFalse(pln.contains("GBP"), t.key() + " a PLN preview must never show GBP: " + pln);
      assertTrue(pln.contains("PLN") || pln.contains("zł"), t.key() + " should show PLN: " + pln);
      assertTrue(inr.contains("₹") || inr.contains("INR"), t.key() + " should show INR: " + inr);
      assertFalse(pln.equals(inr), t.key() + " two different currencies must render differently");
    }
  }

  /** The one form a message goes out in — its email, or its only form when it has no email. */
  private static String renderedWith(Catalogue.MessageType t, String currency) {
    Catalogue.FormSpec spec = t.form(Catalogue.Form.EMAIL).orElseGet(() -> t.forms().get(0));
    var scope = t.sample().apply(currency).in(Locale.forLanguageTag("en"));
    return Template.parse(spec.body()).render(scope);
  }
}
