import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';

// Withdrawals and recalls. Opening one takes every pack in scope off sale at
// every store on the server, in one go; what the app has to get right is the
// evidence afterwards — what each store found, what became of it, and that a
// pack the recall could not rule out is checked before it goes back on sale.

final _staffBase = '/${ApiConstants.inventory}/admin/inventory/recalls';
final _setupBase = '/${ApiConstants.inventory}/admin/recalls';
final _noticesBase = '/${ApiConstants.order}/orders/recall-notices';

// ── Models ───────────────────────────────────────────────────────────────────

class RecallSummary {
  final String id;
  final String reference;
  final String kind;
  final String hazard;
  final String status;
  final DateTime? openedAt;
  final int storesAffected;
  final int storesOutstanding;
  final double qtyHeld;

  /// Orders that drew on the packs in scope, found as the recall opened.
  final int ordersAffected;

  const RecallSummary({
    required this.id,
    required this.reference,
    required this.kind,
    required this.hazard,
    required this.status,
    this.openedAt,
    this.storesAffected = 0,
    this.storesOutstanding = 0,
    this.qtyHeld = 0,
    this.ordersAffected = 0,
  });

  factory RecallSummary.fromJson(Map<String, dynamic> j) => RecallSummary(
    id: j['id'] as String? ?? '',
    reference: j['reference'] as String? ?? '-',
    kind: j['kind'] as String? ?? 'RECALL',
    hazard: j['hazard'] as String? ?? 'OTHER',
    status: j['status'] as String? ?? 'OPEN',
    openedAt: _time(j['openedAt']),
    storesAffected: (j['storesAffected'] as num?)?.toInt() ?? 0,
    storesOutstanding: (j['storesOutstanding'] as num?)?.toInt() ?? 0,
    qtyHeld: (j['qtyHeld'] as num?)?.toDouble() ?? 0,
    ordersAffected: (j['ordersAffected'] as num?)?.toInt() ?? 0,
  );
}

class RecallScopeLine {
  final String variantId;
  final String? batchNo;
  final String? expiryFrom;
  final String? expiryTo;

  const RecallScopeLine({
    required this.variantId,
    this.batchNo,
    this.expiryFrom,
    this.expiryTo,
  });

  bool get coversEveryPack =>
      batchNo == null && expiryFrom == null && expiryTo == null;

  factory RecallScopeLine.fromJson(Map<String, dynamic> j) => RecallScopeLine(
    variantId: j['variantId'] as String? ?? '',
    batchNo: j['batchNo'] as String?,
    expiryFrom: j['expiryFrom'] as String?,
    expiryTo: j['expiryTo'] as String?,
  );

  Map<String, dynamic> toJson() => {
    'variantId': variantId,
    'batchNo': ?batchNo,
    'expiryFrom': ?expiryFrom,
    'expiryTo': ?expiryTo,
  };
}

class RecallHeldBatch {
  final String batchId;
  final String storeId;
  final String variantId;
  final String? batchNo;
  final String? expiryDate;

  /// IN_SCOPE, or LOT_UNKNOWN / DATE_UNKNOWN for a pack that could be affected.
  final String match;
  final double qtyAtQuarantine;
  final double remainingQty;
  final String quarantinedOn;
  final bool released;
  final String? releaseReason;

  const RecallHeldBatch({
    required this.batchId,
    required this.storeId,
    required this.variantId,
    this.batchNo,
    this.expiryDate,
    required this.match,
    required this.qtyAtQuarantine,
    required this.remainingQty,
    this.quarantinedOn = 'OPEN',
    this.released = false,
    this.releaseReason,
  });

  bool get releasable => match != 'IN_SCOPE' && !released;

  factory RecallHeldBatch.fromJson(Map<String, dynamic> j) => RecallHeldBatch(
    batchId: j['batchId'] as String? ?? '',
    storeId: j['storeId'] as String? ?? '',
    variantId: j['variantId'] as String? ?? '',
    batchNo: j['batchNo'] as String?,
    expiryDate: j['expiryDate'] as String?,
    match: j['match'] as String? ?? 'IN_SCOPE',
    qtyAtQuarantine: (j['qtyAtQuarantine'] as num?)?.toDouble() ?? 0,
    remainingQty: (j['remainingQty'] as num?)?.toDouble() ?? 0,
    quarantinedOn: j['quarantinedOn'] as String? ?? 'OPEN',
    released: j['released'] as bool? ?? false,
    releaseReason: j['releaseReason'] as String?,
  );
}

