import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../shared/util/status_labels.dart';
import '../../core/network/api_client.dart';

// ── Models ───────────────────────────────────────────────────────────────────

class Supplier {
  final String id;
  final String name;
  final String? vatNumber;
  final bool vatRegistered;
  final String? countryCode;
  final String? currency;
  final int paymentTermsDays;

  /// The supplier's quoted lead time in days: the promise a delivery is
  /// measured against when an order names no date.
  final int? leadTimeDays;

  /// Where remittance advice is emailed when a payment run pays it (17.10).
  final String? remittanceEmail;
  final String? bankAccountName;
  final String? bankSortCode;

  /// Only the last four digits ever reach the app; the full number goes to
  /// the bank file alone.
  final String? bankAccountNumberMasked;
  final String? bankIbanMasked;
  final String? bankBic;
  final bool hasBankDetails;
  final String? bankDetailsChangedAt;

  /// Where the supplier's e-invoices come from (07.13): a Peppol electronic
  /// address scheme and the identifier within it.
  final String? einvoiceScheme;
  final String? einvoiceId;

  const Supplier({
    required this.id,
    required this.name,
    this.vatNumber,
    required this.vatRegistered,
    this.countryCode,
    this.currency,
    required this.paymentTermsDays,
    this.leadTimeDays,
    this.remittanceEmail,
    this.bankAccountName,
    this.bankSortCode,
    this.bankAccountNumberMasked,
    this.bankIbanMasked,
    this.bankBic,
    this.hasBankDetails = false,
    this.bankDetailsChangedAt,
    this.einvoiceScheme,
    this.einvoiceId,
  });

  factory Supplier.fromJson(Map<String, dynamic> j) => Supplier(
    id: j['id'] as String? ?? '',
    name: j['name'] as String? ?? '-',
    vatNumber: j['vatNumber'] as String?,
    vatRegistered: j['vatRegistered'] as bool? ?? false,
    countryCode: j['countryCode'] as String?,
    currency: j['currency'] as String?,
    paymentTermsDays: (j['paymentTermsDays'] as num?)?.toInt() ?? 0,
    leadTimeDays: (j['leadTimeDays'] as num?)?.toInt(),
    remittanceEmail: j['remittanceEmail'] as String?,
    bankAccountName: j['bankAccountName'] as String?,
    bankSortCode: j['bankSortCode'] as String?,
    bankAccountNumberMasked: j['bankAccountNumberMasked'] as String?,
    bankIbanMasked: j['bankIbanMasked'] as String?,
    bankBic: j['bankBic'] as String?,
    hasBankDetails: j['hasBankDetails'] == true,
    bankDetailsChangedAt: j['bankDetailsChangedAt'] as String?,
    einvoiceScheme: j['einvoiceScheme'] as String?,
    einvoiceId: j['einvoiceId'] as String?,
  );
}

class PurchaseOrder {
  final String id;
  final String supplierId;
  final String storeId;
  final String status;
  final String currency;
  final double totalNet;
  final double totalVat;
  final double totalGross;
  final String? expectedDelivery;
  final String createdAt;
  /// MANUAL, or PROPOSAL when a proposal run raised it (06.x).
  final String source;

  /// OWNED, or CONSIGNMENT when the supplier owns the goods until they sell.
  final String ownership;

  /// For a DROPSHIP order: the sale it fulfils and the customer the supplier ships to.
  final String? salesOrderId;
  final String? shipTo;

  /// DUTY_PAID, or DUTY_SUSPENDED when the goods arrive into bond.
  final String dutyStatus;

  const PurchaseOrder({
    required this.id,
    required this.supplierId,
    required this.storeId,
    required this.status,
    required this.currency,
    required this.totalNet,
    required this.totalVat,
    required this.totalGross,
    this.expectedDelivery,
    required this.createdAt,
    this.source = 'MANUAL',
    this.ownership = 'OWNED',
    this.salesOrderId,
    this.shipTo,
    this.dutyStatus = 'DUTY_PAID',
  });

