import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../shared/util/status_labels.dart';
import 'admin_shell.dart' show adminRoutes;

// ---------------------------------------------------------------------------
// System health: how the system is doing right now, for the people who run it.
//
// Three reads, all the business's own and all by the verified token (the page
// names no business): the gateway's request counters and recent failures, and
// reporting-svc's count of work waiting for a person. One controller asks them
// every five seconds, never while an earlier ask is still out, only while the
// screen is showing, and keeps the last good figures when an ask fails.
// ---------------------------------------------------------------------------

/// The gateway's own routes for this screen: versioned only, the unversioned
/// alias is not served for them.
const systemHealthBase = '/v1/system-health';

/// reporting-svc's waiting-work read, addressed as the app addresses its other
/// reports. The one place the path is written.
const waitingWorkPath = '/${ApiConstants.reporting}/admin/reports/system-health/waiting-work';

/// The permission that opens the screen; owners always hold it.
const systemHealthPermission = 'system.health';

/// How often the screen asks. A provider so a test can make it short.
final systemHealthPollIntervalProvider = Provider<Duration>((_) => const Duration(seconds: 5));

/// A failure list page asks for this many; the server's default.
const _failuresPageSize = 20;

// ── the verdict ─────────────────────────────────────────────────────────────

/// Below this share of failed requests in the last five minutes the system is
/// healthy.
const healthyBelow = 0.01;

/// Below this (and from [healthyBelow]) it is degraded; from here, unhealthy.
const degradedBelow = 0.05;

enum HealthVerdict {
  noTraffic('No traffic yet', StatusTone.neutral),
  healthy('Healthy', StatusTone.success),
  degraded('Degraded', StatusTone.warning),
  unhealthy('Unhealthy', StatusTone.error);

  const HealthVerdict(this.label, this.tone);
  final String label;
  final StatusTone tone;
}

/// The verdict on [window] (the last five minutes): nothing asked is *No
/// traffic yet*, never *Healthy*.
HealthVerdict healthVerdict(HealthWindow window) {
  final rate = window.rate;
  if (window.total == 0 || rate == null) return HealthVerdict.noTraffic;
  if (rate < healthyBelow) return HealthVerdict.healthy;
  if (rate < degradedBelow) return HealthVerdict.degraded;
  return HealthVerdict.unhealthy;
}

// ── what the services send ──────────────────────────────────────────────────

int _int(Object? v) => (v as num?)?.toInt() ?? 0;

DateTime? _instant(Object? v) => switch (v) {
      String s => DateTime.tryParse(s),
      num n => DateTime.fromMillisecondsSinceEpoch(n.toInt(), isUtc: true),
      _ => null,
    };

Map<String, dynamic> _map(Object? v) => v is Map ? v.cast<String, dynamic>() : const {};

List<T> _list<T>(Object? v, T Function(Map<String, dynamic>) read) =>
    [for (final e in (v as List?) ?? const []) read(_map(e))];

/// Requests over one stretch of time.
class HealthWindow {
  final int total;
  final int succeeded;
  final int failed;

  /// Answers that were a normal business reply (not found, invalid, conflict).
  final int clientErrors;

  /// 0..1, or null when nothing was asked.
  final double? failureRate;

  const HealthWindow({
    required this.total,
    required this.succeeded,
    required this.failed,
    required this.clientErrors,
    required this.failureRate,
  });

  factory HealthWindow.fromJson(Map<String, dynamic> j) => HealthWindow(
        total: _int(j['total']),
        succeeded: _int(j['succeeded']),
        failed: _int(j['failed']),
        clientErrors: _int(j['clientErrors']),
        failureRate: (j['failureRate'] as num?)?.toDouble(),
      );

  static const empty = HealthWindow(total: 0, succeeded: 0, failed: 0, clientErrors: 0, failureRate: null);

  /// The rate the server sent, else worked out from the counts; null for none.
  double? get rate => failureRate ?? (total == 0 ? null : failed / total);
}

class HealthGroup {
  final String group;
  final int total;
  final int failed;

