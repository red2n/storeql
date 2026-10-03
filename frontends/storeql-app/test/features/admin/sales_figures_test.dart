import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/sales_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The sales desk's figures — a gift card's value, a layaway's deposit, a
// line's quantity and price — are money in the business's (or the card's)
// currency, to its own places, and quantities to three. Each is read the way
// the app's language writes a number and sent as the decimal typed, or refused
// under its field with nothing sent: read with a point, Romanian's 12,50 was
// no amount at all and 1.250 lei was 1,25.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  final String currency;
  final String cardCurrency;
  _Server(this.currency, this.cardCurrency);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (o.method == 'GET' && path.endsWith('/admin/tenant')) {
      return jsonResponse(jsonEncode({
        'data': {'id': 't', 'name': 'Shop', 'status': 'ACTIVE', 'currency': currency, 'country': 'GB'}
      }));
    }
    if (o.method == 'GET' && path.endsWith('/transactions')) return jsonResponse('{"data":[]}');
    if (o.method == 'GET' && path.contains('/gift-cards/')) {
      return jsonResponse(jsonEncode({
        'data': {
          'code': 'GC-7777',
          'currentBalance': 50,
          'initialBalance': 50,
          'status': 'ACTIVE',
          'currency': cardCurrency,
        }
      }));
    }
    if (o.method == 'GET' && path.contains('/layaways/')) {
      return jsonResponse(jsonEncode({
        'data': {'id': 'lay-1', 'totalAmount': 100, 'depositPaid': 10, 'balance': 90, 'status': 'ACTIVE'}
      }));
    }
    if (o.method == 'GET' && path.endsWith('/admin/products')) {
      return jsonResponse('{"data":[{"id":"p-oat","name":"Oat milk"}],"meta":{"nextCursor":null}}');
    }
    if (o.method == 'GET' && path.endsWith('/admin/products/p-oat/variants')) {
      return jsonResponse('{"data":[{"id":"v-oat","productId":"p-oat","sku":"OAT-1"}]}');
    }
    if (o.method == 'POST' && path.endsWith('/gift-cards')) {
      return jsonResponse('{"data":{"code":"GC-1234"}}');
    }
    if (o.method == 'POST' && path.endsWith('/layaways')) {
      return jsonResponse('{"data":{"id":"lay-2"}}');
    }
    return jsonResponse('{"data":{}}');
  }

  List<Map<String, dynamic>> posts(String suffix) => [
        for (final r in requests)
          if (r.method == 'POST' && r.path.endsWith(suffix))
            (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>,
      ];
}

