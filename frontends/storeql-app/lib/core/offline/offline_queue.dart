import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../constants.dart';
import '../network/api_client.dart';
import '../network/api_error.dart';
import '../storage/app_storage.dart';
import 'offline_sale.dart';
import 'offline_synced.dart';
import 'package:storeql_app/core/ids.dart';
import '../../features/pos/cash_providers.dart';

/// Store-and-forward queue for POS sales taken while the server was unreachable.
///
/// Sales are held on the device, replayed FIFO, and only dropped once every write
/// they owe has been accepted. Replay leans entirely on the idempotency keys the
/// till stamps at capture time — see [OfflineSale] for why each step is safe to
/// send twice.
///
/// What this does *not* do: let a cashier ring up an item that was never loaded.
/// Scanning still resolves the barcode and price against the server, so an
/// offline till can only complete a sale whose lines are already in the cart. A
/// cached catalog is the separate, larger piece of work.
final offlineQueueProvider =
    StateNotifierProvider<OfflineQueueNotifier, List<OfflineSale>>(
        (ref) => OfflineQueueNotifier(ref));

/// How many sales are waiting to reach the server (failed ones included — they
/// are still money the server has not been told about).
final offlineQueueCountProvider =
    Provider<int>((ref) => ref.watch(offlineQueueProvider).length);

class OfflineQueueNotifier extends StateNotifier<List<OfflineSale>> {
  final Ref _ref;
  final AppStorage _storage;

  /// When false the queue never schedules a replay of its own — callers drive it.
  /// Tests use this so a background timer cannot fire mid-assertion.
  final bool _autoSync;

  /// Told which till session the server would not count a replayed tender in
  /// (by default: the drawer the terminal holds, if it is that one, is read
  /// again, so the next sale names the one open now).
  final void Function(String sessionId) _onTillSessionRefused;

  /// Serialises replay runs: the periodic timer, a manual "Sync now" and an
  /// enqueue can all fire at once, and two concurrent runs would replay the same
  /// sale twice. Idempotency makes that harmless on the server, but it would
  /// double-count attempts and confuse the pending list.
  bool _syncing = false;

  Timer? _timer;
  int _consecutiveFailures = 0;

  /// True once a replay has failed to reach the server and not yet succeeded —
  /// what the shell shows as "offline".
  bool get isOffline => _consecutiveFailures > 0;

  OfflineQueueNotifier(this._ref,
      {AppStorage storage = const AppStorage(),
      bool autoSync = true,
      void Function(String sessionId)? onTillSessionRefused})
      : _storage = storage,
        _autoSync = autoSync,
        _onTillSessionRefused = onTillSessionRefused ??
            // A replayed tender the server will not count in its drawer is
            // recorded naming none; the terminal reads the open till again.
            ((id) => _ref.read(saleTillProvider.notifier).refused(id)),
        super(const []) {
    _ready = restore().then((_) {
      if (state.isNotEmpty) _scheduleNext();
    });
  }

  /// Completes when the on-disk queue has been read.
  ///
  /// Every mutating operation waits for it. Without that, a sale taken in the
  /// startup window wrote an in-memory state that did not yet include the
  /// restored sales — and `_persist` writes the whole list, so the sales already
  /// on disk were replaced by the one just taken.
  late final Future<void> _ready;

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  // ── persistence ───────────────────────────────────────────────────────────

