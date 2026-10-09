import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/ids.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/offline/offline_queue.dart';
import 'package:storeql_app/core/offline/offline_sale.dart';
import 'package:storeql_app/core/storage/app_storage.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/pos/cash_providers.dart';
import 'package:storeql_app/features/pos/held_card_payment.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_receipt_data.dart';
import 'package:storeql_app/features/pos/pos_printer_settings.dart';
import 'package:storeql_app/features/pos/pos_receipt_printer.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';
import 'package:storeql_app/features/pos/pos_terminal.dart';
import 'package:storeql_app/features/pos/tender_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The till's hold on a card payment at (or taken by) the card machine (07.16):
// a double charge must be impossible, however the press ends.
//
//   * The hold is on the device before any amount goes to a machine, and a
//     press that carries held keys never lets it go at its start: leaving the
//     screen mid-press, then pressing again, sends no second amount.
//   * Every way such a press can fail keeps it: a refusal of the replay (409
//     TERMINAL_RETIRED), a gateway that answers 503 or 429.
//   * It outlives the app: a till that starts again reads it back and settles
//     it before any new sale.
//   * A card the machine took for a sale that has since changed is finished
//     (the sale put back — asked first when another basket is on the till) or,
//     by a manager with a reason, put back on the card through payment-svc.
//   * The wait for the machine is ninety seconds by the clock, the asks' own
//     timeouts included.
//   * A split sale whose first card went through is never told "nothing was
//     taken on the card".
//   * A card taken whose payment record is refused, or followed by a declined
//     card, keeps its keys: the next press replays it, never charges it again.
// ---------------------------------------------------------------------------

class _MemStorage implements AppStorage {
  final Map<String, String> data = {};

  /// The device refuses every write, as a full or locked store does.
  bool failWrites = false;

  @override
  Future<String?> read({required String key}) async => data[key];

  @override
  Future<void> write({required String key, required String? value}) async {
    if (failWrites) throw StateError('the device store is full');
    if (value == null) {
      data.remove(key);
    } else {
      data[key] = value;
    }
  }

  @override
  Future<void> delete({required String key}) async => data.remove(key);

  @override
  Future<void> deleteAll({Set<String> keep = const {}}) async =>
      data.removeWhere((k, _) => !keep.contains(k));
}

/// A till signed in to [tenant], which can change under it: the owner going
/// into the sandbox (a business of its own), or another business's staff
/// signing in on the same device.
class _TillAuth extends AuthNotifier {
  _TillAuth(this.role, this.tenant);

  final String role;
  String tenant;

  AuthAuthenticated _as(String t) => AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'u',
        tenantId: t,
        roles: [role],
        sandbox: t == 'sandbox',
      );

  @override
  Future<AuthState> build() async => _as(tenant);

  void become(String t) {
    tenant = t;
    state = AsyncData(_as(t));
  }
}

/// A printer that keeps each receipt it is handed.
class _Printer extends ReceiptPrinter {
  _Printer(PrinterSettings settings, this.printed)
      : super(settings: settings);

  final List<PosReceiptData> printed;

  @override
  Future<PrintOutcome> print(PosReceiptData data, {bool? openDrawer}) async {
    printed.add(data);
    return const PrintOutcome(
        ok: true, method: ReceiptMethod.none, message: 'Kept.');
  }
}

class _NoopPosSessionNotifier extends PosSessionNotifier {
  _NoopPosSessionNotifier(super.ref);

  @override
  Future<void> restore() async {}
}

/// The till drawer a sale is rung on, as the Cash screen leaves it.
class _Drawer extends SaleTillNotifier {
  _Drawer(this.id);

  final String? id;

  @override
  Future<String?> build() async => id;
}

/// A drawer whose reads are scripted: each read takes the next step (the last
/// repeats) - a session id, `null` for none open, or [down] for a read that
/// fails - and waits on [hold] first while it is set.
class _ScriptedDrawer extends SaleTillNotifier {
  _ScriptedDrawer(this.steps);

  static const down = 'down';

  final List<String?> steps;
  Completer<void>? hold;
  int reads = 0;

  @override
  Future<String?> build() async {
    final step = steps[math.min(reads++, steps.length - 1)];
    final waiting = hold;
    if (waiting != null) await waiting.future;
    if (step == down) throw StateError('payment-svc is down');
    return step;
  }
}

const _jam = PosLine(
  variantId: 'v-jam',
  sku: 'JAM-1',
  name: 'Strawberry jam',
  qty: 2,
  unitPrice: 6.0,
  currency: 'GBP',
);

const _tea = PosLine(
  variantId: 'v-tea',
  sku: 'TEA-1',
  name: 'Breakfast tea',
  qty: 1,
  unitPrice: 3.0,
  currency: 'GBP',
);

class _Cart extends PosCartNotifier {
  _Cart(List<PosLine> lines) {
    loadLines(lines);
  }
}

String _key(RequestOptions r) => r.headers['Idempotency-Key'] as String;

/// A stand-in for order-svc and payment-svc that remembers what it was sent,
/// as they do: one order per Idempotency-Key, one card attempt per key (a
/// repeat hands the attempt back), one payment recorded per key, and every
/// attempt against an order readable.
///
/// [presses] says how each card attempt's presses are answered, by the order
/// its key first arrived in (attempt 0 is the first key's), the last state
/// repeating: a state, `TIMEOUT` (the till's own receive timeout ends the
/// press; the attempt is at the machine), or `HTTP409` / `HTTP503` / `HTTP429`
/// (refused before the attempt is looked at). [paymentAnswers] does the same
/// for POST /payments: `OK`, `HTTP409` or `OFFLINE`.
class _Server implements HttpClientAdapter {
  _Server({
    this.presses = const {0: ['APPROVED']},
    this.paymentAnswers = const ['OK'],
  });

  final Map<int, List<String>> presses;
  final List<String> paymentAnswers;

  /// Each attempt as payment-svc has it now, by id.
  final Map<String, Map<String, dynamic>> attempts = {};

  /// The card keys in the order they first arrived.
  final List<String> termKeys = [];

  /// The order keys in the order they first arrived.
  final List<String> orderKeys = [];

  /// The payments recorded, by key.
  final Map<String, Map<String, dynamic>> recorded = {};

  final Map<String, int> _pressesOf = {};
  final Map<String, String> _refundsByKey = {};
  int _paymentPosts = 0;

  /// How long the order post takes to answer.
  Duration orderDelay = Duration.zero;

  /// How long a card press takes to answer.
  Duration pressTakes = Duration.zero;

  /// How long the first press of attempt n takes to answer, by n: the
  /// cardholder at the PIN. The attempt is at the machine (REQUESTED) from the
  /// moment the press arrives, as payment-svc has it.
  Map<int, Duration> firstPressTakes = {};

  /// Refusals for the next posts to /payments, answered before the key is
  /// looked at, as a gateway does (`HTTP503`, `HTTP429`) and as payment-svc's
  /// cash limit is (`CASH_LIMIT`: checked before its idempotency lookup, so a
  /// replay of cash already recorded counts it twice).
  final List<String> refuseNextPayments = [];

  /// Called as each card press arrives, before it is answered.
  void Function(RequestOptions)? onPress;

  /// The terminal has been retired: payment-svc refuses a sale or a refund on
  /// it (409 TERMINAL_RETIRED) before it looks the key up, every time, and
  /// nothing brings it back.
  bool retired = false;

  /// The network is down for the order post.
  bool ordersOffline = false;

  /// payment-svc's guard: a card press under a new key is refused 409
  /// TERMINAL_UNSETTLED_APPROVAL, one detail per payment, while an earlier
  /// one holds the machine — still at it, approved and neither recorded nor
  /// put back in full, or timed out with nobody's word on it.
  bool guard = false;

  /// How long the retired machine's refusal takes to come back (a slow
  /// gateway): time the press has spent before it can read the attempt.
  Duration retiredTakes = Duration.zero;

  /// What each settle asked: the attempt and its body.
  final List<RequestOptions> settles = [];

  /// A settle is refused 409 TERMINAL_REQUEST_IN_FLIGHT with this
  /// `decidableFrom=` while it is set.
  String? inFlightUntil;

  /// The machine never answers a cancel: payment-svc restarted while the card
  /// was at it, and the attempt stays REQUESTED until a manager settles it.
  bool cancelIgnored = false;

  /// What the machine answers a refund with.
  String refundState = 'APPROVED';

  /// The gift card GC-1's balance.
  double giftBalance = 8;

  /// Each order as order-svc has it, by id; one not here is awaiting payment
  /// at £12.00.
  final Map<String, Map<String, dynamic>> orders = {};

  /// Another till records [attemptId] on its own order, under a key of its
  /// own: 'Finish that sale' on a machine both tills share.
  void recordElsewhere(String attemptId) {
    final a = attempts[attemptId]!;
    recorded['elsewhere:$attemptId'] = {
      'id': 'pay-elsewhere',
      'orderId': a['orderId'],
      'amount': a['amount'],
      'method': 'CARD',
      'terminalPaymentId': attemptId,
    };
    a['paymentId'] = 'pay-elsewhere';
  }

  List<RequestOptions> get redeems => posts('/redeem');

  /// Whether [a] holds the machine, as payment-svc's guard judges it.
  bool _holds(Map<String, dynamic> a) {
    final state = a['state'];
    final decided = a['decision'] is Map ? a['decision']['outcome'] : null;
    if (state == 'REQUESTED') return true;
    if (state == 'TIMED_OUT' && decided == null) return true;
    if (a['kind'] == 'REFUND') return false;
    final took = state == 'APPROVED' ||
        (state == 'TIMED_OUT' && decided == 'APPROVED');
    if (!took || a['paymentId'] != null) return false;
    // What a machine took for an order given up is owed back by payment-svc
    // itself, and holds no machine.
    if (orders[a['orderId']]?['status'] == 'CANCELLED') return false;
    final back = attempts.values
        .where((r) =>
            r['kind'] == 'REFUND' &&
            r['refundOf'] == a['id'] &&
            (r['state'] == 'APPROVED' ||
                (r['state'] == 'TIMED_OUT' &&
                    r['decision'] is Map &&
                    r['decision']['outcome'] == 'APPROVED')))
        .fold<double>(0, (t, r) => t + double.parse('${r['amount']}'));
    return back < double.parse('${a['amount']}');
  }

  String _standing(Map<String, dynamic> a) {
    final decided = a['decision'] is Map ? a['decision']['outcome'] : null;
    if (a['state'] == 'REQUESTED') return 'AT_MACHINE';
    if (a['state'] == 'TIMED_OUT' && decided == null) return 'UNDECIDED';
    return _holds(a) ? 'APPROVED_UNRECORDED' : 'SETTLED';
  }

  ResponseBody? _guardAnswer() {
    final held = [
      for (final a in attempts.values)
        if (a['tenant'] == viewer && _holds(a)) a,
    ];
    if (held.isEmpty) return null;
    return jsonResponse(
        jsonEncode({
          'error': {
            'code': 'TERMINAL_UNSETTLED_APPROVAL',
            'message': 'This card machine has a card payment that is not '
                'settled.',
            'details': [
              for (final a in held)
                'attemptId=${a['id']};orderId=${a['orderId']};'
                    'amount=${a['amount']};currency=${a['currency']};'
                    'onCard=${a['kind'] == 'REFUND' ? '0.00' : a['amount']};'
                    'state=${a['state']};standing=${_standing(a)};'
                    'kind=${a['kind']}'
                    '${a['kind'] == 'REFUND' ? ';refundOf=${a['refundOf']}' : ''}',
            ],
          },
        }),
        409);
  }

  /// The business the till is signed in to: payment-svc reads only that
  /// business's attempts (TerminalRepository.attemptsOf filters by tenant).
  String viewer = 't';

  /// The orders cancelled, with what was sent.
  final List<RequestOptions> orderCancels = [];

  /// order-svc cannot be reached for a cancel (503): the order stays as it is.
  bool orderCancelFails = false;

  /// The orders whose payments payment-svc cannot record just now (503 on
  /// every POST /payments for them).
  final Set<String> refusePaymentsFor = {};

  final List<RequestOptions> requests = [];

  /// The machine settles attempt [id] as [state] while the till is not asking.
  void settle(String id, String state) => attempts[id]!['state'] = state;

  List<RequestOptions> posts(String suffix) => requests
      .where((r) => r.method == 'POST' && r.path.endsWith(suffix))
      .toList();

  List<RequestOptions> get cardPresses => posts('/payments/terminal');

  List<RequestOptions> get refunds => posts('/refunds');

  List<RequestOptions> get attemptsReads => requests
      .where((r) => r.method == 'GET' && r.path.contains('/terminal/by-order/'))
      .toList();

  List<RequestOptions> get attemptReads => requests
      .where((r) => r.method == 'GET' && r.path.contains('/payments/terminal/att-'))
      .toList();

