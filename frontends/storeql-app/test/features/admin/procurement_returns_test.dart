import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/procurement_screen.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// Return to vendor and the debit note (07.8): the reverse SJ-D3 named. From a
// received order a storekeeper sends goods back with a reason, the server
// prices the debit note and refuses more than can go back, and a manager
// records the supplier's credit note against it. Nothing here edits a return.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

const _po = 'po-12345678-abcd';
const _variant = 'v-abcdef123456';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int raiseStatus = 201;
  bool credited = false;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    String body = '{"data":[]}';
    var status = 200;
    if (o.path.endsWith('/purchase-orders')) {
      body =
          '{"data":[{"id":"$_po","supplierId":"s-1","storeId":"st-1","status":"RECEIVED","currency":"GBP",'
          '"totalNet":25.00,"totalVat":5.00,"totalGross":30.00,"createdAt":"2026-09-01T10:00:00Z"}]}';
    } else if (o.path.endsWith('/purchase-orders/$_po/lines')) {
      body =
          '{"data":[{"id":"l-1","variantId":"$_variant","qty":10,"unitPrice":2.50,"vatCode":"T1"}]}';
    } else if (o.path.endsWith('/purchase-orders/$_po/progress')) {
      body =
          '{"data":[{"variantId":"$_variant","qtyOrdered":10,"qtyReceived":10,"qtyOutstanding":0,"qtyReturned":3}]}';
    } else if (o.path.endsWith('/vendor-returns') && o.method == 'POST') {
      status = raiseStatus;
      body = status == 201
          ? '{"data":{"id":"r-2","poId":"$_po","status":"RAISED","reason":"QUALITY","currency":"GBP","netAmount":5.00,"vatAmount":1.00,"grossAmount":6.00,"debitNoteNumber":"DN-000002","raisedAt":"2026-09-12T10:00:00Z","lines":[{"variantId":"$_variant","qty":2,"unitPrice":2.50,"lineNet":5.00}]}}'
          : '{"error":{"code":"PURCHASE_RTV_OVER_RETURN","message":"variant $_variant: 8 to return against 7 received and not yet returned"}}';
    } else if (o.path.endsWith('/vendor-returns')) {
      body =
          '{"data":[{"id":"r-1","poId":"$_po","status":"${credited ? 'CREDITED' : 'RAISED'}","reason":"DAMAGED","notes":"crushed","currency":"GBP",'
          '"netAmount":7.50,"vatAmount":1.50,"grossAmount":9.00,"debitNoteNumber":"DN-000001","raisedAt":"2026-09-10T10:00:00Z",'
          '${credited ? '"creditNoteNumber":"CN-77","creditNoteDate":"2026-09-20","creditAmount":9.00,' : ''}'
          '"lines":[{"variantId":"$_variant","qty":3,"unitPrice":2.50,"lineNet":7.50}]}]}';
    } else if (o.path.endsWith('/vendor-returns/r-1/credit')) {
      credited = true;
      body =
          '{"data":{"id":"r-1","poId":"$_po","status":"CREDITED","reason":"DAMAGED","currency":"GBP","netAmount":7.50,"vatAmount":1.50,"grossAmount":9.00,"debitNoteNumber":"DN-000001","raisedAt":"2026-09-10T10:00:00Z","creditNoteNumber":"CN-77","creditNoteDate":"2026-09-20","creditAmount":9.00,"lines":[]}}';
    }
    return ResponseBody.fromString(
      body,
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }
}

