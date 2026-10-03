package com.storeql.einvoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Poland's structured invoice, FA(3) (schema 1-0E, binding from 1 February 2026), written from the
 * EN 16931 model: KSeF alone takes no EN 16931 document, only its own. The header names the schema;
 * Podmiot1 is the seller by NIP, Podmiot2 the buyer by NIP, EU VAT number or none; Fa carries the
 * dates, the number, the totals per rate bucket (P_13_x net, P_14_x VAT), the annotations the
 * schema requires an answer to, the kind of invoice (VAT, or KOR naming the invoice it corrects),
 * and one FaWiersz per line with its rate as the schema spells it. A document allowance, which the
 * schema has no place for, is spread over the lines it came off as their P_10. A credit note is a
 * KOR whose lines are the differences: negative.
 */
public final class Fa3 {
  private static final java.util.regex.Pattern SPACE_DASH =
      java.util.regex.Pattern.compile("[\\s-]");

  public static final String NAMESPACE = "http://crd.gov.pl/wzor/2025/06/25/13775/";
  public static final String SYSTEM_CODE = "FA (3)";
  public static final String SCHEMA_VERSION = "1-0E";
  public static final String FORM_VALUE = "FA";
  public static final String VARIANT = "3";

  private Fa3() {}

  /** A rate as the schema spells it, and the total bucket it falls in. */
  record Bucket(String p12, String net, String vat) {}

  /**
   * The rate buckets: P_12's text, and the P_13/P_14 pair it totals into (VAT null where the bucket
   * carries none).
   */
  static Bucket bucket(String category, BigDecimal rate) {
    String c = category == null ? "S" : category;
    if ("E".equals(c)) return new Bucket("zw", "P_13_7", null);
    if ("AE".equals(c)) return new Bucket("oo", "P_13_9", null);
    if ("O".equals(c) || "G".equals(c)) return new Bucket("np I", "P_13_8", null);
    int r = rate == null ? 0 : rate.setScale(0, RoundingMode.HALF_UP).intValueExact();
    if ("Z".equals(c) || r == 0) return new Bucket("0 KR", "P_13_6_1", null);
    return switch (r) {
      case 23, 22 -> new Bucket(Integer.toString(r), "P_13_1", "P_14_1");
      case 8, 7 -> new Bucket(Integer.toString(r), "P_13_2", "P_14_2");
      case 5 -> new Bucket("5", "P_13_3", "P_14_3");
      case 4 -> new Bucket("4", "P_13_4", "P_14_4");
      case 3 -> new Bucket("3", "P_13_5", "P_14_5");
      default -> null;
    };
  }

  /** The Polish tax number in a VAT identifier, or null when the party is not Polish. */
  public static String nipOf(String vatId) {
    if (vatId == null) return null;
    String v = SPACE_DASH.matcher(vatId).replaceAll("").toUpperCase(Locale.ROOT);
    if (!v.startsWith("PL")) return null;
    String digits = v.substring(2);
    return digits.matches("\\d{10}") ? digits : null;
  }

  /**
   * Whether ten digits are a NIP: the first nine weighted 6 5 7 2 3 4 5 6 7, the sum mod 11 the
   * tenth digit. A remainder of 10 has no digit to match, so such a number is never valid.
   */
  public static boolean validNip(String nip) {
    if (nip == null || !nip.matches("\\d{10}")) return false;
    int[] w = {6, 5, 7, 2, 3, 4, 5, 6, 7};
    int sum = 0;
    for (int i = 0; i < 9; i++) sum += (nip.charAt(i) - '0') * w[i];
    int check = sum % 11;
    return check != 10 && check == nip.charAt(9) - '0';
  }