  const HealthGroup({required this.group, required this.total, required this.failed});

  factory HealthGroup.fromJson(Map<String, dynamic> j) => HealthGroup(
        group: j['group'] as String? ?? '',
        total: _int(j['total']),
        failed: _int(j['failed']),
      );
}

/// One minute's or one hour's requests, for the bar strips.
class HealthPoint {
  final DateTime? at;
  final int total;
  final int failed;

  const HealthPoint({required this.at, required this.total, required this.failed});

  factory HealthPoint.fromJson(Map<String, dynamic> j) =>
      HealthPoint(at: _instant(j['at']), total: _int(j['total']), failed: _int(j['failed']));
}

class HealthSummary {
  final DateTime? generatedAt;

  /// False when the gateway could not read its counters: the numbers are then
  /// empty, not zero.
  final bool available;
  final int droppedSinceStart;
  final HealthWindow last5Minutes;
  final HealthWindow lastHour;
  final HealthWindow last24Hours;
  final List<HealthGroup> byGroup;
  final List<HealthPoint> perMinute;
  final List<HealthPoint> perHour;

  const HealthSummary({
    required this.generatedAt,
    required this.available,
    required this.droppedSinceStart,
    required this.last5Minutes,
    required this.lastHour,
    required this.last24Hours,
    required this.byGroup,
    required this.perMinute,
    required this.perHour,
  });

  factory HealthSummary.fromJson(Map<String, dynamic> j) {
    final windows = _map(j['windows']);
    HealthWindow window(String key) =>
        windows[key] is Map ? HealthWindow.fromJson(_map(windows[key])) : HealthWindow.empty;
    return HealthSummary(
      generatedAt: _instant(j['generatedAt']),
      available: j['available'] != false,
      droppedSinceStart: _int(j['droppedSinceStart']),
      last5Minutes: window('last5Minutes'),
      lastHour: window('lastHour'),
      last24Hours: window('last24Hours'),
      byGroup: _list(j['byGroup'], HealthGroup.fromJson),
      perMinute: _list(j['perMinute'], HealthPoint.fromJson),
      perHour: _list(j['perHour'], HealthPoint.fromJson),
    );
  }
}

/// One failed or refused request, as the gateway recorded it: no body, no
/// query, only its pattern.
class FailureEntry {
  final DateTime? at;
  final String requestId;
  final String method;
  final String routePattern;
  final String group;
  final int? status;
  final String? code;
  final String? userId;
  final int? ms;

  const FailureEntry({
    required this.at,
    required this.requestId,
    required this.method,
    required this.routePattern,
    required this.group,
    required this.status,
    required this.code,
    required this.userId,
    required this.ms,
  });

  factory FailureEntry.fromJson(Map<String, dynamic> j) => FailureEntry(
        at: _instant(j['at']),
        requestId: j['requestId'] as String? ?? '',
        method: j['method'] as String? ?? '',
        routePattern: j['routePattern'] as String? ?? '',
        group: j['group'] as String? ?? '',
        status: (j['status'] as num?)?.toInt(),
        code: j['code'] as String?,
        userId: j['userId'] as String?,
        ms: (j['ms'] as num?)?.toInt(),
      );
}

class FailuresPage {
  final List<FailureEntry> items;
  final String? nextCursor;

  const FailuresPage(this.items, this.nextCursor);
}

/// One queue of work that stays where it is until a person decides.
class WaitingItem {
  final String kind;
  final String label;

  /// Null when the owning service could not be reached: unknown, never zero.
  final int? count;

  /// True when the owning service counted only up to its cap and stopped, so
  /// [count] is "this many or more". The server says so; the app knows no cap.
  /// Never true for an unknown count.
  final bool capped;

  /// Where the server says the work is settled; see [waitingWorkRoute].
  final String? opens;
  final String? note;

  const WaitingItem({
    required this.kind,
    required this.label,
    required this.count,
    this.capped = false,
    required this.opens,
    required this.note,
  });
}

class WaitingWork {
  final DateTime? generatedAt;
  final List<WaitingItem> items;
  final List<String> unreachable;

