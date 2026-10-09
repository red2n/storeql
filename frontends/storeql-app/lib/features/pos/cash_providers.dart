import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
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
/// is open, and not retried behind the cashier's back. This holds what the
/// screen shows; the drawer the sales name is [saleTillProvider], which the
/// screen keeps in step (what it reads, and what it opens or closes).
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

/// The person signed in, or null when nobody is. A change of person is what
/// [saleTillProvider] must start again for; a refreshed token of the same person
/// is not.
final _signedInUserProvider = Provider<String?>((ref) => ref.watch(
      authNotifierProvider.select((a) {
        final auth = a.value;
        return auth is AuthAuthenticated && auth.userId.isNotEmpty
            ? auth.userId
            : null;
      }),
    ));

/// The till session this terminal's sales are counted in: the open one of **the
/// person signed in**, at this store, or null when there is none.
///
/// Every tender, and every return, a sale sends names it, so the drawer's X
/// report and close count exactly the money that went through it. It lives for
/// as long as the terminal is on and follows three things:
///
///  * **who and where** — it is read from payment-svc (the till *this person*
///    opened at *this store*) when the terminal starts and whenever the store or
///    the person changes, so the next cashier never rings on the last one's
///    drawer, and a drawer of another store is never named;
///  * **what the Cash screen sees** — opening or closing a till there sets it at
///    once (even when the cashier has left that tab by the time the answer
///    comes), and every answer the Cash screen reads from the server is
///    published here ([set]), so a drawer opened on another device is named too;
///  * **what the server refuses** — a tender it will not count in a drawer
///    ([refused]) sends the terminal to read again.
///
/// A read that fails is **not** "no till": it is an error, read again at the
/// next sale ([settle]). Nothing here ever stops a sale: when the drawer cannot
/// be read, within a few seconds, the sale goes unattributed ("not at a till" on
/// the report), and if one has gone stale the server's refusal is answered by
/// sending the tender again naming none ([postTender]). Read it as
/// `state.drawer`, never `state.value`, which would hand back the previous
/// person's or store's drawer while a new one is being read.
final saleTillProvider = AsyncNotifierProvider<SaleTillNotifier, String?>(
    SaleTillNotifier.new,
    retry: (_, _) => null);

/// What a sale, a return or a pay-out names as its drawer: the open till as
/// read for this store and this person, and nothing while that is being read
/// again, has failed, or was last read for someone else.
extension SaleDrawer on AsyncValue<String?> {
  String? get drawer => unwrapPrevious().value;
}

class SaleTillNotifier extends AsyncNotifier<String?> {
  /// How long a sale waits for the drawer to be read before it is rung without
  /// one. Shorter than the client's own timeouts: a cashier is not made to wait
  /// for a payment service that is down.
  static const readWait = Duration(seconds: 3);

  /// How many times [set] has been called, and the last value: an answer the
  /// server is still working on is older than a till opened or closed here
  /// meanwhile, and must not undo it.
  int _sets = 0;
  String? _lastSet;

  @override
  Future<String?> build() async {
    final storeId = ref.watch(posStoreProvider);
    final userId = ref.watch(_signedInUserProvider);
    if (storeId == null || userId == null) return null;
    final before = _sets;
    try {
      // A failure is thrown, not turned into null: "no till is open" is the
      // server's 404 alone ([fetchOpenTill]).
      final read =
          await fetchOpenTill(ref.read(apiClientProvider).dio, storeId);
      return _sets == before ? read : _lastSet;
    } catch (_) {
      if (_sets != before) return _lastSet;
      rethrow;
    }
  }

  /// The drawer to name now ([SaleDrawer.drawer]).
  String? get drawer => state.drawer;

  /// Whether the drawer has been read for this store and this person: a sale
  /// that finds it settled needs nothing more.
  bool get settled => state.unwrapPrevious() is AsyncData<String?>;

  /// Gives a read still in flight, or one that failed, a moment before a sale
  /// names its drawer: waits for the one in flight, reads again once after a
  /// failure (its own or the one it waited for), and gives up after [wait] in
  /// all (a read still going lands for the next sale). Never throws and never
  /// stops a sale.
  Future<void> settle({Duration wait = readWait}) async {
    final watch = Stopwatch()..start();
    for (var tries = 0; tries < 2 && !settled; tries++) {
      final left = wait - watch.elapsed;
      if (left <= Duration.zero) return;
      if (!state.isLoading) ref.invalidateSelf();
      try {
        await future.timeout(left);
      } catch (_) {
        // Unreadable (or too slow): the sale is rung naming none, as the
        // Decision of 2026-10-09 says, and a later sale reads again.
      }
    }
  }

  /// A till was opened here, or the server's answer is [id] (null: none open).
  /// Idempotent: an answer already held is not a change.
  void set(String? id) {
    _sets++;
    _lastSet = id;
    final now = state;
    if (now is AsyncData<String?> && !now.isLoading && now.value == id) return;
    state = AsyncData(id);
  }

  void opened(String sessionId) => set(sessionId);

  void closed() => set(null);

  /// The server would not count a tender in [sessionId] (closed, another
  /// store's, unknown): if that is the drawer held, it is stale. Read again, so
  /// the next sale names the drawer open now, or none.
  void refused(String sessionId) {
    if (drawer != sessionId) return;
    ref.invalidateSelf();
    // Asking for it starts the read now, though nothing is listening just now.
    future.then<void>((_) {}, onError: (_) {});
  }
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

  /// On a drawer opened on the session basis: the net cash the store took in the
  /// window that names no drawer (online, back-office), which is in no drawer's
  /// expected cash. Null when there is none, or on the window basis.
  final double? cashNotAtTill;

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
    this.cashNotAtTill,
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
        cashNotAtTill: _cashNotAtTill(j['notAtTill']),
      );

  static double? _cashNotAtTill(Object? notAtTill) {
    if (notAtTill is! Map) return null;
    final cash = notAtTill['CASH'];
    if (cash is! Map) return null;
    final net = (cash['net'] as num?)?.toDouble();
    return net == null || net == 0 ? null : net;
  }
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
