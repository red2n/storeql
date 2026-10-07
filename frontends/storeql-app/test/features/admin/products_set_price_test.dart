import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/products_screen.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A variant's selling price is money in the business's currency, set at
// every till and online: it is read the way the app's language writes a
// number, to that currency's own places, and sent as the decimal typed.
// Read with a point, Romanian's 1.250 lei went to every till as 1,25; a
// Kuwaiti dinar's third place was dropped; a yen took a point.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    return jsonResponse('{"data":{}}');
  }
}

Future<_Server> _open(WidgetTester tester, {String? currency, double? current}) async {
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      defaultPriceListProvider.overrideWith((ref) async => 'L1'),
    ],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => FilledButton(
            onPressed: () => showDialog<void>(
              context: context,
              builder: (_) => SetPriceDialog(
                variantId: 'v-1',
                sku: 'OAT-1',
                current: current,
                currency: currency,
              ),
            ),
            child: const Text('Open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('Open'));
  await tester.pumpAndSettle();
  return server;
}

Finder get _price => find.byKey(const Key('set-price-amount'));

String? _says(WidgetTester tester) => tester.widget<TextField>(_price).decoration?.errorText;

bool _saveEnabled(WidgetTester tester) =>
    tester.widget<FilledButton>(find.byKey(const Key('set-price-save'))).onPressed != null;

/// Types [text] one key at a time, as a person does.
Future<void> _press(WidgetTester tester, String text) async {
  for (var i = 1; i <= text.length; i++) {
    await tester.enterText(_price, text.substring(0, i));
    await tester.pump();
  }
}

Iterable<Map<String, dynamic>> _posts(_Server server) => server.requests
    .where((r) => r.method == 'POST')
    .map((r) => (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>);

void main() {
  tearDown(() => Intl.defaultLocale = null);

  for (final (locale, currency, typed, sent) in [
    ('ro', 'RON', '1250,50', '1250.5'),
    ('ro', 'RON', '12,5', '12.5'),
    ('en_GB', 'GBP', '12.50', '12.5'),
    ('en', 'USD', '1250.5', '1250.5'),
    ('pl', 'PLN', '12,50', '12.5'),
    ('pl', 'PLN', '12.5', '12.5'),
    ('ar', 'KWD', '1٫250', '1.25'),
    ('ar', 'KWD', '1.234', '1.234'),
    ('en_GB', 'JPY', '1500', '1500'),
  ]) {
    testWidgets('in $locale, $typed $currency typed key by key is set as $sent', (tester) async {
      Intl.defaultLocale = locale;
      final server = await _open(tester, currency: currency);
      await _press(tester, typed);
      expect(_says(tester), isNull);
      await tester.tap(find.byKey(const Key('set-price-save')));
      await tester.pumpAndSettle();
      expect(_posts(server).single, {'variantId': 'v-1', 'price': sent, 'minQty': 1});
    });
  }

  for (final (locale, currency, typed, says) in [
    ('ro', 'RON', '1.250', 'Type the amount without thousands separators. Decimals go after a comma.'),
    ('en_GB', 'GBP', '1,250', 'Type the amount without thousands separators. Decimals go after a point.'),
    ('en', 'EUR', '12,50', 'Type the amount without thousands separators. Decimals go after a point.'),
    ('pl', 'PLN', '1.250', 'A point may group thousands here. Type the figure without grouping, with any decimals after a comma.'),
    ('en_GB', 'GBP', '12.505', 'At most 2 decimal places.'),
    ('en_GB', 'JPY', '1500.5', 'Whole amounts only.'),
    ('ar', 'KWD', '1.2345', 'At most 3 decimal places.'),
    ('ro', 'RON', ',', 'Type the amount in digits.'),
    ('en_GB', 'GBP', '-3', 'Type the amount without a sign.'),
  ]) {
    testWidgets('in $locale, $typed $currency is refused in words and nothing is set', (tester) async {
      Intl.defaultLocale = locale;
      final server = await _open(tester, currency: currency);
      await _press(tester, typed);
      expect(_says(tester), says);
      expect(_saveEnabled(tester), isFalse);
      await tester.tap(find.byKey(const Key('set-price-save')));
      await tester.pumpAndSettle();
      expect(_posts(server), isEmpty);
    });
  }

  // The price it holds is written at its currency's places in the app's
  // language, so Save sends it back unchanged: never 1.23 for a dinar's
  // 1.234, never a point on a yen.
  for (final (locale, currency, current, written, sent) in [
    ('ro', 'KWD', 1.234, '1,234', '1.234'),
    ('en_GB', 'KWD', 1.234, '1.234', '1.234'),
    ('pl', 'KWD', 1.5, '1,500', '1.5'),
    ('en', 'JPY', 1500.0, '1500', '1500'),
    ('ar', 'JPY', 1500.0, '1500', '1500'),
    ('ro', 'RON', 9.0, '9,00', '9'),
  ]) {
    testWidgets('in $locale, a price of $current $currency starts as $written', (tester) async {
      Intl.defaultLocale = locale;
      final server = await _open(tester, currency: currency, current: current);
      expect(tester.widget<TextField>(_price).controller!.text, written);
      expect(_says(tester), isNull);
      await tester.tap(find.byKey(const Key('set-price-save')));
      await tester.pumpAndSettle();
      expect(_posts(server).single['price'], sent);
    });
  }

  testWidgets('nought is not a price', (tester) async {
    final server = await _open(tester, currency: 'GBP');
    await _press(tester, '0.00');
    await tester.tap(find.byKey(const Key('set-price-save')));
    await tester.pumpAndSettle();
    expect(find.text('Enter a price greater than 0'), findsOneWidget);
    expect(_posts(server), isEmpty);
  });
}