  factory PurchaseOrder.fromJson(Map<String, dynamic> j) => PurchaseOrder(
    id: j['id'] as String? ?? '',
    supplierId: j['supplierId'] as String? ?? '',
    storeId: j['storeId'] as String? ?? '',
    status: j['status'] as String? ?? '-',
    currency: j['currency'] as String? ?? '',
    totalNet: (j['totalNet'] as num?)?.toDouble() ?? 0,
    totalVat: (j['totalVat'] as num?)?.toDouble() ?? 0,
    totalGross: (j['totalGross'] as num?)?.toDouble() ?? 0,
    expectedDelivery: j['expectedDelivery'] as String?,
    createdAt: j['createdAt'] as String? ?? '',
    source: j['source'] as String? ?? 'MANUAL',
    ownership: j['ownership'] as String? ?? 'OWNED',
    salesOrderId: j['salesOrderId'] as String?,
    shipTo: j['shipTo'] as String?,
    dutyStatus: j['dutyStatus'] as String? ?? 'DUTY_PAID',
  );
}

class PurchaseOrderLine {
  final String id;
  final String variantId;
  final double qty;
  final double unitPrice;
  final String? vatCode;
  /// The proposal's arithmetic for this line; null on a line a person typed.
  final String? proposalReason;

  const PurchaseOrderLine({
    required this.id,
    required this.variantId,
    required this.qty,
    required this.unitPrice,
    this.vatCode,
    this.proposalReason,
  });

  factory PurchaseOrderLine.fromJson(Map<String, dynamic> j) =>
      PurchaseOrderLine(
        id: j['id'] as String? ?? '',
        variantId: j['variantId'] as String? ?? '',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
        unitPrice: (j['unitPrice'] as num?)?.toDouble() ?? 0,
        vatCode: j['vatCode'] as String?,
        proposalReason: j['proposalReason'] as String?,
      );
}

// ── Providers ────────────────────────────────────────────────────────────────

final suppliersProvider = FutureProvider.autoDispose<List<Supplier>>((
  ref,
) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.purchase}/suppliers');
  final data = (resp.data['data'] as List?) ?? [];
  return data.map((e) => Supplier.fromJson(e as Map<String, dynamic>)).toList();
});

final purchaseOrdersProvider = FutureProvider.autoDispose<List<PurchaseOrder>>((
  ref,
) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.purchase}/purchase-orders');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => PurchaseOrder.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// How much of each ordered line has actually turned up.
///
/// Separate from the lines themselves because it answers a different question:
/// the lines say what was ordered, this says what is still owed. A
/// PARTIALLY_RECEIVED badge tells a buyer that something is missing but not
/// what, which is the only thing they can act on.
class PurchaseOrderLineProgress {
  final String variantId;
  final double qtyOrdered;
  final double qtyReceived;
  final double qtyOutstanding;

  /// What has gone back to the supplier out of what was received (07.8).
  final double qtyReturned;

  const PurchaseOrderLineProgress({
    required this.variantId,
    required this.qtyOrdered,
    required this.qtyReceived,
    required this.qtyOutstanding,
    this.qtyReturned = 0,
  });

  /// What could still go back: received less already returned.
  double get qtyReturnable => qtyReceived - qtyReturned;

  factory PurchaseOrderLineProgress.fromJson(Map<String, dynamic> j) =>
      PurchaseOrderLineProgress(
        variantId: j['variantId'] as String? ?? '',
        qtyOrdered: (j['qtyOrdered'] as num?)?.toDouble() ?? 0,
        qtyReceived: (j['qtyReceived'] as num?)?.toDouble() ?? 0,
        qtyOutstanding: (j['qtyOutstanding'] as num?)?.toDouble() ?? 0,
        qtyReturned: (j['qtyReturned'] as num?)?.toDouble() ?? 0,
      );
}

// ── Return to vendor and the debit note (07.8) ───────────────────────────────
//
// Goods going back against a received order, priced at the order's prices, with
// the debit note raised for them and the supplier's credit note once it comes.

