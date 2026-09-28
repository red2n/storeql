import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/procurement_screen.dart';
import 'package:storeql_app/shared/widgets/page_header.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Procurement's page frame: the title, the first tab's label, the actions and
// the cards of every tab this screen draws share one inset — 16 on a phone, 24
// from tablet width — so nothing sits 8px in from what is under it.
// ---------------------------------------------------------------------------

const _supplierId = '0199a0b0-0000-7000-8000-0000000005a1';
const _poId = '0199a0b0-0000-7000-8000-0000000000a1';

const _suppliers = '''
{"data":[{"id":"$_supplierId","name":"Fresh Farms","currency":"GBP","paymentTermsDays":30}]}''';

const _orders = '''
{"data":[{"id":"$_poId","supplierId":"$_supplierId","storeId":"s-1","status":"SUBMITTED",
 "currency":"GBP","totalNet":100,"totalVat":20,"totalGross":120,"createdAt":"2026-09-01T10:00:00Z"}]}''';

const _invoices = '''
{"data":[{"id":"i-1","poId":"$_poId","invoiceNumber":"INV-1","invoiceDate":"2026-09-02",
 "currency":"GBP","netAmount":100,"vatAmount":20,"grossAmount":120,"status":"MATCHED","lines":[]}]}''';

class _Server implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    final path = o.path;
    if (path.endsWith('/suppliers')) return jsonResponse(_suppliers);
    if (path.endsWith('/purchase-orders')) return jsonResponse(_orders);
    if (path.endsWith('/supplier-invoices')) return jsonResponse(_invoices);
    return jsonResponse('{"data":[]}');
  }
}

Future<void> _pump(WidgetTester tester, Size size,
    {String role = 'STOREKEEPER', double textScale = 1}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Server();
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      // A storekeeper: the suppliers tab shows no scorecards card (another file's) above its list.
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
    ],
    child: MaterialApp(
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
        child: child!,
      ),
      home: const Scaffold(body: ProcurementScreen()),
    ),
  ));
  await tester.pumpAndSettle();
}

Rect _card(WidgetTester tester, String text) =>
    tester.getRect(find.ancestor(of: find.text(text), matching: find.byType(Card)).first);

void main() {
  setUpAll(initializeDateFormatting);

  for (final (label, size, gutter) in [
    ('on a phone', const Size(390, 844), 16.0),
    ('from tablet width', const Size(1200, 900), 24.0),
  ]) {
    testWidgets('$label the title, the first tab, the action and the orders share one inset',
        (tester) async {
      await _pump(tester, size);
      expect(find.byType(PageHeader), findsOneWidget);
      expect(tester.getTopLeft(find.text('Procurement')).dx, gutter);
      // The label itself, not the tab's box: the tab's own label padding is part of the inset.
      expect(tester.getTopLeft(find.text('Purchase Orders')).dx, gutter);
      expect(tester.getTopLeft(find.byKey(const Key('propose-orders'))).dx, gutter);
      final order = _card(tester, 'Fresh Farms');
      expect(order.left, gutter);
      expect(order.right, size.width - gutter);
      expect(tester.takeException(), isNull);
    });

    testWidgets('$label the invoices and the suppliers line up under the title too', (tester) async {
      await _pump(tester, size);
      await tester.tap(find.text('Invoices'));
      await tester.pumpAndSettle();
      expect(_card(tester, 'INV-1').left, gutter);
      expect(_card(tester, 'INV-1').right, size.width - gutter);

      // On a phone the tab is scrolled into view first.
      await tester.ensureVisible(find.text('Suppliers'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Suppliers'));
      await tester.pumpAndSettle();
      expect(_card(tester, 'Fresh Farms').left, gutter);
      expect(tester.takeException(), isNull);
    });

    testWidgets('$label the scorecards, consignment and sourcing tabs share the inset too',
        (tester) async {
      await _pump(tester, size, role: 'MANAGER');
      Future<void> open(String tab) async {
        await tester.ensureVisible(find.text(tab));
        await tester.pumpAndSettle();
        await tester.tap(find.text(tab));
        await tester.pumpAndSettle();
      }

      await open('Suppliers');
      final scorecards = _card(tester, 'Scorecards, last 90 days');
      expect(scorecards.left, gutter);
      expect(scorecards.right, size.width - gutter);

      await open('Consignment & dropship');
      expect(tester.getTopLeft(find.text('Dropship arrangements')).dx, gutter);

      await open('Sourcing');
      expect(tester.getTopLeft(find.text('Requests for quotation')).dx, gutter);
      expect(tester.takeException(), isNull);
    });
  }

  testWidgets('on a phone at 200% text the Propose orders button wraps rather than running off',
      (tester) async {
    await _pump(tester, const Size(390, 844), textScale: 2);
    expect(tester.takeException(), isNull);
    expect(find.byKey(const Key('propose-orders')), findsOneWidget);
    expect(tester.getRect(find.byKey(const Key('propose-orders'))).right,
        lessThanOrEqualTo(390 - 16));
  });

  testWidgets('the title follows the type scale of the window', (tester) async {
    await _pump(tester, const Size(390, 844));
    final phone = tester.widget<Text>(find.text('Procurement')).style;
    await _pump(tester, const Size(1200, 900));
    final wide = tester.widget<Text>(find.text('Procurement')).style;
    expect(phone!.fontSize, lessThan(wide!.fontSize!));
  });
}
