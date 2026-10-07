import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/einvoice_providers.dart';
import 'package:storeql_app/features/admin/orders_screen.dart';
import 'package:storeql_app/features/admin/sales_invoice_providers.dart';
import 'package:storeql_app/features/admin/sales_invoices_dialog.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Invoices to business buyers (18.9). The server issues them when a sale to a
// registered business completes; the back office reads them, downloads them
// as issued, and issues one by hand when the automatic one was refused. These
// tests hold the screen to that: the menu offers it only where a sale exists
// and only to management, a document offers the formats the server says it
// has and no other, a refusal is shown in the server's words, one tap is one
// request, a file is named by the number, and a customer's registration is
// recorded whole — both halves of an address, a VAT number when registered.
// ---------------------------------------------------------------------------

Map<String, dynamic> _doc({
  String id = 'i-1',
  String kind = 'INVOICE',
  String number = 'INV/2026/000001',
  bool peppol = false,
  List<String> formats = const ['UBL', 'CII', 'FACTURX'],
  List<String> irpProblems = const [],
  String? preceding,
}) =>
    {
      'id': id,
      'orderId': 'o-1',
      'returnId': kind == 'CREDIT_NOTE' ? 'r-1' : null,
      'storeId': 's-1',
      'kind': kind,
      'typeCode': kind == 'CREDIT_NOTE' ? '381' : '380',
      'fullNumber': number,
      'seriesCode': number.substring(0, 3),
      'period': '2026',
      'number': 1,
      'issueDate': '2026-09-16',
      'issuedAt': '2026-09-16T09:00:00Z',
      'customerId': 'c-1',
      'buyerName': 'Cafe Leeds Ltd',
      'buyerVatId': 'GB555555555',
      'currency': 'GBP',
      'netAmount': 29.0,
      'vatAmount': 4.2,
      'payableAmount': 33.2,
      'precedingInvoiceId': preceding,
      'customizationId': peppol
          ? 'urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0'
          : 'urn:cen.eu:en16931:2017',
      'peppol': peppol,
      'formats': formats,
      'irpProblems': irpProblems,
    };

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  List<Map<String, dynamic>> documents = [];
  int issueStatus = 200;
  String issueRefusal =
      '{"error":{"code":"ORDER_INVOICE_BUYER_NOT_REGISTERED","message":"the customer is not recorded as VAT-registered with a VAT number"}}';
  Map<String, dynamic>? vatStatus;
  int saveStatus = 200;
  String saveRefusal =
      '{"error":{"code":"PRICING_VAT_NUMBER_INVALID","message":"GB12 is not a valid VAT number"}}';

  /// When set, a request is held until the gate completes — for the double-tap case.
  Completer<void>? gate;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (gate != null) await gate!.future;
    final path = o.path;
    if (o.method == 'GET' && path.endsWith('/admin/orders/o-1/invoices')) {
      return jsonResponse('{"data":${jsonEncode(documents)}}');
    }
    if (o.method == 'POST' && path.endsWith('/admin/orders/o-1/invoice')) {
      if (issueStatus != 200) return jsonResponse(issueRefusal, issueStatus);
      documents = [_doc()];
      return jsonResponse('{"data":${jsonEncode(_doc())}}');
    }
    if (o.method == 'GET' &&
        path.contains('/admin/sales-invoices/') &&
        path.endsWith('/document')) {
      final format = o.queryParameters['format'] as String? ?? 'UBL';
      final type = switch (format) {
        'FACTURX' => 'application/pdf',
        'IRP' => 'application/json',
        _ => 'application/xml',
      };
      return ResponseBody.fromBytes(
        Uint8List.fromList(utf8.encode('<$format/>')),
        200,
        headers: {
          Headers.contentTypeHeader: [type],
        },
      );
    }
    if (o.method == 'GET' && path.endsWith('/customer-vat-status/c-1')) {
      return vatStatus == null
          ? jsonResponse(
              '{"error":{"code":"PRICING_VAT_STATUS_NOT_FOUND","message":"none"}}',
              404)
          : jsonResponse('{"data":${jsonEncode(vatStatus)}}');
    }
    if (o.method == 'POST' && path.endsWith('/customer-vat-status')) {
      if (saveStatus != 200) return jsonResponse(saveRefusal, saveStatus);
      vatStatus = {..._body(o), 'id': 'v-1', 'tenantId': 't'};
      return jsonResponse('{"data":${jsonEncode(vatStatus)}}');
    }
    return jsonResponse(
        '{"error":{"code":"NOT_FOUND","message":"no route ${o.method} $path"}}',
        404);
  }
}

