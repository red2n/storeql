import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/ids.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/reports_screen.dart';
import 'package:storeql_app/shared/util/short_ref.dart';

// ---------------------------------------------------------------------------
// Report rows name people and things, never ids. A report grouped by staff
// member names each person by their login (iam-svc's email), and one grouped
// by category names each category from the catalogue. The end of an id stands
// in only for someone or something that cannot be named — a login iam-svc will
// not name, a category the catalogue no longer lists — and a bucket that is
// nobody (`SYSTEM`, `UNATTRIBUTED`) is said in words and never sent to iam-svc,
// which refuses anything that is not a UUIDv7.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

const _ana = '0192f0c1-7a3b-7c11-8d2e-4f1a9c3e5b71';
const _ben = '0192f0c1-7a3b-7c11-9a4d-2b8e6f0c1d42';

/// Someone iam-svc will not name: left the business, or never on its staff.
const _left = '0192f0c1-7a3b-7c11-a17c-93d05e2f4a63';

const _drinks = '0192f0c1-7a3b-7c11-b2e9-1c4d7a8f0e15';

/// A category with sales in the period that the catalogue no longer lists.
const _retired = '0192f0c1-7a3b-7c11-8f03-6a2c9d1e7b36';

const _logins = {_ana: 'ana@corner.shop', _ben: 'ben@corner.shop'};

class _Server implements HttpClientAdapter {
  /// The `ids` of every request to iam-svc for staff names.
  final List<List<String>> staffAsks = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    var status = 200;
    String body = '{"data":[]}';
    final p = o.path;
    if (p.endsWith('/auth/admin/staff-users')) {
      final ids = (o.queryParameters['ids'] as String).split(',');
      staffAsks.add(ids);
      if (ids.any((id) => !isV7(id))) {
        // As iam-svc answers: one id that is not a UUIDv7 refuses the lot.
        status = 400;
        body = '{"data":null,"error":{"code":"INVALID_UUID"}}';
      } else {
        body = '{"data":[${[
          for (final id in ids)
            if (_logins[id] != null) '{"userId":"$id","email":"${_logins[id]}"}',
        ].join(',')}]}';
      }
    } else if (p.endsWith('/reports/shrinkage')) {
      body = o.queryParameters['groupBy'] == 'ACTOR'
          ? '{"data":['
              '{"groupKey":"$_ana","qtyWrittenOff":4,"qtyFound":0,"netQty":-4,"movements":2},'
              '{"groupKey":"$_left","qtyWrittenOff":1,"qtyFound":0,"netQty":-1,"movements":1},'
              '{"groupKey":"SYSTEM","qtyWrittenOff":2,"qtyFound":0,"netQty":-2,"movements":1}]}'
          : '{"data":[]}';
    } else if (p.endsWith('/reports/exceptions')) {
      body = '{"data":{"rows":['
          '{"groupKey":"$_ben","discounts":2,"discountAmount":5,"voids":1,"noSales":0,'
          '"sales":40,"salesValue":800},'
          '{"groupKey":"UNATTRIBUTED","discounts":0,"discountAmount":0,"voids":1,'
          '"noSales":0,"sales":10,"salesValue":90}],"journalCoverage":true}}';
    } else if (p.endsWith('/reports/sales-by-staff')) {
      body = '{"data":['
          '{"groupKey":"$_ana","sales":12,"grossAmount":240,"discountAmount":0,'
          '"averageBasket":20,"discountRate":0},'
          '{"groupKey":"$_ben","sales":8,"grossAmount":120,"discountAmount":12,'
          '"averageBasket":15,"discountRate":10}]}';
    } else if (p.endsWith('/reports/sales/by-category')) {
      body = '{"data":{"level":"leaf","rows":['
          '{"categoryId":"$_drinks","currency":"GBP","orders":3,"units":6,"gross":18,"share":60},'
          '{"categoryId":"$_retired","currency":"GBP","orders":1,"units":2,"gross":12,"share":40}]}}';
    } else if (p.endsWith('/admin/categories')) {
      body = '{"data":[{"id":"$_drinks","name":"Soft drinks","status":"ACTIVE",'
          '"createdAt":"2026-01-01T00:00:00Z"}]}';
    }
    return ResponseBody.fromString(body, status, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Server> _pump(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1400, 1300);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: Scaffold(body: ReportsScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

Future<void> _open(WidgetTester tester, String report) async {
  await tester.tap(find.text(report).last);
  await tester.pumpAndSettle();
}

Finder _inTable(Finder f) => find.descendant(of: find.byType(DataTable), matching: f);

void main() {
  // Report periods are dated with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);

  testWidgets('shrinkage by staff member names each person by their login', (tester) async {
    final server = await _pump(tester);
    await _open(tester, 'Shrinkage');
    await tester.tap(find.widgetWithText(ChoiceChip, 'By staff member'));
    await tester.pumpAndSettle();

    expect(_inTable(find.text('ana@corner.shop')), findsOneWidget);
    expect(_inTable(find.textContaining(shortRef(_ana))), findsNothing,
        reason: 'a named person is not shown as the end of their id');
    // No one iam-svc will name: the end of the id, and only then.
    expect(_inTable(find.text('…${shortRef(_left)}')), findsOneWidget);
    // Stock the platform moved itself is nobody's, said in words.
    expect(_inTable(find.text('System')), findsOneWidget);
    expect(_inTable(find.textContaining('SYSTEM')), findsNothing);
    expect(server.staffAsks, isNotEmpty);
    expect(server.staffAsks.expand((ids) => ids), everyElement(predicate<String>(isV7)),
        reason: 'SYSTEM is never sent to iam-svc');
  });

  testWidgets('staff exceptions name the person, and keep the unattributed row', (tester) async {
    final server = await _pump(tester);
    await _open(tester, 'Staff Exceptions');

    expect(_inTable(find.text('ben@corner.shop')), findsOneWidget);
    expect(_inTable(find.textContaining(shortRef(_ben))), findsNothing);
    expect(_inTable(find.text('Unattributed')), findsOneWidget);
    expect(server.staffAsks.expand((ids) => ids), isNot(contains('UNATTRIBUTED')));
  });

  testWidgets('sales by staff names each cashier', (tester) async {
    await _pump(tester);
    await _open(tester, 'Sales by Staff');

    expect(_inTable(find.text('ana@corner.shop')), findsOneWidget);
    expect(_inTable(find.text('ben@corner.shop')), findsOneWidget);
    for (final id in [_ana, _ben]) {
      expect(_inTable(find.textContaining(shortRef(id))), findsNothing,
          reason: '$id is shown as an id');
    }
  });

  testWidgets('names already read stay named from one report to the next, without asking again',
      (tester) async {
    final server = await _pump(tester);
    await _open(tester, 'Sales by Staff');
    expect(server.staffAsks, hasLength(1));

    await _open(tester, 'Staff Exceptions');
    expect(_inTable(find.text('ben@corner.shop')), findsOneWidget);
    expect(server.staffAsks, hasLength(1), reason: 'Ben was named a moment ago');
  });

  testWidgets('sales by category names each category, and an id only for one no longer listed',
      (tester) async {
    await _pump(tester);
    await _open(tester, 'Sales by Category');

    expect(_inTable(find.text('Soft drinks')), findsOneWidget);
    expect(_inTable(find.textContaining(shortRef(_drinks))), findsNothing,
        reason: 'a named category is not shown as the end of its id');
    // Marked as an id, not passed off as a name.
    final retired = tester.widget<Text>(_inTable(find.text('…${shortRef(_retired)}')));
    expect(retired.style?.fontFamily, 'monospace');
  });
}
