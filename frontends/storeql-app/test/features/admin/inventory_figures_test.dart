import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/inventory_screen.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Stock's figures — what arrives and at what cost, an adjustment either way,
// a reorder level and its cap — are quantities to three places and money in
// the business's currency to its own places. Each is read the way the app's
// language writes a number and sent as the decimal typed, or refused under
// its field with nothing sent: parsed with a point, Romanian's 1,5 kg was
// refused as "> 0", its cost 2,40 threw, and Polish's 1.250 became 1,25.
// ---------------------------------------------------------------------------

const _store = '01a0c200-0000-7000-8000-000000000001';
const _mug = '01a0c200-0000-7000-8000-0000000000v1';

class _Server implements HttpClientAdapter {
  final String currency;
  _Server(this.currency);
  final List<RequestOptions> requests = [];

  Iterable<Map<String, dynamic>> posts(String suffix) => [
        for (final r in requests)
          if (r.method == 'POST' && r.path.endsWith(suffix))
            (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>,
      ];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (o.method == 'POST') return jsonResponse('{"data":{}}', 201);
    if (path.endsWith('/admin/tenant')) {
      return jsonResponse(jsonEncode({
        'data': {'id': 't', 'name': 'Shop', 'status': 'ACTIVE', 'currency': currency, 'country': 'GB'}
      }));
    }
    if (path.endsWith('/admin/stores')) {
      return jsonResponse('{"data":[{"id":"$_store","name":"Leeds","code":"LDS","type":"STORE",'
          '"status":"ACTIVE"}],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/catalog/scan')) {
      return jsonResponse(jsonEncode({
        'data': {
          'item': {'variantId': _mug, 'productName': 'Blue mug', 'sku': 'MUG-BLU'},
        },
      }));
    }
    if (path.endsWith('/admin/inventory/levels/summary')) {
      return jsonResponse('{"data":{"skuCount":0,"lowStockCount":0}}');
    }
    if (path.endsWith('/admin/inventory/levels')) {
      return jsonResponse('{"data":[],"meta":{"nextCursor":null}}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pumpScreen(WidgetTester tester, String currency) async {
  tester.view.physicalSize = const Size(1280, 1100);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(currency);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('OWNER')),
      inventoryBarcodeScannerProvider.overrideWithValue((_) async => '5000000000001'),
    ],
    child: const MaterialApp(home: Scaffold(body: InventoryScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

Future<_Server> _pumpDialog(WidgetTester tester, Widget Function() dialog) async {
  tester.view.physicalSize = const Size(900, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server('GBP');
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
    child: MaterialApp(
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

/// Types [text] into [field] one key at a time, as a person does.
Future<void> _press(WidgetTester tester, Finder field, String text) async {
  for (var i = 1; i <= text.length; i++) {
    await tester.enterText(field, text.substring(0, i));
    await tester.pump();
  }
}

/// What [field] says under it.
String? _says(WidgetTester tester, Finder field) => tester
    .widget<TextField>(find.descendant(of: field, matching: find.byType(TextField)))
    .decoration
    ?.errorText;

Finder _labelled(String label) => find.widgetWithText(TextFormField, label);

void main() {
  setUpAll(initializeDateFormatting);
  tearDown(() => Intl.defaultLocale = null);

  group('receiving stock', () {
    Future<_Server> open(WidgetTester tester, String currency) async {
      final server = await _pumpScreen(tester, currency);
      await tester.tap(find.widgetWithText(FilledButton, 'Receive Stock'));
      await tester.pumpAndSettle();
      await tester.tap(find.byTooltip('Scan barcode'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Store *'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Leeds (LDS)').last);
      await tester.pumpAndSettle();
      return server;
    }

    for (final (locale, currency, qty, cost, sent) in [
      ('ro', 'RON', '1,5', '2,40', ('1.5', '2.4')),
      ('en_GB', 'GBP', '12', '0.85', ('12', '0.85')),
      ('en', 'USD', '2.125', '10', ('2.125', '10')),
      ('pl', 'PLN', '1,250', '3,5', ('1.25', '3.5')),
      ('ar', 'KWD', '2٫5', '1٫125', ('2.5', '1.125')),
      ('en_GB', 'JPY', '3', '450', ('3', '450')),
    ]) {
      testWidgets('in $locale, $qty at $cost $currency is received as typed', (tester) async {
        Intl.defaultLocale = locale;
        final server = await open(tester, currency);
        await _press(tester, _labelled('Quantity *'), qty);
        await _press(tester, _labelled('Cost price'), cost);
        expect(_says(tester, _labelled('Quantity *')), isNull);
        expect(_says(tester, _labelled('Cost price')), isNull);
        await tester.tap(find.widgetWithText(FilledButton, 'Receive'));
        await tester.pumpAndSettle();
        final body = server.posts('/inventory/receive').single;
        expect(body['qty'], sent.$1);
        expect(body['costPrice'], sent.$2);
      });
    }

    for (final (locale, currency, qty, cost, field, says) in [
      ('ro', 'RON', '1.5', '', 'Quantity *', 'Type the amount without thousands separators. Decimals go after a comma.'),
      ('pl', 'PLN', '1.250', '', 'Quantity *', 'A point may group thousands here. Type the figure without grouping, with any decimals after a comma.'),
      ('en', 'EUR', '2', '2,40', 'Cost price', 'Type the amount without thousands separators. Decimals go after a point.'),
      ('en_GB', 'JPY', '2', '450.5', 'Cost price', 'Whole amounts only.'),
      ('ar', 'KWD', '2', '٫', 'Cost price', 'Type the amount in digits.'),
      ('en_GB', 'GBP', '.', '', 'Quantity *', 'Type the amount in digits.'),
    ]) {
      testWidgets('in $locale, $field of "${field == 'Cost price' ? cost : qty}" is refused and nothing is received',
          (tester) async {
        Intl.defaultLocale = locale;
        final server = await open(tester, currency);
        await _press(tester, _labelled('Quantity *'), qty);
        if (cost.isNotEmpty) await _press(tester, _labelled('Cost price'), cost);
        await tester.tap(find.widgetWithText(FilledButton, 'Receive'));
        await tester.pumpAndSettle();
        expect(_says(tester, _labelled(field)), says);
        expect(server.posts('/inventory/receive'), isEmpty);
      });
    }
  });

  group('adjusting stock', () {
    Widget adjust() => AdjustStockDialog(
          level: const InventoryLevel(
              variantId: 'v-1', storeId: 's-1', onHand: 10, reserved: 0, available: 10),
          onDone: () {},
        );

    for (final (locale, typed, sent) in [
      ('ro', '-1,5', '-1.5'),
      ('en_GB', '−2.25', '-2.25'),
      ('en', '+3', '3'),
      ('pl', '0,125', '0.125'),
      ('ar', '-2٫5', '-2.5'),
    ]) {
      testWidgets('in $locale, $typed is adjusted by $sent', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pumpDialog(tester, adjust);
        await _press(tester, _labelled('Delta *'), typed);
        expect(_says(tester, _labelled('Delta *')), isNull);
        await tester.tap(find.text('Adjust'));
        await tester.pumpAndSettle();
        expect(server.posts('/inventory/adjust').single['delta'], sent);
      });
    }

    for (final (locale, typed, says) in [
      ('ro', '-1.5', 'Type the amount without thousands separators. Decimals go after a comma.'),
      ('pl', '-1.500', 'A point may group thousands here. Type the figure without grouping, with any decimals after a comma.'),
      ('en', '-', 'Type the digits after the sign.'),
      ('en_GB', '0', 'Non-zero number'),
      ('ar', '2-', 'Only one sign, before the digits.'),
    ]) {
      testWidgets('in $locale, $typed is refused and nothing moves', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pumpDialog(tester, adjust);
        await _press(tester, _labelled('Delta *'), typed);
        await tester.tap(find.text('Adjust'));
        await tester.pumpAndSettle();
        expect(_says(tester, _labelled('Delta *')), says);
        expect(server.posts('/inventory/adjust'), isEmpty);
      });
    }
  });

  group('a reorder level', () {
    Widget threshold() => SetThresholdDialog(storeId: 's-1', variantId: 'v-1', onDone: () {});

    for (final (locale, level, cap, sent) in [
      ('ro', '2,5', '12,25', ('2.5', '12.25')),
      ('en_GB', '4', '', ('4', null)),
      ('en', '0.5', '6', ('0.5', '6')),
      ('pl', '1,125', '3', ('1.125', '3')),
      ('ar', '2٫5', '1٫5', ('2.5', '1.5')),
    ]) {
      testWidgets('in $locale, $level up to "$cap" is saved as typed', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pumpDialog(tester, threshold);
        await _press(tester, _labelled('Threshold *'), level);
        if (cap.isNotEmpty) await _press(tester, _labelled('Max qty (optional)'), cap);
        await tester.tap(find.widgetWithText(FilledButton, 'Save'));
        await tester.pumpAndSettle();
        final body = server.posts('/inventory/thresholds').single;
        expect(body['threshold'], sent.$1);
        expect(body['maxQty'], sent.$2);
        expect(body.containsKey('maxQty'), cap.isNotEmpty);
      });
    }

    for (final (locale, level, cap) in [
      ('ro', '2.5', ''),
      ('pl', '1.250', ''),
      ('en', '2', '1,5'),
      ('en_GB', '2', '.'),
      ('ar', '-2', ''),
    ]) {
      testWidgets('in $locale, $level up to "$cap" is refused and nothing is saved', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pumpDialog(tester, threshold);
        await _press(tester, _labelled('Threshold *'), level);
        if (cap.isNotEmpty) await _press(tester, _labelled('Max qty (optional)'), cap);
        await tester.tap(find.widgetWithText(FilledButton, 'Save'));
        await tester.pumpAndSettle();
        expect(
          _says(tester, _labelled('Threshold *')) ?? _says(tester, _labelled('Max qty (optional)')),
          isNotNull,
        );
        expect(server.posts('/inventory/thresholds'), isEmpty);
      });
    }
  });
}
