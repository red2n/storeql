import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/offline/offline_queue.dart';
import 'package:storeql_app/core/storage/app_storage.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';
import 'package:storeql_app/features/pos/tender_screen.dart';

// ---------------------------------------------------------------------------
// What the till tenders (intent/vat-inclusive-pricing.md). A basket is priced
// on the server — promotions, and the VAT inside every line — and the order is
// charged exactly what the quote said, so the till asks for the quote's total.
// Its own sum of the shelf prices is what it shows until the quote arrives and
// what it falls back to with no server.
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

class _StubAuth extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthUnauthenticated();
}

class _Cart extends PosCartNotifier {
  _Cart(List<PosLine> lines) {
    loadLines(lines);
  }
}

class _Server implements HttpClientAdapter {
  /// The basket total the server quotes, or null for a server that cannot quote.
  double? quoteTotal;
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  ResponseBody _json(String body, [int status = 200]) =>
      ResponseBody.fromString(body, status, headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      });

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'POST' && o.path.endsWith('/prices/quote')) {
      if (quoteTotal == null) return _json('{"error":{"code":"DOWN"}}', 503);
      return _json(
          '{"data":{"total":$quoteTotal,"vatAmount":0.2,"basketDiscount":0,'
          '"taxInclusive":true,"lines":[{"variantId":"v-bread","lineGross":1.0},'
          '{"variantId":"v-jam","lineGross":1.0}]}}');
    }
    return _json('{"data":{}}');
  }
}

const _bread = PosLine(
  variantId: 'v-bread', sku: 'BRE', name: 'Bread', qty: 1, unitPrice: 1.29,
  currency: 'GBP', vatCode: 'T1', vatRate: 0.2, taxInclusive: true,
);
const _jam = PosLine(
  variantId: 'v-jam', sku: 'JAM', name: 'Jam', qty: 1, unitPrice: 1.99,
  currency: 'GBP', vatCode: 'T5', vatRate: 0.05, taxInclusive: true,
);

Future<void> _pumpTender(WidgetTester tester, _Server server) async {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(
          Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server)),
      offlineQueueProvider.overrideWith((ref) =>
          OfflineQueueNotifier(ref, storage: _MemStorage(), autoSync: false)),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      authNotifierProvider.overrideWith(_StubAuth.new),
      posCartProvider.overrideWith((ref) => _Cart(const [_bread, _jam])),
      posStoreProvider.overrideWith((ref) => 'store-1'),
      posWalkInPhoneProvider.overrideWith((ref) => '07700900000'),
      posStoresProvider.overrideWith((ref) async => const []),
    ],
    child: const MaterialApp(home: Scaffold(body: TenderScreen())),
  ));
  await tester.pumpAndSettle();
}

Future<String> _cashDefault(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(OutlinedButton, 'Cash'));
  await tester.pumpAndSettle();
  final field = tester.widget<TextField>(find.byType(TextField).last);
  return field.controller!.text;
}

void main() {
  setUpAll(() => initializeDateFormatting('en'));

  testWidgets('the till asks for the server\'s quote of the basket, not its own sum',
      (tester) async {
    // Both lines are shelf prices (3.28 between them); a promotion the server
    // knows and the till does not takes it to 2.99.
    final server = _Server()..quoteTotal = 2.99;
    await _pumpTender(tester, server);

    expect(server.requests.where((r) => r.path.endsWith('/prices/quote')), isNotEmpty);
    expect(await _cashDefault(tester), '2.99');
  });

  testWidgets('with no quote the till tenders the sum of the shelf prices it was given',
      (tester) async {
    final server = _Server()..quoteTotal = null;
    await _pumpTender(tester, server);

    expect(await _cashDefault(tester), '3.28');
  });

  testWidgets('the quote is asked of the lines in the basket, by variant and quantity',
      (tester) async {
    final server = _Server()..quoteTotal = 3.28;
    await _pumpTender(tester, server);

    final ask = server.requests.firstWhere((r) => r.path.endsWith('/prices/quote'));
    final body = ask.data as Map<String, dynamic>;
    expect(body['channel'], 'POS');
    expect(body['storeId'], 'store-1');
    expect((body['lines'] as List).map((l) => (l as Map)['variantId']), ['v-bread', 'v-jam']);
  });
}
