package com.storeql.order.service;

import com.storeql.einvoice.EInvoices;
import com.storeql.einvoice.Invoice;
import com.storeql.einvoice.Irp;
import com.storeql.einvoice.Rules;
import com.storeql.einvoice.Violation;
import com.storeql.ids.Ids;
import com.storeql.order.client.ServiceReads;
import com.storeql.order.config.Json;
import com.storeql.order.domain.Domain.Order;
import com.storeql.order.domain.Domain.OrderItem;
import com.storeql.order.domain.Domain.ReturnItem;
import com.storeql.order.domain.SalesInvoiceDraft;
import com.storeql.order.domain.SalesInvoiceDraft.Address;
import com.storeql.order.domain.SalesInvoiceDraft.Buyer;
import com.storeql.order.domain.SalesInvoiceDraft.Document;
import com.storeql.order.domain.SalesInvoiceDraft.Line;
import com.storeql.order.domain.SalesInvoiceDraft.Seller;
import com.storeql.order.domain.SalesInvoices;
import com.storeql.order.domain.SalesInvoices.Pending;
import com.storeql.order.domain.SalesInvoices.SalesInvoice;
import com.storeql.order.domain.SalesInvoices.Written;
import com.storeql.order.repo.OrderRepository;
import com.storeql.order.repo.SalesInvoiceRepository;
import com.storeql.service.ServiceReader.Reply;
import com.storeql.web.ApiException;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Invoices and credit notes to business buyers (18.9).
 *
 * <p>A completed sale to a customer recorded as VAT-registered is invoiced: the business as its
 * tenant identity and store name it, the buyer as its VAT status and billing address name it, and
 * every line at the rate its quote applied. The document is checked against EN 16931 (and Peppol
 * BIS Billing 3.0 when both parties are on the network) before its number is kept; an Indian
 * business's document is also written as the IRP's INV-01, or kept with what the portal would
 * refuse. A return against an invoiced sale is credited.
 */
@ApplicationScoped
public class SalesInvoiceService {

  private static final System.Logger LOG = System.getLogger(SalesInvoiceService.class.getName());

  /** Sales that happened, and so can be invoiced. */
  static final Set<String> SOLD =
      Set.of(
          Order.STATUS_CONFIRMED,
          Order.STATUS_FULFILLED,
          Order.STATUS_PARTIALLY_FULFILLED,
          Order.STATUS_PARTIALLY_REFUNDED,
          Order.STATUS_REFUNDED);

  static final String TERMS = "Payable on receipt, unless paid at the time of sale";

  @Inject OrderRepository orders;
  @Inject SalesInvoiceRepository invoices;
  @Inject com.storeql.service.TenantProfiles profiles;
  @Inject ServiceReads reads;
  @Inject EInvoiceTransportService transport;

  // Documents issued in the background of a sale: a till is never kept waiting on four services.
  // Bounded: a slow dependency makes sales wait in at most this many places, never without limit. A
  // document that finds the queue full is logged and left for the manager to issue by hand (the
  // sale stands either way).
  private final ExecutorService background =
      new ThreadPoolExecutor(
          2,
          2,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(
              Math.max(
                  1,
                  ConfigProvider.getConfig()
                      .getOptionalValue("storeql.order.invoices.background-queue", Integer.class)
                      .orElse(1000))),
          r -> {
            Thread t = new Thread(r, "sales-invoices");
            t.setDaemon(true);
            return t;
          },
          (r, pool) ->
              LOG.log(
                  System.Logger.Level.WARNING,
                  "background invoice queue is full; the document is left to be issued by hand"));