class VendorReturnLine {
  final String variantId;
  final double qty;
  final double unitPrice;
  final double lineNet;
  const VendorReturnLine({
    required this.variantId,
    required this.qty,
    required this.unitPrice,
    required this.lineNet,
  });
  factory VendorReturnLine.fromJson(Map<String, dynamic> j) => VendorReturnLine(
    variantId: j['variantId'] as String? ?? '',
    qty: (j['qty'] as num?)?.toDouble() ?? 0,
    unitPrice: (j['unitPrice'] as num?)?.toDouble() ?? 0,
    lineNet: (j['lineNet'] as num?)?.toDouble() ?? 0,
  );
}

class VendorReturn {
  final String id;
  final String poId;
  final String status;
  final String reason;
  final String? notes;
  final String currency;
  final double netAmount;
  final double vatAmount;
  final double grossAmount;
  final String debitNoteNumber;
  final String raisedAt;
  final String? creditNoteNumber;
  final String? creditNoteDate;
  final double? creditAmount;
  final List<VendorReturnLine> lines;
  const VendorReturn({
    required this.id,
    required this.poId,
    required this.status,
    required this.reason,
    this.notes,
    required this.currency,
    required this.netAmount,
    required this.vatAmount,
    required this.grossAmount,
    required this.debitNoteNumber,
    required this.raisedAt,
    this.creditNoteNumber,
    this.creditNoteDate,
    this.creditAmount,
    this.lines = const [],
  });
  bool get credited => status == 'CREDITED';
  factory VendorReturn.fromJson(Map<String, dynamic> j) => VendorReturn(
    id: j['id'] as String? ?? '',
    poId: j['poId'] as String? ?? '',
    status: j['status'] as String? ?? 'RAISED',
    reason: j['reason'] as String? ?? '',
    notes: j['notes'] as String?,
    currency: j['currency'] as String? ?? '',
    netAmount: (j['netAmount'] as num?)?.toDouble() ?? 0,
    vatAmount: (j['vatAmount'] as num?)?.toDouble() ?? 0,
    grossAmount: (j['grossAmount'] as num?)?.toDouble() ?? 0,
    debitNoteNumber: j['debitNoteNumber'] as String? ?? '',
    raisedAt: j['raisedAt'] as String? ?? '',
    creditNoteNumber: j['creditNoteNumber'] as String?,
    creditNoteDate: j['creditNoteDate'] as String?,
    creditAmount: (j['creditAmount'] as num?)?.toDouble(),
    lines: [
      for (final l in (j['lines'] as List?) ?? const [])
        VendorReturnLine.fromJson(l as Map<String, dynamic>),
    ],
  );
}

/// The reasons the server accepts, in the words a buyer uses.
const vendorReturnReasons = <String, String>{
  'DAMAGED': 'Damaged in transit',
  'WRONG_ITEM': 'Wrong item delivered',
  'OVER_DELIVERED': 'Over-delivered',
  'QUALITY': 'Quality rejected',
  'EXPIRED': 'Expired or short-dated',
  'RECALL': 'Recalled by the supplier',
  'OTHER': 'Other',
};

final vendorReturnsProvider = FutureProvider.autoDispose
    .family<List<VendorReturn>, String>((ref, poId) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(
            '/${ApiConstants.purchase}/vendor-returns',
            queryParameters: {'poId': poId},
          );
      final data = (resp.data['data'] as List?) ?? [];
      return data
          .map((e) => VendorReturn.fromJson(e as Map<String, dynamic>))
          .toList();
    });

final purchaseOrderProgressProvider = FutureProvider.autoDispose
    .family<List<PurchaseOrderLineProgress>, String>((ref, poId) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get('/${ApiConstants.purchase}/purchase-orders/$poId/progress');
      final data = (resp.data['data'] as List?) ?? [];
      return data
          .map(
            (e) =>
                PurchaseOrderLineProgress.fromJson(e as Map<String, dynamic>),
          )
          .toList();
    });

final purchaseOrderLinesProvider = FutureProvider.autoDispose
    .family<List<PurchaseOrderLine>, String>((ref, poId) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get('/${ApiConstants.purchase}/purchase-orders/$poId/lines');
      final data = (resp.data['data'] as List?) ?? [];
      return data
          .map((e) => PurchaseOrderLine.fromJson(e as Map<String, dynamic>))
          .toList();
    });

