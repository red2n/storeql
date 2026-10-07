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

  /// The order's status, and the return's currency and gross as purchase-svc
  /// keeps them.
  String poStatus = 'RECEIVED';
  String currency = 'GBP';
  String gross = '9.00';

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
          '{"data":[{"id":"$_po","supplierId":"s-1","storeId":"st-1","status":"$poStatus","currency":"$currency",'
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
          '{"data":[{"id":"r-1","poId":"$_po","status":"${credited ? 'CREDITED' : 'RAISED'}","reason":"DAMAGED","notes":"crushed","currency":"$currency",'
          '"netAmount":7.50,"vatAmount":1.50,"grossAmount":$gross,"debitNoteNumber":"DN-000001","raisedAt":"2026-09-10T10:00:00Z",'
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

Future<_Server> _openPo(WidgetTester tester,
    {String status = 'RECEIVED', String currency = 'GBP', String gross = '9.00'}) async {
  tester.view.physicalSize = const Size(1200, 1800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server()
    ..poStatus = status
    ..currency = currency
    ..gross = gross;
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
        {'variantId': _variant, 'qty': '2'},
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
      // The plain decimal shown, as a string: JSON-B reads it exactly.
      expect(body['amount'], '9');
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

  // The amount credited is read the way the app's language writes a number,
  // with the shared amount reader. One it cannot read is refused under the
  // field and nothing is sent: sent as null, purchase-svc recorded the debit
  // note's whole gross as credited, and Romanian's 1.250 went as 1.25.
  group('the amount credited is read as typed, or refused', () {
    Future<void> openCredit(WidgetTester tester) async {
      await tester.tap(find.byKey(const Key('vendor-return-credit-r-1')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('credit-number')), 'CN-77');
      await tester.enterText(find.byKey(const Key('credit-date')), '2026-09-20');
    }

    TextField amount(WidgetTester tester) =>
        tester.widget<TextField>(find.byKey(const Key('credit-amount')));

    bool recordEnabled(WidgetTester tester) =>
        tester.widget<FilledButton>(find.byKey(const Key('credit-submit'))).onPressed != null;

    Iterable<RequestOptions> credits(_Server server) =>
        server.requests.where((r) => r.path.endsWith('/credit'));

    testWidgets('in Romanian, the gross reads 9,00 and 4,50 is sent as 4.5', (tester) async {
      Intl.defaultLocale = 'ro';
      final server = await _openPo(tester);
      await openCredit(tester);
      expect(amount(tester).controller!.text, '9,00');
      expect(amount(tester).decoration?.errorText, isNull);
      await tester.enterText(find.byKey(const Key('credit-amount')), '4,50');
      await tester.pump();
      await tester.tap(find.byKey(const Key('credit-submit')));
      await tester.pumpAndSettle();
      final body = credits(server).single.data;
      expect((body is String ? jsonDecode(body) : body)['amount'], '4.5');
    });

    for (final (locale, typed, says) in [
      ('ro', '1.250', 'Decimals go after a comma.'),
      ('en_GB', '1,250.00', 'Decimals go after a point.'),
    ]) {
      testWidgets('in $locale, $typed is refused in words and nothing is recorded',
          (tester) async {
        Intl.defaultLocale = locale;
        final server = await _openPo(tester);
        await openCredit(tester);
        await tester.enterText(find.byKey(const Key('credit-amount')), typed);
        await tester.pump();
        expect(amount(tester).decoration?.errorText,
            'Type the amount without thousands separators. $says');
        expect(recordEnabled(tester), isFalse);
        await tester.tap(find.byKey(const Key('credit-submit')));
        await tester.pumpAndSettle();
        expect(credits(server), isEmpty, reason: 'never the whole gross, never 1.25');
      });
    }
  });

  // A mark alone is no figure: refused under the field, never sent as blank,
  // which purchase-svc reads as the debit note's whole gross credited.
  group('a lone mark in the amount credited', () {
    for (final (locale, mark) in [
      ('ro', ','),
      ('en_GB', '.'),
      ('en', '.'),
      ('pl', ','),
      ('ar', '٫'),
    ]) {
      testWidgets('in $locale, "$mark" alone is refused and nothing is recorded', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _openPo(tester);
        await tester.tap(find.byKey(const Key('vendor-return-credit-r-1')));
        await tester.pumpAndSettle();
        await tester.enterText(find.byKey(const Key('credit-number')), 'CN-77');
        await tester.enterText(find.byKey(const Key('credit-amount')), mark);
        await tester.pump();
        expect(
          tester.widget<TextField>(find.byKey(const Key('credit-amount'))).decoration?.errorText,
          'Type the amount in digits.',
        );
        expect(
          tester.widget<FilledButton>(find.byKey(const Key('credit-submit'))).onPressed,
          isNull,
        );
        await tester.tap(find.byKey(const Key('credit-submit')));
        await tester.pumpAndSettle();
        expect(server.requests.where((r) => r.path.endsWith('/credit')), isEmpty,
            reason: 'never the whole gross for a lone mark');
      });
    }
  });

  // The amount credited starts at the debit note's gross, written at its
  // currency's own places and in the app's language: a Kuwaiti dinar's
  // 1.234 is never 1.23, a yen's 1500 carries no point, and either reads
  // back exactly as it was written.
  group('the gross is written at its currency\'s places', () {
    for (final (locale, currency, gross, written, sent) in [
      ('ro', 'KWD', '1.234', '1,234', '1.234'),
      ('en_GB', 'KWD', '1.234', '1.234', '1.234'),
      ('pl', 'KWD', '1.234', '1,234', '1.234'),
      ('ar', 'BHD', '12.5', '12.500', '12.5'),
      ('en', 'JPY', '1500', '1500', '1500'),
      ('ro', 'JPY', '1500', '1500', '1500'),
    ]) {
      testWidgets('in $locale, $gross $currency starts as $written and is recorded as $sent', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _openPo(tester, currency: currency, gross: gross);
        await tester.tap(find.byKey(const Key('vendor-return-credit-r-1')));
        await tester.pumpAndSettle();
        final field = tester.widget<TextField>(find.byKey(const Key('credit-amount')));
        expect(field.controller!.text, written);
        expect(field.decoration?.errorText, isNull);
        await tester.enterText(find.byKey(const Key('credit-number')), 'CN-77');
        await tester.tap(find.byKey(const Key('credit-submit')));
        await tester.pumpAndSettle();
        final body = server.requests.singleWhere((r) => r.path.endsWith('/credit')).data;
        expect((body is String ? jsonDecode(body) : body)['amount'], sent);
      });
    }
  });

  // What goes back and what was received are quantities, to three places,
  // read the way the app's language writes a number: never a comma dropped
  // (Romanian's 1,5 sent as nothing), never Polish's 1.250 taken as 1.25.
  group('quantities sent back or received are read as typed', () {
    Map<String, dynamic> lastPost(_Server server, String suffix) {
      final r = server.requests.lastWhere((r) => r.method == 'POST' && r.path.endsWith(suffix));
      return (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>;
    }

    for (final (locale, typed, sent) in [
      ('ro', '1,5', '1.5'),
      ('en_GB', '1.5', '1.5'),
      ('en', '2.25', '2.25'),
      ('pl', '1,250', '1.25'),
      ('ar', '1٫5', '1.5'),
    ]) {
      testWidgets('in $locale, $typed goes back as $sent', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _openPo(tester);
        await tester.tap(find.byKey(const Key('po-return-to-vendor')));
        await tester.pumpAndSettle();
        for (var i = 1; i <= typed.length; i++) {
          await tester.enterText(find.byKey(const Key('rtv-qty-$_variant')), typed.substring(0, i));
          await tester.pump();
        }
        await tester.tap(find.byKey(const Key('rtv-submit')));
        await tester.pumpAndSettle();
        expect(lastPost(server, '/vendor-returns')['lines'], [
          {'variantId': _variant, 'qty': sent},
        ]);
      });
    }

    for (final (locale, typed, says) in [
      ('ro', '1.5', 'Type the amount without thousands separators. Decimals go after a comma.'),
      ('en', '1,5', 'Type the amount without thousands separators. Decimals go after a point.'),
      ('pl', '1.250', 'A point may group thousands here. Type the figure without grouping, with any decimals after a comma.'),
      ('en_GB', '.', 'Type the amount in digits.'),
      ('ar', '1.2345', 'At most 3 decimal places.'),
    ]) {
      testWidgets('in $locale, $typed to send back is refused in words and nothing is raised', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _openPo(tester);
        await tester.tap(find.byKey(const Key('po-return-to-vendor')));
        await tester.pumpAndSettle();
        await tester.enterText(find.byKey(const Key('rtv-qty-$_variant')), typed);
        await tester.pump();
        expect(
          tester.widget<TextField>(find.byKey(const Key('rtv-qty-$_variant'))).decoration?.errorText,
          says,
        );
        await tester.tap(find.byKey(const Key('rtv-submit')));
        await tester.pumpAndSettle();
        expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
      });
    }

    for (final (locale, typed, sent) in [
      ('ro', '2,5', '2.5'),
      ('en_GB', '2.5', '2.5'),
      ('pl', '2,125', '2.125'),
      ('ar', '2٫5', '2.5'),
      ('en', '7', '7'),
    ]) {
      testWidgets('in $locale, $typed received goes as $sent', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _openPo(tester, status: 'PARTIALLY_RECEIVED');
        await tester.tap(find.text('Receive balance'));
        await tester.pumpAndSettle();
        final field = find.byKey(const Key('receive-qty-$_variant'));
        expect(tester.widget<TextField>(field).controller!.text, '10',
            reason: 'starts at what was ordered');
        await tester.enterText(field, typed);
        await tester.pump();
        await tester.tap(find.text('Confirm receipt'));
        await tester.pumpAndSettle();
        expect(lastPost(server, '/goods-receipts')['lines'], [
          {'variantId': _variant, 'qtyReceived': sent},
        ]);
      });
    }

    testWidgets('a received quantity that cannot be read records nothing', (tester) async {
      Intl.defaultLocale = 'ro';
      final server = await _openPo(tester, status: 'PARTIALLY_RECEIVED');
      await tester.tap(find.text('Receive balance'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('receive-qty-$_variant')), ',');
      await tester.pump();
      expect(
        tester.widget<TextField>(find.byKey(const Key('receive-qty-$_variant'))).decoration?.errorText,
        'Type the amount in digits.',
      );
      await tester.tap(find.text('Confirm receipt'));
      await tester.pumpAndSettle();
      expect(server.requests.where((r) => r.path.endsWith('/goods-receipts')), isEmpty);
    });
  });
}
