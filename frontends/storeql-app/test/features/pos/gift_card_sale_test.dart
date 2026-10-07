import 'dart:convert';

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
import 'package:storeql_app/features/pos/cart_screen.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';
import 'package:storeql_app/features/pos/pos_receipt_data.dart';
import 'package:storeql_app/features/pos/pos_receipt_escpos.dart';
import 'package:storeql_app/features/pos/pos_session_providers.dart';
import 'package:storeql_app/features/pos/tender_screen.dart';

// ---------------------------------------------------------------------------
// A gift card is sold at the till as a line of the sale (order-svc, 30 Sep
// 2026): an amount, and optionally the card to top up. It goes to the server
// as `giftCardLoads`, never `items`; the card exists only once the sale is
// paid, so its code is read from the paid order and shown once on the
// completion screen and the receipt. A card is never sold while the till is
// offline, because it cannot be issued offline.
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
  /// Drop the connection on the order, or on the payment after it.
  bool dropOrder = false;
  bool dropPayment = false;

  /// What GET /orders/{id}/gift-card-loads says about the cards sold.
  String cards = '[{"amount":25,"status":"LOADED","code":"GC-NEW-1","kind":"NEW"}]';
  final List<RequestOptions> requests = [];

  /// When set, answered from the second ask of the cards on (the first says [cards]).
  String? cardsLater;
  int _cardAsks = 0;

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
    final p = o.path;
    if (o.method == 'POST' && p.endsWith('/orders')) {
      if (dropOrder) {
        throw DioException(requestOptions: o, type: DioExceptionType.connectionError);
      }
      return _json('{"data":{"id":"order-1","total":25.0}}', 201);
    }
    if (o.method == 'POST' && p.endsWith('/payments')) {
      if (dropPayment) {
        throw DioException(requestOptions: o, type: DioExceptionType.connectionError);
      }
      return _json('{"data":{}}', 201);
    }
    if (o.method == 'GET' && p.endsWith('/orders/order-1/gift-card-loads')) {
      return _json('{"data":${_cardAsks++ > 0 && cardsLater != null ? cardsLater : cards}}');
    }
    if (p.endsWith('/fiscal-receipt')) {
      return _json('{"data":{"fullNumber":"2026-000042","regime":"NONE"}}');
    }
    return _json('{"data":{}}');
  }

  List<RequestOptions> posts(String fragment) => requests
      .where((r) => r.method == 'POST' && r.path.endsWith(fragment))
      .toList();
}

const _jam = PosLine(
    variantId: 'v-jam', sku: 'JAM', name: 'Jam', qty: 1, unitPrice: 4, currency: 'GBP');

PosLine _card({double amount = 25, String? code}) =>
    PosLine.giftCardSale(amount: amount, currency: 'GBP', code: code);

List<Override> _overrides(_Server server, List<PosLine> lines,
        {bool showPrices = true}) =>
    [
      apiClientProvider.overrideWithValue(_FakeApiClient(
          Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server)),
      offlineQueueProvider.overrideWith((ref) =>
          OfflineQueueNotifier(ref, storage: _MemStorage(), autoSync: false)),
      posSessionProvider.overrideWith((ref) => _NoopPosSessionNotifier(ref)),
      authNotifierProvider.overrideWith(_StubAuth.new),
      posCartProvider.overrideWith((ref) => _Cart(lines)),
      posStoreProvider.overrideWith((ref) => 'store-1'),
      posWalkInPhoneProvider.overrideWith((ref) => '07700900000'),
      posStoresProvider.overrideWith((ref) async => const []),
      if (!showPrices) posShowPricesProvider.overrideWith((ref) => false),
    ];

Future<ProviderContainer> _pumpTender(WidgetTester tester, _Server server, List<PosLine> lines) async {
  tester.view.physicalSize = const Size(800, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: _overrides(server, lines),
    child: const MaterialApp(home: Scaffold(body: TenderScreen())),
  ));
  final c = ProviderScope.containerOf(tester.element(find.byType(TenderScreen)));
  c.read(authNotifierProvider);
  await tester.pumpAndSettle();
  return c;
}

Future<void> _payCashAndComplete(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(OutlinedButton, 'Cash'));
  await tester.pumpAndSettle();
  await tester.tap(find.widgetWithText(FilledButton, 'Add'));
  await tester.pumpAndSettle();
  await tester.ensureVisible(find.widgetWithText(FilledButton, 'Complete Sale'));
  await tester.tap(find.widgetWithText(FilledButton, 'Complete Sale'));
  await tester.pumpAndSettle();
}

