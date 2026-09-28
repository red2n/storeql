import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/statutory_returns_screen.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The statutory calendar (07.14): what is outstanding, what each period says,
// and what recording a filing sends.
//
// The cases worth the test are the ones a reader could get wrong from the
// screen: an overdue return has to read as overdue rather than merely due; a
// return the platform cannot produce has to say so rather than look the same as
// one it can; and correcting a filed period has to name the filing it replaces,
// because a second filing without `supersedes` is refused by the service and
// the person would see an error they did not cause.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Tenant implements HttpClientAdapter {
  int status = 200;
  String body = '{"data":{"asOf":"2026-09-18","obligations":[],"outstanding":[]}}';
  final List<RequestOptions> calls = [];
  final List<Object?> bodies = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    calls.add(o);
    if (o.method == 'POST') {
      bodies.add(o.data);
      return ResponseBody.fromString(
          '{"data":{"returnCode":"SAFT_PT","name":"SAF-T (PT) sales invoices",'
          '"scopeKind":"COUNTRY","scope":"PT","frequency":"MONTHLY",'
          '"periodStart":"2026-08-01","periodEnd":"2026-09-01","dueOn":"2026-09-05",'
          '"state":"FILED","citation":"Portaria 302/2016"}}',
          200,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    }
    return ResponseBody.fromString(body, status,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Tenant> _pump(WidgetTester tester, void Function(_Tenant) setUp,
    {Size size = const Size(1200, 2200)}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final tenant = _Tenant();
  setUp(tenant);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = tenant;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: Scaffold(body: StatutoryReturnsScreen())),
  ));
  await tester.pumpAndSettle();
  return tenant;
}