// ── Supplier invoices: the three-way match ───────────────────────────────────
//
// Ordered against received against invoiced. The screen exists because a status
// alone answers the wrong question: a buyer told an invoice is FLAGGED still has
// to know WHICH line disagreed and by how much before they can ring the
// supplier, and that is three numbers side by side rather than one badge.

/// One invoice line with all three documents' figures beside it.
class InvoiceMatchLine {
  final String variantId;
  final double qtyOrdered;
  final double qtyReceived;
  final double qtyInvoicedBefore;
  final double qtyInvoiced;
  final double? orderedUnitPrice;
  final double invoicedUnitPrice;
  final List<String> variances;

  const InvoiceMatchLine({
    required this.variantId,
    required this.qtyOrdered,
    required this.qtyReceived,
    required this.qtyInvoicedBefore,
    required this.qtyInvoiced,
    this.orderedUnitPrice,
    required this.invoicedUnitPrice,
    required this.variances,
  });

  bool get matched => variances.isEmpty;

  factory InvoiceMatchLine.fromJson(Map<String, dynamic> j) => InvoiceMatchLine(
    variantId: j['variantId'] as String? ?? '',
    qtyOrdered: (j['qtyOrdered'] as num?)?.toDouble() ?? 0,
    qtyReceived: (j['qtyReceived'] as num?)?.toDouble() ?? 0,
    qtyInvoicedBefore: (j['qtyInvoicedBefore'] as num?)?.toDouble() ?? 0,
    qtyInvoiced: (j['qtyInvoiced'] as num?)?.toDouble() ?? 0,
    // Absent, not null: JSON-B omits a null field entirely, which is the
    // shape SJ-D14 turned into a 503 on every guest order for months.
    orderedUnitPrice: (j['orderedUnitPrice'] as num?)?.toDouble(),
    invoicedUnitPrice: (j['invoicedUnitPrice'] as num?)?.toDouble() ?? 0,
    variances: ((j['variances'] as List?) ?? const [])
        .map((e) => e.toString())
        .toList(),
  );
}

class SupplierInvoice {
  final String id;
  final String poId;
  final String invoiceNumber;
  final String? invoiceDate;
  final String currency;
  final double netAmount;
  final double vatAmount;
  final double grossAmount;
  final String status;
  final List<InvoiceMatchLine> lines;

  /// Invoice date plus the supplier's payment terms — what accounts payable
  /// schedules by.
  final String? dueDate;

  /// The total printed on the supplier's document, when the capturer keyed it.
  final double? statedGross;

  /// Header-level variances: `TOTAL_MISMATCH` when the supplier's own total does
  /// not equal their own lines plus VAT.
  final List<String> headerVariances;

  /// When the AP posting was written; null for invoices captured before the
  /// ledger was wired in.
  final String? postedAt;

  /// Whether it may be paid: MATCHED, or FLAGGED and then APPROVED.
  final bool payable;
  final String? resolvedAt;
  final String? resolutionReason;

  const SupplierInvoice({
    required this.id,
    required this.poId,
    required this.invoiceNumber,
    this.invoiceDate,
    required this.currency,
    required this.netAmount,
    required this.vatAmount,
    required this.grossAmount,
    required this.status,
    required this.lines,
    this.dueDate,
    this.statedGross,
    this.headerVariances = const [],
    this.postedAt,
    this.payable = false,
    this.resolvedAt,
    this.resolutionReason,
  });

  /// Awaiting a manager's decision.
  bool get flagged => status == 'FLAGGED';
  bool get approved => status == 'APPROVED';
  bool get rejected => status == 'REJECTED';

