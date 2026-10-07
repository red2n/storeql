package com.storeql.purchase;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No column in this service's schema rounds money at a fixed two places (SJ-D25).
 *
 * <p>Postgres rounds to a column's declared scale on write without complaint, so a {@code
 * NUMERIC(p,2)} money column silently drops a dinar's third decimal and answers whole yen as {@code
 * 1843.00}. Every column the migrations declare at scale 2 must either be altered to unconstrained
 * {@code NUMERIC} by a later migration (the currency then decides the precision) or be named below
 * as not money.
 *
 * <p>A column is known by its table and its name ({@code supplier_invoices.net_amount}), never by
 * its name alone: {@code net_amount} widened in one table says nothing of a {@code net_amount}
 * another table still holds at two places. Each statement is read for the table it creates or
 * alters, and renames and drops follow the column.
 */
class MoneyColumnScaleTest {

  private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

  /** Scale-2 columns that are percentages, not amounts in any currency. */
  private static final Set<String> NOT_MONEY =
      Set.of(
          "deferred_revenue_settings.points_breakage_pct",
          "deferred_revenue_settings.gift_card_breakage_pct");

  private static final String NAME = "[a-z_][a-z0-9_]*";

  /** The table a statement creates or alters; a schema prefix is dropped. */
  private static final Pattern TABLE_OF =
      Pattern.compile(
          "(?is)^\\s*(?:CREATE\\s+(?:(?:GLOBAL|LOCAL)\\s+)?(?:TEMP(?:ORARY)?\\s+|UNLOGGED\\s+)?"
              + "TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
              + "|ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?)"
              + "(?:"
              + NAME
              + "\\.)?("
              + NAME
              + ")");

  private static final Pattern DROP_TABLE =
      Pattern.compile("(?is)^\\s*DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([a-z0-9_.,\\s]+)");

  /** A column declared at scale 2: in a CREATE TABLE, or by ADD COLUMN. */
  private static final Pattern FIXED_TWO =
      Pattern.compile("(?i)\\b(" + NAME + ")\\s+NUMERIC\\s*\\(\\s*\\d+\\s*,\\s*2\\s*\\)");

  /** A column's type changed to NUMERIC: unconstrained (group 2 absent), or to (p) or (p,s). */
  private static final Pattern ALTER_TYPE =
      Pattern.compile(
          "(?i)ALTER\\s+COLUMN\\s+("
              + NAME
              + ")\\s+(?:SET\\s+DATA\\s+)?TYPE\\s+NUMERIC\\b(\\s*\\(\\s*\\d+\\s*(?:,\\s*(\\d+)\\s*)?\\))?");

  private static final Pattern RENAME_TABLE =
      Pattern.compile("(?i)\\bRENAME\\s+TO\\s+(" + NAME + ")");
  private static final Pattern RENAME_COLUMN =
      Pattern.compile("(?i)\\bRENAME\\s+(?:COLUMN\\s+)?(" + NAME + ")\\s+TO\\s+(" + NAME + ")");
  private static final Pattern DROP_COLUMN =
      Pattern.compile("(?i)\\bDROP\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?(" + NAME + ")");

  @Test
  @DisplayName("Every scale-2 column is a percentage or was widened to unconstrained NUMERIC")
  void noMoneyColumnIsFixedAtTwoPlaces() throws IOException {
    List<String> scripts = new ArrayList<>();
    for (Path file : migrationsInOrder()) {
      scripts.add(Files.readString(file, StandardCharsets.UTF_8));
    }
    assertThat(offenders(scripts), empty());
  }

  @Test
  @DisplayName(
      "A column is its table's: net_amount widened in one table does not hide another table's"
          + " net_amount still at two places; a rename and a drop follow the column")
  void aColumnIsKnownByItsTable() {
    assertThat(
        offenders(
            List.of(
                "CREATE TABLE a (id UUID, net_amount NUMERIC(14,2) NOT NULL);\n"
                    + "CREATE TABLE IF NOT EXISTS b (net_amount NUMERIC(14,2), qty NUMERIC(14,3));",
                "-- widen a only\nALTER TABLE a ALTER COLUMN net_amount TYPE NUMERIC;")),
        contains("b.net_amount"));

    assertThat(
        offenders(
            List.of(
                "CREATE TABLE a (fee NUMERIC(14,2));",
                "ALTER TABLE a RENAME COLUMN fee TO charge;",
                "ALTER TABLE a RENAME TO c;",
                "ALTER TABLE ONLY public.c ADD COLUMN total NUMERIC(18,2), DROP COLUMN charge;")),
        contains("c.total"));

    assertThat(
        "widened, then fixed at two places again",
        offenders(
            List.of(
                "CREATE TABLE a (fee NUMERIC(14,2));",
                "ALTER TABLE a ALTER COLUMN fee TYPE NUMERIC;",
                "ALTER TABLE a ALTER COLUMN fee SET DATA TYPE NUMERIC(20,2);")),
        contains("a.fee"));

    assertThat(
        "a dropped table's columns are gone",
        offenders(List.of("CREATE TABLE a (fee NUMERIC(14,2));", "DROP TABLE IF EXISTS a;")),
        empty());
  }

