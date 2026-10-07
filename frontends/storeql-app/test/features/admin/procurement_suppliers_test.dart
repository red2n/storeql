import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/procurement_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A supplier can be corrected after it is created (SJ-D34). The dialog opens
// with what the supplier has — a JPY supplier opens in JPY even though JPY is
// not on the picker — and sends the correction to PUT /suppliers/{id}. The
// server's refusal (an open order in the old currency) is shown in words.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int putStatus = 200;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'PUT') {
      final body = putStatus == 200
          ? '{"data":{"id":"s-1","name":"Yen By Mistake","vatRegistered":true,"countryCode":"GB","currency":"GBP","paymentTermsDays":45}}'
          : '{"error":{"code":"PURCHASE_SUPPLIER_CURRENCY_IN_USE","message":"1 open purchase order(s) are denominated in JPY; receive, close or cancel them before changing the currency"}}';
      return ResponseBody.fromString(body, putStatus,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    }
    final body = o.path.endsWith('/suppliers')
        ? '{"data":[{"id":"s-1","name":"Yen By Mistake","vatRegistered":true,"vatNumber":"GB999999973","countryCode":"GB","currency":"JPY","paymentTermsDays":30}]}'
        : '{"data":[]}';
    return ResponseBody.fromString(body, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Server> _pump(WidgetTester tester,
    {String role = 'MANAGER', List<String> storeIds = const []}) async {
  tester.view.physicalSize = const Size(1200, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role, storeIds: storeIds)),
    ],
    child: const MaterialApp(home: ProcurementScreen()),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Suppliers'));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  testWidgets('a manager opens the supplier prefilled — in its own currency — and corrects it',
      (tester) async {
    final server = await _pump(tester);
    expect(find.text('Yen By Mistake'), findsOneWidget);
    await tester.tap(find.byTooltip('Edit supplier'));
    await tester.pumpAndSettle();
    expect(find.text('Edit supplier'), findsOneWidget);
    // Prefilled from the record, JPY included though the picker never offered it.
    expect(find.widgetWithText(TextFormField, 'Yen By Mistake'), findsOneWidget);
    expect(find.text('JPY — Japanese Yen'), findsOneWidget);
    expect(find.widgetWithText(TextFormField, '30'), findsOneWidget);

    await tester.enterText(find.widgetWithText(TextFormField, '30'), '45');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    final put = server.requests.singleWhere((r) => r.method == 'PUT');
    expect(put.path, endsWith('/suppliers/s-1'));
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body['paymentTermsDays'], 45);
    expect(body['currency'], 'JPY', reason: 'what was not touched is sent as it was');
    expect(body['vatNumber'], 'GB999999973');
    expect(find.text('Supplier updated.'), findsOneWidget);
  });

  // The terms and the quoted lead time are whole days. Text that is not a
  // number of days was saved as 30 days, or as no lead time, and is now
  // refused under its field with nothing saved.
  for (final (typed, why) in [
    ('1,000', 'Type the amount without thousands separators.'),
    ('15.', 'Whole amounts only.'),
    ('.', 'Whole amounts only.'),
    ('-', 'Type the amount without a sign.'),
    ('+5', 'Type the amount without a sign.'),
    ('0x10', 'Only digits.'),
  ]) {
    testWidgets('terms of "$typed" are refused under the field, never saved as 30 days',
        (tester) async {
      final server = await _pump(tester);
      await tester.tap(find.byTooltip('Edit supplier'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('supplier-terms')), typed);
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(find.text(why), findsOneWidget);
      expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
    });

    testWidgets('a lead time of "$typed" is refused under the field, never saved as none',
        (tester) async {
      final server = await _pump(tester);
      await tester.tap(find.byTooltip('Edit supplier'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('supplier-lead-time')), typed);
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(find.text(why), findsOneWidget);
      expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
    });
  }

  testWidgets('a lead time typed is saved as typed; left blank it is left out', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byTooltip('Edit supplier'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('supplier-lead-time')), '7');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    final put = server.requests.singleWhere((r) => r.method == 'PUT');
    final body = (put.data is String ? jsonDecode(put.data as String) : put.data) as Map;
    expect(body['leadTimeDays'], 7);
    expect(body['paymentTermsDays'], 30);
  });

  testWidgets('the refusal for an open order in the old currency is shown in words',
      (tester) async {
    final server = await _pump(tester)..putStatus = 409;
    await tester.tap(find.byTooltip('Edit supplier'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.textContaining('open purchase order'), findsOneWidget);
    expect(find.text('Edit supplier'), findsOneWidget, reason: 'the dialog stays open');
    expect(server.requests.where((r) => r.method == 'PUT'), hasLength(1));
  });

  testWidgets('a supplier\'s e-invoicing address needs both halves before it is sent',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byTooltip('Edit supplier'));
    await tester.pumpAndSettle();
    final scheme = find.byKey(const Key('supplier-einvoice-scheme'));
    await tester.ensureVisible(scheme);
    await tester.enterText(scheme, '0088');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.text('Give the identifier too'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);

    final id = find.byKey(const Key('supplier-einvoice-id'));
    await tester.ensureVisible(id);
    await tester.enterText(id, '5790000435951');
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    final put = server.requests.singleWhere((r) => r.method == 'PUT');
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body['einvoiceScheme'], '0088');
    expect(body['einvoiceId'], '5790000435951');
  });

  testWidgets('a cashier is not offered the edit', (tester) async {
    await _pump(tester, role: 'CASHIER');
    expect(find.text('Yen By Mistake'), findsOneWidget);
    expect(find.byTooltip('Edit supplier'), findsNothing);
  });

  // A supplier's terms and bank details are every store's: purchase-svc answers a manager held
  // to stores 403 BUSINESS_WIDE_ONLY on PUT /suppliers/{id}. Such a manager reads the supplier,
  // is not offered the correction, and is told who makes one; a head-office manager is.
  testWidgets('a manager held to stores reads a supplier and is told who corrects one',
      (tester) async {
    final server = await _pump(tester, storeIds: const ['01a0b000-0000-7000-8000-0000000000a1']);
    expect(find.text('Yen By Mistake'), findsOneWidget);
    expect(find.byTooltip('Edit supplier'), findsNothing);
    expect(find.text('Only an owner or a head-office manager corrects a supplier.'), findsOneWidget);
    // Adding one is still theirs.
    expect(find.text('Add supplier'), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
  });

  testWidgets('a head-office manager held to no store is offered the correction, and no note',
      (tester) async {
    await _pump(tester);
    expect(find.byTooltip('Edit supplier'), findsOneWidget);
    expect(find.text('Only an owner or a head-office manager corrects a supplier.'), findsNothing);
  });
}