  const WaitingWork({required this.generatedAt, required this.items, required this.unreachable});

  factory WaitingWork.fromJson(Map<String, dynamic> j) {
    final unreachable = [for (final k in (j['unreachable'] as List?) ?? const []) '$k'];
    return WaitingWork(
      generatedAt: _instant(j['generatedAt']),
      unreachable: unreachable,
      items: _list(j['items'], (e) {
        final kind = e['kind'] as String? ?? '';
        final label = (e['label'] as String? ?? '').trim();
        final note = (e['note'] as String? ?? '').trim();
        // A queue named unreachable is unknown even if a number came with it.
        final count = unreachable.contains(kind) ? null : (e['count'] as num?)?.toInt();
        return WaitingItem(
          kind: kind,
          label: label.isEmpty ? humanizeCode(kind) : label,
          count: count,
          // Absent means counted in full; nothing known, nothing to be capped.
          capped: count != null && e['capped'] == true,
          opens: e['opens'] as String?,
          note: note.isEmpty ? null : note,
        );
      }),
    );
  }
}

// ── where a queue is settled ────────────────────────────────────────────────

/// The back-office screen that settles each queue, by the app's own address.
/// Procurement's tabs are named by `?tab=` (`ProcurementScreen.tabNames`).
const _waitingRoutes = {
  'PURCHASE_ORDER_APPROVAL': '/admin/procurement?tab=purchase-orders',
  'PAYMENT_RUN': '/admin/procurement?tab=payments',
  'SUPPLIER_INVOICE': '/admin/procurement?tab=invoices',
  'ACCOUNTING_SYNC': '/admin/integrations',
  'PRIVACY_REQUEST': '/admin/privacy',
};

/// Queues with no screen of their own yet: a tile with no link, whatever
/// address the server offers. Card refund dues are decided at the card machine
/// and have no page in the back office.
const _waitingWithoutScreen = {'CARD_REFUND'};

/// Where a tile leads, or null when the app has no page for it. The app's own
/// address for a known queue wins over the server's `opens`; a queue this app
/// has not met leads where the server says only if that is a page the app has.
String? waitingWorkRoute(String kind, String? opens) {
  if (_waitingWithoutScreen.contains(kind)) return null;
  final own = _waitingRoutes[kind];
  if (own != null) return own;
  final path = (opens ?? '').split('?').first;
  return adminRoutes.contains(path) ? opens : null;
}

// ── the reads ───────────────────────────────────────────────────────────────

class SystemHealthApi {
  SystemHealthApi(this._dio);

  final Dio _dio;

  Map<String, dynamic> _data(Response<dynamic> r) {
    final body = r.data;
    final data = body is Map ? body['data'] : null;
    if (data is! Map) throw const FormatException('no data in the answer');
    return data.cast<String, dynamic>();
  }

  Future<HealthSummary> summary() async =>
      HealthSummary.fromJson(_data(await _dio.get('$systemHealthBase/summary')));

  Future<FailuresPage> failures({int limit = _failuresPageSize, String? after}) async {
    final data = _data(await _dio.get(
      '$systemHealthBase/failures',
      queryParameters: {'limit': limit, 'after': ?after},
    ));
    return FailuresPage(_list(data['items'], FailureEntry.fromJson), data['nextCursor'] as String?);
  }

  Future<WaitingWork> waitingWork() async => WaitingWork.fromJson(_data(await _dio.get(waitingWorkPath)));
}

final systemHealthApiProvider =
    Provider<SystemHealthApi>((ref) => SystemHealthApi(ref.watch(apiClientProvider).dio));

// ── the controller ──────────────────────────────────────────────────────────

/// Why the server turned the screen away.
enum SystemHealthRefusal { notPermitted, businessWideOnly }

SystemHealthRefusal? _refusalOf(Object? error) => switch (error is DioException ? apiErrorCode(error) : null) {
      'SYSTEM_HEALTH_NOT_PERMITTED' => SystemHealthRefusal.notPermitted,
      'BUSINESS_WIDE_ONLY' => SystemHealthRefusal.businessWideOnly,
      _ => null,
    };

