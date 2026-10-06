package com.storeql.purchase.service;

import static com.storeql.purchase.domain.PaymentRuns.APPROVED;
import static com.storeql.purchase.domain.PaymentRuns.CANCELLED;
import static com.storeql.purchase.domain.PaymentRuns.PAID;
import static com.storeql.purchase.domain.PaymentRuns.PROPOSED;

import com.storeql.ids.Ids;
import com.storeql.purchase.domain.Domain;
import com.storeql.purchase.domain.Domain.NominalLedgerEntry;
import com.storeql.purchase.domain.Domain.Supplier;
import com.storeql.purchase.domain.LedgerPosting;
import com.storeql.purchase.domain.Money;
import com.storeql.purchase.domain.PaymentProposal;
import com.storeql.purchase.domain.PaymentProposal.Document;
import com.storeql.purchase.domain.PaymentProposal.Payee;
import com.storeql.purchase.domain.PaymentRuns.Item;
import com.storeql.purchase.domain.PaymentRuns.PayeeCheck;
import com.storeql.purchase.domain.PaymentRuns.PaymentRun;
import com.storeql.purchase.domain.PaymentRuns.View;
import com.storeql.purchase.dto.Dtos.CancelPaymentRunRequest;
import com.storeql.purchase.dto.Dtos.ProposePaymentRunRequest;
import com.storeql.purchase.repo.BankFileRepository;
import com.storeql.purchase.repo.PaymentRunRepository;
import com.storeql.service.OutboxRow;
import com.storeql.web.ApiException;
import com.storeql.web.Parsing;
import com.storeql.web.Permissions;
import com.storeql.web.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Supplier payment runs and remittance (17.10): propose from what is due, approve with a second
 * pair of eyes, pay with a posting that clears the creditor against the bank, advise each supplier.
 *
 * <p>Every step needs a management role and {@code finance.payments}. The proposer cannot approve
 * their own run unless they are the owner — a one-person business still has to pay its suppliers —
 * and a run whose suppliers' bank details changed after it was approved is refused at payment and
 * at the bank file, because a changed account between approval and payment is exactly how a payment
 * is diverted.
 */
@ApplicationScoped
public class PaymentRunService {

  /** The most invoices, and separately credit notes, one proposal reads. */
  static final int MAX_DOCUMENTS = 1000;

  private static final Set<String> STATUSES = Set.of(PROPOSED, APPROVED, PAID, CANCELLED);
  private static final DateTimeFormatter REF_DATE = DateTimeFormatter.ofPattern("yyMMdd");

  @Inject PaymentRunRepository runs;
  @Inject PurchaseService purchases;
  @Inject BankFileRepository files;

