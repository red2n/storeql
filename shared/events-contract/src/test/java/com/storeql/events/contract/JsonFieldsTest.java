package com.storeql.events.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.storeql.ids.Ids;
import jakarta.json.JsonObject;
import java.io.StringReader;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class JsonFieldsTest {

  @Test
  void manyMembersBuildOneValidObjectAndCloseCanBeCalledTwice() {
    JsonFields f = new JsonFields("{\"eventType\":\"T\"");
    for (int i = 0; i < 200; i++) f.str("k" + i, "v\"" + i).num("n" + i, BigDecimal.valueOf(i, 2));
    String first = f.close();
    assertEquals(first, f.close(), "closing does not consume the builder");
    JsonObject o = EventReader.JSON.createReader(new StringReader(first)).readObject();
    assertEquals(401, o.size());
    assertEquals("v\"199", o.getString("k199"));
    assertEquals(new BigDecimal("1.99"), o.getJsonNumber("n199").bigDecimalValue());
  }

  @Test
  void theProviderIsSharedNotLookedUpPerCall() {
    assertSame(EventReader.JSON, EventReader.JSON);
    EventReader.open("{\"eventType\":\"X\",\"eventId\":\"" + Ids.newId() + "\"}", "X");
  }
}
