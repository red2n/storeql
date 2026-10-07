package com.storeql.einvoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * India's e-invoice as the Invoice Registration Portal takes it: FORM GST INV-01, schema 1.1, as
 * JSON, written from the same EN 16931 model the UBL and CII writers take.
 *
 * <p>A business whose aggregate turnover has passed ₹5 crore in any year since 2017-18 reports
 * every B2B invoice, credit note and debit note here and prints the IRN and QR code the portal
 * returns; from ₹10 crore it must do so within 30 days of the document's date. This class writes
 * the document and checks it the way the portal validates it. Submitting it and keeping the IRN is
 * the transport's work, not this class's.
 *
 * <p>What INV-01 needs that EN 16931 holds elsewhere: the GSTIN is the party's VAT identifier
 * (BT-31, BT-48); the HSN or SAC code is an item classification in the HS scheme (BT-158); the
 * state is the GSTIN's first two digits, or a GST state code in the delivery address's subdivision
 * (BT-79) for the place of supply; the PIN is the postcode.
 */
public final class Irp {
  private static final java.util.regex.Pattern NON_DIGITS =
      java.util.regex.Pattern.compile("[^0-9]");

  /** The INV-01 schema version the payload declares. */
  public static final String SCHEMA_VERSION = "1.1";

  /** UNTDID 7143 HS: HSN and SAC codes are India's extension of the Harmonized System. */
  public static final String HS_SCHEME = "HS";

  private static final Pattern DOCUMENT_NUMBER = Pattern.compile("[A-Z1-9][A-Z0-9/-]{0,15}");
  private static final Pattern HSN = Pattern.compile("[0-9]{4}|[0-9]{6}|[0-9]{8}");
  private static final Pattern PIN = Pattern.compile("[1-9][0-9]{5}");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
  private static final BigDecimal MAX_ROUND_OFF = BigDecimal.TEN;
  private static final BigDecimal CENT = new BigDecimal("0.01");
  private static final int MAX_ITEMS = 1000;

  /** The document types INV-01 carries, by the UNTDID 1001 code the model holds. */
  private static final Map<String, String> DOCUMENT_TYPES =
      Map.of("380", "INV", "381", "CRN", "383", "DBN");

  /** The GST rates, in percent, the portal accepts. */
  static final Set<String> GST_RATES =
      Set.of("0", "0.1", "0.25", "1", "1.5", "3", "5", "6", "7.5", "12", "18", "28", "40");

  /** UN/ECE Recommendation 20 units, and the GST unit quantity code (UQC) each is reported as. */
  static final Map<String, String> UNITS =
      Map.ofEntries(
          Map.entry("C62", "NOS"),
          Map.entry("EA", "NOS"),
          Map.entry("NAR", "NOS"),
          Map.entry("H87", "PCS"),
          Map.entry("KGM", "KGS"),
          Map.entry("GRM", "GMS"),
          Map.entry("TNE", "TON"),
          Map.entry("LTR", "LTR"),
          Map.entry("MLT", "MLT"),
          Map.entry("MTR", "MTR"),
          Map.entry("CMT", "CMS"),
          Map.entry("KMT", "KME"),
          Map.entry("MTK", "SQM"),
          Map.entry("MTQ", "CBM"),
          Map.entry("DZN", "DOZ"),
          Map.entry("PR", "PRS"),
          Map.entry("SET", "SET"),
          Map.entry("XBX", "BOX"),
          Map.entry("XBG", "BAG"),
          Map.entry("XBO", "BTL"),
          Map.entry("XCT", "CTN"),
          Map.entry("XRO", "ROL"),
          Map.entry("XCA", "CAN"),
          Map.entry("XBE", "BDL"),
          Map.entry("XPK", "PAC"));

  private Irp() {}

  // ── the figures, as the portal recomputes them ────────────────────────────────

  /** One item as INV-01 states it. */
  record Item(
      String slNo,
      String description,
      boolean service,
      String hsn,
      String barcode,
      BigDecimal quantity,
      String unit,
      BigDecimal unitPrice,
      BigDecimal totAmt,
      BigDecimal discount,
      BigDecimal assAmt,
      BigDecimal rate,
      BigDecimal igst,
      BigDecimal cgst,
      BigDecimal sgst,
      BigDecimal total) {}

