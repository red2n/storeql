import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/consignment_tab.dart';
import 'package:storeql_app/features/admin/procurement_providers.dart';
import 'package:storeql_app/features/admin/procurement_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Dropship on the Procurement screen: arrangements listed with the supplier by
// name and its cost, End posting, a dropship order wearing its badge and saying
// where it ships, and no buttons for staff who may not.
// ---------------------------------------------------------------------------

const _supplier = '01a0b000-0000-7000-8000-0000000000a1';
const _variant = '01a0b000-0000-7000-8000-0000000000b1';
const _arrangement = '01a0b000-0000-7000-8000-0000000000e1';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool ended = false;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/suppliers')) {
      return jsonResponse(
          '{"data":[{"id":"$_supplier","name":"Desks Direct","vatRegistered":true,"currency":"GBP","paymentTermsDays":30}]}');
    }
    if (path.endsWith('/admin/dropship/arrangements') && o.method == 'GET') {
      return jsonResponse(
          '{"data":[{"id":"$_arrangement","variantId":"$_variant","supplierId":"$_supplier","unitCost":80.0,"vatCode":"T1","active":${!ended}}]}');
    }
    if (path.endsWith('/admin/dropship/arrangements/$_arrangement/end')) {
      ended = true;
      return jsonResponse(
          '{"data":{"id":"$_arrangement","variantId":"$_variant","supplierId":"$_supplier","unitCost":80.0,"vatCode":"T1","active":false}}');
    }
    if (path.endsWith('/purchase-orders') && o.method == 'GET') {
      return jsonResponse('{"data":['
          '{"id":"01a0b000-0000-7000-8000-0000000000f1","supplierId":"$_supplier","storeId":"st-1","status":"DRAFT","currency":"GBP","totalNet":80.0,"totalVat":16.0,"totalGross":96.0,"createdAt":"2026-09-24T09:00:00Z","source":"DROPSHIP","salesOrderId":"01a0b000-0000-7000-8000-0000000000f2","shipTo":"Chris Carter, 12 High Street, Leeds, LS1 1AA, tel 07700900123"}'
          ']}');
    }
    if (path.endsWith('/stores')) {
      return jsonResponse('{"data":[{"id":"st-1","name":"High Street","code":"HS","type":"STORE","status":"ACTIVE","country":"GB"}]}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pumpTab(WidgetTester tester, {String role = 'MANAGER'}) async {
  tester.view.physicalSize = const Size(1100, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
    ],
    child: const MaterialApp(home: Scaffold(body: ConsignmentTab())),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  dialogTests();
  // Dates are written with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);
  testWidgets('an arrangement names the supplier and its cost; End posts and the row says ended',
      (tester) async {
    final server = await _pumpTab(tester);
    expect(find.textContaining('from Desks Direct'), findsOneWidget);
    expect(find.textContaining('80.00'), findsOneWidget);
    await tester.tap(find.byKey(const Key('arrangement-end-$_arrangement')));
    await tester.pumpAndSettle();
    final post = server.requests.lastWhere((r) => r.method == 'POST');
    expect(post.path, endsWith('/admin/dropship/arrangements/$_arrangement/end'));
    expect(find.textContaining('ended'), findsWidgets);
    expect(find.byKey(const Key('arrangement-end-$_arrangement')), findsNothing);
  });

  testWidgets('New arrangement asks for the supplier and the cost and posts them', (tester) async {
    final server = await _pumpTab(tester);
    await tester.tap(find.byKey(const Key('dropship-new')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('dropship-supplier')), findsOneWidget);
    await tester.enterText(find.byKey(const Key('dropship-cost')), '80');
    await tester.tap(find.byKey(const Key('dropship-save')));
    await tester.pumpAndSettle();
    // Without a variant it refuses before posting.
    expect(find.byKey(const Key('dropship-refusal')), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
  });

  testWidgets('staff who may not arrange sourcing read the section without its buttons',
      (tester) async {
    await _pumpTab(tester, role: 'STOREKEEPER');
    expect(find.textContaining('from Desks Direct'), findsOneWidget);
    expect(find.byKey(const Key('dropship-new')), findsNothing);
    expect(find.byKey(const Key('arrangement-end-$_arrangement')), findsNothing);
  });

  testWidgets('a dropship order wears its badge and says where it ships', (tester) async {
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
    expect(find.text('Dropship'), findsOneWidget);
    await tester.tap(find.text('Draft')); // the status badge's word
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('po-ship-to')), findsOneWidget);
    expect(find.textContaining('Chris Carter'), findsOneWidget);
    expect(jsonDecode('{"ok":true}')['ok'], isTrue);
  });
}

