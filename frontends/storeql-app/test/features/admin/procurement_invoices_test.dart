import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/procurement_screen.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The three-way match on screen: ordered against received against invoiced.
//
// The server answers with variance CODES — INVOICED_ABOVE_RECEIVED and friends.
// A buyer about to ring a supplier needs the sentence, not the constant, and
// they need to know WHICH line disagreed. A status badge alone answers neither,
// which is why these tests assert the words and the figures rather than that a
// widget rendered.
//
// This is also the first widget test procurement_screen has ever had — it was
// built across three commits with none, which is called out in the artifact
// rather than left quiet.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _StubAdapter implements HttpClientAdapter {
  String body = '{"data":[]}';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions options, Stream<List<int>>? stream, Future<void>? cancel) async {
    return ResponseBody.fromString(body, 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

String _invoice({
  required String number,
  required String status,
  required String variances,
  double qtyOrdered = 100,
  double qtyReceived = 60,
  double qtyInvoiced = 60,
  double orderedPrice = 2.50,
  double invoicedPrice = 2.50,
}) =>
    '''
{"id":"i-$number","poId":"po-1111111111","invoiceNumber":"$number",
 "invoiceDate":"2026-02-01","currency":"GBP","netAmount":150,"vatAmount":30,
 "grossAmount":180,"status":"$status","lines":[
   {"variantId":"v-abcdef123456","qtyOrdered":$qtyOrdered,"qtyReceived":$qtyReceived,
    "qtyInvoicedBefore":0,"qtyInvoiced":$qtyInvoiced,
    "orderedUnitPrice":$orderedPrice,"invoicedUnitPrice":$invoicedPrice,
    "variances":[$variances]}]}''';

Future<void> _pump(WidgetTester tester, String body) async {
  final adapter = _StubAdapter()..body = body;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: ProcurementScreen()),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Invoices'));
  await tester.pumpAndSettle();
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  // An invoice's dates are shown as dates, which needs the locale's date data.
  setUpAll(initializeDateFormatting);

  testWidgets('a clean match shows no variance and does not shout', (tester) async {
    await _pump(tester, '{"data":[${_invoice(number: "INV-1", status: "MATCHED", variances: "")}]}');

    expect(find.text('INV-1'), findsOneWidget);
    // The status in a word, not the server's constant.
    expect(find.text('Matched'), findsOneWidget);
    expect(find.text('MATCHED'), findsNothing);
    expect(find.textContaining('Billed for more than arrived'), findsNothing);
  });

  testWidgets('a variance is shown in words, not as the server\'s constant',
      (tester) async {
    await _pump(
        tester,
        '{"data":[${_invoice(number: "INV-2", status: "FLAGGED", variances: '"INVOICED_ABOVE_RECEIVED"', qtyInvoiced: 100)}]}');

    // The sentence a buyer can act on…
    expect(find.text('Billed for more than arrived'), findsOneWidget);
    // …not the constant the API speaks in.
    expect(find.text('INVOICED_ABOVE_RECEIVED'), findsNothing);
    // The badge too: a word, in the tone of something to act on.
    expect(find.text('FLAGGED'), findsNothing);
    final badge = tester.widget<StatusBadge>(find.widgetWithText(StatusBadge, 'Flagged'));
    expect(badge.tone, StatusTone.warning);
  });

  testWidgets('all three documents\' figures are on the row, not just a badge',
      (tester) async {
    await _pump(
        tester,
        '{"data":[${_invoice(number: "INV-3", status: "FLAGGED", variances: '"INVOICED_ABOVE_RECEIVED"', qtyInvoiced: 100)}]}');

    expect(find.text('Ordered'), findsOneWidget);
    expect(find.text('Received'), findsOneWidget);
    expect(find.text('Invoiced'), findsOneWidget);
    // 100 ordered, 60 received, 100 invoiced — the three numbers that make the
    // variance obvious without reading the chip.
    expect(find.text('60'), findsOneWidget);
    expect(find.text('100'), findsNWidgets(2));
  });

  testWidgets('a price variance shows both prices, so the gap is visible',
      (tester) async {
    await _pump(
        tester,
        '{"data":[${_invoice(number: "INV-4", status: "FLAGGED", variances: '"PRICE_ABOVE_ORDER"', invoicedPrice: 2.75)}]}');

    expect(find.text('£2.50 → £2.75'), findsOneWidget);
    expect(find.text('Charged above the agreed price'), findsOneWidget);
  });

  testWidgets('flagged invoices sort ahead of matched ones', (tester) async {
    await _pump(
        tester,
        '{"data":['
        '${_invoice(number: "INV-OK", status: "MATCHED", variances: "")},'
        '${_invoice(number: "INV-BAD", status: "FLAGGED", variances: '"NOT_RECEIVED"', qtyReceived: 0)}'
        ']}');

    // The exceptions are the entire point of the control; a list in date order
    // buries them behind the invoices nobody needs to read.
    final bad = tester.getTopLeft(find.text('INV-BAD')).dy;
    final ok = tester.getTopLeft(find.text('INV-OK')).dy;
    expect(bad, lessThan(ok));
  });

  testWidgets('an empty list says what to do next rather than just being blank',
      (tester) async {
    await _pump(tester, '{"data":[]}');
    expect(find.text('No supplier invoices yet'), findsOneWidget);
    expect(find.textContaining('Capture one from a purchase order'), findsOneWidget);
  });

  lifecycleTests();
}

// ---------------------------------------------------------------------------
// The accounting seam (07.7): due dates, the header check, the decision on a
// flagged invoice, and the badge for what a manager decided.
// ---------------------------------------------------------------------------

class _Auth extends AuthNotifier {
  final String role;
  _Auth(this.role);

  @override
  Future<AuthState> build() async => AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'user-1',
        tenantId: 'tenant-1',
        roles: [role],
      );
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  String listBody = '{"data":[]}';
  int resolveStatus = 200;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    // product-svc naming the lines' variants: none known here.
    if (o.path.endsWith('/variants/resolve')) {
      return ResponseBody.fromString('{"data":[]}', 200,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    }
    requests.add(o);
    if (o.path.endsWith('/resolve')) {
      final body = resolveStatus == 200
          ? '{"data":{"id":"i-INV-9","status":"APPROVED"}}'
          : '{"error":{"code":"PURCHASE_INVOICE_ALREADY_RESOLVED","message":"this invoice has already been decided"}}';
      return ResponseBody.fromString(body, resolveStatus,
          headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
    }
    return ResponseBody.fromString(listBody, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

String _lifecycleInvoice({
  required String number,
  required String status,
  String variances = '',
  String headerVariances = '',
  String? dueDate = '2026-03-03',
  bool posted = true,
  double statedGross = 180,
  String? reason,
}) =>
    '''
{"id":"i-$number","poId":"po-1111111111","invoiceNumber":"$number",
 "invoiceDate":"2026-02-01","currency":"GBP","netAmount":150,"vatAmount":30,
 "grossAmount":180,"status":"$status","statedGross":$statedGross,
 "headerVariances":[$headerVariances],${dueDate == null ? '' : '"dueDate":"$dueDate",'}
 ${posted ? '"postedAt":"2026-02-01T10:00:00Z",' : ''}
 "payable":${status == 'MATCHED' || status == 'APPROVED'},
 ${reason == null ? '' : '"resolutionReason":"$reason",'}
 "lines":[
   {"variantId":"v-abcdef123456","qtyOrdered":100,"qtyReceived":60,
    "qtyInvoicedBefore":0,"qtyInvoiced":60,
    "orderedUnitPrice":2.50,"invoicedUnitPrice":2.50,
    "variances":[$variances]}]}''';

Future<_Server> _pumpAs(WidgetTester tester, String role, String body,
    {int resolveStatus = 200}) async {
  final server = _Server()
    ..listBody = body
    ..resolveStatus = resolveStatus;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  // A fresh key: pumpWidget UPDATES a root of the same type, and a ProviderScope
  // keeps the container it was born with, overrides included. Without the key a
  // second pump in the same test would keep the first pump's login.
  await tester.pumpWidget(ProviderScope(
    key: UniqueKey(),
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => _Auth(role)),
    ],
    child: const MaterialApp(home: Scaffold(body: ProcurementScreen())),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Invoices'));
  await tester.pumpAndSettle();
  return server;
}

void lifecycleTests() {
  testWidgets('the due date and the posting are on the card, not in a detail view',
      (tester) async {
    await _pumpAs(tester, 'OWNER',
        '{"data":[${_lifecycleInvoice(number: "INV-5", status: "MATCHED")}]}');
    // Both dates as dates, not ISO strings.
    expect(find.textContaining('1 Feb 2026 · due 3 Mar 2026'), findsOneWidget);
    expect(find.textContaining('2026-03-03'), findsNothing);
    expect(find.textContaining('posted'), findsOneWidget);
  });

  testWidgets('a stated total that does not add up is a sentence with both figures',
      (tester) async {
    await _pumpAs(
        tester,
        'OWNER',
        '{"data":[${_lifecycleInvoice(number: "INV-6", status: "FLAGGED", headerVariances: '"TOTAL_MISMATCH"', statedGross: 181)}]}');
    expect(find.textContaining('The stated total does not add up'), findsOneWidget);
    expect(find.textContaining('£181.00 stated, £180.00 from the lines'), findsOneWidget);
    expect(find.text('TOTAL_MISMATCH'), findsNothing);
  });

  testWidgets('a manager sees Approve and Reject on a flagged invoice; a storekeeper does not',
      (tester) async {
    final flagged =
        '{"data":[${_lifecycleInvoice(number: "INV-7", status: "FLAGGED", variances: '"PRICE_ABOVE_ORDER"')}]}';
    await _pumpAs(tester, 'MANAGER', flagged);
    expect(find.text('Approve for payment'), findsOneWidget);
    expect(find.text('Reject'), findsOneWidget);

    await _pumpAs(tester, 'STOREKEEPER', flagged);
    expect(find.text('Approve for payment'), findsNothing);
    expect(find.text('Reject'), findsNothing);
  });

  testWidgets('a matched, approved or rejected invoice offers no decision', (tester) async {
    await _pumpAs(
        tester,
        'OWNER',
        '{"data":['
        '${_lifecycleInvoice(number: "INV-A", status: "APPROVED", reason: "Supplier confirmed")},'
        '${_lifecycleInvoice(number: "INV-R", status: "REJECTED", reason: "Billed for six that never came")},'
        '${_lifecycleInvoice(number: "INV-M", status: "MATCHED")}'
        ']}');
    expect(find.text('Approve for payment'), findsNothing);
    // Each decision in a word, in its tone: a rejection is a refusal, not a closed file.
    expect(find.text('Approved'), findsOneWidget);
    expect(find.text('Rejected'), findsOneWidget);
    expect(find.text('APPROVED'), findsNothing);
    expect(find.text('REJECTED'), findsNothing);
    expect(tester.widget<StatusBadge>(find.widgetWithText(StatusBadge, 'Approved')).tone,
        StatusTone.success);
    expect(tester.widget<StatusBadge>(find.widgetWithText(StatusBadge, 'Rejected')).tone,
        StatusTone.error);
    // The reason travels with the decision.
    await tester.tap(find.text('INV-R'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Rejected: Billed for six that never came'), findsOneWidget);
  });

  testWidgets('approving sends the action and the reason, and needs the reason first',
      (tester) async {
    final server = await _pumpAs(
        tester,
        'OWNER',
        '{"data":[${_lifecycleInvoice(number: "INV-9", status: "FLAGGED", variances: '"PRICE_ABOVE_ORDER"')}]}');
    await tester.tap(find.byKey(const Key('approve-invoice-i-INV-9')));
    await tester.pumpAndSettle();
    expect(find.text('Approve INV-9'), findsOneWidget);

    // No reason: the button is not even enabled, and nothing was sent.
    final button = tester.widget<FilledButton>(find.byKey(const Key('resolve-invoice-submit')));
    expect(button.onPressed, isNull);
    expect(server.requests.where((r) => r.path.endsWith('/resolve')), isEmpty);

    await tester.enterText(
        find.byKey(const Key('resolve-invoice-reason')), '  Supplier confirmed the price rise  ');
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('resolve-invoice-submit')));
    await tester.pumpAndSettle();

    final sent = server.requests.singleWhere((r) => r.path.endsWith('/resolve'));
    expect(sent.method, 'POST');
    expect(sent.path, contains('/purchase-svc/supplier-invoices/i-INV-9/resolve'));
    expect(sent.data['action'], 'APPROVE');
    expect(sent.data['reason'], 'Supplier confirmed the price rise');
    expect(find.text('Approve INV-9'), findsNothing);
    expect(find.textContaining('approved for payment'), findsOneWidget);
  });

  testWidgets('rejecting says what it does, and a refusal is shown in words', (tester) async {
    final server = await _pumpAs(
        tester,
        'OWNER',
        '{"data":[${_lifecycleInvoice(number: "INV-9", status: "FLAGGED", variances: '"NOT_RECEIVED"')}]}',
        resolveStatus: 409);
    await tester.tap(find.byKey(const Key('reject-invoice-i-INV-9')));
    await tester.pumpAndSettle();
    expect(find.text('Reject INV-9'), findsOneWidget);
    expect(find.textContaining('Its posting is reversed'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('resolve-invoice-reason')), 'Never arrived');
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('resolve-invoice-submit')));
    await tester.pumpAndSettle();

    expect(server.requests.singleWhere((r) => r.path.endsWith('/resolve')).data['action'],
        'REJECT');
    // Still open, the server's sentence on screen, not a code.
    expect(find.text('Reject INV-9'), findsOneWidget);
    expect(find.text('this invoice has already been decided'), findsOneWidget);
  });

  testWidgets('a double tap on the decision sends it once', (tester) async {
    final server = await _pumpAs(
        tester,
        'OWNER',
        '{"data":[${_lifecycleInvoice(number: "INV-9", status: "FLAGGED", variances: '"NOT_RECEIVED"')}]}');
    await tester.tap(find.byKey(const Key('approve-invoice-i-INV-9')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('resolve-invoice-reason')), 'fine');
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('resolve-invoice-submit')));
    await tester.pump();
    final again = tester.widget<FilledButton>(find.byKey(const Key('resolve-invoice-submit')));
    expect(again.onPressed, isNull);
    await tester.pumpAndSettle();
    expect(server.requests.where((r) => r.path.endsWith('/resolve')).length, 1);
  });
}