  /** What KSeF would refuse, as rules: FA3-*. Fatal ones stop the document being written. */
  public static List<Violation> check(Invoice inv) {
    List<Violation> out = new ArrayList<>();
    String nip = inv.seller() == null ? null : nipOf(inv.seller().vatId());
    if (nip == null || !validNip(nip)) {
      out.add(
          fatal(
              "FA3-SELLER-NIP",
              "the seller is named by a Polish NIP (PL and ten digits with their check digit)"));
    }
    if (inv.seller() == null || blank(inv.seller().name())) {
      out.add(fatal("FA3-SELLER-NAME", "the seller has a name"));
    }
    if (inv.buyer() == null || blank(inv.buyer().name())) {
      out.add(fatal("FA3-BUYER-NAME", "the buyer has a name"));
    }
    String buyerNip = inv.buyer() == null ? null : nipOf(inv.buyer().vatId());
    if (buyerNip != null && !validNip(buyerNip)) {
      out.add(fatal("FA3-BUYER-NIP", "the buyer's NIP has its check digit"));
    }
    if (inv.currency() == null || !inv.currency().matches("[A-Z]{3}")) {
      out.add(fatal("FA3-CURRENCY", "KodWaluty is a three-letter currency code"));
    }
    if (blank(inv.number()) || inv.number().length() > 256) {
      out.add(fatal("FA3-NUMBER", "P_2 is the invoice number, up to 256 characters"));
    }
    if (inv.issueDate() == null) out.add(fatal("FA3-DATE", "P_1 is the issue date"));
    if (inv.lines() == null || inv.lines().isEmpty()) {
      out.add(fatal("FA3-LINES", "an invoice has at least one FaWiersz"));
    } else {
      for (Invoice.Line l : inv.lines()) {
        if (bucket(l.vatCategory(), l.vatRate()) == null) {
          out.add(
              fatal(
                  "FA3-RATE",
                  "line "
                      + l.id()
                      + ": "
                      + l.vatRate()
                      + "% is not a Polish rate (23, 22, 8, 7, 5, 4, 3, 0, exempt or reverse charge)"));
        }
        if (l.quantity() == null || l.netAmount() == null) {
          out.add(fatal("FA3-LINE", "line " + l.id() + " has a quantity and a net value"));
        }
      }
    }
    if (inv.isCreditNote() && inv.precedingInvoices().isEmpty()) {
      out.add(fatal("FA3-KOR", "a correction names the invoice it corrects (DaneFaKorygowanej)"));
    }
    return out;
  }

