import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/orders_screen.dart';
import 'package:storeql_app/shared/widgets/empty_state.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The back office's Orders screen reads in words:
//   * Collect payment offers Cash / Card / UPI / Wallet and says "£42.30
//     collected in cash." — never the codes CASH, CARD, UPI, WALLET;
//   * Picked & packed and Price order name each line by its product, not by a
//     fragment of its variant id;
//   * on a touch screen the list (and its empty state) is pulled to refresh,
//     so the header has no lone refresh icon on a row of its own; with a mouse
//     the icon is there.
// ---------------------------------------------------------------------------

const _order = '01a0c100-0000-7000-8000-000000000001';
const _store = '01a0c100-0000-7000-8000-0000000000s1';
const _mug = '01a090ae-611e-7011-ae7d-1bd68c966ff6';
const _plate = '01a090ae-611e-7011-ae7d-1bd68c966aa1';
const _bowl = '01a090ae-611e-7011-ae7d-1bd68c966bb2';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  /// The orders list; empty when set.
  bool noOrders = false;

  /// The order's status on the list and in its detail.
  String status = 'PENDING';

  /// A third line on the order (a bowl), for dialogs that must scroll.
  bool bowl = false;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/admin/products/variants/resolve')) {
      return jsonResponse('{"data":['
          '{"variantId":"$_mug","productName":"Blue mug","sku":"MUG-BLU"},'
          '{"variantId":"$_plate","productName":"Side plate","sku":"PLT-SD"},'
          '{"variantId":"$_bowl","productName":"Deep cereal bowl","sku":"BWL-DP"}]}');
    }
    if (path.endsWith('/payments/by-order/$_order')) {
      return jsonResponse('{"data":[]}');
    }
    if (path.endsWith('/payments') && o.method == 'POST') {
      return jsonResponse('{"data":{"id":"p-1","status":"CAPTURED"}}', 201);
    }
    if (path.endsWith('/orders/$_order') && o.method == 'GET') {
      return jsonResponse('{"data":{"id":"$_order","status":"$status","currency":"GBP","total":42.3,'
          '"items":[{"variantId":"$_mug","qty":5,"unitPrice":0,"lineTotal":0,"fulfilledQty":2},'
          '{"variantId":"$_plate","qty":3,"unitPrice":0,"lineTotal":0,"fulfilledQty":0}'
          '${bowl ? ',{"variantId":"$_bowl","qty":2,"unitPrice":0,"lineTotal":0,"fulfilledQty":0}' : ''}]}}');
    }
    if (path.endsWith('/order-svc/orders') && o.method == 'GET') {
      if (noOrders) return jsonResponse('{"data":[],"meta":{"nextCursor":null}}');
      return jsonResponse('{"data":[{"id":"$_order","storeId":"$_store","channel":"ONLINE",'
          '"fulfilmentType":"DELIVERY","status":"$status","total":42.3,"currency":"GBP",'
          '"createdAt":"2026-09-25T09:00:00Z","paymentMethod":"CASH"}],"meta":{"nextCursor":null}}');
    }
    return jsonResponse('{"data":[]}');
  }

  int get listReads =>
      requests.where((r) => r.method == 'GET' && r.path.endsWith('/order-svc/orders')).length;
}

Future<_Server> _pumpScreen(WidgetTester tester,
    {Size size = const Size(1200, 900),
    double textScale = 1,
    void Function(_Server)? setUp}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  setUp?.call(server);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('OWNER')),
    ],
    child: MaterialApp(
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
        child: child!,
      ),
      home: const Scaffold(body: AdminOrdersScreen()),
    ),
  ));
  await tester.pumpAndSettle();
  return server;
}

Future<_Server> _pumpDialog(WidgetTester tester, Widget Function() dialog,
    {Size? size, double textScale = 1, bool bowl = false}) async {
  if (size != null) {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
  }
  final server = _Server()
    ..status = 'AWAITING_PRICE'
    ..bowl = bowl;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
    child: MaterialApp(
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
        child: child!,
      ),
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(context: context, builder: (_) => dialog()),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return server;
}