class _Opened {
  final _Server server;
  final List<String> saved = [];
  _Opened(this.server);
}

Future<_Opened> _pump(WidgetTester tester, Widget Function(BuildContext) open,
    {_Server? server}) async {
  final opened = _Opened(server ?? _Server());
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = opened.server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      eInvoiceSaverProvider
          .overrideWithValue((name, bytes) async => opened.saved.add(name)),
    ],
    child: MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => showDialog<void>(
                context: context, builder: (_) => open(context)),
            child: const Text('open'),
          ),
        ),
      ),
    ),
  ));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
  return opened;
}

Future<_Opened> _pumpSection(WidgetTester tester, {_Server? server}) async {
  final opened = _Opened(server ?? _Server());
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = opened.server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
    child: const MaterialApp(
      home: Scaffold(body: VatRegistrationSection(customerId: 'c-1')),
    ),
  ));
  await tester.pumpAndSettle();
  return opened;
}

List<RequestOptions> _posts(_Server s) =>
    s.requests.where((r) => r.method == 'POST').toList();

Map<String, dynamic> _body(RequestOptions r) =>
    (r.data is String ? jsonDecode(r.data as String) : r.data)
        as Map<String, dynamic>;

Future<List<String>> _pumpMenu(WidgetTester tester,
    {required String status, bool canInvoice = true}) async {
  final picked = <String>[];
  await tester.pumpWidget(MaterialApp(
    home: Scaffold(
      body: OrderActionsMenu(
        status: status,
        channel: 'POS',
        canInvoice: canInvoice,
        onAction: picked.add,
      ),
    ),
  ));
  await tester.tap(find.byIcon(Icons.more_vert));
  await tester.pumpAndSettle();
  return picked;
}

