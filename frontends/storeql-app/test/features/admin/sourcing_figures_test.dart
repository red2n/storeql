import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/procurement_providers.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/sourcing_tab.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A request for quotation's lines are quantities, to three places, read the
// way the app's language writes a number and sent as the decimal typed, or
// refused under the line with nothing raised: parsed with a point,
// Romanian's 12,5 kg went out as a line of none.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/admin/products')) {
      return jsonResponse('{"data":[{"id":"p-beef","name":"Beef"}],"meta":{"nextCursor":null}}');
    }
    if (o.path.endsWith('/admin/products/p-beef/variants')) {
      return jsonResponse('{"data":[{"id":"v-beef","productId":"p-beef","sku":"BEEF-KG"}]}');
    }
    return jsonResponse('{"data":{}}');
  }

  Iterable<Map<String, dynamic>> posts() => [
        for (final r in requests)
          if (r.method == 'POST')
            (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>,
      ];
}

Future<_Server> _open(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1200, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      storesProvider.overrideWith((ref) async => const [
            StoreInfo(id: 's-1', name: 'Leeds', code: 'LDS', type: 'STORE', status: 'ACTIVE', country: 'GB'),
          ]),
      suppliersProvider.overrideWith((ref) async => const [
            Supplier(id: 'sup-1', name: 'Acme', vatRegistered: true, paymentTermsDays: 30),
          ]),
    ],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(context: context, builder: (_) => const NewRfqDialog()),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  await tester.enterText(find.byKey(const Key('rfq-title')), 'Autumn beef');
  await tester.tap(find.byKey(const Key('rfq-store')));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Leeds').last);
  await tester.pumpAndSettle();
  await tester.tap(find.text('Product *'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Beef').last);
  await tester.pumpAndSettle();
  await tester.tap(find.text('Variant *'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('BEEF-KG').last);
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('rfq-supplier-sup-1')));
  await tester.pumpAndSettle();
  return server;
}

Future<void> _press(WidgetTester tester, String text) async {
  for (var i = 1; i <= text.length; i++) {
    await tester.enterText(find.byKey(const Key('rfq-qty-0')), text.substring(0, i));
    await tester.pump();
  }
}

String? _says(WidgetTester tester) =>
    tester.widget<TextField>(find.byKey(const Key('rfq-qty-0'))).decoration?.errorText;

Finder get _raise => find.widgetWithText(FilledButton, 'Raise');

void main() {
  tearDown(() => Intl.defaultLocale = null);

  for (final (locale, typed, sent) in [
    ('ro', '12,5', '12.5'),
    ('en_GB', '12.5', '12.5'),
    ('en', '40', '40'),
    ('pl', '1,125', '1.125'),
    ('ar', '12٫5', '12.5'),
  ]) {
    testWidgets('in $locale, a line of $typed is asked for as $sent', (tester) async {
      Intl.defaultLocale = locale;
      final server = await _open(tester);
      await _press(tester, typed);
      expect(_says(tester), isNull);
      await tester.tap(_raise);
      await tester.pumpAndSettle();
      expect(server.posts().single['lines'], [
        {'variantId': 'v-beef', 'qty': sent},
      ]);
    });
  }

  for (final (locale, typed) in [('ro', '12.5'), ('en', '12,5'), ('pl', '1.250'), ('en_GB', '.'), ('ar', '-1')]) {
    testWidgets('in $locale, a line of "$typed" is refused and nothing is raised', (tester) async {
      Intl.defaultLocale = locale;
      final server = await _open(tester);
      await _press(tester, typed);
      expect(_says(tester), isNotNull);
      await tester.tap(_raise);
      await tester.pumpAndSettle();
      expect(server.posts(), isEmpty);
    });
  }
}
