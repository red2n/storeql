import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/offline/offline_queue.dart';
import 'package:storeql_app/core/storage/app_storage.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/pos/cash_providers.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';
import 'package:storeql_app/features/pos/tender_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The drawer a sale is rung on (per-till money, 9 Oct 2026). On a drawer opened
// on the session basis the X report and the close count only the money that
// names the drawer, so what the Tender screen puts on the tender it sends is
// the whole link between a sale and the cash it should have counted. These
// tests drive the real screen, the real provider and a stand-in for
// payment-svc, and read what was *sent*:
//
//   * the tender names the open drawer, and names none when none is open;
//   * a read of the open till that failed, or is still in flight, is not "no
//     till": the sale gives it a moment, and never goes without a drawer for
//     the rest of the shift because one read went wrong;
//   * the drawer follows the person signed in, not only the store: the next
//     cashier does not ring on the last one's drawer;
//   * a drawer the server will not count is read again, so the next sale names
//     the one that is open now.
// ---------------------------------------------------------------------------

const _drawerA = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c00a1';
const _drawerB = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c00b2';
const _down = 'down';

/// A till signed in as one person, who can be replaced by another on the same
/// running app (sign out, sign in) without the app being reloaded.
class _Auth extends AuthNotifier {
  _Auth(this.userId);

  String userId;

  AuthAuthenticated _as(String id) => AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: id,
        tenantId: 't',
        roles: const ['CASHIER'],
      );

  @override
  Future<AuthState> build() async => _as(userId);

  void signOut() => state = const AsyncData(AuthUnauthenticated());

  void signInAs(String id) {
    userId = id;
    state = AsyncData(_as(id));
  }
}

class _Memory implements AppStorage {
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

class _NoopPosSession extends PosSessionNotifier {
  _NoopPosSession(super.ref);

  @override
  Future<void> restore() async {}
}

class _Cart extends PosCartNotifier {
  _Cart() {
    loadLines(const [
      PosLine(
        variantId: 'v-1',
        sku: 'SKU-1',
        name: 'Product 1',
        qty: 2,
        unitPrice: 6.0,
        currency: 'GBP',
      ),
    ]);
  }
}

/// order-svc and payment-svc as a cash sale meets them.
class _Server implements HttpClientAdapter {
  /// Who is asking, for the one question that depends on it: which till is
  /// open for this caller.
  String caller = 'u-a';

  /// The open till per caller, in the order the questions come (the last answer
  /// repeats): a session id, `null` for none (404), or [_down] for a 503.
  final Map<String, List<String?>> openTill = {
    'u-a': [_drawerA],
  };
  final Map<String, int> _asked = {};

  /// While set, the question "which till is open" is not answered.
  Completer<void>? holdOpenTill;

  /// Drawers payment-svc will not count money in (409 TILL_SESSION_NOT_OPEN).
  final Set<String> closedDrawers = {};

  final List<RequestOptions> requests = [];

  List<RequestOptions> get asks => requests
      .where((r) => r.path.endsWith('/till-sessions/current'))
      .toList();

  List<RequestOptions> get payments => requests
      .where((r) => r.method == 'POST' && r.path.endsWith('/payments'))
      .toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/till-sessions/current')) {
      final hold = holdOpenTill;
      if (hold != null) await hold.future;
      final answers = openTill[caller] ?? const <String?>[null];
      final n = _asked[caller] = (_asked[caller] ?? 0) + 1;
      final answer = answers[n > answers.length ? answers.length - 1 : n - 1];
      if (answer == _down) {
        return jsonResponse(
            '{"error":{"code":"SERVICE_UNAVAILABLE","message":"Down."}}', 503);
      }
      if (answer == null) {
        return jsonResponse(
            '{"error":{"code":"TILL_SESSION_NOT_OPEN","message":"None."}}',
            404);
      }
      return jsonResponse(
          jsonEncode({
            'data': {'id': answer, 'status': 'OPEN', 'storeId': 'store-1'}
          }),
          200);
    }
    if (o.method == 'POST' && path.endsWith('/orders')) {
      return jsonResponse('{"data":{"id":"order-1","total":12.0}}', 201);
    }
    if (o.method == 'POST' && path.endsWith('/payments')) {
      final named = (o.data as Map)['tillSessionId'];
      if (named != null && closedDrawers.contains(named)) {
        return jsonResponse(
            '{"error":{"code":"TILL_SESSION_NOT_OPEN","message":"Closed."}}',
            409);
      }
      return jsonResponse(jsonEncode({'data': o.data}), 201);
    }
    if (path.endsWith('/fiscal-receipt')) {
      return jsonResponse('{"data":{"fullNumber":"2026-000042"}}');
    }
    return jsonResponse('{"data":{}}');
  }
}