void main() {
  setUpAll(initializeDateFormatting);

  group('the actions menu', () {
    testWidgets(
        'offers Invoices on a completed sale to management, and it opens them',
        (tester) async {
      final picked = await _pumpMenu(tester, status: 'FULFILLED');
      expect(find.text('Invoices'), findsOneWidget);
      await tester.tap(find.text('Invoices'));
      await tester.pumpAndSettle();
      expect(picked, ['invoices']);
    });

    testWidgets(
        'not on a basket never paid for, a cancelled sale, or to anyone below manager',
        (tester) async {
      await _pumpMenu(tester, status: 'PENDING');
      expect(find.text('Invoices'), findsNothing);
      await tester.tapAt(Offset.zero);
      await _pumpMenu(tester, status: 'CANCELLED');
      expect(find.text('Invoices'), findsNothing);
      await tester.tapAt(Offset.zero);
      await _pumpMenu(tester, status: 'FULFILLED', canInvoice: false);
      expect(find.text('Invoices'), findsNothing);
    });
  });

  group('the documents dialog', () {
    testWidgets(
        'lists the invoice and its credit note, and downloads each as the server offers it',
        (tester) async {
      final server = _Server()
        ..documents = [
          _doc(id: 'i-1', peppol: true),
          _doc(
              id: 'c-1',
              kind: 'CREDIT_NOTE',
              number: 'CRN/2026/000001',
              preceding: 'i-1'),
        ];
      final opened = await _pump(
          tester, (_) => const OrderInvoicesDialog(orderId: 'o-1'),
          server: server);
      expect(find.text('Invoice INV/2026/000001'), findsOneWidget);
      expect(find.text('Credit note CRN/2026/000001'), findsOneWidget);
      expect(find.text('Peppol'), findsOneWidget);
      expect(find.textContaining('Cafe Leeds Ltd · GB555555555'),
          findsNWidgets(2));
      // Already invoiced: nothing to issue by hand.
      expect(find.byKey(const Key('sales-invoice-issue')), findsNothing);

      await tester.tap(find.byKey(const Key('sales-invoice-download-i-1')));
      await tester.pumpAndSettle();
      expect(find.text('UBL XML (as issued)'), findsOneWidget);
      expect(find.text('CII XML'), findsOneWidget);
      expect(find.text('Factur-X PDF'), findsOneWidget);
      expect(find.text('IRP JSON (India)'), findsNothing);
      await tester.tap(find.text('Factur-X PDF'));
      await tester.pumpAndSettle();

      final get =
          server.requests.singleWhere((r) => r.path.endsWith('/document'));
      expect(
          get.path, endsWith('/order-svc/admin/sales-invoices/i-1/document'));
      expect(get.queryParameters['format'], 'FACTURX');
      expect(opened.saved, ['INV-2026-000001.pdf']);
    });

    testWidgets(
        "an Indian document the portal would refuse says why, and offers no IRP download",
        (tester) async {
      final server = _Server()
        ..documents = [
          _doc(
              id: 'i-1',
              irpProblems: ['IRP-ITEM-03: every item needs an HSN code']),
          _doc(
              id: 'i-2',
              number: 'INV/2026/000002',
              formats: ['UBL', 'CII', 'FACTURX', 'IRP']),
        ];
      final opened = await _pump(
          tester, (_) => const OrderInvoicesDialog(orderId: 'o-1'),
          server: server);
      expect(
          find.textContaining('every item needs an HSN code'), findsOneWidget);
      await tester.tap(find.byKey(const Key('sales-invoice-download-i-1')));
      await tester.pumpAndSettle();
      expect(find.text('IRP JSON (India)'), findsNothing);
      await tester.tapAt(Offset.zero);
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('sales-invoice-download-i-2')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('IRP JSON (India)'));
      await tester.pumpAndSettle();
      expect(opened.saved, ['INV-2026-000002-irp.json']);
    });

    testWidgets(
        'issues the invoice by hand once, and shows a refusal in the server\'s words',
        (tester) async {
      final server = _Server()..issueStatus = 409;
      await _pump(tester, (_) => const OrderInvoicesDialog(orderId: 'o-1'),
          server: server);
      expect(find.textContaining('No invoice has been issued'), findsOneWidget);
      await tester.tap(find.byKey(const Key('sales-invoice-issue')));
      await tester.pumpAndSettle();
      expect(_posts(server), hasLength(1));
      expect(find.byKey(const Key('sales-invoice-error')), findsOneWidget);
      expect(find.textContaining('not recorded as VAT-registered'),
          findsOneWidget);

      // Put right on the server; two taps at once are one request, and the list follows.
      server
        ..issueStatus = 200
        ..gate = Completer<void>();
      await tester.tap(find.byKey(const Key('sales-invoice-issue')));
      await tester.pump();
      await tester.tap(find.byKey(const Key('sales-invoice-issue')),
          warnIfMissed: false);
      await tester.pump();
      server.gate!.complete();
      server.gate = null;
      await tester.pumpAndSettle();
      expect(_posts(server), hasLength(2));
      expect(find.text('Invoice INV/2026/000001'), findsOneWidget);
      expect(find.byKey(const Key('sales-invoice-issue')), findsNothing);
    });

    test('a file is named by the number, never by anything a person typed', () {
      final inv = SalesInvoice.fromJson(_doc(number: '../etc/passwd'));
      expect(salesInvoiceFileName(inv, 'UBL'), '---etc-passwd.xml');
      expect(salesInvoiceFileName(SalesInvoice.fromJson(_doc()), 'CII'),
          'INV-2026-000001-cii.xml');
    });
  });

  group("a customer's VAT registration", () {
    testWidgets(
        'none recorded means receipts, and the dialog records one whole',
        (tester) async {
      final opened = await _pumpSection(tester);
      expect(find.byKey(const Key('customer-vat-none')), findsOneWidget);
      await tester.tap(find.byKey(const Key('customer-vat-edit')));
      await tester.pumpAndSettle();
      await tester.enterText(
          find.byKey(const Key('customer-vat-legal-name')), 'Cafe Leeds Ltd');
      await tester.enterText(
          find.byKey(const Key('customer-vat-number')), 'GB555555555');
      // The country is chosen from the list of every country.
      await tester.tap(find.byKey(const Key('customer-vat-country')));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(find.text('United Kingdom (GB)'), 400,
          scrollable: find.byType(Scrollable).last);
      await tester.tap(find.text('United Kingdom (GB)').last);
      await tester.pumpAndSettle();
      await tester.enterText(
          find.byKey(const Key('customer-einvoice-scheme')), '9932');
      await tester.enterText(
          find.byKey(const Key('customer-einvoice-id')), 'GB555555555');
      await tester.tap(find.byKey(const Key('customer-vat-save')));
      await tester.pumpAndSettle();

      final posts = _posts(opened.server);
      expect(posts, hasLength(1));
      expect(posts.single.path, endsWith('/pricing-svc/customer-vat-status'));
      expect(_body(posts.single), {
        'customerId': 'c-1',
        'vatRegistered': true,
        'reverseChargeEligible': false,
        'vatNumber': 'GB555555555',
        'legalName': 'Cafe Leeds Ltd',
        'countryCode': 'GB',
        'einvoiceScheme': '9932',
        'einvoiceId': 'GB555555555',
      });
      // The dialog closed and the section shows what was recorded.
      expect(find.byKey(const Key('customer-vat-save')), findsNothing);
      expect(find.byKey(const Key('customer-vat-summary')), findsOneWidget);
      expect(find.textContaining('Peppol 9932:GB555555555'), findsOneWidget);
    });

    testWidgets(
        'a registered business needs a VAT number, and an address needs both halves',
        (tester) async {
      final opened = await _pumpSection(tester);
      await tester.tap(find.byKey(const Key('customer-vat-edit')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('customer-vat-save')));
      await tester.pumpAndSettle();
      expect(
          find.text('A registered business has a VAT number'), findsOneWidget);
      await tester.enterText(
          find.byKey(const Key('customer-vat-number')), 'GB555555555');
      await tester.enterText(
          find.byKey(const Key('customer-einvoice-scheme')), '9932');
      await tester.tap(find.byKey(const Key('customer-vat-save')));
      await tester.pumpAndSettle();
      expect(find.text('Give the identifier too'), findsOneWidget);
      expect(_posts(opened.server), isEmpty);
    });

    testWidgets(
        "the server's refusal is shown in its words, and nothing is recorded",
        (tester) async {
      final server = _Server()..saveStatus = 400;
      await _pumpSection(tester, server: server);
      await tester.tap(find.byKey(const Key('customer-vat-edit')));
      await tester.pumpAndSettle();
      await tester.enterText(
          find.byKey(const Key('customer-vat-number')), 'GB12');
      await tester.tap(find.byKey(const Key('customer-vat-save')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('customer-vat-error')), findsOneWidget);
      expect(find.textContaining('GB12 is not a valid VAT number'),
          findsOneWidget);
      expect(find.byKey(const Key('customer-vat-save')), findsOneWidget);
    });

    testWidgets(
        'a business already recorded is shown, and can be taken off the register',
        (tester) async {
      final server = _Server()
        ..vatStatus = {
          'customerId': 'c-1',
          'vatRegistered': true,
          'reverseChargeEligible': true,
          'vatNumber': '29AAGCB7383J1Z4',
          'legalName': 'Bengaluru Stores Pvt Ltd',
          'countryCode': 'IN',
        };
      await _pumpSection(tester, server: server);
      expect(
          find.textContaining(
              'Bengaluru Stores Pvt Ltd · 29AAGCB7383J1Z4 · IN · reverse charge'),
          findsOneWidget);
      await tester.tap(find.byKey(const Key('customer-vat-edit')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('customer-vat-registered')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('customer-vat-save')));
      await tester.pumpAndSettle();
      final body = _body(_posts(server).single);
      expect(body['vatRegistered'], false);
      // Off the register means no reverse charge either, whatever the switch said.
      expect(body['reverseChargeEligible'], false);
      expect(find.byKey(const Key('customer-vat-none')), findsOneWidget);
    });
  });
}
