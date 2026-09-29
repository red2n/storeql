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
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';
import 'package:storeql_app/features/pos/tender_screen.dart';

// ---------------------------------------------------------------------------
// Stop-sale and certified scales at the server: order-svc checks every order
// again after the till has, and refuses a recalled line (ORDER_LINE_RECALLED)
// or a line weighed on a scale not certified at the store
// (ORDER_SCALE_NOT_CERTIFIED). The till sends what the pack declared so the
// server can decide about the pack, shows the server's own words when it
// refuses, and keeps the sale on the till — never queued, because the server
// answered and would answer the same again.
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

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
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

/// A jar scanned from its 2D code: the pack said its lot and its expiry.
final _jar = PosLine(
  variantId: 'v-jam',
  sku: 'JAM-1',
  name: 'Strawberry jam',
  qty: 2,
  unitPrice: 6.0,
  currency: 'GBP',
  batchNo: 'L42',
  expiry: DateTime(2026, 10, 1),
);

class _LoadedCart extends PosCartNotifier {
  _LoadedCart() {
    loadLines([_jar]);
  }
}

/// Answers every POST /orders with a refusal in problem-details form, as
/// order-svc sends it; everything else gets a bare success.
class _Server implements HttpClientAdapter {
  _Server(this.code, this.detail);

  final String code;
  final String detail;
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/orders') && o.method == 'POST') {
      return ResponseBody.fromString(
        '{"type":"urn:storeql:problem:$code","title":"Conflict","status":409,'
        '"detail":"$detail","code":"$code",'
        '"error":{"code":"$code","message":"$detail"}}',
        409,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType]
        },
      );
    }
    return ResponseBody.fromString('{"data":{}}', 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }

  List<RequestOptions> get orderPosts => requests
      .where((r) => r.path.endsWith('/orders') && r.method == 'POST')
      .toList();
}

Future<(_Server, ProviderContainer)> _refusedSale(
  WidgetTester tester, {
  required String code,
  required String detail,
}) async {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final server = _Server(code, detail);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;

  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      offlineQueueProvider.overrideWith((ref) =>
          OfflineQueueNotifier(ref, storage: _MemStorage(), autoSync: false)),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      authNotifierProvider.overrideWith(_StubAuthNotifier.new),
      posCartProvider.overrideWith((ref) => _LoadedCart()),
      posStoreProvider.overrideWith((ref) => 'store-1'),
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
  await tester.pumpAndSettle();

  // Cash for the full balance (2 × £6), then Complete Sale.
  await tester.tap(find.widgetWithText(OutlinedButton, 'Cash'));
  await tester.pumpAndSettle();
  await tester.tap(find.widgetWithText(FilledButton, 'Add'));
  await tester.pumpAndSettle();
  await tester.ensureVisible(find.widgetWithText(FilledButton, 'Complete Sale'));
  await tester.tap(find.widgetWithText(FilledButton, 'Complete Sale'));
  await tester.pumpAndSettle();

  final container =
      ProviderScope.containerOf(tester.element(find.byType(TenderScreen)));
  return (server, container);
}

void main() {
  testWidgets('the order carries the lot and expiry the pack declared',
      (tester) async {
    final (server, _) = await _refusedSale(
      tester,
      code: 'ORDER_LINE_RECALLED',
      detail: 'Recalled.',
    );

    final body = server.orderPosts.single.data as Map<String, dynamic>;
    final line = (body['items'] as List).single as Map<String, dynamic>;
    expect(line['batchNo'], 'L42');
    expect(line['expiry'], '2026-10-01');
    expect(body.containsKey('capturedAt'), isFalse,
        reason: 'a sale made now is judged now; only a replay says when');
  });

  testWidgets(
      'a recalled line refused by the server is shown in its words, and the '
      'sale stays on the till, not queued', (tester) async {
    const words = 'This sale has stock that must not be sold: item 1 is under '
        'product recall R-2026-017 (undeclared allergen), lot L42. Take it out '
        'of the sale and hand it to a supervisor.';
    final (_, container) = await _refusedSale(
      tester,
      code: 'ORDER_LINE_RECALLED',
      detail: words,
    );

    expect(find.text(words), findsOneWidget);
    expect(container.read(posCartProvider), isNotEmpty,
        reason: 'the sale is still on the till, for the item to come out');
    expect(container.read(offlineQueueProvider), isEmpty,
        reason: 'the server answered: nothing is held to send again');
  });

  testWidgets('a line on a scale not certified here is shown in its words too',
      (tester) async {
    const words = 'Sold by weight on a scale that may not be used for trade '
        'here: item 1 was weighed on Deli 2, which is overdue for '
        're-verification. Weigh it again on a scale certified at this store.';
    final (_, container) = await _refusedSale(
      tester,
      code: 'ORDER_SCALE_NOT_CERTIFIED',
      detail: words,
    );

    expect(find.text(words), findsOneWidget);
    expect(container.read(posCartProvider), isNotEmpty);
    expect(container.read(offlineQueueProvider), isEmpty);
  });
}
