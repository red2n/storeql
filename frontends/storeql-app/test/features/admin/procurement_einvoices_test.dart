import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/einvoice_providers.dart';
import 'package:storeql_app/features/admin/einvoice_tab.dart';
import 'package:storeql_app/features/admin/procurement_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Supplier e-invoices received (07.13). A document the server captured needs
// nobody; one that waits has to say, in a sentence, what is missing, and let
// the person who knows pick it. These tests hold the screen to that: the
// waiting ones lead, the reason is words not a constant, only the changed
// choices are sent, a refusal needs a reason and goes once, nothing over the
// cap leaves the browser, and a supplier's invoice number never becomes a path.
// ---------------------------------------------------------------------------

Map<String, dynamic> _einvoice({
  String id = 'e-1',
  String number = 'INV-9',
  String status = 'NEEDS_LINES',
  String? problem = 'Line 1 is not on the order: pick its order line',
  String? poLineId,
  List<Map<String, dynamic>> violations = const [],
  String channel = 'UPLOAD',
  String? deliveryRef,
}) =>
    {
      'id': id,
      'receivedAt': '2026-09-15T10:00:00Z',
      'channel': channel,
      'deliveryRef': deliveryRef,
      'container': 'XML',
      'syntax': 'UBL',
      'typeCode': '380',
      'creditNote': false,
      'invoiceNumber': number,
      'issueDate': '2026-09-14',
      'currency': 'GBP',
      'sellerName': 'Acme Foods Ltd',
      'sellerVatId': 'GB555555555',
      'sellerEndpoint': '0088:5790000435951',
      'orderReference': 'po-1',
      'payableAmount': 120.0,
      'status': status,
      'problem': problem,
      'supplierId': 's-1',
      'poId': 'po-1',
      'alreadyReceived': false,
      'violations': violations,
      'lines': [
        {
          'position': 1,
          'itemName': 'Oat milk 1L',
          'sellersItemId': 'OAT-1',
          'quantity': 10,
          'unitCode': 'C62',
          'netAmount': 100.0,
          'netPrice': 10.0,
          'poLineId': poLineId,
          'matchedBy': poLineId == null ? null : 'ORDER_LINE',
        }
      ],
    };

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  final List<List<int>> uploads = [];
  List<Map<String, dynamic>> inbox = [];
  Map<String, dynamic> detail = _einvoice();
  Map<String, dynamic> tenant = {
    'id': 't',
    'name': 'Corner Shop',
    'status': 'ACTIVE',
    'currency': 'GBP',
    'country': 'GB',
  };
  int failStatus = 0;
  String failMessage = '';

  List<RequestOptions> sent(String method, String pathEnd) =>
      requests.where((o) => o.method == method && o.path.endsWith(pathEnd)).toList();

  static Map<String, dynamic> body(RequestOptions o) =>
      (o.data is String ? jsonDecode(o.data as String) : o.data) as Map<String, dynamic>;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    ResponseBody json(Object b, [int status = 200]) => ResponseBody.fromString(
        jsonEncode(b), status,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    final p = o.path;
    if (o.method != 'GET' && failStatus != 0) {
      await s?.drain<void>();
      return json({
        'error': {'code': 'PURCHASE_EINVOICE_NOT_AN_INVOICE', 'message': failMessage}
      }, failStatus);
    }
    if (p.endsWith('/admin/tenant')) {
      if (o.method == 'PUT') tenant = {...tenant, ...body(o)};
      return json({'data': tenant});
    }
    if (p.endsWith('/suppliers')) {
      return json({
        'data': [
          {'id': 's-1', 'name': 'Acme Foods Ltd', 'paymentTermsDays': 30}
        ]
      });
    }
    if (p.endsWith('/purchase-orders')) {
      return json({
        'data': [
          {
            'id': 'po-1', 'supplierId': 's-1', 'storeId': 'st', 'status': 'RECEIVED',
            'currency': 'GBP', 'totalNet': 100, 'totalVat': 20, 'totalGross': 120,
            'createdAt': '2026-09-01T09:00:00Z',
          }
        ]
      });
    }
    if (p.endsWith('/variants/resolve')) {
      return json({
        'data': [
          {'variantId': 'v-oat-0001', 'productName': 'Oat loaf', 'sku': 'OAT-1'},
          {'variantId': 'v-rye-0002', 'productName': 'Rye loaf', 'sku': 'RYE-2'},
        ],
      });
    }
    if (p.endsWith('/purchase-orders/po-1/lines')) {
      return json({
        'data': [
          {'id': 'pl-1', 'variantId': 'v-oat-0001', 'qty': 10, 'unitPrice': 9.5},
          {'id': 'pl-2', 'variantId': 'v-rye-0002', 'qty': 10, 'unitPrice': 10},
        ]
      });
    }
    if (p.endsWith('/document')) {
      return ResponseBody.fromBytes(utf8.encode('<Invoice/>'), 200, headers: {
        Headers.contentTypeHeader: ['application/xml']
      });
    }
    if (p.endsWith('/e-invoices') && o.method == 'POST') {
      uploads.add([for (final chunk in await s!.toList()) ...chunk]);
      return json({'data': detail}, 201);
    }
    if (p.endsWith('/match')) {
      detail = _einvoice(status: 'CAPTURED', problem: null, poLineId: 'pl-2');
      return json({'data': detail});
    }
    if (p.endsWith('/refuse')) {
      detail = {...detail, 'status': 'REFUSED', 'problem': null, 'decisionReason': body(o)['reason']};
      return json({'data': detail});
    }
    if (p.endsWith('/e-invoices')) return json({'data': inbox});
    if (p.contains('/e-invoices/')) return json({'data': detail});
    return json({'data': []});
  }
}

