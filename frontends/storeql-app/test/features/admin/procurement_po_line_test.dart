import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/procurement_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A line added to a draft order: its quantity and unit cost are read the way
// the app's language writes a number, with the shared amount reader, and
// sent as the decimals they are. One the dialog cannot read is refused under
// its field and nothing is sent: read with a point, Romanian's 1.250 lei a
// unit was ordered at 1,25.
// ---------------------------------------------------------------------------

const _supplier = '01a0b000-0000-7000-8000-0000000000a1';
const _po = '01a0b000-0000-7000-8000-0000000000f1';

const _order =
    '"id":"$_po","supplierId":"$_supplier","storeId":"st-1","status":"DRAFT","currency":"RON","totalNet":0,"totalVat":0,"totalGross":0,"createdAt":"2026-09-24T09:00:00Z"';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (o.method == 'POST') return jsonResponse('{"data":{}}', 201);
    if (path.endsWith('/suppliers')) {
      return jsonResponse('{"data":[{"id":"$_supplier","name":"Desks Direct","currency":"RON","paymentTermsDays":30}]}');
    }
    if (path.endsWith('/purchase-orders')) return jsonResponse('{"data":[{$_order}]}');
    if (path.endsWith('/purchase-orders/$_po')) return jsonResponse('{"data":{$_order,"warnings":[]}}');
    if (path.endsWith('/admin/products')) {
      return jsonResponse('{"data":[{"id":"p-desk","name":"Desk"}],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/admin/products/p-desk/variants')) {
      return jsonResponse('{"data":[{"id":"v-desk","productId":"p-desk","sku":"DESK-1"}]}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _addLine(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1200, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('MANAGER')),
    ],
    child: const MaterialApp(home: ProcurementScreen()),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Draft'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Add line'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Product *'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Desk').last);
  await tester.pumpAndSettle();
  await tester.tap(find.text('Variant *'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('DESK-1').last);
  await tester.pumpAndSettle();
  return server;
}

Finder _field(String label) => find.widgetWithText(TextField, label);

Iterable<RequestOptions> _lines(_Server server) =>
    server.requests.where((r) => r.method == 'POST' && r.path.endsWith('/lines'));

void main() {
  setUpAll(initializeDateFormatting);
  tearDown(() => Intl.defaultLocale = null);

  testWidgets('in Romanian, a unit cost of 1.250 is refused in words, never ordered at 1,25',
      (tester) async {
    Intl.defaultLocale = 'ro';
    final server = await _addLine(tester);
    await tester.enterText(_field('Unit cost'), '1.250');
    await tester.pump();
    expect(tester.widget<TextField>(_field('Unit cost')).decoration?.errorText,
        'Type the amount without thousands separators. Decimals go after a comma.');
    await tester.tap(find.widgetWithText(FilledButton, 'Add line'));
    await tester.pumpAndSettle();
    expect(_lines(server), isEmpty);
  });

  testWidgets('in Romanian, 2,5 at 0,0125 is sent as typed', (tester) async {
    Intl.defaultLocale = 'ro';
    final server = await _addLine(tester);
    await tester.enterText(_field('Qty'), '2,5');
    await tester.enterText(_field('Unit cost'), '0,0125');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Add line'));
    await tester.pumpAndSettle();
    final sent = _lines(server).single.data as Map;
    expect(sent['qty'], '2.5');
    expect(sent['unitPrice'], '0.0125');
  });
}
