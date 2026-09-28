import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/reports_screen.dart';

// ---------------------------------------------------------------------------
// The four reports horizon 1 built server-side and never gave a client. Two
// things are worth pinning hard.
//
// First, the date format. The pickers speak yyyy-MM-dd; these four endpoints
// parse from/to with Instant.parse, which rejects a bare date — SJ-D9 from the
// other side of the wire. Sending the picker's value straight through would 400
// every time, and nothing in the widget tree would reveal why.
//
// Second, the Box 1 warning. The server's own DTO says a VAT/Box-1 mismatch is
// "a data fault the Box 1 query drops silently", and this is the screen someone
// files a return from.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

/// Records the query parameters of every request and replies with canned bodies.
class _RecordingAdapter implements HttpClientAdapter {
  final List<({String path, Map<String, dynamic> query})> calls = [];
  final Map<String, String> bodyFor = {};
  final Map<String, int> statusFor = {};
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions options, Stream<List<int>>? stream, Future<void>? cancel) async {
    calls.add((path: options.path, query: Map.of(options.queryParameters)));
    requests.add(options);
    // The longest fragment wins, so '/deferred-revenue/settings' is not answered as '/deferred-revenue'.
    final keys = bodyFor.keys.where(options.path.contains).toList()
      ..sort((a, b) => b.length.compareTo(a.length));
    final body = keys.isEmpty ? '{"data":[]}' : bodyFor[keys.first]!;
    final status = keys.isEmpty ? 200 : (statusFor[keys.first] ?? 200);
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }

  ({String path, Map<String, dynamic> query}) callTo(String fragment) =>
      calls.firstWhere((c) => c.path.contains(fragment));
}

({ProviderContainer container, _RecordingAdapter adapter}) _harness(
    {Map<String, String> bodies = const {}}) {
  final adapter = _RecordingAdapter()..bodyFor.addAll(bodies);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter;
  final container = ProviderContainer(overrides: [
    apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
  ]);
  addTearDown(container.dispose);
  container.read(reportDateRangeProvider.notifier).state =
      const ReportDateRange(from: '2026-08-01', to: '2026-08-31');
  return (container: container, adapter: adapter);
}

