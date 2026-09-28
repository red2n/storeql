package com.storeql.customer.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

/** What the staff customer search takes from its {@code q}, and the pattern it matches with. */
class CustomerSearchTest {

  @Test
  void anAbsentOrBlankQueryIsNoSearchAtAll() {
    assertThat(CustomerSearch.term(null), is(nullValue()));
    assertThat(CustomerSearch.term(""), is(nullValue()));
    assertThat(CustomerSearch.term("   "), is(nullValue()));
    assertThat(CustomerSearch.term("\t\n "), is(nullValue()));
  }

  @Test
  void aQueryIsTrimmedAndOtherwiseKeptAsTyped() {
    assertThat(CustomerSearch.term("  Ada Love  "), is("Ada Love"));
    assertThat(CustomerSearch.term("+44 7700"), is("+44 7700"));
  }

  @Test
  void aHundredCharactersIsTheMostAndTheyAreCountedAsCharactersNotCodeUnits() {
    assertThat(CustomerSearch.tooLong("x".repeat(100)), is(false));
    assertThat(CustomerSearch.tooLong("x".repeat(101)), is(true));
    // An emoji is two UTF-16 code units but one character to the person typing it.
    assertThat(CustomerSearch.tooLong("\uD83D\uDE00".repeat(100)), is(false));
    assertThat(CustomerSearch.tooLong("\uD83D\uDE00".repeat(101)), is(true));
    assertThat(CustomerSearch.tooLong(null), is(false));
  }

  @Test
  void thePatternIsASubstringMatchInWhichWildcardsStandForThemselves() {
    assertThat(CustomerSearch.pattern("ada"), is("%ada%"));
    assertThat(CustomerSearch.pattern("50%"), is("%50\\%%"));
    assertThat(CustomerSearch.pattern("a_b"), is("%a\\_b%"));
    assertThat(CustomerSearch.pattern("c:\\x"), is("%c:\\\\x%"));
  }

  @Test
  void aPhoneShapedQueryIsAlsoSearchedAsItsDigitsHoweverItIsSpacedOrPunctuated() {
    assertThat(CustomerSearch.phonePattern("07700 900111"), is("%07700900111%"));
    assertThat(CustomerSearch.phonePattern("07700900111"), is("%07700900111%"));
    assertThat(CustomerSearch.phonePattern("7700 900111"), is("%7700900111%"));
    assertThat(CustomerSearch.phonePattern("+44 (0)20 7946-0958"), is("%4402079460958%"));
    assertThat(CustomerSearch.phonePattern("020.7946/0958"), is("%02079460958%"));
    assertThat(CustomerSearch.phonePattern("0770\t0 900"), is("%07700900%"));
    assertThat(
        "a non-breaking space", CustomerSearch.phonePattern("0770\u00A0090"), is("%0770090%"));
  }

  @Test
  void fourDigitsAreTheFewestThatSearchThePhoneByItsDigits() {
    assertThat(CustomerSearch.phonePattern("0 7 7"), is(nullValue()));
    assertThat(CustomerSearch.phonePattern("+44"), is(nullValue()));
    assertThat(CustomerSearch.phonePattern("0 7 7 0"), is("%0770%"));
    assertThat(CustomerSearch.phonePattern("+ - ( ) ."), is(nullValue()));
    assertThat(CustomerSearch.phonePattern(null), is(nullValue()));
  }

  @Test
  void aQueryHoldingLettersIsNotAPhoneNumberSoItsDigitsAreNotSearchedAlone() {
    // An email with a birth year in it must not bring in every customer whose phone holds 1985.
    assertThat(CustomerSearch.phonePattern("john1985@mail.example"), is(nullValue()));
    assertThat(CustomerSearch.phonePattern("Ada 07700900111"), is(nullValue()));
    assertThat(CustomerSearch.phonePattern("0770O900111"), is(nullValue()));
    // Digits from another script are not the ASCII digits a stored phone is reduced to.
    assertThat(CustomerSearch.phonePattern("\u0660\u0661\u0662\u0663"), is(nullValue()));
    // Nor are wildcards: they never reach the digits pattern.
    assertThat(CustomerSearch.phonePattern("0770%900"), is(nullValue()));
    assertThat(CustomerSearch.phonePattern("0770_900"), is(nullValue()));
  }
}
