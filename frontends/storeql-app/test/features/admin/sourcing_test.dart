import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/sourcing_tab.dart';

import '../../support/fake_api.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The Procurement screen's "Sourcing" tab: the requests listed with how far the
// asking has got; the detail's comparison with the lowest price per line marked
// in the business's own money, the totals ranked and each supplier's grade; the
// award dialog picking the lowest for each line and posting the awards; a
// recorded quote posting prices per line; and a cashier seeing no buttons.
// ---------------------------------------------------------------------------

const _rfq = '01a0b300-0000-7000-8000-0000000000r1';
const _va = '01a0b300-0000-7000-8000-0000000000v1';
const _vb = '01a0b300-0000-7000-8000-0000000000v2';
const _s1 = '01a0b300-0000-7000-8000-0000000000s1';
const _s2 = '01a0b300-0000-7000-8000-0000000000s2';
const _s3 = '01a0b300-0000-7000-8000-0000000000s3';

const _detail = '{"id":"$_rfq","reference":"RFQ-000001","title":"Autumn beef","storeId":"01a0b300-0000-7000-8000-000000000001","status":"ISSUED","neededBy":"2026-10-08",'
    '"lines":[{"id":"l1","variantId":"$_va","qty":10},{"id":"l2","variantId":"$_vb","qty":5}],'
    '"bids":['
    '{"supplierId":"$_s1","supplierName":"Highland Meats","status":"QUOTED","currency":"GBP","leadTimeDays":4,"grade":"C","prices":[{"variantId":"$_va","unitPrice":10.00},{"variantId":"$_vb","unitPrice":4.00}]},'
    '{"supplierId":"$_s2","supplierName":"Boucherie Nord","status":"QUOTED","currency":"EUR","leadTimeDays":4,"grade":"A","prices":[{"variantId":"$_va","unitPrice":11.00},{"variantId":"$_vb","unitPrice":5.00}]},'
    '{"supplierId":"$_s3","supplierName":"Quiet Farm","status":"DECLINED","prices":[]}],'
    '"comparison":{"homeCurrency":"GBP","lines":['
    '{"variantId":"$_va","qty":10,"prices":[{"supplierId":"$_s1","unitPrice":10.00,"currency":"GBP","homeUnitPrice":10.00,"lineTotal":100.00,"homeLineTotal":100.00,"lowest":false},{"supplierId":"$_s2","unitPrice":11.00,"currency":"EUR","homeUnitPrice":9.35,"lineTotal":110.00,"homeLineTotal":93.50,"lowest":true}]},'
    '{"variantId":"$_vb","qty":5,"prices":[{"supplierId":"$_s1","unitPrice":4.00,"currency":"GBP","homeUnitPrice":4.00,"lineTotal":20.00,"homeLineTotal":20.00,"lowest":true},{"supplierId":"$_s2","unitPrice":5.00,"currency":"EUR","homeUnitPrice":4.25,"lineTotal":25.00,"homeLineTotal":21.25,"lowest":false}]}],'
    '"bids":[{"supplierId":"$_s1","supplierName":"Highland Meats","status":"QUOTED","complete":true,"total":120.00,"currency":"GBP","homeTotal":120.00,"rank":2},'
    '{"supplierId":"$_s2","supplierName":"Boucherie Nord","status":"QUOTED","complete":true,"total":135.00,"currency":"EUR","homeTotal":114.75,"rank":1},'
    '{"supplierId":"$_s3","supplierName":"Quiet Farm","status":"DECLINED","complete":false}]},'
    '"awards":[],"purchaseOrderIds":[]}';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/rfqs') && o.method == 'GET') {
      return jsonResponse(
          '{"data":[{"id":"$_rfq","reference":"RFQ-000001","title":"Autumn beef","storeId":"x","status":"ISSUED","neededBy":"2026-10-08","lines":2,"suppliers":3,"quotes":2}]}');
    }
    if (path.endsWith('/rfqs/$_rfq') && o.method == 'GET') {
      return jsonResponse('{"data":$_detail}');
    }
    if (path.endsWith('/rfqs/$_rfq/award')) {
      return jsonResponse('{"data":{"id":"$_rfq","status":"AWARDED","purchaseOrderIds":["p1","p2"]}}');
    }
    if (path.contains('/rfqs/$_rfq/quotes/')) {
      return jsonResponse('{"data":$_detail}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester, {String role = 'MANAGER'}) async {
  tester.view.physicalSize = const Size(1200, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
    ],
    child: const MaterialApp(home: Scaffold(body: SourcingTab())),
  ));
  await tester.pumpAndSettle();
  return server;
}

