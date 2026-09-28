import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/reports_screen.dart';
import 'package:storeql_app/shared/util/short_ref.dart';

// ---------------------------------------------------------------------------
// On-Hand Inventory, the report the screen opens on, as a manager reads it: the
// stores and products by name, a count of the range that does not count a
// product twice because two stores stock it, and — on a phone — the selected
// report in view, the actions under the title, and one inset for the title,
// the summary and the table.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

const _highStreet = '0192f0c1-7a3b-7c11-8d2e-000000000001';
const _market = '0192f0c1-7a3b-7c11-8d2e-000000000002';
const _peanut = '0192f0c1-7a3b-7c11-8d2e-0000000000a1';
const _jam = '0192f0c1-7a3b-7c11-8d2e-0000000000a2';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final String body;
    if (o.path.endsWith('/reports/inventory/on-hand')) {
      // Peanut butter is stocked in both stores: two rows, one product.
      body = '{"data":{"rows":['
          '{"storeId":"$_highStreet","variantId":"$_peanut","onHand":10},'
          '{"storeId":"$_market","variantId":"$_peanut","onHand":5},'
          '{"storeId":"$_highStreet","variantId":"$_jam","onHand":3}]}}';
    } else if (o.path.endsWith('/variants/resolve')) {
      body = '{"data":['
          '{"variantId":"$_peanut","productName":"Crunchy peanut butter","sku":"PB-340"},'
          '{"variantId":"$_jam","productName":"Strawberry jam","sku":"JAM-454"}]}';
    } else {
      body = '{"data":[]}';
    }
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<_Server> _pump(WidgetTester tester, Size size) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      storesProvider.overrideWith((ref) async => const [
            StoreInfo(
                id: _highStreet, name: 'High Street', code: 'HS', type: 'STORE', status: 'ACTIVE'),
            StoreInfo(
                id: _market, name: 'Market Square', code: 'MS', type: 'STORE', status: 'ACTIVE'),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: ReportsScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

/// The report's own title: the sidebar tile or the chip that selects it comes
/// first in the tree.
Finder get _title => find.text('On-Hand Inventory').last;

Finder get _tableCard =>
    find.ancestor(of: find.byType(DataTable), matching: find.byType(Card)).first;

void main() {
  // Report periods are dated with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);
  testWidgets('stores and products are named, not shown as the ends of their ids',
      (tester) async {
    await _pump(tester, const Size(1400, 1000));
    final table = find.byType(DataTable);
    expect(find.descendant(of: table, matching: find.text('High Street')), findsNWidgets(2));
    expect(find.descendant(of: table, matching: find.text('Market Square')), findsOneWidget);
    expect(find.descendant(of: table, matching: find.textContaining('Crunchy peanut butter')),
        findsNWidgets(2));
    expect(find.descendant(of: table, matching: find.textContaining('Strawberry jam')),
        findsOneWidget);
    expect(find.descendant(of: table, matching: find.textContaining('PB-340')), findsWidgets);
    for (final id in [_highStreet, _market, _peanut, _jam]) {
      expect(find.descendant(of: table, matching: find.textContaining(shortRef(id))), findsNothing,
          reason: '$id is shown as an id');
    }
  });

  testWidgets('the summary counts products once, however many stores stock them',
      (tester) async {
    await _pump(tester, const Size(1400, 1000));
    expect(find.textContaining('3 SKUs'), findsNothing, reason: 'three rows are two products');
    expect(find.textContaining('2 SKUs'), findsOneWidget);
    expect(find.textContaining('18 units'), findsOneWidget);
  });

  testWidgets('on a phone the selected report chip is scrolled into view', (tester) async {
    await _pump(tester, const Size(390, 844));
    final chip = tester.getRect(find.widgetWithText(ChoiceChip, 'On-Hand Inventory'));
    expect(chip.left, greaterThanOrEqualTo(0));
    expect(chip.right, lessThanOrEqualTo(390));
  });

  testWidgets('on a phone Export CSV and Refresh sit under the title and description',
      (tester) async {
    await _pump(tester, const Size(390, 844));
    final subtitle = tester.getRect(find.text('Total units across all stores'));
    final export = tester.getRect(find.widgetWithText(TextButton, 'Export CSV'));
    final refresh = tester.getRect(find.byTooltip('Refresh'));
    expect(export.top, greaterThanOrEqualTo(subtitle.bottom));
    expect(refresh.top, greaterThanOrEqualTo(subtitle.bottom));
    // The text keeps the whole width inside the gutters, not what the buttons
    // leave of it.
    expect(tester.renderObject<RenderBox>(_title).constraints.maxWidth, 390 - 2 * 16);
    expect(tester.takeException(), isNull);
  });

  testWidgets('on a phone at 200% text the report still fits', (tester) async {
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await _pump(tester, const Size(390, 844));
    expect(tester.takeException(), isNull);
    expect(find.widgetWithText(TextButton, 'Export CSV'), findsOneWidget);
  });

  for (final (label, size, gutter) in [
    ('a phone', const Size(390, 844), 16.0),
    ('a desktop', const Size(1400, 1000), 24.0),
  ]) {
    testWidgets('on $label the title, the summary and the table share one inset', (tester) async {
      await _pump(tester, size);
      final title = tester.getTopLeft(_title).dx;
      final summary = tester.getTopLeft(find.byType(Chip).last).dx;
      final card = tester.getTopLeft(_tableCard).dx;
      expect(summary, title);
      expect(card, title);
      // And that inset is the page gutter, measured from the content's own edge.
      final contentStart = size.width >= 800 ? 221.0 : 0.0;
      expect(title - contentStart, gutter);
    });
  }
}
