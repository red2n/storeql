package com.storeql.order.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A phone at the till (intent/phone-at-the-till.md), the pure part: what a store's till asks, when
 * a till sale lacks the number its store asks for, and how a contact number reads — in the store's
 * own country first, never a country named here.
 */
class TillPhoneTest {

  @Test
  @DisplayName("A store's choice is read as recorded; none, or anything else, is Optional")
  void aChoiceNobodyCouldReadIsOptional() {
    assertEquals("REQUIRED", TillPhone.ask("REQUIRED"));
    assertEquals("OFF", TillPhone.ask("OFF"));
    assertEquals("OPTIONAL", TillPhone.ask("OPTIONAL"));
    assertEquals("OPTIONAL", TillPhone.ask(null), "a store tenant-svc could not be asked about");
    assertEquals("OPTIONAL", TillPhone.ask("SOMETIMES"));
  }

  @Test
  @DisplayName("Only a Required store refuses, and only a sale with no number and no customer")
  void onlyRequiredRefusesAndOnlyWithNeither() {
    assertTrue(TillPhone.missing("REQUIRED", null, null));
    assertTrue(TillPhone.missing("REQUIRED", null, "   "), "blank is no number");
    assertFalse(TillPhone.missing("REQUIRED", null, "98860 21001"), "a number given");
    assertFalse(TillPhone.missing("REQUIRED", Ids.newId(), null), "a customer named");
    assertFalse(TillPhone.missing("OPTIONAL", null, null), "the customer may say no");
    assertFalse(TillPhone.missing("OFF", null, null), "the till never asks");
  }

  @Test
  @DisplayName("A number is read in the store's country first: the same digits differ by shop")
  void theStoresOwnCountryComesFirst() {
    // A mobile in both the Netherlands and France; a French business with a Dutch shop.
    assertEquals(
        "+31612345678",
        TillPhone.read("06 12 34 56 78", "NL", "FR", List.of("FR", "NL")).e164(),
        "at the Dutch shop");
    assertEquals(
        "+33612345678",
        TillPhone.read("06 12 34 56 78", "FR", "FR", List.of("FR", "NL")).e164(),
        "at the French shop");
  }

  @Test
  @DisplayName("A number typed the local way is read as the international form")
  void aLocallyTypedNumberReads() {
    TillPhone.Reading r = TillPhone.read("98860 21001", "IN", "IN", List.of("IN"));
    assertEquals("+919886021001", r.e164());
    assertFalse(r.unreadable());
    assertEquals(
        "+919886021001",
        TillPhone.read("+91 98860 21001", null, null, List.of()).e164(),
        "a + number needs no country");
    assertEquals(
        "+919886021001",
        TillPhone.read("98860 21001", "GB", "GB", List.of("GB", "IN")).e164(),
        "the business's other countries are tried after the store's own");
  }

  @Test
  @DisplayName("A number that is no phone where the business trades is unreadable")
  void aNumberThatIsNoPhoneAnywhereIsUnreadable() {
    TillPhone.Reading r = TillPhone.read("12345", "IN", "IN", List.of("IN"));
    assertNull(r.e164());
    assertTrue(r.unreadable());
    assertTrue(
        TillPhone.read("+44 7700 900111", null, null, List.of()).unreadable(),
        "a + number judges itself even when no country is known");
  }

  @Test
  @DisplayName("With no country known a national number cannot be judged, so it is not refused")
  void withNoCountryKnownNothingIsJudged() {
    TillPhone.Reading r = TillPhone.read("98860 21001", null, null, List.of());
    assertNull(r.e164());
    assertFalse(r.unreadable(), "kept as typed, never refused for want of a country");
    assertFalse(TillPhone.read("98860 21001", null, null, null).unreadable());
  }

  @Test
  @DisplayName("No number is simply no number")
  void noNumberIsNoNumber() {
    assertNull(TillPhone.read(null, "IN", "IN", List.of()).e164());
    assertFalse(TillPhone.read(null, "IN", "IN", List.of()).unreadable());
    assertFalse(TillPhone.read("  ", "IN", "IN", List.of()).unreadable());
  }
}
