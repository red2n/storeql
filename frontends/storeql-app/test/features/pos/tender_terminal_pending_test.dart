import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/ids.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/offline/offline_queue.dart';
import 'package:storeql_app/core/storage/app_storage.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/pos/held_card_payment.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';
import 'package:storeql_app/features/pos/pos_terminal.dart';
import 'package:storeql_app/features/pos/tender_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A card payment that is still at the machine (07.16).
//
// payment-svc settles an attempt before it answers the press that started it,
// so a cardholder who takes longer over a PIN than the till's own receive
// timeout (10 s) leaves the till's press with no answer. That is not the till
// being offline: the amount is at the machine. It used to be read as offline,
// the sale queued and a receipt printed, and the sale replayed later as a plain
// CARD tender whatever the machine decided.
//
// The till now asks again under the same Idempotency-Key. payment-svc answers a
// repeat with the attempt it already has — REQUESTED (201) while the first press
// is still at the machine, or the settled attempt — and never asks the machine
// twice. The till says what it is waiting on, reads the attempt until it
// settles, and goes on from what the machine said. If the wait runs out it stops
// without failing, keeps the sale and its keys, and a press of Complete Sale
// after that presents the SAME keys. A sale whose card on a machine has no
// answer is never queued as paid.
//
// The held keys outlive the screen (heldCardPaymentProvider), and a sale that
// changed in the meantime starts nothing new — no order, no amount at the
// machine — until the held payment is settled: read, then waited for or
// cancelled on the machine; a card it approved puts the sale back as it was.
// ---------------------------------------------------------------------------

class _MemStorage implements AppStorage {
  final Map<String, String> data = {};

  @override
  Future<String?> read({required String key}) async => data[key];

  @override
  Future<void> write({required String key, required String? value}) async {
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

class _NoopPosSessionNotifier extends PosSessionNotifier {
  _NoopPosSessionNotifier(super.ref);

  @override
  Future<void> restore() async {}
}

class _StubAuthNotifier extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthUnauthenticated();
}

const _jam = PosLine(
  variantId: 'v-jam',
  sku: 'JAM-1',
  name: 'Strawberry jam',
  qty: 2,
  unitPrice: 6.0,
  currency: 'GBP',
);

class _LoadedCart extends PosCartNotifier {
  _LoadedCart() {
    loadLines(const [_jam]);
  }
}

/// A till server with one card machine. [saleStates] is what each press of
/// POST /payments/terminal is answered with (the attempt's state, the last one
/// repeating; [_timeout] is a press the till's own receive timeout ends, as a
/// cardholder slow over a PIN does) and [readStates] what each read of the
/// attempt is. [ordersOffline] answers the first so many order posts as a lost
/// network would.
///
/// [byOrderStates] is what each read of the attempts against the first order
/// finds (the first press's attempt, the last state repeating), and
/// [byOrderOffline] makes that read fail as a lost network would.
/// [cancelState] is what a cancel on the machine ends the attempt as.
class _Till implements HttpClientAdapter {
  _Till({
    required this.saleStates,
    required this.readStates,
    this.ordersOffline = 0,
    this.byOrderStates = const ['REQUESTED'],
    this.cancelState = 'CANCELLED',
  });

  final List<String> saleStates;
  final List<String> readStates;
  final List<String> byOrderStates;
  final String cancelState;
  int ordersOffline;
  bool byOrderOffline = false;
  final List<RequestOptions> requests = [];
  int _sales = 0;
  int _reads = 0;
  int _byOrder = 0;

  /// What a manager recorded the machine as showing for att-1 (payment-svc's
  /// settle): APPROVED or NOT_TAKEN, on every answer about it from then on.
  String? decided;

  @override
  void close({bool force = false}) {}

  String _attemptBody(String state) {
    final decision = decided == null || state != 'TIMED_OUT'
        ? ''
        : ',"decision":{"outcome":"$decided","reason":"seen"}';
    return switch (state) {
      'APPROVED' => '{"id":"att-1","state":"APPROVED","scheme":"VISA",'
          '"panLast4":"4242","receiptLine":"VISA DEBIT ****4242 (CHIP, PIN)",'
          '"amount":"12.00","currency":"GBP","kind":"SALE"}',
      'DECLINED' => '{"id":"att-1","state":"DECLINED",'
          '"outcomeDetail":"DECLINED — insufficient funds",'
          '"amount":"12.00","currency":"GBP","kind":"SALE"}',
      _ => '{"id":"att-1","state":"$state","amount":"12.00",'
          '"currency":"GBP","kind":"SALE"$decision}',
    };
  }

