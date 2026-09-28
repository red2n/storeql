import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/pos/container_return_dialog.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';

// ---------------------------------------------------------------------------
// Container return at the till (09.16): the scheme where the store trades
// prices the empties, the amount is shown before it is paid, the refund is
// posted once with the till session, and a store without a scheme pays none.
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

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    var body = '{"data":{}}';
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

Future<_Server> _open(WidgetTester tester, {bool hasScheme = true}) async {
  tester.view.physicalSize = const Size(1000, 1200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server()..hasScheme = hasScheme;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      posSessionProvider.overrideWith((ref) => _Session(ref)),
      posStoreProvider.overrideWith((ref) => 'store-de'),
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
    expect(body['tillSessionId'], 'sess-1');
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
}
