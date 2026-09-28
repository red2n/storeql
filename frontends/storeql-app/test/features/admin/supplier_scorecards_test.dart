import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/supplier_scorecards.dart';

import '../../support/fake_api.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The Suppliers tab's scorecards: ranked by score with a grade each, the
// figures the score is made from on one line, a supplier with nothing to judge
// unscored; the detail opening with the period, the sections and the
// deliveries as measured; and the period asked for being the last ninety days.
// ---------------------------------------------------------------------------

const _butcher = '01a0b200-0000-7000-8000-0000000000s1';
const _idle = '01a0b200-0000-7000-8000-0000000000s2';
const _po = '01a0b200-0000-7000-8000-0000000000p1';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/suppliers/scorecards')) {
      return jsonResponse('{"data":['
          '{"supplierId":"$_butcher","supplierName":"Highland Meats","leadTimeDays":3,"from":"2026-06-26","to":"2026-09-24",'
          '"deliveries":{"count":2,"avgLeadDays":3.5,"medianLeadDays":3.5,"maxLeadDays":5,"promised":2,"onTime":1,"late":1,"onTimePct":50.0,"avgDaysLate":2.0,"receivedQty":16},'
          '"fill":{"orders":2,"orderedQty":20,"receivedQty":16,"fillRatePct":80.0,"shortClosed":1},'
          '"quality":{"returns":1,"returnedQty":2,"returnRatePct":12.5},'
          '"invoices":{"invoices":1,"flagged":0,"accuracyPct":100.0},"score":71.5,"grade":"C"},'
          '{"supplierId":"$_idle","supplierName":"Quiet Farm","from":"2026-06-26","to":"2026-09-24",'
          '"deliveries":{"count":0,"promised":0,"onTime":0,"late":0,"receivedQty":0},'
          '"fill":{"orders":0,"orderedQty":0,"receivedQty":0,"shortClosed":0},'
          '"quality":{"returns":0,"returnedQty":0},"invoices":{"invoices":0,"flagged":0}}'
          ']}');
    }
    if (path.endsWith('/suppliers/$_butcher/deliveries')) {
      return jsonResponse('{"data":['
          '{"id":"01a0b200-0000-7000-8000-0000000000d2","poId":"$_po","orderedAt":"2026-09-22T09:00:00Z","promisedDate":"2026-09-25","receivedAt":"2026-09-24T10:00:00Z","leadDays":2,"lateDays":-1,"complete":false,"receivedQty":6},'
          '{"id":"01a0b200-0000-7000-8000-0000000000d1","poId":"$_po","orderedAt":"2026-09-19T09:00:00Z","promisedDate":"2026-09-22","receivedAt":"2026-09-24T09:00:00Z","leadDays":5,"lateDays":2,"complete":true,"receivedQty":10}'
          ']}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1100, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('MANAGER')),
    ],
    child: const MaterialApp(home: Scaffold(body: SingleChildScrollView(child: SupplierScorecardsCard()))),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);
  testWidgets('suppliers are ranked with a grade and the figures the score is made from',
      (tester) async {
    await _pump(tester);
    expect(find.text('Highland Meats · 71.5'), findsOneWidget);
    expect(find.byKey(const Key('grade-C')), findsOneWidget);
    expect(
        find.textContaining(
            '2 deliveries · on time 50 % · lead 3.5 d against 3 quoted · fill 80 % · returns 12.5 %'),
        findsOneWidget);
    // The idle supplier is last, unscored and ungraded.
    expect(find.text('Quiet Farm'), findsOneWidget);
    expect(find.byKey(const Key('grade-none')), findsOneWidget);
    expect(find.text('No deliveries in the period'), findsOneWidget);
    final tiles = tester.widgetList<ListTile>(find.byType(ListTile)).toList();
    expect((tiles.first.key as ValueKey<String>).value, 'scorecard-$_butcher');
  });

  testWidgets('the detail opens with the sections and the deliveries as measured', (tester) async {
    await _pump(tester);
    await tester.tap(find.byKey(const Key('scorecard-$_butcher')));
    await tester.pumpAndSettle();
    expect(find.text('26 Jun 2026 to 24 Sept 2026'), findsOneWidget);
    expect(find.byKey(const Key('scorecard-score')), findsOneWidget);
    expect(find.text('1 of 2 (50 %)'), findsOneWidget);
    expect(find.text('3.5 d average, 3.5 d median, 5 d longest · quoted 3 d'), findsOneWidget);
    expect(find.text('1, by 2 d on average'), findsOneWidget);
    expect(find.text('2 (1 closed short)'), findsOneWidget);
    expect(find.text('16 of 20 (80 %)'), findsOneWidget);
    expect(find.text('2 in 1 returns (12.5 % of what arrived)'), findsOneWidget);
    expect(find.text('1 of 1 (100 %)'), findsOneWidget);
    expect(find.textContaining('5 days from order · 2 days late'), findsOneWidget);
    expect(find.textContaining('2 days from order · 1 days early'), findsOneWidget);
    expect(find.textContaining('(part)'), findsOneWidget);
  });

  testWidgets('a supplier with nothing to judge says so in its detail', (tester) async {
    await _pump(tester);
    await tester.tap(find.byKey(const Key('scorecard-$_idle')));
    await tester.pumpAndSettle();
    expect(find.text('Nothing to judge yet'), findsOneWidget);
    expect(find.text('Nothing promised'), findsOneWidget);
    expect(find.text('None in the period'), findsWidgets);
  });

  testWidgets('the period asked for is the last ninety days', (tester) async {
    final server = await _pump(tester);
    final req = server.requests.firstWhere((r) => r.path.endsWith('/suppliers/scorecards'));
    final from = DateTime.parse(req.queryParameters['from'] as String);
    final to = DateTime.parse(req.queryParameters['to'] as String);
    expect(to.difference(from).inDays, 90);
    expect(scorecardPeriod(DateTime(2026, 9, 24)), {'from': '2026-06-26', 'to': '2026-09-24'});
  });
}