Future<ProviderContainer> _pumpCart(WidgetTester tester, _Server server,
    {bool showPrices = true}) async {
  tester.view.physicalSize = const Size(900, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: _overrides(server, const [], showPrices: showPrices),
    child: const MaterialApp(home: Scaffold(body: PosCartScreen())),
  ));
  await tester.pumpAndSettle();
  return ProviderScope.containerOf(tester.element(find.byType(PosCartScreen).first));
}

void main() {
  setUpAll(initializeDateFormatting);

  group('adding a card to the sale', () {
    testWidgets('Gift card asks an amount, and the card is its own line', (tester) async {
      final c = await _pumpCart(tester, _Server());
      await tester.tap(find.byKey(const Key('pos-sell-gift-card')));
      await tester.pumpAndSettle();
      expect(find.text('Sell a gift card'), findsOneWidget);
      // No amount, no card.
      expect(
          tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Add to sale')).onPressed,
          isNull);
      await tester.enterText(find.byKey(const Key('gift-card-sale-amount')), '25');
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Add to sale'));
      await tester.pumpAndSettle();

      final lines = c.read(posCartProvider);
      expect(lines.single.giftCard, isTrue);
      expect(lines.single.unitPrice, 25.0);
      expect(lines.single.giftCardCode, isNull);
      expect(find.text('Gift card'), findsWidgets);
      // A card is changed by taking it off, not by a stepper.
      expect(find.byTooltip('Increase quantity'), findsNothing);
    });

    testWidgets('an existing card can be named to top it up', (tester) async {
      final c = await _pumpCart(tester, _Server());
      await tester.tap(find.byKey(const Key('pos-sell-gift-card')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('gift-card-sale-amount')), '10');
      await tester.enterText(find.byKey(const Key('gift-card-sale-code')), ' GC-7777 ');
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Add to sale'));
      await tester.pumpAndSettle();
      expect(c.read(posCartProvider).single.giftCardCode, 'GC-7777');
      expect(find.text('Gift card top-up'), findsOneWidget);
    });

    testWidgets('a store that shows no prices does not offer a card', (tester) async {
      await _pumpCart(tester, _Server(), showPrices: false);
      expect(find.byKey(const Key('pos-sell-gift-card')), findsNothing);
    });

    testWidgets('a discount comes off the goods, never off a card', (tester) async {
      final c = await _pumpCart(tester, _Server());
      c.read(posCartProvider.notifier).loadLines([_jam, _card()]);
      await tester.pump();
      final cart = c.read(posCartProvider.notifier);
      expect(cart.total, 29.0);
      expect(cart.goodsTotal, 4.0);
      await tester.tap(find.text('Add discount'));
      await tester.pumpAndSettle();
      final fields = find.descendant(
          of: find.byType(AlertDialog), matching: find.byType(TextField));
      await tester.enterText(fields.at(0), '99');
      await tester.enterText(fields.at(1), 'goodwill');
      await tester.pumpAndSettle();
      await tester.tap(find.text('Apply'));
      await tester.pumpAndSettle();
      expect(c.read(posDiscountProvider), 4.0);
    });

    testWidgets('a sale holding a card cannot be held for later', (tester) async {
      final server = _Server();
      final c = await _pumpCart(tester, server);
      c.read(posCartProvider.notifier).loadLines([_card()]);
      await tester.pump();
      await tester.tap(find.widgetWithText(TextButton, 'Hold'));
      await tester.pumpAndSettle();
      expect(find.textContaining('cannot be held'), findsOneWidget);
      expect(server.posts('/parked-sales'), isEmpty);
      expect(c.read(posCartProvider), hasLength(1));
    });
  });

  group('selling the card', () {
    testWidgets('a card-only sale sends giftCardLoads and no items, then shows the code once',
        (tester) async {
      final server = _Server();
      await _pumpTender(tester, server, [_card(amount: 25)]);
      await _payCashAndComplete(tester);

      final order = server.posts('/orders').single;
      expect(order.data['giftCardLoads'], [
        {'amount': 25.0}
      ]);
      expect(order.data['items'], isEmpty);
      // The paid order is read for the card's code.
      expect(
          server.requests.any((r) =>
              r.method == 'GET' && r.path.endsWith('/orders/order-1/gift-card-loads')),
          isTrue);
      expect(find.text('Sale complete'), findsOneWidget);
      expect(find.byKey(const Key('sold-gift-card-code')), findsOneWidget);
      expect(find.text('GC-NEW-1'), findsOneWidget);
    });

    testWidgets('a top-up names its card and a mixed sale keeps its goods as items',
        (tester) async {
      final server = _Server()..cards = '[{"amount":10,"status":"LOADED","code":"GC-7777","kind":"TOP_UP"}]';
      await _pumpTender(tester, server, [_jam, _card(amount: 10, code: 'GC-7777')]);
      await _payCashAndComplete(tester);

      final order = server.posts('/orders').single;
      expect(order.data['giftCardLoads'], [
        {'amount': 10.0, 'code': 'GC-7777'}
      ]);
      expect((order.data['items'] as List).single['variantId'], 'v-jam');
      expect(find.text('GC-7777'), findsOneWidget);
      expect(find.textContaining('Gift card top-up'), findsWidgets);
    });

    testWidgets('a card still pending when first asked is asked for again until it is issued',
        (tester) async {
      final server = _Server()
        ..cards = '[{"amount":25,"status":"PENDING"}]'
        ..cardsLater = '[{"amount":25,"status":"LOADED","code":"GC-LATE-1","kind":"NEW"}]';
      await _pumpTender(tester, server, [_card()]);
      await _payCashAndComplete(tester);
      expect(find.text('GC-LATE-1'), findsOneWidget);
      expect(find.byKey(const Key('sold-gift-card-no-code')), findsNothing);
    });

    testWidgets('a card not yet issued has no code: the screen says so and invents none',
        (tester) async {
      final server = _Server()..cards = '[{"amount":25,"status":"PENDING"}]';
      await _pumpTender(tester, server, [_card()]);
      await _payCashAndComplete(tester);
      expect(find.byKey(const Key('sold-gift-card-no-code')), findsOneWidget);
      expect(find.byKey(const Key('sold-gift-card-code')), findsNothing);
    });

    testWidgets('a sale with no card never asks the order for one', (tester) async {
      final server = _Server();
      await _pumpTender(tester, server, [_jam]);
      await _payCashAndComplete(tester);
      expect(server.posts('/orders').single.data.containsKey('giftCardLoads'), isFalse);
      expect(server.requests.where((r) => r.method == 'GET' && r.path.contains('gift-card-loads')),
          isEmpty);
    });

    testWidgets('offline before the order lands: refused in words, nothing queued, sale kept',
        (tester) async {
      final server = _Server()..dropOrder = true;
      final c = await _pumpTender(tester, server, [_card()]);
      await _payCashAndComplete(tester);
      expect(find.textContaining('cannot be sold while the till is offline'), findsOneWidget);
      expect(find.text('Saved offline'), findsNothing);
      expect(c.read(offlineQueueProvider), isEmpty);
      expect(c.read(posCartProvider), hasLength(1), reason: 'the sale is still at the till');
    });

    testWidgets('offline after the order landed: queued, and says the code waits for the sync',
        (tester) async {
      final server = _Server()..dropPayment = true;
      final c = await _pumpTender(tester, server, [_card()]);
      await _payCashAndComplete(tester);
      expect(find.text('Saved offline'), findsOneWidget);
      expect(find.byKey(const Key('offline-gift-card-note')), findsOneWidget);
      expect(c.read(offlineQueueProvider), hasLength(1));
    });
  });

  group('the receipt', () {
    PosReceiptData receipt(List<SoldGiftCard> cards) => PosReceiptData(
          orderId: 'order-1',
          storeName: 'High Street',
          dateTime: DateTime(2026, 9, 30, 10),
          items: [_card()],
          subtotal: 25,
          discount: 0,
          total: 25,
          currency: 'GBP',
          tenders: const [],
          change: 0,
          soldCards: cards,
        );

    test('prints the card and its code once, on paper and on the thermal printer', () {
      final r = receipt(const [SoldGiftCard(amount: 25, code: 'GC-NEW-1')]);
      final html = r.toHtml();
      expect('GC-NEW-1'.allMatches(html).length, 1);
      expect(html, contains('Gift card'));
      final bytes = utf8.decode(const EscPosReceipt().encode(r), allowMalformed: true);
      expect('GC-NEW-1'.allMatches(bytes).length, 1);
    });

    test('a card without a code says so; a sale with no card prints no block', () {
      expect(receipt(const [SoldGiftCard(amount: 25)]).toHtml(), contains('code not available'));
      expect(receipt(const []).toHtml(), isNot(contains('data-gift-card')));
    });

    test('carries the cards through a later reprint with the fiscal number', () {
      final r = receipt(const [SoldGiftCard(amount: 25, code: 'GC-NEW-1')])
          .withFiscalNumber('2026-000042');
      expect(r.soldCards.single.code, 'GC-NEW-1');
    });
  });
}