  /**
   * The columns that, once every script has run in order, hold money at a fixed two places.
   *
   * @param scripts the migrations' SQL, oldest first
   * @return {@code table.column} for each, in the order first declared
   */
  static List<String> offenders(List<String> scripts) {
    Map<String, Boolean> atTwo = new LinkedHashMap<>();
    for (String script : scripts) {
      for (String statement : statements(script)) {
        apply(statement, atTwo);
      }
    }
    List<String> out = new ArrayList<>();
    atTwo.forEach(
        (column, two) -> {
          if (two && !NOT_MONEY.contains(column)) out.add(column);
        });
    return out;
  }

  private static void apply(String statement, Map<String, Boolean> atTwo) {
    Matcher dropTable = DROP_TABLE.matcher(statement);
    if (dropTable.find()) {
      for (String t : dropTable.group(1).split(",")) {
        String name = t.strip().toLowerCase(Locale.ROOT);
        String table = name.substring(name.lastIndexOf('.') + 1).split("\\s+")[0];
        atTwo.keySet().removeIf(k -> k.startsWith(table + "."));
      }
      return;
    }
    Matcher t = TABLE_OF.matcher(statement);
    if (!t.find()) return;
    String table = t.group(1).toLowerCase(Locale.ROOT);
    String body = statement.substring(t.end());

    // Every change the statement makes, in the order it makes them.
    record Change(int at, Runnable apply) {}
    List<Change> changes = new ArrayList<>();
    Matcher f = FIXED_TWO.matcher(body);
    while (f.find()) {
      String column = f.group(1).toLowerCase(Locale.ROOT);
      if (column.equals("type")) continue; // ALTER COLUMN c TYPE NUMERIC(p,2): ALTER_TYPE's
      changes.add(new Change(f.start(), () -> atTwo.put(table + "." + column, true)));
    }
    Matcher a = ALTER_TYPE.matcher(body);
    while (a.find()) {
      String column = a.group(1).toLowerCase(Locale.ROOT);
      boolean two = a.group(2) != null && "2".equals(a.group(3));
      changes.add(new Change(a.start(), () -> atTwo.put(table + "." + column, two)));
    }
    Matcher dc = DROP_COLUMN.matcher(body);
    while (dc.find()) {
      String column = dc.group(1).toLowerCase(Locale.ROOT);
      changes.add(new Change(dc.start(), () -> atTwo.remove(table + "." + column)));
    }
    Matcher rt = RENAME_TABLE.matcher(body);
    boolean renamedTable = false;
    while (rt.find()) {
      renamedTable = true;
      String to = rt.group(1).toLowerCase(Locale.ROOT);
      changes.add(new Change(rt.start(), () -> moveTable(atTwo, table, to)));
    }
    if (!renamedTable) {
      Matcher rc = RENAME_COLUMN.matcher(body);
      while (rc.find()) {
        String from = table + "." + rc.group(1).toLowerCase(Locale.ROOT);
        String to = table + "." + rc.group(2).toLowerCase(Locale.ROOT);
        changes.add(
            new Change(
                rc.start(),
                () -> {
                  Boolean two = atTwo.remove(from);
                  if (two != null) atTwo.put(to, two);
                }));
      }
    }
    changes.sort(Comparator.comparingInt(Change::at));
    changes.forEach(c -> c.apply().run());
  }

  private static void moveTable(Map<String, Boolean> atTwo, String from, String to) {
    Map<String, Boolean> moved = new LinkedHashMap<>();
    atTwo
        .entrySet()
        .removeIf(
            e -> {
              if (!e.getKey().startsWith(from + ".")) return false;
              moved.put(to + e.getKey().substring(from.length()), e.getValue());
              return true;
            });
    atTwo.putAll(moved);
  }

  /**
   * The script's statements, without comments or dollar-quoted bodies (which declare no column).
   */
  private static List<String> statements(String sql) {
    String plain =
        sql.replaceAll("(?s)/\\*.*?\\*/", " ")
            .replaceAll("--[^\\n]*", "")
            .replaceAll("(?s)\\$([a-zA-Z_]*)\\$.*?\\$\\1\\$", " ");
    List<String> out = new ArrayList<>();
    for (String s : plain.split(";")) {
      if (!s.isBlank()) out.add(s.strip());
    }
    return out;
  }

  private static List<Path> migrationsInOrder() throws IOException {
    try (Stream<Path> files = Files.list(MIGRATIONS)) {
      return files
          .filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
          .sorted(Comparator.comparingInt(MoneyColumnScaleTest::version))
          .toList();
    }
  }

  private static int version(Path file) {
    String name = file.getFileName().toString();
    return Integer.parseInt(name.substring(1, name.indexOf("__")));
  }
}