  String _attempt(String state) => '{"data":${_attemptBody(state)}}';

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
      if (ordersOffline > 0) {
        ordersOffline--;
        throw DioException(
            requestOptions: o, type: DioExceptionType.connectionError);
      }
      // The same key finds the same order; a new key places another.
      final keys = <String>{
        for (final r in posts('/orders')) _key(r),
      }.toList();
      final n = keys.indexOf(_key(o)) + 1;
      return jsonResponse('{"data":{"id":"order-$n","total":12.0}}', 201);
    }
    if (o.method == 'GET' &&
        path.endsWith('/payments/terminal/by-order/order-1')) {
      if (byOrderOffline) {
        throw DioException(
            requestOptions: o, type: DioExceptionType.connectionError);
      }
      final state = byOrderStates[_byOrder < byOrderStates.length
          ? _byOrder++
          : byOrderStates.length - 1];
      return jsonResponse('{"data":[${_attemptBody(state)}]}');
    }
    if (o.method == 'POST' && path.endsWith('/payments/terminal/att-1/cancel')) {
      return jsonResponse(_attempt(cancelState));
    }
    if (o.method == 'POST' && path.endsWith('/payments/terminal/att-1/settle')) {
      decided = (o.data as Map)['outcome'] as String;
      return jsonResponse(_attempt('TIMED_OUT'));
    }
    if (o.method == 'POST' && path.endsWith('/payments/terminal')) {
      final state = saleStates[_sales < saleStates.length ? _sales++ : saleStates.length - 1];
      if (state == _timeout) {
        throw DioException(
            requestOptions: o, type: DioExceptionType.receiveTimeout);
      }
      return jsonResponse(_attempt(state), 201);
    }
    if (o.method == 'GET' && path.endsWith('/payments/terminal/att-1')) {
      final state = readStates[_reads < readStates.length ? _reads++ : readStates.length - 1];
      return jsonResponse(_attempt(state));
    }
    if (o.method == 'POST' && path.endsWith('/payments')) {
      return jsonResponse('{"data":{"id":"pay-1"}}', 201);
    }
    if (path.endsWith('/fiscal-receipt')) {
      return jsonResponse('{"data":{"fullNumber":"2026-000042","regime":"NONE"}}');
    }
    return jsonResponse('{"data":{}}');
  }

  List<RequestOptions> posts(String suffix) => requests
      .where((r) => r.method == 'POST' && r.path.endsWith(suffix))
      .toList();

  List<RequestOptions> get attemptReads => requests
      .where((r) => r.method == 'GET' && r.path.endsWith('/payments/terminal/att-1'))
      .toList();

  List<RequestOptions> get orderAttemptReads => requests
      .where((r) => r.method == 'GET' && r.path.contains('/by-order/'))
      .toList();

  /// The cancels asked of the card machine.
  List<RequestOptions> get cancels => requests
      .where((r) =>
          r.method == 'POST' &&
          r.path.contains('/payments/terminal/') &&
          r.path.endsWith('/cancel'))
      .toList();

  /// The orders given up: cancelled at order-svc.
  List<RequestOptions> get orderCancels => requests
      .where((r) =>
          r.method == 'POST' &&
          r.path.contains('/orders/') &&
          r.path.endsWith('/cancel'))
      .toList();

  List<RequestOptions> get settles => requests
      .where((r) => r.method == 'POST' && r.path.endsWith('/settle'))
      .toList();
}

String _key(RequestOptions r) => r.headers['Idempotency-Key'] as String;

/// A press of the card machine that the till's own receive timeout ends.
const _timeout = 'TIMEOUT';

/// A tenth of a second between asks, on the test's own clock: long enough that
/// a test sees the till waiting, short enough to run out. [_patient] has time
/// for five asks after the press and [_brief] for two.
const _patient = TerminalWait(
    every: Duration(milliseconds: 100), limit: Duration(milliseconds: 550));
const _brief = TerminalWait(
    every: Duration(milliseconds: 100), limit: Duration(milliseconds: 250));

Future<(_Till, ProviderContainer)> _pump(
  WidgetTester tester, {
  required List<String> saleStates,
  required List<String> readStates,
  required TerminalWait wait,
  Size size = const Size(800, 1200),
  int ordersOffline = 0,
  List<String> byOrderStates = const ['REQUESTED'],
  String cancelState = 'CANCELLED',
  ValueNotifier<bool>? shown,
  String? role,
}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final till = _Till(
      saleStates: saleStates,
      readStates: readStates,
      ordersOffline: ordersOffline,
      byOrderStates: byOrderStates,
      cancelState: cancelState);
  // The screen can be taken away and brought back under the same providers, as
  // Back to Sale and the Tender tab do.
  final visible = shown ?? ValueNotifier(true);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = till;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      offlineQueueProvider.overrideWith((ref) =>
          OfflineQueueNotifier(ref, storage: _MemStorage(), autoSync: false)),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      authNotifierProvider.overrideWith(
          role == null ? _StubAuthNotifier.new : () => RoleAuth(role)),
      posCartProvider.overrideWith((ref) => _LoadedCart()),
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
      terminalWaitProvider.overrideWithValue(wait),
      // The wait is timed on the test's own clock, which moves as it pumps.
      terminalClockProvider.overrideWithValue(() => tester.binding.clock.now()),
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
  container.read(authNotifierProvider);
  return (till, container);
}

/// Stage a card tender for the whole balance (2 × £6.00). The store has the one
/// card machine, so it is chosen without asking.
Future<void> _addCard(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(OutlinedButton, 'Card'));
  await tester.pumpAndSettle();
  await tester.tap(find.widgetWithText(FilledButton, 'Add'));
  await tester.pumpAndSettle();
}

/// Press Complete Sale and let what is ready run: the order is placed and the
/// machine is asked, a millisecond of time being enough for the requests to land
/// but not for the first pause between asks to end. The sale then waits on
/// timers, so what follows pumps time rather than settling (the busy button
/// never does).
Future<void> _pressComplete(WidgetTester tester) async {
  final button = find.widgetWithText(FilledButton, 'Complete Sale');
  await tester.ensureVisible(button);
  await tester.tap(button);
  await tester.pump(const Duration(milliseconds: 1));
}