class RecallStoreAction {
  final String storeId;
  final double qtyFound;
  final double systemQty;
  final String disposition;
  final bool noticeDisplayed;
  final String? notes;
  final DateTime? recordedAt;

  const RecallStoreAction({
    required this.storeId,
    required this.qtyFound,
    required this.systemQty,
    required this.disposition,
    this.noticeDisplayed = false,
    this.notes,
    this.recordedAt,
  });

  factory RecallStoreAction.fromJson(Map<String, dynamic> j) =>
      RecallStoreAction(
        storeId: j['storeId'] as String? ?? '',
        qtyFound: (j['qtyFound'] as num?)?.toDouble() ?? 0,
        systemQty: (j['systemQty'] as num?)?.toDouble() ?? 0,
        disposition: j['disposition'] as String? ?? 'HELD_FOR_COLLECTION',
        noticeDisplayed: j['noticeDisplayed'] as bool? ?? false,
        notes: j['notes'] as String?,
        recordedAt: _time(j['recordedAt']),
      );
}

class RecallStoreProgress {
  final String storeId;
  final double qtyHeld;
  final double? qtyFound;
  final bool outstanding;

  const RecallStoreProgress({
    required this.storeId,
    required this.qtyHeld,
    this.qtyFound,
    required this.outstanding,
  });

  factory RecallStoreProgress.fromJson(Map<String, dynamic> j) =>
      RecallStoreProgress(
        storeId: j['storeId'] as String? ?? '',
        qtyHeld: (j['qtyHeld'] as num?)?.toDouble() ?? 0,
        qtyFound: (j['qtyFound'] as num?)?.toDouble(),
        outstanding: j['outstanding'] as bool? ?? false,
      );
}

class RecallDetail {
  final String id;
  final String reference;
  final String kind;
  final String hazard;
  final String reason;
  final String? customerNotice;
  final String source;
  final String? sourceReference;
  final String status;
  final DateTime? openedAt;
  final String? endNotes;
  final List<RecallScopeLine> items;
  final List<RecallHeldBatch> batches;
  final List<RecallStoreAction> storeActions;
  final List<RecallStoreProgress> stores;

  /// What a buyer may choose from (GPSR art.37); empty for a withdrawal.
  final List<String> remedies;
  final String? singleRemedyReason;
  final String? contactPhone;
  final String? contactUrl;
  final String? soldFrom;
  final int ordersAffected;
  final double qtySold;

  const RecallDetail({
    required this.id,
    required this.reference,
    required this.kind,
    required this.hazard,
    required this.reason,
    this.customerNotice,
    required this.source,
    this.sourceReference,
    required this.status,
    this.openedAt,
    this.endNotes,
    this.items = const [],
    this.batches = const [],
    this.storeActions = const [],
    this.stores = const [],
    this.remedies = const [],
    this.singleRemedyReason,
    this.contactPhone,
    this.contactUrl,
    this.soldFrom,
    this.ordersAffected = 0,
    this.qtySold = 0,
  });

  bool get isOpen => status == 'OPEN';
  bool get isRecall => kind == 'RECALL';

  factory RecallDetail.fromJson(Map<String, dynamic> j) => RecallDetail(
    id: j['id'] as String? ?? '',
    reference: j['reference'] as String? ?? '-',
    kind: j['kind'] as String? ?? 'RECALL',
    hazard: j['hazard'] as String? ?? 'OTHER',
    reason: j['reason'] as String? ?? '',
    customerNotice: j['customerNotice'] as String?,
    source: j['source'] as String? ?? 'OTHER',
    sourceReference: j['sourceReference'] as String?,
    status: j['status'] as String? ?? 'OPEN',
    openedAt: _time(j['openedAt']),
    endNotes: j['endNotes'] as String?,
    items: _list(j['items'], RecallScopeLine.fromJson),
    batches: _list(j['batches'], RecallHeldBatch.fromJson),
    storeActions: _list(j['storeActions'], RecallStoreAction.fromJson),
    stores: _list(j['stores'], RecallStoreProgress.fromJson),
    remedies: _strings(j['remedies']),
    singleRemedyReason: j['singleRemedyReason'] as String?,
    contactPhone: j['contactPhone'] as String?,
    contactUrl: j['contactUrl'] as String?,
    soldFrom: j['soldFrom'] as String?,
    ordersAffected: (j['ordersAffected'] as num?)?.toInt() ?? 0,
    qtySold: (j['qtySold'] as num?)?.toDouble() ?? 0,
  );
}

/// One line of the order a recall notice is about, as inventory-svc matched it.
class RecallNoticeLine {
  final String variantId;
  final String? productName;
  final String? sku;
  final String? batchNo;
  final String? expiryDate;
  final double qty;