  /**
   * The FA(3) document.
   *
   * @param createdAt DataWytworzeniaFa: when this document was written
   * @param systemInfo the software that wrote it, or null
   * @throws IllegalArgumentException when {@link #check} finds a fatal rule broken
   */
  public static String write(Invoice inv, Instant createdAt, String systemInfo) {
    List<Violation> broken = check(inv).stream().filter(Violation::isFatal).toList();
    if (!broken.isEmpty()) {
      throw new IllegalArgumentException(broken.get(0).rule() + ": " + broken.get(0).message());
    }
    boolean correction = inv.isCreditNote();
    BigDecimal sign = correction ? BigDecimal.ONE.negate() : BigDecimal.ONE;

    // Lines, with any document allowance spread over the lines of its rate as their P_10.
    Map<String, BigDecimal> groupNet = new LinkedHashMap<>();
    for (Invoice.Line l : inv.lines()) {
      groupNet.merge(groupKey(l), money(l.netAmount()), BigDecimal::add);
    }
    Map<String, BigDecimal> groupAllowance = new LinkedHashMap<>();
    for (Invoice.AllowanceCharge a : inv.allowanceCharges()) {
      if (a.charge()) continue;
      groupAllowance.merge(
          groupKey(a.vatCategory(), a.vatRate()), money(a.amount()), BigDecimal::add);
    }
    Map<String, BigDecimal> spent = new LinkedHashMap<>();
    StringBuilder rows = new StringBuilder();
    Map<String, BigDecimal> netByBucket = new LinkedHashMap<>();
    int n = 0;
    List<Invoice.Line> lines = inv.lines();
    for (int i = 0; i < lines.size(); i++) {
      Invoice.Line l = lines.get(i);
      String key = groupKey(l);
      BigDecimal net = money(l.netAmount());
      BigDecimal allowance = groupAllowance.getOrDefault(key, BigDecimal.ZERO);
      BigDecimal share = BigDecimal.ZERO;
      if (allowance.signum() > 0) {
        BigDecimal total = groupNet.get(key);
        boolean last = lastOfGroup(lines, i, key);
        share =
            last
                ? allowance.subtract(spent.getOrDefault(key, BigDecimal.ZERO))
                : money(allowance.multiply(net).divide(total, 6, RoundingMode.HALF_UP));
        spent.merge(key, share, BigDecimal::add);
      }
      Bucket b = bucket(l.vatCategory(), l.vatRate());
      BigDecimal lineNet = net.subtract(share);
      netByBucket.merge(b.net(), lineNet, BigDecimal::add);
      n++;
      rows.append("<FaWiersz>")
          .append(el("NrWierszaFa", Integer.toString(n)))
          .append(
              el(
                  "P_7",
                  l.item() == null || blank(l.item().name()) ? "Pozycja " + n : l.item().name()))
          .append(el("P_8A", unit(l.unitCode())))
          .append(el("P_8B", plain(l.quantity().multiply(sign))))
          .append(
              el(
                  "P_9A",
                  plain(
                      l.price() == null || l.price().net() == null
                          ? unitPrice(net, l.quantity())
                          : l.price().net())));
      if (share.signum() > 0) rows.append(el("P_10", amount(share.multiply(sign))));
      rows.append(el("P_11", amount(lineNet.multiply(sign))))
          .append(el("P_12", b.p12()))
          .append("</FaWiersz>");
    }

    // Totals per bucket: the breakdown's VAT where the model has one, else from the lines.
    Map<String, BigDecimal> vatByBucket = new LinkedHashMap<>();
    for (Invoice.VatBreakdown v : inv.vatBreakdown()) {
      Bucket b = bucket(v.category(), v.rate());
      if (b != null && b.vat() != null)
        vatByBucket.merge(b.vat(), money(v.taxAmount()), BigDecimal::add);
    }
    StringBuilder totals = new StringBuilder();
    for (String bucket :
        List.of(
            "P_13_1",
            "P_13_2",
            "P_13_3",
            "P_13_4",
            "P_13_5",
            "P_13_6_1",
            "P_13_7",
            "P_13_8",
            "P_13_9")) {
      BigDecimal net = netByBucket.get(bucket);
      if (net == null) continue;
      totals.append(el(bucket, amount(net.multiply(sign))));
      String vatBucket = bucket.replace("P_13", "P_14");
      if (vatByBucket.containsKey(vatBucket)) {
        totals.append(el(vatBucket, amount(vatByBucket.get(vatBucket).multiply(sign))));
      }
    }
    BigDecimal payable =
        inv.totals() == null || inv.totals().payable() == null
            ? BigDecimal.ZERO
            : money(inv.totals().payable());

    boolean reverseCharge = inv.lines().stream().anyMatch(l -> "AE".equals(l.vatCategory()));
    Invoice.Line exempt =
        inv.lines().stream().filter(l -> "E".equals(l.vatCategory())).findFirst().orElse(null);
    String exemptionReason =
        inv.vatBreakdown().stream()
            .filter(v -> "E".equals(v.category()))
            .map(Invoice.VatBreakdown::exemptionReason)
            .filter(r -> !blank(r))
            .findFirst()
            .orElse("zwolnienie z VAT");

    StringBuilder x = new StringBuilder();
    x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        .append("<Faktura xmlns=\"")
        .append(NAMESPACE)
        .append("\">")
        .append("<Naglowek>")
        .append("<KodFormularza kodSystemowy=\"")
        .append(SYSTEM_CODE)
        .append("\" wersjaSchemy=\"")
        .append(SCHEMA_VERSION)
        .append("\">")
        .append(FORM_VALUE)
        .append("</KodFormularza>")
        .append(el("WariantFormularza", VARIANT))
        .append(el("DataWytworzeniaFa", createdAt.truncatedTo(ChronoUnit.SECONDS).toString()));
    if (!blank(systemInfo)) x.append(el("SystemInfo", systemInfo));
    x.append("</Naglowek>");
    x.append("<Podmiot1>").append(party(inv.seller(), true)).append("</Podmiot1>");
    x.append("<Podmiot2>")
        .append(party(inv.buyer(), false))
        .append(el("JST", "2"))
        .append(el("GV", "2"))
        .append("</Podmiot2>");
    x.append("<Fa>")
        .append(el("KodWaluty", inv.currency()))
        .append(el("P_1", inv.issueDate().toString()))
        .append(el("P_2", inv.number()));
    LocalDate delivered =
        inv.delivery() == null || inv.delivery().actualDate() == null
            ? inv.issueDate()
            : inv.delivery().actualDate();
    if (!delivered.equals(inv.issueDate())) x.append(el("P_6", delivered.toString()));
    x.append(totals)
        .append(el("P_15", amount(payable.multiply(sign))))
        .append("<Adnotacje>")
        .append(el("P_16", "2"))
        .append(el("P_17", "2"))
        .append(el("P_18", reverseCharge ? "1" : "2"))
        .append(el("P_18A", "2"))
        .append("<Zwolnienie>")
        .append(exempt == null ? el("P_19N", "1") : el("P_19", "1") + el("P_19A", exemptionReason))
        .append("</Zwolnienie>")
        .append("<NoweSrodkiTransportu>")
        .append(el("P_22N", "1"))
        .append("</NoweSrodkiTransportu>")
        .append(el("P_23", "2"))
        .append("<PMarzy>")
        .append(el("P_PMarzyN", "1"))
        .append("</PMarzy>")
        .append("</Adnotacje>")
        .append(el("RodzajFaktury", correction ? "KOR" : "VAT"));
    if (correction) {
      Invoice.PrecedingInvoice p = inv.precedingInvoices().get(0);
      x.append(el("PrzyczynaKorekty", "Zwrot towaru"))
          .append(el("TypKorekty", "1"))
          .append("<DaneFaKorygowanej>")
          .append(
              el(
                  "DataWystFaKorygowanej",
                  p.issueDate() == null ? inv.issueDate().toString() : p.issueDate().toString()))
          .append(el("NrFaKorygowanej", p.number()))
          .append(el("NrKSeF", "2"))
          .append("</DaneFaKorygowanej>");
    }
    x.append(rows).append("</Fa></Faktura>");
    return x.toString();
  }

