import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/pricing_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A price set on a price list goes to every till and basket that list
// prices. It is read the way the app's language writes a number, with the
// shared amount reader, and sent as the decimal it is. One the dialog cannot
// read is refused under its field and nothing is sent: read with a point,
// Romanian's 1.250 lei was set as 1,25; a minimum quantity it could not read
// went as 1.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

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
          {'id': 'L1', 'name': 'Shop prices', 'channel': 'ALL', 'currency': 'RON', 'active': true},
        ],
        'meta': {'nextCursor': null},
      }));
    }
    if (path.endsWith('/admin/products')) {
      return jsonResponse('{"data":[{"id":"p-oat","name":"Oat milk"}],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/admin/products/p-oat/variants')) {
      return jsonResponse('{"data":[{"id":"v-oat","productId":"p-oat","sku":"OAT-1"}]}');
    }
    return jsonResponse('{"data":[],"meta":{"nextCursor":null}}');
  }
}

Future<_Server> _open(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1200, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
    child: const MaterialApp(home: Scaffold(body: PricingScreen())),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Shop prices'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Add item'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Product *'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Oat milk').last);
  await tester.pumpAndSettle();
  await tester.tap(find.text('Variant *'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('OAT-1').last);
  await tester.pumpAndSettle();
  return server;
}

Finder _field(String label) => find.widgetWithText(TextField, label);

String? _says(WidgetTester tester, String label) =>
    tester.widget<TextField>(_field(label)).decoration?.errorText;

Iterable<RequestOptions> _posts(_Server server) =>
    server.requests.where((r) => r.method == 'POST');

void main() {
  setUpAll(initializeDateFormatting);
  tearDown(() => Intl.defaultLocale = null);

  testWidgets('in Romanian, 1.250 is refused in words, never set as 1,25', (tester) async {
    Intl.defaultLocale = 'ro';
    final server = await _open(tester);
    await tester.enterText(_field('Price'), '1.250');
    await tester.pump();
    expect(_says(tester, 'Price'),
        'Type the amount without thousands separators. Decimals go after a comma.');
    await tester.tap(find.text('Add'));
    await tester.pumpAndSettle();
    expect(_posts(server), isEmpty);
  });

  testWidgets('in Romanian, 12,50 from 2,5 is sent as typed', (tester) async {
    Intl.defaultLocale = 'ro';
    final server = await _open(tester);
    await tester.enterText(_field('Price'), '12,50');
    await tester.enterText(_field('Min qty'), '2,5');
    await tester.pump();
    await tester.tap(find.text('Add'));
    await tester.pumpAndSettle();
    final sent = _posts(server).single;
    expect(sent.path, endsWith('/admin/price-lists/L1/items'));
    expect(sent.data, {'variantId': 'v-oat', 'price': '12.5', 'minQty': '2.5'});
  });

  testWidgets('in English, a minimum quantity of 1,5 is refused, never sent as 1', (tester) async {
    Intl.defaultLocale = 'en_GB';
    final server = await _open(tester);
    await tester.enterText(_field('Price'), '12.50');
    await tester.enterText(_field('Min qty'), '1,5');
    await tester.pump();
    expect(_says(tester, 'Min qty'),
        'Type the amount without thousands separators. Decimals go after a point.');
    await tester.tap(find.text('Add'));
    await tester.pumpAndSettle();
    expect(_posts(server), isEmpty);
    expect(find.text('A figure cannot be read. Correct the one marked.'), findsOneWidget);
  });

  // A mark alone is no quantity and is not blank: refused, never sent as
  // pricing-svc's default of one.
  for (final (locale, mark) in [('ro', ','), ('en_GB', '.'), ('en', '.'), ('pl', ','), ('ar', '\u066B')]) {
    testWidgets('in $locale, a minimum quantity of "$mark" is refused, never sent as 1', (tester) async {
      Intl.defaultLocale = locale;
      final server = await _open(tester);
      await tester.enterText(_field('Price'), '12');
      await tester.enterText(_field('Min qty'), mark);
      await tester.pump();
      expect(_says(tester, 'Min qty'), 'Type the amount in digits.');
      await tester.tap(find.text('Add'));
      await tester.pumpAndSettle();
      expect(_posts(server), isEmpty);
    });
  }
}