  ResponseBody _retiredAnswer() => jsonResponse(
      '{"error":{"code":"TERMINAL_RETIRED",'
      '"message":"That terminal has been retired"}}',
      409);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (o.method == 'GET' && path.endsWith('/admin/payments/terminals')) {
      return jsonResponse('{"data":[{"id":"term-1","label":"Till 1",'
          '"vendor":"VERIFONE","storeId":"store-1","status":"ACTIVE"}]}');
    }
    if (o.method == 'POST' && path.endsWith('/orders')) {
      if (ordersOffline) {
        throw DioException(
            requestOptions: o, type: DioExceptionType.connectionError);
      }
      if (orderDelay > Duration.zero) await Future<void>.delayed(orderDelay);
      final key = _key(o);
      if (!orderKeys.contains(key)) orderKeys.add(key);
      final n = orderKeys.indexOf(key) + 1;
      return jsonResponse('{"data":{"id":"order-$n","total":12.0}}', 201);
    }
    if (o.method == 'GET' && path.contains('/payments/terminal/by-order/')) {
      final orderId = path.split('/').last;
      return jsonResponse(jsonEncode({
        'data': [
          for (final a in attempts.values)
            if (a['orderId'] == orderId && a['tenant'] == viewer) a,
        ],
      }));
    }
    if (o.method == 'POST' &&
        path.contains('/orders/') &&
        path.endsWith('/cancel')) {
      orderCancels.add(o);
      final id = path.split('/').reversed.skip(1).first;
      if (orderCancelFails) {
        return jsonResponse(
            '{"error":{"code":"UNAVAILABLE","message":"Try again"}}', 503);
      }
      orders[id] = {...?orders[id], 'status': 'CANCELLED'};
      return jsonResponse(
          jsonEncode({'data': {'id': id, 'status': 'CANCELLED'}}));
    }
    if (o.method == 'POST' && path.endsWith('/cancel')) {
      final id = path.split('/').reversed.skip(1).first;
      if (attempts[id]!['state'] == 'REQUESTED' && !cancelIgnored) {
        settle(id, 'CANCELLED');
      }
      return jsonResponse(jsonEncode({'data': attempts[id]}));
    }
    if (o.method == 'POST' && path.endsWith('/settle')) {
      settles.add(o);
      final id = path.split('/').reversed.skip(1).first;
      if (inFlightUntil != null) {
        return jsonResponse(
            jsonEncode({
              'error': {
                'code': 'TERMINAL_REQUEST_IN_FLIGHT',
                'message': 'The machine may still answer',
                'details': ['decidableFrom=$inFlightUntil'],
              },
            }),
            409);
      }
      final a = attempts[id]!;
      if (a['state'] == 'REQUESTED') a['state'] = 'TIMED_OUT';
      a['decision'] = {
        'outcome': (o.data as Map)['outcome'],
        'reason': (o.data as Map)['reason'],
      };
      a['standing'] = _standing(a);
      return jsonResponse(jsonEncode({'data': a}));
    }
    if (o.method == 'POST' && path.endsWith('/refunds')) {
      if (retired) return _retiredAnswer();
      final id = path.split('/').reversed.skip(1).first;
      final refundId = _refundsByKey.putIfAbsent(
          _key(o), () => 'ref-${_refundsByKey.length + 1}');
      attempts[refundId] ??= {
        'id': refundId,
        'orderId': attempts[id]!['orderId'],
        'kind': 'REFUND',
        'tenant': viewer,
        'refundOf': id,
        'state': refundState,
        'amount': (o.data as Map)['amount'],
        'currency': 'GBP',
      };
      return jsonResponse(jsonEncode({'data': attempts[refundId]}), 201);
    }
    if (o.method == 'POST' && path.endsWith('/payments/terminal')) {
      onPress?.call(o);
      if (retired) {
        if (retiredTakes > Duration.zero) {
          await Future<void>.delayed(retiredTakes);
        }
        return _retiredAnswer();
      }
      final key = _key(o);
      // The guard refuses a new key before it is claimed: the machine is not
      // asked, and the key stays free.
      if (guard && !termKeys.contains(key)) {
        final refusal = _guardAnswer();
        if (refusal != null) return refusal;
      }
      if (!termKeys.contains(key)) termKeys.add(key);
      final n = termKeys.indexOf(key);
      final id = 'att-${n + 1}';
      final script = presses[n] ?? presses[presses.keys.reduce(math.max)]!;
      final count = _pressesOf[key] = (_pressesOf[key] ?? 0) + 1;
      final answer = script[math.min(count - 1, script.length - 1)];
      if (!answer.startsWith('HTTP') && answer != 'INFLIGHT') {
        final body = o.data as Map;
        attempts[id] ??= {
          'id': id,
          'orderId': body['orderId'],
          'tenant': viewer,
          'kind': 'SALE',
          'amount': body['amount'],
          'currency': body['currency'],
          'state': 'REQUESTED',
        };
      }
      if (pressTakes > Duration.zero) await Future<void>.delayed(pressTakes);
      final first = firstPressTakes[n];
      if (count == 1 && first != null) await Future<void>.delayed(first);
      if (answer == 'INFLIGHT') {
        return jsonResponse(
            '{"error":{"code":"TERMINAL_REQUEST_IN_FLIGHT",'
            '"message":"That card payment is still being asked for"}}',
            409);
      }
      if (answer.startsWith('HTTP')) {
        final status = int.parse(answer.substring(4));
        return jsonResponse(
            '{"error":{"code":"${status == 409 ? 'TERMINAL_RETIRED' : 'UNAVAILABLE'}",'
            '"message":"${status == 409 ? 'That terminal has been retired' : 'Try again'}"}}',
            status);
      }
      final body = o.data as Map;
      final had = attempts[id];
      attempts[id] = {
        'id': id,
        'orderId': body['orderId'],
        'tenant': had?['tenant'] ?? viewer,
        'kind': 'SALE',
        'amount': body['amount'],
        'currency': body['currency'],
        // A repeat hands back the attempt as it stands; only the first press
        // reaches the machine.
        'state': answer == 'TIMEOUT'
            ? (had?['state'] ?? 'REQUESTED')
            : (had != null && had['state'] != 'REQUESTED'
                ? had['state']
                : answer),
        'panLast4': '4242',
        'receiptLine': 'VISA DEBIT ****4242 (CHIP, PIN)',
        if (had?['decision'] != null) 'decision': had!['decision'],
        if (had?['paymentId'] != null) 'paymentId': had!['paymentId'],
      };
      if (answer == 'TIMEOUT') {
        throw DioException(
            requestOptions: o, type: DioExceptionType.receiveTimeout);
      }
      return jsonResponse(jsonEncode({'data': attempts[id]}), 201);
    }
    if (o.method == 'GET' &&
        path.contains('/payments/terminal/') &&
        attempts.containsKey(path.split('/').last)) {
      return jsonResponse(jsonEncode({'data': attempts[path.split('/').last]}));
    }
    if (o.method == 'GET' && path.endsWith('/gift-cards/GC-1')) {
      return jsonResponse(jsonEncode({
        'data': {
          'currentBalance': giftBalance,
          'currency': 'GBP',
          'status': 'ACTIVE',
        },
      }));
    }
    if (o.method == 'POST' && path.endsWith('/gift-cards/GC-1/redeem')) {
      return jsonResponse(
          jsonEncode({
            'data': {'redemptionId': 'r-${redeems.length}', 'amount': 8},
          }),
          201);
    }
    if (o.method == 'GET' && RegExp(r'/orders/[^/]+$').hasMatch(path)) {
      final id = path.split('/').last;
      return jsonResponse(jsonEncode({
        'data': {
          'id': id,
          'status': 'PENDING',
          'total': 12.0,
          ...?orders[id],
        },
      }));
    }
    if (o.method == 'GET' && path.contains('/payments/by-order/')) {
      final orderId = path.split('/').last;
      return jsonResponse(jsonEncode({
        'data': [
          for (final p in recorded.values)
            if (p['orderId'] == orderId) p,
        ],
      }));
    }
    if (o.method == 'POST' && path.endsWith('/payments')) {
      if (refusePaymentsFor.contains((o.data as Map)['orderId'])) {
        return jsonResponse(
            '{"error":{"code":"UNAVAILABLE","message":"Try again"}}', 503);
      }
      // The cash limit is a refusal of cash: a card's record never meets it.
      if (refuseNextPayments.isNotEmpty &&
          (refuseNextPayments.first != 'CASH_LIMIT' ||
              (o.data as Map)['method'] == 'CASH')) {
        final refusal = refuseNextPayments.removeAt(0);
        if (refusal == 'GIVEN_UP') {
          return jsonResponse(
              '{"error":{"code":"PAYMENT_ORDER_GIVEN_UP",'
              '"message":"That order was cancelled or voided"}}',
              409);
        }
        return refusal == 'CASH_LIMIT'
            ? jsonResponse(
                '{"error":{"code":"PAYMENT_CASH_LIMIT_EXCEEDED",'
                '"message":"Over the cash limit for one sale"}}',
                409)
            : jsonResponse(
                '{"error":{"code":"UNAVAILABLE","message":"Try again"}}',
                int.parse(refusal.substring(4)));
      }
      final key = _key(o);
      if (recorded.containsKey(key)) {
        return jsonResponse(jsonEncode({'data': recorded[key]}), 201);
      }
      // An approval is recorded once: under another key it is refused.
      final naming = (o.data as Map)['terminalPaymentId'];
      if (naming != null && attempts[naming]?['paymentId'] != null) {
        return jsonResponse(
            '{"error":{"code":"TERMINAL_ATTEMPT_ALREADY_RECORDED",'
            '"message":"That card payment is already recorded on its sale"}}',
            409);
      }
      final answer = paymentAnswers[
          math.min(_paymentPosts++, paymentAnswers.length - 1)];
      if (answer == 'OFFLINE') {
        throw DioException(
            requestOptions: o, type: DioExceptionType.connectionError);
      }
      if (answer == 'HTTP409') {
        return jsonResponse(
            '{"error":{"code":"PAYMENT_ORDER_NOT_PAYABLE",'
            '"message":"That order cannot take a payment now"}}',
            409);
      }
      recorded[key] = {
        'id': 'pay-${recorded.length + 1}',
        ...Map<String, dynamic>.from(o.data as Map),
      };
      // A tender naming the machine's approval settles it.
      final named = (o.data as Map)['terminalPaymentId'];
      if (named != null && attempts[named] != null) {
        attempts[named]!['paymentId'] = recorded[key]!['id'];
      }
      return jsonResponse(jsonEncode({'data': recorded[key]}), 201);
    }
    if (path.endsWith('/fiscal-receipt')) {
      return jsonResponse(
          '{"data":{"fullNumber":"2026-000042","regime":"NONE"}}');
    }
    return jsonResponse('{"data":{}}');
  }
}

/// A tenth of a second between asks on the test's own clock, and time for two.
const _brief = TerminalWait(
    every: Duration(milliseconds: 100), limit: Duration(milliseconds: 250));

