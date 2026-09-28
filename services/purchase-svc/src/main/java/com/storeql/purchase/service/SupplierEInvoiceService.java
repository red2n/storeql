package com.storeql.purchase.service;

import static com.storeql.purchase.domain.EInvoiceIntake.CODE_BUYER;
import static com.storeql.purchase.domain.EInvoiceIntake.CODE_SELLER;
import static com.storeql.purchase.domain.EInvoiceIntake.CODE_STANDARD;
import static com.storeql.purchase.domain.EInvoiceIntake.MATCHED_BY_PERSON;

import com.storeql.einvoice.EInvoiceFormatException;
import com.storeql.einvoice.EInvoices;
import com.storeql.einvoice.ElectronicAddress;
import com.storeql.einvoice.Invoice;
import com.storeql.einvoice.Violation;
import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.PurchaseOrderLine;
import com.storeql.purchase.domain.EInvoiceIntake;
import com.storeql.purchase.domain.EInvoiceIntake.Found;
import com.storeql.purchase.domain.EInvoiceIntake.LineMatch;
import com.storeql.purchase.domain.EInvoiceIntake.OrderLine;
import com.storeql.purchase.domain.EInvoiceIntake.ReturnRef;
import com.storeql.purchase.domain.EInvoiceIntake.SupplierRef;
import com.storeql.purchase.domain.SupplierEInvoices;
import com.storeql.purchase.domain.SupplierEInvoices.Document;
import com.storeql.purchase.domain.SupplierEInvoices.Line;
import com.storeql.purchase.domain.SupplierEInvoices.Original;
import com.storeql.purchase.dto.Dtos.CaptureSupplierInvoiceLine;
import com.storeql.purchase.dto.Dtos.CaptureSupplierInvoiceRequest;
import com.storeql.purchase.dto.Dtos.RecordCreditNoteRequest;
import com.storeql.purchase.dto.EInvoiceDtos.LineChoiceRequest;
import com.storeql.purchase.dto.EInvoiceDtos.MatchSupplierEInvoiceRequest;
import com.storeql.purchase.dto.EInvoiceDtos.RefuseSupplierEInvoiceRequest;
import com.storeql.purchase.repo.PurchaseRepository;
import com.storeql.purchase.repo.SupplierEInvoiceRepository;
import com.storeql.service.TenantProfiles;
import com.storeql.web.ApiException;
import com.storeql.web.Permissions;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Supplier e-invoices received (07.13): read, checked, matched and captured, or kept waiting for a
 * person with the reason.
 *
 * <p>A document is received once. Its bytes are kept as they arrived and its hash makes a second
 * sending of the same file the first one, not a second invoice. A document that breaks a fatal rule
 * of EN 16931 or Peppol is kept and not captured: the supplier is told, not the ledger. One that is
 * addressed to another business is kept and not captured. Everything else is matched to a supplier,
 * a purchase order and its lines by {@link EInvoiceIntake}, and captured through {@link
 * PurchaseService#captureSupplierInvoice} — the same three-way match, posting and payment gate a
 * keyed invoice gets — or, for a credit note, closes the return it credits.
 *
 * <p>Two people matching one document at once make one invoice: the document is claimed for the
 * duration, and the second is told it is busy.
 */
@ApplicationScoped
public class SupplierEInvoiceService {

  private static final Logger LOG = System.getLogger(SupplierEInvoiceService.class.getName());

  /** What an e-invoice arrives as. */
  static final Set<String> MEDIA_TYPES =
      Set.of("application/xml", "text/xml", "application/pdf", "application/octet-stream");

  /** Orders nothing has been ordered on yet, or never will be. */
  private static final Set<String> NOT_BILLABLE = Set.of("DRAFT", "PENDING_APPROVAL", "CANCELLED");

  /** A capture or credit that was refused because it had been done already. */
  private static final Set<String> ALREADY_DONE =
      Set.of("PURCHASE_INVOICE_DUPLICATE", "PURCHASE_RTV_ALREADY_CREDITED");

  private static final Set<String> STATUSES =
      Set.of(
          EInvoiceIntake.STATUS_NOT_COMPLIANT,
          EInvoiceIntake.STATUS_MISDIRECTED,
          EInvoiceIntake.STATUS_NEEDS_SUPPLIER,
          EInvoiceIntake.STATUS_NEEDS_ORDER,
          EInvoiceIntake.STATUS_NEEDS_LINES,
          EInvoiceIntake.STATUS_NEEDS_RETURN,
          EInvoiceIntake.STATUS_NEEDS_DECISION,
          EInvoiceIntake.STATUS_DUPLICATE,
          EInvoiceIntake.STATUS_CAPTURED,
          EInvoiceIntake.STATUS_CREDITED,
          EInvoiceIntake.STATUS_REFUSED);

  private static final int SHOWN_RULES = 5;

  @Inject SupplierEInvoiceRepository repo;
  @Inject com.storeql.service.Entitlements entitlements;
  @Inject PurchaseRepository purchases;
  @Inject PurchaseService purchasing;
  @Inject TenantProfiles tenants;

  /** A document as it now stands, and whether these bytes had been received before. */
  public record Receipt(Document document, List<Line> lines, boolean alreadyReceived) {}

  /** Where the document waits and why, or everything its capture needs when status is null. */
  private record Decision(
      String status,
      String problem,
      UUID supplierId,
      UUID poId,
      UUID returnId,
      List<LineMatch> matches,
      Map<UUID, PurchaseOrderLine> orderLines) {

    boolean ready() {
      return status == null;
    }

    static Decision waiting(String status, String problem, UUID supplierId, UUID poId) {
      return new Decision(status, problem, supplierId, poId, null, List.of(), Map.of());
    }
  }

  /** What a person chose; null or empty where they chose nothing. */
  private record Choices(UUID supplierId, UUID poId, UUID returnId, Map<Integer, UUID> lines) {

    static final Choices NONE = new Choices(null, null, null, Map.of());
  }

  /**
   * Receives a document.
   *
   * @throws ApiException {@code 400 PURCHASE_EINVOICE_EMPTY}; {@code 415
   *     PURCHASE_EINVOICE_MEDIA_TYPE}; {@code 400 PURCHASE_EINVOICE_<code>} when it cannot be read
   *     as an e-invoice (NOT_XML, DTD_REFUSED, NOT_AN_INVOICE, BAD_VALUE, NOT_A_PDF, PDF_ENCRYPTED,
   *     NO_EMBEDDED_INVOICE …), {@code 413 PURCHASE_EINVOICE_TOO_LARGE}
   */
  public Receipt receive(TenantContext ctx, byte[] body, String contentType) {
    return receive(ctx, body, contentType, SupplierEInvoices.CHANNEL_UPLOAD, null, null);
  }

  /**
   * Receives a document over a channel: a person's upload, or a network's delivery once {@link
   * EInvoiceDeliveryService} has found the business it names.
   *
   * @param channel {@code UPLOAD}, or the network that delivered it
   * @param deliveryRef the network's own reference for the delivery, or null
   * @param already the document as the caller has read it, or null to read it here
   * @throws ApiException as {@link #receive(TenantContext, byte[], String)}
   */
  public Receipt receive(
      TenantContext ctx,
      byte[] body,
      String contentType,
      String channel,
      String deliveryRef,
      EInvoices.Received already) {
    UUID tenantId = ctx.requireTenantId();
    String type = mediaType(contentType);
    requireDocument(body);
    String sha = sha256(body);
    Optional<UUID> seen = repo.findIdBySha(tenantId, sha);
    if (seen.isPresent()) return receipt(tenantId, seen.get(), true);
    // The plan's cap on what the business keeps in documents (21.11): asked after the dedupe, since
    // a document already held costs nothing more, and before the document is read, since one there
    // is no room for is not worth parsing. A plan with no cap holds nothing back.
    entitlements.requireBytesWithin(
        tenantId,
        com.storeql.service.Entitlements.DOCUMENTS_MB_MAX,
        "MB of supplier e-invoice documents",
        () -> repo.documentBytes(tenantId) + body.length);

    EInvoices.Received received = already != null ? already : read(body);
    Invoice inv = received.invoice();
    List<Violation> violations = EInvoices.validate(received);
    Decision d = decide(ctx, inv, violations, Choices.NONE);
    UUID id = Ids.newId();
    Instant now = Instant.now();
    Invoice.Totals totals = inv.totals();
    Document doc =
        new Document(
            id,
            tenantId,
            now,
            ctx.userId(),
            channel,
            deliveryRef,
            type,
            received.container().name(),
            received.syntax().name(),
            received.embeddedFilename(),
            sha,
            inv.customizationId(),
            inv.typeCode(),
            inv.number(),
            inv.issueDate(),
            inv.currency(),
            inv.seller() == null ? null : inv.seller().name(),
            inv.seller() == null ? null : inv.seller().vatId(),
            endpoint(inv.seller()),
            inv.buyer() == null ? null : inv.buyer().vatId(),
            endpoint(inv.buyer()),
            inv.orderReference(),
            inv.precedingInvoices().isEmpty() ? null : inv.precedingInvoices().get(0).number(),
            totals == null ? null : totals.withoutVat(),
            totals == null ? null : totals.vat(),
            totals == null ? null : totals.withVat(),
            totals == null ? null : totals.payable(),
            violationsJson(violations),
            d.ready() ? EInvoiceIntake.STATUS_NEEDS_DECISION : d.status(),
            d.ready() ? "being captured" : d.problem(),
            d.supplierId(),
            d.poId(),
            null,
            null,
            null,
            null,
            null,
            now);
    if (!repo.insert(doc, body, lines(tenantId, id, inv, d.matches()))) {
      UUID first =
          repo.findIdBySha(tenantId, sha)
              .orElseThrow(
                  () ->
                      ApiException.conflict(
                          "PURCHASE_EINVOICE_BUSY",
                          "the same document is being received; try again"));
      return receipt(tenantId, first, true);
    }
    if (d.ready()) complete(ctx, id, inv, d);
    return receipt(tenantId, id, false);
  }

  /**
   * Matches a waiting document with what a person chose, and captures it when that is enough.
   *
   * @throws ApiException {@code 404 PURCHASE_EINVOICE_NOT_FOUND}; {@code 409
   *     PURCHASE_EINVOICE_SETTLED}, {@code PURCHASE_EINVOICE_NOT_COMPLIANT}, {@code
   *     PURCHASE_EINVOICE_MISDIRECTED} or {@code PURCHASE_EINVOICE_BUSY}; {@code 400} for a choice
   *     that does not fit the document
   */
  public Receipt match(TenantContext ctx, UUID id, MatchSupplierEInvoiceRequest req) {
    UUID tenantId = ctx.requireTenantId();
    Document doc = document(tenantId, id);
    requireOpen(doc);
    if (EInvoiceIntake.STATUS_NOT_COMPLIANT.equals(doc.status())) {
      throw ApiException.conflict(
          "PURCHASE_EINVOICE_NOT_COMPLIANT",
          "an e-invoice that breaks EN 16931 is not captured; refuse it and ask the supplier for a"
              + " corrected one");
    }
    if (EInvoiceIntake.STATUS_MISDIRECTED.equals(doc.status())) {
      throw ApiException.conflict(
          "PURCHASE_EINVOICE_MISDIRECTED",
          "this e-invoice is addressed to another business; refuse it");
    }
    Original original =
        repo.original(tenantId, id)
            .orElseThrow(
                () -> ApiException.notFound("PURCHASE_EINVOICE_NOT_FOUND", "no such e-invoice"));
    Invoice inv = read(original.bytes()).invoice();
    Map<Integer, UUID> lines = new HashMap<>();
    if (req.lines() != null) {
      for (LineChoiceRequest l : req.lines()) {
        if (lines.put(l.position(), l.poLineId()) != null) {
          throw ApiException.badRequest(
              "PURCHASE_EINVOICE_LINE_CHOSEN_TWICE", "line " + l.position() + " is chosen twice");
        }
      }
    }
    Decision d =
        decide(
            ctx, inv, List.of(), new Choices(req.supplierId(), req.poId(), req.returnId(), lines));
    if (req.remember()) remember(ctx, id, inv, d);
    if (d.ready()) {
      complete(ctx, id, inv, d);
    } else {
      UUID token = claim(tenantId, id);
      repo.settle(
          tenantId,
          id,
          token,
          d.status(),
          d.problem(),
          d.supplierId(),
          d.poId(),
          null,
          null,
          d.matches(),
          null,
          null,
          null);
    }
    return receipt(tenantId, id, false);
  }

  /**
   * Refuses a waiting document, with the reason the supplier is to be given.
   *
   * @throws ApiException {@code 403} without purchasing.invoices.decide; {@code 404}; {@code 409
   *     PURCHASE_EINVOICE_SETTLED} or {@code PURCHASE_EINVOICE_BUSY}
   */
  public Receipt refuse(TenantContext ctx, UUID id, RefuseSupplierEInvoiceRequest req) {
    ctx.requirePermission(Permissions.PURCHASING_INVOICES_DECIDE);
    UUID tenantId = ctx.requireTenantId();
    Document doc = document(tenantId, id);
    requireOpen(doc);
    UUID token = claim(tenantId, id);
    repo.settle(
        tenantId,
        id,
        token,
        EInvoiceIntake.STATUS_REFUSED,
        null,
        doc.supplierId(),
        doc.poId(),
        null,
        null,
        null,
        Instant.now(),
        ctx.userId(),
        req.reason().strip());
    return receipt(tenantId, id, false);
  }

  /** Received documents, newest first. */
  public List<Document> list(TenantContext ctx, String status, int limit) {
    String wanted = null;
    if (status != null && !status.isBlank()) {
      wanted = status.strip().toUpperCase(Locale.ROOT);
      if (!STATUSES.contains(wanted)) {
        throw ApiException.badRequest(
            "PURCHASE_EINVOICE_STATUS_INVALID",
            "status must be one of " + String.join(", ", STATUSES.stream().sorted().toList()));
      }
    }
    return repo.list(ctx.requireTenantId(), wanted, limit);
  }

  public Receipt get(TenantContext ctx, UUID id) {
    return receipt(ctx.requireTenantId(), id, false);
  }

  /** The document exactly as it arrived. */
  public Original original(TenantContext ctx, UUID id) {
    return repo.original(ctx.requireTenantId(), id)
        .orElseThrow(
            () -> ApiException.notFound("PURCHASE_EINVOICE_NOT_FOUND", "no such e-invoice"));
  }

  // ── deciding ─────────────────────────────────────────────────────────────────

  private Decision decide(TenantContext ctx, Invoice inv, List<Violation> violations, Choices ch) {
    UUID tenantId = ctx.requireTenantId();
    List<String> fatal =
        violations.stream().filter(Violation::isFatal).map(Violation::rule).distinct().toList();
    if (!fatal.isEmpty()) {
      return Decision.waiting(
          EInvoiceIntake.STATUS_NOT_COMPLIANT,
          "breaks "
              + fatal.size()
              + (fatal.size() == 1 ? " rule" : " rules")
              + " of EN 16931 or Peppol: "
              + String.join(", ", fatal.subList(0, Math.min(SHOWN_RULES, fatal.size())))
              + (fatal.size() > SHOWN_RULES ? " …" : ""),
          null,
          null);
    }
    Optional<TenantProfiles.Identity> identity = tenants.identity(tenantId);
    if (identity.isPresent()) {
      TenantProfiles.Identity own = identity.get();
      String elsewhere =
          EInvoiceIntake.misdirected(
              inv,
              own.vatNumber(),
              own.hasElectronicAddress()
                  ? new ElectronicAddress(own.einvoiceScheme(), own.einvoiceId())
                  : null);
      if (elsewhere != null)
        return Decision.waiting(EInvoiceIntake.STATUS_MISDIRECTED, elsewhere, null, null);
    }

    SupplierRef supplier;
    if (ch.supplierId() != null) {
      Domain.Supplier s =
          purchases
              .findSupplier(tenantId, ch.supplierId())
              .orElseThrow(
                  () -> ApiException.notFound("PURCHASE_SUPPLIER_NOT_FOUND", "Supplier not found"));
      supplier =
          new SupplierRef(s.id(), s.name(), s.vatNumber(), s.einvoiceScheme(), s.einvoiceId());
    } else {
      Found<SupplierRef> found = EInvoiceIntake.supplier(inv, repo.supplierRefs(tenantId));
      if (found.value() == null) {
        return Decision.waiting(EInvoiceIntake.STATUS_NEEDS_SUPPLIER, found.problem(), null, null);
      }
      supplier = found.value();
    }

    UUID poId = ch.poId() != null ? ch.poId() : EInvoiceIntake.orderReference(inv).orElse(null);
    if (poId == null && inv.isCreditNote() && !inv.precedingInvoices().isEmpty()) {
      String number = inv.precedingInvoices().get(0).number();
      if (number != null) poId = repo.orderOfInvoice(tenantId, supplier.id(), number).orElse(null);
    }
    if (poId == null) {
      return Decision.waiting(
          EInvoiceIntake.STATUS_NEEDS_ORDER,
          "it names no purchase order of " + supplier.name() + "'s; choose the order it bills",
          supplier.id(),
          null);
    }
    Domain.PurchaseOrder po = purchases.findPurchaseOrder(tenantId, poId).orElse(null);
    if (po == null || !po.supplierId().equals(supplier.id())) {
      if (ch.poId() != null) {
        throw ApiException.badRequest(
            "PURCHASE_EINVOICE_ORDER_NOT_SUPPLIERS",
            "that order is not one of " + supplier.name() + "'s");
      }
      return Decision.waiting(
          EInvoiceIntake.STATUS_NEEDS_ORDER,
          "the order it names is not one of " + supplier.name() + "'s; choose the order it bills",
          supplier.id(),
          null);
    }
    if (NOT_BILLABLE.contains(po.status())) {
      return Decision.waiting(
          EInvoiceIntake.STATUS_NEEDS_ORDER,
          "the order it names is " + po.status() + ", so nothing on it can be billed yet",
          supplier.id(),
          po.id());
    }
    if (inv.currency() != null && !inv.currency().equalsIgnoreCase(po.currency())) {
      return Decision.waiting(
          EInvoiceIntake.STATUS_NEEDS_ORDER,
          "it is in " + inv.currency() + " and the order it names is in " + po.currency(),
          supplier.id(),
          po.id());
    }

    if (inv.isCreditNote()) {
      List<ReturnRef> returns =
          purchases.findVendorReturns(tenantId, po.id()).stream()
              .map(r -> new ReturnRef(r.id(), r.status(), r.grossAmount()))
              .toList();
      if (ch.returnId() != null) {
        ReturnRef chosen =
            returns.stream()
                .filter(r -> r.id().equals(ch.returnId()) && "RAISED".equals(r.status()))
                .findFirst()
                .orElseThrow(
                    () ->
                        ApiException.badRequest(
                            "PURCHASE_EINVOICE_RETURN_NOT_OPEN",
                            "that return is not one on this order waiting for a credit note"));
        return new Decision(null, null, supplier.id(), po.id(), chosen.id(), List.of(), Map.of());
      }
      Found<ReturnRef> found = EInvoiceIntake.creditedReturn(inv, returns);
      if (found.value() == null) {
        return Decision.waiting(
            EInvoiceIntake.STATUS_NEEDS_RETURN, found.problem(), supplier.id(), po.id());
      }
      return new Decision(
          null, null, supplier.id(), po.id(), found.value().id(), List.of(), Map.of());
    }

    List<PurchaseOrderLine> ordered =
        new ArrayList<>(purchases.findPurchaseOrderLines(tenantId, po.id()));
    ordered.sort(
        Comparator.comparing(PurchaseOrderLine::createdAt).thenComparing(PurchaseOrderLine::id));
    List<OrderLine> orderLines = new ArrayList<>();
    Map<UUID, PurchaseOrderLine> byId = new HashMap<>();
    for (int i = 0; i < ordered.size(); i++) {
      PurchaseOrderLine l = ordered.get(i);
      orderLines.add(new OrderLine(l.id(), i + 1, l.variantId()));
      byId.put(l.id(), l);
    }
    List<LineMatch> matches =
        new ArrayList<>(
            EInvoiceIntake.lines(inv, orderLines, repo.itemCodes(tenantId, supplier.id())));
    for (Map.Entry<Integer, UUID> choice : ch.lines().entrySet()) {
      int position = choice.getKey();
      if (position < 1 || position > matches.size()) {
        throw ApiException.badRequest(
            "PURCHASE_EINVOICE_LINE_UNKNOWN", "the e-invoice has no line " + position);
      }
      PurchaseOrderLine chosen = byId.get(choice.getValue());
      if (chosen == null) {
        throw ApiException.badRequest(
            "PURCHASE_EINVOICE_LINE_NOT_ON_ORDER",
            "the order line chosen for line " + position + " is not on that order");
      }
      matches.set(
          position - 1,
          new LineMatch(position, chosen.id(), chosen.variantId(), MATCHED_BY_PERSON));
    }
    List<String> unmatched = new ArrayList<>();
    for (LineMatch m : matches) {
      if (!m.matched()) {
        Invoice.Line line = inv.lines().get(m.position() - 1);
        String name =
            line.item() == null || line.item().name() == null
                ? "line " + m.position()
                : line.item().name();
        unmatched.add(name);
      }
    }
    if (!unmatched.isEmpty()) {
      return new Decision(
          EInvoiceIntake.STATUS_NEEDS_LINES,
          unmatched.size()
              + " of "
              + matches.size()
              + (matches.size() == 1 ? " line is" : " lines are")
              + " not yet a line of the order: "
              + String.join(", ", unmatched.subList(0, Math.min(SHOWN_RULES, unmatched.size())))
              + (unmatched.size() > SHOWN_RULES ? " …" : ""),
          supplier.id(),
          po.id(),
          null,
          matches,
          byId);
    }
    return new Decision(null, null, supplier.id(), po.id(), null, matches, byId);
  }

  /** Captures or credits a decided document under a claim, and records what came of it. */
  private void complete(TenantContext ctx, UUID id, Invoice inv, Decision d) {
    UUID tenantId = ctx.requireTenantId();
    UUID token = claim(tenantId, id);
    String status;
    String problem = null;
    UUID invoiceId = null;
    UUID returnId = null;
    try {
      if (inv.isCreditNote()) {
        BigDecimal credited = inv.totals() == null ? null : inv.totals().withVat();
        purchasing.recordCreditNote(
            ctx,
            d.returnId(),
            new RecordCreditNoteRequest(inv.number(), inv.issueDate().toString(), credited));
        status = EInvoiceIntake.STATUS_CREDITED;
        returnId = d.returnId();
      } else {
        invoiceId = purchasing.captureSupplierInvoice(ctx, capture(inv, d)).id();
        status = EInvoiceIntake.STATUS_CAPTURED;
      }
    } catch (ApiException e) {
      status =
          ALREADY_DONE.contains(e.code())
              ? EInvoiceIntake.STATUS_DUPLICATE
              : EInvoiceIntake.STATUS_NEEDS_DECISION;
      problem = e.getMessage();
    } catch (RuntimeException e) {
      repo.release(tenantId, id, token);
      throw e;
    }
    boolean settled =
        repo.settle(
            tenantId,
            id,
            token,
            status,
            problem,
            d.supplierId(),
            d.poId(),
            invoiceId,
            returnId,
            d.matches(),
            null,
            null,
            null);
    if (!settled) {
      LOG.log(
          Level.WARNING, "the claim on supplier e-invoice {0} lapsed before it was settled", id);
    }
  }

  private static CaptureSupplierInvoiceRequest capture(Invoice inv, Decision d) {
    List<CaptureSupplierInvoiceLine> lines = new ArrayList<>();
    for (LineMatch m : d.matches()) {
      Invoice.Line line = inv.lines().get(m.position() - 1);
      PurchaseOrderLine ordered = d.orderLines().get(m.poLineId());
      lines.add(
          new CaptureSupplierInvoiceLine(
              m.variantId(),
              line.quantity(),
              EInvoiceIntake.unitPrice(line),
              ordered == null ? null : ordered.vatCode()));
    }
    Invoice.Totals t = inv.totals();
    return new CaptureSupplierInvoiceRequest(
        d.poId(),
        inv.number(),
        inv.issueDate().toString(),
        inv.currency(),
        t == null ? null : t.vat(),
        t == null ? null : t.withVat(),
        lines);
  }

  /**
   * Keeps what a person decided for the supplier: the electronic address it sent from, when the
   * supplier has none and no other supplier holds it, and what its item codes are.
   */
  private void remember(TenantContext ctx, UUID einvoiceId, Invoice inv, Decision d) {
    UUID tenantId = ctx.requireTenantId();
    if (d.supplierId() == null) return;
    ElectronicAddress from =
        inv.seller() == null ? null : ElectronicAddress.of(inv.seller().electronicAddress());
    Domain.Supplier s = purchases.findSupplier(tenantId, d.supplierId()).orElse(null);
    if (from != null && s != null && s.einvoiceId() == null) {
      try {
        purchases.updateSupplier(s.withEinvoiceAddress(from.scheme(), from.id(), Instant.now()));
      } catch (ApiException e) {
        if (!"PURCHASE_SUPPLIER_EINVOICE_ADDRESS_TAKEN".equals(e.code())) throw e;
      }
    }
    for (LineMatch m : d.matches()) {
      if (!MATCHED_BY_PERSON.equals(m.matchedBy())) continue;
      Invoice.Item item = inv.lines().get(m.position() - 1).item();
      if (item == null) continue;
      String kind;
      String code;
      if (item.sellersId() != null && !item.sellersId().isBlank()) {
        kind = CODE_SELLER;
        code = item.sellersId().strip();
      } else if (EInvoiceIntake.standardCode(item) != null) {
        kind = CODE_STANDARD;
        code = EInvoiceIntake.standardCode(item);
      } else if (item.buyersId() != null && !item.buyersId().isBlank()) {
        kind = CODE_BUYER;
        code = item.buyersId().strip();
      } else {
        continue;
      }
      repo.learn(
          Ids.newId(),
          tenantId,
          d.supplierId(),
          kind,
          code,
          m.variantId(),
          einvoiceId,
          ctx.userId());
    }
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private UUID claim(UUID tenantId, UUID id) {
    UUID token = Ids.newId();
    if (!repo.claim(tenantId, id, token, EInvoiceIntake.OPEN)) {
      throw ApiException.conflict(
          "PURCHASE_EINVOICE_BUSY",
          "this e-invoice is being matched by another request, or is already settled; reload it");
    }
    return token;
  }

  private Receipt receipt(UUID tenantId, UUID id, boolean alreadyReceived) {
    return new Receipt(document(tenantId, id), repo.lines(tenantId, id), alreadyReceived);
  }

  private Document document(UUID tenantId, UUID id) {
    return repo.find(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("PURCHASE_EINVOICE_NOT_FOUND", "no such e-invoice"));
  }

  private static void requireOpen(Document doc) {
    if (!EInvoiceIntake.OPEN.contains(doc.status())) {
      throw ApiException.conflict(
          "PURCHASE_EINVOICE_SETTLED",
          "this e-invoice is already " + doc.status() + " and cannot change");
    }
  }

  /**
   * @throws ApiException {@code 400 PURCHASE_EINVOICE_EMPTY} for no document at all
   */
  static void requireDocument(byte[] body) {
    if (body == null || body.length == 0) {
      throw ApiException.badRequest("PURCHASE_EINVOICE_EMPTY", "the request carries no document");
    }
  }

  /**
   * Reads a document as an e-invoice, refusing what cannot be read as the intake does.
   *
   * @throws ApiException {@code 400 PURCHASE_EINVOICE_<code>}, or {@code 413} when too large
   */
  static EInvoices.Received read(byte[] body) {
    try {
      return EInvoices.read(body);
    } catch (EInvoiceFormatException e) {
      int status = "TOO_LARGE".equals(e.code()) ? 413 : 400;
      throw new ApiException(status, "PURCHASE_EINVOICE_" + e.code(), e.getMessage(), List.of(), e);
    }
  }

  /**
   * @throws ApiException {@code 415 PURCHASE_EINVOICE_MEDIA_TYPE}
   */
  static String mediaType(String contentType) {
    String type =
        contentType == null ? "" : contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    if (!MEDIA_TYPES.contains(type)) {
      throw new ApiException(
          415,
          "PURCHASE_EINVOICE_MEDIA_TYPE",
          "an e-invoice is sent as application/xml, text/xml or application/pdf",
          List.of());
    }
    return type;
  }

  private static List<Line> lines(
      UUID tenantId, UUID einvoiceId, Invoice inv, List<LineMatch> matches) {
    List<Line> out = new ArrayList<>();
    for (int i = 0; i < inv.lines().size(); i++) {
      Invoice.Line l = inv.lines().get(i);
      LineMatch m = i < matches.size() ? matches.get(i) : null;
      Invoice.Item item = l.item();
      out.add(
          new Line(
              Ids.newId(),
              tenantId,
              einvoiceId,
              i + 1,
              l.id(),
              item == null ? null : item.name(),
              item == null ? null : item.sellersId(),
              item == null ? null : item.buyersId(),
              EInvoiceIntake.standardCode(item),
              l.orderLineReference(),
              l.quantity(),
              l.unitCode(),
              l.netAmount(),
              l.price() == null ? null : l.price().net(),
              l.vatCategory(),
              l.vatRate(),
              m == null ? null : m.poLineId(),
              m == null ? null : m.variantId(),
              m == null ? null : m.matchedBy()));
    }
    return out;
  }

  private static String endpoint(Invoice.Party party) {
    ElectronicAddress a = party == null ? null : ElectronicAddress.of(party.electronicAddress());
    return a == null ? null : a.toString();
  }

  private static String violationsJson(List<Violation> violations) {
    JsonArrayBuilder array = Json.createArrayBuilder();
    for (Violation v : violations) {
      array.add(
          Json.createObjectBuilder()
              .add("rule", v.rule())
              .add("severity", v.severity().name())
              .add("message", v.message()));
    }
    return array.build().toString();
  }

  static String sha256(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java platform", e);
    }
  }
}