  /** The document's items and totals. */
  record Figures(
      List<Item> items,
      BigDecimal assVal,
      BigDecimal igst,
      BigDecimal cgst,
      BigDecimal sgst,
      BigDecimal totInvVal,
      BigDecimal roundOff) {}

  // ── checking ───────────────────────────────────────────────────────────────────

  /**
   * The ways the document would be refused by the portal, or be written with less than it holds.
   *
   * @param inv the invoice, credit note or debit note
   * @return the violations; none when it can be reported as it is
   */
  public static List<Violation> check(Invoice inv) {
    List<Violation> out = new ArrayList<>();
    String type = inv.typeCode() == null ? null : inv.typeCode().strip();
    if (!DOCUMENT_TYPES.containsKey(type)) {
      out.add(
          Violation.fatal(
              "IRP-DOC-01",
              "INV-01 reports an invoice (380), a credit note (381) or a debit note (383); this"
                  + " document is "
                  + type));
    }
    if (inv.number() == null || !DOCUMENT_NUMBER.matcher(inv.number()).matches()) {
      out.add(
          Violation.fatal(
              "IRP-DOC-02",
              "a document number is 1 to 16 capital letters, digits, / and -, and does not start"
                  + " with 0, / or -; this one is "
                  + inv.number()));
    }
    if (inv.issueDate() == null) {
      out.add(Violation.fatal("IRP-DOC-03", "the document has no date"));
    }
    if (!"INR".equals(inv.currency())) {
      out.add(
          Violation.fatal(
              "IRP-DOC-04",
              "a B2B supply is reported in rupees; this document is in " + inv.currency()));
    }
    for (Invoice.PrecedingInvoice p : inv.precedingInvoices()) {
      if (p.number() == null
          || !DOCUMENT_NUMBER.matcher(p.number()).matches()
          || p.issueDate() == null) {
        out.add(
            Violation.fatal(
                "IRP-DOC-05",
                "the document it refers to needs a number INV-01 can carry and its date; "
                    + p.number()
                    + " does not"));
      }
    }
    checkParty(out, "SELLER", inv.seller(), 50, true);
    checkParty(out, "BUYER", inv.buyer(), 100, false);
    if (!inv.allowanceCharges().isEmpty()) {
      out.add(
          Violation.fatal(
              "IRP-VAL-01",
              "INV-01 taxes each item, so an allowance or charge on the whole document has to be"
                  + " spread over the lines it applies to"));
    }
    if (inv.totals() == null || inv.totals().withVat() == null) {
      out.add(Violation.fatal("IRP-VAL-02", "the document has no total with tax"));
    }
    if (inv.lines().isEmpty() || inv.lines().size() > MAX_ITEMS) {
      out.add(
          Violation.fatal(
              "IRP-ITEM-01",
              "a document carries 1 to "
                  + MAX_ITEMS
                  + " items; this one has "
                  + inv.lines().size()));
    }
    for (int i = 0; i < inv.lines().size(); i++) {
      checkLine(out, i + 1, inv.lines().get(i));
    }
    if (out.stream().noneMatch(Violation::isFatal)) {
      Figures f = figures(inv);
      if (f.roundOff().abs().compareTo(MAX_ROUND_OFF) > 0) {
        out.add(
            Violation.fatal(
                "IRP-VAL-03",
                "the items' value and GST come to "
                    + f.totInvVal().subtract(f.roundOff())
                    + " and the document's total to "
                    + f.totInvVal()
                    + "; the portal allows ₹10 of rounding"));
      }
    }
    return out;
  }

  private static void checkParty(
      List<Violation> out, String role, Invoice.Party party, int maxCity, boolean pinRequired) {
    String rule = "IRP-" + role + "-";
    String who = "SELLER".equals(role) ? "the supplier" : "the recipient";
    if (party == null || !Gstin.valid(party.vatId())) {
      out.add(
          Violation.fatal(
              rule + "01",
              who
                  + " needs a valid GSTIN; only a supply between GST-registered persons (B2B) is"
                  + " reported here"));
      if (party == null) return;
    }
    if (!between(party.name(), 3, 100)) {
      out.add(Violation.fatal(rule + "02", who + "'s legal name is 3 to 100 characters"));
    }
    Invoice.Address a = party.address();
    if (a == null || !between(a.line1(), 1, 100)) {
      out.add(Violation.fatal(rule + "03", who + "'s address needs a first line of up to 100"));
    }
    if (a == null || !between(a.city(), 3, maxCity)) {
      out.add(
          Violation.fatal(
              rule + "04", who + "'s address needs a place of 3 to " + maxCity + " characters"));
    }
    String pin = a == null ? null : a.postcode();
    if ((pin != null || pinRequired) && (pin == null || !PIN.matcher(pin.strip()).matches())) {
      out.add(Violation.fatal(rule + "05", who + "'s PIN code is six digits"));
    }
  }

