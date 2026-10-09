import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../shared/util/pick_file.dart';

// ── Supplier e-invoices received (07.13) ─────────────────────────────────────
//
// A supplier's EN 16931 invoice — UBL, CII, or the CII inside a Factur-X PDF —
// is read and checked by purchase-svc, matched to a supplier, an order and its
// lines, and captured through the three-way match. What cannot be matched waits
// here with the reason, for a person to finish.

/// The statuses a person can still act on.
const openEInvoiceStatuses = {
  'NOT_COMPLIANT',
  'MISDIRECTED',
  'NEEDS_SUPPLIER',
  'NEEDS_ORDER',
  'NEEDS_LINES',
  'NEEDS_RETURN',
  'NEEDS_DECISION',
};

/// What a status means, in the words a buyer uses.
String eInvoiceStatusLabel(String status) => switch (status) {
      'CAPTURED' => 'Captured',
      'CREDITED' => 'Credited',
      'REFUSED' => 'Refused',
      'DUPLICATE' => 'Already captured',
      'NOT_COMPLIANT' => 'Breaks the e-invoice rules',
      'MISDIRECTED' => 'Addressed to another business',
      'NEEDS_SUPPLIER' => 'Which supplier?',
      'NEEDS_ORDER' => 'Which order?',
      'NEEDS_LINES' => 'Lines to match',
      'NEEDS_RETURN' => 'Which return?',
      'NEEDS_DECISION' => 'Needs a decision',
      _ => status,
    };

/// What an order line was matched by, in words.
String matchedByLabel(String? by) => switch (by) {
      'ORDER_LINE' => 'by the supplier\'s reference',
      'ITEM_CODE' => 'by the supplier\'s item code',
      'PERSON' => 'by you',
      _ => '',
    };

class EInvoiceViolation {
  final String rule;
  final String severity;
  final String message;

  const EInvoiceViolation({
    required this.rule,
    required this.severity,
    required this.message,
  });

  bool get fatal => severity == 'FATAL';

  factory EInvoiceViolation.fromJson(Map<String, dynamic> j) =>
      EInvoiceViolation(
        rule: j['rule'] as String? ?? '',
        severity: j['severity'] as String? ?? '',
        message: j['message'] as String? ?? '',
      );
}

class EInvoiceLine {
  final int position;
  final String? itemName;
  final String? sellersItemId;
  final double? quantity;
  final String? unitCode;
  final double? netAmount;
  final double? netPrice;
  final String? poLineId;
  final String? matchedBy;

  const EInvoiceLine({
    required this.position,
    this.itemName,
    this.sellersItemId,
    this.quantity,
    this.unitCode,
    this.netAmount,
    this.netPrice,
    this.poLineId,
    this.matchedBy,
  });

  bool get matched => poLineId != null;

  factory EInvoiceLine.fromJson(Map<String, dynamic> j) => EInvoiceLine(
        position: (j['position'] as num?)?.toInt() ?? 0,
        itemName: j['itemName'] as String?,
        sellersItemId: j['sellersItemId'] as String?,
        quantity: (j['quantity'] as num?)?.toDouble(),
        unitCode: j['unitCode'] as String?,
        netAmount: (j['netAmount'] as num?)?.toDouble(),
        netPrice: (j['netPrice'] as num?)?.toDouble(),
        poLineId: j['poLineId'] as String?,
        matchedBy: j['matchedBy'] as String?,
      );
}

class SupplierEInvoice {
  final String id;
  final String? receivedAt;

  /// UPLOAD when a person posted it; the network's name when it delivered it.
  final String channel;

  /// The network's own reference for the delivery, when it gave one.
  final String? deliveryRef;
  final String container;
  final String syntax;
  final bool creditNote;
  final String? invoiceNumber;
  final String? issueDate;
  final String? currency;
  final String? sellerName;
  final String? sellerVatId;
  final String? sellerEndpoint;
  final String? orderReference;
  final double? payableAmount;
  final String status;
  final String? problem;
  final String? supplierId;
  final String? poId;
  final String? supplierInvoiceId;
  final String? decisionReason;
  final bool alreadyReceived;
  final List<EInvoiceViolation> violations;
  final List<EInvoiceLine> lines;