Future<ProviderContainer> _pump(
  WidgetTester tester,
  _Server server, {
  AuthNotifier Function()? auth,
  bool readTheTill = true,
}) async {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      offlineQueueProvider.overrideWith((ref) =>
          OfflineQueueNotifier(ref, storage: _Memory(), autoSync: false)),
      posSessionProvider.overrideWith((ref) => _NoopPosSession(ref)),
      authNotifierProvider.overrideWith(auth ?? () => _Auth('u-a')),
      posCartProvider.overrideWith((ref) => _Cart()),
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
    ],
    child: const MaterialApp(home: Scaffold(body: TenderScreen())),
  ));
  final container =
      ProviderScope.containerOf(tester.element(find.byType(TenderScreen)));
  await container.read(authNotifierProvider.future);
  // In the app the POS shell watches the open till from the moment the
  // terminal is on, so it has been asked long before a sale is rung.
  if (readTheTill) container.read(saleTillProvider);
  await tester.pumpAndSettle();
  return container;
}

/// Stage cash for the whole balance (2 × £6 = £12).
Future<void> _stageCash(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(OutlinedButton, 'Cash'));
  await tester.pumpAndSettle();
  await tester.tap(find.widgetWithText(FilledButton, 'Add'));
  await tester.pumpAndSettle();
}

/// Press Complete Sale and let the sale run to its end.
Future<void> _complete(WidgetTester tester) async {
  final button = find.widgetWithText(FilledButton, 'Complete Sale');
  await tester.ensureVisible(button);
  await tester.tap(button);
  await tester.pumpAndSettle();
}

Map<String, dynamic> _tenderOf(RequestOptions r) =>
    Map<String, dynamic>.from(r.data as Map);