const _keep = Object();

class SystemHealthState {
  final HealthSummary? summary;

  /// The newest failures as the last refresh read them.
  final List<FailureEntry> latest;
  final String? latestCursor;

  /// Pages added by *Load more*, older than [latest].
  final List<FailureEntry> older;
  final String? olderCursor;
  final bool olderLoaded;
  final bool failuresRead;
  final WaitingWork? waiting;

  /// Whether the first round of reads has finished, well or badly.
  final bool settled;
  final Object? summaryError;
  final Object? failuresError;
  final Object? waitingError;
  final bool loadingMore;
  final Object? loadMoreError;

  /// Whether the last round of reads failed in any part.
  final bool refreshFailed;

  /// When the last round that went wholly well finished.
  final DateTime? updatedAt;
  final SystemHealthRefusal? refusal;

  const SystemHealthState({
    this.summary,
    this.latest = const [],
    this.latestCursor,
    this.older = const [],
    this.olderCursor,
    this.olderLoaded = false,
    this.failuresRead = false,
    this.waiting,
    this.settled = false,
    this.summaryError,
    this.failuresError,
    this.waitingError,
    this.loadingMore = false,
    this.loadMoreError,
    this.refreshFailed = false,
    this.updatedAt,
    this.refusal,
  });

  SystemHealthState copyWith({
    Object? summary = _keep,
    List<FailureEntry>? latest,
    Object? latestCursor = _keep,
    List<FailureEntry>? older,
    Object? olderCursor = _keep,
    bool? olderLoaded,
    bool? failuresRead,
    Object? waiting = _keep,
    bool? settled,
    Object? summaryError = _keep,
    Object? failuresError = _keep,
    Object? waitingError = _keep,
    bool? loadingMore,
    Object? loadMoreError = _keep,
    bool? refreshFailed,
    Object? updatedAt = _keep,
    SystemHealthRefusal? refusal,
  }) =>
      SystemHealthState(
        summary: summary == _keep ? this.summary : summary as HealthSummary?,
        latest: latest ?? this.latest,
        latestCursor: latestCursor == _keep ? this.latestCursor : latestCursor as String?,
        older: older ?? this.older,
        olderCursor: olderCursor == _keep ? this.olderCursor : olderCursor as String?,
        olderLoaded: olderLoaded ?? this.olderLoaded,
        failuresRead: failuresRead ?? this.failuresRead,
        waiting: waiting == _keep ? this.waiting : waiting as WaitingWork?,
        settled: settled ?? this.settled,
        summaryError: summaryError == _keep ? this.summaryError : summaryError,
        failuresError: failuresError == _keep ? this.failuresError : failuresError,
        waitingError: waitingError == _keep ? this.waitingError : waitingError,
        loadingMore: loadingMore ?? this.loadingMore,
        loadMoreError: loadMoreError == _keep ? this.loadMoreError : loadMoreError,
        refreshFailed: refreshFailed ?? this.refreshFailed,
        updatedAt: updatedAt == _keep ? this.updatedAt : updatedAt as DateTime?,
        refusal: refusal ?? this.refusal,
      );

  /// The failures to list: the newest page, then the pages loaded after it,
  /// each request once however often the newest page is read again.
  List<FailureEntry> get failures {
    final seen = {for (final f in latest) f.requestId};
    return [...latest, ...older.where((f) => seen.add(f.requestId))];
  }

  /// The cursor of the next older page, or null at the end.
  String? get moreCursor => olderLoaded ? olderCursor : latestCursor;

  /// The people the failures name, as one stable key for name look-ups.
  Set<String> get failureUserIds => {
        for (final f in failures)
          if (f.userId != null && f.userId!.isNotEmpty) f.userId!,
      };

  /// Nothing has been read and something failed: the page has nothing to show.
  bool get nothingLoaded =>
      summary == null && !failuresRead && waiting == null && (summaryError != null || failuresError != null || waitingError != null);
}

