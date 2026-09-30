import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/procurement_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// An order of a supplier whose recent deliveries grade D carries a plain
// warning when it is read and when it is submitted. Never a block: Submit stays
// on offer and goes through.
// ---------------------------------------------------------------------------

const _supplier = '01a0b000-0000-7000-8000-0000000000a1';
const _po = '01a0b000-0000-7000-8000-0000000000f1';

const _order =
    '"id":"$_po","supplierId":"$_supplier","storeId":"st-1","status":"DRAFT","currency":"GBP","totalNet":80.0,"totalVat":16.0,"totalGross":96.0,"createdAt":"2026-09-24T09:00:00Z"';

class _Server implements HttpClientAdapter {
  _Server({required this.warned});
  final bool warned;
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/suppliers')) {
      return jsonResponse('{"data":[{"id":"$_supplier","name":"Desks Direct","vatRegistered":true,"currency":"GBP","paymentTermsDays":30}]}');
    }
    if (path.endsWith('/purchase-orders') && o.method == 'GET') return jsonResponse('{"data":[{$_order}]}');
    if (path.endsWith('/purchase-orders/$_po/submit')) {
      return jsonResponse('{"data":{$_order,"status":"SUBMITTED","warnings":["SUPPLIER_GRADE_D"]}}');
    }
    if (path.endsWith('/purchase-orders/$_po') && o.method == 'GET') {
      return jsonResponse('{"data":{$_order,"warnings":${warned ? '["SUPPLIER_GRADE_D"]' : '[]'}}}');
    }
    if (path.endsWith('/stores')) return jsonResponse('{"data":[]}');
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _open(WidgetTester tester, {required bool warned}) async {
  tester.view.physicalSize = const Size(1200, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(warned: warned);
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
  return server;
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('an order read for a grade D supplier shows the warning in words, and Submit stays on offer',
      (tester) async {
    await _open(tester, warned: true);
    expect(find.byKey(const Key('po-warning-SUPPLIER_GRADE_D')), findsOneWidget);
    expect(find.text("This supplier's recent deliveries grade D"), findsOneWidget);
    expect(find.text('SUPPLIER_GRADE_D'), findsNothing);
    expect(find.text('Submit'), findsOneWidget);
  });

  testWidgets('an order with no warnings shows none', (tester) async {
    await _open(tester, warned: false);
    expect(find.byKey(const Key('po-warning-SUPPLIER_GRADE_D')), findsNothing);
  });

  testWidgets('submitting is not blocked and the answer\'s warning is said with it', (tester) async {
    final server = await _open(tester, warned: true);
    await tester.tap(find.text('Submit'));
    await tester.pumpAndSettle();
    expect(server.requests.where((r) => r.method == 'POST' && r.path.endsWith('/submit')), hasLength(1));
    expect(find.textContaining("Purchase order submitted. This supplier's recent deliveries grade D"),
        findsOneWidget);
  });
}