  private static String party(Invoice.Party p, boolean seller) {
    StringBuilder x = new StringBuilder("<DaneIdentyfikacyjne>");
    String nip = nipOf(p.vatId());
    if (nip != null) {
      x.append(el("NIP", nip));
    } else if (!seller && p.vatId() != null && p.vatId().matches("[A-Z]{2}[A-Z0-9]{2,}")) {
      x.append(el("KodUE", p.vatId().substring(0, 2)))
          .append(el("NrVatUE", p.vatId().substring(2)));
    } else if (!seller) {
      x.append(el("BrakID", "1"));
    }
    x.append(el("Nazwa", p.name())).append("</DaneIdentyfikacyjne>");
    Invoice.Address a = p.address();
    x.append("<Adres>")
        .append(el("KodKraju", a == null || blank(a.country()) ? "PL" : a.country()))
        .append(el("AdresL1", a == null || blank(a.line1()) ? p.name() : a.line1()));
    if (a != null) {
      String l2 = join(a.postcode(), a.city());
      if (!blank(l2)) x.append(el("AdresL2", l2));
    }
    x.append("</Adres>");
    return x.toString();
  }

  private static String groupKey(Invoice.Line l) {
    return groupKey(l.vatCategory(), l.vatRate());
  }

  private static String groupKey(String category, BigDecimal rate) {
    return (category == null ? "S" : category)
        + "|"
        + (rate == null ? "0" : rate.stripTrailingZeros().toPlainString());
  }

  private static boolean lastOfGroup(List<Invoice.Line> lines, int i, String key) {
    for (int j = i + 1; j < lines.size(); j++) {
      if (groupKey(lines.get(j)).equals(key)) return false;
    }
    return true;
  }

  /** The unit as the schema's free text names it; Recommendation 20 codes the Polish way. */
  static String unit(String code) {
    if (code == null) return "szt.";
    return switch (code.toUpperCase(Locale.ROOT)) {
      case "KGM" -> "kg";
      case "GRM" -> "g";
      case "LTR" -> "l";
      case "MLT" -> "ml";
      case "MTR" -> "m";
      case "CMT" -> "cm";
      case "MTK" -> "m2";
      case "DZN" -> "tuzin";
      case "XBX" -> "karton";
      case "XPK" -> "opak.";
      case "HUR" -> "godz.";
      default -> "szt.";
    };
  }

  private static BigDecimal unitPrice(BigDecimal net, BigDecimal quantity) {
    return quantity.signum() == 0 ? net : net.divide(quantity, 6, RoundingMode.HALF_UP);
  }

  private static BigDecimal money(BigDecimal x) {
    return (x == null ? BigDecimal.ZERO : x).setScale(2, RoundingMode.HALF_UP);
  }

  /** An amount: always two decimals, as the schema's TKwotowy reads best. */
  private static String amount(BigDecimal x) {
    return x.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /** A quantity or a unit price: as many decimals as it needs. */
  private static String plain(BigDecimal x) {
    BigDecimal v = x.stripTrailingZeros();
    if (v.scale() < 0) v = v.setScale(0);
    if (v.scale() < 2 && v.scale() > 0) v = v.setScale(2);
    return v.toPlainString();
  }

  private static String join(String a, String b) {
    if (blank(a)) return b == null ? "" : b;
    if (blank(b)) return a;
    return a + " " + b;
  }

  private static String el(String name, String value) {
    return "<" + name + ">" + escape(value) + "</" + name + ">";
  }

  private static String escape(String s) {
    StringBuilder b = new StringBuilder();
    for (char c : (s == null ? "" : s).toCharArray()) {
      switch (c) {
        case '<' -> b.append("&lt;");
        case '>' -> b.append("&gt;");
        case '&' -> b.append("&amp;");
        case '"' -> b.append("&quot;");
        default -> b.append(c);
      }
    }
    return b.toString();
  }

  private static Violation fatal(String rule, String message) {
    return new Violation(rule, Violation.Severity.FATAL, message);
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