void main() {
  // Report periods are dated with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);
  group('date handling — the endpoints parse instants, not dates', () {
    test('shrinkage widens the picker range to instants', () async {
      final h = _harness();
      await h.container.read(shrinkageReportProvider.future);

      final call = h.adapter.callTo('/reports/shrinkage');
      expect(call.query['from'], '2026-08-01T00:00:00Z');
      expect(call.query['to'], '2026-09-01T00:00:00Z');
    });

    test('tax summary does the same', () async {
      final h = _harness(bodies: {
        'tax-summary': '{"data":{"rows":[],"totals":{},"periodFrom":null,"periodTo":null}}'
      });
      await h.container.read(taxSummaryReportProvider.future);

      final call = h.adapter.callTo('/tax-summary');
      expect(call.query['from'], '2026-08-01T00:00:00Z');
      expect(call.query['to'], '2026-09-01T00:00:00Z');
    });

    test('`to` covers the whole final day, as a half-open bound', () async {
      // Two ways to get this wrong, and this asserts we are between them.
      //
      // T00:00:00Z of the SAME day would exclude everything that happened on the
      // last day of the range — the most recent day, silently missing.
      //
      // T23:59:59Z of the same day, which this used to send, is a whole second
      // short: every report compares with `<`, so anything logged in that last
      // second was dropped. It also disagreed with the exception report, which
      // compared inclusively, so one screen could show a numerator and a
      // denominator measured over different windows.
      //
      // The start of the NEXT day makes the window truly half-open: nothing lost,
      // nothing counted twice.
      final h = _harness();
      await h.container.read(shrinkageReportProvider.future);
      expect(h.adapter.callTo('/reports/shrinkage').query['to'],
          endsWith('T00:00:00Z'));
      expect(h.adapter.callTo('/reports/shrinkage').query['to'],
          startsWith('2026-09-01'));
    });

    test('an empty range sends no date params rather than a malformed one',
        () async {
      final h = _harness();
      h.container.read(reportDateRangeProvider.notifier).state =
          const ReportDateRange(from: null, to: null);
      await h.container.read(shrinkageReportProvider.future);

      final call = h.adapter.callTo('/reports/shrinkage');
      expect(call.query.containsKey('from'), isFalse);
      expect(call.query.containsKey('to'), isFalse);
      expect(call.query['groupBy'], 'REASON');
    });
  });

  group('grouping', () {
    test('changing the grouping re-requests with it', () async {
      final h = _harness();
      await h.container.read(shrinkageReportProvider.future);
      h.container.read(shrinkageGroupingProvider.notifier).state = 'ACTOR';
      await h.container.read(shrinkageReportProvider.future);

      expect(h.adapter.calls.last.query['groupBy'], 'ACTOR');
    });

    test('valuation defaults to STORE — the site-level question comes first',
        () async {
      final h = _harness();
      await h.container.read(valuationReportProvider.future);
      expect(h.adapter.callTo('/reports/valuation').query['groupBy'], 'STORE');
    });
  });

  group('parsing', () {
    test('a shrinkage row keeps write-offs and finds apart', () async {
      final h = _harness(bodies: {
        'shrinkage': '{"data":[{"groupKey":"THEFT","qtyWrittenOff":100,'
            '"qtyFound":100,"netQty":0,"movements":7}]}'
      });
      final rows = await h.container.read(shrinkageReportProvider.future);

      // A store that wrote off 100 and found 100 others is not a store that did
      // nothing, and a net figure alone would say it was.
      expect(rows.single.qtyWrittenOff, 100);
      expect(rows.single.qtyFound, 100);
      expect(rows.single.netQty, 0);
      expect(rows.single.movements, 7);
    });

    test('a low-stock row keeps the signal that bound it', () async {
      final h = _harness(bodies: {
        'low-stock': '{"data":[{"storeId":"s-1","variantId":"v-1",'
            '"signal":"SAFETY_STOCK","reorderLevel":20,"availableQty":5,"shortfall":15}]}'
      });
      final rows = await h.container.read(lowStockReportProvider.future);
      expect(rows.single.signal, 'SAFETY_STOCK');
      expect(rows.single.shortfall, 15);
    });

    test('missing fields degrade to zero rather than throwing', () async {
      final h = _harness(bodies: {'valuation': '{"data":[{"groupKey":"s-1"}]}'});
      final rows = await h.container.read(valuationReportProvider.future);
      expect(rows.single.groupKey, 's-1');
      expect(rows.single.value, 0);
      expect(rows.single.unvaluedQty, 0);
    });
  });

  group('tax summary — Box 1 reconciliation', () {
    TaxSummaryReport report({required double vat, required double box1}) =>
        TaxSummaryReport.fromJson({
          'rows': const [],
          'totals': {
            'netAmount': 1000.0,
            'vatAmount': vat,
            'outputVat': box1,
            'grossAmount': 1000.0 + vat,
            'transactions': 3,
          },
        });

    test('agreeing totals raise nothing', () {
      expect(report(vat: 200, box1: 200).boxOneDisagrees, isFalse);
    });

    test('an exempt line carrying VAT is flagged', () {
      expect(report(vat: 200, box1: 180).boxOneDisagrees, isTrue);
    });

    test('a rounding-sized difference is not treated as a fault', () {
      expect(report(vat: 200.001, box1: 200).boxOneDisagrees, isFalse);
    });
  });

  group('staff exception report', () {
    test('a rate needs a denominator, and says so when it has none', () {
      const noDenominator = ExceptionRow(
          groupKey: 'a-1',
          discounts: 3,
          discountAmount: 40,
          voids: 1,
          noSales: 2,
          sales: 0,
          salesValue: 0);
      // Null, not zero: zero would read as "impeccably behaved", which is the
      // opposite of what an absent denominator means.
      expect(noDenominator.ratePerHundredSales, isNull);
      expect(noDenominator.totalExceptions, 6);

      const withSales = ExceptionRow(
          groupKey: 'a-1',
          discounts: 3,
          discountAmount: 40,
          voids: 1,
          noSales: 2,
          sales: 200,
          salesValue: 5000);
      expect(withSales.ratePerHundredSales, 3.0);
    });

    test('the report reads coverage from the server, not from the rows', () async {
      final h = _harness(bodies: {
        'exceptions': '{"data":{"rows":[{"groupKey":"a-1","discounts":2,'
            '"discountAmount":15.5,"voids":0,"noSales":1,"sales":0,"salesValue":0}],'
            '"journalCoverage":false}}'
      });
      final report = await h.container.read(exceptionReportProvider.future);

      expect(report.journalCoverage, isFalse);
      expect(report.rows.single.discounts, 2);
      expect(report.rows.single.ratePerHundredSales, isNull);
    });

    test('it widens the picker dates to instants like the others', () async {
      final h = _harness(bodies: {
        'exceptions': '{"data":{"rows":[],"journalCoverage":false}}'
      });
      await h.container.read(exceptionReportProvider.future);

      final call = h.adapter.callTo('/reports/exceptions');
      expect(call.query['from'], '2026-08-01T00:00:00Z');
      expect(call.query['to'], '2026-09-01T00:00:00Z');
      expect(call.query['groupBy'], 'ACTOR');
    });
  });

  group('the screen', () {
    Future<void> pump(WidgetTester tester, _RecordingAdapter adapter) async {
      // Tall enough that the whole sidebar is built: a report below the fold
      // of a lazy list is not "unreachable", only unscrolled.
      tester.view.physicalSize = const Size(1400, 1300);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final dio = Dio(BaseOptions(baseUrl: 'http://test'))
        ..httpClientAdapter = adapter;
      await tester.pumpWidget(ProviderScope(
        overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
        child: const MaterialApp(home: Scaffold(body: ReportsScreen())),
      ));
      await tester.pumpAndSettle();
    }

    testWidgets('all four now have a way in', (tester) async {
      await pump(tester, _RecordingAdapter());
      for (final label in ['Low Stock', 'Stock Valuation', 'Shrinkage', 'Tax Summary']) {
        expect(find.text(label), findsWidgets, reason: '$label is not reachable');
      }
    });

    testWidgets('an empty report says so instead of showing a bare table',
        (tester) async {
      await pump(tester, _RecordingAdapter());
      await tester.tap(find.text('Low Stock').last);
      await tester.pumpAndSettle();
      expect(find.text('Nothing is below its reorder level.'), findsOneWidget);
    });

    testWidgets('a Box 1 mismatch warns before the return is filed',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['tax-summary'] = '{"data":{"rows":[{"groupKey":"ZERO",'
            '"exempt":true,"netAmount":100,"vatAmount":20,"grossAmount":120,'
            '"transactions":1}],"totals":{"netAmount":100,"vatAmount":20,'
            '"outputVat":0,"grossAmount":120,"transactions":1}}}';
      await pump(tester, adapter);
      await tester.tap(find.text('Tax Summary').last);
      await tester.pumpAndSettle();

      expect(find.textContaining('does not match total VAT'), findsOneWidget);
      expect(find.textContaining('Check the rows below before filing'),
          findsOneWidget);
    });

    testWidgets('uncosted stock is called out, not folded into the value',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['valuation'] = '{"data":[{"groupKey":"s-1","method":"FIFO",'
            '"onHandQty":100,"unvaluedQty":40,"value":600}]}';
      await pump(tester, adapter);
      await tester.tap(find.text('Stock Valuation').last);
      await tester.pumpAndSettle();

      expect(find.textContaining('40 units carry no cost'), findsOneWidget);
      expect(find.textContaining('not counted as zero'), findsOneWidget);
    });

    testWidgets('an exception report with no denominator warns before it is read',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['exceptions'] = '{"data":{"rows":[{"groupKey":"a-1","discounts":2,'
            '"discountAmount":15.5,"voids":1,"noSales":3,"sales":0,"salesValue":0}],'
            '"journalCoverage":false}}';
      await pump(tester, adapter);
      await tester.tap(find.text('Staff Exceptions').last);
      await tester.pumpAndSettle();

      expect(find.textContaining('No sales were journalled'), findsOneWidget);
      expect(find.text('—'), findsOneWidget, reason: 'no rate without a denominator');
    });

    testWidgets('an unattributed row is labelled, not hidden', (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['exceptions'] = '{"data":{"rows":[{"groupKey":"UNATTRIBUTED",'
            '"discounts":0,"discountAmount":0,"voids":4,"noSales":0,"sales":50,'
            '"salesValue":900}],"journalCoverage":true}}';
      await pump(tester, adapter);
      await tester.tap(find.text('Staff Exceptions').last);
      await tester.pumpAndSettle();

      expect(find.text('Unattributed'), findsOneWidget);
      expect(find.textContaining('No sales were journalled'), findsNothing);
      expect(find.text('8.0'), findsOneWidget, reason: '4 exceptions per 50 sales');
    });

    testWidgets('sales by category names each category from the catalogue and shows what it cannot place',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['by-category'] = '{"data":{"level":"leaf","rows":['
            '{"categoryId":"c-1","currency":"GBP","orders":2,"units":5.000,"gross":10.00,"share":47.62},'
            '{"currency":"GBP","orders":1,"units":1,"gross":11.00,"share":52.38}]}}'
        ..bodyFor['categories'] =
            '{"data":[{"id":"c-1","name":"Soft drinks","status":"ACTIVE","createdAt":"2026-01-01T00:00:00Z"}]}';
      await pump(tester, adapter);
      await tester.tap(find.text('Sales by Category').last);
      await tester.pumpAndSettle();
      expect(find.text('Soft drinks'), findsOneWidget);
      expect(find.text('Uncategorised'), findsOneWidget);
      expect(find.text('47.62'), findsOneWidget);
      expect(find.textContaining('has not announced yet'), findsOneWidget);
      expect(find.text('Export CSV'), findsOneWidget);
      expect(adapter.callTo('by-category').query['level'], 'leaf');
      await tester.tap(find.text('Top level'));
      await tester.pumpAndSettle();
      expect(
          adapter.calls.where((c) => c.path.contains('by-category')).last.query['level'], 'top');
    });

    testWidgets('sales by category with no lines says so', (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['by-category'] = '{"data":{"level":"leaf","rows":[]}}';
      await pump(tester, adapter);
      await tester.tap(find.text('Sales by Category').last);
      await tester.pumpAndSettle();
      expect(find.text('No sale lines in this range.'), findsOneWidget);
    });

    testWidgets('a report with rows offers the CSV export', (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['low-stock'] = '{"data":[{"storeId":"s-1","variantId":"v-1",'
            '"signal":"THRESHOLD","reorderLevel":10,"availableQty":2,"shortfall":8}]}';
      await pump(tester, adapter);
      await tester.tap(find.text('Low Stock').last);
      await tester.pumpAndSettle();
      expect(find.text('Export CSV'), findsOneWidget);
    });

    testWidgets('the trial balance is reachable, with its journal button', (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['trial-balance'] = '{"data":{"rows":[{"nominalCode":"1001",'
            '"nominalName":"Stock","debit":150,"credit":0,"balance":150},'
            '{"nominalCode":"2109","nominalName":"Goods Received Not Invoiced",'
            '"debit":0,"credit":150,"balance":-150}],"totalDebit":150,'
            '"totalCredit":150,"balanced":true}}';
      await pump(tester, adapter);
      // The sidebar scrolls and the sixteenth entry starts below the fold.
      await tester.ensureVisible(find.text('Trial Balance', skipOffstage: false).last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Trial Balance').last);
      await tester.pumpAndSettle();
      expect(find.text('Goods Received Not Invoiced'), findsOneWidget);
      expect(find.text('-150.00'), findsOneWidget);
      expect(find.text('Export CSV'), findsOneWidget);
      expect(find.byKey(const Key('post-journal')), findsOneWidget);
      expect(find.textContaining('does not balance'), findsNothing);
      // The picker's dates go through as they are: this endpoint takes days.
      final call = adapter.callTo('trial-balance');
      expect(call.query['from'], matches(RegExp(r'^\d{4}-\d{2}-\d{2}$')));
      expect(call.query['to'], matches(RegExp(r'^\d{4}-\d{2}-\d{2}$')));
    });

    testWidgets('a ledger that does not balance warns instead of printing two totals',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['trial-balance'] = '{"data":{"rows":[{"nominalCode":"1001",'
            '"nominalName":"Stock","debit":150,"credit":0,"balance":150}],'
            '"totalDebit":150,"totalCredit":0,"balanced":false}}';
      await pump(tester, adapter);
      // The sidebar scrolls and the sixteenth entry starts below the fold.
      await tester.ensureVisible(find.text('Trial Balance', skipOffstage: false).last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Trial Balance').last);
      await tester.pumpAndSettle();
      expect(find.textContaining('The ledger does not balance over this range'), findsOneWidget);
      expect(find.textContaining('fault to investigate'), findsOneWidget);
    });

    testWidgets('open sales clearing is listed under the trial balance (17.7)', (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['trial-balance'] = '{"data":{"rows":[],"totalDebit":0,'
            '"totalCredit":0,"balanced":true}}'
        ..bodyFor['sales-clearing'] = '{"data":[{"orderId":"01a090ae-611e-7a00-8000-00000000abcd",'
            '"storeId":"s-1","balance":-40.00,"firstPosted":"2026-09-13","lastPosted":"2026-09-13"}]}';
      await pump(tester, adapter);
      await tester.ensureVisible(find.text('Trial Balance', skipOffstage: false).last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Trial Balance').last);
      await tester.pumpAndSettle();
      expect(find.text('Open sales clearing: 1 order(s)'), findsOneWidget);
      await tester.tap(find.text('Open sales clearing: 1 order(s)'));
      await tester.pumpAndSettle();
      expect(find.textContaining('taken, no confirmed sale'), findsOneWidget);
      expect(find.text('-40.00'), findsOneWidget);
      expect(adapter.callTo('sales-clearing').path, contains('/nominal-ledger/sales-clearing'));
    });

    testWidgets('a ledger whose sales all cleared says so', (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['trial-balance'] = '{"data":{"rows":[],"totalDebit":0,'
            '"totalCredit":0,"balanced":true}}'
        ..bodyFor['sales-clearing'] = '{"data":[]}';
      await pump(tester, adapter);
      await tester.ensureVisible(find.text('Trial Balance', skipOffstage: false).last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Trial Balance').last);
      await tester.pumpAndSettle();
      expect(find.textContaining("Every sale's takings cleared"), findsOneWidget);
      expect(find.textContaining('Open sales clearing'), findsNothing);
    });

    Future<void> openDeferredRevenue(WidgetTester tester) async {
      await tester.ensureVisible(find.text('Deferred Revenue', skipOffstage: false).last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Deferred Revenue').last);
      await tester.pumpAndSettle();
    }

    const waiting = '{"data":{"history":[],"pointsOutstanding":0,"deferredIncome":0,'
        '"pointsUnmatched":0,"eventsAwaitingEstimates":3,"giftCardsLoaded":120,'
        '"giftCardsRedeemed":55,"giftCardBreakage":0,"giftCardLiability":65}}';

    testWidgets('deferred revenue with no estimates says what is waiting (17.11)', (tester) async {
      await pump(tester, _RecordingAdapter()..bodyFor['deferred-revenue'] = waiting);
      await openDeferredRevenue(tester);
      expect(find.textContaining('3 loyalty event(s) are waiting'), findsOneWidget);
      expect(find.text('Set estimates'), findsOneWidget);
      expect(find.text('65.00'), findsOneWidget);
      expect(find.text('Deferred income (2330)'), findsOneWidget);
    });

    testWidgets('estimates need a reason, a refusal is shown in words, and what is saved is what was typed',
        (tester) async {
      final adapter = _RecordingAdapter()..bodyFor['deferred-revenue'] = waiting;
      await pump(tester, adapter);
      await openDeferredRevenue(tester);
      await tester.tap(find.byKey(const Key('set-estimates')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('estimate-point-value')), '0.05');
      await tester.enterText(find.byKey(const Key('estimate-points-breakage')), '96');
      await tester.enterText(find.byKey(const Key('estimate-gift-card-breakage')), '10');
      await tester.tap(find.byKey(const Key('save-estimates')));
      await tester.pumpAndSettle();
      expect(find.textContaining('the reason for them'), findsOneWidget);
      expect(adapter.requests.where((r) => r.method == 'PUT'), isEmpty);

      adapter
        ..bodyFor['deferred-revenue/settings'] =
            '{"error":{"code":"PURCHASE_BREAKAGE_OUT_OF_RANGE","message":"a breakage estimate is a percentage from 0 to 95, to two decimal places"}}'
        ..statusFor['deferred-revenue/settings'] = 400;
      await tester.enterText(find.byKey(const Key('estimate-reason')), 'Two years of scheme data');
      await tester.tap(find.byKey(const Key('save-estimates')));
      await tester.pumpAndSettle();
      expect(find.textContaining('from 0 to 95'), findsOneWidget);
      expect(find.byKey(const Key('save-estimates')), findsOneWidget);

      adapter
        ..bodyFor['deferred-revenue/settings'] = '{"data":{}}'
        ..statusFor.remove('deferred-revenue/settings');
      await tester.enterText(find.byKey(const Key('estimate-points-breakage')), '20');
      await tester.tap(find.byKey(const Key('save-estimates')));
      await tester.pumpAndSettle();
      final put = adapter.requests.lastWhere((r) => r.method == 'PUT');
      expect(put.path, contains('/nominal-ledger/deferred-revenue/settings'));
      expect(put.data, {
        'pointValue': 0.05,
        'pointsBreakagePct': 20.0,
        'giftCardBreakagePct': 10.0,
        'reason': 'Two years of scheme data',
      });
      expect(find.byKey(const Key('save-estimates')), findsNothing);
    });

    testWidgets('an empty trial balance says so, and opens the journal dialog', (tester) async {
      await pump(tester, _RecordingAdapter());
      // The sidebar scrolls and the sixteenth entry starts below the fold.
      await tester.ensureVisible(find.text('Trial Balance', skipOffstage: false).last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Trial Balance').last);
      await tester.pumpAndSettle();
      expect(find.text('Nothing was posted in this range.'), findsOneWidget);
      await tester.tap(find.byKey(const Key('post-journal')));
      await tester.pumpAndSettle();
      expect(find.text('Post a journal'), findsOneWidget);
    });

    testWidgets('the four that finish the pack are reachable too', (tester) async {
      await pump(tester, _RecordingAdapter());
      for (final label in [
        'Sales by Hour',
        'Sales by Staff',
        'Tender Mix',
        'Stock Turn',
        'Gross Margin',
        'Dead Stock',
      ]) {
        expect(find.text(label), findsWidgets, reason: '$label is not reachable');
      }
    });

    testWidgets('sales by hour says how many hours are absent, not zero',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['sales-by-hour'] = '{"data":[{"hourOfDay":9,"orders":4,'
            '"grossAmount":120.00,"discountAmount":5.00,"averageBasket":30.00}]}';
      await pump(tester, adapter);
      await tester.tap(find.text('Sales by Hour').last);
      await tester.pumpAndSettle();

      expect(find.text('09:00–10:00'), findsOneWidget);
      expect(find.text('30.00'), findsOneWidget);
      // The other 23 hours produced no row. Reading that as "we sold nothing"
      // rather than "the shop was shut" is the mistake this line prevents.
      expect(find.textContaining('23 of the 24 are absent'), findsOneWidget);
    });

    testWidgets('sales by staff warns that online orders are not in it',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['sales-by-staff'] = '{"data":[{"groupKey":"UNATTRIBUTED",'
            '"sales":2,"grossAmount":40.00,"discountAmount":10.00,'
            '"averageBasket":20.00,"discountRate":20.0}]}';
      await pump(tester, adapter);
      await tester.tap(find.text('Sales by Staff').last);
      await tester.pumpAndSettle();

      expect(find.textContaining('In-store sales only'), findsOneWidget);
      expect(find.text('Unattributed'), findsOneWidget);
      expect(find.text('20.0%'), findsOneWidget);
    });

    testWidgets('tender mix surfaces declines as their own signal',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['tender-mix'] = '{"data":[{"method":"CARD",'
            '"capturedAmount":100.00,"capturedCount":2,"refundedAmount":40.00,'
            '"refundedCount":1,"failedCount":3,"netAmount":60.00,"shareOfNet":50.0}]}';
      await pump(tester, adapter);
      await tester.tap(find.text('Tender Mix').last);
      await tester.pumpAndSettle();

      // The method in words, never its code.
      expect(find.text('Card'), findsOneWidget);
      expect(find.text('CARD'), findsNothing);
      expect(find.text('50.0%'), findsOneWidget);
      expect(find.textContaining('3 tenders did not capture'), findsOneWidget);
    });

    testWidgets('amounts read as money in the business\'s currency, never bare numbers',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['/admin/tenant'] =
            '{"data":{"id":"t","name":"Corner Shop","status":"ACTIVE","currency":"GBP","country":"GB"}}'
        ..bodyFor['tender-mix'] = '{"data":[{"method":"GIFT_CARD",'
            '"capturedAmount":1234.5,"capturedCount":2,"refundedAmount":40.00,'
            '"refundedCount":1,"failedCount":0,"netAmount":1194.5,"shareOfNet":100.0}]}';
      await pump(tester, adapter);
      await tester.tap(find.text('Tender Mix').last);
      await tester.pumpAndSettle();

      expect(find.text('Gift card'), findsOneWidget);
      expect(find.text('£1,234.50'), findsOneWidget);
      expect(find.text('£40.00'), findsOneWidget);
      expect(find.text('£1,194.50'), findsOneWidget);
      expect(find.text('1234.50'), findsNothing);
    });

    testWidgets('stock turn shows a dash, not a zero, when nothing turned',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['stock-turn'] = '{"data":{"rows":[{"groupKey":"s-1","cogs":0,'
            '"uncostedSaleQty":4.000,"openingValue":0,"closingValue":0,'
            '"averageValue":0,"turnoverRatio":null,"daysOnHand":null}],'
            '"historyComplete":false,"windowDays":30}}';
      await pump(tester, adapter);
      await tester.tap(find.text('Stock Turn').last);
      await tester.pumpAndSettle();

      // Nothing to turn is a different finding from turning it zero times.
      expect(find.text('—'), findsNWidgets(2));
      // Both caveats are live and they mean different things.
      expect(find.textContaining('has been archived'), findsOneWidget);
      expect(find.textContaining('no cost price'), findsOneWidget);
    });

    testWidgets('gross margin marks a loss and declares what it could not price',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['gross-margin'] = '{"data":{"rows":['
            '{"groupKey":"v-loss","revenue":10.00,"cogs":15.00,"grossMargin":-5.00,'
            '"marginPercent":-50.0,"averageValue":50.00,"gmroi":-0.10,'
            '"annualisedGmroi":-1.22,"uncostedSaleQty":0,"unpricedSaleQty":0},'
            '{"groupKey":"v-unpriced","revenue":0,"cogs":3.00,"grossMargin":-3.00,'
            '"marginPercent":null,"averageValue":0,"gmroi":null,'
            '"annualisedGmroi":null,"uncostedSaleQty":0,"unpricedSaleQty":3.000}],'
            '"historyComplete":true,"windowDays":30}}';
      await pump(tester, adapter);
      await tester.tap(find.text('Gross Margin').last);
      await tester.pumpAndSettle();

      expect(find.text('-50.0%'), findsOneWidget);
      expect(find.text('-0.10'), findsOneWidget);
      // Nothing earned has no margin percentage and nothing held no GMROI.
      expect(find.text('—'), findsNWidgets(3));
      expect(find.textContaining('no revenue recorded'), findsOneWidget);
      // A complete history and fully costed sales raise no caveat.
      expect(find.textContaining('has been archived'), findsNothing);
      expect(find.textContaining('no cost price'), findsNothing);
    });

    testWidgets('gross margin with no sales says so instead of a blank table',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['gross-margin'] =
            '{"data":{"rows":[],"historyComplete":false,"windowDays":1}}';
      await pump(tester, adapter);
      await tester.tap(find.text('Gross Margin').last);
      await tester.pumpAndSettle();

      expect(find.text('No sales in this range.'), findsOneWidget);
      expect(find.textContaining('over 1 day ·'), findsOneWidget);
      expect(find.textContaining('has been archived'), findsOneWidget);
    });

    testWidgets('dead stock says which date each age is measured from',
        (tester) async {
      final adapter = _RecordingAdapter()
        ..bodyFor['dead-stock'] = '{"data":['
            '{"groupKey":"0-30","onHandQty":10.000,"value":50.00,'
            '"uncostedQty":0,"daysSinceLastSale":12,"neverSold":false},'
            '{"groupKey":"180+","onHandQty":4.000,"value":90.00,'
            '"uncostedQty":0,"daysSinceLastSale":400,"neverSold":true}]}';
      await pump(tester, adapter);
      await tester.tap(find.text('Dead Stock').last);
      await tester.pumpAndSettle();

      // 400 days since *receipt* and 12 since a *sale* are not the same claim.
      expect(find.text('received'), findsOneWidget);
      expect(find.text('last sale'), findsOneWidget);
      expect(find.textContaining('140.00 at risk'), findsOneWidget);
    });
  });

  group('the reports that finish the pack — requests', () {
    test('stock turn always sends a window, because the endpoint requires one',
        () async {
      final h = _harness(bodies: {
        'stock-turn': '{"data":{"rows":[],"historyComplete":true,"windowDays":30}}'
      });
      await h.container.read(stockTurnReportProvider.future);

      final call = h.adapter.callTo('/reports/stock-turn');
      expect(call.query['from'], '2026-08-01T00:00:00Z');
      expect(call.query['to'], '2026-09-01T00:00:00Z');
      expect(call.query['groupBy'], 'STORE');
    });

    test('gross margin sends the same window and grouping as stock turn',
        () async {
      final h = _harness(bodies: {
        'gross-margin':
            '{"data":{"rows":[{"groupKey":"v","revenue":12,"cogs":null,'
                '"grossMargin":12,"marginPercent":100.0,"gmroi":null}],'
                '"historyComplete":true,"windowDays":31}}'
      });
      final report = await h.container.read(grossMarginReportProvider.future);

      final call = h.adapter.callTo('/reports/gross-margin');
      expect(call.query['from'], '2026-08-01T00:00:00Z');
      expect(call.query['to'], '2026-09-01T00:00:00Z');
      expect(call.query['groupBy'], 'STORE');
      // A missing amount reads as zero; a missing ratio stays missing.
      expect(report.rows.single.cogs, 0);
      expect(report.rows.single.gmroi, isNull);
      expect(report.rows.single.annualisedGmroi, isNull);
    });

    test('dead stock sends no window — it is a question about now', () async {
      final h = _harness();
      await h.container.read(deadStockReportProvider.future);

      final call = h.adapter.callTo('/reports/dead-stock');
      expect(call.query.containsKey('from'), isFalse);
      expect(call.query.containsKey('to'), isFalse);
      expect(call.query['groupBy'], 'BUCKET');
    });

    test('sales by hour sends a timezone the server reads the same way',
        () async {
      final h = _harness();
      await h.container.read(salesByHourReportProvider.future);

      // The form is load-bearing: Postgres reads "UTC+04:00" under the POSIX
      // convention, where the sign is inverted, so a UTC-prefixed offset would
      // bucket every hour on the wrong side of the meridian.
      final tz = h.adapter.callTo('/sales-by-hour').query['tz'] as String;
      expect(tz == 'UTC' || RegExp(r'^[+-]\d{2}:\d{2}$').hasMatch(tz), isTrue,
          reason: 'tz was "$tz" — must be UTC or a bare ISO offset');
    });

    test('a channel filter is sent only when one is chosen', () async {
      final h = _harness();
      await h.container.read(salesByHourReportProvider.future);
      expect(h.adapter.callTo('/sales-by-hour').query.containsKey('channel'),
          isFalse);

      h.container.read(salesByHourChannelProvider.notifier).state = 'POS';
      await h.container.read(salesByHourReportProvider.future);
      expect(h.adapter.calls.last.query['channel'], 'POS');
    });

    test('tender mix widens the picker range to instants like the rest',
        () async {
      final h = _harness();
      await h.container.read(tenderMixReportProvider.future);

      final call = h.adapter.callTo('/tender-mix');
      expect(call.query['from'], '2026-08-01T00:00:00Z');
      expect(call.query['to'], '2026-09-01T00:00:00Z');
    });
  });
}
