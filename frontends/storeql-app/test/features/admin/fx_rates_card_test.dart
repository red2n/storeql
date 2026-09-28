import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/fx_rates_card.dart';

// ---------------------------------------------------------------------------
// The exchange-rates card on the Pricing screen (03.x): the home currency alone
// to begin with, each rate as a chip once set, Set a rate posting the body and
// saying what it kept, a refusal shown by name, and no Set for staff who may not.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool hasRates;
  bool refuse = false;
  _Server({this.hasRates = true});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/admin/tenant/fx-rates') && o.method == 'GET') {
      return _json(
          hasRates
              ? '{"data":{"home":"GBP","rates":[{"currency":"USD","rate":0.79,"effectiveFrom":"2026-09-24","reason":"ECB"},{"currency":"JPY","rate":0.0053,"effectiveFrom":"2026-09-01","reason":"BoJ"}]}}'
              : '{"data":{"home":"GBP","rates":[]}}',
          200);
    }
    if (o.path.contains('/admin/tenant/fx-rates/') && o.method == 'PUT') {
      if (refuse) {
        return _json(
            '{"code":"FX_CURRENCY_INVALID","status":400,"error":{"code":"FX_CURRENCY_INVALID","message":"the home currency GBP has no rate against itself"}}',
            400);
      }
      final body = o.data is String ? jsonDecode(o.data as String) : o.data;
      return _json(
          '{"data":{"currency":"${o.path.split('/').last}","rate":${body['rate']},"effectiveFrom":"2026-09-24","reason":${jsonEncode(body['reason'])}}}',
          200);
    }
    return _json('{"data":null}', 200);
  }

  static ResponseBody _json(String body, int status) => ResponseBody.fromString(body, status,
      headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
}

Future<_Server> _pump(WidgetTester tester, {bool hasRates = true, bool management = true, bool refuse = false}) async {
  tester.view.physicalSize = const Size(1000, 800);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server(hasRates: hasRates)..refuse = refuse;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: MaterialApp(home: Scaffold(body: FxRatesCard(management: management))),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  // A rate's day is written through AppFormat (en_GB here).
  setUpAll(initializeDateFormatting);
  testWidgets('a business with no rates is told what a rate would do, in its home currency', (tester) async {
    await _pump(tester, hasRates: false);
    expect(find.textContaining('Prices are in GBP'), findsOneWidget);
    expect(find.byKey(const Key('fx-set-rate')), findsOneWidget);
  });

  testWidgets('each rate is a chip reading one unit in home units; staff who may not set see no button', (tester) async {
    await _pump(tester, management: false);
    expect(find.text('1 USD = 0.79 GBP'), findsOneWidget);
    expect(find.text('1 JPY = 0.0053 GBP'), findsOneWidget);
    expect(find.byKey(const Key('fx-set-rate')), findsNothing);
  });

  testWidgets('Set a rate puts the currency, rate, day and reason, and says what it kept', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('fx-set-rate')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('fx-currency')), 'eur');
    await tester.enterText(find.byKey(const Key('fx-rate')), '0.86');
    await tester.enterText(find.byKey(const Key('fx-from')), '2026-09-25');
    await tester.enterText(find.byKey(const Key('fx-reason')), 'ECB reference');
    await tester.tap(find.byKey(const Key('fx-save')));
    await tester.pumpAndSettle();
    final put = server.requests.lastWhere((r) => r.method == 'PUT');
    expect(put.path, endsWith('/admin/tenant/fx-rates/EUR'));
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body, {'rate': 0.86, 'effectiveFrom': '2026-09-25', 'reason': 'ECB reference'});
    expect(find.text('1 EUR = 0.86 GBP from 2026-09-25.'), findsOneWidget);
  });

  testWidgets('a refusal is shown by its reason', (tester) async {
    await _pump(tester, refuse: true);
    await tester.tap(find.byKey(const Key('fx-set-rate')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('fx-currency')), 'GBP');
    await tester.enterText(find.byKey(const Key('fx-rate')), '1');
    await tester.enterText(find.byKey(const Key('fx-reason')), 'x');
    await tester.tap(find.byKey(const Key('fx-save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('fx-refusal')), findsOneWidget);
    expect(find.textContaining('no rate against itself'), findsOneWidget);
  });
}