  const RecallNoticeLine({
    required this.variantId,
    this.productName,
    this.sku,
    this.batchNo,
    this.expiryDate,
    required this.qty,
  });

  factory RecallNoticeLine.fromJson(Map<String, dynamic> j) => RecallNoticeLine(
    variantId: j['variantId'] as String? ?? '',
    productName: j['productName'] as String?,
    sku: j['sku'] as String?,
    batchNo: j['batchNo'] as String?,
    expiryDate: j['expiryDate'] as String?,
    qty: (j['qty'] as num?)?.toDouble() ?? 0,
  );

  /// "Crunchy peanut butter, lot L1, best before 2026-10-01".
  String describe() => [
    productName ?? 'the product',
    if (batchNo != null) 'lot $batchNo',
    if (expiryDate != null) 'best before $expiryDate',
  ].join(', ');
}

/// A recall's notice to the buyer of one order (05.10), as order-svc holds it.
class RecallNotice {
  final String id;
  final String recallId;
  final String reference;
  final String hazard;
  final String reason;
  final String customerNotice;
  final List<String> remedies;
  final String? singleRemedyReason;
  final String? contactPhone;
  final String? contactUrl;
  final String orderId;
  final String storeId;
  final String channel;
  final bool buyerIdentified;
  final DateTime? soldAt;

  /// ISSUED, UNIDENTIFIED, REMEDY_CHOSEN or RESOLVED.
  final String status;
  final String? remedy;
  final String? remedyChosenVia;
  final String? resolution;
  final String? returnId;
  final String? resolutionNotes;
  final List<RecallNoticeLine> lines;

  const RecallNotice({
    required this.id,
    required this.recallId,
    required this.reference,
    required this.hazard,
    required this.reason,
    required this.customerNotice,
    this.remedies = const [],
    this.singleRemedyReason,
    this.contactPhone,
    this.contactUrl,
    required this.orderId,
    required this.storeId,
    required this.channel,
    this.buyerIdentified = false,
    this.soldAt,
    required this.status,
    this.remedy,
    this.remedyChosenVia,
    this.resolution,
    this.returnId,
    this.resolutionNotes,
    this.lines = const [],
  });

  bool get isResolved => status == 'RESOLVED';

  factory RecallNotice.fromJson(Map<String, dynamic> j) => RecallNotice(
    id: j['id'] as String? ?? '',
    recallId: j['recallId'] as String? ?? '',
    reference: j['reference'] as String? ?? '-',
    hazard: j['hazard'] as String? ?? 'OTHER',
    reason: j['reason'] as String? ?? '',
    customerNotice: j['customerNotice'] as String? ?? '',
    remedies: _strings(j['remedies']),
    singleRemedyReason: j['singleRemedyReason'] as String?,
    contactPhone: j['contactPhone'] as String?,
    contactUrl: j['contactUrl'] as String?,
    orderId: j['orderId'] as String? ?? '',
    storeId: j['storeId'] as String? ?? '',
    channel: j['channel'] as String? ?? 'POS',
    buyerIdentified: j['buyerIdentified'] as bool? ?? false,
    soldAt: _time(j['soldAt']),
    status: j['status'] as String? ?? 'ISSUED',
    remedy: j['remedy'] as String?,
    remedyChosenVia: j['remedyChosenVia'] as String?,
    resolution: j['resolution'] as String?,
    returnId: j['returnId'] as String?,
    resolutionNotes: j['resolutionNotes'] as String?,
    lines: _list(j['lines'], RecallNoticeLine.fromJson),
  );
}

/// How a recall's buyers stand.
class RecallBuyersProgress {
  final int notices;
  final int identified;
  final int unidentified;
  final int remedyChosen;
  final int resolved;
  final Map<String, int> chosen;

  const RecallBuyersProgress({
    this.notices = 0,
    this.identified = 0,
    this.unidentified = 0,
    this.remedyChosen = 0,
    this.resolved = 0,
    this.chosen = const {},
  });

  factory RecallBuyersProgress.fromJson(Map<String, dynamic> j) =>
      RecallBuyersProgress(
        notices: (j['notices'] as num?)?.toInt() ?? 0,
        identified: (j['identified'] as num?)?.toInt() ?? 0,
        unidentified: (j['unidentified'] as num?)?.toInt() ?? 0,
        remedyChosen: (j['remedyChosen'] as num?)?.toInt() ?? 0,
        resolved: (j['resolved'] as num?)?.toInt() ?? 0,
        chosen: {
          for (final e in ((j['chosen'] as Map?) ?? const {}).entries)
            '${e.key}': (e.value as num?)?.toInt() ?? 0,
        },
      );
}