  private static void checkLine(List<Violation> out, int n, Invoice.Line line) {
    String hsn = hsnOf(line);
    if (hsn == null || !HSN.matcher(hsn).matches()) {
      out.add(
          Violation.fatal(
              "IRP-ITEM-02",
              "item "
                  + n
                  + " needs its HSN or SAC code: 4, 6 or 8 digits, as an item classification in"
                  + " the HS scheme"));
    }
    if (line.vatRate() == null || !GST_RATES.contains(rateText(line.vatRate()))) {
      out.add(
          Violation.fatal(
              "IRP-ITEM-03",
              "item " + n + "'s tax rate " + line.vatRate() + "% is not a rate GST charges"));
    }
    boolean service = hsn != null && hsn.startsWith("99");
    if (!service && (line.quantity() == null || line.quantity().signum() <= 0)) {
      out.add(Violation.fatal("IRP-ITEM-04", "item " + n + " is goods, and goods need a quantity"));
    }
    if (line.price() == null || line.price().net() == null || line.netAmount() == null) {
      out.add(Violation.fatal("IRP-ITEM-05", "item " + n + " has no price or no net amount"));
      return;
    }
    if (line.netAmount().signum() < 0 || line.price().net().signum() < 0) {
      out.add(
          Violation.fatal(
              "IRP-ITEM-06",
              "item " + n + " is negative; a reduction is reported as a credit note"));
    }
    if (line.quantity() != null && line.netAmount().subtract(totAmt(line)).compareTo(CENT) > 0) {
      out.add(
          Violation.fatal(
              "IRP-ITEM-07",
              "item "
                  + n
                  + " carries a charge INV-01 has no place for; include it in the unit price"));
    }
    if (!service && !UNITS.containsKey(line.unitCode())) {
      out.add(
          Violation.warning(
              "IRP-ITEM-08",
              "item "
                  + n
                  + "'s unit "
                  + line.unitCode()
                  + " has no GST unit code and is written OTH"));
    }
  }

  // ── writing ────────────────────────────────────────────────────────────────────