Future<_Server> _pump(WidgetTester tester, {String currency = 'GBP', String cardCurrency = 'GBP'}) async {
  tester.view.physicalSize = const Size(1100, 1800);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final server = _Server(currency, cardCurrency);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('MANAGER')),
      storesProvider.overrideWith((ref) async => const [
            StoreInfo(id: 's1', name: 'High Street', code: 'HS', type: 'STORE', status: 'ACTIVE', country: 'GB'),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: SalesScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

/// Types [text] into [field] one key at a time, as a person does.
Future<void> _press(WidgetTester tester, Finder field, String text) async {
  for (var i = 1; i <= text.length; i++) {
    await tester.enterText(field, text.substring(0, i));
    await tester.pump();
  }
}

String? _says(WidgetTester tester, Finder field) =>
    tester.widget<TextField>(field).decoration?.errorText;

Future<void> _openIssue(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(OutlinedButton, 'Issue'));
  await tester.pumpAndSettle();
  await tester.tap(find.byType(DropdownButtonFormField<String>).first);
  await tester.pumpAndSettle();
  await tester.tap(find.text('High Street').last);
  await tester.pumpAndSettle();
  await tester.tap(find.byKey(const Key('gift-card-reason')));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Goodwill').last);
  await tester.pumpAndSettle();
}

Finder get _issueAmount => find.byKey(const Key('gift-card-issue-amount'));

void main() {
  tearDown(() => Intl.defaultLocale = null);

  group('a gift card given by hand', () {
    for (final (locale, currency, typed, sent) in [
      ('ro', 'RON', '12,50', '12.5'),
      ('en_GB', 'GBP', '12.50', '12.5'),
      ('en', 'USD', '25', '25'),
      ('pl', 'PLN', '12,5', '12.5'),
      ('ar', 'KWD', '1٫250', '1.25'),
      ('en_GB', 'JPY', '1500', '1500'),
    ]) {
      testWidgets('in $locale, $typed $currency typed key by key is issued as $sent', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester, currency: currency);
        await _openIssue(tester);
        await _press(tester, _issueAmount, typed);
        expect(_says(tester, _issueAmount), isNull);
        await tester.tap(find.widgetWithText(FilledButton, 'Issue'));
        await tester.pumpAndSettle();
        expect(server.posts('/gift-cards').single['amount'], sent);
      });
    }

    for (final (locale, currency, typed, says) in [
      ('ro', 'RON', '1.250', 'Type the amount without thousands separators. Decimals go after a comma.'),
      ('en', 'EUR', '12,50', 'Type the amount without thousands separators. Decimals go after a point.'),
      ('pl', 'PLN', '1.250', 'A point may group thousands here. Type the figure without grouping, with any decimals after a comma.'),
      ('en_GB', 'JPY', '1500.5', 'Whole amounts only.'),
      ('ar', 'KWD', '.', 'Type the amount in digits.'),
    ]) {
      testWidgets('in $locale, $typed $currency is refused and no card is issued', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester, currency: currency);
        await _openIssue(tester);
        await _press(tester, _issueAmount, typed);
        expect(_says(tester, _issueAmount), says);
        await tester.tap(find.widgetWithText(FilledButton, 'Issue'));
        await tester.pumpAndSettle();
        expect(server.posts('/gift-cards'), isEmpty);
      });
    }
  });

  group('a reload is money in the card\'s own currency', () {
    Future<void> openReload(WidgetTester tester) async {
      await tester.enterText(find.widgetWithText(TextField, 'Gift card code'), 'GC-7777');
      await tester.tap(find.text('Look up'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Reload'));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('gift-card-reason')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Compensation').last);
      await tester.pumpAndSettle();
    }

    Finder amount() => find.byKey(const Key('value-dialog-amount'));

    for (final (locale, cardCurrency, typed, sent) in [
      ('ro', 'RON', '10,5', '10.5'),
      ('en_GB', 'GBP', '10.50', '10.5'),
      ('en', 'USD', '10', '10'),
      ('pl', 'PLN', '10,25', '10.25'),
      ('ar', 'KWD', '1٫125', '1.125'),
    ]) {
      testWidgets('in $locale, $typed $cardCurrency reloads as $sent', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester, cardCurrency: cardCurrency);
        await openReload(tester);
        await _press(tester, amount(), typed);
        expect(_says(tester, amount()), isNull);
        await tester.tap(find.text('OK'));
        await tester.pumpAndSettle();
        expect(server.posts('/reload').single['amount'], sent);
      });
    }

    for (final (locale, cardCurrency, typed) in [
      ('ro', 'RON', '10.5'),
      ('en_GB', 'GBP', '10.505'),
      ('pl', 'PLN', '1.250'),
      ('en', 'JPY', '10.5'),
      ('ar', 'KWD', '٫'),
    ]) {
      testWidgets('in $locale, $typed $cardCurrency is refused and nothing is reloaded', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester, cardCurrency: cardCurrency);
        await openReload(tester);
        await _press(tester, amount(), typed);
        expect(_says(tester, amount()), isNotNull);
        await tester.tap(find.text('OK'));
        await tester.pumpAndSettle();
        expect(server.posts('/reload'), isEmpty);
      });
    }
  });

  group('a layaway', () {
    Future<void> openLayaways(WidgetTester tester) async {
      await tester.tap(find.text('Layaways'));
      await tester.pumpAndSettle();
    }

    for (final (locale, currency, typed, sent) in [
      ('ro', 'RON', '12,50', '12.5'),
      ('en_GB', 'GBP', '12.50', '12.5'),
      ('en', 'USD', '7', '7'),
      ('pl', 'PLN', '12,5', '12.5'),
      ('ar', 'KWD', '1٫125', '1.125'),
    ]) {
      testWidgets('in $locale, a deposit of $typed $currency is added as $sent', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester, currency: currency);
        await openLayaways(tester);
        await tester.enterText(find.widgetWithText(TextField, 'Layaway id'), 'lay-1');
        await tester.tap(find.text('Look up'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Add deposit'));
        await tester.pumpAndSettle();
        final amount = find.byKey(const Key('value-dialog-amount'));
        await _press(tester, amount, typed);
        expect(_says(tester, amount), isNull);
        await tester.tap(find.text('OK'));
        await tester.pumpAndSettle();
        expect(server.posts('/deposits').single, {'amount': sent, 'paymentMethod': 'CASH'});
      });
    }

    Future<void> openNew(WidgetTester tester) async {
      await openLayaways(tester);
      await tester.tap(find.text('New'));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(DropdownButtonFormField<String>).first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('High Street').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Product *'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Oat milk').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Variant *'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('OAT-1').last);
      await tester.pumpAndSettle();
    }

    for (final (locale, currency, qty, price, deposit, sent) in [
      ('ro', 'RON', '1,5', '12,50', '5,25', ('1.5', '12.5', '5.25')),
      ('en_GB', 'GBP', '2', '12.50', '5', ('2', '12.5', '5')),
      ('en', 'USD', '0.25', '8', '1.5', ('0.25', '8', '1.5')),
      ('pl', 'PLN', '1,125', '12,5', '5', ('1.125', '12.5', '5')),
      ('ar', 'KWD', '2٫5', '1٫250', '0٫5', ('2.5', '1.25', '0.5')),
    ]) {
      testWidgets('in $locale, a line of $qty at $price with $deposit down is sent as typed', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester, currency: currency);
        await openNew(tester);
        await _press(tester, find.byKey(const Key('line-qty')), qty);
        await _press(tester, find.byKey(const Key('line-price')), price);
        expect(_says(tester, find.byKey(const Key('line-qty'))), isNull);
        expect(_says(tester, find.byKey(const Key('line-price'))), isNull);
        await tester.tap(find.byIcon(Icons.add).last);
        await tester.pumpAndSettle();
        await _press(tester, find.byKey(const Key('layaway-deposit')), deposit);
        await tester.tap(find.widgetWithText(FilledButton, 'Create'));
        await tester.pumpAndSettle();
        final body = server.posts('/layaways').single;
        expect(body['items'], [
          {'variantId': 'v-oat', 'qty': sent.$1, 'unitPrice': sent.$2},
        ]);
        expect(body['initialDeposit'], sent.$3);
      });
    }

    for (final (locale, qty, price) in [
      ('ro', '1.5', '12,50'),
      ('pl', '1.250', '12'),
      ('en', '1,5', '12'),
      ('en_GB', '2', '12,50'),
      ('ar', '.', '12'),
    ]) {
      testWidgets('in $locale, a line of $qty at $price is refused and not added', (tester) async {
        Intl.defaultLocale = locale;
        final server = await _pump(tester);
        await openNew(tester);
        await _press(tester, find.byKey(const Key('line-qty')), qty);
        await _press(tester, find.byKey(const Key('line-price')), price);
        expect(
          _says(tester, find.byKey(const Key('line-qty'))) ?? _says(tester, find.byKey(const Key('line-price'))),
          isNotNull,
        );
        await tester.tap(find.byIcon(Icons.add).last);
        await tester.pumpAndSettle();
        await tester.enterText(find.byKey(const Key('layaway-deposit')), '5');
        await tester.tap(find.widgetWithText(FilledButton, 'Create'));
        await tester.pumpAndSettle();
        expect(server.posts('/layaways'), isEmpty, reason: 'no item was added');
      });
    }
  });
}