Future<_Server> _openPo(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1200, 1800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
      child: const MaterialApp(home: ProcurementScreen()),
    ),
  );
  await tester.pumpAndSettle();
  await tester.tap(find.textContaining('#'));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  setUpAll(initializeDateFormatting);
  testWidgets('a received order shows what went back and offers a return', (
    tester,
  ) async {
    await _openPo(tester);
    expect(find.textContaining('3 returned'), findsOneWidget);
    expect(find.text('Returned to vendor'), findsOneWidget);
    expect(
      find.textContaining('DN-000001 · Damaged in transit'),
      findsOneWidget,
    );
    expect(
      find.textContaining("awaiting the supplier's credit note"),
      findsOneWidget,
    );
    expect(find.byKey(const Key('po-return-to-vendor')), findsOneWidget);
    expect(find.byKey(const Key('vendor-return-credit-r-1')), findsOneWidget);
  });

  testWidgets(
    'a storekeeper sends goods back with a reason; the server prices the debit note',
    (tester) async {
      final server = await _openPo(tester);
      await tester.tap(find.byKey(const Key('po-return-to-vendor')));
      await tester.pumpAndSettle();
      expect(
        find.textContaining('received 10 · returned 3 · up to 7 can go back'),
        findsOneWidget,
      );
      // Nothing entered: refused locally, no request made.
      await tester.tap(find.byKey(const Key('rtv-submit')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('rtv-error')), findsOneWidget);
      expect(server.requests.where((r) => r.method == 'POST'), isEmpty);

      await tester.tap(find.byKey(const Key('rtv-reason')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Quality rejected').last);
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('rtv-qty-$_variant')), '2');
      await tester.enterText(find.byKey(const Key('rtv-notes')), 'off spec');
      await tester.tap(find.byKey(const Key('rtv-submit')));
      await tester.pumpAndSettle();
      expect(
        find.byType(AlertDialog),
        findsOneWidget,
        reason: 'the return dialog closed; the PO dialog stays',
      );
      expect(
        find.textContaining('debit note DN-000002 raised'),
        findsOneWidget,
      );
      final post = server.requests.lastWhere((r) => r.method == 'POST');
      expect(post.path, endsWith('/vendor-returns'));
      final body = post.data is String
          ? jsonDecode(post.data as String)
          : post.data;
      expect(body['poId'], _po);
      expect(body['reason'], 'QUALITY');
      expect(body['notes'], 'off spec');
      expect(body['lines'], [
        {'variantId': _variant, 'qty': 2.0},
      ]);
      // No price is typed here: the server prices the debit note at the order's prices.
      expect(body.containsKey('unitPrice'), isFalse);
    },
  );

  testWidgets(
    'more than can go back is refused by the server and the dialog says so',
    (tester) async {
      (await _openPo(tester)).raiseStatus = 422;
      await tester.tap(find.byKey(const Key('po-return-to-vendor')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('rtv-qty-$_variant')), '8');
      await tester.tap(find.byKey(const Key('rtv-submit')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('rtv-error')), findsOneWidget);
      expect(find.textContaining('8 to return against 7'), findsOneWidget);
      expect(
        find.byKey(const Key('rtv-submit')),
        findsOneWidget,
        reason: 'the dialog stays open to fix it',
      );
    },
  );

  testWidgets(
    "a manager records the supplier's credit note and the return reads credited",
    (tester) async {
      final server = await _openPo(tester);
      await tester.tap(find.byKey(const Key('vendor-return-credit-r-1')));
      await tester.pumpAndSettle();
      expect(find.text('Credit note for DN-000001'), findsOneWidget);
      expect(find.textContaining('The debit note asked for'), findsOneWidget);
      await tester.enterText(find.byKey(const Key('credit-number')), 'CN-77');
      await tester.enterText(
        find.byKey(const Key('credit-date')),
        '2026-09-20',
      );
      await tester.tap(find.byKey(const Key('credit-submit')));
      await tester.pumpAndSettle();
      final post = server.requests.lastWhere((r) => r.method == 'POST');
      expect(post.path, endsWith('/vendor-returns/r-1/credit'));
      final body = post.data is String
          ? jsonDecode(post.data as String)
          : post.data;
      expect(body['creditNoteNumber'], 'CN-77');
      expect(body['creditNoteDate'], '2026-09-20');
      expect(body['amount'], 9.0);
      expect(
        find.textContaining('credit note CN-77 · 20 Sept 2026'),
        findsOneWidget,
      );
      expect(
        find.byKey(const Key('vendor-return-credit-r-1')),
        findsNothing,
        reason: 'a credited return offers no second credit note',
      );
      expect(
        server.requests.where((r) => r.method == 'DELETE' || r.method == 'PUT'),
        isEmpty,
      );
    },
  );
}