  const SupplierEInvoice({
    required this.id,
    this.receivedAt,
    this.channel = 'UPLOAD',
    this.deliveryRef,
    required this.container,
    required this.syntax,
    this.creditNote = false,
    this.invoiceNumber,
    this.issueDate,
    this.currency,
    this.sellerName,
    this.sellerVatId,
    this.sellerEndpoint,
    this.orderReference,
    this.payableAmount,
    required this.status,
    this.problem,
    this.supplierId,
    this.poId,
    this.supplierInvoiceId,
    this.decisionReason,
    this.alreadyReceived = false,
    this.violations = const [],
    this.lines = const [],
  });

  bool get open => openEInvoiceStatuses.contains(status);

  /// Where it came from, in words; null for an upload, which needs no saying.
  String? get arrivedBy => switch (channel) {
        'PEPPOL' => 'via Peppol',
        'FR_PDP' => "via France's platform",
        'SIMULATED' => 'via the simulated network',
        'UPLOAD' => null,
        _ => 'via $channel',
      };

  /// Open, and something a person can match rather than only refuse.
  bool get matchable =>
      open && status != 'NOT_COMPLIANT' && status != 'MISDIRECTED';

  String get title =>
      '${creditNote ? 'Credit note' : 'Invoice'} ${invoiceNumber ?? '(no number)'}';

