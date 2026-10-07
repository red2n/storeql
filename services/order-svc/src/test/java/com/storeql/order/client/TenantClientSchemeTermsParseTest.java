package com.storeql.order.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.ids.Ids;
import com.storeql.order.client.TenantClient.SchemeTerms;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** tenant-svc's scheme list read as what each arrangement pays in, as its answer is shaped. */
class TenantClientSchemeTermsParseTest {

  @Test
  void eachSchemeSaysWhatItPaysIn() {
    UUID perUnit = Ids.newId();
    UUID percent = Ids.newId();
    UUID withdrawn = Ids.newId();
    // JSON-B leaves a null member out entirely: a percentage carries no currency key at all.
    String body =
        "{\"data\":["
            + "{\"id\":\""
            + perUnit
            + "\",\"name\":\"per tin\",\"basis\":\"PER_UNIT\",\"currency\":\"eur\","
            + "\"status\":\"ACTIVE\",\"bands\":[{\"thresholdFrom\":\"0.000\",\"rate\":\"0.1\"}]},"
            + "{\"id\":\""
            + percent
            + "\",\"name\":\"counter\",\"basis\":\"PERCENT_OF_NET\",\"status\":\"ACTIVE\","
            + "\"bands\":[]},"
            + "{\"id\":\""
            + withdrawn
            + "\",\"name\":\"old\",\"basis\":\"PER_UNIT\",\"currency\":\"JPY\","
            + "\"status\":\"WITHDRAWN\",\"supersededBy\":null,\"bands\":[]}"
            + "],\"error\":null,\"meta\":{}}";

    Map<UUID, SchemeTerms> terms = TenantClient.schemeTerms(body);

    assertEquals(3, terms.size());
    assertTrue(terms.get(perUnit).perUnit());
    assertEquals("EUR", terms.get(perUnit).currency());
    assertFalse(terms.get(percent).perUnit());
    assertNull(terms.get(percent).currency());
    // Withdrawn and superseded versions are listed too: a period may have been earned under one.
    assertEquals("JPY", terms.get(withdrawn).currency());
  }
}