/// Lets the waiting run on: each ask pauses on a timer, which fires only as time
/// passes.
Future<void> _letTimePass(WidgetTester tester, {int steps = 30}) async {
  for (var i = 0; i < steps; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

const _waiting = Key('tender-terminal-waiting');
const _pendingDialog = Key('tender-terminal-pending');
const _offlineDialog = Key('tender-terminal-offline');
const _atMachineDialog = Key('tender-held-at-machine');
const _takenDialog = Key('tender-held-taken');
const _unknownDialog = Key('tender-held-unknown');
const _uncertainHeldDialog = Key('tender-held-uncertain');

/// Press Complete Sale and let the wait run out with the card still at the
/// machine, then close "Still waiting for the card machine".
Future<void> _stopAtTheMachine(WidgetTester tester) async {
  await _pressComplete(tester);
  await _letTimePass(tester);
  await tester.pumpAndSettle();
  expect(find.byKey(_pendingDialog), findsOneWidget);
  await tester.tap(find.text('OK'));
  await tester.pumpAndSettle();
}

Future<void> _enterPhone(WidgetTester tester, String phone) async {
  final field = find.byKey(const Key('tender-phone-field'));
  await tester.ensureVisible(field);
  await tester.enterText(field, phone);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
      'a card the till\'s own press times out on is asked after under the same '
      'key, waited for and recorded once — never queued offline', (tester) async {
    // The press outlasts the till's receive timeout while the cardholder is at
    // the PIN; asked again, payment-svc hands back the attempt still REQUESTED.
    final (till, container) = await _pump(tester,
        saleStates: [_timeout, 'REQUESTED'],
        readStates: ['REQUESTED', 'APPROVED'],
        wait: _patient);
    await _addCard(tester);

    await _pressComplete(tester);

    // The machine has the amount and has not said: the till says what it is
    // waiting on, and the button stays busy so nothing can press again.
    expect(find.byKey(_waiting), findsOneWidget);
    expect(find.text('Waiting for the card machine…'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Processing…'), findsOneWidget);
    expect(find.textContaining('could not be reached'), findsNothing);
    expect(find.byType(SnackBar), findsNothing);

    await _letTimePass(tester);
    await tester.pumpAndSettle();

    expect(find.text('Sale complete'), findsOneWidget);
    expect(find.textContaining('Held offline'), findsNothing);
    expect(find.textContaining('could not be reached'), findsNothing);
    expect(find.byKey(_waiting), findsNothing, reason: 'the wait is over');

    // One payment: the press and the ask after it carry the same key, so the
    // machine was asked once. Everything after was a read of that attempt,
    // which starts nothing and so carries no key.
    final presses = till.posts('/payments/terminal');
    expect(presses, hasLength(2));
    expect(isV7(_key(presses[0])), isTrue);
    expect(_key(presses[1]), _key(presses[0]));
    expect(till.attemptReads, hasLength(2));
    for (final read in till.attemptReads) {
      expect(read.headers.containsKey('Idempotency-Key'), isFalse);
    }

    // The approval is recorded once, as a card tender against the order, and
    // nothing waits in the offline queue to be recorded again.
    final recorded = till.posts('/payments').single;
    expect(recorded.data['method'], 'CARD');
    expect(recorded.data['orderId'], 'order-1');
    expect(container.read(offlineQueueProvider), isEmpty);
  });

  testWidgets(
      'when the wait runs out the sale is held, not failed, and pressing again '
      'asks after the same payment under the same keys', (tester) async {
    // The first press times out, the ask after it finds the attempt REQUESTED
    // and the machine says nothing while the till waits; by the next press of
    // Complete Sale it has approved.
    final (till, container) = await _pump(tester,
        saleStates: [_timeout, 'REQUESTED', 'APPROVED'],
        readStates: ['REQUESTED'],
        wait: _brief);
    await _addCard(tester);

    await _pressComplete(tester);
    await _letTimePass(tester);
    await tester.pumpAndSettle();

    // Said in words, as a notice and not an error: nothing was refused.
    expect(find.byKey(_pendingDialog), findsOneWidget);
    expect(find.text('Still waiting for the card machine'), findsOneWidget);
    expect(find.textContaining('Do not take the card again'), findsOneWidget);
    expect(find.textContaining('could not be reached'), findsNothing);
    expect(find.byType(SnackBar), findsNothing);
    expect(find.text('Sale complete'), findsNothing);
    // Nothing is recorded or queued, and the sale is still on the till.
    expect(till.posts('/payments'), isEmpty);
    expect(container.read(offlineQueueProvider), isEmpty);
    expect(container.read(posCartProvider), isNotEmpty);
    // It asked for as long as it was set to: the ask after the timeout and a
    // read, a tenth of a second apart, and then its time was up.
    expect(till.posts('/payments/terminal').length - 1 + till.attemptReads.length,
        2);

    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(FilledButton, 'Complete Sale'), findsOneWidget,
        reason: 'the cashier can press again');

    await _pressComplete(tester);
    await _letTimePass(tester);
    await tester.pumpAndSettle();

    // The same payment: the same keys, so the server hands back the attempt it
    // already has and the amount is never sent to the machine a second time.
    final orders = till.posts('/orders');
    expect(orders, hasLength(2));
    expect(_key(orders[1]), _key(orders[0]));
    final presses = till.posts('/payments/terminal');
    expect(presses, hasLength(3));
    expect(isV7(_key(presses[0])), isTrue);
    for (final p in presses) {
      expect(_key(p), _key(presses[0]),
          reason: 'a new key would be a second payment on the card');
    }

    expect(find.text('Sale complete'), findsOneWidget);
    expect(till.posts('/payments'), hasLength(1),
        reason: 'the approval is recorded once');
  });

  testWidgets(
      'a press the till never hears back from is held as "no answer yet", '
      'never queued as paid, and pressing again asks under the same key',
      (tester) async {
    final (till, container) = await _pump(tester,
        saleStates: [_timeout, _timeout, _timeout, 'APPROVED'],
        readStates: ['APPROVED'],
        wait: _brief);
    await _addCard(tester);

    await _pressComplete(tester);
    await _letTimePass(tester);
    await tester.pumpAndSettle();

    expect(find.byKey(_pendingDialog), findsOneWidget);
    expect(find.text('No answer from the card machine yet'), findsOneWidget);
    expect(find.textContaining('Do not take the card again'), findsOneWidget);
    expect(find.text('Sale complete'), findsNothing);
    expect(find.textContaining('Held offline'), findsNothing);
    // Not paid, not queued, still on the till.
    expect(container.read(offlineQueueProvider), isEmpty,
        reason: 'a card with no answer is never queued as paid');
    expect(till.posts('/payments'), isEmpty);
    expect(container.read(posCartProvider), isNotEmpty);
    // The press and the asks the wait's time allows, all under one key.
    final first = till.posts('/payments/terminal');
    expect(first, hasLength(3));
    expect({for (final p in first) _key(p)}, hasLength(1));

    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    await _pressComplete(tester);
    await _letTimePass(tester);
    await tester.pumpAndSettle();

    final presses = till.posts('/payments/terminal');
    expect(presses, hasLength(first.length + 1));
    expect(_key(presses.last), _key(presses.first));
    expect(find.text('Sale complete'), findsOneWidget);
    expect(till.posts('/payments'), hasLength(1));
    expect(container.read(offlineQueueProvider), isEmpty);
  });

  testWidgets(
      'a network lost before the card machine is asked never queues the card '
      'as paid: nothing is taken, the sale is kept, and the next press uses the '
      'same keys', (tester) async {
    final (till, container) = await _pump(tester,
        saleStates: ['APPROVED'],
        readStates: ['APPROVED'],
        wait: _brief,
        ordersOffline: 1);
    await _addCard(tester);

    await _pressComplete(tester);
    await _letTimePass(tester);
    await tester.pumpAndSettle();

    expect(find.byKey(_offlineDialog), findsOneWidget);
    expect(find.text('Card not taken'), findsOneWidget);
    expect(find.textContaining('nothing was taken on the card'), findsOneWidget);
    expect(find.textContaining('Held offline'), findsNothing);
    expect(find.text('Sale complete'), findsNothing);
    expect(container.read(offlineQueueProvider), isEmpty,
        reason: 'a card the machine never answered is never queued as paid');
    expect(till.posts('/payments/terminal'), isEmpty);
    expect(till.posts('/payments'), isEmpty);
    expect(container.read(posCartProvider), isNotEmpty);

    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    await _pressComplete(tester);
    await _letTimePass(tester);
    await tester.pumpAndSettle();

    // The order post may have landed before the network went: the same key
    // finds it rather than placing a second order.
    final orders = till.posts('/orders');
    expect(orders, hasLength(2));
    expect(_key(orders[1]), _key(orders[0]));
    expect(find.text('Sale complete'), findsOneWidget);
    expect(till.posts('/payments'), hasLength(1));
    expect(container.read(offlineQueueProvider), isEmpty);
  });

  testWidgets(
      'a network lost after a card was left at the machine says so: do not take '
      'the card again', (tester) async {
    final (till, container) = await _pump(tester,
        saleStates: [_timeout, _timeout, _timeout],
        readStates: ['REQUESTED'],
        wait: _brief);
    await _addCard(tester);

    await _pressComplete(tester);
    await _letTimePass(tester);
    await tester.pumpAndSettle();
    expect(find.byKey(_pendingDialog), findsOneWidget);
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();

    // The network goes before the same payment is asked after again.
    till.ordersOffline = 1;
    await _pressComplete(tester);
    await _letTimePass(tester);
    await tester.pumpAndSettle();

    expect(find.byKey(_offlineDialog), findsOneWidget);
    expect(find.text('No answer from the card machine yet'), findsOneWidget);
    expect(find.textContaining('Do not take the card again'), findsOneWidget);
    expect(find.textContaining('nothing was taken'), findsNothing);
    expect(container.read(offlineQueueProvider), isEmpty);
    expect(till.posts('/payments'), isEmpty);
  });

  testWidgets(
      'a decline read while the till waits is a decline in the machine\'s '
      'words, and the next press starts afresh', (tester) async {
    final (till, container) = await _pump(tester,
        saleStates: [_timeout, 'REQUESTED', 'DECLINED'],
        readStates: ['REQUESTED', 'DECLINED'],
        // What payment-svc then has against the order: the one attempt, declined.
        byOrderStates: ['DECLINED'],
        wait: _patient);
    await _addCard(tester);

    await _pressComplete(tester);
    await _letTimePass(tester);
    await tester.pumpAndSettle();

    expect(find.text('DECLINED — insufficient funds — try another tender.'),
        findsOneWidget);
    expect(find.byKey(_pendingDialog), findsNothing,
        reason: 'the machine said no, so it is not "still waiting"');
    expect(till.posts('/payments'), isEmpty);
    expect(container.read(posCartProvider), isNotEmpty);
    expect(container.read(offlineQueueProvider), isEmpty);

    // A settled answer under the same key would only be handed back again, so a
    // press after a decline is a new attempt with keys of its own — once the
    // server has said the declined card took nothing.
    expect(till.orderAttemptReads, hasLength(1));
    expect(container.read(heldCardPaymentProvider), isNull);
    await _pressComplete(tester);
    await _letTimePass(tester);
    await tester.pumpAndSettle();

    final presses = till.posts('/payments/terminal');
    expect(presses, hasLength(3));
    expect(_key(presses[1]), _key(presses[0]));
    expect(_key(presses[2]), isNot(_key(presses[0])));
    final orders = till.posts('/orders');
    expect(_key(orders[1]), isNot(_key(orders[0])));
  });

  // A sale that changed while its card was still at the machine. A key stands
  // for one request, so the changed sale cannot carry the held keys — and it
  // used to get keys of its own at once: a second order, and a second amount
  // sent to the machine while the first was still waiting there. If the first
  // then approved, the customer paid twice. Now nothing new starts until the
  // held payment is settled.

  testWidgets(
      'a changed sale whose card is still at the machine starts nothing new: '
      'the till reads the held payment, and waiting sends nothing', (tester) async {
    final (till, container) = await _pump(tester,
        saleStates: [_timeout, 'REQUESTED'],
        readStates: ['REQUESTED'],
        byOrderStates: ['REQUESTED'],
        wait: _brief);
    await _addCard(tester);
    await _stopAtTheMachine(tester);

    // The customer's number changes the order the sale sends.
    await _enterPhone(tester, '07700900111');
    final ordersBefore = till.posts('/orders').length;
    final pressesBefore = till.posts('/payments/terminal').length;

    await _pressComplete(tester);
    await tester.pumpAndSettle();

    // Read, not started: the attempts against the held order are asked after,
    // and no order is placed and no amount is sent.
    expect(till.orderAttemptReads, hasLength(1));
    expect(find.byKey(_atMachineDialog), findsOneWidget);
    expect(find.text('The earlier card payment is still on the machine'),
        findsOneWidget);
    expect(find.textContaining('£12.00'), findsWidgets,
        reason: 'the cashier is told which payment');
    expect(till.posts('/orders'), hasLength(ordersBefore));
    expect(till.posts('/payments/terminal'), hasLength(pressesBefore));
    expect(till.posts('/payments'), isEmpty);
    expect(till.cancels, isEmpty);

    await tester.tap(find.text('Wait'));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(FilledButton, 'Complete Sale'), findsOneWidget);
    expect(container.read(heldCardPaymentProvider), isNotNull,
        reason: 'waiting keeps the payment held');

    // Pressed again, it asks again — never a second payment.
    await _pressComplete(tester);
    await tester.pumpAndSettle();
    expect(find.byKey(_atMachineDialog), findsOneWidget);
    expect(till.posts('/orders'), hasLength(ordersBefore));
    expect(till.posts('/payments/terminal'), hasLength(pressesBefore));
    await tester.tap(find.text('Wait'));
    await tester.pumpAndSettle();
  });

  testWidgets(
      'cancelling the held payment on the machine frees the till: the changed '
      'sale then goes on under keys of its own', (tester) async {
    final (till, container) = await _pump(tester,
        saleStates: [_timeout, 'REQUESTED', 'APPROVED'],
        readStates: ['REQUESTED'],
        byOrderStates: ['REQUESTED'],
        cancelState: 'CANCELLED',
        wait: _brief);
    await _addCard(tester);
    await _stopAtTheMachine(tester);
    await _enterPhone(tester, '07700900111');

    await _pressComplete(tester);
    await tester.pumpAndSettle();
    expect(find.byKey(_atMachineDialog), findsOneWidget);

    await tester.tap(find.byKey(const Key('tender-held-cancel')));
    await _letTimePass(tester);
    await tester.pumpAndSettle();

    // The machine was told to stop, said it had, and nothing was taken: only
    // then is the changed sale a new request with keys of its own.
    expect(till.cancels, hasLength(1));
    expect(till.cancels.single.path, endsWith('/payments/terminal/att-1/cancel'));
    final orders = till.posts('/orders');
    expect(orders, hasLength(2));
    expect(_key(orders[1]), isNot(_key(orders[0])));
    expect(orders[1].data['contactPhone'], '07700900111');
    final presses = till.posts('/payments/terminal');
    expect(presses, hasLength(3));
    expect(_key(presses[2]), isNot(_key(presses[0])));
    // Cancelled before the new order was placed.
    expect(till.requests.indexOf(till.cancels.single),
        lessThan(till.requests.indexOf(orders[1])));
    // And the order let go is given up, not left awaiting payment: whatever
    // its card takes later, payment-svc puts back by itself.
    final givenUp = till.orderCancels.single;
    expect(givenUp.path, endsWith('/orders/order-1/cancel'));
    expect((givenUp.data as Map)['reason'], isNotEmpty);
    expect(till.requests.indexOf(givenUp),
        lessThan(till.requests.indexOf(orders[1])));

    expect(find.text('Sale complete'), findsOneWidget);
    expect(till.posts('/payments'), hasLength(1));
    expect(container.read(heldCardPaymentProvider), isNull);
  });

  testWidgets(
      'a cancel that loses the race to an approval is the card taken: nothing '
      'new is started', (tester) async {
    final (till, container) = await _pump(tester,
        saleStates: [_timeout, 'REQUESTED'],
        readStates: ['REQUESTED'],
        byOrderStates: ['REQUESTED'],
        cancelState: 'APPROVED',
        wait: _brief);
    await _addCard(tester);
    await _stopAtTheMachine(tester);
    await _enterPhone(tester, '07700900111');

    await _pressComplete(tester);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('tender-held-cancel')));
    await tester.pumpAndSettle();

    expect(find.byKey(_takenDialog), findsOneWidget);
    expect(till.posts('/orders'), hasLength(1));
    expect(till.posts('/payments/terminal'), hasLength(2));
    expect(container.read(heldCardPaymentProvider), isNotNull);
  });

  testWidgets(
      'a card the machine approved after the till stopped waiting is never '
      'taken again: the changed sale is refused and put back as it was',
      (tester) async {
    final (till, container) = await _pump(tester,
        saleStates: [_timeout, 'REQUESTED', 'APPROVED'],
        readStates: ['REQUESTED'],
        byOrderStates: ['APPROVED'],
        wait: _brief);
    await _addCard(tester);
    await _stopAtTheMachine(tester);
    await _enterPhone(tester, '07700900111');

    await _pressComplete(tester);
    await tester.pumpAndSettle();

    expect(find.byKey(_takenDialog), findsOneWidget);
    expect(find.text('The card was taken for this sale as it was'),
        findsOneWidget);
    expect(find.textContaining('£12.00'), findsWidgets);
    expect(till.posts('/orders'), hasLength(1));
    expect(till.posts('/payments/terminal'), hasLength(2));
    expect(till.posts('/payments'), isEmpty);

    await tester.tap(find.byKey(const Key('tender-held-put-back')));
    await tester.pumpAndSettle();
    // The sale as it was: the number it was sent with, on the field and in
    // the sale.
    expect(container.read(posWalkInPhoneProvider), '07700900000');
    expect(
        tester
            .widget<TextField>(find.byKey(const Key('tender-phone-field')))
            .controller!
            .text,
        '07700900000');

    await _pressComplete(tester);
    await _letTimePass(tester);
    await tester.pumpAndSettle();

    // The same keys, so the server hands back the order it already has; the
    // approval read from the order is final, so it is recorded as exactly that
    // payment and the machine is never asked again.
    final orders = till.posts('/orders');
    expect(orders, hasLength(2));
    expect(_key(orders[1]), _key(orders[0]));
    expect(till.posts('/payments/terminal'), hasLength(2),
        reason: 'the card machine is not asked a third time');
    expect(find.text('Sale complete'), findsOneWidget);
    expect(till.posts('/payments').single.data['terminalPaymentId'], 'att-1');
    expect(container.read(heldCardPaymentProvider), isNull);
  });

  testWidgets(
      'taking the card off and cash instead does not leave the card at the '
      'machine to be paid as well', (tester) async {
    final (till, _) = await _pump(tester,
        saleStates: [_timeout, 'REQUESTED'],
        readStates: ['REQUESTED'],
        byOrderStates: ['REQUESTED'],
        wait: _brief);
    await _addCard(tester);
    await _stopAtTheMachine(tester);

    await tester.tap(find.byTooltip('Remove tender'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(OutlinedButton, 'Cash'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Add'));
    await tester.pumpAndSettle();

    await _pressComplete(tester);
    await tester.pumpAndSettle();

    expect(find.byKey(_atMachineDialog), findsOneWidget);
    expect(till.posts('/orders'), hasLength(1));
    expect(till.posts('/payments'), isEmpty,
        reason: 'the cash is not recorded while the card may yet be taken');
    await tester.tap(find.text('Wait'));
    await tester.pumpAndSettle();
  });

  testWidgets(
      'the held payment outlives the screen: leaving it and coming back to the '
      'same sale carries on the same payment', (tester) async {
    final shown = ValueNotifier(true);
    final (till, container) = await _pump(tester,
        saleStates: [_timeout, 'REQUESTED', 'APPROVED'],
        readStates: ['REQUESTED'],
        wait: _brief,
        shown: shown);
    await _addCard(tester);
    await _stopAtTheMachine(tester);

    // Back to Sale, and back again: the screen and its state are gone.
    shown.value = false;
    await tester.pumpAndSettle();
    expect(find.byType(TenderScreen), findsNothing);
    shown.value = true;
    await tester.pumpAndSettle();

    // The card is staged again, so the sale is the same and the press carries
    // on the same payment.
    expect(find.byTooltip('Remove tender'), findsOneWidget);
    expect(find.text('No payments added yet'), findsNothing);
    expect(
        tester
            .widget<FilledButton>(
                find.widgetWithText(FilledButton, 'Complete Sale'))
            .onPressed,
        isNotNull);
    await _pressComplete(tester);
    await _letTimePass(tester);
    await tester.pumpAndSettle();

    final orders = till.posts('/orders');
    expect(orders, hasLength(2));
    expect(_key(orders[1]), _key(orders[0]));
    final presses = till.posts('/payments/terminal');
    expect(presses, hasLength(3));
    expect(_key(presses[2]), _key(presses[0]));
    expect(till.orderAttemptReads, isEmpty,
        reason: 'the same sale needs no settling: its keys find the payment');
    expect(find.text('Sale complete'), findsOneWidget);
    expect(till.posts('/payments'), hasLength(1));
    expect(container.read(heldCardPaymentProvider), isNull);
  });

  testWidgets(
      'leaving, changing the sale and tendering it anew still settles the held '
      'payment first', (tester) async {
    final shown = ValueNotifier(true);
    final (till, container) = await _pump(tester,
        saleStates: [_timeout, 'REQUESTED'],
        readStates: ['REQUESTED'],
        byOrderStates: ['REQUESTED'],
        wait: _brief,
        shown: shown);
    await _addCard(tester);
    await _stopAtTheMachine(tester);

    shown.value = false;
    await tester.pumpAndSettle();
    // On the Sale tab the number is changed.
    container.read(posWalkInPhoneProvider.notifier).state = '07700900111';
    shown.value = true;
    await tester.pumpAndSettle();

    // A different sale: nothing is staged for it.
    expect(find.text('No payments added yet'), findsOneWidget);
    await _addCard(tester);
    await _pressComplete(tester);
    await tester.pumpAndSettle();

    expect(find.byKey(_atMachineDialog), findsOneWidget);
    expect(till.posts('/orders'), hasLength(1));
    expect(till.posts('/payments/terminal'), hasLength(2));
    await tester.tap(find.text('Wait'));
    await tester.pumpAndSettle();
  });

  testWidgets(
      'a changed sale whose held payment cannot be read starts nothing, and can '
      'be put back to carry on with it', (tester) async {
    final (till, container) = await _pump(tester,
        saleStates: [_timeout, 'REQUESTED', 'APPROVED'],
        readStates: ['REQUESTED'],
        wait: _brief);
    await _addCard(tester);
    await _stopAtTheMachine(tester);
    await _enterPhone(tester, '07700900111');
    till.byOrderOffline = true;

    await _pressComplete(tester);
    await tester.pumpAndSettle();

    expect(find.byKey(_unknownDialog), findsOneWidget);
    expect(find.textContaining('Do not take the card again'), findsOneWidget);
    expect(till.posts('/orders'), hasLength(1));
    expect(till.posts('/payments/terminal'), hasLength(2));
    expect(container.read(offlineQueueProvider), isEmpty);

    await tester.tap(find.byKey(const Key('tender-held-put-back')));
    await tester.pumpAndSettle();
    expect(container.read(posWalkInPhoneProvider), '07700900000');
    await _pressComplete(tester);
    await _letTimePass(tester);
    await tester.pumpAndSettle();
    final presses = till.posts('/payments/terminal');
    expect(_key(presses.last), _key(presses.first));
    expect(find.text('Sale complete'), findsOneWidget);
  });

  testWidgets(
      'a manager may cancel a held sale whose payment cannot be read, with a '
      'reason: the till says its card is put back, never that it stays taken',
      (tester) async {
    final (till, container) = await _pump(tester,
        saleStates: [_timeout, 'REQUESTED'],
        readStates: ['REQUESTED'],
        wait: _brief,
        role: 'MANAGER');
    await _addCard(tester);
    await _stopAtTheMachine(tester);
    await _enterPhone(tester, '07700900111');
    till.byOrderOffline = true;

    await _pressComplete(tester);
    await tester.pumpAndSettle();
    expect(find.byKey(_unknownDialog), findsOneWidget);
    await tester.tap(find.byKey(const Key('tender-held-close')));
    await tester.pumpAndSettle();
    expect(find.textContaining('goes back on the card'), findsOneWidget);
    expect(find.textContaining('does not put money back'), findsNothing);
    await tester.enterText(find.byKey(const Key('tender-close-reason-field')),
        'Payments server down; customer left');
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('tender-close-confirm')));
    await tester.pumpAndSettle();

    final cancel = till.requests.singleWhere((r) =>
        r.method == 'POST' && r.path.endsWith('/orders/order-1/cancel'));
    expect(cancel.data, {'reason': 'Payments server down; customer left'});
    expect(container.read(heldCardPaymentProvider), isNull);
  });

  group('a held payment that timed out on the machine', () {
    // payment-svc can never say what a timed-out payment took. A cashier's
    // "I have checked" used to let it go, and the next sale went ahead while
    // the first card may have been charged. Now only a manager's record of what
    // the machine shows, with a reason, settles it.

    Future<(_Till, ProviderContainer)> timedOutThenChanged(WidgetTester tester,
        {String? role}) async {
      final (till, container) = await _pump(tester,
          saleStates: [_timeout, 'REQUESTED', 'APPROVED'],
          readStates: ['REQUESTED'],
          byOrderStates: ['TIMED_OUT'],
          wait: _brief,
          role: role);
      await _addCard(tester);
      await _stopAtTheMachine(tester);
      await _enterPhone(tester, '07700900111');

      await _pressComplete(tester);
      await tester.pumpAndSettle();
      expect(find.byKey(_uncertainHeldDialog), findsOneWidget);
      expect(find.textContaining('may have been charged'), findsOneWidget);
      expect(find.text('I have checked'), findsNothing);
      expect(till.posts('/orders'), hasLength(1));
      expect(till.posts('/payments/terminal'), hasLength(2));
      return (till, container);
    }

    testWidgets(
        'a cashier cannot let it go: the hold stands, nothing new is sent, and '
        'the next press asks again', (tester) async {
      final (till, container) = await timedOutThenChanged(tester);
      expect(find.byKey(const Key('tender-held-settle')), findsNothing);
      expect(find.textContaining('Ask a manager'), findsOneWidget);
      await tester.tap(find.text('Not now'));
      await tester.pumpAndSettle();
      expect(container.read(heldCardPaymentProvider), isNotNull,
          reason: 'only a manager\'s record settles it');

      await _pressComplete(tester);
      await tester.pumpAndSettle();
      expect(find.byKey(_uncertainHeldDialog), findsOneWidget);
      expect(till.posts('/orders'), hasLength(1));
      expect(till.posts('/payments/terminal'), hasLength(2));
      expect(till.settles, isEmpty);
      await tester.tap(find.text('Not now'));
      await tester.pumpAndSettle();
    });

    testWidgets(
        'a manager records it as not taken, with a reason: the till is free '
        'and the changed sale goes ahead under keys of its own', (tester) async {
      final (till, container) =
          await timedOutThenChanged(tester, role: 'MANAGER');
      await tester.tap(find.byKey(const Key('tender-held-settle')));
      await tester.pumpAndSettle();
      final confirm = find.byKey(const Key('tender-settle-confirm'));
      expect(tester.widget<FilledButton>(confirm).onPressed, isNull,
          reason: 'not without what it shows and why');
      await tester.tap(find.byKey(const Key('tender-settle-not-taken')));
      await tester.enterText(find.byKey(const Key('tender-settle-reason-field')),
          'Machine screen shows the payment cancelled');
      await tester.pumpAndSettle();
      await tester.tap(confirm);
      await _letTimePass(tester);
      await tester.pumpAndSettle();

      final settle = till.settles.single;
      expect(settle.path, endsWith('/payments/terminal/att-1/settle'));
      expect(settle.data, {
        'outcome': 'NOT_TAKEN',
        'reason': 'Machine screen shows the payment cancelled',
      });
      expect(isV7(_key(settle)), isTrue);
      final orders = till.posts('/orders');
      expect(orders, hasLength(2));
      expect(_key(orders[1]), isNot(_key(orders[0])));
      // The machine may yet say it took the card: the order let go is given
      // up first, so payment-svc puts that back by itself.
      expect(till.orderCancels.single.path, endsWith('/orders/order-1/cancel'));
      expect(till.requests.indexOf(till.orderCancels.single),
          lessThan(till.requests.indexOf(orders[1])));
      expect(find.text('Sale complete'), findsOneWidget);
      expect(container.read(heldCardPaymentProvider), isNull);
    });

    testWidgets(
        'a manager records it as approved: it is the card taken, and putting '
        'the sale back finishes it naming that payment, never asking again',
        (tester) async {
      final (till, container) =
          await timedOutThenChanged(tester, role: 'MANAGER');
      await tester.tap(find.byKey(const Key('tender-held-settle')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-approved')));
      await tester.enterText(find.byKey(const Key('tender-settle-reason-field')),
          'Last receipt on the machine shows it approved');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-confirm')));
      await tester.pumpAndSettle();

      expect(till.settles.single.data['outcome'], 'APPROVED');
      expect(find.byKey(_takenDialog), findsOneWidget);
      expect(container.read(heldCardPaymentProvider)?.approved, {0});
      await tester.tap(find.byKey(const Key('tender-held-put-back')));
      await tester.pumpAndSettle();
      await _pressComplete(tester);
      await _letTimePass(tester);
      await tester.pumpAndSettle();

      expect(find.text('Sale complete'), findsOneWidget);
      expect(till.posts('/payments/terminal'), hasLength(2));
      expect(till.posts('/payments').single.data['terminalPaymentId'], 'att-1');
      expect(container.read(heldCardPaymentProvider), isNull);
    });
  });

  group('a card the machine timed out on during the press', () {
    Future<(_Till, ProviderContainer)> timedOut(WidgetTester tester,
        {String? role}) async {
      final (till, container) = await _pump(tester,
          saleStates: ['TIMED_OUT'],
          readStates: ['TIMED_OUT'],
          byOrderStates: ['TIMED_OUT'],
          wait: _brief,
          role: role);
      await _addCard(tester);
      await _pressComplete(tester);
      await _letTimePass(tester);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-terminal-uncertain')), findsOneWidget);
      expect(find.text('I have checked'), findsNothing);
      return (till, container);
    }

    testWidgets(
        'nothing the cashier presses lets it go: the next press asks after the '
        'same payment, never a new one', (tester) async {
      final (till, container) = await timedOut(tester);
      expect(find.byKey(const Key('tender-uncertain-settle')), findsNothing);
      await tester.tap(find.text('Not now'));
      await tester.pumpAndSettle();
      final held = container.read(heldCardPaymentProvider);
      expect(held, isNotNull, reason: 'the card may have been charged');
      expect(held!.fixed, 1);
      expect(held.termTries, isEmpty, reason: 'no key of its own yet');

      await _pressComplete(tester);
      await _letTimePass(tester);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tender-terminal-uncertain')), findsOneWidget);
      final presses = till.posts('/payments/terminal');
      expect(presses, hasLength(2));
      expect(_key(presses[1]), _key(presses[0]),
          reason: 'the same payment asked after, never a second amount');
      expect(till.posts('/payments'), isEmpty);
      await tester.tap(find.text('Not now'));
      await tester.pumpAndSettle();
    });

    testWidgets(
        'a manager records it as not taken: the card may be taken again, under '
        'a key of its own — on the same order, which the till keeps: the '
        'machine may yet say it took the card for it', (tester) async {
      final (till, container) = await timedOut(tester, role: 'MANAGER');
      await tester.tap(find.byKey(const Key('tender-uncertain-settle')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-not-taken')));
      await tester.enterText(find.byKey(const Key('tender-settle-reason-field')),
          'Machine shows no payment');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-confirm')));
      await tester.pumpAndSettle();
      expect(till.settles.single.data['outcome'], 'NOT_TAKEN');
      final held = container.read(heldCardPaymentProvider);
      expect(held?.orderId, 'order-1',
          reason: 'let go, the same basket would be rung up as a second order '
              'and a late approval would find this one unpaid');
      expect(held?.fixed, 0);
      expect(held?.termTries, {0: 1});
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      await _pressComplete(tester);
      await _letTimePass(tester);
      await tester.pumpAndSettle();
      expect(till.posts('/orders'), hasLength(2), reason: 'asked again…');
      expect(_key(till.posts('/orders')[1]), _key(till.posts('/orders')[0]),
          reason: '…under the same key: the same order');
      final presses = till.posts('/payments/terminal');
      expect(presses, hasLength(2));
      expect(_key(presses[1]), isNot(_key(presses[0])),
          reason: 'the card again is a payment of its own');
      expect(presses[1].data['orderId'], 'order-1');
    });

    testWidgets(
        'a manager records it as approved: the next press records it as that '
        'payment and never asks the machine again', (tester) async {
      final (till, container) = await timedOut(tester, role: 'MANAGER');
      await tester.tap(find.byKey(const Key('tender-uncertain-settle')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-approved')));
      await tester.enterText(find.byKey(const Key('tender-settle-reason-field')),
          'Approved on the machine screen');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('tender-settle-confirm')));
      await tester.pumpAndSettle();
      expect(container.read(heldCardPaymentProvider)?.approved, {0});
      await tester.pump(const Duration(seconds: 5));
      await tester.pumpAndSettle();

      await _pressComplete(tester);
      await _letTimePass(tester);
      await tester.pumpAndSettle();
      expect(find.text('Sale complete'), findsOneWidget);
      expect(till.posts('/payments/terminal'), hasLength(1));
      expect(till.posts('/payments').single.data['terminalPaymentId'], 'att-1');
    });
  });

  testWidgets(
      'on a phone the notice sits above the button in the bottom bar and '
      'nothing overflows', (tester) async {
    final (_, _) = await _pump(tester,
        saleStates: [_timeout, 'REQUESTED'],
        readStates: ['REQUESTED'],
        wait: _brief,
        size: const Size(360, 740));
    await _addCard(tester);

    await _pressComplete(tester);

    expect(find.byKey(_waiting), findsOneWidget);
    final notice = tester.getRect(find.byKey(_waiting));
    final button = tester.getRect(find.widgetWithText(FilledButton, 'Processing…'));
    expect(notice.bottom, lessThanOrEqualTo(button.top),
        reason: 'the notice is above the button the cashier pressed');
    expect(notice.right, lessThanOrEqualTo(360));
    expect(tester.takeException(), isNull);

    await _letTimePass(tester);
    await tester.pumpAndSettle();
    expect(find.byKey(_pendingDialog), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  test('a sale that cannot be written as JSON reuses no keys, rather than stop '
      'the till', () {
    // Guard only: the amount dialog refuses a figure that is not a number
    // (tender_amount_dialog_test.dart), so no sale should carry one. If one
    // ever did, the fingerprint reads it as "reuse nothing" rather than throw
    // before the sale's own handling and leave the button spinning.
    expect(saleFingerprint([1, 'a', {'k': 2.5}]), isNotNull);
    expect(saleFingerprint([double.nan]), isNull);
    expect(saleFingerprint({'amount': double.infinity}), isNull);
  });
}
