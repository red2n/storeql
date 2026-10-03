package com.storeql.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EventSourceScanTest {

  @TempDir Path dir;

  @Test
  void findsTypesBuiltWithTheSharedBaseAndNothingElse() throws Exception {
    Path main = Files.createDirectories(dir.resolve("svc/src/main/java/x"));
    Files.writeString(
        main.resolve("Events.java"),
        "return EventPayload.base(\"OrderPlaced\", t, id) + \"}\";\n"
            + "return EventPayload\n    .base(\n \"OrderFulfilled\", t, id);\n"
            + "return EventPayload.baseOptionalTenant(\"PlatformActionRecorded\", null, id);\n"
            + "return \"{\\\"eventType\\\":\\\"HandBuilt\\\"}\";\n");
    Path test = Files.createDirectories(dir.resolve("svc/src/test/java/x"));
    Files.writeString(test.resolve("T.java"), "EventPayload.base(\"OnlyInTests\", t, id)");
    assertEquals(
        Set.of("OrderPlaced", "OrderFulfilled", "PlatformActionRecorded"),
        EventSourceScan.typesBuiltWithBase(dir));
  }
}
