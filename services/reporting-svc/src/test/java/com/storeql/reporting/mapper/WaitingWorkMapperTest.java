package com.storeql.reporting.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.reporting.domain.PendingWork.Item;
import com.storeql.reporting.domain.PendingWork.Kind;
import com.storeql.reporting.domain.PendingWork.Report;
import com.storeql.reporting.dto.Dtos.WaitingWorkReport;
import com.storeql.web.PendingWorkCount;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Waiting work on the wire: a count stays a plain number, {@code capped} is always written beside
 * it, and a count the source could not give stays a null (and is never capped).
 */
class WaitingWorkMapperTest {

  private static Item item(Kind kind, Long count) {
    return new Item(kind, kind.label(), count, null, null);
  }

  private static JsonObject wire(Long... counts) throws Exception {
    List<Item> items = new java.util.ArrayList<>();
    Kind[] kinds = Kind.values();
    for (int i = 0; i < counts.length; i++) items.add(item(kinds[i], counts[i]));
    WaitingWorkReport dto =
        Mappers.toWaitingWorkReport(
            new Report(Instant.parse("2026-10-07T09:30:00Z"), items, List.of()));
    try (Jsonb jsonb = JsonbBuilder.create();
        var reader = Json.createReader(new StringReader(jsonb.toJson(dto)))) {
      return reader.readObject();
    }
  }

  private static JsonObject at(JsonObject wire, int index) {
    return wire.getJsonArray("items").getJsonObject(index);
  }

  @Test
  @DisplayName("Below the cap: the count as it is, capped false")
  void belowTheCap() throws Exception {
    JsonObject wire = wire((long) PendingWorkCount.CAP - 1);

    assertEquals(PendingWorkCount.CAP - 1L, at(wire, 0).getJsonNumber("count").longValueExact());
    assertTrue(at(wire, 0).containsKey("capped"));
    assertEquals(false, at(wire, 0).getBoolean("capped"));
  }

  @Test
  @DisplayName("At the cap and past it: the count as it is, capped true")
  void atTheCap() throws Exception {
    JsonObject wire = wire((long) PendingWorkCount.CAP, PendingWorkCount.CAP + 5L);

    for (int i = 0; i < 2; i++) assertEquals(true, at(wire, i).getBoolean("capped"));
    assertEquals((long) PendingWorkCount.CAP, at(wire, 0).getJsonNumber("count").longValueExact());
    assertEquals(PendingWorkCount.CAP + 5L, at(wire, 1).getJsonNumber("count").longValueExact());
  }

  @Test
  @DisplayName("A count the source could not give stays null and is written not capped")
  void unknownIsNullAndNotCapped() throws Exception {
    JsonObject wire = wire((Long) null, 0L);

    assertTrue(at(wire, 0).containsKey("count"));
    assertTrue(at(wire, 0).isNull("count"));
    assertEquals(false, at(wire, 0).getBoolean("capped"));
    assertEquals(0L, at(wire, 1).getJsonNumber("count").longValueExact());
    assertEquals(false, at(wire, 1).getBoolean("capped"));
  }
}
