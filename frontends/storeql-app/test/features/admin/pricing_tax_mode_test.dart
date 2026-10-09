import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/pricing_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Selling at shelf prices (intent/vat-inclusive-pricing.md), from the admin's
// side: a price list is made as one whose prices already include VAT (and says
// so, and its price field says so), and the items that have no VAT category —
// which a shelf price cannot be worked out for — are listed with a way to give
// them one.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool ready = false;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (o.method == 'POST') return jsonResponse('{"data":{}}', 201);
    if (path.endsWith('/price-lists')) {
      return jsonResponse(jsonEncode({
        'data': [
          {'id': 'L1', 'name': 'Net prices', 'channel': 'ALL', 'currency': 'GBP', 'active': true},
          {
            'id': 'L2',
            'name': 'Shelf prices',
            'channel': 'ALL',
            'currency': 'GBP',
            'active': true,
            'taxMode': 'INCLUSIVE',
          },
        ],
        'meta': {'nextCursor': null},
      }));
    }
    if (path.endsWith('/admin/pricing/vat-readiness')) {
      return jsonResponse(jsonEncode({
        'data': ready
            ? {'taxMode': 'INCLUSIVE', 'variantsWithoutCategory': 0, 'codesWithoutRate': [], 'gaps': [], 'ready': true}
            : {
                'taxMode': 'INCLUSIVE',
                'variantsWithoutCategory': 2,
                'codesWithoutRate': [],
                'gaps': [
                  {'variantId': 'v-1', 'priceListName': 'Shelf prices', 'taxMode': 'INCLUSIVE', 'price': 1.29},
                  {'variantId': 'v-2', 'priceListName': 'Shelf prices', 'taxMode': 'INCLUSIVE', 'price': 1.99},
                ],
                'ready': false,
              },
        'meta': {},
      }));
    }
    if (path.endsWith('/vat-rates')) {
      return jsonResponse(jsonEncode({
        'data': [
          {'code': 'T1', 'name': 'Standard', 'rate': 0.2, 'exempt': false},
          {'code': 'T5', 'name': 'Reduced', 'rate': 0.05, 'exempt': false},
        ],
        'meta': {'nextCursor': null},
      }));
    }
    return jsonResponse('{"data":[],"meta":{"nextCursor":null}}');
  }
}

Future<_Server> _open(WidgetTester tester, {bool ready = false}) async {
  tester.view.physicalSize = const Size(1200, 1600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final server = _Server()..ready = ready;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('OWNER')),
    ],
    child: const MaterialApp(home: Scaffold(body: PricingScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

Iterable<RequestOptions> _posts(_Server s) => s.requests.where((r) => r.method == 'POST');

void main() {
  setUpAll(initializeDateFormatting);

  group('price lists', () {
    testWidgets('one whose prices include VAT says so in the list', (tester) async {
      await _open(tester, ready: true);

      expect(find.textContaining('prices include VAT'), findsOneWidget);
    });

    testWidgets('its price field says the price is the shelf price; a net list\'s does not',
        (tester) async {
      await _open(tester, ready: true);

      await tester.tap(find.text('Shelf prices'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Add item'));
      await tester.pumpAndSettle();
      expect(find.widgetWithText(TextField, 'Price (VAT included)'), findsOneWidget);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Close'));
      await tester.pumpAndSettle();

      await tester.tap(find.text('Net prices'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Add item'));
      await tester.pumpAndSettle();
      expect(find.widgetWithText(TextField, 'Price'), findsOneWidget);
      expect(find.widgetWithText(TextField, 'Price (VAT included)'), findsNothing);
    });

    testWidgets('the switch is off by default and sends nothing; on, it sends INCLUSIVE',
        (tester) async {
      final server = await _open(tester, ready: true);

      await tester.tap(find.text('New price list'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextField, 'Name *'), 'Net list');
      await tester.tap(find.widgetWithText(FilledButton, 'Create'));
      await tester.pumpAndSettle();
      expect((_posts(server).single.data as Map).containsKey('taxMode'), isFalse);

      await tester.tap(find.text('New price list'));
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextField, 'Name *'), 'Shelf list');
      await tester.ensureVisible(find.byKey(const Key('price-list-includes-vat')));
      await tester.tap(find.byKey(const Key('price-list-includes-vat')));
      await tester.pump();
      await tester.tap(find.widgetWithText(FilledButton, 'Create'));
      await tester.pumpAndSettle();
      expect((_posts(server).last.data as Map)['taxMode'], 'INCLUSIVE');
    });
  });

  group('the VAT readiness card', () {
    testWidgets('lists the priced items with no category and says what that means',
        (tester) async {
      await _open(tester);

      expect(find.byKey(const Key('vat-readiness-card')), findsOneWidget);
      expect(find.text('2 priced items have no VAT category'), findsOneWidget);
      expect(find.textContaining('cannot be sold until each has one'), findsOneWidget);
      // Let the name lookup's own timer run out before the test ends.
      await tester.pumpWidget(const SizedBox());
      await tester.pump(const Duration(seconds: 5));
    });

    testWidgets('is not there once every priced item has a category', (tester) async {
      await _open(tester, ready: true);

      expect(find.byKey(const Key('vat-readiness-card')), findsNothing);
    });

    testWidgets('gives the listed items one VAT code, all in one call', (tester) async {
      final server = await _open(tester);

      await tester.tap(find.byKey(const Key('vat-readiness-fix')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('vat-batch-code')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('T1 — Standard').last);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Set'));
      await tester.pumpAndSettle();

      final sent = _posts(server).single;
      expect(sent.path, endsWith('/product-vat-categories/batch'));
      expect(sent.data, {
        'items': [
          {'variantId': 'v-1', 'vatCode': 'T1'},
          {'variantId': 'v-2', 'vatCode': 'T1'},
        ],
      });
    });

    testWidgets('asks for a code before it sends anything', (tester) async {
      final server = await _open(tester);

      await tester.tap(find.byKey(const Key('vat-readiness-fix')));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Set'));
      await tester.pumpAndSettle();

      expect(_posts(server), isEmpty);
      expect(find.textContaining('Choose the VAT code'), findsOneWidget);
    });
  });
}