List<String> _strings(Object? v) =>
    v is List ? [for (final e in v) '$e'] : const <String>[];

List<T> _list<T>(Object? v, T Function(Map<String, dynamic>) from) => v is List
    ? [
        for (final e in v)
          if (e is Map) from(e.cast<String, dynamic>()),
      ]
    : <T>[];

DateTime? _time(Object? v) =>
    v is String ? DateTime.tryParse(v)?.toLocal() : null;

String hazardLabel(String hazard) => switch (hazard) {
  'MICROBIOLOGICAL' => 'Microbiological contamination',
  'ALLERGEN' => 'Undeclared allergen',
  'FOREIGN_BODY' => 'Foreign body',
  'CHEMICAL' => 'Chemical contamination',
  'LABELLING' => 'Labelling error',
  'QUALITY' => 'Quality defect',
  _ => 'Other safety issue',
};

String remedyLabel(String remedy) => switch (remedy) {
  'REFUND' => 'a refund',
  'REPLACEMENT' => 'a replacement',
  'REPAIR' => 'a repair',
  _ => remedy.toLowerCase(),
};

/// "a refund or a replacement" — the choice, in words.
String remediesLabel(List<String> remedies) {
  final words = [for (final r in remedies) remedyLabel(r)];
  if (words.length <= 1) return words.join();
  return '${words.sublist(0, words.length - 1).join(', ')} or ${words.last}';
}

String resolutionLabel(String resolution) => switch (resolution) {
  'REFUNDED' => 'Refunded',
  'REPLACED' => 'Replacement given',
  'REPAIRED' => 'Repaired',
  'DECLINED' => 'Wanted nothing',
  _ => resolution,
};

/// What a notice says about its buyer, in one phrase.
String noticeProgressLabel(RecallNotice n) {
  if (n.isResolved) return resolutionLabel(n.resolution ?? '');
  if (n.remedy != null) {
    return 'Chose ${remedyLabel(n.remedy!)}'
        '${n.remedyChosenVia == 'STAFF' ? ' at the counter' : ''}';
  }
  return n.buyerIdentified ? 'Told' : 'Buyer not known';
}

String dispositionLabel(String disposition) => switch (disposition) {
  'RETURNED_TO_SUPPLIER' => 'Returned to supplier',
  'DESTROYED' => 'Destroyed',
  _ => 'Held for collection',
};

/// What a scope line covers, in the words printed on a pack.
String describeScope(RecallScopeLine line) {
  if (line.coversEveryPack) return 'Every pack';
  final parts = <String>[
    if (line.batchNo != null) 'Lot ${line.batchNo}',
    if (line.expiryFrom != null && line.expiryTo != null)
      'dated ${AppFormat.date(line.expiryFrom)} to ${AppFormat.date(line.expiryTo)}'
    else if (line.expiryFrom != null)
      'dated ${AppFormat.date(line.expiryFrom)} or later'
    else if (line.expiryTo != null)
      'dated ${AppFormat.date(line.expiryTo)} or earlier',
  ];
  return parts.join(', ');
}

// ── Selection ────────────────────────────────────────────────────────────────

/// OPEN, CLOSED, CANCELLED, or null for every recall.
final recallStatusFilterProvider = StateProvider<String?>((ref) => 'OPEN');

// ── Readers ──────────────────────────────────────────────────────────────────

final recallsProvider = FutureProvider.autoDispose
    .family<List<RecallSummary>, String?>((ref, status) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(_staffBase, queryParameters: {'status': ?status, 'limit': 100});
      final data = (resp.data['data'] as List?) ?? const [];
      return [
        for (final e in data)
          if (e is Map) RecallSummary.fromJson(e.cast<String, dynamic>()),
      ];
    });

final recallDetailProvider = FutureProvider.autoDispose
    .family<RecallDetail, String>((ref, id) async {
      final resp = await ref.read(apiClientProvider).dio.get('$_staffBase/$id');
      return RecallDetail.fromJson(
        (resp.data['data'] as Map).cast<String, dynamic>(),
      );
    });

/// A recall's notices to buyers, from order-svc, which knows who placed each
/// order. Up to a hundred; the progress figures count every one.
final recallNoticesProvider = FutureProvider.autoDispose
    .family<List<RecallNotice>, String>((ref, recallId) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(
            _noticesBase,
            queryParameters: {'recallId': recallId, 'limit': 100},
          );
      final data = (resp.data['data'] as List?) ?? const [];
      return [
        for (final e in data)
          if (e is Map) RecallNotice.fromJson(e.cast<String, dynamic>()),
      ];
    });