Future<ProviderContainer> _pump(
  WidgetTester tester,
  _Server server, {
  required _MemStorage device,
  List<PosLine> lines = const [_jam],
  String role = 'CASHIER',
  AuthNotifier Function()? auth,
  List<PosReceiptData>? printed,
  TerminalWait wait = _brief,
  ValueNotifier<bool>? shown,
  Key? key,
  List<Override> overrides = const [],
}) async {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final visible = shown ?? ValueNotifier(true);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    key: key,
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      offlineQueueProvider.overrideWith((ref) =>
          OfflineQueueNotifier(ref, storage: _MemStorage(), autoSync: false)),
      // The device the hold is kept on: the same one across a restart.
      heldCardPaymentProvider
          .overrideWith((ref) => HeldCardPaymentNotifier(storage: device)),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      authNotifierProvider.overrideWith(auth ?? () => RoleAuth(role)),
      posCartProvider.overrideWith((ref) => _Cart(lines)),
      posStoreProvider.overrideWith((ref) => 'store-1'),
      posWalkInPhoneProvider.overrideWith((ref) => '07700900000'),
      posStoresProvider.overrideWith((ref) async => const [
            StoreInfo(
              id: 'store-1',
              name: 'High Street',
              code: 'HS',
              type: 'STORE',
              status: 'ACTIVE',
              tillPhone: 'OPTIONAL',
            ),
          ]),
      if (printed != null)
        receiptPrinterFactoryProvider
            .overrideWithValue((s) => _Printer(s, printed)),
      terminalWaitProvider.overrideWithValue(wait),
      terminalClockProvider.overrideWithValue(() => tester.binding.clock.now()),
      ...overrides,
    ],
    child: MaterialApp(
      home: Scaffold(
        body: ValueListenableBuilder<bool>(
          valueListenable: visible,
          builder: (_, on, _) =>
              on ? const TenderScreen() : const SizedBox(key: Key('elsewhere')),
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  final container =
      ProviderScope.containerOf(tester.element(find.byType(Scaffold)));
  await container.read(authNotifierProvider.future);
  return container;
}

/// Stage a card tender of [amount], or of what is left to pay. The store has
/// the one card machine, so it is chosen without asking.
Future<void> _addCard(WidgetTester tester, {String? amount}) async {
  await tester.tap(find.widgetWithText(OutlinedButton, 'Card'));
  await tester.pumpAndSettle();
  if (amount != null) {
    await tester.enterText(
        find.descendant(
            of: find.byType(AlertDialog), matching: find.byType(TextField)),
        amount);
    await tester.pumpAndSettle();
  }
  await tester.tap(find.widgetWithText(FilledButton, 'Add'));
  await tester.pumpAndSettle();
}

Future<void> _addCash(WidgetTester tester, {String? amount}) async {
  await tester.tap(find.widgetWithText(OutlinedButton, 'Cash'));
  await tester.pumpAndSettle();
  if (amount != null) {
    await tester.enterText(
        find.descendant(
            of: find.byType(AlertDialog), matching: find.byType(TextField)),
        amount);
    await tester.pumpAndSettle();
  }
  await tester.tap(find.widgetWithText(FilledButton, 'Add'));
  await tester.pumpAndSettle();
}

Future<void> _pressComplete(WidgetTester tester) async {
  final button = find.widgetWithText(FilledButton, 'Complete Sale');
  await tester.ensureVisible(button);
  await tester.tap(button);
  await tester.pump(const Duration(milliseconds: 1));
}

Future<void> _letTimePass(WidgetTester tester, {int steps = 30}) async {
  for (var i = 0; i < steps; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

/// Press, let the wait run out with the card still at the machine, and close
/// "Still waiting for the card machine".
Future<void> _stopAtTheMachine(WidgetTester tester) async {
  await _pressComplete(tester);
  await _letTimePass(tester);
  await tester.pumpAndSettle();
  expect(find.byKey(const Key('tender-terminal-pending')), findsOneWidget);
  await tester.tap(find.text('OK'));
  await tester.pumpAndSettle();
}

Future<void> _finish(WidgetTester tester) async {
  await _pressComplete(tester);
  await _letTimePass(tester);
  await tester.pumpAndSettle();
}

void main() {
  // A time of day is said in the till's own format.
  setUpAll(() => initializeDateFormatting());

  group('leaving the screen mid-press', () {
    testWidgets(
        'leaving while a press carries the held keys, then pressing again, '
        'sends no second amount', (tester) async {
      final device = _MemStorage();
      final shown = ValueNotifier(true);
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED']
      });
      final container =
          await _pump(tester, server, device: device, shown: shown);
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      final held = container.read(heldCardPaymentProvider)!;

      // Pressed again — the same sale, the same keys — and the cashier goes to
      // another tab while the order is still being answered.
      server.orderDelay = const Duration(seconds: 1);
      await _pressComplete(tester);
      shown.value = false;
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      await tester.pumpAndSettle();

      // Nothing new went to the machine, and the hold is as it was: the press
      // never let it go.
      expect(server.termKeys, hasLength(1));
      expect(container.read(heldCardPaymentProvider)?.base, held.base);

      // Meanwhile the cardholder finished on the machine.
      server.settle('att-1', 'APPROVED');
      server.orderDelay = Duration.zero;
      shown.value = true;
      await tester.pumpAndSettle();
      if (find.byTooltip('Remove tender').evaluate().isEmpty) {
        await _addCard(tester); // as a cashier would, finding nothing staged
      }
      await _finish(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.termKeys, hasLength(1),
          reason: 'one card payment: a second key would be a second amount');
      expect(server.orderKeys, hasLength(1));
      expect(server.recorded, hasLength(1));
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets(
        'the hold is on the device before the amount goes to the machine, and '
        'leaving while the machine has it, then pressing again, finishes that '
        'payment — never a second', (tester) async {
      final device = _MemStorage();
      final shown = ValueNotifier(true);
      final server = _Server(presses: {
        0: ['APPROVED']
      })
        ..pressTakes = const Duration(seconds: 1);
      String? onDeviceAtPress;
      server.onPress = (_) =>
          onDeviceAtPress ??= device.data[heldCardPaymentStorageKey];
      final container =
          await _pump(tester, server, device: device, shown: shown);
      await _addCard(tester);

      await _pressComplete(tester);
      expect(server.cardPresses, hasLength(1));
      expect(onDeviceAtPress, isNotNull,
          reason: 'held before any amount was sent');
      final kept = jsonDecode(onDeviceAtPress!) as Map<String, dynamic>;
      expect(kept['orderId'], 'order-1');
      expect(kept['base'], isNotNull);

      // Gone while the card is at the machine; the machine approves.
      shown.value = false;
      await tester.pump();
      await tester.pump(const Duration(seconds: 2));
      await tester.pumpAndSettle();
      expect(container.read(heldCardPaymentProvider)?.approved, {0},
          reason: 'the approval is held, the sale not finished out of sight');

      shown.value = true;
      await tester.pumpAndSettle();
      if (find.byTooltip('Remove tender').evaluate().isEmpty) {
        await _addCard(tester);
      }
      server.pressTakes = Duration.zero;
      await _finish(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.termKeys, hasLength(1));
      expect(server.cardPresses.map(_key).toSet(), hasLength(1));
      expect(server.recorded, hasLength(1),
          reason: 'the approval is recorded once');
      expect(container.read(heldCardPaymentProvider), isNull);
      expect(device.data.containsKey(heldCardPaymentStorageKey), isFalse);
    });
  });

  testWidgets(
      'a replay a gateway refuses — 503 or 429 — keeps the hold, and the next '
      'press presents the same key', (tester) async {
    final device = _MemStorage();
    final server = _Server(presses: {
      0: ['TIMEOUT', 'REQUESTED', 'HTTP503', 'HTTP429', 'APPROVED']
    });
    final container = await _pump(tester, server, device: device);
    await _addCard(tester);
    await _stopAtTheMachine(tester);
    final base = container.read(heldCardPaymentProvider)!.base;

    for (final refusal in ['503', '429']) {
      await _finish(tester);
      expect(find.text('Sale complete'), findsNothing, reason: refusal);
      expect(container.read(heldCardPaymentProvider)?.base, base,
          reason: 'a $refusal keeps the hold');
      expect(device.data[heldCardPaymentStorageKey], contains(base),
          reason: refusal);
      expect(server.termKeys, hasLength(1), reason: refusal);
      await tester.pump(const Duration(seconds: 5)); // the snackbar goes
      await tester.pumpAndSettle();
    }

    await _finish(tester);
    expect(find.text('Sale complete'), findsOneWidget);
    expect(server.termKeys, hasLength(1));
    expect(server.orderKeys, hasLength(1));
    expect(server.recorded, hasLength(1));
  });

  group('a terminal retired under a held sale', () {
    // payment-svc refuses a sale or a refund on a retired terminal before it
    // looks the key up (TerminalService.requireActive), on every press, and
    // nothing brings a retired terminal back: a till that only ever asked the
    // machine again would never finish the sale nor start another.

    testWidgets(
        'a replay refused 409 TERMINAL_RETIRED keeps the hold on every press; '
        'the attempt the till heard is read instead, and once the machine has '
        'approved it the sale finishes — never a second amount', (tester) async {
      final device = _MemStorage();
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED']
      });
      final container = await _pump(tester, server, device: device);
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      final base = container.read(heldCardPaymentProvider)!.base;

      // The pinpad is swapped and the manager retires term-1, while the
      // cardholder is still at it.
      server.retired = true;
      await _pressComplete(tester);
      await _letTimePass(tester);
      await tester.pumpAndSettle();
      expect(find.text('Sale complete'), findsNothing);
      expect(find.byKey(const Key('tender-terminal-pending')), findsOneWidget,
          reason: 'the attempt read says it is still at the machine');
      expect(container.read(heldCardPaymentProvider)?.base, base);
      expect(device.data[heldCardPaymentStorageKey], contains(base));
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();

      // The machine approves it while the till is not listening.
      server.settle('att-1', 'APPROVED');
      await _finish(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.termKeys, hasLength(1));
      expect(server.orderKeys, hasLength(1));
      expect(server.recorded, hasLength(1));
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets(
        'a card the machine approved is final: the press that carries it on '
        'records it without asking the retired machine again', (tester) async {
      // Approved, but its record refused: the hold has the approval and no
      // record. Then the pinpad is swapped and term-1 retired.
      final server = _Server(presses: {
        0: ['APPROVED']
      }, paymentAnswers: [
        'HTTP409',
        'OK'
      ]);
      final printed = <PosReceiptData>[];
      final container = await _pump(tester, server,
          device: _MemStorage(), printed: printed);
      await _addCard(tester);
      await _finish(tester);
      expect(find.text('Sale complete'), findsNothing);
      expect(container.read(heldCardPaymentProvider)?.approved, {0});
      expect(container.read(heldCardPaymentProvider)?.paid, isEmpty);
      await tester.pump(const Duration(seconds: 5)); // the snackbar goes
      await tester.pumpAndSettle();

      server.retired = true;
      await _finish(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.cardPresses, hasLength(1),
          reason: 'the approved card is not asked after again');
      expect(server.recorded, hasLength(1));
      // Its receipt carries the card line the machine gave.
      expect(printed.single.tenders.single.terminalReceiptLine,
          'VISA DEBIT ****4242 (CHIP, PIN)');
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets(
        'a split sale whose approved card was recorded, its second declined, '
        'finishes for cash with the machine retired', (tester) async {
      final server = _Server(presses: {
        0: ['APPROVED'],
        1: ['DECLINED'],
      });
      final container = await _pump(tester, server, device: _MemStorage());
      await _addCard(tester, amount: '6.00');
      await _addCard(tester);
      await _finish(tester);
      expect(container.read(heldCardPaymentProvider)?.approved, {0});
      await tester.pump(const Duration(seconds: 5)); // the snackbar goes
      await tester.pumpAndSettle();

      server.retired = true;
      // The declined card off, cash on.
      await tester.tap(find.byTooltip('Remove tender').last);
      await tester.pumpAndSettle();
      await _addCash(tester);
      await _finish(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.cardPresses, hasLength(2),
          reason: 'the approved card is not asked after again');
      expect(server.recorded, hasLength(2));
      expect(
          [for (final p in server.recorded.values) p['method']]..sort(),
          ['CARD', 'CASH']);
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets(
        'a payment already recorded is not sent again: a cash limit payment-svc '
        'checks before its key lookup cannot strand the card after it',
        (tester) async {
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED']
      });
      final container = await _pump(tester, server, device: _MemStorage());
      await _addCash(tester, amount: '6.00');
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      expect(server.recorded, hasLength(1), reason: 'the cash recorded');
      final cashPosts = server.posts('/payments').length;

      server.refuseNextPayments.add('CASH_LIMIT');
      server.settle('att-1', 'APPROVED');
      await _finish(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.posts('/payments').length, cashPosts + 1,
          reason: 'only the card is recorded: the cash is not sent again');
      expect(server.refuseNextPayments, ['CASH_LIMIT'],
          reason: 'the refusal was never met');
      expect(server.recorded, hasLength(2));
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    /// Every ask ends on the till's own timeout, so the till never hears the
    /// attempt and has nothing of its own to read. The machine approves; then
    /// the terminal is retired.
    Future<(_Server, ProviderContainer)> unheardThenRetired(WidgetTester tester,
        {required String role}) async {
      final server = _Server(presses: {
        0: ['TIMEOUT']
      });
      final container =
          await _pump(tester, server, device: _MemStorage(), role: role);
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      expect(container.read(heldCardPaymentProvider)?.attemptIds, isEmpty,
          reason: 'the till never heard the attempt');
      server.settle('att-1', 'APPROVED');
      server.retired = true;
      return (server, container);
    }

    testWidgets(
        'carried on unchanged, the card the till never heard is found among '
        'the order\'s attempts and recorded as that payment — never asked for '
        'again', (tester) async {
      final (server, container) =
          await unheardThenRetired(tester, role: 'CASHIER');
      final asked = server.termKeys.length;

      await _finish(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.termKeys, hasLength(asked), reason: 'no second amount');
      final record = server.posts('/payments').single;
      expect(record.data['terminalPaymentId'], 'att-1',
          reason: 'recorded as exactly the payment the machine took');
      expect(server.orderCancels, isEmpty);
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    Future<(_Server, ProviderContainer)> stranded(WidgetTester tester,
        {required String role}) async {
      final (server, container) =
          await unheardThenRetired(tester, role: role);
      // The basket changes: the held sale is settled first.
      container.read(posCartProvider.notifier).setQty('v-jam', 3);
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Remove tender').first);
      await tester.pumpAndSettle();
      await _addCash(tester);
      await _pressComplete(tester);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-held-taken')), findsOneWidget);
      return (server, container);
    }

    testWidgets(
        'a manager is not offered "Cancel the earlier sale" — it left the money '
        'on the card — and where the machine cannot put it back, the approval '
        'read from the order is recorded in the hold, so putting the sale back '
        'finishes it without asking the machine', (tester) async {
      final (server, container) = await stranded(tester, role: 'MANAGER');
      expect(find.byKey(const Key('tender-held-close')), findsNothing);
      expect(find.text('Cancel the earlier sale'), findsNothing);
      final held = container.read(heldCardPaymentProvider)!;
      expect(held.approved, {0}, reason: 'the approval read from the order');
      expect(held.attemptIds, {0: 'att-1'});

      // Putting it back on the card is refused by the retired machine.
      await tester.tap(find.byKey(const Key('tender-held-reverse')));
      await tester.pumpAndSettle();
      await tester.enterText(
          find.byKey(const Key('tender-reverse-reason-field')), 'Wrong size');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-reverse-confirm')));
      await tester.pumpAndSettle();
      expect(server.refunds.single.data,
          {'amount': '12.00', 'reason': 'Wrong size'});
      expect(container.read(heldCardPaymentProvider), isNotNull);
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      // So the sale is put back and finished: the approval is recorded on it.
      await _pressComplete(tester);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-held-close')), findsNothing);
      await tester.tap(find.byKey(const Key('tender-held-put-back')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-put-back-replace')));
      await tester.pumpAndSettle();
      final pressesBefore = server.cardPresses.length;
      await _finish(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.cardPresses, hasLength(pressesBefore),
          reason: 'the retired machine is not asked again');
      expect(server.recorded.values.single['terminalPaymentId'], 'att-1');
      expect(server.orderCancels, isEmpty,
          reason: 'nothing cancels the sale and leaves the money on the card');
      expect(server.orderKeys, hasLength(1));
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets('a cashier is not offered either: the sale is put back and '
        'finished', (tester) async {
      final (server, _) = await stranded(tester, role: 'CASHIER');
      expect(find.byKey(const Key('tender-held-close')), findsNothing);
      expect(find.byKey(const Key('tender-held-reverse')), findsNothing);
      await tester.tap(find.byKey(const Key('tender-held-put-back')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-put-back-replace')));
      await tester.pumpAndSettle();
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.orderCancels, isEmpty);
      expect(server.refunds, isEmpty);
      expect(server.recorded.values.single['terminalPaymentId'], 'att-1');
    });
  });

  group('after the app closes', () {
    testWidgets(
        'a till that starts again reads the hold back and settles it before a '
        'new sale: the card it took is finished, after asking before the new '
        'basket is replaced', (tester) async {
      final device = _MemStorage();
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED']
      });
      await _pump(tester, server, device: device, key: const Key('before'));
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      expect(device.data[heldCardPaymentStorageKey], isNotNull);

      // The app closes; the cardholder finishes on the machine.
      await tester.pumpWidget(const SizedBox());
      server.settle('att-1', 'APPROVED');

      // It opens again with another customer's basket on the till.
      final container = await _pump(tester, server,
          device: device, lines: const [_tea], key: const Key('after'));
      expect(container.read(heldCardPaymentProvider)?.orderId, 'order-1',
          reason: 'read back from the device');
      await _addCash(tester);
      await _pressComplete(tester);
      await tester.pumpAndSettle();

      // Settled first: read, never started. Nothing new was placed or sent.
      expect(server.attemptsReads, isNotEmpty);
      expect(find.byKey(const Key('tender-held-taken')), findsOneWidget);
      expect(server.orderKeys, hasLength(1));
      expect(server.cardPresses, hasLength(2));
      expect(server.recorded, isEmpty);

      // Putting the sale back would take the tea off: the cashier is asked.
      await tester.tap(find.byKey(const Key('tender-held-put-back')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-put-back-confirm')), findsOneWidget);
      expect(find.textContaining('1 item on it now'), findsOneWidget);
      await tester.tap(find.text('Keep this sale'));
      await tester.pumpAndSettle();
      expect(container.read(posCartProvider).single.variantId, 'v-tea');
      expect(container.read(heldCardPaymentProvider), isNotNull);

      await _pressComplete(tester);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-held-put-back')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-put-back-replace')));
      await tester.pumpAndSettle();
      expect(container.read(posCartProvider).single.variantId, 'v-jam');

      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.orderKeys, hasLength(1));
      expect(server.termKeys, hasLength(1));
      expect(server.recorded, hasLength(1));
      expect(device.data.containsKey(heldCardPaymentStorageKey), isFalse);
    });

    testWidgets(
        'a till that starts again with nothing on it offers the held sale '
        'back, and carries on that same payment', (tester) async {
      final device = _MemStorage();
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED', 'APPROVED']
      });
      await _pump(tester, server, device: device, key: const Key('before'));
      await _addCard(tester);
      await _stopAtTheMachine(tester);

      await tester.pumpWidget(const SizedBox());
      final container = await _pump(tester, server,
          device: device, lines: const [], key: const Key('after'));

      expect(find.byKey(const Key('tender-held-sale')), findsOneWidget);
      await tester.tap(find.byKey(const Key('tender-held-sale-put-back')));
      await tester.pumpAndSettle();
      expect(container.read(posCartProvider).single.variantId, 'v-jam');
      expect(find.byTooltip('Remove tender'), findsOneWidget);

      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.termKeys, hasLength(1));
      expect(server.orderKeys, hasLength(1));
      expect(server.recorded, hasLength(1));
    });
  });

  group('a card taken for a sale that has changed', () {
    Future<(_Server, ProviderContainer)> takenThenChanged(WidgetTester tester,
        {String role = 'CASHIER'}) async {
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED'],
        1: ['APPROVED'],
      });
      final container =
          await _pump(tester, server, device: _MemStorage(), role: role);
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      server.settle('att-1', 'APPROVED');
      final field = find.byKey(const Key('tender-phone-field'));
      await tester.ensureVisible(field);
      await tester.enterText(field, '07700900111');
      await tester.pumpAndSettle();
      await _pressComplete(tester);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-held-taken')), findsOneWidget);
      return (server, container);
    }

    testWidgets(
        'a manager can put the money back on the card, with a reason, through '
        'payment-svc; the changed sale then goes ahead under keys of its own',
        (tester) async {
      final (server, container) = await takenThenChanged(tester, role: 'MANAGER');
      final held = container.read(heldCardPaymentProvider)!;

      await tester.tap(find.byKey(const Key('tender-held-reverse')));
      await tester.pumpAndSettle();
      final confirm = find.byKey(const Key('tender-reverse-confirm'));
      expect(tester.widget<FilledButton>(confirm).onPressed, isNull,
          reason: 'not without a reason');
      await tester.enterText(
          find.byKey(const Key('tender-reverse-reason-field')),
          'Customer wanted a different number on the receipt');
      await tester.pumpAndSettle();
      await tester.tap(confirm);
      await _letTimePass(tester);
      await tester.pumpAndSettle();

      final refund = server.refunds.single;
      expect(refund.path, endsWith('/payments/terminal/att-1/refunds'));
      // The reason goes with it: payment-svc keeps it on the refund.
      expect(refund.data, {
        'amount': '12.00',
        'reason': 'Customer wanted a different number on the receipt',
      });
      expect(_key(refund), derivedId(held.base, 'reverse:att-1'));
      // Put back before anything new was placed.
      expect(server.requests.indexOf(refund),
          lessThan(server.requests.indexOf(server.posts('/orders').last)));
      expect(server.orderKeys, hasLength(2));
      expect(server.posts('/orders').last.data['contactPhone'], '07700900111');
      expect(server.termKeys, hasLength(2));
      expect(find.text('Sale complete'), findsOneWidget);
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets('a cashier is not offered it: the sale is put back and finished',
        (tester) async {
      final (server, _) = await takenThenChanged(tester);
      expect(find.byKey(const Key('tender-held-reverse')), findsNothing);
      expect(server.refunds, isEmpty);
      await tester.tap(find.text('Not now'));
      await tester.pumpAndSettle();
    });

    testWidgets(
        'nor a manager, once a payment is recorded against the sale: it is '
        'finished, then returned', (tester) async {
      final server = _Server(presses: {
        0: ['APPROVED'],
        1: ['DECLINED'],
      });
      final container =
          await _pump(tester, server, device: _MemStorage(), role: 'MANAGER');
      await _addCard(tester, amount: '6.00');
      await _addCard(tester);
      await _finish(tester);
      expect(server.recorded, hasLength(1), reason: 'the first card recorded');
      expect(container.read(heldCardPaymentProvider)?.approved, {0});

      // The basket changes.
      container.read(posCartProvider.notifier).setQty('v-jam', 3);
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Remove tender').first);
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Remove tender').first);
      await tester.pumpAndSettle();
      await _addCash(tester);
      await _pressComplete(tester);
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('tender-held-taken')), findsOneWidget);
      expect(find.byKey(const Key('tender-held-reverse')), findsNothing);
      expect(find.textContaining('A payment is already recorded'),
          findsOneWidget);
      expect(server.refunds, isEmpty);
      await tester.tap(find.text('Not now'));
      await tester.pumpAndSettle();
    });
  });

  testWidgets(
      'the wait for the card machine is ninety seconds by the clock, each '
      'ask\'s own timeout counted, never forty-five asks', (tester) async {
    // Every press ends on the till's own ten-second receive timeout. Counted
    // in asks — 45, two seconds apart — the customer stood there nine minutes.
    final server = _Server(presses: {
      0: ['TIMEOUT']
    })
      ..pressTakes = const Duration(seconds: 10);
    await _pump(tester, server,
        device: _MemStorage(), wait: const TerminalWait());
    await _addCard(tester);

    final start = tester.binding.clock.now();
    await _pressComplete(tester);
    Duration? told;
    for (var s = 0; s < 200 && told == null; s++) {
      await tester.pump(const Duration(seconds: 1));
      if (find.byKey(const Key('tender-terminal-pending')).evaluate().isNotEmpty) {
        told = tester.binding.clock.now().difference(start);
      }
    }

    expect(told, isNotNull, reason: 'the cashier is told within 200 s');
    expect(told!, lessThanOrEqualTo(const Duration(seconds: 97)));
    expect(told, greaterThanOrEqualTo(const Duration(seconds: 90)));
    // Asks at 0, 12, … 84 seconds: the last began inside the ninety.
    expect(server.cardPresses, hasLength(8));
    expect(server.termKeys, hasLength(1));
    expect(find.text('No answer from the card machine yet'), findsOneWidget);
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
  });

  group('a split sale whose first card went through', () {
    testWidgets(
        'losing the network before the second card is asked says what was '
        'taken and what was not — never "nothing was taken on the card"',
        (tester) async {
      final server = _Server(
        presses: {
          0: ['APPROVED'],
          1: ['APPROVED'],
        },
        paymentAnswers: ['OFFLINE', 'OK'],
      );
      final container = await _pump(tester, server, device: _MemStorage());
      await _addCard(tester, amount: '6.00');
      await _addCard(tester);
      await _finish(tester);

      expect(find.byKey(const Key('tender-terminal-offline')), findsOneWidget);
      expect(find.textContaining('£6.00 was taken on the card'), findsOneWidget);
      expect(find.textContaining('not asked for the £6.00 still owed'),
          findsOneWidget);
      expect(find.textContaining('nothing was taken on the card'), findsNothing);
      expect(find.text('Card not taken'), findsNothing);
      expect(container.read(offlineQueueProvider), isEmpty,
          reason: 'a card the machine never answered is never queued as paid');
      expect(container.read(heldCardPaymentProvider)?.approved, {0});

      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();
      await _finish(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      // The first card, approved, is final: it is not asked after again, only
      // recorded; the second went once.
      expect(server.termKeys, hasLength(2));
      expect(server.cardPresses.where((p) => _key(p) == server.termKeys[0]),
          hasLength(1));
      expect(server.recorded, hasLength(2));
      expect(server.orderKeys, hasLength(1));
    });

    testWidgets(
        'a second card declined says what was taken and what is still owed, '
        'and swapping it for cash carries the first card on, never takes it '
        'again', (tester) async {
      final server = _Server(presses: {
        0: ['APPROVED'],
        1: ['DECLINED'],
      });
      final container = await _pump(tester, server, device: _MemStorage());
      await _addCard(tester, amount: '6.00');
      await _addCard(tester);
      await _finish(tester);

      final said = tester.widget<SnackBar>(find.byType(SnackBar));
      final words = (said.content as Text).data!;
      expect(words, startsWith('Card declined. '));
      expect(words, contains('£6.00 was taken on the card'));
      expect(words, contains('the £6.00 still owed'));
      expect(container.read(heldCardPaymentProvider)?.approved, {0},
          reason: 'the card taken keeps its keys');
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      // The declined card off, cash on.
      await tester.tap(find.byTooltip('Remove tender').last);
      await tester.pumpAndSettle();
      await _addCash(tester);
      await _finish(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.orderKeys, hasLength(1), reason: 'the same order');
      expect(server.termKeys, hasLength(2),
          reason: 'the first card and the declined one: no third');
      expect(server.cardPresses.where((p) => _key(p) == server.termKeys[0]),
          hasLength(1),
          reason: 'the approved card is final: never asked after again');
      expect(server.recorded, hasLength(2));
      expect(
          [for (final p in server.recorded.values) p['method']]..sort(),
          ['CARD', 'CASH']);
      expect(container.read(heldCardPaymentProvider), isNull);
    });
  });

  testWidgets(
      'a card taken whose payment record is refused keeps its keys: the next '
      'press replays it, never charges it again', (tester) async {
    final server = _Server(presses: {
      0: ['APPROVED']
    }, paymentAnswers: [
      'HTTP409',
      'OK'
    ]);
    final container = await _pump(tester, server, device: _MemStorage());
    await _addCard(tester);
    await _finish(tester);

    expect(find.text('Sale complete'), findsNothing);
    final words =
        (tester.widget<SnackBar>(find.byType(SnackBar)).content as Text).data!;
    expect(words, contains('£12.00 was taken on the card'));
    expect(words, contains('do not take the card again'));
    expect(container.read(heldCardPaymentProvider)?.approved, {0});
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();

    await _finish(tester);
    expect(find.text('Sale complete'), findsOneWidget);
    expect(server.termKeys, hasLength(1));
    expect(server.cardPresses, hasLength(1),
        reason: 'the approval is final: only its record is sent again');
    expect(server.recorded, hasLength(1));
    expect(container.read(heldCardPaymentProvider), isNull);
  });
  group('a refusal of the held card\'s replay', () {
    // The press that follows "still waiting for the card machine" asks after
    // the card again under its key; a tender recorded before it is not sent
    // again. A gateway's 503 or 429 on that ask says nothing about the card,
    // which is still at the machine under its place's key.
    for (final refusal in ['HTTP503', 'HTTP429']) {
      testWidgets(
          '$refusal never frees the card the held sale sent: a sale tendered '
          'again settles it first, and no second amount goes to the machine',
          (tester) async {
        final server = _Server(presses: {
          0: ['TIMEOUT', 'REQUESTED', refusal, 'REQUESTED']
        });
        final container = await _pump(tester, server, device: _MemStorage());
        // £12 as £6 cash and £6 on the card machine.
        await _addCash(tester, amount: '6.00');
        await _addCard(tester);
        await _stopAtTheMachine(tester);
        expect(server.recorded, hasLength(1), reason: 'the cash recorded');
        expect(container.read(heldCardPaymentProvider)?.fixed, 2);

        // Pressed again, the same sale: the card's ask is refused.
        await _finish(tester);
        expect(find.text('Sale complete'), findsNothing);
        expect(server.posts('/payments'), hasLength(1),
            reason: 'the cash recorded is not sent again');
        expect(container.read(heldCardPaymentProvider)?.fixed, 2,
            reason: 'the card at the second place was sent to the machine');
        await tester.pump(const Duration(seconds: 5)); // the snackbar goes
        await tester.pumpAndSettle();

        // Told the sale failed, the cashier takes it all on the card.
        await tester.tap(find.byTooltip('Remove tender').last);
        await tester.pumpAndSettle();
        await tester.tap(find.byTooltip('Remove tender').last);
        await tester.pumpAndSettle();
        await _addCard(tester);
        await _pressComplete(tester);
        await tester.pumpAndSettle();
        expect(server.termKeys, hasLength(1), reason: 'no second amount');
        expect(find.byKey(const Key('tender-held-at-machine')), findsOneWidget,
            reason: 'the £6 still at the machine is settled first');
        await tester.tap(find.text('Wait'));
        await tester.pumpAndSettle();

        // Or as £2 cash and £10 on the card: the card's place would present
        // the key the £6 went under, and record £10 against it.
        await tester.tap(find.byTooltip('Remove tender').last);
        await tester.pumpAndSettle();
        await _addCash(tester, amount: '2.00');
        await _addCard(tester);
        await _pressComplete(tester);
        await tester.pumpAndSettle();
        expect(server.termKeys, hasLength(1));
        expect(server.recorded, hasLength(1));
        expect(find.byKey(const Key('tender-held-at-machine')), findsOneWidget);
        await tester.tap(find.text('Wait'));
        await tester.pumpAndSettle();
      });
    }
  });

  group('a press whose screen has gone, and the press after it', () {
    /// Lets [steps] tenths of a second pass, answering the till as a cashier
    /// would: cancelling the earlier card payment when asked, closing "still
    /// waiting".
    Future<void> answerAsTheyCome(WidgetTester tester, {int steps = 120}) async {
      for (var t = 0; t < steps; t++) {
        await tester.pump(const Duration(milliseconds: 100));
        final cancel = find.byKey(const Key('tender-held-cancel'));
        if (cancel.evaluate().isNotEmpty) await tester.tap(cancel);
        if (find.byKey(const Key('tender-terminal-pending')).evaluate().isNotEmpty) {
          await tester.tap(find.text('OK'));
        }
      }
      await tester.pumpAndSettle();
    }

    // Its card comes back as the machine has it — cancelled, by the press
    // after it — or not at all (the till's own receive timeout).
    for (final comesBack in ['REQUESTED', 'TIMEOUT']) {
      testWidgets(
          'cannot let go of, or write over, the newer sale\'s hold when its own '
          'card comes back (${comesBack == 'TIMEOUT' ? 'no answer' : 'cancelled'})',
          (tester) async {
        final device = _MemStorage();
        final shown = ValueNotifier(true);
        // The first card is with the cardholder for five seconds.
        final server = _Server(presses: {
          0: [comesBack],
          1: ['REQUESTED'],
        })
          ..firstPressTakes = {0: const Duration(seconds: 5)};
        final container =
            await _pump(tester, server, device: device, shown: shown);
        await _addCard(tester);
        await _pressComplete(tester); // A: order-1, £12 to the machine
        await tester.pump(const Duration(milliseconds: 100));
        expect(server.cardPresses, hasLength(1));

        // Back to the sale, a third jar, and Complete Sale again (B) while A's
        // card is still with the cardholder.
        shown.value = false;
        await tester.pump();
        container.read(posCartProvider.notifier).setQty('v-jam', 3);
        shown.value = true;
        await tester.pump();
        await _addCard(tester);
        await _pressComplete(tester);
        await answerAsTheyCome(tester);

        // B cancelled A's payment, placed its own order and sent its own card,
        // which is at the machine now: B's is what the till holds.
        expect(server.attempts['att-1']!['state'], 'CANCELLED');
        expect(server.termKeys, hasLength(2));
        final held = container.read(heldCardPaymentProvider);
        expect(held?.orderId, 'order-2',
            reason: 'B\'s card is at the machine: its hold must stand');
        expect(device.data[heldCardPaymentStorageKey], contains('order-2'));
      });
    }

    testWidgets(
        'cannot bring back the hold of a sale the press after it completed',
        (tester) async {
      final device = _MemStorage();
      final shown = ValueNotifier(true);
      final server = _Server(presses: {
        0: ['APPROVED']
      })
        ..firstPressTakes = {0: const Duration(seconds: 5)};
      final container =
          await _pump(tester, server, device: device, shown: shown);
      await _addCard(tester);
      await _pressComplete(tester); // A
      await tester.pump(const Duration(milliseconds: 100));

      // Back and in again, the same sale, pressed again (B) while A's card is
      // with the cardholder.
      shown.value = false;
      await tester.pump();
      shown.value = true;
      await tester.pump();
      if (find.byTooltip('Remove tender').evaluate().isEmpty) {
        await _addCard(tester);
      }
      await _pressComplete(tester);
      await answerAsTheyCome(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.termKeys, hasLength(1));
      expect(server.recorded, hasLength(1));
      expect(container.read(heldCardPaymentProvider), isNull,
          reason: 'the sale is complete: nothing is held for it, and the next '
              'customer is not told their card was taken for it');
      expect(device.data.containsKey(heldCardPaymentStorageKey), isFalse);
    });
  });

  group('the same basket rung up again is another sale', () {
    // A hold is the sale's, not its content's: another customer buying the
    // same thing must never be carried on under the held sale's keys, or the
    // till records the new sale as paid by the earlier customer's card and
    // never asks the machine for this one.

    testWidgets(
        'after the till is cleared, the held sale is settled first and its '
        'card is never staged for the next customer', (tester) async {
      final device = _MemStorage();
      final shown = ValueNotifier(true);
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED'],
        1: ['APPROVED'],
      });
      final container =
          await _pump(tester, server, device: device, shown: shown);
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      // Customer A's card goes through on the machine; A leaves.
      server.settle('att-1', 'APPROVED');

      // The till is cleared and customer B rings up the same two jars.
      shown.value = false;
      await tester.pump();
      container.read(posCartProvider.notifier).clear();
      container.read(posCartProvider.notifier).addOrIncrement(_jam);
      shown.value = true;
      await tester.pumpAndSettle();
      expect(find.byTooltip('Remove tender'), findsNothing,
          reason: "A's card is not staged for B");

      await _addCard(tester);
      await _pressComplete(tester);
      await tester.pumpAndSettle();

      expect(find.text('Sale complete'), findsNothing);
      expect(find.byKey(const Key('tender-held-taken')), findsOneWidget,
          reason: 'the held sale is settled before B\'s starts');
      expect(server.orderKeys, hasLength(1));
      expect(server.recorded, isEmpty, reason: 'B is not paid by A\'s card');
      await tester.tap(find.text('Not now'));
      await tester.pumpAndSettle();
    });

    testWidgets(
        'after a restart, likewise; putting the sale back is what carries it '
        'on', (tester) async {
      final device = _MemStorage();
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED'],
        1: ['APPROVED'],
      });
      await _pump(tester, server, device: device, key: const Key('before'));
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      server.settle('att-1', 'APPROVED');

      // The app starts again with nothing on the till; B rings up the same.
      await tester.pumpWidget(const SizedBox());
      final container = await _pump(tester, server,
          device: device, lines: const [], key: const Key('after'));
      container.read(posCartProvider.notifier).addOrIncrement(_jam);
      await tester.pumpAndSettle();
      await _addCard(tester);
      await _pressComplete(tester);
      await tester.pumpAndSettle();

      expect(find.text('Sale complete'), findsNothing);
      expect(find.byKey(const Key('tender-held-taken')), findsOneWidget);
      expect(server.recorded, isEmpty);
      expect(server.cardPresses.map(_key).toSet(), hasLength(1));

      // The cashier says this is that sale: put back, it is finished under
      // its own keys — once.
      await tester.tap(find.byKey(const Key('tender-held-put-back')));
      await tester.pumpAndSettle();
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.termKeys, hasLength(1));
      expect(server.orderKeys, hasLength(1));
      expect(server.recorded, hasLength(1));
      expect(container.read(heldCardPaymentProvider), isNull);
    });
  });

  group('a hold belongs to the business that made it', () {
    testWidgets(
        'in the sandbox the live hold is never read, settled or let go, nothing '
        'of its sale is offered, and a card sale waits; back in live it is '
        'finished', (tester) async {
      final device = _MemStorage();
      final shown = ValueNotifier(true);
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED']
      });
      final auth = _TillAuth('OWNER', 'live');
      server.viewer = 'live';
      final container = await _pump(tester, server,
          device: device, auth: () => auth, shown: shown);
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      final held = container.read(heldCardPaymentProvider)!;

      // The owner goes into the sandbox, a business of its own.
      auth.become('sandbox');
      server.viewer = 'sandbox';
      shown.value = false;
      await tester.pump();
      container.read(posCartProvider.notifier).clear();
      shown.value = true;
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-held-sale-put-back')), findsNothing,
          reason: 'the live sale and its customer are not the sandbox\'s');
      expect(find.byKey(const Key('tender-held-elsewhere')), findsOneWidget);

      // A cash sale in the sandbox goes ahead and leaves the hold alone.
      container.read(posCartProvider.notifier).addOrIncrement(_tea);
      await tester.pumpAndSettle();
      await _addCash(tester);
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.attemptsReads, isEmpty,
          reason: 'read under the sandbox, the live attempts come back empty');
      expect(container.read(heldCardPaymentProvider)?.base, held.base);
      expect(device.data[heldCardPaymentStorageKey], contains(held.base));
      Navigator.of(tester.element(find.text('Sale complete'))).pop();
      await tester.pumpAndSettle();

      // A card sale there waits: the hold is not written over.
      container.read(posCartProvider.notifier).addOrIncrement(_tea);
      await tester.pumpAndSettle();
      await _addCard(tester);
      final ordersBefore = server.orderKeys.length;
      await _pressComplete(tester);
      await tester.pumpAndSettle();
      expect(find.textContaining('held on this till for another business'),
          findsOneWidget);
      expect(server.orderKeys, hasLength(ordersBefore));
      expect(server.termKeys, hasLength(1));
      expect(container.read(heldCardPaymentProvider)?.base, held.base);
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      // Back in live: the sale is offered back and finished, once.
      auth.become('live');
      server.viewer = 'live';
      container.read(posCartProvider.notifier).clear();
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-held-sale-put-back')), findsOneWidget);
      await tester.tap(find.byKey(const Key('tender-held-sale-put-back')));
      await tester.pumpAndSettle();
      server.settle('att-1', 'APPROVED');
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.termKeys, hasLength(1));
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets(
        'another business signed in on the device after a sign-out does not '
        'free it either', (tester) async {
      final device = _MemStorage();
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED']
      });
      server.viewer = 'live';
      await _pump(tester, server,
          device: device,
          auth: () => _TillAuth('CASHIER', 'live'),
          key: const Key('before'));
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      final base = jsonDecode(device.data[heldCardPaymentStorageKey]!)['base'];

      await tester.pumpWidget(const SizedBox());
      server.viewer = 'other';
      final container = await _pump(tester, server,
          device: device,
          auth: () => _TillAuth('MANAGER', 'other'),
          lines: const [_tea],
          key: const Key('after'));
      await _addCash(tester);
      await _finish(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.attemptsReads, isEmpty);
      expect(container.read(heldCardPaymentProvider)?.base, base);
      expect(device.data[heldCardPaymentStorageKey], contains(base));
    });
  });

  testWidgets(
      'a gift card sale carried on while offline, whose first card was taken '
      'and second sent, is never told "nothing was sent"', (tester) async {
    final card = PosLine.giftCardSale(amount: 20, currency: 'GBP', code: 'GC-1');
    final server = _Server(presses: {
      0: ['APPROVED'],
      1: ['TIMEOUT', 'REQUESTED'],
    });
    final container =
        await _pump(tester, server, device: _MemStorage(), lines: [card]);
    await _addCard(tester, amount: '10.00');
    await _addCard(tester);
    await _stopAtTheMachine(tester);
    expect(container.read(heldCardPaymentProvider)?.approved, {0});
    expect(container.read(heldCardPaymentProvider)?.fixed, 2);

    // The network drops; pressed again, the order's replay never answers.
    server.ordersOffline = true;
    await _finish(tester);

    expect(find.textContaining('Nothing was sent'), findsNothing);
    expect(find.byKey(const Key('tender-terminal-offline')), findsOneWidget);
    expect(find.textContaining('£10.00 was taken on the card'), findsOneWidget);
    expect(find.textContaining('before it heard about the card payment of '
        '£10.00'), findsOneWidget);
    expect(container.read(heldCardPaymentProvider)?.approved, {0});
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
  });
  group('a card machine an earlier payment holds (409 TERMINAL_UNSETTLED_APPROVAL)',
      () {
    // payment-svc refuses a new card on a machine while an earlier card payment
    // on it is not settled. The till says which, and offers only the ways out:
    // finish that sale, a manager putting the money back with a reason, or a
    // manager recording what the machine shows for one it never answered.

    /// An earlier sale's payment on the machine, from a till that lost its
    /// hold: [state] as the machine left it.
    _Server held(String state, {String role = 'CASHIER'}) => _Server(presses: {
          0: ['APPROVED']
        })
          ..guard = true
          ..orders['order-9'] = {'status': 'PENDING', 'total': 7.5}
          ..attempts['att-9'] = {
            'id': 'att-9',
            'orderId': 'order-9',
            'tenant': 't',
            'kind': 'SALE',
            'amount': '7.50',
            'currency': 'GBP',
            'state': state,
            'panLast4': '1111',
          };

    Future<ProviderContainer> refused(WidgetTester tester, _Server server,
        {String role = 'CASHIER'}) async {
      final container =
          await _pump(tester, server, device: _MemStorage(), role: role);
      await _addCard(tester);
      await _finish(tester);
      expect(find.byKey(const Key('tender-terminal-unsettled')), findsOneWidget);
      expect(find.text('Sale complete'), findsNothing);
      expect(server.termKeys, isEmpty, reason: 'the machine was not asked');
      expect(
          server.attemptsReads.map((r) => r.path),
          contains(endsWith('/terminal/by-order/order-9')),
          reason: 'what holds it is read from its own order');
      return container;
    }

    Future<void> pressAgainAndFinish(WidgetTester tester, _Server server) async {
      await tester.pump(const Duration(seconds: 5)); // the snackbar goes
      await tester.pumpAndSettle();
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.orderKeys, hasLength(1), reason: 'the same order');
      expect(server.termKeys, hasLength(1));
    }

    testWidgets(
        'an approval nobody recorded on another sale is a manager\'s call: a '
        'cashier is not offered it, and nothing is recorded', (tester) async {
      final server = held('APPROVED');
      final container = await refused(tester, server);
      expect(
          find.textContaining(
              '£7.50 was taken on the card (card ending 1111) for an earlier '
              'sale'),
          findsOneWidget);
      expect(find.byKey(const Key('tender-unsettled-finish-att-9')),
          findsNothing,
          reason: 'whether that sale\'s customer paid another way is not the '
              'till\'s to guess, nor a cashier\'s to say');
      expect(find.textContaining('a manager records it on that sale'),
          findsOneWidget);
      expect(find.byKey(const Key('tender-unsettled-reverse-att-9')),
          findsNothing,
          reason: 'putting money back is a manager\'s');
      expect(find.byKey(const Key('tender-unsettled-settle-att-9')),
          findsNothing);
      // This sale's card place was never sent: it stays open.
      expect(container.read(heldCardPaymentProvider)?.fixed, 0);
      await tester.tap(find.text('Close'));
      await tester.pumpAndSettle();
      expect(server.posts('/payments'), isEmpty);
    });

    testWidgets(
        'a manager may record it on that sale, once told what that means — '
        'never a default button — the card recorded on its own order, naming '
        'the payment; and is told that its customer has paid: never to press '
        'again and take the card twice', (tester) async {
      final server = held('APPROVED');
      await refused(tester, server, role: 'MANAGER');

      // Asked first, with what makes it right; backing out records nothing.
      await tester.tap(find.byKey(const Key('tender-unsettled-finish-att-9')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-unsettled-finish-ask')),
          findsOneWidget);
      expect(find.textContaining('has not paid for them another way'),
          findsOneWidget);
      expect(find.textContaining('put the money back on the card instead'),
          findsOneWidget);
      expect(server.posts('/payments'), isEmpty);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(server.posts('/payments'), isEmpty);

      await _finish(tester);
      await tester.tap(find.byKey(const Key('tender-unsettled-finish-att-9')));
      await tester.pumpAndSettle();
      await tester
          .tap(find.byKey(const Key('tender-unsettled-finish-confirm')));
      await tester.pumpAndSettle();
      final record = server.posts('/payments').single;
      expect(record.data, {
        'orderId': 'order-9',
        'amount': '7.50',
        'method': 'CARD',
        'currency': 'GBP',
        'terminalPaymentId': 'att-9',
      });
      expect(_key(record), derivedId('att-9', 'record'));
      expect(find.byKey(const Key('tender-unsettled-finished')), findsOneWidget);
      expect(find.textContaining('is recorded on the sale it was taken for'),
          findsOneWidget);
      expect(find.textContaining('do not take their card again'),
          findsOneWidget);
      expect(find.textContaining('Press Complete Sale again'), findsNothing,
          reason: 'the customer at the till may be the one who paid it');
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();
      expect(server.termKeys, isEmpty, reason: 'nothing more was charged');
      expect(find.text('Sale complete'), findsNothing);
    });

    for (final role in ['CASHIER', 'MANAGER']) {
      testWidgets(
          'an approval that arrived after a manager recorded it as not taken '
          'is never recorded on its sale from any till — that sale\'s customer '
          'was told nothing was taken: a manager puts it back on the card '
          '($role)', (tester) async {
        // Another till's sale, or one this till no longer knows: all this
        // till has is what payment-svc says of the attempt.
        final server = held('APPROVED');
        server.attempts['att-9']!['decision'] = {
          'outcome': 'NOT_TAKEN',
          'reason': 'Screen blank',
        };
        await refused(tester, server, role: role);
        expect(find.byKey(const Key('tender-unsettled-finish-att-9')),
            findsNothing,
            reason: 'order-9 reads unpaid, but its customer paid another way '
                'or left');
        expect(find.textContaining('recorded it as not taken'), findsOneWidget);
        expect(find.byKey(const Key('tender-unsettled-reverse-att-9')),
            role == 'MANAGER' ? findsOneWidget : findsNothing);
        await tester.tap(find.text('Close'));
        await tester.pumpAndSettle();
        expect(server.posts('/payments'), isEmpty);
      });
    }

    for (final (why, status, before) in [
      ('paid already', 'CONFIRMED', 0.0),
      ('it would pay twice', 'PENDING', 7.5),
    ]) {
      for (final role in ['CASHIER', 'MANAGER']) {
        testWidgets(
            'an approval on a sale $why is never finished on it: a '
            'manager puts it back on the card ($role)', (tester) async {
          final server = held('APPROVED')
            ..orders['order-9'] = {'status': status, 'total': 7.5};
          if (before > 0) {
            server.recorded['cash'] = {
              'id': 'pay-cash',
              'orderId': 'order-9',
              'amount': before,
              'method': 'CASH',
              'status': 'CAPTURED',
            };
          }
          await refused(tester, server, role: role);
          expect(find.byKey(const Key('tender-unsettled-finish-att-9')),
              findsNothing);
          expect(find.textContaining('already paid'), findsOneWidget);
          expect(find.byKey(const Key('tender-unsettled-reverse-att-9')),
              role == 'MANAGER' ? findsOneWidget : findsNothing);
          await tester.tap(find.text('Close'));
          await tester.pumpAndSettle();
          expect(server.posts('/payments'), isEmpty);
        });
      }
    }

    testWidgets(
        'an approval on this till\'s own sale it no longer knows — a late one, '
        'after "nothing taken" — is recorded in the hold: put back, the sale '
        'finishes with it, never asked for again', (tester) async {
      final server = _Server(presses: {
        0: ['TIMED_OUT']
      });
      final container =
          await _pump(tester, server, device: _MemStorage(), role: 'MANAGER');
      // £6 cash, £6 on the card; the card machine does not answer.
      await _addCash(tester, amount: '6.00');
      await _addCard(tester);
      await _finish(tester);
      await tester.tap(find.byKey(const Key('tender-uncertain-settle')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-not-taken')));
      await tester.enterText(
          find.byKey(const Key('tender-settle-reason-field')), 'Screen blank');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-confirm')));
      await tester.pumpAndSettle();
      expect(container.read(heldCardPaymentProvider)?.attemptIds, isEmpty);
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      // The machine's late answer: it took the card after all.
      server.attempts['att-1']!['state'] = 'APPROVED';
      server.guard = true;
      await _finish(tester);
      expect(find.byKey(const Key('tender-terminal-unsettled')), findsOneWidget);
      // The till says what it knows: the customer was told nothing was taken.
      expect(
          find.textContaining('If the customer has paid for this sale another '
              'way since, do not finish it with this payment'),
          findsOneWidget);
      await tester.tap(find.byKey(const Key('tender-unsettled-finish-att-1')));
      await tester.pumpAndSettle();
      expect(container.read(heldCardPaymentProvider)?.approved, {1});
      expect(container.read(heldCardPaymentProvider)?.attemptIds,
          {1: 'att-1'});
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      final presses = server.cardPresses.length;
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.cardPresses, hasLength(presses),
          reason: 'the approval is recorded, the machine not asked again');
      expect(
          [for (final p in server.recorded.values) p['terminalPaymentId']],
          [null, 'att-1']);
      expect(server.orderKeys, hasLength(1));
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets(
        'a manager puts it back on the card instead, with a reason the server '
        'keeps', (tester) async {
      final server = held('APPROVED');
      await refused(tester, server, role: 'MANAGER');
      await tester.tap(find.byKey(const Key('tender-unsettled-reverse-att-9')));
      await tester.pumpAndSettle();
      final confirm = find.byKey(const Key('tender-unsettled-reverse-confirm'));
      expect(tester.widget<FilledButton>(confirm).onPressed, isNull,
          reason: 'not without a reason');
      await tester.enterText(
          find.byKey(const Key('tender-unsettled-reverse-reason-field')),
          'Customer left without the goods');
      await tester.pumpAndSettle();
      await tester.tap(confirm);
      await tester.pumpAndSettle();

      final refund = server.refunds.single;
      expect(refund.path, endsWith('/payments/terminal/att-9/refunds'));
      expect(refund.data,
          {'amount': '7.50', 'reason': 'Customer left without the goods'});
      expect(_key(refund), derivedId('att-9', 'reverse:att-9'));
      expect(server.posts('/payments'), isEmpty);
      expect(find.textContaining('£7.50 put back on the card'), findsOneWidget);

      await pressAgainAndFinish(tester, server);
    });

    testWidgets(
        'one the machine never answered: only a manager records what it '
        'shows, with a reason, through the settle', (tester) async {
      final cashier = held('TIMED_OUT');
      await refused(tester, cashier);
      expect(find.textContaining('the card may have been charged'),
          findsOneWidget);
      expect(find.byKey(const Key('tender-unsettled-settle-att-9')),
          findsNothing);
      expect(find.byKey(const Key('tender-unsettled-finish-att-9')),
          findsNothing,
          reason: 'nothing is known to have been taken');
      await tester.tap(find.text('Close'));
      await tester.pumpAndSettle();
      expect(cashier.settles, isEmpty);
    });

    testWidgets(
        'a manager records it as not taken, and the press after goes ahead',
        (tester) async {
      final server = held('TIMED_OUT');
      await refused(tester, server, role: 'MANAGER');
      await tester.tap(find.byKey(const Key('tender-unsettled-settle-att-9')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-not-taken')));
      await tester.enterText(find.byKey(const Key('tender-settle-reason-field')),
          'Machine log shows it declined');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-confirm')));
      await tester.pumpAndSettle();

      final settle = server.settles.single;
      expect(settle.path, endsWith('/payments/terminal/att-9/settle'));
      expect(settle.data,
          {'outcome': 'NOT_TAKEN', 'reason': 'Machine log shows it declined'});
      expect(isV7(_key(settle)), isTrue);
      await pressAgainAndFinish(tester, server);
    });

    testWidgets(
        'a payment still at the machine cannot be settled before it has had '
        'its time to answer: the manager is told when', (tester) async {
      final server = held('REQUESTED')
        ..inFlightUntil = '2026-10-02T10:15:00Z';
      await refused(tester, server, role: 'MANAGER');
      expect(find.textContaining('still at the card machine'), findsOneWidget);
      await tester.tap(find.byKey(const Key('tender-unsettled-settle-att-9')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-approved')));
      await tester.enterText(
          find.byKey(const Key('tender-settle-reason-field')), 'Looked');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-confirm')));
      await tester.pumpAndSettle();
      expect(server.settles, hasLength(1));
      expect(find.textContaining('may still answer this payment'),
          findsOneWidget);
      expect(find.textContaining('record what it shows after'), findsOneWidget);
    });

    testWidgets(
        'a sale queued on this till for the order named is sent first: its '
        'tender is what settles the machine', (tester) async {
      final server = held('APPROVED');
      final container =
          await _pump(tester, server, device: _MemStorage(), role: 'CASHIER');
      await container.read(offlineQueueProvider.notifier).enqueue(OfflineSale(
            id: newId(),
            capturedAt: DateTime.utc(2026, 10, 2, 9),
            storeId: 'store-1',
            currency: 'GBP',
            orderId: 'order-9',
            total: 7.5,
            itemCount: 1,
            orderRequest: const {},
            tenders: const [
              OfflineTender(body: {
                'amount': 7.5,
                'method': 'CARD',
                'storeId': 'store-1',
                'terminalPaymentId': 'att-9',
              }, amount: 7.5),
            ],
          ));
      await _addCard(tester);
      await _finish(tester);

      expect(find.byKey(const Key('tender-terminal-unsettled')), findsNothing);
      expect(server.posts('/payments').single.data['terminalPaymentId'],
          'att-9');
      expect(container.read(offlineQueueProvider), isEmpty);
      expect(find.textContaining('was sent to the server just now'),
          findsOneWidget);
      await pressAgainAndFinish(tester, server);
    });

    /// A sale this till completed offline for [orderId], its card approved as
    /// [attemptId] and still to be recorded.
    OfflineSale queuedSale(String orderId,
            {String? attemptId, double amount = 7.5}) =>
        OfflineSale(
          id: newId(),
          capturedAt: DateTime.utc(2026, 10, 2, 9),
          storeId: 'store-1',
          currency: 'GBP',
          orderId: orderId,
          total: amount,
          itemCount: 1,
          orderRequest: const {},
          tenders: [
            OfflineTender(body: {
              'amount': amount,
              'method': attemptId == null ? 'CASH' : 'CARD',
              'storeId': 'store-1',
              'terminalPaymentId': ?attemptId,
            }, amount: amount),
          ],
        );

    testWidgets(
        'the queued sale for the order named is sent whatever waits ahead of '
        'it in line: a sale before it that cannot be sent yet does not keep '
        'the machine held', (tester) async {
      final server = held('APPROVED')..refusePaymentsFor.add('order-head');
      final container =
          await _pump(tester, server, device: _MemStorage(), role: 'CASHIER');
      final queue = container.read(offlineQueueProvider.notifier);
      await queue.enqueue(queuedSale('order-head'));
      await queue.enqueue(queuedSale('order-9', attemptId: 'att-9'));
      await _addCard(tester);
      await _finish(tester);

      expect(find.byKey(const Key('tender-terminal-unsettled')), findsNothing);
      expect(server.recorded.values.single['terminalPaymentId'], 'att-9');
      expect(
          container.read(offlineQueueProvider).map((s) => s.orderId),
          ['order-head'],
          reason: 'only the sale that holds the machine was sent out of turn');
      expect(find.textContaining('was sent to the server just now'),
          findsOneWidget);
      await pressAgainAndFinish(tester, server);
    });

    for (final role in ['CASHIER', 'MANAGER']) {
      testWidgets(
          'a queued sale for the order named that cannot be sent yet is never '
          'finished or put back from here: its own replay records the card '
          '($role)', (tester) async {
        final server = held('APPROVED')..refusePaymentsFor.add('order-9');
        final container =
            await _pump(tester, server, device: _MemStorage(), role: role);
        await container
            .read(offlineQueueProvider.notifier)
            .enqueue(queuedSale('order-9', attemptId: 'att-9'));
        await _addCard(tester);
        await _finish(tester);

        expect(find.byKey(const Key('tender-terminal-unsettled')),
            findsOneWidget);
        expect(find.textContaining('kept on this till'), findsOneWidget);
        expect(find.byKey(const Key('tender-unsettled-finish-att-9')),
            findsNothing,
            reason: 'recorded here under another key, the queued sale\'s own '
                'tender would be refused on every replay');
        expect(find.byKey(const Key('tender-unsettled-reverse-att-9')),
            findsNothing,
            reason: 'that sale is complete: its customer paid by that card');
        expect(container.read(offlineQueueProvider), hasLength(1));
        await tester.tap(find.text('Close'));
        await tester.pumpAndSettle();
        expect(server.recorded, isEmpty);
        expect(server.refunds, isEmpty);
      });
    }
  });

  group('a late approval after a manager\'s "nothing taken"', () {
    // A manager records a card the machine never answered as not taken, and
    // the machine's own answer arrives afterwards: approved. payment-svc keeps
    // it — the money is on the card — and the machine is held. The customer
    // was told nothing was taken, so they paid another way, or left. The till
    // must never record that approval beside what they paid: the sale keeps
    // its order (so the approval finds it paid, or finishes it once), and an
    // order the till does let go is given up, never left reading "unpaid".

    /// A card-only sale whose card the machine does not answer, recorded by
    /// the manager as not taken.
    Future<(_Server, ProviderContainer, _MemStorage)> notTaken(
        WidgetTester tester) async {
      final device = _MemStorage();
      final server = _Server(presses: {
        0: ['TIMED_OUT'],
        1: ['APPROVED'],
      });
      final container = await _pump(tester, server,
          device: device, role: 'MANAGER', key: const Key('before'));
      await _addCard(tester);
      await _finish(tester);
      await tester.tap(find.byKey(const Key('tender-uncertain-settle')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-not-taken')));
      await tester.enterText(
          find.byKey(const Key('tender-settle-reason-field')), 'Screen blank');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-confirm')));
      await tester.pumpAndSettle();
      await tester.pump(const Duration(seconds: 5)); // the snackbar goes
      await tester.pumpAndSettle();
      return (server, container, device);
    }

    /// The machine's own answer arrives after the manager's word: approved.
    void lateApproval(_Server server, {bool keepDecision = true}) {
      server.attempts['att-1']!['state'] = 'APPROVED';
      if (!keepDecision) server.attempts['att-1']!.remove('decision');
      server.guard = true;
    }

    /// The till starts again and the next customer pays by card on the same
    /// machine.
    Future<ProviderContainer> nextCardSale(
        WidgetTester tester, _Server server, _MemStorage device) async {
      await tester.pumpWidget(const SizedBox());
      final container = await _pump(tester, server,
          device: device,
          lines: const [_tea],
          role: 'MANAGER',
          key: const Key('after'));
      await _addCard(tester);
      await _finish(tester);
      return container;
    }

    testWidgets(
        'a card-only sale keeps its order: paid in cash instead it is the same '
        'order, so the late approval finds that sale paid and is never '
        'recorded beside the cash — a manager puts it back on the card',
        (tester) async {
      final (server, container, device) = await notTaken(tester);
      final held = container.read(heldCardPaymentProvider);
      expect(held?.orderId, 'order-1', reason: 'the order placed is not let go');
      expect(held?.fixed, 0, reason: 'its card place may change');
      expect(held?.termTries, {0: 1},
          reason: 'a card taken there again goes under a key of its own');

      await tester.tap(find.byTooltip('Remove tender'));
      await tester.pumpAndSettle();
      await _addCash(tester);
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.orderKeys, hasLength(1),
          reason: 'the same order: the basket is not rung up a second time');
      expect(server.recorded.values.single['method'], 'CASH');
      expect(server.recorded.values.single['orderId'], 'order-1');
      expect(container.read(heldCardPaymentProvider), isNull);

      lateApproval(server);
      await nextCardSale(tester, server, device);
      expect(find.byKey(const Key('tender-terminal-unsettled')), findsOneWidget);
      expect(find.byKey(const Key('tender-unsettled-finish-att-1')),
          findsNothing);
      expect(find.byKey(const Key('tender-unsettled-reverse-att-1')),
          findsOneWidget);
      await tester.tap(find.text('Close'));
      await tester.pumpAndSettle();
      expect([for (final p in server.recorded.values) p['method']], ['CASH'],
          reason: 'one basket, one payment');
    });

    testWidgets(
        'the card taken again for it goes to the machine under a key of its '
        'own, on the same order', (tester) async {
      final (server, container, _) = await notTaken(tester);
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.orderKeys, hasLength(1));
      expect(server.termKeys, hasLength(2));
      expect(server.recorded.values.single['terminalPaymentId'], 'att-2');
      expect(server.recorded.values.single['orderId'], 'order-1');
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets(
        'the approval arriving while the sale is still on the till finishes '
        'that sale: one order, one payment, the card never taken again',
        (tester) async {
      final (server, container, _) = await notTaken(tester);
      lateApproval(server);
      await _finish(tester);
      expect(find.byKey(const Key('tender-terminal-unsettled')), findsOneWidget);
      await tester.tap(find.byKey(const Key('tender-unsettled-finish-att-1')));
      await tester.pumpAndSettle();
      expect(container.read(heldCardPaymentProvider)?.approved, {0});
      expect(container.read(heldCardPaymentProvider)?.attemptIds,
          {0: 'att-1'});
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.orderKeys, hasLength(1));
      expect(server.termKeys, hasLength(1),
          reason: 'no second amount ever reached the machine');
      expect(server.recorded.values.single['terminalPaymentId'], 'att-1');
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets(
        'the approval arriving before a changed sale is paid: the till says '
        'it followed "not taken", the manager puts it back on the card, the '
        'order is given up and the changed sale is paid once', (tester) async {
      final (server, container, _) = await notTaken(tester);
      lateApproval(server);
      container.read(posCartProvider.notifier).setQty('v-jam', 3);
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Remove tender').first);
      await tester.pumpAndSettle();
      await _addCash(tester);
      await _pressComplete(tester);
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('tender-held-taken')), findsOneWidget);
      expect(
          find.textContaining('approved it after a manager had recorded it as '
              'not taken'),
          findsOneWidget);
      expect(server.orderKeys, hasLength(1), reason: 'nothing new started');
      await tester.tap(find.byKey(const Key('tender-held-reverse')));
      await tester.pumpAndSettle();
      await tester.enterText(
          find.byKey(const Key('tender-reverse-reason-field')),
          'Customer paying cash instead');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-reverse-confirm')));
      await _letTimePass(tester);
      await tester.pumpAndSettle();

      expect(server.refunds.single.path,
          endsWith('/payments/terminal/att-1/refunds'));
      expect(server.refunds.single.data,
          {'amount': '12.00', 'reason': 'Customer paying cash instead'});
      expect(server.orderCancels.single.path,
          endsWith('/orders/order-1/cancel'));
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.recorded.values.single['method'], 'CASH');
      expect(server.recorded.values.single['orderId'], 'order-2');
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets(
        'a payment left at the machine, recorded as not taken from "Still '
        'waiting", keeps its order too', (tester) async {
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED']
      });
      final container =
          await _pump(tester, server, device: _MemStorage(), role: 'MANAGER');
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      await _pressComplete(tester);
      await _letTimePass(tester);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-pending-settle')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-not-taken')));
      await tester.enterText(
          find.byKey(const Key('tender-settle-reason-field')),
          'Nothing on the machine');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-confirm')));
      await tester.pumpAndSettle();
      expect(server.settles.single.data['outcome'], 'NOT_TAKEN');
      expect(container.read(heldCardPaymentProvider)?.orderId, 'order-1');
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Remove tender'));
      await tester.pumpAndSettle();
      await _addCash(tester);
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.orderKeys, hasLength(1));
      expect(server.recorded.values.single['orderId'], 'order-1');
    });

    testWidgets(
        'recorded as not taken from elsewhere, the sale asked after again '
        'keeps its order too', (tester) async {
      final server = _Server(presses: {
        0: ['TIMED_OUT']
      });
      final container = await _pump(tester, server, device: _MemStorage());
      await _addCard(tester);
      await _finish(tester);
      await tester.tap(find.text('Not now'));
      await tester.pumpAndSettle();

      // A manager records it from another till.
      server.attempts['att-1']!['decision'] = {
        'outcome': 'NOT_TAKEN',
        'reason': 'Seen on the machine',
      };
      await _finish(tester);
      expect(find.textContaining('Nothing was taken'), findsOneWidget);
      expect(container.read(heldCardPaymentProvider)?.orderId, 'order-1',
          reason: 'the machine may yet say it took the card for this order');
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('Remove tender'));
      await tester.pumpAndSettle();
      await _addCash(tester);
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.orderKeys, hasLength(1));
      expect(server.recorded.values.single['orderId'], 'order-1');
    });

    for (final cancelFails in [false, true]) {
      testWidgets(
          'a changed sale lets the held order go only by giving it up: the '
          'order is cancelled before the new one is placed '
          '${cancelFails ? '— and where that cannot be done, this till still '
              'never records a later approval on it' : 'so payment-svc itself '
              'puts back whatever its card takes later'}', (tester) async {
        final device = _MemStorage();
        final server = _Server(presses: {
          0: ['TIMED_OUT'],
          1: ['APPROVED'],
        })
          ..orderCancelFails = cancelFails;
        final container = await _pump(tester, server,
            device: device, role: 'MANAGER', key: const Key('before'));
        await _addCard(tester);
        await _finish(tester);
        await tester.tap(find.text('Not now'));
        await tester.pumpAndSettle();

        // The basket changes, and is paid in cash: the held sale is settled
        // first, by the manager's record of what the machine shows.
        container.read(posCartProvider.notifier).setQty('v-jam', 3);
        await tester.pumpAndSettle();
        await tester.tap(find.byTooltip('Remove tender').first);
        await tester.pumpAndSettle();
        await _addCash(tester);
        await _pressComplete(tester);
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('tender-held-settle')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('tender-settle-not-taken')));
        await tester.enterText(
            find.byKey(const Key('tender-settle-reason-field')), 'Screen blank');
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('tender-settle-confirm')));
        await _letTimePass(tester);
        await tester.pumpAndSettle();

        expect(find.text('Sale complete'), findsOneWidget);
        expect(server.orderKeys, hasLength(2));
        final cancel = server.orderCancels.single;
        expect(cancel.path, endsWith('/orders/order-1/cancel'));
        expect((cancel.data as Map)['reason'], isNotEmpty);
        expect(
            server.requests.indexOf(cancel),
            lessThan(server.requests.indexOf(server.posts('/orders').last)),
            reason: 'given up before the changed sale\'s order is placed');
        expect(container.read(heldCardPaymentProvider), isNull);
        expect(server.recorded.values.single['orderId'], 'order-2');

        // The machine's late answer on the order let go — as an approval
        // with nobody's word on it, so only the till's own record of having
        // let the order go can say it is not to be finished.
        lateApproval(server, keepDecision: false);
        await nextCardSale(tester, server, device);
        if (!cancelFails) {
          // Given up, so payment-svc owes it back itself and the machine is
          // free: the next card goes through.
          expect(find.text('Sale complete'), findsOneWidget);
          return;
        }
        expect(find.byKey(const Key('tender-terminal-unsettled')),
            findsOneWidget);
        expect(find.byKey(const Key('tender-unsettled-finish-att-1')),
            findsNothing,
            reason: 'order-1 reads unpaid, but its goods never left on it');
        expect(find.textContaining('let go at this till'), findsOneWidget);
        expect(find.byKey(const Key('tender-unsettled-reverse-att-1')),
            findsOneWidget);
        await tester.tap(find.text('Close'));
        await tester.pumpAndSettle();
        expect([for (final p in server.recorded.values) p['orderId']],
            ['order-2'],
            reason: 'nothing is recorded on the order let go');
      });
    }

    testWidgets(
        'after an unreadable hold is cleared, the card it may stand for is '
        'never a one-press record: the customer was sent to another tender, '
        'so the manager is told what recording it means first', (tester) async {
      final device = _MemStorage()
        ..data[heldCardPaymentStorageKey] = '{not json';
      // The sale the unreadable hold stood for: its card approved on the
      // machine, nothing recorded on its order.
      final server = _Server(presses: {
        0: ['APPROVED']
      })
        ..orders['order-9'] = {'status': 'PENDING', 'total': 12.0}
        ..attempts['att-9'] = {
          'id': 'att-9',
          'orderId': 'order-9',
          'tenant': 't',
          'kind': 'SALE',
          'amount': '12.00',
          'currency': 'GBP',
          'state': 'APPROVED',
          'panLast4': '1111',
        };
      await _pump(tester, server,
          device: device, role: 'MANAGER', key: const Key('before'));
      // As the till says: another tender. The customer pays in cash.
      await _addCash(tester);
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);

      await tester.pumpWidget(const SizedBox());
      await _pump(tester, server,
          device: device,
          lines: const [_tea],
          role: 'MANAGER',
          key: const Key('after'));
      await tester.tap(find.byKey(const Key('tender-hold-corrupt-clear')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-hold-corrupt-confirm')));
      await tester.pumpAndSettle();
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
      server.guard = true;
      await _addCard(tester);
      await _finish(tester);

      expect(find.byKey(const Key('tender-terminal-unsettled')), findsOneWidget);
      await tester.tap(find.byKey(const Key('tender-unsettled-finish-att-9')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-unsettled-finish-ask')),
          findsOneWidget);
      expect(find.textContaining('or the sale was rung up again'),
          findsOneWidget);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect([for (final p in server.recorded.values) p['method']], ['CASH'],
          reason: 'the cash the customer paid, and no card beside it');
    });
  });

  group('a card place a refusal shows was never sent', () {
    // A refusal payment-svc makes before it asks any machine, of a card place
    // this hold never sent, leaves nothing at a machine: the place may change.
    // A gateway's 5xx says nothing about the machine, TERMINAL_REQUEST_IN_FLIGHT
    // is a press of that key still going, and an ask that went unanswered may
    // have reached the machine: those keep the place.
    for (final (answer, reopens) in [
      (['HTTP409'], true),
      (['INFLIGHT'], false),
      (['HTTP503'], false),
      (['TIMEOUT', 'HTTP409'], false),
    ]) {
      testWidgets(
          '${answer.join(' then ')} ${reopens ? 'reopens it: swapped for cash, '
              'the press carries the same order on' : 'keeps it'}',
          (tester) async {
        final server = _Server(presses: {0: answer});
        final container = await _pump(tester, server, device: _MemStorage());
        await _addCash(tester, amount: '6.00');
        await _addCard(tester);
        await _finish(tester);
        expect(find.text('Sale complete'), findsNothing);
        expect(server.recorded, hasLength(1), reason: 'the cash recorded');
        expect(container.read(heldCardPaymentProvider)?.fixed, reopens ? 1 : 2);
        if (!reopens) return;
        await tester.pump(const Duration(seconds: 5)); // the snackbar goes
        await tester.pumpAndSettle();

        await tester.tap(find.byTooltip('Remove tender').last);
        await tester.pumpAndSettle();
        await _addCash(tester);
        await _finish(tester);

        expect(find.byKey(const Key('tender-held-at-machine')), findsNothing);
        expect(find.byKey(const Key('tender-held-taken')), findsNothing);
        expect(find.text('Sale complete'), findsOneWidget);
        expect(server.orderKeys, hasLength(1), reason: 'the same order');
        expect(server.recorded, hasLength(2),
            reason: 'the first cash is not taken again under a new order');
        expect(container.read(heldCardPaymentProvider), isNull);
      });
    }
  });

  testWidgets(
      'a machine retired while its card is at it is read within the time the '
      'press began with — never a fresh wait', (tester) async {
    const wait = TerminalWait(
        every: Duration(milliseconds: 100), limit: Duration(milliseconds: 1000));
    final server = _Server(presses: {
      0: ['TIMEOUT', 'REQUESTED']
    });
    await _pump(tester, server, device: _MemStorage(), wait: wait);
    await _addCard(tester);
    await _stopAtTheMachine(tester);

    // Retired, and its refusal is slow to come back: most of the press's time
    // is gone before the till can read the attempt.
    server.retired = true;
    server.retiredTakes = const Duration(milliseconds: 700);
    final readsBefore = server.attemptReads.length;
    final start = tester.binding.clock.now();
    await _pressComplete(tester);
    Duration? told;
    for (var t = 0; t < 40 && told == null; t++) {
      await tester.pump(const Duration(milliseconds: 100));
      if (find.byKey(const Key('tender-terminal-pending')).evaluate().isNotEmpty) {
        told = tester.binding.clock.now().difference(start);
      }
    }
    expect(told, isNotNull);
    expect(told!, lessThanOrEqualTo(const Duration(milliseconds: 1200)),
        reason: 'the read shares the press\'s second, never a fresh one');
    expect(server.attemptReads.length - readsBefore, lessThanOrEqualTo(4));
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
  });

  group('the hold is written before the card machine is asked', () {
    testWidgets(
        'a hold the device will not take sends no amount to the machine, and '
        'the cashier is told; once it can, the same order goes ahead',
        (tester) async {
      final device = _MemStorage();
      final server = _Server(presses: {
        0: ['APPROVED']
      });
      final container = await _pump(tester, server, device: device);
      await _addCard(tester);
      device.failWrites = true;
      await _finish(tester);

      expect(find.byKey(const Key('tender-hold-not-saved')), findsOneWidget);
      expect(find.textContaining('was not asked for £12.00'), findsOneWidget);
      expect(server.cardPresses, isEmpty, reason: 'no hold, no amount');
      expect(find.text('Sale complete'), findsNothing);
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();

      device.failWrites = false;
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.orderKeys, hasLength(1));
      expect(server.termKeys, hasLength(1));
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    for (final role in ['CASHIER', 'MANAGER']) {
      testWidgets(
          'a hold the till cannot read keeps the card machine out of use until '
          'a manager clears it; other tenders go ahead ($role)', (tester) async {
        final device = _MemStorage()
          ..data[heldCardPaymentStorageKey] = '{not json';
        final server = _Server(presses: {
          0: ['APPROVED']
        });
        final container =
            await _pump(tester, server, device: device, role: role);
        expect(device.data[heldCardPaymentCorruptStorageKey], '{not json');
        expect(find.byKey(const Key('tender-hold-corrupt')), findsOneWidget);
        expect(find.byKey(const Key('tender-hold-corrupt-clear')),
            role == 'MANAGER' ? findsOneWidget : findsNothing);

        await _addCard(tester);
        await _finish(tester);
        expect(find.textContaining('could not be read'), findsWidgets);
        expect(server.orderKeys, isEmpty, reason: 'nothing was started');
        expect(server.cardPresses, isEmpty);
        await tester.pump(const Duration(seconds: 5));
        await tester.pumpAndSettle();

        if (role == 'CASHIER') {
          await tester.tap(find.byTooltip('Remove tender'));
          await tester.pumpAndSettle();
          await _addCash(tester);
          await _finish(tester);
          expect(find.text('Sale complete'), findsOneWidget);
          expect(server.cardPresses, isEmpty);
          return;
        }
        await tester.tap(find.byKey(const Key('tender-hold-corrupt-clear')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('tender-hold-corrupt-confirm')));
        await tester.pumpAndSettle();
        expect(find.byKey(const Key('tender-hold-corrupt')), findsNothing);
        expect(device.data.containsKey(heldCardPaymentCorruptStorageKey),
            isFalse);
        expect(container.read(heldCardPaymentProvider.notifier).corrupt.value,
            isFalse);
        await tester.pump(const Duration(seconds: 5));
        await tester.pumpAndSettle();
        await _finish(tester);
        expect(find.text('Sale complete'), findsOneWidget);
        expect(server.termKeys, hasLength(1));
      });
    }
  });

  group('what the earlier press recorded stays with its order', () {
    // A tender recorded on the held order — a gift card redeemed, store credit,
    // cash — is that order's. Letting the hold go to a new order would take it
    // again under keys of its own.

    Future<void> addGiftCard(WidgetTester tester) async {
      await tester.tap(find.widgetWithText(OutlinedButton, 'Gift card'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).last, 'GC-1');
      await tester.tap(find.byTooltip('Check balance'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Add'));
      await tester.pumpAndSettle();
    }

    testWidgets(
        'a gift card redeemed before a card left at the machine is never '
        'redeemed again: the card cancelled and swapped for cash, the press '
        'carries the same order on', (tester) async {
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED']
      });
      final container = await _pump(tester, server, device: _MemStorage());
      await addGiftCard(tester); // £8 of the £12
      await _addCard(tester); // £4 on the machine
      await _stopAtTheMachine(tester);
      expect(server.redeems, hasLength(1));
      expect(container.read(heldCardPaymentProvider)?.paid, {0});

      // The customer offers cash instead: the card comes off, cash goes on.
      await tester.tap(find.byTooltip('Remove tender').last);
      await tester.pumpAndSettle();
      await _addCash(tester);
      await _pressComplete(tester);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-held-at-machine')), findsOneWidget);
      await tester.tap(find.byKey(const Key('tender-held-cancel')));
      await _letTimePass(tester);
      await tester.pumpAndSettle();

      expect(server.attempts['att-1']!['state'], 'CANCELLED');
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.redeems, hasLength(1), reason: 'the gift card once');
      expect(server.orderKeys, hasLength(1), reason: 'the same order');
      expect(server.recorded.values.single['method'], 'CASH');
      expect(server.termKeys, hasLength(1));
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    for (final role in ['CASHIER', 'MANAGER']) {
      testWidgets(
          'cash recorded before a card declined, then the basket changed: no '
          'new order takes it again — the sale is put back, or a manager '
          'cancels it with a reason ($role)', (tester) async {
        final server = _Server(presses: {
          0: ['DECLINED'],
          1: ['APPROVED'],
        });
        final container =
            await _pump(tester, server, device: _MemStorage(), role: role);
        await _addCash(tester, amount: '6.00');
        await _addCard(tester);
        await _finish(tester);
        expect(server.recorded, hasLength(1));
        expect(container.read(heldCardPaymentProvider)?.paid, {0});
        await tester.pump(const Duration(seconds: 5)); // the snackbar goes
        await tester.pumpAndSettle();

        // A third jar; the declined card off, cash for the rest.
        container.read(posCartProvider.notifier).setQty('v-jam', 3);
        await tester.pumpAndSettle();
        await tester.tap(find.byTooltip('Remove tender').last);
        await tester.pumpAndSettle();
        await _addCash(tester);
        await _pressComplete(tester);
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('tender-held-recorded')), findsOneWidget);
        expect(find.textContaining('£6.00 in cash'), findsOneWidget);
        expect(find.text('Sale complete'), findsNothing);
        expect(server.orderKeys, hasLength(1), reason: 'no new order');
        expect(server.recorded, hasLength(1), reason: 'the cash not again');
        expect(find.byKey(const Key('tender-held-close')),
            role == 'MANAGER' ? findsOneWidget : findsNothing);

        if (role == 'MANAGER') {
          await tester.tap(find.byKey(const Key('tender-held-close')));
          await tester.pumpAndSettle();
          expect(find.textContaining('refunded the way it was paid'),
              findsOneWidget);
          await tester.enterText(
              find.byKey(const Key('tender-close-reason-field')),
              'Customer added a jar');
          await tester.pumpAndSettle();
          await tester.tap(find.byKey(const Key('tender-close-confirm')));
          await tester.pumpAndSettle();
          expect(server.orderCancels.single.path, endsWith('/orders/order-1/cancel'));
          expect(container.read(heldCardPaymentProvider), isNull);
          await tester.pump(const Duration(seconds: 5));
          await tester.pumpAndSettle();
          await _finish(tester);
          expect(find.text('Sale complete'), findsOneWidget);
          expect(server.orderKeys, hasLength(2),
              reason: 'a new order once the earlier one is cancelled');
          return;
        }

        await tester.tap(find.byKey(const Key('tender-held-put-back')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('tender-put-back-replace')));
        await tester.pumpAndSettle();
        expect(container.read(posCartProvider).single.qty, 2);
        await tester.tap(find.byTooltip('Remove tender').last);
        await tester.pumpAndSettle();
        await _addCash(tester);
        await _finish(tester);
        expect(find.text('Sale complete'), findsOneWidget);
        expect(server.orderKeys, hasLength(1));
        expect(server.recorded, hasLength(2));
        expect(container.read(heldCardPaymentProvider), isNull);
      });
    }

    testWidgets(
        'a manager is not offered the card back while a gift card redeemed on '
        'the sale may not show as its tender yet', (tester) async {
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED']
      });
      await _pump(tester, server, device: _MemStorage(), role: 'MANAGER');
      await addGiftCard(tester);
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      server.settle('att-1', 'APPROVED');
      // payment-svc records a gift card's tender from GiftCardRedeemed, after
      // the redeem: the order's tenders read none yet.
      final field = find.byKey(const Key('tender-phone-field'));
      await tester.ensureVisible(field);
      await tester.enterText(field, '07700900111');
      await tester.pumpAndSettle();
      await _pressComplete(tester);
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('tender-held-taken')), findsOneWidget);
      expect(find.byKey(const Key('tender-held-reverse')), findsNothing);
      expect(server.refunds, isEmpty);
      await tester.tap(find.text('Not now'));
      await tester.pumpAndSettle();
    });
  });

  group('a card payment no cancel stops', () {
    for (final role in ['MANAGER', 'CASHIER']) {
      testWidgets(
          'left at the machine after a restart of payment-svc: after the cancel '
          'wait a manager records what the machine shows, and the changed sale '
          'goes ahead ($role)', (tester) async {
        final server = _Server(presses: {
          0: ['TIMEOUT', 'REQUESTED'],
          1: ['APPROVED'],
        })
          ..cancelIgnored = true;
        await _pump(tester, server, device: _MemStorage(), role: role);
        await _addCard(tester);
        await _stopAtTheMachine(tester);
        final field = find.byKey(const Key('tender-phone-field'));
        await tester.ensureVisible(field);
        await tester.enterText(field, '07700900111');
        await tester.pumpAndSettle();
        await _pressComplete(tester);
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('tender-held-cancel')));
        await _letTimePass(tester);
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('tender-held-still-at-machine')),
            findsOneWidget);
        expect(server.orderKeys, hasLength(1));
        if (role == 'CASHIER') {
          expect(find.byKey(const Key('tender-held-still-settle')),
              findsNothing);
          expect(find.textContaining('Ask a manager'), findsOneWidget);
          await tester.tap(find.text('OK'));
          await tester.pumpAndSettle();
          expect(server.settles, isEmpty);
          return;
        }
        await tester.tap(find.byKey(const Key('tender-held-still-settle')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('tender-settle-not-taken')));
        await tester.enterText(
            find.byKey(const Key('tender-settle-reason-field')),
            'Machine restarted, nothing on it');
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('tender-settle-confirm')));
        await _letTimePass(tester);
        await tester.pumpAndSettle();

        final settle = server.settles.single;
        expect(settle.path, endsWith('/payments/terminal/att-1/settle'));
        expect(settle.data, {
          'outcome': 'NOT_TAKEN',
          'reason': 'Machine restarted, nothing on it',
        });
        expect(find.text('Sale complete'), findsOneWidget);
        expect(server.orderKeys, hasLength(2));
        expect(server.posts('/orders').last.data['contactPhone'],
            '07700900111');
        expect(server.orderCancels.single.path,
            endsWith('/orders/order-1/cancel'),
            reason: 'the order let go is given up: the machine may yet answer');
      });
    }

    testWidgets(
        'carried on, still at the machine: a manager records it as taken from '
        '"Still waiting", and the next press records it without asking the '
        'machine', (tester) async {
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED']
      });
      final container =
          await _pump(tester, server, device: _MemStorage(), role: 'MANAGER');
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      await _pressComplete(tester);
      await _letTimePass(tester);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-terminal-pending')), findsOneWidget);
      await tester.tap(find.byKey(const Key('tender-pending-settle')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-approved')));
      await tester.enterText(
          find.byKey(const Key('tender-settle-reason-field')),
          'Receipt shows approved');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-confirm')));
      await tester.pumpAndSettle();

      expect(server.settles.single.data['outcome'], 'APPROVED');
      expect(container.read(heldCardPaymentProvider)?.approved, {0});
      expect(container.read(heldCardPaymentProvider)?.attemptIds,
          {0: 'att-1'});
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      final presses = server.cardPresses.length;
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.cardPresses, hasLength(presses));
      expect(server.recorded.values.single['terminalPaymentId'], 'att-1');
    });

    testWidgets(
        'money being put back that the machine never answered: the sale is '
        'not put back — its record would be refused — a manager records what '
        'the machine shows, and once it went back the changed sale goes ahead',
        (tester) async {
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED'],
        1: ['APPROVED'],
      })
        ..refundState = 'TIMED_OUT';
      final container =
          await _pump(tester, server, device: _MemStorage(), role: 'MANAGER');
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      server.settle('att-1', 'APPROVED');
      final field = find.byKey(const Key('tender-phone-field'));
      await tester.ensureVisible(field);
      await tester.enterText(field, '07700900111');
      await tester.pumpAndSettle();
      await _pressComplete(tester);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-held-reverse')));
      await tester.pumpAndSettle();
      await tester.enterText(
          find.byKey(const Key('tender-reverse-reason-field')), 'Wrong number');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-reverse-confirm')));
      await _letTimePass(tester);
      await tester.pumpAndSettle();
      expect(server.refunds, hasLength(1));
      expect(container.read(heldCardPaymentProvider), isNotNull);
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      // A cash sale, or any press, meets it next.
      await _pressComplete(tester);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-held-taken')), findsOneWidget);
      expect(find.byKey(const Key('tender-held-put-back')), findsNothing);
      await tester.tap(find.byKey(const Key('tender-held-refund-settle')));
      await tester.pumpAndSettle();
      expect(find.text('It shows the money went back on the card'),
          findsOneWidget);
      await tester.tap(find.byKey(const Key('tender-settle-approved')));
      await tester.enterText(
          find.byKey(const Key('tender-settle-reason-field')),
          'Refund receipt printed');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-confirm')));
      await _letTimePass(tester);
      await tester.pumpAndSettle();

      expect(server.settles.single.path,
          endsWith('/payments/terminal/ref-1/settle'));
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.orderKeys, hasLength(2));
      expect(container.read(heldCardPaymentProvider), isNull);
    });
  });

  group('a held approval somebody else recorded', () {
    // Two tills share the machine. Till A's card is approved and its record
    // gets a gateway 503; till B, refused a card on the machine, finishes
    // A's sale. A's own record of the approval is then refused as already
    // recorded — which is the tender it was sending.
    Future<(_Server, ProviderContainer)> recordedElsewhere(
        WidgetTester tester) async {
      final server = _Server(presses: {
        0: ['APPROVED']
      })
        ..refuseNextPayments.add('HTTP503');
      final container = await _pump(tester, server, device: _MemStorage());
      await _addCard(tester);
      await _finish(tester);
      expect(find.text('Sale complete'), findsNothing);
      expect(container.read(heldCardPaymentProvider)?.approved, {0});
      expect(container.read(heldCardPaymentProvider)?.paid, isEmpty);
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();
      server.recordElsewhere('att-1');
      return (server, container);
    }

    testWidgets('pressed again, the sale finishes on that record', (tester) async {
      final (server, container) = await recordedElsewhere(tester);
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.cardPresses, hasLength(1));
      expect(server.recorded, hasLength(1), reason: 'recorded once, by B');
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets(
        'a changed sale reads it recorded: put back, it finishes without '
        'sending the record again', (tester) async {
      final (server, container) = await recordedElsewhere(tester);
      final field = find.byKey(const Key('tender-phone-field'));
      await tester.ensureVisible(field);
      await tester.enterText(field, '07700900111');
      await tester.pumpAndSettle();
      await _pressComplete(tester);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-held-taken')), findsOneWidget);
      expect(container.read(heldCardPaymentProvider)?.paid, {0});
      await tester.tap(find.byKey(const Key('tender-held-put-back')));
      await tester.pumpAndSettle();
      final posted = server.posts('/payments').length;
      await _finish(tester);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.posts('/payments'), hasLength(posted));
      expect(server.orderKeys, hasLength(1));
      expect(container.read(heldCardPaymentProvider), isNull);
    });
  });

  testWidgets(
      'a sale cancelled or voided since it was rung up is let go: nothing more '
      'is recorded for it, and the next press is a new sale', (tester) async {
    final server = _Server(presses: {
      0: ['APPROVED']
    })
      ..refuseNextPayments.add('GIVEN_UP');
    final container = await _pump(tester, server, device: _MemStorage());
    await _addCard(tester);
    await _finish(tester);

    expect(find.text('Sale complete'), findsNothing);
    expect(find.textContaining('cancelled or voided'), findsOneWidget);
    expect(find.textContaining('goes back on the card'), findsOneWidget);
    expect(container.read(heldCardPaymentProvider), isNull,
        reason: 'payment-svc owes the card back; nothing is left to finish');
  });
  // The drawer a sale is rung on is where the cash is counted; it is not what
  // the sale IS. A held card payment is carried on only while the sale is what
  // it was, and the drawer changing between two presses - a till opened or
  // closed on the Cash tab while the cardholder is still at the machine - used
  // to read as a different sale: the card taken for the first press could then
  // only be put back, for ever, because every press named the new drawer.
  group('a drawer that changes between two presses', () {
    testWidgets(
        'does not make a held card sale a different one: the next press '
        'carries it on under the same keys, naming the drawer open now',
        (tester) async {
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED']
      });
      final container = await _pump(tester, server,
          device: _MemStorage(),
          overrides: [saleTillProvider.overrideWith(() => _Drawer('drawer-1'))]);
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      final held = container.read(heldCardPaymentProvider)!;

      // The cashier opens another drawer; the cardholder finishes at the machine.
      container.read(saleTillProvider.notifier).opened('drawer-2');
      server.settle('att-1', 'APPROVED');
      await _finish(tester);

      expect(find.byKey(const Key('tender-held-taken')), findsNothing,
          reason: 'it is the same sale, not "a changed sale"');
      expect(find.byKey(const Key('tender-held-recorded')), findsNothing);
      expect(server.orderCancels, isEmpty);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.termKeys, hasLength(1),
          reason: 'one card payment: a second key would be a second amount');
      expect(server.orderKeys, hasLength(1));
      expect(container.read(heldCardPaymentProvider), isNull);
      expect(held.base, isNotEmpty);
      expect(server.recorded.values.single['tillSessionId'], 'drawer-2',
          reason: 'the money is counted in the drawer open when it is recorded');
    });

    testWidgets(
        'nor does a drawer that was not open at the first press and is by '
        'the second', (tester) async {
      final server = _Server(presses: {
        0: ['TIMEOUT', 'REQUESTED']
      });
      final container = await _pump(tester, server,
          device: _MemStorage(),
          overrides: [saleTillProvider.overrideWith(() => _Drawer(null))]);
      await _addCard(tester);
      await _stopAtTheMachine(tester);

      container.read(saleTillProvider.notifier).opened('drawer-1');
      server.settle('att-1', 'APPROVED');
      await _finish(tester);

      expect(find.byKey(const Key('tender-held-taken')), findsNothing);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(server.termKeys, hasLength(1));
      expect(server.orderKeys, hasLength(1));
    });
  });

  // A manager cancels the earlier sale of a held card payment (its cash, gift
  // card or store credit already recorded on its order, so a new order would
  // take them again): the cash it took is handed back out of a drawer, so the
  // cancel names the one open now - order-svc carries it on OrderCancelled and
  // payment-svc counts the refund there while it is this business's open
  // session at the tender's store, else "not at a till". A cancel is never
  // refused over it, so there is no second try without it.
  group('the drawer a cancelled earlier sale hands back from', () {
    /// A held sale with cash recorded on its order and the basket then changed,
    /// up to the dialog where a manager may cancel the earlier sale.
    Future<(_Server, ProviderContainer)> toTheEarlierSale(
        WidgetTester tester, List<Override> overrides) async {
      final server = _Server(presses: {
        0: ['DECLINED'],
        1: ['APPROVED'],
      });
      final container = await _pump(tester, server,
          device: _MemStorage(), role: 'MANAGER', overrides: overrides);
      await _addCash(tester, amount: '6.00');
      await _addCard(tester);
      await _finish(tester);
      await tester.pump(const Duration(seconds: 5)); // the snackbar goes
      await tester.pumpAndSettle();
      container.read(posCartProvider.notifier).setQty('v-jam', 3);
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Remove tender').last);
      await tester.pumpAndSettle();
      await _addCash(tester);
      await _pressComplete(tester);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-held-recorded')), findsOneWidget);
      return (server, container);
    }

    /// Cancel the earlier sale, saying why, and press the confirming button.
    Future<void> cancelIt(WidgetTester tester) async {
      await tester.tap(find.byKey(const Key('tender-held-close')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('tender-close-reason-field')),
          'Customer added a jar');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-close-confirm')));
      await tester.pump(const Duration(milliseconds: 1));
    }

    testWidgets('the cancel names the open drawer, which hands the cash back',
        (tester) async {
      final (server, container) = await toTheEarlierSale(tester,
          [saleTillProvider.overrideWith(() => _Drawer('drawer-1'))]);

      await cancelIt(tester);
      await tester.pumpAndSettle();

      final cancel = server.orderCancels.single;
      expect(cancel.path, endsWith('/orders/order-1/cancel'));
      expect(cancel.data,
          {'reason': 'Customer added a jar', 'tillSessionId': 'drawer-1'});
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets('with no drawer open it names none, and is not held back',
        (tester) async {
      final (server, container) = await toTheEarlierSale(
          tester, [saleTillProvider.overrideWith(() => _Drawer(null))]);

      await cancelIt(tester);
      await tester.pumpAndSettle();

      expect(server.orderCancels.single.data, {'reason': 'Customer added a jar'},
          reason: 'no guess: with no drawer the cash is "not at a till"');
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets(
        'a drawer being read again is waited for, not skipped: the cancel '
        'names the drawer it finds', (tester) async {
      final drawer = _ScriptedDrawer(['drawer-1', 'drawer-2']);
      final (server, container) = await toTheEarlierSale(
          tester, [saleTillProvider.overrideWith(() => drawer)]);
      // The server would not count the first drawer: the terminal reads again,
      // and the answer is still on its way when the cancel is confirmed.
      drawer.hold = Completer<void>();
      container.read(saleTillProvider.notifier).refused('drawer-1');
      await tester.pump();

      await cancelIt(tester);
      await tester.pump(const Duration(seconds: 1));
      expect(server.orderCancels, isEmpty,
          reason: 'the drawer is still being looked up');

      drawer.hold!.complete();
      await tester.pumpAndSettle();

      expect(server.orderCancels.single.data,
          {'reason': 'Customer added a jar', 'tillSessionId': 'drawer-2'});
    });

    testWidgets(
        'a drawer the terminal could not read a moment ago is read again, so '
        'the cancel still names it', (tester) async {
      final drawer =
          _ScriptedDrawer(['drawer-1', _ScriptedDrawer.down, 'drawer-3']);
      final (server, container) = await toTheEarlierSale(
          tester, [saleTillProvider.overrideWith(() => drawer)]);
      container.read(saleTillProvider.notifier).refused('drawer-1');
      await tester.pumpAndSettle();
      expect(container.read(saleTillProvider).hasError, isTrue);

      await cancelIt(tester);
      await tester.pumpAndSettle();

      expect(server.orderCancels.single.data,
          {'reason': 'Customer added a jar', 'tillSessionId': 'drawer-3'});
    });

    testWidgets(
        'a read that never answers does not stop the cancel: after a few '
        'seconds it goes out naming none, and the till can sell again',
        (tester) async {
      final drawer = _ScriptedDrawer(['drawer-1', 'drawer-2']);
      final (server, container) = await toTheEarlierSale(
          tester, [saleTillProvider.overrideWith(() => drawer)]);
      drawer.hold = Completer<void>(); // the payment service never answers
      container.read(saleTillProvider.notifier).refused('drawer-1');
      await tester.pump();

      await cancelIt(tester);
      await tester.pump(const Duration(seconds: 1));
      expect(server.orderCancels, isEmpty,
          reason: 'it gives the read a moment first');
      for (var i = 0; i < 4 && server.orderCancels.isEmpty; i++) {
        await tester.pump(const Duration(seconds: 3));
      }
      await tester.pumpAndSettle();

      expect(server.orderCancels.single.data, {'reason': 'Customer added a jar'});
      expect(container.read(heldCardPaymentProvider), isNull,
          reason: 'the earlier sale is let go: the till can sell again');
      drawer.hold!.complete();
      await tester.pumpAndSettle();
    });

    testWidgets(
        'an order let go unfinished hands back nothing, so its cancel names '
        'no drawer', (tester) async {
      // A card that took nothing, then the basket changed: the held order has
      // no tender recorded on it, so giving it up moves no cash.
      final server = _Server(presses: {
        0: ['TIMED_OUT'],
        1: ['APPROVED'],
      });
      final container = await _pump(tester, server,
          device: _MemStorage(),
          role: 'MANAGER',
          overrides: [saleTillProvider.overrideWith(() => _Drawer('drawer-1'))]);
      await _addCard(tester);
      await _finish(tester);
      await tester.tap(find.text('Not now'));
      await tester.pumpAndSettle();

      container.read(posCartProvider.notifier).setQty('v-jam', 3);
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Remove tender').first);
      await tester.pumpAndSettle();
      await _addCash(tester);
      await _pressComplete(tester);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-held-settle')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-not-taken')));
      await tester.enterText(
          find.byKey(const Key('tender-settle-reason-field')), 'Screen blank');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-confirm')));
      await _letTimePass(tester);
      await tester.pumpAndSettle();

      expect(find.text('Sale complete'), findsOneWidget);
      final cancel = server.orderCancels.single;
      expect(cancel.path, endsWith('/orders/order-1/cancel'));
      expect((cancel.data as Map).containsKey('tillSessionId'), isFalse,
          reason: 'nothing was recorded on that order: nothing is handed back');
      expect(container.read(heldCardPaymentProvider), isNull);
    });
  });

  group('what makes a tender "the same one"', () {
    // payment-svc replays a tender by its key whatever else the body says, and
    // takes a tender again naming no drawer when it will not count one: the
    // drawer is where cash is counted, not what the tender is.
    const body = {
      'amount': 12.0,
      'method': 'CARD',
      'storeId': 'store-1',
      'reference': 'AUTH-77',
    };

    test('the drawer is not part of it', () {
      expect(tenderFingerprint({...body, 'tillSessionId': 'drawer-1'}, 'term-1'),
          tenderFingerprint({...body, 'tillSessionId': 'drawer-2'}, 'term-1'));
      expect(tenderFingerprint({...body, 'tillSessionId': 'drawer-1'}, 'term-1'),
          tenderFingerprint(body, 'term-1'),
          reason: 'a drawer opened between two presses is the same tender');
    });

    test('everything else that makes a tender what it is, is', () {
      final base = tenderFingerprint({...body, 'tillSessionId': 'drawer-1'}, 'term-1');
      expect(tenderFingerprint({...body, 'amount': 12.5}, 'term-1'), isNot(base));
      expect(tenderFingerprint({...body, 'method': 'CASH'}, 'term-1'), isNot(base));
      expect(tenderFingerprint({...body, 'reference': 'AUTH-78'}, 'term-1'),
          isNot(base));
      expect(tenderFingerprint({...body, 'storeId': 'store-2'}, 'term-1'),
          isNot(base));
      expect(tenderFingerprint(body, 'term-2'), isNot(base),
          reason: 'another card machine is another payment');
      expect(tenderFingerprint(body, null), isNot(base));
    });
  });
}