  @PreDestroy
  void stop() {
    background.shutdown();
    try {
      if (!background.awaitTermination(10, TimeUnit.SECONDS)) background.shutdownNow();
    } catch (InterruptedException e) {
      background.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** The business and its buyer, and the business's country. */
  record Parties(Seller seller, Buyer buyer, String country) {}

  /** What product-svc says an item is. */
  record ItemInfo(String name, String sku, String unit, String hsnCode) {}

  /** A document to save, as bytes. */
  public record Download(String contentType, String fileName, byte[] bytes) {}

  // ── issuing ───────────────────────────────────────────────────────────────────

  /**
   * The invoice for a sale: issued now, or the one already issued.
   *
   * @throws ApiException 404 no such sale; 409 not a completed sale, no customer, the customer not
   *     VAT-registered or without a billing address, the business without a VAT number or legal
   *     name, or a document that would break EN 16931; 503 a service it needs could not be reached
   */
  public SalesInvoice issueInvoice(UUID tenantId, UUID orderId, UUID userId) {
    Order order =
        orders
            .findOrder(tenantId, orderId)
            .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "order not found"));
    Optional<SalesInvoice> existing = invoices.findInvoice(tenantId, orderId);
    if (existing.isPresent()) return existing.get();
    if (!SOLD.contains(order.status())) {
      throw ApiException.conflict(
          "ORDER_INVOICE_NOT_SOLD",
          "only a completed sale is invoiced; this order is " + order.status());
    }
    if (order.customerId() == null) {
      throw ApiException.conflict(
          "ORDER_INVOICE_NO_BUYER",
          "the sale names no customer; an invoice is addressed to a business buyer on the order");
    }
    List<OrderItem> items = orders.findOrderItems(tenantId, orderId);
    Parties parties = parties(tenantId, order.storeId(), order.customerId());
    Map<UUID, ItemInfo> info = items(tenantId, items.stream().map(OrderItem::variantId).toList());
    String exempt = exemptionOf(order);
    List<Line> lines = new ArrayList<>();
    for (OrderItem i : items) {
      lines.add(line(i.variantId(), i.qty(), i.lineTotal(), rateOf(i), exempt, info));
    }
    // What came off the lines, stated net: a till takes its discount off what the customer pays,
    // VAT included, and the invoice states the VAT on what was actually received.
    BigDecimal discount = SalesInvoiceDraft.netDiscount(lines, order.total());
    LocalDate day = LocalDate.now(ZoneOffset.UTC);
    boolean india = "IN".equals(parties.country());
    Pending pending =
        new Pending(
            tenantId,
            order.storeId(),
            orderId,
            null,
            SalesInvoiceDraft.TYPE_INVOICE,
            SalesInvoices.SERIES_INVOICE,
            String.valueOf(day.getYear()),
            day,
            userId,
            order.customerId(),
            parties.buyer().name(),
            parties.buyer().vatId(),
            order.currency(),
            null);
    SalesInvoice issued =
        invoices.issue(
            pending,
            number ->
                write(
                    new Document(
                        SalesInvoiceDraft.TYPE_INVOICE,
                        number,
                        day,
                        order.currency(),
                        orderId.toString(),
                        null,
                        null,
                        TERMS,
                        discount,
                        order.total(),
                        india),
                    parties,
                    lines,
                    india));
    transport.enqueueQuietly(issued);
    return issued;
  }

