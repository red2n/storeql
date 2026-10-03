import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import 'pos_providers.dart';

/// The till session open at this terminal's store (its id), or null when there
/// is none.
///
/// The server holds the open till, not the app: after a reload, or on another
/// device at the same counter, the session is still open there. So this is
/// read from payment-svc each time the Cash screen opens (it is disposed when
/// the screen goes) and whenever the store changes; opening or closing a till
/// on this screen sets it at once. A failed read is an error the screen shows
/// beside *Open till*, with its own *Try again* — never a guess that no till
/// is open, and not retried behind the cashier's back.
final activeTillProvider =
    AsyncNotifierProvider.autoDispose<ActiveTillNotifier, String?>(
        ActiveTillNotifier.new,
        retry: (_, _) => null);

class ActiveTillNotifier extends AsyncNotifier<String?> {
  @override
  Future<String?> build() async {
    final storeId = ref.watch(posStoreProvider);
    if (storeId == null) return null;
    return fetchOpenTill(ref.read(apiClientProvider).dio, storeId);
  }

  /// A till was just opened here.
  void opened(String sessionId) => state = AsyncData(sessionId);

  /// The till was just closed.
  void closed() => state = const AsyncData(null);
}

/// The caller's open till session at [storeId]: its id, or null when the
/// server answers 404 (`TILL_SESSION_NOT_OPEN`, no till open there). Any other
/// failure is thrown.
Future<String?> fetchOpenTill(Dio dio, String storeId) async {
  try {
    final resp = await dio.get(
      '/${ApiConstants.payment}/admin/cash/till-sessions/current',
      queryParameters: {'storeId': storeId},
    );
    final session = (resp.data as Map)['data'];
    if (session is! Map) return null;
    // The same shape as GET /till-sessions/{id}: a closed one is no open till.
    final status = (session['status'] as String?)?.toUpperCase();
    if (status != null && status != 'OPEN') return null;
    final id = session['id'] as String?;
    return id == null || id.isEmpty ? null : id;
  } on DioException catch (e) {
    if (e.response?.statusCode == 404) return null;
    rethrow;
  }
}

class TillReport {
  final String tillSessionId;
  final double floatAmount;
  final double cashDropsTotal;
  final double expectedCashInTill;
  final double grossSales;
  final double totalRefunds;
  final double netSales;

  /// Every term of the expected cash, as payment-svc counts them: the session's
  /// own store, in the window it was open. Zero when the server names none.
  final double cashSales;
  final double cashRefunds;
  final double payIns;
  final double payOuts;

  /// Set on a closed session: what was counted and what that leaves over
  /// (positive) or short (negative).
  final double? countedCash;
  final double? overShort;

  /// The closer's note on a difference; null on a live X report.
  final String? note;

  const TillReport({
    required this.tillSessionId,
    required this.floatAmount,
    required this.cashDropsTotal,
    required this.expectedCashInTill,
    required this.grossSales,
    required this.totalRefunds,
    required this.netSales,
    this.cashSales = 0,
    this.cashRefunds = 0,
    this.payIns = 0,
    this.payOuts = 0,
    this.countedCash,
    this.overShort,
    this.note,
  });

  factory TillReport.fromJson(Map<String, dynamic> j) => TillReport(
        tillSessionId: j['tillSessionId'] as String? ?? '',
        floatAmount: (j['floatAmount'] as num?)?.toDouble() ?? 0,
        cashDropsTotal: (j['cashDropsTotal'] as num?)?.toDouble() ?? 0,
        expectedCashInTill: (j['expectedCashInTill'] as num?)?.toDouble() ?? 0,
        grossSales: (j['grossSales'] as num?)?.toDouble() ?? 0,
        totalRefunds: (j['totalRefunds'] as num?)?.toDouble() ?? 0,
        netSales: (j['netSales'] as num?)?.toDouble() ?? 0,
        cashSales: (j['cashSales'] as num?)?.toDouble() ?? 0,
        cashRefunds: (j['cashRefunds'] as num?)?.toDouble() ?? 0,
        payIns: (j['payIns'] as num?)?.toDouble() ?? 0,
        payOuts: (j['payOuts'] as num?)?.toDouble() ?? 0,
        countedCash: (j['countedCash'] as num?)?.toDouble(),
        overShort: (j['overShort'] as num?)?.toDouble(),
        note: j['note'] as String?,
      );
}