typedef _Attempt<T> = ({T? value, Object? error});

Future<_Attempt<T>> _attempt<T>(Future<T> Function() read) async {
  try {
    return (value: await read(), error: null);
  } catch (e) {
    return (value: null, error: e);
  }
}

final systemHealthProvider =
    NotifierProvider.autoDispose<SystemHealthController, SystemHealthState>(SystemHealthController.new);

class SystemHealthController extends Notifier<SystemHealthState> {
  Timer? _timer;
  bool _inFlight = false;
  bool _visible = false;

  @override
  SystemHealthState build() {
    ref.onDispose(() => _timer?.cancel());
    return const SystemHealthState();
  }

  /// Tells the controller whether the screen can be seen. Hidden, it stops
  /// asking; shown again, it asks at once and then on the clock. Starts hidden,
  /// so a screen that is not showing never asks at all.
  void setVisible(bool visible) {
    if (visible == _visible) return;
    _visible = visible;
    _timer?.cancel();
    _timer = null;
    if (!visible || state.refusal != null) return;
    refresh();
    _timer = Timer.periodic(ref.read(systemHealthPollIntervalProvider), (_) => refresh());
  }

  /// One round of reads, unless one is still out (a slow answer is waited for,
  /// never doubled up on) or the server has turned the screen away.
  Future<void> refresh() async {
    if (_inFlight || state.refusal != null) return;
    _inFlight = true;
    try {
      await _round();
    } finally {
      _inFlight = false;
    }
  }

  Future<void> _round() async {
    final api = ref.read(systemHealthApiProvider);

    // The gateway's own read first: it judges the permission, and a refusal
    // means nothing else is asked.
    final s = await _attempt(api.summary);
    if (!ref.mounted) return;
    if (_refuse(s.error)) return;
    final summary = s.value;
    state = s.error == null
        ? state.copyWith(summary: summary, summaryError: null)
        : state.copyWith(summaryError: s.error);

    // Failures come from the same counters: with those unreadable the list
    // cannot say there are none, so it is not asked.
    final unavailable = summary != null && !summary.available;
    final failuresRead = unavailable ? null : _attempt(() => api.failures());
    final waitingRead = _attempt(api.waitingWork);
    final f = await failuresRead;
    final w = await waitingRead;
    if (!ref.mounted) return;
    if (_refuse(f?.error) || _refuse(w.error)) return;

    var next = state;
    if (f != null) {
      final page = f.value;
      next = page == null
          ? next.copyWith(failuresError: f.error)
          : next.copyWith(
              latest: page.items,
              latestCursor: page.nextCursor,
              failuresRead: true,
              failuresError: null,
            );
    }
    final waiting = w.value;
    next = waiting == null
        ? next.copyWith(waitingError: w.error)
        : next.copyWith(waiting: waiting, waitingError: null);
    final failed = s.error != null || f?.error != null || w.error != null;
    state = next.copyWith(
      settled: true,
      refreshFailed: failed,
      updatedAt: failed ? next.updatedAt : DateTime.now(),
    );
  }

  bool _refuse(Object? error) {
    final why = _refusalOf(error);
    if (why == null) return false;
    _timer?.cancel();
    _timer = null;
    state = state.copyWith(refusal: why, settled: true);
    return true;
  }

  /// The next older page of failures, by the cursor the last page ended at.
  Future<void> loadMoreFailures() async {
    final cursor = state.moreCursor;
    if (cursor == null || state.loadingMore || state.refusal != null) return;
    state = state.copyWith(loadingMore: true, loadMoreError: null);
    final r = await _attempt(() => ref.read(systemHealthApiProvider).failures(after: cursor));
    if (!ref.mounted || _refuse(r.error)) return;
    final page = r.value;
    state = page == null
        ? state.copyWith(loadingMore: false, loadMoreError: r.error)
        : state.copyWith(
            loadingMore: false,
            older: [...state.older, ...page.items],
            olderCursor: page.nextCursor,
            olderLoaded: true,
          );
  }
}
