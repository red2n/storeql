import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/sales_screen.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';

// ---------------------------------------------------------------------------
// A gift card looked up in Sales tools, as the person at the desk reads it:
// the balance and every amount as money, the card's state and each
// transaction in words with when it happened, a redemption as money taken off
// the card (order-svc stores it as a positive amount), and on a phone a code
// field with room for the code.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    final Object body;
    if (o.path.endsWith('/transactions')) {
      body = {
        'data': [
          {
            'txType': 'ISSUE',
            'amount': 20,
            'balanceBefore': 0,
            'balanceAfter': 20,
            'createdAt': '2026-09-20T10:00:00Z',
          },
          {
            'txType': 'REDEEM',
            'amount': 4.85,
            'balanceBefore': 20,
            'balanceAfter': 15.15,
            'createdAt': '2026-09-21T12:30:00Z',
          },
        ]
      };
    } else if (o.path.contains('/gift-cards/')) {
      body = {
        'data': {
          'code': 'GC-7777',
          'currentBalance': 15.15,
          'initialBalance': 20,
          'status': 'ACTIVE',
          'currency': 'GBP',
        }
      };
    } else {
      body = {'data': []};
    }
    return ResponseBody.fromString(jsonEncode(body), 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }
}

Future<void> _pump(WidgetTester tester, Size size, {String? tab}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Server();
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      storesProvider.overrideWith((ref) async => const <StoreInfo>[]),
    ],
    child: MaterialApp(home: Scaffold(body: SalesScreen(initialTab: tab))),
  ));
  await tester.pumpAndSettle();
}

Finder get _codeField => find.widgetWithText(TextField, 'Gift card code');

Future<void> _lookUp(WidgetTester tester) async {
  await tester.enterText(_codeField, 'GC-7777');
  await tester.tap(find.widgetWithText(FilledButton, 'Look up'));
  await tester.pumpAndSettle();
}

Finder _row(String words) =>
    find.ancestor(of: find.text(words), matching: find.byType(ListTile));

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('a redemption reads as money taken off the card, whatever its sign',
      (tester) async {
    await _pump(tester, const Size(1100, 1600));
    await _lookUp(tester);
    expect(find.descendant(of: _row('Redeemed'), matching: find.byIcon(Icons.arrow_downward)),
        findsOneWidget);
    expect(find.descendant(of: _row('Redeemed'), matching: find.byIcon(Icons.arrow_upward)),
        findsNothing);
    expect(find.descendant(of: _row('Issued'), matching: find.byIcon(Icons.arrow_upward)),
        findsOneWidget);
    expect(
        find.descendant(
            of: _row('Redeemed'),
            matching: find.textContaining(AppFormat.money(-4.85, currencyCode: 'GBP'))),
        findsOneWidget);
  });

  testWidgets('the card and its transactions are in words, each with when it happened',
      (tester) async {
    await _pump(tester, const Size(1100, 1600));
    await _lookUp(tester);
    for (final code in ['ISSUE', 'REDEEM', 'ACTIVE']) {
      expect(find.textContaining(code), findsNothing, reason: '$code is a code, not words');
    }
    expect(find.textContaining('Status:'), findsNothing);
    final active = find.ancestor(of: find.text('Active'), matching: find.byType(StatusBadge));
    expect(active, findsOneWidget);
    expect(tester.widget<StatusBadge>(active).tone, StatusTone.success);
    expect(
        find.descendant(
            of: _row('Issued'),
            matching: find.textContaining(AppFormat.dateTime('2026-09-20T10:00:00Z'))),
        findsOneWidget);
    expect(
        find.descendant(
            of: _row('Redeemed'),
            matching: find.textContaining(AppFormat.dateTime('2026-09-21T12:30:00Z'))),
        findsOneWidget);
  });

  testWidgets('the balance and the amounts are money, not a code and a bare number',
      (tester) async {
    await _pump(tester, const Size(1100, 1600));
    await _lookUp(tester);
    final balance = AppFormat.money(15.15, currencyCode: 'GBP');
    expect(find.textContaining('GBP 15.15'), findsNothing);
    expect(find.textContaining('Balance: $balance'), findsOneWidget);
    expect(
        find.descendant(
            of: _row('Issued'),
            matching: find.textContaining(AppFormat.money(20, currencyCode: 'GBP'))),
        findsWidgets);
    expect(find.descendant(of: _row('Redeemed'), matching: find.textContaining(balance)),
        findsOneWidget);
  });

  testWidgets('on a phone Look up and Issue go under the code field', (tester) async {
    await _pump(tester, const Size(390, 844));
    final field = tester.getRect(_codeField);
    final lookUp = tester.getRect(find.widgetWithText(FilledButton, 'Look up'));
    final issue = tester.getRect(find.widgetWithText(OutlinedButton, 'Issue'));
    expect(lookUp.top, greaterThanOrEqualTo(field.bottom));
    expect(issue.top, greaterThanOrEqualTo(field.bottom));
    expect(field.width, greaterThan(300), reason: 'the code needs the width of the screen');
    expect(tester.takeException(), isNull);
  });

  testWidgets('on a phone at 200% text a looked-up card still fits', (tester) async {
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await _pump(tester, const Size(390, 844));
    await _lookUp(tester);
    await tester.scrollUntilVisible(find.text('Redeemed'), 200,
        scrollable: find
            .descendant(of: find.byType(ListView).first, matching: find.byType(Scrollable))
            .first);
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('Redeemed'), findsOneWidget);
  });

  testWidgets('from tablet width they stay beside it', (tester) async {
    await _pump(tester, const Size(1100, 1600));
    final field = tester.getRect(_codeField);
    final lookUp = tester.getRect(find.widgetWithText(FilledButton, 'Look up'));
    expect(lookUp.left, greaterThanOrEqualTo(field.right));
  });

  for (final (label, size, gutter) in [
    ('a phone', const Size(390, 844), 16.0),
    ('a tablet', const Size(1100, 1600), 24.0),
  ]) {
    testWidgets('on $label the title and the tab content share one inset', (tester) async {
      await _pump(tester, size);
      final title = tester.getTopLeft(find.text('Sales tools')).dx;
      expect(title, gutter);
      expect(tester.getTopLeft(_codeField).dx, title);
    });

    testWidgets('on $label the legal receipts line up under the title too', (tester) async {
      await _pump(tester, size, tab: 'receipts');
      expect(tester.getTopLeft(find.text('Sales tools')).dx, gutter);
      expect(tester.getTopLeft(find.text('Legal receipts')).dx, gutter);
    });
  }
}
