import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart' show Intl;
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/admin/dashboard_screen.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/providers/live_alerts_provider.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The back-office dashboard's Revenue and Orders cards.
//
// They cover a period of their own — the last 30 days, today included — and
// never the Reports screen's date range, so changing a report leaves the
// dashboard as it was. Revenue and Orders count the same currency, so a
// business selling in pounds and euros never reads a pound total beside a
// count of every sale. Counts are grouped like the amounts beside them.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  String salesRows = '[]';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/reports/sales/summary')) {
      return jsonResponse('{"data":{"rows":$salesRows}}');
    }
    if (path.endsWith('/admin/tenant')) {
      return jsonResponse(
          '{"data":{"id":"t","name":"Corner Stores","currency":"GBP"}}');
    }
    if (path.endsWith('/levels/summary')) {
      return jsonResponse('{"data":{"skuCount":1234,"lowStockCount":0}}');
    }
    return jsonResponse('{"data":[]}');
  }

  List<RequestOptions> get salesQueries => requests
      .where((o) => o.path.endsWith('/reports/sales/summary'))
      .toList();
}

Future<_Server> _pump(
  WidgetTester tester, {
  String salesRows = '[]',
  ReportDateRange? reportsRange,
}) async {
  tester.view.physicalSize = const Size(1200, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server()..salesRows = salesRows;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      // No live push in a test: a notifier with no tenant never connects.
      liveAlertsProvider
          .overrideWith((ref) => LiveAlertsNotifier(null, '', '', const [])),
      if (reportsRange != null)
        reportDateRangeProvider.overrideWith((ref) => reportsRange),
    ],
    child: MaterialApp(
      theme: AppTheme.light,
      home: const Scaffold(body: DashboardScreen()),
    ),
  ));
  await tester.pumpAndSettle();
  return server;
}

String _day(DateTime d) => '${d.year.toString().padLeft(4, '0')}-'
    '${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets(
      'Revenue and Orders keep the last 30 days, whatever range Reports was left on',
      (tester) async {
    final server = await _pump(
      tester,
      salesRows: '[{"currency":"GBP","orders":12,"gross":120,"refunded":0,"net":120}]',
      reportsRange: const ReportDateRange(from: '2026-01-01', to: '2026-01-31'),
    );
    final now = DateTime.now();
    final query = server.salesQueries.single.queryParameters;
    expect(query['from'], _day(DateTime(now.year, now.month, now.day - 29)));
    expect(query['to'], _day(now));
    expect(find.text('Last 30 days'), findsNWidgets(2));
    expect(find.textContaining('Jan 2026'), findsNothing);
  });

  testWidgets(
      'Orders counts the same currency as Revenue, grouped like the amount',
      (tester) async {
    await _pump(
      tester,
      salesRows: '[{"currency":"EUR","orders":40,"gross":1200,"refunded":0,"net":1200},'
          '{"currency":"GBP","orders":2917,"gross":39000,"refunded":587.10,"net":38412.90}]',
    );
    expect(find.text('£38,412.90'), findsOneWidget);
    expect(find.text('2,917'), findsOneWidget);
    expect(find.text('2,957'), findsNothing,
        reason: 'the euro sales are not in the pound revenue, so not in its count');
    // Which currency both cards cover is said, since there is more than one.
    expect(find.text('Last 30 days · GBP sales'), findsNWidgets(2));
    expect(find.text('1,234'), findsOneWidget, reason: 'SKUs grouped too');
    // Counted in the locale AppFormat writes in, named: a formatter made with
    // none would set Intl's default to en_US, and every date after it in the
    // app would read "Sep 11, 2026".
    expect(Intl.defaultLocale, isNull);
  });

  testWidgets('one currency needs no currency in the caption', (tester) async {
    await _pump(
      tester,
      salesRows: '[{"currency":"GBP","orders":2917,"gross":38412.90,"refunded":0,"net":38412.90}]',
    );
    expect(find.text('2,917'), findsOneWidget);
    expect(find.text('Last 30 days'), findsNWidgets(2));
  });
}
