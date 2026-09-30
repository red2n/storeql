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
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';
import 'package:storeql_app/features/pos/tender_screen.dart';

// ---------------------------------------------------------------------------
// A gift-card tender at the till is taken by redeeming the card FIRST
// (return-controls, 2026-09-30): payment-svc records the GIFT_CARD tender
// itself from the redemption and refuses a client-posted one, so the till
// posts no GIFT_CARD payment at all. A card the server will not charge stops
// the sale before any further tender, and says why in words. An exchange's
// difference is settled on the order the server already made.
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

/// A till server. [redeemRefusal], when set, is the code a redeem answers 409 with.
class _Server implements HttpClientAdapter {
  _Server({this.redeemRefusal});

  final String? redeemRefusal;
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
    final path = o.path;
    if (o.method == 'GET' && path.endsWith('/gift-cards/GC-1')) {
      return _json('{"data":{"currentBalance":50,"currency":"GBP","status":"ACTIVE"}}');
    }
    if (o.method == 'POST' && path.endsWith('/gift-cards/GC-1/redeem')) {
      final code = redeemRefusal;
      if (code != null) {
        return _json(
            '{"type":"urn:storeql:problem:$code","status":409,"detail":"raw server text",'
            '"code":"$code","error":{"code":"$code","message":"raw server text"}}',
            409);
      }
      return _json('{"data":{"redemptionId":"r-1","giftCardId":"g-1","amount":12,"balance":38}}', 201);
    }
    if (o.method == 'POST' && path.endsWith('/orders')) {
      return _json('{"data":{"id":"order-1","total":12.0}}', 201);
    }
    if (path.endsWith('/fiscal-receipt')) {
      return _json('{"data":{"fullNumber":"2026-000042","regime":"NONE"}}');
    }
    return _json('{"data":{}}');
  }

  List<RequestOptions> posts(String fragment) => requests
      .where((r) => r.method == 'POST' && r.path.contains(fragment))
      .toList();
}

Future<(_Server, ProviderContainer)> _pump(
  WidgetTester tester, {
  String? redeemRefusal,
  bool exchange = false,
}) async {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);

  final server = _Server(redeemRefusal: redeemRefusal);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      offlineQueueProvider.overrideWith((ref) =>
          OfflineQueueNotifier(ref, storage: _MemStorage(), autoSync: false)),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      authNotifierProvider.overrideWith(_StubAuthNotifier.new),
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
      if (exchange)
        posExchangeSettlementProvider.overrideWith((ref) =>
            const PosExchangeSettlement(
                orderId: 'order-xchg', due: 4.0, currency: 'GBP', lines: [_jam])),
    ],
    child: const MaterialApp(home: Scaffold(body: TenderScreen())),
  ));
  await tester.pumpAndSettle();
  final container =
      ProviderScope.containerOf(tester.element(find.byType(TenderScreen)));
  container.read(authNotifierProvider);
  return (server, container);
}

/// Add a gift-card tender for the code GC-1 and complete the sale.
Future<void> _giftCardAndComplete(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(OutlinedButton, 'Gift card'));
  await tester.pumpAndSettle();
  await tester.enterText(find.byType(TextField).last, 'GC-1');
  await tester.tap(find.byTooltip('Check balance'));
  await tester.pumpAndSettle();
  await tester.tap(find.widgetWithText(FilledButton, 'Add'));
  await tester.pumpAndSettle();
  await tester.ensureVisible(find.widgetWithText(FilledButton, 'Complete Sale'));
  await tester.tap(find.widgetWithText(FilledButton, 'Complete Sale'));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets(
      'a gift-card tender redeems the card under a derived key and posts no '
      'GIFT_CARD payment', (tester) async {
    final (server, _) = await _pump(tester);
    await _giftCardAndComplete(tester);

    final order = server.posts('/orders').where((r) => r.path.endsWith('/orders')).single;
    final orderKey = order.headers['Idempotency-Key'] as String;

    final redeem = server.posts('/gift-cards/GC-1/redeem').single;
    expect(redeem.data, {'amount': 12.0, 'orderId': 'order-1'});
    final key = redeem.headers['Idempotency-Key'] as String;
    expect(isV7(key), isTrue, reason: 'the server refuses a key that is not a UUIDv7');
    expect(key, isNot(orderKey));

    expect(server.posts('/payments'), isEmpty,
        reason: 'payment-svc records the tender from the redemption');
    expect(find.text('Sale complete'), findsOneWidget);
  });

  testWidgets(
      'a refused redeem stops the sale in words, before any further tender',
      (tester) async {
    final (server, container) =
        await _pump(tester, redeemRefusal: 'GIFT_CARD_INSUFFICIENT_BALANCE');
    await _giftCardAndComplete(tester);

    expect(find.text('Sale complete'), findsNothing);
    expect(find.textContaining("doesn't have enough on it"), findsOneWidget,
        reason: 'the refusal is shown in words');
    expect(server.posts('/payments'), isEmpty);
    expect(server.posts('/pos/log'), isEmpty, reason: 'the sale is not journalled');
    expect(container.read(offlineQueueProvider), isEmpty,
        reason: 'the server answered and said no: nothing to replay');
    expect(container.read(posCartProvider), isNotEmpty,
        reason: 'the sale stays on the till for another tender');
  });

  testWidgets(
      "an exchange's difference is settled on the order the server made, "
      'with no new order', (tester) async {
    final (server, container) = await _pump(tester, exchange: true);

    expect(find.text('Difference due'), findsOneWidget);

    await _giftCardAndComplete(tester);

    expect(server.requests.where((r) => r.method == 'POST' && r.path.endsWith('/orders')),
        isEmpty);
    final redeem = server.posts('/gift-cards/GC-1/redeem').single;
    expect(redeem.data, {'amount': 4.0, 'orderId': 'order-xchg'});
    expect(container.read(posExchangeSettlementProvider), isNull,
        reason: 'settled: the next sale is not mistaken for it');
    expect(container.read(posCartProvider), isNotEmpty,
        reason: 'the exchange never touched the sale in the cart');
  });
}