final recallBuyersProgressProvider = FutureProvider.autoDispose
    .family<RecallBuyersProgress, String>((ref, recallId) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get('$_noticesBase/progress', queryParameters: {'recallId': recallId});
      return RecallBuyersProgress.fromJson(
        (resp.data['data'] as Map).cast<String, dynamic>(),
      );
    });

// ── Writes ───────────────────────────────────────────────────────────────────

Future<RecallDetail> openRecall(
  Dio dio, {
  required String reference,
  required String kind,
  required String hazard,
  required String reason,
  String? customerNotice,
  required String source,
  String? sourceReference,
  required List<RecallScopeLine> items,
  List<String> remedies = const [],
  String? singleRemedyReason,
  String? contactPhone,
  String? contactUrl,
  String? soldFrom,
}) async {
  String? text(String? v) =>
      v == null || v.trim().isEmpty ? null : v.trim();
  final resp = await dio.post(
    _setupBase,
    data: {
      'reference': reference.trim(),
      'kind': kind,
      'hazard': hazard,
      'reason': reason.trim(),
      if (customerNotice != null && customerNotice.trim().isNotEmpty)
        'customerNotice': customerNotice.trim(),
      'source': source,
      if (sourceReference != null && sourceReference.trim().isNotEmpty)
        'sourceReference': sourceReference.trim(),
      'items': [for (final i in items) i.toJson()],
      if (remedies.isNotEmpty) 'remedies': remedies,
      'singleRemedyReason': ?text(singleRemedyReason),
      'contactPhone': ?text(contactPhone),
      'contactUrl': ?text(contactUrl),
      'soldFrom': ?text(soldFrom),
    },
  );
  return RecallDetail.fromJson(
    (resp.data['data'] as Map).cast<String, dynamic>(),
  );
}

Future<void> recordRecallStoreAction(
  Dio dio, {
  required String recallId,
  required String storeId,
  required double qtyFound,
  required String disposition,
  required bool noticeDisplayed,
  String? notes,
}) => dio.post(
  '$_staffBase/$recallId/stores/$storeId/actions',
  data: {
    'qtyFound': qtyFound,
    'disposition': disposition,
    'noticeDisplayed': noticeDisplayed,
    if (notes != null && notes.trim().isNotEmpty) 'notes': notes.trim(),
  },
);

Future<void> releaseRecalledBatch(
  Dio dio, {
  required String recallId,
  required String batchId,
  required String reason,
}) => dio.post(
  '$_staffBase/$recallId/batches/$batchId/release',
  data: {'reason': reason.trim()},
);

Future<void> closeRecall(Dio dio, {required String recallId, String? notes}) =>
    dio.post(
      '$_setupBase/$recallId/close',
      data: {
        if (notes != null && notes.trim().isNotEmpty) 'notes': notes.trim(),
      },
    );

Future<void> cancelRecall(
  Dio dio, {
  required String recallId,
  required String reason,
}) => dio.post('$_setupBase/$recallId/cancel', data: {'reason': reason.trim()});

/// Staff record the remedy a buyer chose at the counter.
Future<RecallNotice> chooseRecallRemedy(
  Dio dio, {
  required String noticeId,
  required String remedy,
}) async {
  final resp = await dio.post(
    '$_noticesBase/$noticeId/remedy',
    data: {'remedy': remedy},
  );
  return RecallNotice.fromJson((resp.data['data'] as Map).cast<String, dynamic>());
}

/// Staff settle a notice other than by a refund: REPLACED, REPAIRED or DECLINED.
Future<RecallNotice> resolveRecallNotice(
  Dio dio, {
  required String noticeId,
  required String resolution,
  String? notes,
}) async {
  final resp = await dio.post(
    '$_noticesBase/$noticeId/resolve',
    data: {
      'resolution': resolution,
      if (notes != null && notes.trim().isNotEmpty) 'notes': notes.trim(),
    },
  );
  return RecallNotice.fromJson((resp.data['data'] as Map).cast<String, dynamic>());
}

/// The refund: a return of the recalled lines against the order, naming the
/// notice, so the goods, the money and the notice are settled together.
Future<void> refundRecallNotice(Dio dio, {required RecallNotice notice}) =>
    dio.post(
      '/${ApiConstants.order}/orders/${notice.orderId}/returns',
      data: {
        'reason': 'Product safety recall ${notice.reference}',
        'recallNoticeId': notice.id,
        'items': [
          for (final l in notice.lines)
            {'variantId': l.variantId, 'qty': l.qty},
        ],
      },
    );
