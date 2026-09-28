package com.storeql.iam.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.storeql.ids.Ids;
import com.storeql.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Reading {@code ?ids=} for the staff lookup: a comma-separated list of UUIDv7s, blanks and repeats
 * ignored, at most a hundred as sent, and one id that is not a UUIDv7 refuses the whole request.
 */
class StaffDirectoryTest {

  private static String csv(List<UUID> ids) {
    return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
  }

  private static List<UUID> fresh(int n) {
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < n; i++) ids.add(Ids.newId());
    return ids;
  }

  @Test
  void nothingAskedIsNothingToLookUp() {
    assertEquals(List.of(), StaffDirectory.parseIds(null));
    assertEquals(List.of(), StaffDirectory.parseIds(""));
    assertEquals(List.of(), StaffDirectory.parseIds("  "));
    assertEquals(List.of(), StaffDirectory.parseIds(" , ,"));
  }

  @Test
  void blanksAndRepeatsAreIgnoredAndTheOrderIsKept() {
    UUID a = Ids.newId();
    UUID b = Ids.newId();
    assertEquals(List.of(a, b), StaffDirectory.parseIds(" " + a + ",," + b + " ," + a + ","));
  }

  @Test
  void aHundredIsTheMostOneRequestNames() {
    assertEquals(100, StaffDirectory.parseIds(csv(fresh(100))).size());
    ApiException tooMany =
        assertThrows(ApiException.class, () -> StaffDirectory.parseIds(csv(fresh(101))));
    assertEquals(400, tooMany.status());
    assertEquals("STAFF_IDS_TOO_MANY", tooMany.code());
  }

  @Test
  void theLimitCountsWhatWasSentNotWhatWasDistinct() {
    // Refused before anything is parsed, so a long list costs nothing to turn away.
    UUID a = Ids.newId();
    String repeated = csv(java.util.Collections.nCopies(101, a));
    ApiException e = assertThrows(ApiException.class, () -> StaffDirectory.parseIds(repeated));
    assertEquals("STAFF_IDS_TOO_MANY", e.code());
  }

  @Test
  void anIdThatIsNotAUuidV7RefusesTheRequest() {
    UUID a = Ids.newId();
    for (String bad :
        new String[] {
          "not-a-uuid",
          // A version-4 id: never minted here, so it cannot name anybody.
          "0b6d7d5e-3c1a-4f4e-9b8e-5f1a2c3d4e5f",
          "1-1-1-1-1"
        }) {
      ApiException e =
          assertThrows(ApiException.class, () -> StaffDirectory.parseIds(a + "," + bad));
      assertEquals(400, e.status(), bad);
      assertEquals("INVALID_UUID", e.code(), bad);
    }
  }
}