/// Live X-report for an open till session.
final xReportProvider =
    FutureProvider.autoDispose.family<TillReport, String>((ref, sessionId) async {
  final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.payment}/admin/cash/till-sessions/$sessionId/x-report');
  return TillReport.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// A store's day settled (payment-svc `z-report`): every term of the expected
/// cash, the day it covers in the store's own zone, and which version of the
/// day this is. Append-only: a correction is the next version.
class DayReport {
  final String id;
  final String businessDate;
  final double totalSales;
  final double totalRefunds;
  final double netSales;
  final double openingFloat;
  final double cashSales;
  final double cashRefunds;
  final double payIns;
  final double payOuts;
  final double cashDrops;
  final double expectedCash;
  final double countedCash;
  final double overShort;
  final int transactionCount;
  final String currency;
  final int version;
  final String? correctionReason;

  /// The IANA zone the day was counted in, and whether it was assumed (UTC)
  /// because the store's own could not be read.
  final String? timeZone;
  final bool zoneAssumed;

  /// True when this request wrote it; false when a stored report was answered.
  final bool regenerated;

  const DayReport({
    required this.id,
    required this.businessDate,
    required this.totalSales,
    required this.totalRefunds,
    required this.netSales,
    required this.openingFloat,
    required this.cashSales,
    required this.cashRefunds,
    required this.payIns,
    required this.payOuts,
    required this.cashDrops,
    required this.expectedCash,
    required this.countedCash,
    required this.overShort,
    required this.transactionCount,
    required this.currency,
    required this.version,
    this.correctionReason,
    this.timeZone,
    this.zoneAssumed = false,
    this.regenerated = false,
  });

  factory DayReport.fromJson(Map<String, dynamic> j) {
    double n(String k) => (j[k] as num?)?.toDouble() ?? 0;
    return DayReport(
      id: j['id'] as String? ?? '',
      businessDate: j['businessDate'] as String? ?? '',
      totalSales: n('totalSales'),
      totalRefunds: n('totalRefunds'),
      netSales: n('netSales'),
      openingFloat: n('openingFloat'),
      cashSales: n('cashSales'),
      cashRefunds: n('cashRefunds'),
      payIns: n('payIns'),
      payOuts: n('payOuts'),
      cashDrops: n('cashDrops'),
      expectedCash: n('expectedCash'),
      countedCash: n('countedCash'),
      overShort: n('overShort'),
      transactionCount: (j['transactionCount'] as num?)?.toInt() ?? 0,
      currency: j['currency'] as String? ?? '',
      version: (j['version'] as num?)?.toInt() ?? 1,
      correctionReason: j['correctionReason'] as String?,
      timeZone: j['timeZone'] as String?,
      zoneAssumed: j['zoneAssumed'] as bool? ?? false,
      regenerated: j['regenerated'] as bool? ?? false,
    );
  }
}

/// A clocked-in cashier at a store (iam-svc `GET /auth/pos/sessions`), as a
/// manager sees it before ending it.
class OpenPosSession {
  final String id;
  final String userId;
  final String storeId;
  final String startedAt;
  final String? lastActivityAt;

  const OpenPosSession({
    required this.id,
    required this.userId,
    required this.storeId,
    required this.startedAt,
    this.lastActivityAt,
  });

  factory OpenPosSession.fromJson(Map<String, dynamic> j) => OpenPosSession(
        id: j['id'] as String? ?? '',
        userId: j['userId'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        startedAt: j['startedAt'] as String? ?? '',
        lastActivityAt: j['lastActivityAt'] as String?,
      );
}

/// The open till sessions at [storeId]: management's list (a cashier is
/// refused). Only sessions still open are shown.
final openPosSessionsProvider = FutureProvider.autoDispose
    .family<List<OpenPosSession>, String>((ref, storeId) async {
  final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.iam}/auth/pos/sessions',
      queryParameters: {'storeId': storeId});
  return [
    for (final e in (resp.data['data'] as List?) ?? const [])
      if (e is Map<String, dynamic> &&
          (e['status'] as String?)?.toUpperCase() != 'ENDED' &&
          (e['status'] as String?)?.toUpperCase() != 'EXPIRED')
        OpenPosSession.fromJson(e),
  ];
});
