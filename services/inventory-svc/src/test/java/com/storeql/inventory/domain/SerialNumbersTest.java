package com.storeql.inventory.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Generated serial numbers do not clash and a supplied list is judged exactly. */
class SerialNumbersTest {

  @Test
  void ahundredThousandGeneratedNumbersAreDistinctAndKeepThePrefix() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 100_000; i++) {
      String s = SerialNumbers.generate("K6");
      assertThat(s, startsWith("K6-"));
      assertThat(s, s.matches("K6-[0-9A-F]{16}"), is(true));
      seen.add(s);
    }
    assertThat(seen, hasSize(100_000));
  }

  @Test
  void aRepeatInASuppliedListIsFoundAfterTrimming() {
    List<String> nos = SerialNumbers.normalise(List.of("A1", " A1 ", "B2", "b2", "C3"));
    assertThat(SerialNumbers.repeated(nos), is(List.of("A1")));
  }

  @Test
  void aBlankNumberIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> SerialNumbers.normalise(List.of("A", " ")));
  }
}