  factory SupplierEInvoice.fromJson(Map<String, dynamic> j) => SupplierEInvoice(
        id: j['id'] as String? ?? '',
        receivedAt: j['receivedAt'] as String?,
        channel: j['channel'] as String? ?? 'UPLOAD',
        deliveryRef: j['deliveryRef'] as String?,
        container: j['container'] as String? ?? '',
        syntax: j['syntax'] as String? ?? '',
        creditNote: j['creditNote'] == true,
        invoiceNumber: j['invoiceNumber'] as String?,
        issueDate: j['issueDate'] as String?,
        currency: j['currency'] as String?,
        sellerName: j['sellerName'] as String?,
        sellerVatId: j['sellerVatId'] as String?,
        sellerEndpoint: j['sellerEndpoint'] as String?,
        orderReference: j['orderReference'] as String?,
        payableAmount: (j['payableAmount'] as num?)?.toDouble(),
        status: j['status'] as String? ?? '',
        problem: j['problem'] as String?,
        supplierId: j['supplierId'] as String?,
        poId: j['poId'] as String?,
        supplierInvoiceId: j['supplierInvoiceId'] as String?,
        decisionReason: j['decisionReason'] as String?,
        alreadyReceived: j['alreadyReceived'] == true,
        violations: ((j['violations'] as List?) ?? const [])
            .map((e) => EInvoiceViolation.fromJson(e as Map<String, dynamic>))
            .toList(),
        lines: ((j['lines'] as List?) ?? const [])
            .map((e) => EInvoiceLine.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// Received e-invoices, newest first.
final supplierEInvoicesProvider =
    FutureProvider.autoDispose<List<SupplierEInvoice>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(
    '/${ApiConstants.purchase}/e-invoices',
    queryParameters: {'limit': 50},
  );
  final data = (resp.data['data'] as List?) ?? [];
  return data
      .map((e) => SupplierEInvoice.fromJson(e as Map<String, dynamic>))
      .toList();
});

/// One e-invoice, with its lines and the rules it broke.
final supplierEInvoiceProvider = FutureProvider.autoDispose
    .family<SupplierEInvoice, String>((ref, id) async {
  final resp = await ref
      .read(apiClientProvider)
      .dio
      .get('/${ApiConstants.purchase}/e-invoices/$id');
  return SupplierEInvoice.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// A file picked to send: its bytes and name.
class PickedDocument {
  final Uint8List bytes;
  final String name;

  const PickedDocument(this.bytes, this.name);

  String get contentType => name.toLowerCase().endsWith('.pdf')
      ? 'application/pdf'
      : 'application/xml';
}

/// The largest document purchase-svc reads: a Factur-X PDF of 20 MB.
const maxEInvoiceBytes = 20 * 1024 * 1024;

/// Picks an XML or PDF e-invoice; null when dismissed. A provider so widget
/// tests hand a file in.
final eInvoicePickerProvider = Provider<Future<PickedDocument?> Function()>(
  (ref) => () async {
    final f = await pickFileWithBytes(const ['xml', 'pdf']);
    return f == null ? null : PickedDocument(f.bytes, f.name);
  },
);

/// Saves an e-invoice's original document; a provider so tests can watch it.
final eInvoiceSaverProvider =
    Provider<Future<void> Function(String fileName, Uint8List bytes)>(
  (ref) => (fileName, bytes) async {
    await FilePicker.saveFile(fileName: fileName, bytes: bytes);
  },
);

/// Sends the document as it was picked, and returns what it became.
Future<SupplierEInvoice> uploadEInvoice(Dio dio, PickedDocument doc) async {
  final resp = await dio.post(
    '/${ApiConstants.purchase}/e-invoices',
    data: Stream<List<int>>.fromIterable([doc.bytes]),
    options: Options(
      contentType: doc.contentType,
      headers: {Headers.contentLengthHeader: doc.bytes.length},
    ),
  );
  return SupplierEInvoice.fromJson(resp.data['data'] as Map<String, dynamic>);
}

/// What a person chose for a waiting e-invoice.
Future<SupplierEInvoice> matchEInvoice(
  Dio dio,
  String id, {
  String? supplierId,
  String? poId,
  String? returnId,
  Map<int, String> lines = const {},
  bool remember = false,
}) async {
  final resp = await dio.post(
    '/${ApiConstants.purchase}/e-invoices/$id/match',
    data: {
      'supplierId': ?supplierId,
      'poId': ?poId,
      'returnId': ?returnId,
      if (lines.isNotEmpty)
        'lines': [
          for (final e in lines.entries)
            {'position': e.key, 'poLineId': e.value},
        ],
      'remember': remember,
    },
  );
  return SupplierEInvoice.fromJson(resp.data['data'] as Map<String, dynamic>);
}

Future<SupplierEInvoice> refuseEInvoice(
  Dio dio,
  String id,
  String reason,
) async {
  final resp = await dio.post(
    '/${ApiConstants.purchase}/e-invoices/$id/refuse',
    data: {'reason': reason},
  );
  return SupplierEInvoice.fromJson(resp.data['data'] as Map<String, dynamic>);
}

/// The document exactly as the supplier sent it.
Future<Uint8List> originalEInvoice(Dio dio, String id) async {
  final resp = await dio.get<List<int>>(
    '/${ApiConstants.purchase}/e-invoices/$id/document',
    options: Options(responseType: ResponseType.bytes),
  );
  return Uint8List.fromList(resp.data ?? const []);
}

/// A supplier's e-invoicing address is a Peppol scheme — four digits — and an
/// identifier within it, or neither.
String? validEinvoiceScheme(String? v, {required bool idKeyed}) {
  final t = v?.trim() ?? '';
  if (t.isEmpty) return idKeyed ? 'Give the scheme too' : null;
  return RegExp(r'^\d{4}$').hasMatch(t) ? null : 'Four digits, e.g. 0088';
}

String? validEinvoiceId(String? v, {required bool schemeKeyed}) {
  final t = v?.trim() ?? '';
  if (t.isEmpty) return schemeKeyed ? 'Give the identifier too' : null;
  if (t.length > 128) return 'Up to 128 characters';
  return RegExp(r'^[\x21-\x7E]+$').hasMatch(t) ? null : 'No spaces';
}