// What the supplier charges per unit is a purchase unit cost, to six places
// as an order line carries it, read the way the app's language writes a
// number and sent as the decimal typed: read with a point, Romanian's 80,50
// went as -1 and the arrangement was refused for a cost nobody typed.
class _DialogServer implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/admin/products')) {
      return jsonResponse('{"data":[{"id":"p-oat","name":"Oat milk"}],"meta":{"nextCursor":null}}');
    }
    if (o.path.endsWith('/admin/products/p-oat/variants')) {
      return jsonResponse('{"data":[{"id":"v-oat","productId":"p-oat","sku":"OAT-1"}]}');
    }
    return jsonResponse('{"data":{}}');
  }
}

Future<_DialogServer> _openArrangement(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1200, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _DialogServer();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(
              context: context,
              builder: (_) => const NewDropshipArrangementDialog(suppliers: [
                Supplier(id: 's-1', name: 'Acme', vatRegistered: true, currency: 'GBP', paymentTermsDays: 30),
              ]),
            ),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Product *'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Oat milk').last);
  await tester.pumpAndSettle();
  await tester.tap(find.text('Variant *'));
  await tester.pumpAndSettle();
  await tester.tap(find.text('OAT-1').last);
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('dropship-supplier')));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Acme').last);
  await tester.pumpAndSettle();
  return server;
}

void dialogTests() {
  group('the cost of a dropship arrangement', () {
    tearDown(() => Intl.defaultLocale = null);

    Future<void> press(WidgetTester tester, String text) async {
      for (var i = 1; i <= text.length; i++) {
        await tester.enterText(find.byKey(const Key('dropship-cost')), text.substring(0, i));
        await tester.pump();
      }
    }

    String? says(WidgetTester tester) =>
        tester.widget<TextField>(find.byKey(const Key('dropship-cost'))).decoration?.errorText;

    Iterable<Map<String, dynamic>> posts(_DialogServer s) => [
          for (final r in s.requests)
            if (r.method == 'POST')
              (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>,
        ];

    for (final (locale, typed, sent) in [
      ('ro', '80,50', '80.5'),
      ('en_GB', '0.0125', '0.0125'),
      ('en', '80', '80'),
      ('pl', '12,125', '12.125'),
      ('ar', '1٫5', '1.5'),
    ]) {
      testWidgets('in $locale, $typed is arranged as $sent', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _openArrangement(tester);
        await press(tester, typed);
        expect(says(tester), isNull);
        await tester.tap(find.byKey(const Key('dropship-save')));
        await tester.pumpAndSettle();
        expect(posts(server).single,
            {'variantId': 'v-oat', 'supplierId': 's-1', 'unitCost': sent});
      });
    }

    for (final (locale, typed) in [('ro', '80.50'), ('en', '80,50'), ('pl', '1.250'), ('en_GB', '.'), ('ar', '-1'), ('en_GB', '')]) {
      testWidgets('in $locale, "$typed" is refused and nothing is arranged', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _openArrangement(tester);
        await press(tester, typed);
        await tester.tap(find.byKey(const Key('dropship-save')));
        await tester.pumpAndSettle();
        expect(says(tester) ?? (find.byKey(const Key('dropship-refusal')).evaluate().isEmpty ? null : 'refused'),
            isNotNull);
        expect(posts(server), isEmpty);
      });
    }
  });
}
