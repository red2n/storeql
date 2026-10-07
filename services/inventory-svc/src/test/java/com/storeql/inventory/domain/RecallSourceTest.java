package com.storeql.inventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.inventory.dto.RecallDtos.OpenRecallRequest;
import jakarta.validation.constraints.Pattern;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * A recall's source is not only a UK regulator: the domain, the DTO's pattern and the list agree.
 */
class RecallSourceTest {

  private static final Set<String> EXPECTED =
      Set.of("REGULATOR", "MANUFACTURER", "SUPPLIER", "INTERNAL", "OTHER", "FSA", "FSS");

  @Test
  void theDomainNamesCountryNeutralSourcesBesideTheUkRegulators() {
    Set<String> names =
        Arrays.stream(Recall.Source.values()).map(Enum::name).collect(Collectors.toSet());
    assertEquals(EXPECTED, names);
  }

  @Test
  void theRequestAcceptsExactlyTheDomainsSources() throws Exception {
    Pattern pattern =
        Arrays.stream(OpenRecallRequest.class.getRecordComponents())
            .filter(c -> "source".equals(c.getName()))
            .findFirst()
            .orElseThrow()
            .getAccessor()
            .getAnnotation(Pattern.class);
    assertTrue(pattern != null, "source carries a @Pattern");
    for (Recall.Source s : Recall.Source.values()) {
      assertTrue(s.name().matches(pattern.regexp()), s + " is accepted by the request");
    }
    assertTrue(!"THE_MINISTRY".matches(pattern.regexp()));
    assertTrue(!"fsa".matches(pattern.regexp()));
  }
}
