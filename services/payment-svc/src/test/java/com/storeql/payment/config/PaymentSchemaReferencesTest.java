package com.storeql.payment.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.storeql.service.TenantDataCatalog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payment schema's foreign keys, read from its migrations, can be exported, imported and erased
 * (21.14): {@link TenantDataCatalog} orders the tables so each comes after what it refers to, and a
 * cycle of references is a problem that turns every export, import and erasure of every business
 * into {@code 500 TENANT_DATA_CATALOG_INCOMPLETE}. Pure: the migrations are read as text, so a
 * cycle is caught at build time rather than by the first business that asks for its data.
 *
 * <p>Every table is given a {@code tenant_id} and an {@code id} key here, because the question is
 * only the references and the spec's names; the integration test ({@code
 * PermissionsIT.tenantDataIsExportable}) asks the real catalog.
 */
class PaymentSchemaReferencesTest {

  private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
  private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
  private static final Pattern CREATE =
      Pattern.compile("^create (?:unlogged )?table (?:if not exists )?([a-z_][a-z0-9_]*)");
  private static final Pattern ALTER =
      Pattern.compile("^alter table (?:if exists )?(?:only )?([a-z_][a-z0-9_]*)");
  private static final Pattern DROP =
      Pattern.compile("^drop table (?:if exists )?([a-z_][a-z0-9_]*)");
  private static final Pattern REFERENCES = Pattern.compile("references ([a-z_][a-z0-9_]*)");

  /** The schema as the migrations leave it: its tables, and which refers to which. */
  private record Schema(Set<String> tables, List<TenantDataCatalog.Reference> references) {}

  private static Schema read() throws IOException {
    List<Path> files;
    try (Stream<Path> all = Files.list(MIGRATIONS)) {
      files =
          all.filter(p -> VERSION.matcher(p.getFileName().toString()).matches())
              .sorted(Comparator.comparingInt(PaymentSchemaReferencesTest::version))
              .toList();
    }
    Set<String> tables = new LinkedHashSet<>();
    List<TenantDataCatalog.Reference> refs = new ArrayList<>();
    for (Path file : files) {
      String sql =
          Files.readString(file, StandardCharsets.UTF_8)
              .replaceAll("--[^\\n]*", " ")
              .toLowerCase(Locale.ROOT);
      for (String raw : sql.split(";")) {
        String stmt = raw.strip().replaceAll("\\s+", " ");
        Matcher created = CREATE.matcher(stmt);
        Matcher altered = ALTER.matcher(stmt);
        Matcher dropped = DROP.matcher(stmt);
        String table;
        if (created.find()) {
          table = created.group(1);
          tables.add(table);
        } else if (altered.find()) {
          table = altered.group(1);
        } else if (dropped.find()) {
          String gone = dropped.group(1);
          tables.remove(gone);
          refs.removeIf(r -> r.table().equals(gone) || r.referencedTable().equals(gone));
          continue;
        } else {
          continue;
        }
        Matcher r = REFERENCES.matcher(stmt);
        while (r.find()) refs.add(new TenantDataCatalog.Reference(table, r.group(1)));
      }
    }
    return new Schema(tables, refs);
  }

  private static int version(Path p) {
    Matcher m = VERSION.matcher(p.getFileName().toString());
    return m.matches() ? Integer.parseInt(m.group(1)) : -1;
  }

  private static TenantDataCatalog catalog(Schema schema) {
    List<TenantDataCatalog.RawColumn> columns = new ArrayList<>();
    Map<String, List<String>> keys = new TreeMap<>();
    for (String t : schema.tables()) {
      columns.add(new TenantDataCatalog.RawColumn(t, "id", "uuid", false));
      columns.add(new TenantDataCatalog.RawColumn(t, "tenant_id", "uuid", false));
      keys.put(t, List.of("id"));
    }
    return TenantDataCatalog.build(new ExportableData(), columns, keys, schema.references());
  }

  @Test
  @DisplayName(
      "No two payment tables refer to each other in a cycle, so a business's data can be exported,"
          + " imported and erased")
  void theReferencesCanBeOrdered() throws IOException {
    Schema schema = read();
    assertTrue(schema.tables().contains("card_refund_dues"), "the migrations were read");
    TenantDataCatalog cat = catalog(schema);
    assertEquals(List.of(), cat.problems(), "what stops payment-svc's data being exported");
  }

  @Test
  @DisplayName(
      "Money owed back to a card comes after the card payments it names on the way in, and goes"
          + " before them on the way out")
  void moneyOwedBackFollowsTheCardPayments() throws IOException {
    TenantDataCatalog cat = catalog(read());
    List<String> in = cat.ordered().stream().map(TenantDataCatalog.Table::name).toList();
    assertTrue(in.indexOf("terminal_payments") < in.indexOf("card_refund_dues"), in.toString());
    assertTrue(
        in.indexOf("terminal_payments") < in.indexOf("terminal_attempt_decisions"), in.toString());
    List<String> out = cat.erasureOrder().stream().map(TenantDataCatalog.Erasable::name).toList();
    assertTrue(out.indexOf("card_refund_dues") < out.indexOf("terminal_payments"), out.toString());
    assertTrue(
        out.indexOf("terminal_attempt_decisions") < out.indexOf("terminal_payments"),
        out.toString());
  }
}
