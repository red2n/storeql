import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/deposits_report_screen.dart';

import '../../support/mid_word.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// Container deposits (09.16): what was charged, what was paid back, what the
// scheme holds, by material — and what the screen says with nothing or an error.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Orders implements HttpClientAdapter {
  int status = 200;
  String body = '{"data":{"from":"2026-09-01T00:00:00Z","to":"2026-10-01T00:00:00Z","currency":"EUR",'
      '"chargedContainers":0,"chargedAmount":0,"chargedVat":0,"refundedContainers":0,'
      '"refundedAmount":0,"unredeemedAmount":0,"byMaterial":[]}}';
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

/// September 2026, whatever month the test runs in.
class _September extends DepositPeriodNotifier {
  @override
  DepositPeriod build() => DepositPeriod(DateTime.utc(2026, 9, 1), DateTime.utc(2026, 10, 1));
}

Future<_Orders> _pump(WidgetTester tester, void Function(_Orders) setUp,
    {Size size = const Size(1100, 1400)}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final orders = _Orders();
  setUp(orders);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = orders;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      depositPeriodProvider.overrideWith(_September.new),
    ],
    child: const MaterialApp(home: Scaffold(body: DepositsReportScreen())),
  ));
  await tester.pumpAndSettle();
  return orders;
}

const _germany = '{"data":{"from":"2026-09-01T00:00:00Z","to":"2026-10-01T00:00:00Z","currency":"EUR",'
    '"chargedContainers":120,"chargedAmount":30.00,"chargedVat":4.79,"refundedContainers":80,'
    '"refundedAmount":20.00,"unredeemedAmount":10.00,"byMaterial":['
    '{"material":"PET","chargedContainers":100,"chargedAmount":25.00,"chargedVat":3.99,"refundedContainers":70,"refundedAmount":17.50},'
    '{"material":"ALUMINIUM","chargedContainers":20,"chargedAmount":5.00,"chargedVat":0.80,"refundedContainers":10,"refundedAmount":2.50}]}}';

const _uk = '{"data":{"from":"2026-09-01T00:00:00Z","to":"2026-10-01T00:00:00Z","currency":"GBP",'
    '"chargedContainers":2598,"chargedAmount":519.60,"chargedVat":0,"refundedContainers":1802,'
    '"refundedAmount":360.40,"unredeemedAmount":159.20,"byMaterial":['
    '{"material":"PET","chargedContainers":1388,"chargedAmount":277.60,"chargedVat":0,"refundedContainers":1051,"refundedAmount":210.20},'
    '{"material":"GLASS","chargedContainers":1210,"chargedAmount":242.00,"chargedVat":0,"refundedContainers":751,"refundedAmount":150.20}]}}';

