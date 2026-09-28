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

  const TillReport({
    required this.tillSessionId,
    required this.floatAmount,
    required this.cashDropsTotal,
    required this.expectedCashInTill,
    required this.grossSales,
    required this.totalRefunds,
    required this.netSales,
  });

  factory TillReport.fromJson(Map<String, dynamic> j) => TillReport(
        tillSessionId: j['tillSessionId'] as String? ?? '',
        floatAmount: (j['floatAmount'] as num?)?.toDouble() ?? 0,
        cashDropsTotal: (j['cashDropsTotal'] as num?)?.toDouble() ?? 0,
        expectedCashInTill: (j['expectedCashInTill'] as num?)?.toDouble() ?? 0,
        grossSales: (j['grossSales'] as num?)?.toDouble() ?? 0,
        totalRefunds: (j['totalRefunds'] as num?)?.toDouble() ?? 0,
        netSales: (j['netSales'] as num?)?.toDouble() ?? 0,
      );
}

/// Live X-report for an open till session.
final xReportProvider =
    FutureProvider.autoDispose.family<TillReport, String>((ref, sessionId) async {
  final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.payment}/admin/cash/till-sessions/$sessionId/x-report');
  return TillReport.fromJson(resp.data['data'] as Map<String, dynamic>);
});
