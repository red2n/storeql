import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/spacing.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/admin/age_checks_screen.dart';
import 'package:storeql_app/features/pos/pos_age_check.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

// The age-check register a manager shows a licensing officer: counts, the
// refusals with their reasons, and filters that ask the server the right
// question. Read-only — there is nothing on this screen that could edit a
// record, and the test would fail if there were.

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool empty = false;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    String body;
    if (o.path.endsWith('/age-checks/summary')) {
      body = empty
          ? '{"data":{"total":0,"passed":0,"refused":0,"refusedByReason":{},"byCategory":{}}}'
          : '{"data":{"total":5,"passed":3,"refused":2,"refusedByReason":{"NO_ID":1,"UNDER_AGE":1},"byCategory":{"ALCOHOL":4,"TOBACCO":1}}}';
    } else if (o.path.endsWith('/age-checks')) {
      body = empty
          ? '{"data":[],"meta":{}}'
          : '{"data":['
              '{"id":"a","storeId":"s1","variantId":"v1","category":"ALCOHOL","minimumAge":18,"country":"GB","storePolicy":false,"outcome":"REFUSED","reason":"NO_ID","checkedAt":"2026-09-12T10:00:00Z"},'
              '{"id":"b","storeId":"s1","variantId":"v2","category":"TOBACCO","minimumAge":18,"country":"GB","storePolicy":true,"outcome":"PASSED","idType":"PASS_CARD","checkedAt":"2026-09-12T09:00:00Z"}'
              '],"meta":{}}';
    } else {
      body = '{"data":[]}';
    }
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Server> _pump(WidgetTester tester,
    {bool empty = false,
    ThemeData? theme,
    Size size = const Size(1100, 1400),
    double textScale = 1}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final server = _Server()..empty = empty;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      storesProvider.overrideWith((ref) async => const [
            StoreInfo(id: 's1', name: 'High Street', code: 'HS', type: 'STORE', status: 'ACTIVE', country: 'GB'),
          ]),
    ],
    child: MaterialApp(
      theme: theme,
      home: MediaQuery.withClampedTextScaling(
        minScaleFactor: textScale,
        maxScaleFactor: textScale,
        child: const Scaffold(body: AgeChecksScreen()),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  return server;
}

Rect _cardAround(WidgetTester tester, String label) => tester.getRect(
    find.ancestor(of: find.text(label), matching: find.byType(Card)).first);

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('the counts and the refusals with their reasons are shown', (tester) async {
    await _pump(tester);
    expect(find.text('5'), findsOneWidget);
    expect(find.text('2'), findsOneWidget);
    expect(find.text('No ID shown: 1'), findsOneWidget);
    expect(find.textContaining('Refused — No ID shown'), findsOneWidget);
    expect(find.textContaining('Sale went ahead — PASS card'), findsOneWidget);
    expect(find.textContaining('(store policy)'), findsOneWidget);
    // The country in words, never its code.
    expect(find.textContaining('18+ in the United Kingdom'), findsNWidgets(2));
    expect(find.textContaining('in GB'), findsNothing);
  });

  testWidgets('the three count cards stand apart, never edge to edge', (tester) async {
    // The app's theme gives cards no margin of their own, so any gap is the screen's.
    await _pump(tester, theme: AppTheme.light);
    final checks = _cardAround(tester, 'Checks');
    final passed = _cardAround(tester, 'Sales went ahead');
    final refused = _cardAround(tester, 'Refused');
    expect(passed.left - checks.right, greaterThanOrEqualTo(AppSpacing.sm));
    expect(refused.left - passed.right, greaterThanOrEqualTo(AppSpacing.sm));
  });

  for (final (name, theme) in [('light', AppTheme.light), ('dark', AppTheme.dark)]) {
    testWidgets('a sale that went ahead is drawn in the success colour ($name)', (tester) async {
      await _pump(tester, theme: theme);
      final ctx = tester.element(find.byType(AgeChecksScreen));
      final pass = tester.widget<Icon>(find.byIcon(Icons.check_circle_outline));
      expect(pass.color, ctx.status.success);
      expect(pass.color, isNot(theme.colorScheme.primary));
      // A refusal stays in the error colour.
      expect(tester.widget<Icon>(find.byIcon(Icons.block)).color, theme.colorScheme.error);
    });
  }

  testWidgets('each check says its outcome once, in words, not again in capitals', (tester) async {
    await _pump(tester);
    expect(find.text('REFUSED'), findsNothing);
    expect(find.text('PASSED'), findsNothing);
    expect(find.textContaining('Refused — No ID shown'), findsOneWidget);
    expect(find.textContaining('Sale went ahead — PASS card'), findsOneWidget);
  });

  testWidgets('on a phone the page sits 16 in from the edge', (tester) async {
    await _pump(tester, size: const Size(390, 1400));
    expect(tester.getTopLeft(find.text('Age checks')).dx, AppSpacing.lg);
    expect(tester.takeException(), isNull);
  });

  testWidgets('on a phone with text at 200% nothing overflows', (tester) async {
    await _pump(tester, theme: AppTheme.light, size: const Size(390, 844), textScale: 2);
    expect(tester.takeException(), isNull);
    expect(find.byKey(const Key('age-checks-store')), findsOneWidget);
  });

  testWidgets('an empty period says what an empty register means', (tester) async {
    await _pump(tester, empty: true);
    expect(find.textContaining('has not been checking'), findsOneWidget);
  });

  testWidgets('the filters ask the server, with an exclusive upper bound', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.text('Refusals'));
    await tester.pumpAndSettle();
    final register = server.requests.lastWhere((r) => r.path.endsWith('/age-checks'));
    expect(register.queryParameters['outcome'], 'REFUSED');
    final from = DateTime.parse(register.queryParameters['from'] as String);
    final to = DateTime.parse(register.queryParameters['to'] as String);
    expect(to.isAfter(from), isTrue);
    // The picker's last day is inclusive; the API's bound is exclusive, so "to" is one day past.
    expect(to.difference(from).inDays, 30);
  });

  testWidgets('nothing on the register can change a record', (tester) async {
    await _pump(tester);
    expect(find.byIcon(Icons.edit), findsNothing);
    expect(find.byIcon(Icons.delete), findsNothing);
    expect(find.widgetWithText(FilledButton, 'Save'), findsNothing);
  });

  test('a check against a birth-date cut-off keeps the date and whose rule it was',
      () {
    final r = AgeCheckRecord.fromJson({
      'id': 'c-1',
      'storeId': 's-1',
      'variantId': 'v-1',
      'category': 'TOBACCO',
      'minimumAge': 18,
      'country': 'GB',
      'storePolicy': false,
      'bornBefore': '2009-01-01',
      'bornBeforeStorePolicy': true,
      'outcome': 'REFUSED',
      'reason': 'BORN_AFTER_CUTOFF',
    });
    expect(r.bornBefore, '2009-01-01');
    expect(r.bornBeforeStorePolicy, isTrue);
    expect(ageRefusalReasons[r.reason], 'Born on or after the cut-off date');
    // A check with no cut-off has none.
    expect(AgeCheckRecord.fromJson({'outcome': 'PASSED'}).bornBefore, isNull);
  });
}