  /**
   * The document as the portal's Generate IRN payload.
   *
   * @throws IllegalArgumentException when {@link #check} finds a fatal violation, naming the first
   */
  public static String toJson(Invoice inv) {
    for (Violation v : check(inv)) {
      if (v.isFatal()) throw new IllegalArgumentException(v.rule() + ": " + v.message());
    }
    Figures f = figures(inv);
    Invoice.Party seller = inv.seller();
    Invoice.Party buyer = inv.buyer();
    boolean reverseCharge = inv.lines().stream().anyMatch(l -> "AE".equals(l.vatCategory()));

    Map<String, Object> doc = new LinkedHashMap<>();
    doc.put("Version", SCHEMA_VERSION);
    doc.put(
        "TranDtls", ordered("TaxSch", "GST", "SupTyp", "B2B", "RegRev", reverseCharge ? "Y" : "N"));
    doc.put(
        "DocDtls",
        ordered(
            "Typ",
            DOCUMENT_TYPES.get(inv.typeCode().strip()),
            "No",
            inv.number(),
            "Dt",
            DATE.format(inv.issueDate())));
    Map<String, Object> sellerDtls = party(seller);
    sellerDtls.put("Stcd", Gstin.stateCode(seller.vatId()));
    doc.put("SellerDtls", sellerDtls);
    Map<String, Object> buyerDtls = party(buyer);
    buyerDtls.put("Pos", placeOfSupply(inv));
    buyerDtls.put("Stcd", Gstin.stateCode(buyer.vatId()));
    doc.put("BuyerDtls", buyerDtls);

    List<Object> items = new ArrayList<>();
    for (Item it : f.items()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("SlNo", it.slNo());
      if (between(it.description(), 3, 300)) item.put("PrdDesc", it.description());
      item.put("IsServc", it.service() ? "Y" : "N");
      item.put("HsnCd", it.hsn());
      if (it.barcode() != null) item.put("Barcde", it.barcode());
      if (it.quantity() != null) item.put("Qty", decimal(it.quantity(), 3));
      if (it.unit() != null) item.put("Unit", it.unit());
      item.put("UnitPrice", decimal(it.unitPrice(), 3));
      item.put("TotAmt", money(it.totAmt()));
      item.put("Discount", money(it.discount()));
      item.put("AssAmt", money(it.assAmt()));
      item.put("GstRt", decimal(it.rate(), 3));
      item.put("IgstAmt", money(it.igst()));
      item.put("CgstAmt", money(it.cgst()));
      item.put("SgstAmt", money(it.sgst()));
      item.put("TotItemVal", money(it.total()));
      items.add(item);
    }
    doc.put("ItemList", items);
    doc.put(
        "ValDtls",
        ordered(
            "AssVal", money(f.assVal()),
            "CgstVal", money(f.cgst()),
            "SgstVal", money(f.sgst()),
            "IgstVal", money(f.igst()),
            "RndOffAmt", money(f.roundOff()),
            "TotInvVal", money(f.totInvVal())));
    if (!inv.precedingInvoices().isEmpty()) {
      List<Object> preceding = new ArrayList<>();
      for (Invoice.PrecedingInvoice p : inv.precedingInvoices()) {
        preceding.add(ordered("InvNo", p.number(), "InvDt", DATE.format(p.issueDate())));
      }
      doc.put("RefDtls", ordered("PrecDocDtls", preceding));
    }
    StringBuilder json = new StringBuilder();
    write(json, doc);
    return json.toString();
  }

  static Figures figures(Invoice inv) {
    boolean intraState =
        inv.seller() != null
            && Gstin.stateCode(inv.seller().vatId()) != null
            && Gstin.stateCode(inv.seller().vatId()).equals(placeOfSupply(inv));
    List<Item> items = new ArrayList<>();
    BigDecimal assVal = BigDecimal.ZERO;
    BigDecimal igst = BigDecimal.ZERO;
    BigDecimal cgst = BigDecimal.ZERO;
    BigDecimal sgst = BigDecimal.ZERO;
    for (int i = 0; i < inv.lines().size(); i++) {
      Invoice.Line line = inv.lines().get(i);
      String hsn = hsnOf(line);
      boolean service = hsn != null && hsn.startsWith("99");
      BigDecimal rate = line.vatRate() == null ? BigDecimal.ZERO : line.vatRate();
      BigDecimal ass = money(line.netAmount());
      BigDecimal tot = money(totAmt(line));
      BigDecimal discount = tot.subtract(ass).max(BigDecimal.ZERO);
      BigDecimal lineIgst = BigDecimal.ZERO;
      BigDecimal lineCgst = BigDecimal.ZERO;
      BigDecimal lineSgst = BigDecimal.ZERO;
      if (intraState) {
        lineCgst = money(ass.multiply(rate).divide(BigDecimal.valueOf(200)));
        lineSgst = lineCgst;
      } else {
        lineIgst = money(ass.multiply(rate).divide(BigDecimal.valueOf(100)));
      }
      Invoice.Item item = line.item();
      String barcode =
          item != null
                  && item.standardId() != null
                  && "0160".equals(item.standardId().scheme())
                  && between(item.standardId().id(), 3, 30)
              ? item.standardId().id()
              : null;
      items.add(
          new Item(
              Integer.toString(i + 1),
              item == null ? null : item.name(),
              service,
              hsn,
              barcode,
              line.quantity(),
              service ? null : UNITS.getOrDefault(line.unitCode(), "OTH"),
              unitPrice(line),
              ass.add(discount),
              discount,
              ass,
              rate,
              lineIgst,
              lineCgst,
              lineSgst,
              ass.add(lineIgst).add(lineCgst).add(lineSgst)));
      assVal = assVal.add(ass);
      igst = igst.add(lineIgst);
      cgst = cgst.add(lineCgst);
      sgst = sgst.add(lineSgst);
    }
    BigDecimal computed = assVal.add(igst).add(cgst).add(sgst);
    Invoice.Totals t = inv.totals();
    BigDecimal total =
        t == null || t.withVat() == null
            ? computed
            : money(t.withVat().add(t.rounding() == null ? BigDecimal.ZERO : t.rounding()));
    return new Figures(items, assVal, igst, cgst, sgst, total, total.subtract(computed));
  }