  factory SupplierInvoice.fromJson(Map<String, dynamic> j) => SupplierInvoice(
    id: j['id'] as String? ?? '',
    poId: j['poId'] as String? ?? '',
    invoiceNumber: j['invoiceNumber'] as String? ?? '-',
    invoiceDate: j['invoiceDate'] as String?,
    currency: j['currency'] as String? ?? '',
    netAmount: (j['netAmount'] as num?)?.toDouble() ?? 0,
    vatAmount: (j['vatAmount'] as num?)?.toDouble() ?? 0,
    grossAmount: (j['grossAmount'] as num?)?.toDouble() ?? 0,
    status: j['status'] as String? ?? '-',
    lines: ((j['lines'] as List?) ?? const [])
        .map((e) => InvoiceMatchLine.fromJson(e as Map<String, dynamic>))
        .toList(),
    dueDate: j['dueDate'] as String?,
    statedGross: (j['statedGross'] as num?)?.toDouble(),
    headerVariances: ((j['headerVariances'] as List?) ?? const [])
        .map((e) => e.toString())
        .toList(),
    postedAt: j['postedAt'] as String?,
    payable: j['payable'] == true,
    resolvedAt: j['resolvedAt'] as String?,
    resolutionReason: j['resolutionReason'] as String?,
  );
}

final supplierInvoicesProvider =
    FutureProvider.autoDispose<List<SupplierInvoice>>((ref) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get('/${ApiConstants.purchase}/supplier-invoices');
      final data = (resp.data['data'] as List?) ?? [];
      return data
          .map((e) => SupplierInvoice.fromJson(e as Map<String, dynamic>))
          .toList();
    });

// ── Supplier payment runs (17.10) ────────────────────────────────────────────

/// An invoice a run pays, or a supplier credit note it offsets.
class PaymentRunDocument {
  final String type;
  final String documentId;
  final String reference;
  final String? documentDate;
  final String? dueDate;
  final double amount;

  const PaymentRunDocument({
    required this.type,
    required this.documentId,
    required this.reference,
    this.documentDate,
    this.dueDate,
    required this.amount,
  });

  bool get isCredit => type == 'CREDIT_NOTE';

  factory PaymentRunDocument.fromJson(Map<String, dynamic> j) =>
      PaymentRunDocument(
        type: j['type'] as String? ?? 'INVOICE',
        documentId: j['documentId'] as String? ?? '',
        reference: j['reference'] as String? ?? '-',
        documentDate: j['documentDate'] as String?,
        dueDate: j['dueDate'] as String?,
        amount: (j['amount'] as num?)?.toDouble() ?? 0,
      );
}

/// What the bank's status report said about a supplier's payment (17.12): its
/// status, and the Verification of Payee result on the payee's name.
class PayeeCheck {
  final String endToEndId;
  final String status;
  final String? reasonCode;
  final String? payeeMatch;
  final String? matchedName;
  final bool held;
  final bool releasable;
  final String? releasedAt;
  final String? releaseReason;

  const PayeeCheck({
    required this.endToEndId,
    required this.status,
    this.reasonCode,
    this.payeeMatch,
    this.matchedName,
    required this.held,
    required this.releasable,
    this.releasedAt,
    this.releaseReason,
  });

  bool get released => releasedAt != null;

  /// Held and not released: the run cannot be paid while it is.
  bool get blocking => held && !released;

  factory PayeeCheck.fromJson(Map<String, dynamic> j) => PayeeCheck(
    endToEndId: j['endToEndId'] as String? ?? '',
    status: j['status'] as String? ?? '',
    reasonCode: j['reasonCode'] as String?,
    payeeMatch: j['payeeMatch'] as String?,
    matchedName: j['matchedName'] as String?,
    held: j['held'] == true,
    releasable: j['releasable'] == true,
    releasedAt: j['releasedAt'] as String?,
    releaseReason: j['releaseReason'] as String?,
  );
}

/// What a run pays one supplier, and anything a reviewer should check first.
class PaymentRunSupplier {
  final String supplierId;
  final String name;
  final double net;
  final bool remittanceEmailOnFile;
  final List<String> warnings;
  final List<PaymentRunDocument> documents;
  final PayeeCheck? bankCheck;

  const PaymentRunSupplier({
    required this.supplierId,
    required this.name,
    required this.net,
    required this.remittanceEmailOnFile,
    this.warnings = const [],
    this.documents = const [],
    this.bankCheck,
  });

