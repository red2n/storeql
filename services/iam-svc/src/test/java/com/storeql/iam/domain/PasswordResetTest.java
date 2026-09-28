package com.storeql.iam.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The pure rules of forgotten-password: what kind of entry a login gets from the facts read for it,
 * the address hash a throttle and every token key on, the language a request named (read strictly,
 * never refusing the request), and the link a reset email carries.
 */
class PasswordResetTest {

  // ── kindOf ──────────────────────────────────────────────────────────────────

  @Test
  void aShopperLoginGetsALink() {
    assertEquals(
        PasswordReset.Kind.SHOPPER,
        PasswordReset.kindOf(
            /* active= */ true,
            /* platformAdmin= */ false,
            /* businessActive= */ true,
            /* staff= */ false,
            /* ssoRequired= */ false));
  }

  @Test
  void aStaffLoginGetsALinkNamedByItsBusiness() {
    assertEquals(PasswordReset.Kind.STAFF, PasswordReset.kindOf(true, false, true, true, false));
  }

  @Test
  void staffOfABusinessRequiringSingleSignOnGetsNamedWithNoLink() {
    assertEquals(PasswordReset.Kind.STAFF_SSO, PasswordReset.kindOf(true, false, true, true, true));
  }

  @Test
  void ssoRequiredIsIgnoredForAShopperWhoHasNoBusiness() {
    // A shopper can never be "sso required" in practice (PasswordResetService never asks the
    // question for one), but the pure rule is still sound if it ever were.
    assertEquals(PasswordReset.Kind.SHOPPER, PasswordReset.kindOf(true, false, true, false, true));
  }

  @Test
  void aSuspendedLoginGetsNoEntry() {
    assertEquals(PasswordReset.Kind.NONE, PasswordReset.kindOf(false, false, true, false, false));
    assertEquals(PasswordReset.Kind.NONE, PasswordReset.kindOf(false, false, true, true, false));
  }

  @Test
  void thePlatformAdministratorGetsNoEntryEvenActiveAndNotStaff() {
    assertEquals(PasswordReset.Kind.NONE, PasswordReset.kindOf(true, true, true, false, false));
  }

  @Test
  void aStaffLoginOfASuspendedBusinessGetsNoEntry() {
    assertEquals(PasswordReset.Kind.NONE, PasswordReset.kindOf(true, false, false, true, true));
    assertEquals(PasswordReset.Kind.NONE, PasswordReset.kindOf(true, false, false, true, false));
  }

  // ── addressHash ─────────────────────────────────────────────────────────────

  @Test
  void theHashIsNeverTheAddressAndIsSixtyFourHexCharacters() {
    String hash = PasswordReset.addressHash("a@b.example");
    assertEquals(64, hash.length());
    assertEquals(hash, hash.toLowerCase(java.util.Locale.ROOT));
    org.hamcrest.MatcherAssert.assertThat(hash, org.hamcrest.Matchers.not("a@b.example"));
  }

  @Test
  void caseAndSurroundingSpaceDoNotChangeTheHash() {
    String canonical = PasswordReset.addressHash("a@b.example");
    assertEquals(canonical, PasswordReset.addressHash("A@B.EXAMPLE"));
    assertEquals(canonical, PasswordReset.addressHash("  a@b.example  "));
    assertEquals(canonical, PasswordReset.addressHash("A@b.Example"));
  }

  @Test
  void differentAddressesHashDifferently() {
    org.hamcrest.MatcherAssert.assertThat(
        PasswordReset.addressHash("a@b.example"),
        org.hamcrest.Matchers.not(PasswordReset.addressHash("c@d.example")));
  }

  // ── language ────────────────────────────────────────────────────────────────

  @Test
  void aWellFormedLanguageIsKeptLowerCased() {
    assertEquals("pl", PasswordReset.language("pl"));
    assertEquals("pl", PasswordReset.language("PL"));
    assertEquals("ban", PasswordReset.language("ban"));
    assertEquals("pl", PasswordReset.language("  pl  "));
  }

  @Test
  void anythingElseReadsAsEnglishRatherThanRefusingTheRequest() {
    assertNull(PasswordReset.language(null));
    assertNull(PasswordReset.language(""));
    assertNull(PasswordReset.language("   "));
    assertNull(PasswordReset.language("english"));
    assertNull(PasswordReset.language("p"));
    assertNull(PasswordReset.language("pol-PL"));
    assertNull(PasswordReset.language("12"));
    assertNull(PasswordReset.language("pl2"));
  }

  // ── link ────────────────────────────────────────────────────────────────────

  @Test
  void theLinkIsTheWebUrlAndTheHashRouteAndTheRawToken() {
    assertEquals(
        "https://app.example/#/reset-password/tok123",
        PasswordReset.link("https://app.example", "tok123"));
  }

  @Test
  void aTrailingSlashOnTheWebUrlIsNotDoubled() {
    assertEquals(
        "https://app.example/#/reset-password/tok123",
        PasswordReset.link("https://app.example/", "tok123"));
  }
}