Finder _inRow(String material, String text) =>
    find.descendant(of: find.byKey(Key('deposits-material-$material')), matching: find.text(text));

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  // The period is written with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);

  testWidgets('charged, refunded and unredeemed, with the VAT inside where the scheme taxes it',
      (tester) async {
    final orders = await _pump(tester, (o) => o.body = _germany);

    expect(orders.last!.path, '/order-svc/admin/reports/deposits');
    expect(orders.last!.queryParameters['from'], isNotNull);
    expect(orders.last!.queryParameters['to'], isNotNull);
    expect(find.byKey(const Key('deposits-charged')), findsOneWidget);
    expect(find.text('€30.00'), findsOneWidget);
    expect(find.text('120 containers'), findsOneWidget);
    expect(find.text('€20.00'), findsOneWidget);
    expect(find.text('€10.00'), findsOneWidget);
    expect(find.byKey(const Key('deposits-vat')), findsOneWidget);
    expect(find.text('€4.79'), findsOneWidget);
    expect(find.byKey(const Key('deposits-material-PET')), findsOneWidget);
    expect(find.text('PET plastic'), findsOneWidget);
    expect(find.text('100 sold · 70 returned'), findsOneWidget);
    expect(find.byKey(const Key('deposits-material-ALUMINIUM')), findsOneWidget);
    expect(find.textContaining('EUR'), findsNothing, reason: 'money in its own form, not the code and a number');
  });

  testWidgets('money is written as money, counts are grouped, and a material says what it leaves unredeemed',
      (tester) async {
    await _pump(tester, (o) => o.body = _uk);

    expect(find.text('£519.60'), findsOneWidget);
    expect(find.text('2,598 containers'), findsOneWidget);
    expect(find.text('1,802 containers'), findsOneWidget);
    expect(find.text('£159.20'), findsOneWidget);
    expect(find.text('1,388 sold · 1,051 returned'), findsOneWidget);
    // Not a sum left to do (£277.60 − £210.20) but its answer.
    expect(find.textContaining('−'), findsNothing);
    expect(_inRow('PET', '£67.40'), findsOneWidget);
    expect(_inRow('PET', 'unredeemed'), findsOneWidget);
    expect(_inRow('PET', 'Charged £277.60 · refunded £210.20'), findsOneWidget);
    expect(_inRow('GLASS', '£91.80'), findsOneWidget);
  });

  testWidgets('the period is written as dates a person reads', (tester) async {
    await _pump(tester, (o) => o.body = _uk);
    expect(find.descendant(of: find.byKey(const Key('deposits-period')), matching: find.text('1 Sept 2026 to 30 Sept 2026')),
        findsOneWidget);
    expect(find.textContaining('2026-09'), findsNothing);
  });

  testWidgets('on a phone the figures are two to a row and the page is inset 16', (tester) async {
    await _pump(tester, (o) => o.body = _germany, size: const Size(390, 1400));

    final charged = tester.getRect(find.byKey(const Key('deposits-charged')));
    final refunded = tester.getRect(find.byKey(const Key('deposits-refunded')));
    final unredeemed = tester.getRect(find.byKey(const Key('deposits-unredeemed')));
    final vat = tester.getRect(find.byKey(const Key('deposits-vat')));
    expect(refunded.top, charged.top, reason: 'charged and refunded share a row');
    expect(vat.top, unredeemed.top, reason: 'and so do unredeemed and the VAT');
    expect(unredeemed.top, greaterThan(charged.bottom - 1));
    expect(charged.left, 16, reason: 'the page gutter on a phone');
    expect(refunded.right, closeTo(390 - 16, 1), reason: 'the row is filled, not a third of it left empty');
    expect(charged.width, closeTo(refunded.width, 1));
  });

  testWidgets('on a wide screen the figures share one row and stretch across it', (tester) async {
    await _pump(tester, (o) => o.body = _germany, size: const Size(1100, 1400));

    final charged = tester.getRect(find.byKey(const Key('deposits-charged')));
    final vat = tester.getRect(find.byKey(const Key('deposits-vat')));
    for (final k in ['deposits-refunded', 'deposits-unredeemed', 'deposits-vat']) {
      expect(tester.getRect(find.byKey(Key(k))).top, charged.top, reason: '$k is on the first row');
    }
    expect(charged.left, 24);
    expect(vat.right, closeTo(1100 - 24, 1));
  });

  testWidgets('at 200% text on a phone nothing overflows', (tester) async {
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await _pump(tester, (o) => o.body = _uk, size: const Size(390, 844));
    expect(tester.takeException(), isNull);
    // A sum reads whole: never '£519.6' on one line and '0' on the next.
    await tester.scrollUntilVisible(find.text('£519.60'), 200);
    await tester.pumpAndSettle();
    expect(breaksMidWord(tester, find.text('£519.60')), isFalse);
    await tester.scrollUntilVisible(find.byKey(const Key('deposits-material-GLASS')), 200);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('a period with nothing says so, and no VAT figure where none was inside',
      (tester) async {
    await _pump(tester, (_) {});
    expect(find.text('No deposit was charged or refunded in this period.'), findsOneWidget);
    expect(find.byKey(const Key('deposits-vat')), findsNothing);
    expect(find.byKey(const Key('deposits-period')), findsOneWidget);
  });

  testWidgets('a failed load is said, with a retry', (tester) async {
    await _pump(tester, (o) {
      o.status = 500;
      o.body = '{}';
    });
    expect(find.text('Could not load the deposit report.'), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
  });
}
