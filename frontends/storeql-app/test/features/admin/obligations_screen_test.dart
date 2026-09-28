import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/obligations_screen.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The laws this business trades under: what is in force, what is coming, and
// what the screen says when it has nothing or cannot find out.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Tenant implements HttpClientAdapter {
  int status = 200;
  String body = '{"data":{"country":"GB","on":"2026-09-14","obligations":[]}}';
  RequestOptions? last;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    last = o;
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Tenant> _pump(WidgetTester tester, void Function(_Tenant) setUp,
    {Size size = const Size(1100, 1400), double textScale = 1}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final tenant = _Tenant();
  setUp(tenant);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = tenant;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: MaterialApp(
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
        child: child!,
      ),
      home: const Scaffold(body: ObligationsScreen()),
    ),
  ));
  await tester.pumpAndSettle();
  return tenant;
}

const _gb = '{"data":{"country":"GB","on":"2026-09-14","obligations":['
    '{"code":"UNIT_PRICING","scope":"GB","effectiveFrom":"2026-04-06",'
    '"citation":"Price Marking Order 2004, as amended","summary":"Unit prices are shown legibly.","status":"IN_FORCE"},'
    '{"code":"TOBACCO_BIRTH_COHORT","scope":"GB","effectiveFrom":"2027-01-01",'
    '"citation":"Tobacco and Vapes Act 2026","summary":"No tobacco is sold to anyone born on or after 1 January 2009.","status":"UPCOMING"}]}}';

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);

  testWidgets('what is in force and what is coming, each with its day and instrument',
      (tester) async {
    final tenant = await _pump(tester, (t) => t.body = _gb);

    expect(tenant.last!.path, '/tenant-svc/admin/tenant/obligations');
    // The country in words, not its ISO code.
    expect(find.text('In force in the United Kingdom'), findsOneWidget);
    expect(find.text('In force in GB'), findsNothing);
    expect(find.text('Coming'), findsOneWidget);
    expect(find.text('Unit prices are shown legibly.'), findsOneWidget);
    expect(find.text('Since 6 Apr 2026'), findsOneWidget);
    expect(find.text('From 1 Jan 2027'), findsOneWidget);
    expect(find.textContaining('Tobacco and Vapes Act 2026 · National law'), findsOneWidget);
  });

  testWidgets('EU law is labelled as such, with the day it stopped when it did',
      (tester) async {
    await _pump(
        tester,
        (t) => t.body = '{"data":{"country":"GB","on":"2019-06-01","obligations":['
            '{"code":"GDPR","scope":"EU","effectiveFrom":"2018-05-25","effectiveTo":"2020-01-31",'
            '"citation":"Regulation (EU) 2016/679","summary":"Personal data needs a lawful basis.","status":"IN_FORCE"}]}}');
    expect(find.textContaining('EU law · until 31 Jan 2020'), findsOneWidget);
    expect(find.text('Coming'), findsNothing);
  });

  testWidgets('cash limits that reach the country are listed with their amount, currency and day',
      (tester) async {
    await _pump(tester, (t) => t.body =
        '{"data":{"country":"FR","on":"2026-09-16","obligations":[],"cashLimits":['
        '{"scope":"FR","currency":"EUR","fromAmount":1000.00,"effectiveFrom":"2015-09-01","citation":"CMF art. L112-6","summary":"A resident may not pay a business EUR 1,000 or more in cash.","status":"IN_FORCE"},'
        '{"scope":"EU","currency":"EUR","fromAmount":10000.00,"effectiveFrom":"2027-07-10","citation":"Regulation (EU) 2024/1624 art.80(1)","summary":"Cash of EUR 10,000 or more may not be accepted.","status":"UPCOMING"}]}}');
    expect(find.text('Cash limits in France'), findsOneWidget);
    expect(find.byKey(const Key('cash-limit-FR-EUR')), findsOneWidget);
    // The amount as money and the day as a date, not `EUR 1000.0` and ISO.
    expect(find.text('€1,000.00 or more'), findsOneWidget);
    expect(find.text('€10,000.00 or more — from 10 Jul 2027'), findsOneWidget);
    // Whether each is in force is a state, in the shared badge, not a chip.
    expect(find.widgetWithText(StatusBadge, 'In force'), findsOneWidget);
    expect(find.widgetWithText(StatusBadge, 'Coming'), findsOneWidget);
    expect(find.byType(Chip), findsNothing);
    expect(find.textContaining('L112-6'), findsOneWidget);
  });

  testWidgets('an empty list says the platform tracks none, not that none apply',
      (tester) async {
    await _pump(tester, (t) => t.body = '{"data":{"country":"US","on":"2026-09-14","obligations":[]}}');
    expect(find.textContaining('No obligations are recorded for the United States.'),
        findsOneWidget);
    expect(find.textContaining('not that none apply'), findsOneWidget);
  });

  testWidgets('the Since / From dates are plain labels, not chips that read as filters',
      (tester) async {
    await _pump(tester, (t) => t.body = _gb);
    expect(find.byType(Chip), findsNothing);
    expect(find.byKey(const Key('obligation-date-UNIT_PRICING-GB')), findsOneWidget);
    expect(find.byKey(const Key('obligation-date-TOBACCO_BIRTH_COHORT-GB')), findsOneWidget);
  });

  testWidgets('wide, the date sits at the end of the row beside the obligation',
      (tester) async {
    await _pump(tester, (t) => t.body = _gb);
    final date = tester.getRect(find.text('Since 6 Apr 2026'));
    final text = tester.getRect(find.text('Unit prices are shown legibly.'));
    expect(date.left, greaterThan(text.right));
  });

  testWidgets('on a phone the date goes above the obligation, which keeps the whole row',
      (tester) async {
    const long = 'No tobacco product is sold to anyone born on or after 1 January '
        '2009, whatever age they claim, and the refusal is recorded.';
    await _pump(
        tester,
        (t) => t.body = '{"data":{"country":"GB","on":"2026-09-14","obligations":['
            '{"code":"TOBACCO_BIRTH_COHORT","scope":"GB","effectiveFrom":"2027-01-01",'
            '"citation":"Tobacco and Vapes Act 2026","summary":"$long","status":"UPCOMING"}]}}',
        size: const Size(390, 844));
    expect(tester.takeException(), isNull);
    final date = tester.getRect(find.text('From 1 Jan 2027'));
    final text = tester.getRect(find.text(long));
    final card = tester.getRect(find.byType(Card));
    // Above the text, on the same start edge — not a column at the end.
    expect(date.bottom, lessThanOrEqualTo(text.top));
    expect((date.left - text.left).abs(), lessThan(1));
    // The obligation gets the card's width less the row's own insets, not
    // the ~150px a trailing date chip once left it.
    expect(text.width, greaterThan(card.width - 64));
  });

  testWidgets('on a phone at 200% text the obligations and cash limits fit', (tester) async {
    await _pump(
        tester,
        (t) => t.body = '{"data":{"country":"FR","on":"2026-09-16","obligations":['
            '{"code":"UNIT_PRICING","scope":"FR","effectiveFrom":"2026-04-06",'
            '"citation":"Code de la consommation","summary":"Unit prices are shown legibly.","status":"IN_FORCE"}],'
            '"cashLimits":[{"scope":"FR","currency":"EUR","fromAmount":1000.00,"effectiveFrom":"2015-09-01",'
            '"citation":"CMF art. L112-6","summary":"A resident may not pay a business EUR 1,000 or more in cash.","status":"IN_FORCE"}]}}',
        size: const Size(390, 844),
        textScale: 2);
    expect(tester.takeException(), isNull);
    expect(find.text('Since 6 Apr 2026'), findsOneWidget);
  });

  testWidgets('a failed read says so, with a way to try again', (tester) async {
    await _pump(tester, (t) {
      t.status = 503;
      t.body = '{"error":{"code":"SERVICE_UNAVAILABLE","message":"down"}}';
    });
    expect(find.byType(ListTile), findsNothing);
    expect(find.textContaining('Retry', findRichText: true), findsWidgets);
  });
}