Future<void> _openDetail(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('rfq-$_rfq')));
  await tester.pumpAndSettle();
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);
  testWidgets('the requests are listed with how far the asking has got', (tester) async {
    await _pump(tester);
    expect(find.text('RFQ-000001 · Autumn beef'), findsOneWidget);
    expect(find.text('2 lines · 2 of 3 suppliers quoted · needed by 8 Oct 2026'), findsOneWidget);
    expect(find.byKey(const Key('rfq-status-ISSUED')), findsOneWidget);
    // The status in words, not the code.
    expect(find.text('Out for quotes'), findsOneWidget);
    expect(find.text('ISSUED'), findsNothing);
    expect(find.byKey(const Key('rfq-new')), findsOneWidget);
  });

  testWidgets('the detail marks the lowest at home per line, ranks the totals and shows the grades',
      (tester) async {
    await _pump(tester);
    await _openDetail(tester);
    expect(find.byKey(const Key('rfq-comparison')), findsOneWidget);
    // The euro quote wins the first line at 9.35 at home; the pound quote the second.
    expect(find.byKey(const Key('rfq-lowest-$_va')), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const Key('rfq-lowest-$_va'))).data, contains('9.35'));
    expect(find.byKey(const Key('rfq-lowest-$_vb')), findsOneWidget);
    expect(tester.widget<Text>(find.byKey(const Key('rfq-lowest-$_vb'))).data, contains('4.00'));
    expect(tester.widget<Text>(find.byKey(const Key('rfq-total-$_s2'))).data, contains('#1'));
    expect(tester.widget<Text>(find.byKey(const Key('rfq-total-$_s1'))).data, contains('#2'));
    expect(find.byKey(const Key('grade-A')), findsWidgets);
    expect(find.text('Declined to quote'), findsOneWidget);
    expect(find.byKey(const Key('rfq-award')), findsOneWidget);
  });

  testWidgets('Award picks the lowest for each line and posts the awards', (tester) async {
    final server = await _pump(tester);
    await _openDetail(tester);
    await tester.tap(find.byKey(const Key('rfq-award')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('award-save')));
    await tester.pumpAndSettle();
    final post = server.requests.lastWhere((r) => r.method == 'POST');
    expect(post.path, endsWith('/rfqs/$_rfq/award'));
    final body = post.data is String ? jsonDecode(post.data as String) : post.data;
    expect(body['awards'], [
      {'variantId': _va, 'supplierId': _s2},
      {'variantId': _vb, 'supplierId': _s1},
    ]);
    expect(find.textContaining('2 draft orders raised'), findsOneWidget);
  });

  testWidgets('a recorded quote posts prices per line in the supplier\'s currency',
      (tester) async {
    final server = await _pump(tester);
    await _openDetail(tester);
    await tester.tap(find.byKey(const Key('rfq-quote-$_s1')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('quote-price-$_va')), '9.50');
    await tester.enterText(find.byKey(const Key('quote-price-$_vb')), '');
    await tester.tap(find.byKey(const Key('quote-save')));
    await tester.pumpAndSettle();
    final put = server.requests.lastWhere((r) => r.method == 'PUT');
    expect(put.path, endsWith('/rfqs/$_rfq/quotes/$_s1'));
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body['currency'], 'GBP');
    expect(body['leadTimeDays'], 4);
    // The price goes as the decimal typed: a double could not carry every
    // price a batch's cost can.
    expect(body['lines'], [
      {'variantId': _va, 'unitPrice': '9.5'},
    ]);
  });

  // A price is read the way the app's language writes it, and one it cannot
  // read is refused where it was typed — never recorded at 0.00, where the
  // comparison ranked it lowest and an award raised an order at nothing.
  Future<void> openQuote(WidgetTester tester) async {
    await _openDetail(tester);
    await tester.tap(find.byKey(const Key('rfq-quote-$_s1')));
    await tester.pumpAndSettle();
  }

  Future<void> save(WidgetTester tester) async {
    await tester.tap(find.byKey(const Key('quote-save')));
    await tester.pumpAndSettle();
  }

  String? priceSays(WidgetTester tester, String variantId) => tester
      .widget<TextField>(find.byKey(Key('quote-price-$variantId')))
      .decoration
      ?.errorText;

  List<RequestOptions> puts(_Server s) => s.requests.where((r) => r.method == 'PUT').toList();

  testWidgets('in Romanian, a quoted 12,50 is twelve fifty, and the prices already quoted read as Romanian writes them',
      (tester) async {
    Intl.defaultLocale = 'ro';
    final server = await _pump(tester);
    await openQuote(tester);
    expect(tester.widget<TextField>(find.byKey(const Key('quote-price-$_vb'))).controller!.text, '4,00');
    await tester.enterText(find.byKey(const Key('quote-price-$_va')), '12,50');
    await tester.pump();
    expect(priceSays(tester, _va), isNull);
    await save(tester);
    final put = puts(server).single;
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body['lines'], [
      {'variantId': _va, 'unitPrice': '12.5'},
      {'variantId': _vb, 'unitPrice': '4'},
    ]);
  });

  for (final (locale, typed, why) in [
    ('ro', '12.50', 'Type the amount without thousands separators. Decimals go after a comma.'),
    ('en_GB', '12,50', 'Type the amount without thousands separators. Decimals go after a point.'),
    ('en_GB', 'twelve', 'Only digits and a decimal point.'),
    ('en_GB', '-12', 'Type the amount without a sign.'),
  ]) {
    testWidgets('in $locale, a quoted $typed is refused where it was typed and nothing is recorded',
        (tester) async {
      Intl.defaultLocale = locale;
      final server = await _pump(tester);
      await openQuote(tester);
      await tester.enterText(find.byKey(const Key('quote-price-$_va')), typed);
      await tester.pump();
      expect(priceSays(tester, _va), why);
      await save(tester);
      expect(puts(server), isEmpty, reason: 'never recorded at 0');
      expect(find.byKey(const Key('quote-refusal')), findsOneWidget);
      expect(find.byType(RecordQuoteDialog), findsOneWidget, reason: 'the dialog stays open to correct it');
    });
  }

  // The quoted lead time is whole days. Text that is not a number of days
  // was recorded as a quote with no lead time, and is now refused under its
  // field with nothing recorded.
  for (final (typed, why) in [
    ('1,000', 'Type the amount without thousands separators.'),
    ('15.', 'Whole amounts only.'),
    ('.', 'Whole amounts only.'),
    ('-', 'Type the amount without a sign.'),
    ('+5', 'Type the amount without a sign.'),
    ('0x10', 'Only digits.'),
  ]) {
    testWidgets('a lead time of "$typed" is refused where it was typed and nothing is recorded',
        (tester) async {
      final server = await _pump(tester);
      await openQuote(tester);
      await tester.enterText(find.byKey(const Key('quote-lead')), typed);
      await tester.pump();
      expect(tester.widget<TextField>(find.byKey(const Key('quote-lead'))).decoration?.errorText, why);
      await save(tester);
      expect(puts(server), isEmpty, reason: 'never recorded with no lead time');
      expect(find.byKey(const Key('quote-refusal')), findsOneWidget);
    });
  }

  testWidgets('a free line is quoted at nought', (tester) async {
    final server = await _pump(tester);
    await openQuote(tester);
    await tester.enterText(find.byKey(const Key('quote-price-$_va')), '0');
    await tester.enterText(find.byKey(const Key('quote-price-$_vb')), '');
    await save(tester);
    final put = puts(server).single;
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body['lines'], [
      {'variantId': _va, 'unitPrice': '0'},
    ]);
  });

  testWidgets('a cashier reads the requests but raises, quotes and awards nothing', (tester) async {
    await _pump(tester, role: 'CASHIER');
    expect(find.byKey(const Key('rfq-new')), findsNothing);
    await _openDetail(tester);
    expect(find.byKey(const Key('rfq-comparison')), findsOneWidget);
    expect(find.byKey(const Key('rfq-award')), findsNothing);
    expect(find.byKey(const Key('rfq-quote-$_s1')), findsNothing);
  });
}
