import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/pos/cash_providers.dart';
import 'package:storeql_app/features/pos/container_return_dialog.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Container return at the till (09.16): the scheme where the store trades
// prices the empties, the amount is shown before it is paid, the refund is
// posted once, and a store without a scheme pays none.
//
// The deposit is paid out of a drawer, so the refund names the TILL session -
// payment-svc's, the one the Cash screen opened - and not the sign-in session
// iam-svc keeps for the clocked-in cashier: that id is no drawer, so a pay-out
// booked to it is in no drawer's report and the drawer closes short by exactly
// the deposits it paid.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  String scheme = '{"scope":"DE","currency":"EUR","depositEach":0.25,'
      '"materials":["PET","ALUMINIUM","STEEL","GLASS"],"minVolumeMl":100,"maxVolumeMl":3000,'
      '"vatTreatment":"STANDARD","citation":"VerpackG §31","summary":"Pfand"}';
  bool hasScheme = true;
  final posts = <RequestOptions>[];

  /// What "which till is open" answers, in turn (the last repeats): an id,
  /// `null` for none (404) or `down` for a 503.
  List<String?> openTill = [null];
  int _asked = 0;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    var body = '{"data":{}}';
    if (o.path.endsWith('/till-sessions/current')) {
      final answer = openTill[_asked < openTill.length ? _asked : openTill.length - 1];
      _asked++;
      if (answer == 'down') {
        return ResponseBody.fromString(
            '{"error":{"code":"SERVICE_UNAVAILABLE","message":"Down."}}', 503,
            headers: {
              Headers.contentTypeHeader: [Headers.jsonContentType]
            });
      }
      if (answer == null) {
        return ResponseBody.fromString(
            '{"error":{"code":"TILL_SESSION_NOT_OPEN","message":"None."}}', 404,
            headers: {
              Headers.contentTypeHeader: [Headers.jsonContentType]
            });
      }
      return ResponseBody.fromString(
          '{"data":{"id":"$answer","status":"OPEN","storeId":"store-de"}}', 200,
          headers: {
            Headers.contentTypeHeader: [Headers.jsonContentType]
          });
    }
    if (o.path.endsWith('/storefront/config')) {
      body = hasScheme
          ? '{"data":{"showPrices":true,"storeName":"Berlin","depositScheme":$scheme}}'
          : '{"data":{"showPrices":true,"storeName":"London"}}';
    } else if (o.path.endsWith('/orders/container-refunds')) {
      posts.add(o);
      body = '{"data":{"id":"r-1","amount":0.75,"containers":3,"currency":"EUR","lines":[]}}';
    }
    return ResponseBody.fromString(body, 201, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

class _Session extends PosSessionNotifier {
  _Session(super.ref) {
    state = const PosSession(
        id: 'sess-1', storeId: 'store-de', startedAt: '2026-09-16T09:00:00Z', idleTimeoutSeconds: 600);
  }
  @override
  Future<void> restore() async {}
  @override
  Future<void> touch() async {}
}

/// The drawer the terminal has read as open (payment-svc's till session id).
class _Drawer extends SaleTillNotifier {
  _Drawer(this.id);

  final String? id;

  @override
  Future<String?> build() async => id;
}

/// Opens the dialog. With [drawer] the terminal already has that till open;
/// with [openTill] the terminal asks the server for it, as it does at start.
Future<_Server> _open(WidgetTester tester,
    {bool hasScheme = true,
    String? drawer = 'till-9',
    List<String?>? openTill}) async {
  tester.view.physicalSize = const Size(1000, 1200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server()..hasScheme = hasScheme;
  if (openTill != null) server.openTill = openTill;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      posSessionProvider.overrideWith((ref) => _Session(ref)),
      posStoreProvider.overrideWith((ref) => 'store-de'),
      authNotifierProvider.overrideWith(() => RoleAuth('CASHIER')),
      if (openTill == null)
        saleTillProvider.overrideWith(() => _Drawer(drawer)),
    ],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showContainerReturnDialog(context),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  // As the POS shell does from the moment the terminal is on.
  ProviderScope.containerOf(tester.element(find.text('open')))
      .read(saleTillProvider);
  await tester.pumpAndSettle();
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  server.posts.clear();
  return server;
}

void main() {
  testWidgets('the scheme prices the empties as they are typed, and the refund is posted once',
      (tester) async {
    final server = await _open(tester);
    expect(find.textContaining('€0.25 back on each container'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('return-volume-0')), '500');
    await tester.enterText(find.byKey(const Key('return-count-0')), '2');
    await tester.pumpAndSettle();
    expect(find.text('Pay back €0.50'), findsOneWidget);

    await tester.tap(find.text('Another kind'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('return-volume-1')), '330');
    await tester.enterText(find.byKey(const Key('return-count-1')), '1');
    await tester.pumpAndSettle();
    expect(find.text('Pay back €0.75'), findsOneWidget);

    await tester.tap(find.byKey(const Key('return-refund')));
    await tester.pumpAndSettle();
    expect(server.posts, hasLength(1));
    final body = jsonDecode(jsonEncode(server.posts.single.data)) as Map<String, dynamic>;
    expect(body['storeId'], 'store-de');
    expect(body['tillSessionId'], 'till-9',
        reason: 'the drawer the cash comes out of, not the sign-in session');
    expect(body['tillSessionId'], isNot('sess-1'));
    expect((body['lines'] as List).length, 2);
    expect(server.posts.single.headers['Idempotency-Key'], isNotNull);
    expect(find.byType(ContainerReturnDialog), findsNothing);
  });

  testWidgets('a container outside the band counts for nothing', (tester) async {
    await _open(tester);
    await tester.enterText(find.byKey(const Key('return-volume-0')), '5000');
    await tester.enterText(find.byKey(const Key('return-count-0')), '4');
    await tester.pumpAndSettle();
    expect(find.text('Pay back €0.00'), findsOneWidget);
  });

  testWidgets('a store with no scheme in force pays nothing back', (tester) async {
    final server = await _open(tester, hasScheme: false);
    expect(find.byKey(const Key('no-scheme')), findsOneWidget);
    expect(find.byKey(const Key('return-refund')), findsOneWidget);
    await tester.tap(find.byKey(const Key('return-refund')));
    await tester.pumpAndSettle();
    expect(server.posts, isEmpty);
  });
  testWidgets(
      'with no till open nothing is paid out: the deposit leaves a drawer, '
      'and the cashier is told to open one', (tester) async {
    final server = await _open(tester, drawer: null);
    await tester.enterText(find.byKey(const Key('return-volume-0')), '500');
    await tester.enterText(find.byKey(const Key('return-count-0')), '2');
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('return-refund')));
    await tester.pumpAndSettle();

    expect(server.posts, isEmpty,
        reason: 'the sign-in session is no drawer: nothing may be booked to it');
    expect(find.textContaining('Open a till'), findsOneWidget);
    expect(find.byType(ContainerReturnDialog), findsOneWidget,
        reason: 'the dialog stays, so the count is not lost');
  });

  testWidgets(
      'a drawer the terminal could not read a moment ago is read again '
      'before the refund is paid out', (tester) async {
    final server = await _open(tester, openTill: ['down', 'till-7']);
    await tester.enterText(find.byKey(const Key('return-volume-0')), '500');
    await tester.enterText(find.byKey(const Key('return-count-0')), '2');
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('return-refund')));
    await tester.pumpAndSettle();

    expect(server.posts, hasLength(1));
    final body = jsonDecode(jsonEncode(server.posts.single.data)) as Map<String, dynamic>;
    expect(body['tillSessionId'], 'till-7');
  });
}