  /**
   * The credit note for a return against an invoiced sale: issued now, or the one already issued.
   *
   * @throws ApiException 404 no such return; 409 the sale was never invoiced, or as for an invoice
   */
  public SalesInvoice issueCreditNote(UUID tenantId, UUID returnId, UUID userId) {
    SalesInvoiceRepository.ReturnRef ret =
        invoices
            .findReturn(tenantId, returnId)
            .orElseThrow(() -> ApiException.notFound("ORDER_RETURN_NOT_FOUND", "return not found"));
    Optional<SalesInvoice> existing = invoices.findCreditNote(tenantId, returnId);
    if (existing.isPresent()) return existing.get();
    SalesInvoice invoice =
        invoices
            .findInvoice(tenantId, ret.orderId())
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "ORDER_CREDIT_NOTE_NO_INVOICE",
                        "the sale was never invoiced, so there is no invoice to credit"));
    Order order = orders.findOrder(tenantId, ret.orderId()).orElseThrow();
    Map<UUID, OrderItem> byVariant = new LinkedHashMap<>();
    for (OrderItem i : orders.findOrderItems(tenantId, order.id())) {
      byVariant.putIfAbsent(i.variantId(), i);
    }
    List<ReturnItem> returned = orders.findReturnItems(tenantId, returnId);
    Parties parties = parties(tenantId, order.storeId(), order.customerId());
    Map<UUID, ItemInfo> info =
        items(tenantId, returned.stream().map(ReturnItem::variantId).toList());
    String exempt = exemptionOf(order);
    List<Line> lines = new ArrayList<>();
    for (ReturnItem r : returned) {
      OrderItem sold = byVariant.get(r.variantId());
      lines.add(
          line(
              r.variantId(),
              r.qty(),
              r.refundAmount(),
              sold == null ? BigDecimal.ZERO : rateOf(sold),
              exempt,
              info));
    }
    LocalDate day = LocalDate.now(ZoneOffset.UTC);
    boolean india = "IN".equals(parties.country());
    Pending pending =
        new Pending(
            tenantId,
            order.storeId(),
            order.id(),
            returnId,
            SalesInvoiceDraft.TYPE_CREDIT_NOTE,
            SalesInvoices.SERIES_CREDIT_NOTE,
            String.valueOf(day.getYear()),
            day,
            userId,
            order.customerId(),
            parties.buyer().name(),
            parties.buyer().vatId(),
            order.currency(),
            invoice.id());
    SalesInvoice issued =
        invoices.issue(
            pending,
            number ->
                write(
                    new Document(
                        SalesInvoiceDraft.TYPE_CREDIT_NOTE,
                        number,
                        day,
                        order.currency(),
                        order.id().toString(),
                        invoice.fullNumber(),
                        invoice.issueDate(),
                        null,
                        BigDecimal.ZERO,
                        null,
                        india),
                    parties,
                    lines,
                    india));
    transport.enqueueQuietly(issued);
    return issued;
  }

  /**
   * Invoices a completed sale in the background, when its customer turns out to be a business.
   * Anything that stops it is logged, never thrown: the sale stands, and the invoice can be issued
   * by hand.
   */
  public void issueInvoiceLater(Order order, UUID userId) {
    if (order.customerId() == null) return;
    background.submit(
        () ->
            quietly(
                "invoice for order " + order.id(),
                () -> issueInvoice(order.tenantId(), order.id(), userId)));
  }

  /** Credits a return in the background, when its sale was invoiced. */
  public void issueCreditNoteLater(UUID tenantId, UUID orderId, UUID returnId, UUID userId) {
    background.submit(
        () -> {
          if (invoices.findInvoice(tenantId, orderId).isEmpty()) return;
          quietly(
              "credit note for return " + returnId,
              () -> issueCreditNote(tenantId, returnId, userId));
        });
  }

  private static void quietly(String what, Runnable work) {
    try {
      work.run();
    } catch (ApiException e) {
      // A refusal is an answer: most customers are not VAT-registered businesses.
      LOG.log(
          e.status() >= 500 ? Level.WARNING : Level.DEBUG,
          () -> what + " not issued: " + e.getMessage());
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, () -> what + " not issued", e);
    }
  }

  // ── reading ───────────────────────────────────────────────────────────────────

  public List<SalesInvoice> list(UUID tenantId, UUID orderId, UUID after, int limit) {
    return invoices.list(tenantId, orderId, after, limit);
  }

  public SalesInvoice get(UUID tenantId, UUID id) {
    return invoices
        .find(tenantId, id)
        .orElseThrow(() -> ApiException.notFound("ORDER_INVOICE_NOT_FOUND", "invoice not found"));
  }

  /**
   * A document to download.
   *
   * @param format UBL as issued; CII or FACTURX written from it; IRP for an Indian business
   */
  public Download document(UUID tenantId, UUID id, String format) {
    SalesInvoice s = get(tenantId, id);
    String f =
        format == null || format.isBlank()
            ? SalesInvoices.FORMAT_UBL
            : format.strip().toUpperCase(Locale.ROOT);
    String base = s.fullNumber().replace('/', '-');
    return switch (f) {
      case SalesInvoices.FORMAT_UBL ->
          new Download(
              "application/xml", base + ".xml", s.document().getBytes(StandardCharsets.UTF_8));
      case SalesInvoices.FORMAT_CII ->
          new Download(
              "application/xml",
              base + "-cii.xml",
              EInvoices.toCii(model(s)).getBytes(StandardCharsets.UTF_8));
      case SalesInvoices.FORMAT_FACTURX ->
          new Download("application/pdf", base + ".pdf", EInvoices.toFacturX(model(s)));
      case SalesInvoices.FORMAT_IRP -> {
        if (s.irpPayload() != null) {
          yield new Download(
              "application/json",
              base + "-irp.json",
              s.irpPayload().getBytes(StandardCharsets.UTF_8));
        }
        if (s.irpProblems() != null) {
          throw new ApiException(
              409,
              "ORDER_INVOICE_IRP_NOT_READY",
              "India's Invoice Registration Portal would refuse this document",
              SalesInvoices.problems(s.irpProblems()));
        }
        throw ApiException.notFound(
            "ORDER_INVOICE_IRP_NOT_APPLICABLE",
            "only an Indian business's documents go to the IRP");
      }
      default ->
          throw ApiException.badRequest(
              "ORDER_INVOICE_FORMAT_UNKNOWN", "format is UBL, CII, FACTURX or IRP");
    };
  }

  // ── building ──────────────────────────────────────────────────────────────────

  private static Written write(Document d, Parties parties, List<Line> lines, boolean india) {
    // EN 16931 BR-25: every line carries its item name (BT-153). A name is never made up for a line
    // product-svc no longer names; the sale is refused until the catalogue names it.
    if (!india) {
      for (int n = 0; n < lines.size(); n++) {
        if (blank(lines.get(n).name())) {
          String why =
              "BR-25: Each Invoice line (BG-25) shall contain the Item name (BT-153), line "
                  + (n + 1);
          throw new ApiException(
              409,
              "ORDER_INVOICE_NOT_COMPLIANT",
              "the document would break 1 EN 16931 rule(s): " + why,
              List.of(why));
        }
      }
    }
    Invoice inv = SalesInvoiceDraft.build(d, parties.seller(), parties.buyer(), lines);
    // The lines at their rates, less the discount, must come to what the sale charged: a sale whose
    // tax does not follow from its lines is not made to look as if it did.
    if (d.payable() != null && inv.totals().payable().compareTo(money(d.payable())) != 0) {
      throw ApiException.conflict(
          "ORDER_INVOICE_TOTALS_DIFFER",
          "the document comes to "
              + inv.totals().payable()
              + " but the sale charged "
              + money(d.payable())
              + "; the sale's tax does not follow from its lines' rates, so it cannot be invoiced");
    }
    String irpPayload = null;
    String irpProblems = null;
    if (india) {
      // An Indian business's parties are named by GSTINs, which carry no country prefix, so the
      // portal's checks are the ones that apply.
      List<Violation> irp = Irp.check(inv);
      if (irp.stream().anyMatch(Violation::isFatal)) {
        irpProblems = SalesInvoices.problemsJson(irp);
      } else {
        irpPayload = Irp.toJson(inv);
      }
    } else {
      List<String> fatal =
          Rules.check(inv).stream()
              .filter(Violation::isFatal)
              .map(v -> v.rule() + ": " + v.message())
              .toList();
      if (!fatal.isEmpty()) {
        throw new ApiException(
            409,
            "ORDER_INVOICE_NOT_COMPLIANT",
            "the document would break " + fatal.size() + " EN 16931 rule(s): " + fatal.get(0),
            fatal);
      }
    }
    Invoice.Totals t = inv.totals();
    return new Written(
        inv.customizationId(),
        t.withoutVat(),
        t.vat(),
        t.payable(),
        EInvoices.toUbl(inv),
        irpPayload,
        irpProblems);
  }

  Parties parties(UUID tenantId, UUID storeId, UUID customerId) {
    // The buyer's registration first: most customers are not businesses, and that answer needs
    // nothing else read.
    JsonObject vat =
        data(
            reads.get("pricing-svc", tenantId, "/customer-vat-status/" + customerId, Map.of()),
            "pricing-svc",
            "ORDER_INVOICE_BUYER_NOT_REGISTERED",
            "the customer's VAT registration");
    if (!vat.getBoolean("vatRegistered", false) || blank(str(vat, "vatNumber"))) {
      throw ApiException.conflict(
          "ORDER_INVOICE_BUYER_NOT_REGISTERED",
          "the customer is not recorded as VAT-registered with a VAT number");
    }
    var identity =
        profiles
            .identity(tenantId)
            .orElseThrow(() -> unavailable("tenant-svc", "the business's identity"));
    if (blank(identity.vatNumber())) {
      throw ApiException.conflict(
          "ORDER_INVOICE_SELLER_VAT_MISSING",
          "the business has no VAT number, and an invoice must carry it; set it first");
    }
    if (blank(identity.legalName())) {
      throw ApiException.conflict(
          "ORDER_INVOICE_SELLER_NAME_MISSING",
          "the business has no legal name, and an invoice must carry it; set it first");
    }
    String country = profiles.requireCountry(tenantId);
    JsonObject store =
        data(
            reads.get("tenant-svc", tenantId, "/admin/stores/" + storeId, Map.of()),
            "tenant-svc",
            "ORDER_INVOICE_STORE_NOT_FOUND",
            "the store the sale was made in");
    Seller seller =
        new Seller(
            identity.legalName(),
            null,
            identity.vatNumber(),
            identity.einvoiceScheme(),
            identity.einvoiceId(),
            new Address(
                str(store, "line1"),
                str(store, "line2"),
                str(store, "city"),
                str(store, "pincode"),
                str(store, "state"),
                orElse(str(store, "country"), country)));

    JsonObject customer =
        data(
            reads.get("customer-svc", tenantId, "/customers/" + customerId, Map.of()),
            "customer-svc",
            "ORDER_INVOICE_BUYER_NOT_FOUND",
            "the customer");
    JsonObject billing =
        billingAddress(
                reads.get(
                    "customer-svc", tenantId, "/customers/" + customerId + "/addresses", Map.of()))
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "ORDER_INVOICE_BUYER_ADDRESS_MISSING",
                        "the customer has no address to put on the invoice"));
    String name =
        orElse(
            str(vat, "legalName"),
            (orElse(str(customer, "firstName"), "") + " " + orElse(str(customer, "lastName"), ""))
                .strip());
    Buyer buyer =
        new Buyer(
            name,
            str(vat, "vatNumber"),
            str(vat, "einvoiceScheme"),
            str(vat, "einvoiceId"),
            new Address(
                str(billing, "line1"),
                str(billing, "line2"),
                str(billing, "city"),
                str(billing, "pincode"),
                str(billing, "state"),
                orElse(str(billing, "country"), str(vat, "countryCode"))),
            vat.getBoolean("reverseChargeEligible", false));
    return new Parties(seller, buyer, country);
  }

  Map<UUID, ItemInfo> items(UUID tenantId, List<UUID> variantIds) {
    Map<UUID, ItemInfo> out = new HashMap<>();
    if (variantIds.isEmpty()) return out;
    Reply r =
        reads.get(
            "product-svc",
            tenantId,
            "/admin/products/variants/resolve",
            Map.of(
                "ids",
                variantIds.stream()
                    .distinct()
                    .map(UUID::toString)
                    .collect(Collectors.joining(","))));
    if (r.unreachable()) throw unavailable("product-svc", "the items' names");
    if (!r.ok()) return out;
    try (JsonReader reader = Json.createReader(new StringReader(r.body()))) {
      JsonArray data = reader.readObject().getJsonArray("data");
      for (JsonValue v : data == null ? List.<JsonValue>of() : data) {
        JsonObject o = v.asJsonObject();
        if (blank(str(o, "variantId"))) continue;
        out.put(
            Ids.parse(str(o, "variantId")),
            new ItemInfo(str(o, "productName"), str(o, "sku"), str(o, "unit"), str(o, "hsnCode")));
      }
    }
    return out;
  }

  private static Line line(
      UUID variantId,
      BigDecimal qty,
      BigDecimal net,
      BigDecimal rate,
      String exempt,
      Map<UUID, ItemInfo> info) {
    ItemInfo i = info.get(variantId);
    return new Line(
        i == null ? null : i.name(),
        i == null ? null : i.sku(),
        i == null ? null : i.hsnCode(),
        qty,
        i == null ? null : i.unit(),
        net,
        exempt == null ? rate : BigDecimal.ZERO,
        exempt);
  }

  /** The rate a line was taxed at, in percent: as the quote recorded it, else worked back. */
  static BigDecimal rateOf(OrderItem i) {
    if (i.vatRate() != null) {
      BigDecimal r = i.vatRate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros();
      return r.scale() < 0 ? r.setScale(0) : r;
    }
    if (i.vatAmount() == null || i.lineTotal() == null || i.lineTotal().signum() == 0) {
      return BigDecimal.ZERO;
    }
    return i.vatAmount()
        .multiply(BigDecimal.valueOf(100))
        .divide(i.lineTotal(), 2, RoundingMode.HALF_UP);
  }

  private static String exemptionOf(Order order) {
    if (!order.taxExempt()) return null;
    return blank(order.exemptReason()) ? "Exempt from VAT" : order.exemptReason();
  }

  private static Optional<JsonObject> billingAddress(Reply r) {
    if (r.unreachable()) throw unavailable("customer-svc", "the customer's addresses");
    if (!r.ok()) return Optional.empty();
    try (JsonReader reader = Json.createReader(new StringReader(r.body()))) {
      JsonArray all = reader.readObject().getJsonArray("data");
      JsonObject best = null;
      int bestScore = -1;
      for (JsonValue v : all == null ? List.<JsonValue>of() : all) {
        JsonObject a = v.asJsonObject();
        int score =
            ("BILLING".equals(str(a, "type")) ? 2 : 0) + (a.getBoolean("isDefault", false) ? 1 : 0);
        if (score > bestScore) {
          best = a;
          bestScore = score;
        }
      }
      return Optional.ofNullable(best);
    }
  }

  private static JsonObject data(Reply r, String service, String missingCode, String what) {
    if (r.unreachable()) throw unavailable(service, what);
    if (!r.ok()) throw ApiException.conflict(missingCode, "no record of " + what + " was found");
    try (JsonReader reader = Json.createReader(new StringReader(r.body()))) {
      return reader.readObject().getJsonObject("data");
    }
  }

  private static ApiException unavailable(String service, String what) {
    return new ApiException(
        503,
        "ORDER_INVOICE_DEPENDENCY_UNAVAILABLE",
        service + " could not be reached for " + what + "; the document can be issued once it is",
        List.of());
  }

  private static Invoice model(SalesInvoice s) {
    return EInvoices.read(s.document().getBytes(StandardCharsets.UTF_8)).invoice();
  }

  private static String str(JsonObject o, String key) {
    return o != null
            && o.containsKey(key)
            && !o.isNull(key)
            && o.get(key).getValueType() == JsonValue.ValueType.STRING
        ? o.getString(key)
        : null;
  }

  private static BigDecimal money(BigDecimal x) {
    return x.setScale(2, RoundingMode.HALF_UP);
  }

  private static String orElse(String s, String fallback) {
    return blank(s) ? fallback : s;
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