  /**
   * Proposes a run: every payable invoice due by {@code payUpTo} in the currency, less each
   * supplier's unallocated credit notes, for every supplier that can be paid.
   *
   * @throws ApiException 400 {@code PURCHASE_PAYMENT_DATE_INVALID}; 409 {@code
   *     PURCHASE_PAYMENT_RUN_NOTHING_DUE} naming each supplier left out and why; 409 {@code
   *     PURCHASE_PAYMENT_RUN_CONFLICT} when a concurrent proposal took a document first
   */
  public View propose(TenantContext ctx, ProposePaymentRunRequest req) {
    UUID tenantId = requireFinance(ctx);
    LocalDate payUpTo = Parsing.date(req.payUpTo(), "payUpTo");
    LocalDate paymentDate = Parsing.date(req.paymentDate(), "paymentDate");
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    if (paymentDate.isBefore(today)) {
      throw ApiException.badRequest(
          "PURCHASE_PAYMENT_DATE_INVALID", "paymentDate cannot be before today");
    }
    if (paymentDate.isAfter(today.plusYears(1)) || payUpTo.isAfter(today.plusYears(1))) {
      throw ApiException.badRequest(
          "PURCHASE_PAYMENT_DATE_INVALID", "a payment run looks at most a year ahead");
    }
    String currency =
        req.currency() == null || req.currency().isBlank()
            ? purchases.resolveTenantCurrency(tenantId)
            : Money.requireIso4217(req.currency());

    List<Document> docs =
        new ArrayList<>(runs.findInvoicesDue(tenantId, currency, payUpTo, MAX_DOCUMENTS));
    docs.addAll(runs.findUnallocatedCredits(tenantId, currency, MAX_DOCUMENTS));
    Map<UUID, Supplier> suppliers =
        supplierMap(tenantId, docs.stream().map(Document::supplierId).collect(Collectors.toSet()));
    Instant now = Instant.now();
    PaymentProposal.Result result = PaymentProposal.build(docs, payees(suppliers, false), now);
    if (result.payments().isEmpty()) {
      throw new ApiException(
          409,
          "PURCHASE_PAYMENT_RUN_NOTHING_DUE",
          "nothing payable is due by " + payUpTo + " in " + currency,
          result.excluded().stream().map(e -> e.name() + ": " + e.reason()).toList());
    }

    UUID runId = Ids.newId();
    String hex = runId.toString().replace("-", "");
    String reference =
        "PAY"
            + today.format(REF_DATE)
            + "-"
            + hex.substring(hex.length() - 6).toUpperCase(Locale.ROOT);
    PaymentRun run =
        new PaymentRun(
            runId,
            tenantId,
            reference,
            PROPOSED,
            payUpTo,
            paymentDate,
            currency,
            result.total(),
            ctx.userId(),
            now,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            now);
    List<Item> items = new ArrayList<>();
    for (PaymentProposal.SupplierPayment payment : result.payments()) {
      for (Document d : payment.documents()) {
        items.add(
            new Item(
                Ids.newId(),
                tenantId,
                runId,
                d.supplierId(),
                d.storeId(),
                d.type(),
                d.documentId(),
                d.reference(),
                d.documentDate(),
                d.dueDate(),
                d.amount()));
      }
    }
    runs.insertRun(run, items);
    return new View(run, result, suppliers);
  }

  /** Runs newest first, optionally in one status, each with its documents. */
  public List<View> list(TenantContext ctx, String status, int limit) {
    UUID tenantId = requireFinance(ctx);
    String wanted = null;
    if (status != null && !status.isBlank()) {
      wanted = status.trim().toUpperCase(Locale.ROOT);
      if (!STATUSES.contains(wanted)) {
        throw ApiException.badRequest(
            "PURCHASE_PAYMENT_RUN_STATUS_UNKNOWN",
            "status must be PROPOSED, APPROVED, PAID or CANCELLED");
      }
    }
    List<PaymentRun> found = runs.findRuns(tenantId, wanted, limit);
    List<Item> items = runs.findItems(tenantId, found.stream().map(PaymentRun::id).toList());
    Map<UUID, Supplier> suppliers = supplierMap(tenantId, supplierIds(items));
    Map<UUID, List<Item>> byRun = items.stream().collect(Collectors.groupingBy(Item::runId));
    Map<UUID, Map<UUID, PayeeCheck>> checks =
        files.findChecks(tenantId, found.stream().map(PaymentRun::id).toList());
    return found.stream()
        .map(
            r ->
                view(
                    r,
                    byRun.getOrDefault(r.id(), List.of()),
                    suppliers,
                    checks.getOrDefault(r.id(), Map.of())))
        .toList();
  }

  public View get(TenantContext ctx, UUID id) {
    UUID tenantId = requireFinance(ctx);
    return viewOf(tenantId, requireRun(tenantId, id));
  }

  /**
   * Approves a proposed run.
   *
   * @throws ApiException 403 {@code PURCHASE_PAYMENT_RUN_SELF_APPROVAL}; 409 when it is not
   *     PROPOSED, or {@code PURCHASE_PAYMENT_RUN_STALE} when a supplier in it can no longer be paid
   */
  public View approve(TenantContext ctx, UUID id) {
    UUID tenantId = requireFinance(ctx);
    PaymentRun run = requireRun(tenantId, id);
    requireStatus(run, PROPOSED);
    boolean owner = ctx.hasRole("OWNER") || ctx.hasRole("PLATFORM_ADMIN");
    if (!owner && run.proposedBy() != null && run.proposedBy().equals(ctx.userId())) {
      throw ApiException.forbidden(
          "PURCHASE_PAYMENT_RUN_SELF_APPROVAL",
          "a payment run is approved by someone other than the person who proposed it");
    }
    requireAllPayable(viewOf(tenantId, run));
    if (!runs.approve(tenantId, id, ctx.userId())) {
      throw stateConflict(requireRun(tenantId, id));
    }
    return viewOf(tenantId, requireRun(tenantId, id));
  }