/// One obligation as tenant-svc sends it. Built with jsonEncode rather than a string template: the
/// first version of this helper nested ternaries inside an interpolation and produced JSON that did
/// not parse, which showed up as three unrelated-looking widget failures.
String _period(String code, String start, String due, String state,
    {String? filingId, String? reference, String provider = 'MANUAL'}) {
  final saft = code == 'SAFT_PT';
  return jsonEncode({
    'returnCode': code,
    'name': saft ? 'SAF-T (PT) sales invoices' : 'Recapitulative statement',
    'scopeKind': saft ? 'COUNTRY' : 'REGIME',
    'scope': saft ? 'PT' : 'EU',
    'frequency': 'MONTHLY',
    'periodStart': start,
    'periodEnd': '2026-09-01',
    'dueOn': due,
    'state': state,
    'citation': saft ? 'Portaria 302/2016' : 'Directive 2006/112/EC art. 262',
    if (saft) 'exportService': 'order-svc',
    if (saft) 'exportPath': '/admin/fiscal-receipts/export?format=saft-pt',
    if (filingId != null)
      'filing': {
        'id': filingId,
        'returnCode': code,
        'periodStart': start,
        'provider': provider,
        'stands': true,
        'filedAt': '2026-09-04T10:00:00Z',
        'reference': reference,
      },
  });
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);

  testWidgets('what is outstanding comes first, and an overdue one is named as overdue',
      (tester) async {
    final tenant = await _pump(
        tester,
        (t) => t.body = '{"data":{"asOf":"2026-09-18","obligations":['
            '${_period('SAFT_PT', '2026-08-01', '2026-09-05', 'OVERDUE')},'
            '${_period('SAFT_PT', '2026-09-01', '2026-10-05', 'NOT_DUE')}'
            '],"outstanding":['
            '${_period('SAFT_PT', '2026-08-01', '2026-09-05', 'OVERDUE')}'
            ']}}');

    expect(tenant.calls.first.path, '/tenant-svc/admin/tenant/statutory-returns');
    expect(find.byKey(const Key('statutory-outstanding')), findsOneWidget);
    expect(find.text('1 overdue, 1 to file'), findsOneWidget);
    expect(find.text('August 2026'), findsOneWidget);
    // The period still running is on the calendar but is not asked for.
    expect(find.text('Not due yet'), findsWidgets);
    // 'Sep' and not 'Sep 2026': intl abbreviates September as "Sept", and an assertion on the whole
    // formatted date passes eleven months of the year and fails in one.
    expect(find.textContaining('Was due 5 Sep'), findsWidgets);
  });

  testWidgets('a filed period shows its receipt, and nothing is outstanding',
      (tester) async {
    await _pump(
        tester,
        (t) => t.body = '{"data":{"asOf":"2026-09-18","obligations":['
            '${_period('SAFT_PT', '2026-08-01', '2026-09-05', 'FILED', filingId: '01a0-1', reference: 'AT-778812')}'
            '],"outstanding":[]}}');

    expect(find.byKey(const Key('statutory-nothing-outstanding')), findsOneWidget);
    expect(find.textContaining('AT-778812'), findsOneWidget);
    expect(find.text('Filed'), findsWidgets);
    // How it went, in the record dialog's own words.
    expect(find.textContaining('By hand, on the portal'), findsOneWidget);
    expect(find.textContaining('MANUAL'), findsNothing);
  });

  testWidgets('a filing names how it went in words, never the provider code',
      (tester) async {
    await _pump(
        tester,
        (t) => t.body = '{"data":{"asOf":"2026-09-18","obligations":['
            '${_period('SAFT_PT', '2026-08-01', '2026-09-05', 'FILED', filingId: '01a0-1', provider: 'HMRC_MTD')}'
            '],"outstanding":[]}}');

    expect(find.textContaining('HMRC Making Tax Digital'), findsOneWidget);
    expect(find.textContaining('HMRC_MTD'), findsNothing);
  });

  testWidgets('an overdue return keeps a strong fill inside the red outstanding card',
      (tester) async {
    await _pump(
        tester,
        (t) => t.body = '{"data":{"asOf":"2026-09-18","obligations":['
            '${_period('SAFT_PT', '2026-08-01', '2026-09-05', 'OVERDUE')}'
            '],"outstanding":['
            '${_period('SAFT_PT', '2026-08-01', '2026-09-05', 'OVERDUE')}'
            ']}}');

    final badge = find.descendant(
        of: find.byKey(const Key('statutory-outstanding')),
        matching: find.byKey(const Key('statutory-state-OVERDUE')));
    expect(badge, findsOneWidget);
    final scheme = Theme.of(tester.element(badge)).colorScheme;
    final card = tester.widget<Card>(find.byKey(const Key('statutory-outstanding')));
    final fill = (tester.widget<Container>(badge).decoration as BoxDecoration).color;
    // errorContainer on errorContainer loses its edge and reads as plain text.
    expect(fill, scheme.error);
    expect(fill, isNot(card.color));
    expect(find.descendant(of: badge, matching: find.byIcon(Icons.error_outline)),
        findsOneWidget);
  });

  testWidgets('the VAT return\'s export is a button to the screen that makes it',
      (tester) async {
    final tenant = _Tenant()
      ..body = '{"data":{"asOf":"2026-09-18","obligations":[${jsonEncode({
        'returnCode': 'VAT_RETURN_UK',
        'name': 'VAT return (Making Tax Digital)',
        'scopeKind': 'COUNTRY',
        'scope': 'GB',
        'frequency': 'QUARTERLY',
        'periodStart': '2026-04-01',
        'periodEnd': '2026-07-01',
        'dueOn': '2026-08-07',
        'state': 'DUE',
        'citation': 'VATA 1994 sch.11 para.2',
        'exportService': 'pricing-svc',
        'exportPath': '/admin/vat-return',
      })}],"outstanding":[]}}';
    final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = tenant;
    tester.view.physicalSize = const Size(1200, 2200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final router = GoRouter(
      initialLocation: '/admin/statutory-returns',
      routes: [
        GoRoute(
            path: '/admin/statutory-returns',
            builder: (_, _) => const Scaffold(body: StatutoryReturnsScreen())),
        GoRoute(
            path: '/admin/pricing',
            builder: (_, state) => Scaffold(
                body: Text('Pricing screen · ${state.uri.queryParameters['tab']}'))),
      ],
    );
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: <Override>[apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
      child: MaterialApp.router(routerConfig: router),
    ));
    await tester.pumpAndSettle();

    expect(find.textContaining('pricing-svc'), findsNothing);
    expect(find.textContaining('/admin/vat-return'), findsNothing);
    await tester.tap(find.text('Export from Pricing › VAT Return'));
    await tester.pumpAndSettle();
    // Straight to the VAT Return tab, not Pricing's first.
    expect(find.text('Pricing screen · vat-return'), findsOneWidget);
  });

  testWidgets('on a phone at 200% text an overdue period still lays out',
      (tester) async {
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await _pump(
        tester,
        (t) => t.body = '{"data":{"asOf":"2026-09-18","obligations":['
            '${_period('SAFT_PT', '2026-08-01', '2026-09-05', 'OVERDUE')},'
            '${_period('SAFT_PT', '2026-07-01', '2026-08-05', 'FILED', filingId: '01a0-2', provider: 'SIMULATED', reference: 'AT-1')}'
            '],"outstanding":['
            '${_period('SAFT_PT', '2026-08-01', '2026-09-05', 'OVERDUE')}'
            ']}}',
        size: const Size(390, 844));
    expect(tester.takeException(), isNull);
    // Scroll the whole calendar through, so every row is laid out once.
    await tester.scrollUntilVisible(
        find.byKey(const Key('statutory-period-SAFT_PT-2026-07-01')), 300,
        scrollable: find
            .descendant(of: find.byType(ListView), matching: find.byType(Scrollable))
            .first);
    expect(tester.takeException(), isNull);
    expect(find.textContaining('Simulated — nothing left the building'), findsOneWidget);
  });

  testWidgets('on a phone the page is inset 16, not 24', (tester) async {
    await _pump(
        tester,
        (t) => t.body = '{"data":{"asOf":"2026-09-18","obligations":['
            '${_period('SAFT_PT', '2026-08-01', '2026-09-05', 'DUE')}'
            '],"outstanding":[]}}',
        size: const Size(390, 844));

    expect(tester.takeException(), isNull);
    expect(tester.getTopLeft(find.text('Statutory returns')).dx, 16);
    expect(tester.getTopLeft(find.byKey(const Key('statutory-nothing-outstanding'))).dx,
        16);
  });

  testWidgets('a return the platform cannot produce says so, rather than looking like one it can',
      (tester) async {
    await _pump(
        tester,
        (t) => t.body = '{"data":{"asOf":"2026-09-18","obligations":['
            '${_period('SAFT_PT', '2026-08-01', '2026-09-05', 'DUE')},'
            '${_period('EC_SALES_LIST', '2026-08-01', '2026-09-20', 'DUE')}'
            '],"outstanding":[]}}');

    // Where the export is made, as a place in the app — not a service and a path.
    expect(find.text('Export from Sales tools › Receipts'), findsOneWidget);
    expect(find.textContaining('order-svc'), findsNothing);
    expect(find.textContaining('The platform cannot produce this one'), findsOneWidget);
    expect(find.text('EU law'), findsOneWidget);
    expect(find.text('National law'), findsOneWidget);
  });

  testWidgets('recording a filing sends the period and how it went, and nothing else',
      (tester) async {
    final tenant = await _pump(
        tester,
        (t) => t.body = '{"data":{"asOf":"2026-09-18","obligations":['
            '${_period('SAFT_PT', '2026-08-01', '2026-09-05', 'DUE')}'
            '],"outstanding":[]}}');

    await tester.tap(find.byKey(const Key('statutory-file-SAFT_PT-2026-08-01')));
    await tester.pumpAndSettle();
    expect(find.text('Record a filing'), findsOneWidget);
    expect(find.textContaining('Nothing here is sent to an authority'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('statutory-reference')), 'AT-99001');
    await tester.tap(find.byKey(const Key('statutory-record-save')));
    await tester.pumpAndSettle();

    final sent = tenant.bodies.single as Map<String, dynamic>;
    // Not calls.last: a successful save invalidates the calendar, so the last call is the refetch.
    expect(tenant.calls.where((c) => c.method == 'POST').single.path,
        '/tenant-svc/admin/tenant/statutory-returns/SAFT_PT/filings');
    expect(sent['periodStart'], '2026-08-01');
    expect(sent['provider'], 'MANUAL');
    expect(sent['reference'], 'AT-99001');
    // Not a correction, so it names no predecessor — and the empty fields are
    // left out rather than sent as blanks the service would have to strip.
    expect(sent.containsKey('supersedes'), isFalse);
    expect(sent.containsKey('payloadDigest'), isFalse);
    expect(sent.containsKey('note'), isFalse);
  });

  testWidgets('correcting a filed period names the filing it replaces',
      (tester) async {
    final tenant = await _pump(
        tester,
        (t) => t.body = '{"data":{"asOf":"2026-09-18","obligations":['
            '${_period('SAFT_PT', '2026-08-01', '2026-09-05', 'FILED', filingId: '01a0-7', reference: 'AT-1')}'
            '],"outstanding":[]}}');

    await tester.tap(find.byKey(const Key('statutory-file-SAFT_PT-2026-08-01')));
    await tester.pumpAndSettle();
    expect(find.text('Correct a filing'), findsOneWidget);
    expect(find.textContaining('Both stay on the record'), findsOneWidget);

    await tester.tap(find.byKey(const Key('statutory-record-save')));
    await tester.pumpAndSettle();

    final sent = tenant.bodies.single as Map<String, dynamic>;
    expect(sent['supersedes'], '01a0-7',
        reason: 'a second filing without this is refused, and the person did '
            'nothing wrong to deserve that error');
  });

  testWidgets('a refusal is shown in the dialog, and the filing is not claimed',
      (tester) async {
    final tenant = _Tenant();
    tenant.body = '{"data":{"asOf":"2026-09-18","obligations":['
        '${_period('SAFT_PT', '2026-08-01', '2026-09-05', 'DUE')}'
        '],"outstanding":[]}}';
    final dio = Dio(BaseOptions(baseUrl: 'http://test'))
      ..httpClientAdapter = tenant
      ..interceptors.add(InterceptorsWrapper(onRequest: (o, h) {
        if (o.method == 'POST') {
          h.reject(DioException(
            requestOptions: o,
            response: Response(
              requestOptions: o,
              statusCode: 409,
              data: {
                'code': 'STATUTORY_FILING_EXISTS',
                'detail': 'This period has already been filed'
              },
            ),
          ));
          return;
        }
        h.next(o);
      }));
    tester.view.physicalSize = const Size(1200, 2200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(ProviderScope(
      overrides: <Override>[apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
      child: const MaterialApp(home: Scaffold(body: StatutoryReturnsScreen())),
    ));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('statutory-file-SAFT_PT-2026-08-01')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('statutory-record-save')));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('statutory-record-filing')), findsOneWidget,
        reason: 'the dialog stays open so the entry is not lost');
    expect(find.textContaining('already been filed'), findsOneWidget);
  });

  testWidgets('a country with nothing tracked is told that, not shown an empty page',
      (tester) async {
    await _pump(tester, (t) {});
    expect(find.textContaining('No statutory returns are tracked'), findsOneWidget);
    expect(find.textContaining('not that none apply'), findsOneWidget);
  });

  testWidgets('a failure offers a retry rather than an empty calendar',
      (tester) async {
    await _pump(tester, (t) {
      t.status = 500;
      t.body = '{"error":"boom"}';
    });
    expect(find.text('Retry'), findsOneWidget);
  });
}