Future<void> _openCollect(WidgetTester tester) async {
  await tester.tap(find.byTooltip('Actions'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Collect payment'));
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(initializeDateFormatting);

  group('Collect payment', () {
    testWidgets('offers the tenders in words, not codes', (tester) async {
      await _pumpScreen(tester);
      await _openCollect(tester);

      final methods = find.byKey(const Key('collect-method'));
      for (final word in ['Cash', 'Card', 'UPI', 'Wallet']) {
        expect(find.descendant(of: methods, matching: find.text(word)), findsOneWidget,
            reason: word);
      }
      for (final code in ['CASH', 'CARD', 'WALLET']) {
        expect(find.text(code), findsNothing, reason: code);
      }
    });

    testWidgets('says what was collected in words, and sends the code', (tester) async {
      final server = await _pumpScreen(tester);
      await _openCollect(tester);
      await tester.tap(find.text('Collect £42.30'));
      await tester.pumpAndSettle();

      expect(find.text('£42.30 collected in cash.'), findsOneWidget);
      expect(find.textContaining('by CASH'), findsNothing);
      final post = server.requests.singleWhere((r) => r.method == 'POST');
      final body = (post.data is String ? jsonDecode(post.data as String) : post.data) as Map;
      expect(body['method'], 'CASH');
    });

    testWidgets('another tender reads as "by card"', (tester) async {
      final server = await _pumpScreen(tester);
      await _openCollect(tester);
      await tester.tap(find.descendant(
          of: find.byKey(const Key('collect-method')), matching: find.text('Card')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Collect £42.30'));
      await tester.pumpAndSettle();

      expect(find.text('£42.30 collected by card.'), findsOneWidget);
      final post = server.requests.singleWhere((r) => r.method == 'POST');
      final body = (post.data is String ? jsonDecode(post.data as String) : post.data) as Map;
      expect(body['method'], 'CARD');
    });

    for (final scale in [1.0, 2.0]) {
      testWidgets('fits a phone, each tender on one line, at ${scale}x text', (tester) async {
        await _pumpScreen(tester, size: const Size(390, 844), textScale: scale);
        await _openCollect(tester);
        expect(tester.takeException(), isNull);

        final methods = find.byKey(const Key('collect-method'));
        double heightOf(String w) =>
            tester.getSize(find.descendant(of: methods, matching: find.text(w))).height;
        // UPI is the shortest word; none of the others is broken over more lines.
        for (final w in ['Cash', 'Card', 'Wallet']) {
          expect(heightOf(w), heightOf('UPI'), reason: w);
        }
        // And one line it is: no taller than the type's line at this scale.
        expect(heightOf('UPI'), lessThan(20 * scale * 1.5));
      });
    }
  });

  group('order lines by name', () {
    testWidgets('Picked & packed names each line by its product and SKU', (tester) async {
      await _pumpDialog(tester, () => FulfilDialog(orderId: _order, onDone: () {}));
      expect(find.text('Blue mug'), findsOneWidget);
      expect(find.text('Side plate'), findsOneWidget);
      expect(find.text('MUG-BLU · 3 of 5 outstanding'), findsOneWidget);
      expect(find.textContaining('8c966ff6'), findsNothing);
      expect(find.textContaining('8c966aa1'), findsNothing);
    });

    testWidgets('a refusal names the line too', (tester) async {
      await _pumpDialog(tester, () => FulfilDialog(orderId: _order, onDone: () {}));
      await tester.enterText(find.byKey(const Key('fulfil-qty-$_mug')), '4');
      await tester.tap(find.widgetWithText(FilledButton, 'Picked & packed'));
      await tester.pumpAndSettle();
      expect(find.text('Only 3 outstanding on Blue mug.'), findsOneWidget);
    });

    testWidgets('Price order names each line by its product', (tester) async {
      await _pumpDialog(
          tester, () => PriceOrderDialog(orderId: _order, currency: 'GBP', onDone: () {}));
      expect(find.text('Blue mug × 5'), findsOneWidget);
      expect(find.text('Side plate × 3'), findsOneWidget);
      expect(find.text('MUG-BLU'), findsOneWidget);
      expect(find.textContaining('8c966ff6'), findsNothing);
    });
  });

  group('order dialogs at 200% text on a phone', () {
    for (final (name, open, field) in [
      (
        'Picked & packed',
        () => FulfilDialog(orderId: _order, onDone: () {}),
        'fulfil-qty-$_bowl',
      ),
      (
        'Price order',
        () => PriceOrderDialog(orderId: _order, currency: 'GBP', onDone: () {}),
        'price-$_bowl',
      ),
    ]) {
      testWidgets('$name scrolls to its last line and its field, overflowing nothing',
          (tester) async {
        await _pumpDialog(tester, open,
            size: const Size(390, 844), textScale: 2, bowl: true);
        expect(tester.takeException(), isNull);
        final last = find.textContaining('Deep cereal bowl');
        await tester.scrollUntilVisible(find.byKey(Key(field)), 100,
            scrollable: find
                .descendant(of: find.byType(AlertDialog), matching: find.byType(Scrollable))
                .first);
        await tester.pumpAndSettle();
        expect(last.hitTestable(), findsOneWidget);
        expect(find.byKey(Key(field)).hitTestable(), findsOneWidget);
        expect(tester.takeException(), isNull);
      });
    }
  });

  group('refresh', () {
    testWidgets('a phone has no refresh icon under the title: the list is pulled',
        (tester) async {
      final server = await _pumpScreen(tester, size: const Size(390, 844));
      expect(find.byTooltip('Refresh'), findsNothing);
      expect(server.listReads, 1);

      await tester.fling(find.byType(ListView), const Offset(0, 400), 1000);
      await tester.pumpAndSettle();
      expect(server.listReads, 2);
    }, variant: TargetPlatformVariant.only(TargetPlatform.android));

    testWidgets('an empty list is pulled to refresh too', (tester) async {
      final server =
          await _pumpScreen(tester, size: const Size(390, 844), setUp: (s) => s.noOrders = true);
      expect(find.byType(EmptyState), findsOneWidget);
      expect(find.byTooltip('Refresh'), findsNothing);

      await tester.fling(find.byType(EmptyState), const Offset(0, 400), 1000);
      await tester.pumpAndSettle();
      expect(server.listReads, 2);
    }, variant: TargetPlatformVariant.only(TargetPlatform.android));

    testWidgets('with a mouse the refresh icon is in the header and reloads', (tester) async {
      final server = await _pumpScreen(tester);
      expect(find.byTooltip('Refresh'), findsOneWidget);
      await tester.tap(find.byTooltip('Refresh'));
      await tester.pumpAndSettle();
      expect(server.listReads, 2);
    }, variant: TargetPlatformVariant.only(TargetPlatform.macOS));
  });
}