  /**
   * Pays an approved run: settles its invoices and credit notes, posts Dr Creditors / Cr Bank per
   * supplier and store on the payment date, and queues a remittance advice per supplier. Once only:
   * concurrent calls produce one payment and 409s.
   *
   * @throws ApiException 409 {@code PURCHASE_PAYMENT_RUN_NOT_APPROVED}, {@code
   *     PURCHASE_PAYMENT_RUN_ALREADY_PAID}, {@code PURCHASE_PAYMENT_RUN_CANCELLED}, {@code
   *     PURCHASE_PAYMENT_RUN_STALE}, {@code PURCHASE_PAYMENT_RUN_BANK_DETAILS_CHANGED} or {@code
   *     PURCHASE_PERIOD_CLOSED}
   */
  public View pay(TenantContext ctx, UUID id) {
    UUID tenantId = requireFinance(ctx);
    PaymentRun run = requireRun(tenantId, id);
    requireStatus(run, APPROVED);
    List<Item> items = runs.findItems(tenantId, List.of(id));
    Map<UUID, Supplier> suppliers = supplierMap(tenantId, supplierIds(items));
    requireBankDetailsUnchangedSinceApproval(tenantId, run, suppliers);
    View view = view(run, items, suppliers, checksOf(tenantId, id));
    requireAllPayable(view);
    requireNothingHeld(view);

    List<NominalLedgerEntry> posting = new ArrayList<>();
    List<OutboxRow> events = new ArrayList<>();
    // One period check per store for the run (one payment date), not one per supplier and store.
    java.util.Set<UUID> periodChecked = new java.util.HashSet<>();
    for (PaymentProposal.SupplierPayment payment : view.proposal().payments()) {
      Supplier supplier = suppliers.get(payment.supplierId());
      Map<UUID, BigDecimal> byStore = new LinkedHashMap<>();
      for (Document d : payment.documents()) {
        BigDecimal signed =
            PaymentProposal.INVOICE.equals(d.type()) ? d.amount() : d.amount().negate();
        byStore.merge(d.storeId(), signed, BigDecimal::add);
      }
      for (var store : byStore.entrySet()) {
        if (periodChecked.add(store.getKey())) {
          purchases.requireOpenPeriod(tenantId, store.getKey(), run.paymentDate());
        }
        posting.addAll(paymentPosting(run, supplier, store.getKey(), store.getValue()));
      }
      List<Item> advised =
          items.stream().filter(i -> i.supplierId().equals(payment.supplierId())).toList();
      events.add(Events.supplierRemittanceIssued(tenantId, run, supplier, advised, payment.net()));
    }
    int invoices =
        (int) items.stream().filter(i -> PaymentProposal.INVOICE.equals(i.itemType())).count();
    if (!runs.pay(tenantId, id, ctx.userId(), invoices, items.size() - invoices, posting, events)) {
      throw stateConflict(requireRun(tenantId, id));
    }
    return viewOf(tenantId, requireRun(tenantId, id));
  }

  /**
   * Cancels a run that is not yet paid, releasing its documents for the next run.
   *
   * @throws ApiException 409 {@code PURCHASE_PAYMENT_RUN_ALREADY_PAID} or {@code
   *     PURCHASE_PAYMENT_RUN_CANCELLED}
   */
  public View cancel(TenantContext ctx, UUID id, CancelPaymentRunRequest req) {
    UUID tenantId = requireFinance(ctx);
    PaymentRun run = requireRun(tenantId, id);
    if (PAID.equals(run.status()) || CANCELLED.equals(run.status())) {
      throw stateConflict(run);
    }
    if (!runs.cancel(tenantId, id, ctx.userId(), req.reason().trim())) {
      throw stateConflict(requireRun(tenantId, id));
    }
    return viewOf(tenantId, requireRun(tenantId, id));
  }

