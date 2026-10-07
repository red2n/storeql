import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/orders_screen.dart';

// ---------------------------------------------------------------------------
// SJ-D41: a catalog-mode till order waits for a manager's prices. The dialog
// takes a unit price per line and the VAT, sends them to /price, refuses to
// send a line without a price, and shows the server's refusal in words.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int postStatus = 200;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'POST') {
      final body = postStatus == 200
          ? '{"data":{"id":"o-1","status":"PENDING","currency":"GBP","total":16.2,"items":[]}}'
          : '{"error":{"code":"ORDER_NOT_AWAITING_PRICE","message":"only an order awaiting a price can be priced; this one is PENDING"}}';
      return ResponseBody.fromString(body, postStatus,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    }
    const body = '{"data":{"id":"o-1","status":"AWAITING_PRICE","currency":"GBP","total":0,'
        '"items":[{"variantId":"01a090ae-611e-7011-ae7d-1bd68c966ff6","qty":3,"unitPrice":0,"lineTotal":0},'
        '{"variantId":"01a090ae-611e-7011-ae7d-1bd68c966aa1","qty":1,"unitPrice":0,"lineTotal":0}]}}';
    return ResponseBody.fromString(body, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Server> _pump(WidgetTester tester, {String currency = 'GBP'}) async {
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(
              context: context,
              builder: (_) => PriceOrderDialog(orderId: 'o-1', currency: currency, onDone: () {}),
            ),
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

Map<String, dynamic> _postBody(_Server s) {
  final post = s.requests.singleWhere((r) => r.method == 'POST');
  return (post.data is String ? jsonDecode(post.data as String) : post.data) as Map<String, dynamic>;
}

void main() {
  testWidgets('every line is priced, the VAT given, and the order sent to /price', (tester) async {
    final server = await _pump(tester);
    expect(find.textContaining('× 3'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('price-01a090ae-611e-7011-ae7d-1bd68c966ff6')), '4.50');
    await tester.enterText(find.byKey(const Key('price-01a090ae-611e-7011-ae7d-1bd68c966aa1')), '2');
    await tester.enterText(find.byKey(const Key('price-tax')), '3.10');
    await tester.tap(find.text('Price and release'));
    await tester.pumpAndSettle();
    final body = _postBody(server);
    expect(body['lines'], [
      {'variantId': '01a090ae-611e-7011-ae7d-1bd68c966ff6', 'unitPrice': '4.5'},
      {'variantId': '01a090ae-611e-7011-ae7d-1bd68c966aa1', 'unitPrice': '2'},
    ]);
    expect(body['taxAmount'], '3.1');
    expect(server.requests.singleWhere((r) => r.method == 'POST').path, endsWith('/orders/o-1/price'));
    expect(find.textContaining('can be paid for now'), findsOneWidget);
  });

  testWidgets('a line left without a price is not sent', (tester) async {
    final server = await _pump(tester);
    await tester.enterText(find.byKey(const Key('price-01a090ae-611e-7011-ae7d-1bd68c966ff6')), '4.50');
    await tester.tap(find.text('Price and release'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Give every line a price'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
  });

  testWidgets("the server's refusal is shown and the dialog stays open", (tester) async {
    final server = await _pump(tester)..postStatus = 409;
    await tester.enterText(find.byKey(const Key('price-01a090ae-611e-7011-ae7d-1bd68c966ff6')), '4.50');
    await tester.enterText(find.byKey(const Key('price-01a090ae-611e-7011-ae7d-1bd68c966aa1')), '2');
    await tester.tap(find.text('Price and release'));
    await tester.pumpAndSettle();
    expect(find.textContaining('this one is PENDING'), findsOneWidget);
    expect(find.text('Price order'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), hasLength(1));
  });

  // Each unit price and the VAT are money in the order's currency, to its
  // places, read the way the app's language writes a number and sent as the
  // decimals typed: Romanian's 4,50 was no price at all, Polish's 1.250 is
  // refused as a possible group, a dinar keeps its third place.
  group('prices are read as typed, or refused', () {
    tearDown(() => Intl.defaultLocale = null);
    const mug = '01a090ae-611e-7011-ae7d-1bd68c966ff6';
    const plate = '01a090ae-611e-7011-ae7d-1bd68c966aa1';

    Future<void> press(WidgetTester tester, Finder f, String text) async {
      for (var i = 1; i <= text.length; i++) {
        await tester.enterText(f, text.substring(0, i));
        await tester.pump();
      }
    }

    String? says(WidgetTester tester, String key) =>
        tester.widget<TextField>(find.byKey(Key(key))).decoration?.errorText;

    for (final (locale, currency, mugPrice, platePrice, vat, sent) in [
      ('ro', 'RON', '4,50', '2', '3,10', ('4.5', '2', '3.1')),
      ('en_GB', 'GBP', '4.50', '2.05', '0', ('4.5', '2.05', '0')),
      ('en', 'USD', '4.5', '2', '1.25', ('4.5', '2', '1.25')),
      ('pl', 'PLN', '4,50', '2.5', '3,1', ('4.5', '2.5', '3.1')),
      ('ar', 'KWD', '1٫250', '0.125', '0٫375', ('1.25', '0.125', '0.375')),
      ('en_GB', 'JPY', '450', '200', '65', ('450', '200', '65')),
    ]) {
      testWidgets('in $locale, $mugPrice, $platePrice and $vat VAT in $currency are sent as typed',
          (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester, currency: currency);
        await press(tester, find.byKey(const Key('price-$mug')), mugPrice);
        await press(tester, find.byKey(const Key('price-$plate')), platePrice);
        await press(tester, find.byKey(const Key('price-tax')), vat);
        expect(says(tester, 'price-$mug'), isNull);
        expect(says(tester, 'price-tax'), isNull);
        await tester.tap(find.text('Price and release'));
        await tester.pumpAndSettle();
        final body = _postBody(server);
        expect(body['lines'], [
          {'variantId': mug, 'unitPrice': sent.$1},
          {'variantId': plate, 'unitPrice': sent.$2},
        ]);
        expect(body['taxAmount'], sent.$3);
      });
    }

    for (final (locale, currency, typed, says_) in [
      ('ro', 'RON', '4.50', 'Type the amount without thousands separators. Decimals go after a comma.'),
      ('en', 'EUR', '4,50', 'Type the amount without thousands separators. Decimals go after a point.'),
      ('pl', 'PLN', '1.250', 'A point may group thousands here. Type the figure without grouping, with any decimals after a comma.'),
      ('en_GB', 'JPY', '4.5', 'Whole amounts only.'),
      ('ar', 'KWD', '٫', 'Type the amount in digits.'),
      ('en_GB', 'GBP', '-1', 'Type the amount without a sign.'),
    ]) {
      testWidgets('in $locale, a price of $typed $currency is refused and nothing is sent', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester, currency: currency);
        await press(tester, find.byKey(const Key('price-$mug')), typed);
        await press(tester, find.byKey(const Key('price-$plate')), '2');
        await press(tester, find.byKey(const Key('price-tax')), typed);
        expect(says(tester, 'price-$mug'), says_);
        expect(says(tester, 'price-tax'), says_);
        await tester.tap(find.text('Price and release'));
        await tester.pumpAndSettle();
        expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
      });
    }
  });
}
