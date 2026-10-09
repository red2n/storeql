package com.storeql.product.domain.imports;

import com.storeql.product.domain.imports.ImportMapping.AliasColumn;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A saved mapping as JSON, and back; keys are written in a fixed order so its hash is stable. */
public final class ImportMappingJson {

  private ImportMappingJson() {}

  /** The mapping as JSON text. */
  public static String write(ImportMapping m) {
    JsonObjectBuilder columns = Json.createObjectBuilder();
    new TreeMap<>(m.columns()).forEach(columns::add);
    var aliases = Json.createArrayBuilder();
    for (AliasColumn a : m.aliasColumns()) {
      aliases.add(
          Json.createObjectBuilder()
              .add("header", a.header())
              .add("kind", a.kind())
              .add("packQty", a.packQty()));
    }
    JsonObjectBuilder vat = Json.createObjectBuilder();
    new TreeMap<>(m.vatCodes()).forEach(vat::add);
    JsonObjectBuilder sold = Json.createObjectBuilder();
    new TreeMap<>(m.soldByValues()).forEach(sold::add);
    JsonObjectBuilder o = Json.createObjectBuilder();
    o.add("aliasColumns", aliases);
    o.add("categorySeparator", m.categorySeparator() == null ? ">" : m.categorySeparator());
    o.add("columns", columns);
    if (m.dateFormat() != null) o.add("dateFormat", m.dateFormat());
    o.add("decimalMark", String.valueOf(m.decimalMark()));
    if (m.defaultVatCode() != null) o.add("defaultVatCode", m.defaultVatCode());
    o.add("priceBasis", m.priceBasis() == null ? "" : m.priceBasis());
    o.add("soldByValues", sold);
    o.add("vatCodes", vat);
    return o.build().toString();
  }

  /**
   * Reads a mapping.
   *
   * @throws IllegalArgumentException for text that is not a mapping
   */
  public static ImportMapping read(String json) {
    try (var reader = Json.createReader(new StringReader(json))) {
      JsonObject o = reader.readObject();
      Map<String, String> columns = strings(o.getJsonObject("columns"));
      List<AliasColumn> aliases = new ArrayList<>();
      JsonArray arr =
          o.containsKey("aliasColumns")
              ? o.getJsonArray("aliasColumns")
              : JsonValue.EMPTY_JSON_ARRAY;
      for (JsonObject a : arr.getValuesAs(JsonObject.class)) {
        aliases.add(
            new AliasColumn(a.getString("header"), a.getString("kind"), a.getInt("packQty", 1)));
      }
      Map<String, String> vat =
          upper(o.containsKey("vatCodes") ? strings(o.getJsonObject("vatCodes")) : Map.of());
      Map<String, String> sold =
          upper(
              o.containsKey("soldByValues") ? strings(o.getJsonObject("soldByValues")) : Map.of());
      String mark = o.getString("decimalMark", ".");
      return new ImportMapping(
          columns,
          aliases,
          vat,
          o.containsKey("defaultVatCode") && !o.isNull("defaultVatCode")
              ? o.getString("defaultVatCode")
              : null,
          o.getString("priceBasis", ""),
          mark.isEmpty() ? '.' : mark.charAt(0),
          o.containsKey("dateFormat") && !o.isNull("dateFormat") ? o.getString("dateFormat") : null,
          sold,
          o.getString("categorySeparator", ">"));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("not a mapping: " + e.getMessage(), e);
    }
  }

  private static Map<String, String> strings(JsonObject o) {
    Map<String, String> out = new LinkedHashMap<>();
    for (var e : o.entrySet())
      out.put(e.getKey(), ((jakarta.json.JsonString) e.getValue()).getString());
    return out;
  }

  private static Map<String, String> upper(Map<String, String> m) {
    Map<String, String> out = new LinkedHashMap<>();
    m.forEach((k, v) -> out.put(k.trim().toUpperCase(java.util.Locale.ROOT), v));
    return out;
  }
}