  factory PaymentRunSupplier.fromJson(Map<String, dynamic> j) =>
      PaymentRunSupplier(
        supplierId: j['supplierId'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        net: (j['net'] as num?)?.toDouble() ?? 0,
        remittanceEmailOnFile: j['remittanceEmailOnFile'] == true,
        warnings: ((j['warnings'] as List?) ?? const [])
            .map((e) => e.toString())
            .toList(),
        documents: ((j['documents'] as List?) ?? const [])
            .map((e) => PaymentRunDocument.fromJson(e as Map<String, dynamic>))
            .toList(),
        bankCheck: j['bankCheck'] is Map
            ? PayeeCheck.fromJson(
                (j['bankCheck'] as Map).cast<String, dynamic>(),
              )
            : null,
      );
}

/// A supplier with something due that the run does not pay, and why.
class PaymentRunExcluded {
  final String supplierId;
  final String name;
  final String reason;
  final double net;

  const PaymentRunExcluded({
    required this.supplierId,
    required this.name,
    required this.reason,
    required this.net,
  });

  factory PaymentRunExcluded.fromJson(Map<String, dynamic> j) =>
      PaymentRunExcluded(
        supplierId: j['supplierId'] as String? ?? '',
        name: j['name'] as String? ?? '-',
        reason: j['reason'] as String? ?? '',
        net: (j['net'] as num?)?.toDouble() ?? 0,
      );
}

/// A payment run: PROPOSED, APPROVED, PAID or CANCELLED.
class PaymentRun {
  final String id;
  final String reference;
  final String status;
  final String payUpTo;
  final String paymentDate;
  final String currency;
  final double total;
  final String? proposedBy;
  final String? cancelReason;
  final List<PaymentRunSupplier> suppliers;
  final List<PaymentRunExcluded> excluded;

  const PaymentRun({
    required this.id,
    required this.reference,
    required this.status,
    required this.payUpTo,
    required this.paymentDate,
    required this.currency,
    required this.total,
    this.proposedBy,
    this.cancelReason,
    this.suppliers = const [],
    this.excluded = const [],
  });

  bool get proposed => status == 'PROPOSED';
  bool get approved => status == 'APPROVED';
  bool get paid => status == 'PAID';
  bool get cancelled => status == 'CANCELLED';

  /// Payments the bank holds that no manager has released.
  int get heldPayments =>
      suppliers.where((s) => s.bankCheck?.blocking == true).length;

  factory PaymentRun.fromJson(Map<String, dynamic> j) => PaymentRun(
    id: j['id'] as String? ?? '',
    reference: j['reference'] as String? ?? '-',
    status: j['status'] as String? ?? '-',
    payUpTo: j['payUpTo'] as String? ?? '',
    paymentDate: j['paymentDate'] as String? ?? '',
    currency: j['currency'] as String? ?? '',
    total: (j['total'] as num?)?.toDouble() ?? 0,
    proposedBy: j['proposedBy'] as String?,
    cancelReason: j['cancelReason'] as String?,
    suppliers: ((j['suppliers'] as List?) ?? const [])
        .map((e) => PaymentRunSupplier.fromJson(e as Map<String, dynamic>))
        .toList(),
    excluded: ((j['excluded'] as List?) ?? const [])
        .map((e) => PaymentRunExcluded.fromJson(e as Map<String, dynamic>))
        .toList(),
  );
}

final paymentRunsProvider = FutureProvider.autoDispose<List<PaymentRun>>((
  ref,
) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.purchase}/payment-runs');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => PaymentRun.fromJson(e as Map<String, dynamic>))
      .toList();
});

// ── Bank-standard payment files (17.12) ──────────────────────────────────────

/// A file a bank takes for a run.
class BankFileFormat {
  final String code;
  final String label;
  final String extension;
  final String mimeType;

  const BankFileFormat(this.code, this.label, this.extension, this.mimeType);