Future<_Server> _pump(
  WidgetTester tester, {
  String role = 'MANAGER',
  _Server? server,
  PickedDocument? pick,
  List<String>? saved,
}) async {
  tester.view.physicalSize = const Size(1400, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final srv = server ?? _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = srv;
  await tester.pumpWidget(ProviderScope(
    // A fresh scope per pump: a ProviderScope keeps the overrides it was born with.
    key: UniqueKey(),
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
      eInvoicePickerProvider.overrideWithValue(() async => pick),
      eInvoiceSaverProvider.overrideWithValue((name, bytes) async => saved?.add(name)),
    ],
    child: const MaterialApp(home: ProcurementScreen()),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('E-invoices'));
  await tester.pumpAndSettle();
  return srv;
}

Future<void> _open(WidgetTester tester, String id) async {
  await tester.tap(find.byKey(Key('einvoice-$id')));
  await tester.pumpAndSettle();
}

Future<void> _upload(WidgetTester tester) async {
  await tester.tap(find.byKey(const Key('einvoice-upload')));
  await tester.pumpAndSettle();
}

void main() {
  // Dates are written with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);
  testWidgets('what waits comes ahead of what was captured, with its reason in words',
      (tester) async {
    final server = _Server()
      ..inbox = [
        _einvoice(id: 'e-done', number: 'INV-1', status: 'CAPTURED', problem: null),
        _einvoice(id: 'e-wait', number: 'INV-2'),
      ];
    await _pump(tester, server: server);

    expect(tester.getTopLeft(find.byKey(const Key('einvoice-e-wait'))).dy,
        lessThan(tester.getTopLeft(find.byKey(const Key('einvoice-e-done'))).dy));
    expect(find.text('Lines to match'), findsOneWidget);
    expect(find.text('Captured'), findsOneWidget);
    expect(find.text('NEEDS_LINES'), findsNothing, reason: 'the constant is not the sentence');
    expect(find.text('Line 1 is not on the order: pick its order line'), findsOneWidget);
  });

  testWidgets('a delivered document says which network brought it, and its reference when opened',
      (tester) async {
    final delivered = _einvoice(id: 'e-ap', number: 'INV-7', channel: 'PEPPOL', deliveryRef: 'AP-MSG-77');
    final server = _Server()
      ..inbox = [delivered, _einvoice(id: 'e-up', number: 'INV-8')]
      ..detail = delivered;
    await _pump(tester, server: server);

    expect(find.textContaining('via Peppol'), findsOneWidget);
    expect(find.textContaining('via'), findsOneWidget, reason: 'an upload needs no saying');
    await _open(tester, 'e-ap');
    expect(find.textContaining('ref AP-MSG-77'), findsOneWidget);
  });

  testWidgets('an empty inbox says what to upload, and a missing address says why it matters',
      (tester) async {
    await _pump(tester);
    expect(find.text('No e-invoices received yet'), findsOneWidget);
    expect(find.text('No e-invoicing address yet'), findsOneWidget);
    expect(find.textContaining('meant for another business is set aside'), findsOneWidget);
  });

  testWidgets('a manager sets the receiving address; half an address or a bad VAT number is not sent',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('einvoice-identity-edit')));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('identity-vat')), '123456789');
    await tester.enterText(find.byKey(const Key('identity-einvoice-scheme')), '0088');
    await tester.tap(find.byKey(const Key('identity-save')));
    await tester.pumpAndSettle();
    expect(find.text('Country prefix and number, e.g. GB123456789'), findsOneWidget);
    expect(find.text('Give the identifier too'), findsOneWidget);
    expect(server.sent('PUT', '/admin/tenant'), isEmpty);

    await tester.enterText(find.byKey(const Key('identity-vat')), 'GB 123 4567 89');
    await tester.enterText(find.byKey(const Key('identity-einvoice-id')), '5790000435951');
    await tester.tap(find.byKey(const Key('identity-save')));
    await tester.pumpAndSettle();

    final put = _Server.body(server.sent('PUT', '/admin/tenant').single);
    expect(put['businessName'], 'Corner Shop', reason: 'the name travels unchanged');
    expect(put['einvoiceScheme'], '0088');
    expect(put['einvoiceId'], '5790000435951');
    expect(put['vatNumber'], 'GB 123 4567 89');
    expect(find.text('Suppliers send e-invoices to 0088:5790000435951'), findsOneWidget);
  });

  testWidgets('a storekeeper can match but is offered neither the address nor a refusal',
      (tester) async {
    final server = _Server()..inbox = [_einvoice()];
    await _pump(tester, role: 'STOREKEEPER', server: server);
    expect(find.byKey(const Key('einvoice-identity-edit')), findsNothing);
    await _open(tester, 'e-1');
    expect(find.byKey(const Key('einvoice-match')), findsOneWidget);
    expect(find.byKey(const Key('einvoice-refuse')), findsNothing);
  });

  testWidgets('an uploaded XML goes as its own bytes, and one that waits opens with its reason',
      (tester) async {
    final xml = Uint8List.fromList(
        utf8.encode('<Invoice xmlns="urn:oasis:names:specification:ubl:schema:xsd:Invoice-2"/>'));
    final server = await _pump(tester, pick: PickedDocument(xml, 'INV-9.xml'));
    await _upload(tester);

    final post = server.sent('POST', '/e-invoices').single;
    expect(post.contentType, 'application/xml');
    expect(server.uploads.single, xml, reason: 'the document is sent byte for byte');
    expect(find.byType(AlertDialog), findsOneWidget);
    expect(find.byKey(const Key('einvoice-problem')), findsOneWidget);
  });

  testWidgets('a file over 20 MB, or an empty one, never leaves the browser', (tester) async {
    var server =
        await _pump(tester, pick: PickedDocument(Uint8List(maxEInvoiceBytes + 1), 'huge.pdf'));
    await _upload(tester);
    expect(find.text('huge.pdf is larger than an e-invoice may be (20 MB).'), findsOneWidget);
    expect(server.sent('POST', '/e-invoices'), isEmpty);

    server = await _pump(tester, pick: PickedDocument(Uint8List(0), 'blank.xml'));
    await _upload(tester);
    expect(find.text('blank.xml is empty.'), findsOneWidget);
    expect(server.sent('POST', '/e-invoices'), isEmpty);
  });

  testWidgets('a document the server cannot read is refused in its words, and nothing opens',
      (tester) async {
    final server = _Server()
      ..failStatus = 400
      ..failMessage = 'This is not an EN 16931 invoice or credit note';
    await _pump(tester,
        server: server, pick: PickedDocument(Uint8List.fromList(utf8.encode('<html/>')), 'page.xml'));
    await _upload(tester);
    expect(find.text('This is not an EN 16931 invoice or credit note'), findsOneWidget);
    expect(find.byType(AlertDialog), findsNothing);
  });

  testWidgets('matching sends only the line the person chose, and closes with what it became',
      (tester) async {
    final server = _Server()..inbox = [_einvoice()];
    await _pump(tester, server: server);
    await _open(tester, 'e-1');

    await tester.tap(find.byKey(const Key('einvoice-line-1')));
    await tester.pumpAndSettle();
    // The order's lines by their product's name.
    expect(find.textContaining('v-rye-0002'), findsNothing);
    await tester.tap(find.textContaining('Rye loaf').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('einvoice-match')));
    await tester.pumpAndSettle();

    final body = _Server.body(server.sent('POST', '/match').single);
    expect(body['lines'], [
      {'position': 1, 'poLineId': 'pl-2'}
    ]);
    expect(body['remember'], isTrue);
    expect(body.containsKey('supplierId'), isFalse, reason: 'what was found is not sent back');
    expect(body.containsKey('poId'), isFalse);
    expect(find.byType(AlertDialog), findsNothing);
    expect(find.text('Invoice INV-9: Captured.'), findsOneWidget);
  });

  testWidgets('a non-compliant invoice lists the rules it breaks and can only be refused',
      (tester) async {
    final server = _Server()
      ..detail = _einvoice(status: 'NOT_COMPLIANT', problem: 'It breaks a rule of EN 16931', violations: [
        {'rule': 'BR-CO-15', 'severity': 'FATAL', 'message': 'Total with VAT is not net plus VAT'}
      ]);
    server.inbox = [server.detail];
    await _pump(tester, server: server);
    await _open(tester, 'e-1');

    expect(find.text('BR-CO-15: Total with VAT is not net plus VAT'), findsOneWidget);
    expect(find.text('Breaks the e-invoice rules'), findsWidgets);
    expect(find.byKey(const Key('einvoice-match')), findsNothing);
    expect(find.byKey(const Key('einvoice-refuse')), findsOneWidget);
  });

  testWidgets('refusing needs a reason, and a double tap sends it once', (tester) async {
    final server = _Server()..inbox = [_einvoice()];
    await _pump(tester, server: server);
    await _open(tester, 'e-1');
    await tester.tap(find.byKey(const Key('einvoice-refuse')));
    await tester.pumpAndSettle();

    final confirm = find.byKey(const Key('einvoice-refuse-confirm'));
    await tester.tap(confirm);
    await tester.pumpAndSettle();
    expect(find.text('Say why it is refused.'), findsOneWidget);
    expect(server.sent('POST', '/refuse'), isEmpty);

    await tester.enterText(find.byKey(const Key('einvoice-refuse-reason')), 'Not our order');
    await tester.tap(confirm);
    await tester.tap(confirm, warnIfMissed: false);
    await tester.pumpAndSettle();

    expect(server.sent('POST', '/refuse'), hasLength(1));
    expect(_Server.body(server.sent('POST', '/refuse').single)['reason'], 'Not our order');
    expect(find.text('Invoice INV-9: Refused.'), findsOneWidget);
  });

  testWidgets('the original is saved under a name the supplier cannot turn into a path',
      (tester) async {
    final saved = <String>[];
    final server = _Server()
      ..detail = _einvoice(number: '../../etc/passwd', status: 'CAPTURED', problem: null);
    server.inbox = [server.detail];
    await _pump(tester, server: server, saved: saved);
    await _open(tester, 'e-1');
    await tester.tap(find.byKey(const Key('einvoice-download')));
    await tester.pumpAndSettle();

    expect(saved, ['_.._etc_passwd.xml']);
    expect(server.sent('GET', '/e-invoices/e-1/document'), hasLength(1));
    expect(find.text('Captured as a supplier invoice: it is under Invoices.'), findsOneWidget);
  });

  test('an e-invoicing address is a four-digit scheme and an identifier, or nothing', () {
    expect(validEinvoiceScheme('', idKeyed: false), isNull);
    expect(validEinvoiceScheme(' ', idKeyed: true), 'Give the scheme too');
    expect(validEinvoiceScheme('88', idKeyed: true), 'Four digits, e.g. 0088');
    expect(validEinvoiceScheme('0088', idKeyed: true), isNull);
    expect(validEinvoiceId('', schemeKeyed: true), 'Give the identifier too');
    expect(validEinvoiceId('579 000', schemeKeyed: true), 'No spaces');
    expect(validEinvoiceId('x' * 129, schemeKeyed: true), 'Up to 128 characters');
    expect(validEinvoiceId('5790000435951', schemeKeyed: true), isNull);
    expect(PickedDocument(Uint8List(1), 'facture.PDF').contentType, 'application/pdf');
    expect(PickedDocument(Uint8List(1), 'invoice.xml').contentType, 'application/xml');
    expect(
        eInvoiceFileName(SupplierEInvoice.fromJson({
          'id': 'e',
          'container': 'PDF',
          'syntax': 'CII',
          'status': 'CAPTURED',
          'invoiceNumber': '',
        })),
        'e-invoice.pdf');
  });
}