  Future<void> restore() async {
    String? raw;
    try {
      raw = await _storage.read(key: StorageKeys.posOfflineSales);
      if (raw == null || raw.isEmpty) return;
      state = (jsonDecode(raw) as List)
          .map((e) => OfflineSale.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
    } catch (_) {
      // A corrupt queue must not brick the till, and the sales in it are money,
      // so the raw payload is kept for recovery. It has to be MOVED to do that:
      // _persist rewrites the live key in full, so leaving it in place — which is
      // what this used to do — meant the next sale silently overwrote it. The
      // promise held only until the cashier rang up one more item.
      //
      // Best effort: if setting it aside fails there is nothing further to try,
      // and the till must still open.
      try {
        if (raw != null && raw.isNotEmpty) {
          await _storage.write(key: StorageKeys.posOfflineSalesCorrupt, value: raw);
        }
      } catch (_) {
        // Nothing left to do about it.
      }
    }
  }

  Future<void> _persist() async {
    await _storage.write(
      key: StorageKeys.posOfflineSales,
      value: jsonEncode(state.map((s) => s.toJson()).toList()),
    );
  }

  // ── queue operations ──────────────────────────────────────────────────────

  /// Take a sale the server could not be told about. Persisted before returning,
  /// so the cashier is only told "saved" once it is genuinely on disk.
  Future<void> enqueue(OfflineSale sale) async {
    await _ready;
    state = [...state, sale];
    await _persist();
    _scheduleNext(immediate: true);
  }

  /// Put a parked (failed) sale back in line — after the cause has been dealt
  /// with, e.g. a store that was closed has been reopened.
  Future<void> retry(String id) async {
    await _ready;
    state = [
      for (final s in state)
        if (s.id == id)
          s.copyWith(status: OfflineSaleStatus.pending, clearError: true)
        else
          s,
    ];
    await _persist();
    await sync();
  }

  /// Drop a sale without sending it. Destructive — the server will never learn
  /// about this money — so the UI confirms first and only offers it for a sale
  /// the server has permanently rejected.
  Future<void> discard(String id) async {
    await _ready;
    state = [
      for (final s in state)
        if (s.id != id) s,
    ];
    await _persist();
  }

  // ── replay ────────────────────────────────────────────────────────────────

  /// Replay everything waiting, oldest first. Safe to call at any time.
  Future<void> sync() async {
    await _ready;
    if (_syncing || state.isEmpty) return;
    _syncing = true;
    try {
      // Snapshot the ids: `state` is rewritten as sales complete.
      for (final id in state.map((s) => s.id).toList()) {
        final sale = state.where((s) => s.id == id).firstOrNull;
        if (sale == null || sale.status == OfflineSaleStatus.failed) continue;
        final outcome = await _replay(sale);
        if (outcome == _Outcome.unreachable) break; // no point trying the rest
      }
    } finally {
      _syncing = false;
      _scheduleNext();
    }
  }

  /// Replay now the sales waiting for [orderIds], whatever is ahead of them
  /// in line — and nothing else.
  ///
  /// A card machine with an approval nobody has recorded takes no other card
  /// (payment-svc's 409 TERMINAL_UNSETTLED_APPROVAL), and the sale queued here
  /// for that order is what records it. Left to its turn it would wait behind
  /// any sale that cannot be sent yet, and the machine with it. Each sale's
  /// keys are its own, so sending one out of turn changes nothing for the
  /// rest. A parked sale is not sent: a person has to say so ([retry]).
  Future<void> syncOrders(Set<String> orderIds) async {
    await _ready;
    if (_syncing || orderIds.isEmpty) return;
    _syncing = true;
    try {
      final ids = [
        for (final s in state)
          if (orderIds.contains(s.orderId) &&
              s.status != OfflineSaleStatus.failed)
            s.id,
      ];
      for (final id in ids) {
        final sale = state.where((s) => s.id == id).firstOrNull;
        if (sale == null || sale.status == OfflineSaleStatus.failed) continue;
        final outcome = await _replay(sale);
        if (outcome == _Outcome.unreachable) break;
      }
    } finally {
      _syncing = false;
      _scheduleNext();
    }
  }

  Future<_Outcome> _replay(OfflineSale sale) async {
    final dio = _ref.read(apiClientProvider).dio;
    var current = sale.copyWith(attempts: sale.attempts + 1);
    await _replace(current);

    try {
      // 1. Place the order. Replays on the stored key: order-svc returns the
      //    original order for a duplicate rather than creating a second one.
      //    It says when the cashier rang it up, and within order-svc's grace
      //    the sale is recorded whatever the recalls and scales say — its goods
      //    have gone — with anything that was wrong then flagged for a manager
      //    on the audit trail. Only a sale older than the grace comes back
      //    refused, and is parked (see isPermanentRejection).
      if (current.orderId == null) {
        final capturedAt = current.capturedAt;
        final resp = await dio.post(
          '/${ApiConstants.order}/orders',
          data: {
            ...current.orderRequest,
            // A capture time that could not be read back is sent as none,
            // never as now: the server then judges the sale as made now —
            // refused over a recall that stands, and parked for a manager —
            // rather than taking an invented moment on the till's word.
            if (capturedAt != null)
              'capturedAt': capturedAt.toUtc().toIso8601String(),
            // Who rang it up, as the till recorded at the sale: whoever is
            // signed in now may not be the cashier who made it.
            if (current.rungUpBy != null) 'rungUpBy': current.rungUpBy,
          },
          options: Options(headers: {'Idempotency-Key': derivedId(current.id, 'order')}),
        );
        final order = resp.data['data'] as Map<String, dynamic>;
        current = current.copyWith(orderId: order['id'] as String? ?? '');
        await _replace(current);
      }

      // 2. Take each tender. A gift card is charged through its redeem, on a
      //    key derived from the sale and the tender's position, and the server
      //    records the GIFT_CARD tender from that charge: no payment is posted
      //    for it. Any other tender is a payment on its own key.
      for (var i = 0; i < current.tenders.length; i++) {
        final t = current.tenders[i];
        final code = t.giftCardCode;
        if (code != null) {
          if (!t.redeemDone) {
            await dio.post(
              '/${ApiConstants.order}/gift-cards/$code/redeem',
              data: {'amount': t.amount, 'orderId': current.orderId},
              options: Options(
                  headers: {'Idempotency-Key': derivedId(current.id, 'gift:$i')}),
            );
            current = current.markTender(i, tenderDone: true, redeemDone: true);
            await _replace(current);
          }
        } else if (!t.tenderDone) {
          try {
            await postTender(
              dio,
              {...t.body, 'orderId': current.orderId},
              idempotencyKey: derivedId(current.id, 'pay:$i'),
              onTillSessionRefused: _onTillSessionRefused,
            );
          } catch (e) {
            // The card machine's approval this tender names is already
            // recorded on its order, under another key: that is this tender
            // recorded, so the sale goes on to what it still owes. Anything
            // else is judged below.
            if (!cardApprovalAlreadyRecorded(e, t.body)) rethrow;
          }
          current = current.markTender(i, tenderDone: true);
          await _replace(current);
        }
      }

      // 3. Journal the sale. Last because it describes a completed transaction,
      //    and idempotent on the order id, so a replay returns the same entry.
      if (!current.posLogDone) {
        await dio.post('/${ApiConstants.order}/pos/log/orders/${current.orderId}');
        current = current.copyWith(posLogDone: true);
        await _replace(current);
      }

      // Everything landed — the server now knows about this sale. Leave a
      // record of it so the number the server issues can be found from the
      // offline receipt, which was printed without one.
      try {
        await _ref.read(offlineSyncedProvider.notifier).record(current);
      } catch (_) {
        // The sale is on the server either way.
      }
      await discard(current.id);
      _consecutiveFailures = 0;
      return _Outcome.done;
    } catch (e) {
      if (isOfflineError(e)) {
        _consecutiveFailures++;
        await _replace(current.copyWith(
            lastError: 'Waiting for the network.'));
        return _Outcome.unreachable;
      }
      if (isPermanentRejection(e)) {
        // Retrying will not change the answer. Park it for a human instead of
        // looping: the customer has already paid, so it must not be dropped.
        await _replace(current.copyWith(
          status: OfflineSaleStatus.failed,
          lastError: friendlyError(e, fallback: 'The server rejected this sale.'),
        ));
        return _Outcome.rejected;
      }
      // 5xx or anything else transient: keep it pending and back off.
      _consecutiveFailures++;
      await _replace(current.copyWith(
          lastError: friendlyError(e, fallback: 'Could not sync this sale.')));
      return _Outcome.unreachable;
    }
  }

  /// Write one sale back into the queue and persist, so progress survives the app
  /// being killed halfway through a replay.
  Future<void> _replace(OfflineSale sale) async {
    state = [
      for (final s in state)
        if (s.id == sale.id) sale else s,
    ];
    await _persist();
  }

  // ── retry cadence ─────────────────────────────────────────────────────────

  /// Poll while anything is waiting, backing off so a till left offline overnight
  /// is not opening a socket every few seconds. Stops entirely once the queue
  /// drains, and starts again on the next enqueue.
  void _scheduleNext({bool immediate = false}) {
    _timer?.cancel();
    if (!_autoSync) return;
    if (state.every((s) => s.status == OfflineSaleStatus.failed)) return;
    final delay = immediate
        ? Duration.zero
        : Duration(seconds: switch (_consecutiveFailures) {
            0 => 10,
            1 => 15,
            2 => 30,
            _ => 60,
          });
    _timer = Timer(delay, sync);
  }
}

enum _Outcome { done, rejected, unreachable }