  static const csv = BankFileFormat(
    'CSV',
    'CSV for bulk upload',
    'csv',
    'text/csv;charset=utf-8',
  );
  static const pain001 = BankFileFormat(
    'PAIN001',
    'SEPA credit transfer (pain.001)',
    'xml',
    'application/xml',
  );
  static const bacs18 = BankFileFormat(
    'BACS18',
    'Bacs Standard 18',
    'txt',
    'text/plain;charset=utf-8',
  );

  /// The formats that can pay a run in this currency, the bank standard first.
  static List<BankFileFormat> forCurrency(String currency) =>
      switch (currency) {
        'EUR' => const [pain001, csv],
        'GBP' => const [bacs18, csv],
        _ => const [csv],
      };
}

/// The account a currency's supplier payments are made from.
class PayingAccount {
  final String currency;
  final String accountName;
  final String? sortCode;
  final String? accountNumberMasked;
  final String? ibanMasked;
  final String? bic;
  final String? serviceUserNumber;
  final bool sendsBacs;
  final bool sendsSepa;
  final String? setAt;

  const PayingAccount({
    required this.currency,
    required this.accountName,
    this.sortCode,
    this.accountNumberMasked,
    this.ibanMasked,
    this.bic,
    this.serviceUserNumber,
    this.sendsBacs = false,
    this.sendsSepa = false,
    this.setAt,
  });

  factory PayingAccount.fromJson(Map<String, dynamic> j) => PayingAccount(
    currency: j['currency'] as String? ?? '',
    accountName: j['accountName'] as String? ?? '-',
    sortCode: j['sortCode'] as String?,
    accountNumberMasked: j['accountNumberMasked'] as String?,
    ibanMasked: j['ibanMasked'] as String?,
    bic: j['bic'] as String?,
    serviceUserNumber: j['serviceUserNumber'] as String?,
    sendsBacs: j['sendsBacs'] == true,
    sendsSepa: j['sendsSepa'] == true,
    setAt: j['setAt'] as String?,
  );
}

final payingAccountsProvider = FutureProvider.autoDispose<List<PayingAccount>>((
  ref,
) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.purchase}/payment-runs/paying-accounts');
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => PayingAccount.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// Part of a warehouse order's line allocated to a shop it serves (cross-docking).
class LineAllocation {
  final String poLineId;
  final String storeId;
  final double qty;
  const LineAllocation({required this.poLineId, required this.storeId, required this.qty});
  factory LineAllocation.fromJson(Map<String, dynamic> j) => LineAllocation(
        poLineId: j['poLineId'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        qty: (j['qty'] as num?)?.toDouble() ?? 0,
      );
}

/// A warehouse order's cross-dock allocations, every line.
final purchaseOrderAllocationsProvider =
    FutureProvider.autoDispose.family<List<LineAllocation>, String>((ref, poId) async {
  final resp = await ref.read(apiClientProvider).dio.get(
        '/${ApiConstants.purchase}/purchase-orders/$poId/allocations',
      );
  return ((resp.data['data'] as List?) ?? const [])
      .map((e) => LineAllocation.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// A purchase order's status in words, and the tone it is shown in: the same
/// on the order list, the order and the e-invoice matching dialog.
(String, StatusTone) purchaseOrderStatus(String status) =>
    switch (status.toUpperCase()) {
      'DRAFT' => ('Draft', StatusTone.neutral),
      // Amber for the same reason PARTIALLY_RECEIVED is: this is a state
      // somebody has to act on, not one to observe. Blue would read as "on its
      // way" when it means "stopped".
      'PENDING_APPROVAL' => ('Pending approval', StatusTone.warning),
      'SUBMITTED' => ('Submitted', StatusTone.info),
      // Something is still owed, and that is a state a buyer is meant to act
      // on rather than merely observe.
      'PARTIALLY_RECEIVED' => ('Part received', StatusTone.warning),
      'RECEIVED' => ('Received', StatusTone.success),
      // Short-closed: part arrived and the rest never will.
      'CLOSED' => ('Closed short', StatusTone.neutral),
      // Grey, not amber: nothing is left for anyone to do.
      'CANCELLED' => ('Cancelled', StatusTone.neutral),
      _ => (humanizeCode(status), StatusTone.neutral),
    };