void main() {
  group('the tender names the drawer', () {
    testWidgets('a cash sale sends the open drawer on its tender',
        (tester) async {
      final server = _Server();
      await _pump(tester, server);
      await _stageCash(tester);

      await _complete(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      final tender = _tenderOf(server.payments.single);
      expect(tender['tillSessionId'], _drawerA,
          reason: 'the drawer counts the cash it rang');
      expect(tender['method'], 'CASH');
      expect(tender['storeId'], 'store-1');
    });

    testWidgets('with no drawer open the tender names none', (tester) async {
      final server = _Server()..openTill['u-a'] = [null];
      await _pump(tester, server);
      await _stageCash(tester);

      await _complete(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(_tenderOf(server.payments.single).containsKey('tillSessionId'),
          isFalse,
          reason: 'no guess: with no drawer the money is "not at a till"');
    });

    testWidgets(
        'a sale that cannot reach the server is queued naming the drawer, '
        'so its replay is counted there', (tester) async {
      final server = _Server();
      final container = await _pump(tester, server);
      await _stageCash(tester);
      // The line drops for the order post: the sale is kept on the till.
      final offline = _Offline(server);
      container.read(apiClientProvider).dio.httpClientAdapter = offline;

      await _complete(tester);

      final queued = container.read(offlineQueueProvider).single;
      expect(queued.tenders.single.body['tillSessionId'], _drawerA);
    });
  });

  group('a read of the open till that did not come back', () {
    testWidgets(
        'a read that failed is not "no till" for the rest of the shift: the '
        'sale reads again and names the drawer', (tester) async {
      // The first question found payment-svc down; it is back when the sale
      // is rung. The drawer is open all the while.
      final server = _Server()..openTill['u-a'] = [_down, _drawerA];
      final container = await _pump(tester, server);
      expect(container.read(saleTillProvider).value, isNull);
      await _stageCash(tester);

      await _complete(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(_tenderOf(server.payments.single)['tillSessionId'], _drawerA,
          reason: 'one failed read must not leave every sale of the shift '
              'in no drawer');
    });

    testWidgets(
        'a read still in flight when the sale is rung is waited for, not '
        'skipped', (tester) async {
      final server = _Server()..holdOpenTill = Completer<void>();
      await _pump(tester, server);
      await _stageCash(tester);

      // Complete Sale is pressed while the drawer is still being looked up.
      final button = find.widgetWithText(FilledButton, 'Complete Sale');
      await tester.ensureVisible(button);
      await tester.tap(button);
      await tester.pump();
      expect(server.payments, isEmpty, reason: 'still waiting on the drawer');
      server.holdOpenTill!.complete();
      await tester.pumpAndSettle();

      expect(find.text('Sale complete'), findsOneWidget);
      expect(_tenderOf(server.payments.single)['tillSessionId'], _drawerA);
    });

    testWidgets(
        'a drawer that cannot be read at all never stops the sale: it is '
        'rung naming none', (tester) async {
      final server = _Server()..openTill['u-a'] = [_down];
      await _pump(tester, server);
      await _stageCash(tester);

      await _complete(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(_tenderOf(server.payments.single).containsKey('tillSessionId'),
          isFalse);
    });
  });

  group('the drawer follows the person signed in', () {
    testWidgets(
        'the next cashier at the same store does not ring on the last one\'s '
        'drawer', (tester) async {
      final server = _Server()
        ..openTill['u-a'] = [_drawerA]
        ..openTill['u-b'] = [null];
      final container = await _pump(tester, server);
      expect(container.read(saleTillProvider).value, _drawerA);

      // A signs out with the drawer still open; B signs in, at the same store,
      // on the same running app, and has opened none.
      final auth = container.read(authNotifierProvider.notifier) as _Auth;
      auth.signOut();
      await tester.pumpAndSettle();
      server.caller = 'u-b';
      auth.signInAs('u-b');
      await tester.pumpAndSettle();
      await _stageCash(tester);

      await _complete(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      expect(_tenderOf(server.payments.single).containsKey('tillSessionId'),
          isFalse,
          reason: 'B opened no drawer: B\'s cash is not A\'s drawer\'s');
    });

    testWidgets('and rings on their own drawer when they have one',
        (tester) async {
      final server = _Server()
        ..openTill['u-a'] = [_drawerA]
        ..openTill['u-b'] = [_drawerB];
      final container = await _pump(tester, server);
      expect(container.read(saleTillProvider).value, _drawerA);

      final auth = container.read(authNotifierProvider.notifier) as _Auth;
      server.caller = 'u-b';
      auth.signInAs('u-b');
      await tester.pumpAndSettle();
      await _stageCash(tester);

      await _complete(tester);

      expect(_tenderOf(server.payments.single)['tillSessionId'], _drawerB);
    });
  });

  group('a drawer the server will not count', () {
    testWidgets(
        'the money is still recorded, and the drawer is read again so the '
        'next sale names the one that is open now', (tester) async {
      // A's drawer was closed from another device; a new one is open there.
      final server = _Server()
        ..openTill['u-a'] = [_drawerA, _drawerB]
        ..closedDrawers.add(_drawerA);
      final container = await _pump(tester, server);
      expect(container.read(saleTillProvider).value, _drawerA);
      await _stageCash(tester);

      await _complete(tester);

      expect(find.text('Sale complete'), findsOneWidget);
      final sent = server.payments;
      expect(sent, hasLength(2));
      expect(_tenderOf(sent[0])['tillSessionId'], _drawerA);
      expect(_tenderOf(sent[1]).containsKey('tillSessionId'), isFalse,
          reason: 'the money taken is recorded, naming none');
      expect(sent[1].headers['Idempotency-Key'],
          sent[0].headers['Idempotency-Key'],
          reason: 'the refusal wrote nothing, so the same key');

      expect(server.asks, hasLength(2),
          reason: 'the refusal sent the till to read the open drawer again');
      expect(container.read(saleTillProvider).value, _drawerB);
    });
  });
}

/// The network is gone for everything but the questions about the drawer.
class _Offline implements HttpClientAdapter {
  _Offline(this.server);

  final _Server server;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) {
    if (o.path.endsWith('/till-sessions/current')) {
      return server.fetch(o, s, c);
    }
    throw DioException(
        requestOptions: o, type: DioExceptionType.connectionError);
  }
}