  /**
   * An approved or paid run as its bank file is written from, with the checks 17.10 makes before
   * any file: refused when a supplier's bank details changed after approval or a supplier can no
   * longer be paid.
   *
   * @throws ApiException 409 {@code PURCHASE_PAYMENT_RUN_NOT_APPROVED}, {@code
   *     PURCHASE_PAYMENT_RUN_CANCELLED}, {@code PURCHASE_PAYMENT_RUN_BANK_DETAILS_CHANGED} or
   *     {@code PURCHASE_PAYMENT_RUN_STALE}
   */
  public View viewForBankFile(TenantContext ctx, UUID id) {
    UUID tenantId = requireFinance(ctx);
    PaymentRun run = requireRun(tenantId, id);
    if (!APPROVED.equals(run.status()) && !PAID.equals(run.status())) {
      throw stateConflict(run);
    }
    List<Item> items = runs.findItems(tenantId, List.of(id));
    Map<UUID, Supplier> suppliers = supplierMap(tenantId, supplierIds(items));
    requireBankDetailsUnchangedSinceApproval(tenantId, run, suppliers);
    View view = view(run, items, suppliers, checksOf(tenantId, id));
    requireAllPayable(view);
    return view;
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  static UUID requireFinance(TenantContext ctx) {
    ctx.requireAnyRole("PLATFORM_ADMIN", "OWNER", "MANAGER");
    ctx.requirePermission(Permissions.FINANCE_PAYMENTS);
    return ctx.requireTenantId();
  }

  private PaymentRun requireRun(UUID tenantId, UUID id) {
    return runs.findRun(tenantId, id)
        .orElseThrow(
            () -> ApiException.notFound("PURCHASE_PAYMENT_RUN_NOT_FOUND", "payment run not found"));
  }

  private View viewOf(UUID tenantId, PaymentRun run) {
    List<Item> items = runs.findItems(tenantId, List.of(run.id()));
    return view(
        run, items, supplierMap(tenantId, supplierIds(items)), checksOf(tenantId, run.id()));
  }

  private Map<UUID, PayeeCheck> checksOf(UUID tenantId, UUID runId) {
    return files.findChecks(tenantId, List.of(runId)).getOrDefault(runId, Map.of());
  }

  /**
   * A payment the bank rejected, or a payee it could not match or matched only closely, stops the
   * run until a manager releases a checked close match, or the run is cancelled (17.12).
   */
  private static void requireNothingHeld(View view) {
    List<String> held =
        view.checks().values().stream()
            .filter(PayeeCheck::blocking)
            .map(c -> supplierName(view, c.supplierId()) + ": " + heldReason(c))
            .sorted()
            .toList();
    if (!held.isEmpty()) {
      throw new ApiException(
          409,
          "PURCHASE_PAYMENT_RUN_PAYEE_HELD",
          "the bank held a payment in this run; release a checked close match, or cancel the run"
              + " and propose again",
          held);
    }
  }

  private static String supplierName(View view, UUID supplierId) {
    Supplier s = view.suppliers().get(supplierId);
    return s == null ? supplierId.toString() : s.name();
  }

  /** Why the bank held a payment, in words a manager can act on. */
  static String heldReason(PayeeCheck c) {
    if (com.storeql.purchase.domain.Pain002.REJECTED.equals(c.status())) {
      return "REJECTED" + (c.reasonCode() == null ? "" : " " + c.reasonCode());
    }
    if (com.storeql.purchase.domain.Pain002.CLOSE_MATCH.equals(c.payeeMatch())) {
      return "CLOSE_MATCH"
          + (c.matchedName() == null ? "" : " (the bank holds \"" + c.matchedName() + "\")");
    }
    return "NO_MATCH";
  }

  /**
   * Regroups a run's documents by supplier. An open run is judged against the suppliers as they
   * stand, so one that can no longer be paid shows as excluded; a paid or cancelled run is shown as
   * it was.
   */
  private static View view(
      PaymentRun run,
      List<Item> items,
      Map<UUID, Supplier> suppliers,
      Map<UUID, PayeeCheck> checks) {
    boolean open = PROPOSED.equals(run.status()) || APPROVED.equals(run.status());
    List<Document> docs =
        items.stream()
            .map(
                i ->
                    new Document(
                        i.itemType(),
                        i.documentId(),
                        i.supplierId(),
                        i.storeId(),
                        i.reference(),
                        i.documentDate(),
                        i.dueDate(),
                        i.amount()))
            .toList();
    Map<UUID, Supplier> mine = new HashMap<>();
    for (Item i : items) {
      Supplier s = suppliers.get(i.supplierId());
      if (s != null) mine.put(s.id(), s);
    }
    return new View(
        run, PaymentProposal.build(docs, payees(mine, !open), Instant.now()), mine, checks);
  }

  private static Map<UUID, Payee> payees(Map<UUID, Supplier> suppliers, boolean asPaid) {
    Map<UUID, Payee> out = new HashMap<>();
    for (Supplier s : suppliers.values()) {
      out.put(
          s.id(),
          asPaid
              ? new Payee(s.id(), s.name(), true, null)
              : new Payee(s.id(), s.name(), s.hasBankDetails(), s.bankDetailsChangedAt()));
    }
    return out;
  }

  private Map<UUID, Supplier> supplierMap(UUID tenantId, Collection<UUID> ids) {
    return runs.findSuppliers(tenantId, ids).stream()
        .collect(Collectors.toMap(Supplier::id, Function.identity()));
  }

  private static Set<UUID> supplierIds(List<Item> items) {
    return items.stream().map(Item::supplierId).collect(Collectors.toSet());
  }

  private static void requireAllPayable(View view) {
    if (!view.proposal().excluded().isEmpty()) {
      throw new ApiException(
          409,
          "PURCHASE_PAYMENT_RUN_STALE",
          "a supplier in this run can no longer be paid; cancel it and propose again",
          view.proposal().excluded().stream().map(e -> e.name() + ": " + e.reason()).toList());
    }
  }

  /**
   * Refuses a run whose payee's bank details changed after it was approved (payment diversion).
   * Counted, not timed: the change's stamp is this service's clock and the approval the database's,
   * and comparing the two missed a change stamped early and refused one stamped late (V20).
   */
  private void requireBankDetailsUnchangedSinceApproval(
      UUID tenantId, PaymentRun run, Map<UUID, Supplier> suppliers) {
    if (run.approvedAt() == null) return;
    List<String> changed =
        runs.payeesChangedSinceApproval(tenantId, run.id()).stream()
            .map(id -> suppliers.containsKey(id) ? suppliers.get(id).name() : id.toString())
            .sorted()
            .toList();
    if (!changed.isEmpty()) {
      throw new ApiException(
          409,
          "PURCHASE_PAYMENT_RUN_BANK_DETAILS_CHANGED",
          "bank details changed after this run was approved; cancel it and propose again",
          changed);
    }
  }

  private static void requireStatus(PaymentRun run, String expected) {
    if (!expected.equals(run.status())) throw stateConflict(run);
  }

  /** The 409 for a run in the wrong state, named by the state it is in. */
  private static ApiException stateConflict(PaymentRun run) {
    String ref = "payment run " + run.reference();
    return switch (run.status()) {
      case PAID ->
          ApiException.conflict("PURCHASE_PAYMENT_RUN_ALREADY_PAID", ref + " has been paid");
      case CANCELLED ->
          ApiException.conflict("PURCHASE_PAYMENT_RUN_CANCELLED", ref + " was cancelled");
      case APPROVED ->
          ApiException.conflict(
              "PURCHASE_PAYMENT_RUN_ALREADY_APPROVED", ref + " is already approved");
      default ->
          ApiException.conflict(
              "PURCHASE_PAYMENT_RUN_NOT_APPROVED", ref + " must be approved first");
    };
  }

  /**
   * Dr Creditors / Cr Bank for what a supplier is paid from one store; reversed for a net credit.
   */
  private static List<NominalLedgerEntry> paymentPosting(
      PaymentRun run, Supplier supplier, UUID storeId, BigDecimal net) {
    if (net.signum() == 0) return List.of();
    BigDecimal amount = net.abs();
    LedgerPosting p =
        LedgerPosting.of(
            run.tenantId(),
            run.paymentDate(),
            "Payment run " + run.reference() + " to " + supplier.name(),
            Domain.SOURCE_SUPPLIER_PAYMENT,
            run.id(),
            storeId);
    return (net.signum() > 0
            ? p.debit(Domain.CODE_CREDITORS, Domain.NAME_CREDITORS, amount)
                .credit(Domain.CODE_BANK, Domain.NAME_BANK, amount)
            : p.debit(Domain.CODE_BANK, Domain.NAME_BANK, amount)
                .credit(Domain.CODE_CREDITORS, Domain.NAME_CREDITORS, amount))
        .build();
  }
}