  /**
   * Where the supply is made: a GST state code given as the delivery address's subdivision, or the
   * recipient's own state.
   */
  static String placeOfSupply(Invoice inv) {
    Invoice.Delivery d = inv.delivery();
    if (d != null && d.address() != null && Gstin.isStateCode(d.address().subdivision())) {
      return d.address().subdivision();
    }
    return inv.buyer() == null ? null : Gstin.stateCode(inv.buyer().vatId());
  }

  private static Map<String, Object> party(Invoice.Party p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("Gstin", Gstin.normalise(p.vatId()));
    m.put("LglNm", p.name());
    if (between(p.tradingName(), 3, 100)) m.put("TrdNm", p.tradingName());
    Invoice.Address a = p.address();
    m.put("Addr1", a.line1());
    if (between(a.line2(), 3, 100)) m.put("Addr2", a.line2());
    m.put("Loc", a.city());
    if (a.postcode() != null) m.put("Pin", Long.parseLong(a.postcode().strip()));
    Invoice.Contact c = p.contact();
    if (c != null && c.phone() != null) {
      String digits = NON_DIGITS.matcher(c.phone()).replaceAll("");
      if (digits.length() >= 6 && digits.length() <= 12) m.put("Ph", digits);
    }
    if (c != null && between(c.email(), 6, 100) && c.email().contains("@")) m.put("Em", c.email());
    return m;
  }

  static String hsnOf(Invoice.Line line) {
    if (line.item() == null) return null;
    return line.item().classifications().stream()
        .filter(c -> HS_SCHEME.equals(c.scheme()) && c.code() != null)
        .map(c -> c.code().strip())
        .findFirst()
        .orElse(null);
  }

  /** The unit price before any discount: the gross price when there is one, per one unit. */
  private static BigDecimal unitPrice(Invoice.Line line) {
    Invoice.Price p = line.price();
    BigDecimal price = p.gross() != null ? p.gross() : p.net();
    BigDecimal base =
        p.baseQuantity() == null || p.baseQuantity().signum() <= 0
            ? BigDecimal.ONE
            : p.baseQuantity();
    return price.divide(base, 6, RoundingMode.HALF_UP);
  }

  /** Quantity times the undiscounted unit price, or the net amount for a service without one. */
  private static BigDecimal totAmt(Invoice.Line line) {
    if (line.quantity() == null) return line.netAmount();
    return money(line.quantity().multiply(unitPrice(line)));
  }

  private static Map<String, Object> ordered(Object... keysAndValues) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      m.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return m;
  }

  private static boolean between(String s, int min, int max) {
    return s != null && s.strip().length() >= min && s.strip().length() <= max;
  }

  private static BigDecimal money(BigDecimal x) {
    return x.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal decimal(BigDecimal x, int scale) {
    BigDecimal d = x.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
    return d.scale() < 0 ? d.setScale(0) : d;
  }

  private static String rateText(BigDecimal rate) {
    return decimal(rate, 3).toPlainString();
  }

  // ── JSON, in the order the schema lists the fields ────────────────────────────

  private static void write(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String s) {
      string(out, s);
    } else if (value instanceof BigDecimal d) {
      out.append(d.toPlainString());
    } else if (value instanceof Long || value instanceof Integer) {
      out.append(value);
    } else if (value instanceof Map<?, ?> m) {
      out.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!first) out.append(',');
        first = false;
        string(out, (String) e.getKey());
        out.append(':');
        write(out, e.getValue());
      }
      out.append('}');
    } else if (value instanceof List<?> l) {
      out.append('[');
      for (int i = 0; i < l.size(); i++) {
        if (i > 0) out.append(',');
        write(out, l.get(i));
      }
      out.append(']');
    } else {
      throw new IllegalStateException("no JSON form for " + value.getClass());
    }
  }

  private static void string(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || c == ' ' || c == ' ') {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
