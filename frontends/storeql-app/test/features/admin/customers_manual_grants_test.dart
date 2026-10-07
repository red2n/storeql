import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/ids.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/customers_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Adding points and issuing store credit by hand are management's
// (customer-svc, 30 Sep 2026): only an owner or manager is offered them, both
// need a reason, adding points carries an Idempotency-Key (the same one on a
// retry of the same request, a new one when it changes), and a refusal reads
// in words. Redeeming, the till's own business, stays open to every role.
// ---------------------------------------------------------------------------

final _ann = {
  'id': 'c-1',
  'email': 'ann@example.com',
  'firstName': 'Ann',
  'lastName': 'Lee',
  'status': 'ACTIVE',
};

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  /// The status the next earn/issue answers with, and its message.
  int grantStatus = 200;
  String grantMessage = 'Only a manager can do that.';

  /// The currency the customer's store credit is kept in (the business's
  /// own), and whether its balance can be read at all.
  final String currency;
  final bool creditReadable;

  _Server({this.currency = 'GBP', this.creditReadable = true});

  List<RequestOptions> get grants =>
      requests.where((r) => r.method == 'POST' && (r.path.contains('/loyalty/') || r.path.contains('/store-credit/'))).toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (o.method == 'POST') {
      return grantStatus == 200
          ? jsonResponse('{"data":{}}')
          : jsonResponse(jsonEncode({'error': {'code': 'FORBIDDEN', 'message': grantMessage}}), grantStatus);
    }
    if (path == '/customer-svc/customers') {
      return jsonResponse(jsonEncode({'data': {'items': [_ann], 'nextCursor': null}}));
    }
    if (path == '/customer-svc/customers/c-1') return jsonResponse(jsonEncode({'data': _ann}));
    if (path.endsWith('/loyalty')) {
      return jsonResponse(jsonEncode({'data': {'pointsBalance': 30, 'lifetimePoints': 50}}));
    }
    if (path.endsWith('/loyalty/ledger')) return jsonResponse('{"data":[]}');
    if (path.endsWith('/store-credit')) {
      return creditReadable
          ? jsonResponse(jsonEncode({'data': {'balance': 12.5, 'currency': currency}}))
          : jsonResponse(jsonEncode({'code': 'UNAVAILABLE', 'status': 503}), 503);
    }
    if (path.endsWith('/addresses')) return jsonResponse('{"data":[]}');
    return jsonResponse(jsonEncode({'code': 'NOT_FOUND', 'status': 404}), 404);
  }
}

Future<_Server> _open(WidgetTester tester, String role,
    {String currency = 'GBP', bool creditReadable = true}) async {
  tester.view.physicalSize = const Size(1200, 2000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(currency: currency, creditReadable: creditReadable);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
    ],
    child: const MaterialApp(home: Scaffold(body: CustomersScreen())),
  ));
  for (var i = 0; i < 12; i++) {
    await tester.pump(const Duration(milliseconds: 50));
  }
  await tester.tap(find.text('Ann Lee'));
  for (var i = 0; i < 12; i++) {
    await tester.pump(const Duration(milliseconds: 50));
  }
  return server;
}

Future<void> _fill(WidgetTester tester, String amount, String reason) async {
  await tester.enterText(find.byKey(const Key('grant-amount')), amount);
  await tester.enterText(find.byKey(const Key('grant-reason')), reason);
  await tester.tap(find.byKey(const Key('grant-apply')));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
}

void main() {
  setUpAll(initializeDateFormatting);

  for (final role in ['CASHIER', 'STOREKEEPER']) {
    testWidgets('a $role is not offered adding points or issuing credit, only the till\'s redeem', (tester) async {
      await _open(tester, role);
      expect(find.byKey(const Key('customer-earn-points')), findsNothing);
      expect(find.byKey(const Key('customer-issue-credit')), findsNothing);
      expect(find.text('Redeem points'), findsOneWidget);
      expect(find.text('Redeem credit'), findsOneWidget);
    });
  }

  for (final role in ['OWNER', 'MANAGER']) {
    testWidgets('a $role is offered both', (tester) async {
      await _open(tester, role);
      expect(find.byKey(const Key('customer-earn-points')), findsOneWidget);
      expect(find.byKey(const Key('customer-issue-credit')), findsOneWidget);
    });
  }

  // Nothing typed is ever dropped: a key the field cannot take — a place too
  // many, a letter, a sign, a mark it cannot read — stays where it was typed
  // with its refusal under it, and Apply waits until it is deleted. Dropped,
  // the keys after it were taken and the figure sent was not the one typed:
  // 12.505 went as 12.50, -3 as 3, 12٫50 as 1250.
  testWidgets('the points field keeps what is typed and refuses a third decimal where it stands',
      (tester) async {
    Intl.defaultLocale = 'en_GB';
    addTearDown(() => Intl.defaultLocale = null);
    await _open(tester, 'OWNER');
    await tester.tap(find.byKey(const Key('customer-earn-points')));
    await tester.pumpAndSettle();
    final field = find.byKey(const Key('grant-amount'));
    String text() => tester.widget<TextField>(field).controller!.text;
    String? says() => tester.widget<TextField>(field).decoration?.errorText;
    bool canApply() =>
        tester.widget<FilledButton>(find.byKey(const Key('grant-apply'))).onPressed != null;

    await tester.enterText(field, '12.34');
    await tester.pump();
    expect(text(), '12.34');
    expect(canApply(), isTrue);
    for (final (typed, why) in [
      ('12.345', 'At most 2 decimal places.'),
      ('12.34a', 'Only digits and a decimal point.'),
      ('12.34,', 'Type the amount without thousands separators. Decimals go after a point.'),
    ]) {
      await tester.enterText(field, typed);
      await tester.pump();
      expect(text(), typed, reason: 'kept where it can be seen');
      expect(says(), why, reason: typed);
      expect(canApply(), isFalse, reason: typed);
    }
    // Deleting back is always allowed, down to nothing.
    await tester.enterText(field, '12.3');
    await tester.pump();
    expect(text(), '12.3');
    expect(says(), isNull);
    await tester.enterText(field, '');
    await tester.pump();
    expect(text(), '');
  });

  testWidgets('a paste that is not an amount is kept, refused, and never sent',
      (tester) async {
    Intl.defaultLocale = 'en_GB';
    addTearDown(() => Intl.defaultLocale = null);
    final server = await _open(tester, 'OWNER');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    final field = find.byKey(const Key('grant-amount'));
    String text() => tester.widget<TextField>(field).controller!.text;
    for (final (paste, why) in [
      ('12.3456', 'At most 2 decimal places.'),
      ('-3', 'Type the amount without a sign.'),
      ('1e3', 'Only digits and a decimal point.'),
      ('ten', 'Only digits and a decimal point.'),
      // A mark the field cannot read is never read with the mark dropped
      // (1,5 as 15).
      ('1,5', 'Type the amount without thousands separators. Decimals go after a point.'),
    ]) {
      await tester.enterText(field, paste);
      await tester.pump();
      expect(text(), paste);
      expect(tester.widget<TextField>(field).decoration?.errorText, why, reason: paste);
      expect(tester.widget<FilledButton>(find.byKey(const Key('grant-apply'))).onPressed, isNull,
          reason: paste);
    }
    await tester.enterText(find.byKey(const Key('grant-reason')), 'Damaged goods');
    await tester.tap(find.byKey(const Key('grant-apply')));
    await tester.pump();
    expect(server.grants, isEmpty);
  });

  // Store credit is money in the business's own currency, and customer-svc
  // takes it to that currency's minor unit (three places for a dinar, none for
  // the dong) and fourteen whole digits; points are two places and sixteen.
  // The field takes exactly that, and the amount goes as the decimal typed —
  // a double could not carry 99999999999999.99 (it reads back .98).
  String fieldText(WidgetTester tester) => tester
      .widget<TextField>(find.byKey(const Key('grant-amount')))
      .controller!
      .text;

  Future<void> type(WidgetTester tester, String text) async {
    await tester.enterText(find.byKey(const Key('grant-amount')), text);
    await tester.pump();
  }

  /// Presses [keys] one at a time, as a person types: each lands on what the
  /// field holds by then, so a key the field refuses shows in what follows.
  Future<void> press(WidgetTester tester, String keys) async {
    for (final k in keys.split('')) {
      await tester.enterText(
          find.byKey(const Key('grant-amount')), fieldText(tester) + k);
      await tester.pump();
    }
  }

  Future<void> apply(WidgetTester tester, String reason) async {
    await tester.enterText(find.byKey(const Key('grant-reason')), reason);
    await tester.tap(find.byKey(const Key('grant-apply')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));
  }

  /// What the amount field says under it, if anything.
  String? amountSays(WidgetTester tester) => tester
      .widget<TextField>(find.byKey(const Key('grant-amount')))
      .decoration
      ?.errorText;

  /// Whether Apply can be pressed.
  bool applyEnabled(WidgetTester tester) =>
      tester.widget<FilledButton>(find.byKey(const Key('grant-apply'))).onPressed != null;

  // The app speaks Polish, Romanian and South African English, and each writes
  // money with a decimal comma — the balance in this very dialog reads
  // "12,50 zł". The amount is typed the way the app's language writes it and
  // sent as the decimal it is: a comma is never dropped with its decimals run
  // into the whole part (12,50 sent as 1250).
  testWidgets('in Polish, 12,50 typed key by key is twelve fifty, never 1250',
      (tester) async {
    Intl.defaultLocale = 'pl';
    addTearDown(() => Intl.defaultLocale = null);
    final server = await _open(tester, 'OWNER', currency: 'PLN');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    expect(
        tester
            .widget<TextField>(find.byKey(const Key('grant-amount')))
            .decoration
            ?.hintText,
        '0,00',
        reason: 'the field shows the mark it takes before anything is typed');
    await press(tester, '12,50');
    expect(fieldText(tester), '12,50');
    expect(amountSays(tester), isNull);
    await apply(tester, 'Damaged goods');
    expect(server.grants.single.data, {'amount': '12.5', 'reason': 'Damaged goods'});
  });

  testWidgets('in Polish, a dinar\'s 1,250 is one and a quarter, never a thousand times it',
      (tester) async {
    Intl.defaultLocale = 'pl';
    addTearDown(() => Intl.defaultLocale = null);
    final server = await _open(tester, 'OWNER', currency: 'KWD');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await press(tester, '1,250');
    expect(fieldText(tester), '1,250');
    await apply(tester, 'Damaged goods');
    expect(server.grants.single.data, {'amount': '1.25', 'reason': 'Damaged goods'});
  });

  testWidgets('in South African English, points take a decimal comma, and a point too since nothing is grouped with one',
      (tester) async {
    Intl.defaultLocale = 'en_ZA';
    addTearDown(() => Intl.defaultLocale = null);
    final server = await _open(tester, 'OWNER', currency: 'ZAR');
    await tester.tap(find.byKey(const Key('customer-earn-points')));
    await tester.pumpAndSettle();
    await press(tester, '2,5');
    expect(fieldText(tester), '2,5');
    await apply(tester, 'Goodwill');
    expect(server.grants.single.data, {'points': '2.5', 'reason': 'Goodwill'});
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('customer-earn-points')));
    await tester.pumpAndSettle();
    await press(tester, '7.5');
    await apply(tester, 'Goodwill');
    expect(server.grants.last.data, {'points': '7.5', 'reason': 'Goodwill'});
  });

  // A grouping mark is refused, and the refusal stays on the field — with the
  // mark where it was typed — until the person deletes back past it or clears
  // the field. Dropping it and taking the keys after it ran the decimals into
  // the whole part: 12.50 in Romanian, or 12,50 typed on a decimal pad whose
  // only decimal key is a comma in an app speaking English, went as 1250.
  const roGrouping =
      'Type the amount without thousands separators. Decimals go after a comma.';
  const enGrouping =
      'Type the amount without thousands separators. Decimals go after a point.';

  testWidgets('in Romanian, a point groups thousands: 1.250,5 stays refused until the point is deleted',
      (tester) async {
    Intl.defaultLocale = 'ro';
    addTearDown(() => Intl.defaultLocale = null);
    final server = await _open(tester, 'OWNER', currency: 'RON');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await press(tester, '1.');
    expect(fieldText(tester), '1.');
    expect(amountSays(tester), roGrouping);
    await press(tester, '250,5');
    expect(fieldText(tester), '1.250,5');
    expect(amountSays(tester), roGrouping, reason: 'the keys after it do not clear it');
    expect(applyEnabled(tester), isFalse);
    await apply(tester, 'Damaged goods');
    expect(server.grants, isEmpty);
    await type(tester, '1250,5');
    expect(amountSays(tester), isNull, reason: 'the point deleted, nothing is refused');
    await apply(tester, 'Damaged goods');
    expect(server.grants.single.data, {'amount': '1250.5', 'reason': 'Damaged goods'});
  });

  testWidgets('in English, a comma groups thousands: 1,250 stays refused until the comma is deleted',
      (tester) async {
    Intl.defaultLocale = 'en_GB';
    addTearDown(() => Intl.defaultLocale = null);
    final server = await _open(tester, 'OWNER', currency: 'GBP');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await press(tester, '1,');
    expect(fieldText(tester), '1,');
    expect(amountSays(tester), enGrouping);
    await press(tester, '250');
    expect(fieldText(tester), '1,250');
    expect(amountSays(tester), enGrouping);
    await apply(tester, 'Damaged goods');
    expect(server.grants, isEmpty, reason: 'never taken as a point, never dropped');
    await type(tester, '1250');
    await apply(tester, 'Damaged goods');
    expect(server.grants.single.data, {'amount': '1250', 'reason': 'Damaged goods'});
  });

  for (final (locale, typed, currency, says) in [
    ('ro', '12.50', 'RON', roGrouping),
    ('en_GB', '12,50', 'GBP', enGrouping),
    // English is also the fallback for every comma-decimal language the app
    // does not ship: a German phone's decimal pad types a comma here.
    ('en', '12,50', 'EUR', enGrouping),
  ]) {
    testWidgets('in $locale, $typed typed key by key sends nothing and keeps saying why',
        (tester) async {
      Intl.defaultLocale = locale;
      addTearDown(() => Intl.defaultLocale = null);
      final server = await _open(tester, 'OWNER', currency: currency);
      await tester.tap(find.byKey(const Key('customer-issue-credit')));
      await tester.pumpAndSettle();
      await press(tester, typed);
      expect(fieldText(tester), typed, reason: 'the mark stays where it was typed');
      expect(amountSays(tester), says);
      expect(applyEnabled(tester), isFalse);
      await apply(tester, 'Damaged goods');
      expect(server.grants, isEmpty, reason: 'never 1250');
      expect(amountSays(tester), says);
      // A letter typed on top of it is kept too, and the mark's refusal
      // still shows.
      await press(tester, 'x');
      expect(fieldText(tester), '${typed}x');
      expect(amountSays(tester), says);
      expect(applyEnabled(tester), isFalse);
      // Deleting back past the mark lets the amount be typed again.
      await type(tester, '12');
      expect(amountSays(tester), isNull);
      expect(applyEnabled(tester), isTrue);
    });
  }

  for (final (locale, typed, currency) in [
    ('ro', '12,50', 'RON'),
    ('en_GB', '12.50', 'GBP'),
  ]) {
    testWidgets('in $locale, $typed typed key by key is twelve fifty', (tester) async {
      Intl.defaultLocale = locale;
      addTearDown(() => Intl.defaultLocale = null);
      final server = await _open(tester, 'OWNER', currency: currency);
      await tester.tap(find.byKey(const Key('customer-issue-credit')));
      await tester.pumpAndSettle();
      await press(tester, typed);
      expect(fieldText(tester), typed);
      expect(amountSays(tester), isNull);
      await apply(tester, 'Damaged goods');
      expect(server.grants.single.data, {'amount': '12.5', 'reason': 'Damaged goods'});
    });
  }

  // The amount is read trimmed, as every other figure field reads one: a
  // space a keyboard or a paste leaves around it is not a thousands
  // separator. A mark alone is no figure, and is refused, not blank.
  for (final (locale, typed, currency) in [
    ('ro', ' 12,50 ', 'RON'),
    ('en_GB', '12.50 ', 'GBP'),
    ('en', ' 12.50', 'USD'),
    ('pl', ' 12,50', 'PLN'),
    ('ar', '12\u066B50 ', 'KWD'),
  ]) {
    testWidgets('in $locale, "$typed" is read trimmed and sent as twelve fifty', (tester) async {
      Intl.defaultLocale = locale;
      addTearDown(() => Intl.defaultLocale = null);
      final server = await _open(tester, 'OWNER', currency: currency);
      await tester.tap(find.byKey(const Key('customer-issue-credit')));
      await tester.pumpAndSettle();
      await type(tester, typed);
      expect(amountSays(tester), isNull, reason: 'a space around it is no grouping');
      expect(applyEnabled(tester), isTrue);
      await apply(tester, 'Damaged goods');
      expect(server.grants.single.data, {'amount': '12.5', 'reason': 'Damaged goods'});
    });
  }

  for (final locale in ['ro', 'en_GB', 'en', 'pl', 'ar']) {
    testWidgets('in $locale, a lone mark is refused in words and nothing is sent', (tester) async {
      Intl.defaultLocale = locale;
      addTearDown(() => Intl.defaultLocale = null);
      final server = await _open(tester, 'OWNER');
      await tester.tap(find.byKey(const Key('customer-issue-credit')));
      await tester.pumpAndSettle();
      await type(tester, locale == 'ar' ? '\u066B' : (locale == 'ro' || locale == 'pl' ? ',' : '.'));
      expect(amountSays(tester), 'Type the amount in digits.');
      expect(applyEnabled(tester), isFalse);
      await apply(tester, 'Damaged goods');
      expect(server.grants, isEmpty);
    });
  }

  testWidgets('cleared, a refused amount is gone with its refusal', (tester) async {
    Intl.defaultLocale = 'ro';
    addTearDown(() => Intl.defaultLocale = null);
    final server = await _open(tester, 'OWNER', currency: 'RON');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await press(tester, '12.50');
    await type(tester, '');
    expect(amountSays(tester), isNull);
    expect(applyEnabled(tester), isFalse, reason: 'nothing to apply');
    await press(tester, '12,50');
    await apply(tester, 'Damaged goods');
    expect(server.grants.single.data, {'amount': '12.5', 'reason': 'Damaged goods'});
  });

  testWidgets('points refuse a grouping mark the same way', (tester) async {
    Intl.defaultLocale = 'ro';
    addTearDown(() => Intl.defaultLocale = null);
    final server = await _open(tester, 'OWNER', currency: 'RON');
    await tester.tap(find.byKey(const Key('customer-earn-points')));
    await tester.pumpAndSettle();
    await press(tester, '12.50');
    expect(fieldText(tester), '12.50');
    expect(amountSays(tester), roGrouping);
    await apply(tester, 'Goodwill');
    expect(server.grants, isEmpty);
  });

  testWidgets('a second decimal mark stays refused too, never dropped', (tester) async {
    Intl.defaultLocale = 'en_GB';
    addTearDown(() => Intl.defaultLocale = null);
    final server = await _open(tester, 'OWNER', currency: 'GBP');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await press(tester, '1.2.5');
    expect(fieldText(tester), '1.2.5');
    expect(amountSays(tester), 'Only one decimal point.');
    await apply(tester, 'Damaged goods');
    expect(server.grants, isEmpty, reason: 'neither 1.25 nor 12.5 was typed');
  });

  testWidgets('every key the amount field refuses stays, says why under it, and holds Apply',
      (tester) async {
    Intl.defaultLocale = 'en_GB';
    addTearDown(() => Intl.defaultLocale = null);
    await _open(tester, 'OWNER', currency: 'GBP');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await press(tester, '12.345');
    expect(fieldText(tester), '12.345');
    expect(amountSays(tester), 'At most 2 decimal places.');
    expect(applyEnabled(tester), isFalse);
    await type(tester, '12.34');
    await press(tester, '.');
    expect(fieldText(tester), '12.34.', reason: 'a mark stays where it was typed');
    expect(amountSays(tester), 'Only one decimal point.');
    await type(tester, '12.34');
    expect(amountSays(tester), isNull);
    await press(tester, 'a');
    expect(fieldText(tester), '12.34a');
    expect(amountSays(tester), 'Only digits and a decimal point.');
    expect(applyEnabled(tester), isFalse);
    await type(tester, '123456789012345');
    expect(amountSays(tester), 'At most 14 digits before the decimals.');
    expect(applyEnabled(tester), isFalse);
    await type(tester, '');
    expect(amountSays(tester), isNull);
  });

  // A digit past the limit is kept with its refusal, and Apply waits: dropped,
  // the figure left under the message was sent though the message stood. In
  // South African English (and in Polish, where the field reads a point as the
  // decimal) 12,500 is a thousand times 12,50; English is the fallback for a
  // German phone, whose 1.250 is twelve hundred and fifty.
  for (final (locale, typed, currency, why) in [
    ('en_GB', '12.505', 'GBP', 'At most 2 decimal places.'),
    ('en_ZA', '12,500', 'ZAR', 'At most 2 decimal places.'),
    // Polish takes a point as the decimal too, but before three digits it may
    // group thousands: refused, asking for the figure without grouping.
    ('pl', '12.500', 'PLN',
        'A point may group thousands here. Type the figure without grouping, with any decimals after a comma.'),
    ('en', '1.250', 'EUR', 'At most 2 decimal places.'),
    ('en', '123456789012345', 'VND', 'At most 14 digits.'),
  ]) {
    testWidgets('in $locale, $typed $currency typed key by key sends nothing while it says why',
        (tester) async {
      Intl.defaultLocale = locale;
      addTearDown(() => Intl.defaultLocale = null);
      final server = await _open(tester, 'OWNER', currency: currency);
      await tester.tap(find.byKey(const Key('customer-issue-credit')));
      await tester.pumpAndSettle();
      await press(tester, typed);
      expect(fieldText(tester), typed, reason: 'the last digit is kept where it was typed');
      expect(amountSays(tester), why);
      expect(applyEnabled(tester), isFalse);
      await apply(tester, 'Damaged goods');
      expect(server.grants, isEmpty, reason: 'never the figure left after a dropped digit');
    });
  }

  // A minus sign is kept and refused, never dropped with the digits after it
  // taken: -3 typed into "Add points" or "Redeem store credit" went as 3.
  for (final (locale, button) in [
    ('en_GB', 'customer-earn-points'),
    ('ar', 'customer-earn-points'),
    ('pl', 'customer-issue-credit'),
  ]) {
    testWidgets('in $locale, a minus sign typed before the digits is refused and nothing is sent ($button)',
        (tester) async {
      Intl.defaultLocale = locale;
      addTearDown(() => Intl.defaultLocale = null);
      final server = await _open(tester, 'OWNER');
      await tester.tap(find.byKey(Key(button)));
      await tester.pumpAndSettle();
      await press(tester, '-3');
      expect(fieldText(tester), '-3');
      expect(amountSays(tester), 'Type the amount without a sign.');
      expect(applyEnabled(tester), isFalse);
      await apply(tester, 'Goodwill');
      expect(server.grants, isEmpty, reason: 'never +3');
    });
  }

  testWidgets('redeeming store credit refuses a minus sign too', (tester) async {
    Intl.defaultLocale = 'en';
    addTearDown(() => Intl.defaultLocale = null);
    final server = await _open(tester, 'CASHIER');
    await tester.tap(find.text('Redeem credit'));
    await tester.pumpAndSettle();
    await press(tester, '-3');
    expect(fieldText(tester), '-3');
    expect(amountSays(tester), 'Type the amount without a sign.');
    await apply(tester, '');
    expect(server.grants, isEmpty);
  });

  // Arabic writes its own decimal separator (U+066B): read as the decimal it
  // is, in every language, never dropped with 12٫50 sent as 1250.
  for (final locale in ['ar', 'en', 'ur']) {
    testWidgets('in $locale, 12\u066B50 typed key by key is twelve fifty', (tester) async {
      Intl.defaultLocale = locale;
      addTearDown(() => Intl.defaultLocale = null);
      final server = await _open(tester, 'OWNER', currency: 'GBP');
      await tester.tap(find.byKey(const Key('customer-issue-credit')));
      await tester.pumpAndSettle();
      await press(tester, '12\u066B50');
      expect(fieldText(tester), '12\u066B50');
      expect(amountSays(tester), isNull);
      await apply(tester, 'Damaged goods');
      expect(server.grants.single.data, {'amount': '12.5', 'reason': 'Damaged goods'});
    });
  }

  testWidgets('a yen field refuses 12\u066B50 as it refuses any decimal, and sends nothing',
      (tester) async {
    Intl.defaultLocale = 'en';
    addTearDown(() => Intl.defaultLocale = null);
    final server = await _open(tester, 'OWNER', currency: 'JPY');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await press(tester, '12\u066B50');
    expect(fieldText(tester), '12\u066B50');
    expect(amountSays(tester), 'Whole amounts only.');
    await apply(tester, 'Damaged goods');
    expect(server.grants, isEmpty, reason: 'never 1250 yen');
  });

  // Any other mark — a thin space, Arabic's thousands separator, a fullwidth
  // point or comma — is kept where it was typed and refused, in every
  // language the app ships: the digits after it are never run into the whole
  // part.
  for (final locale in [
    'en', 'en_IN', 'en_US', 'en_AU', 'en_ZA', 'en_IE', 'en_CA', 'en_NZ', 'en_SG',
    'en_GB', 'pl', 'ro', 'pa', 'ur', 'bn', 'gu', 'ar',
  ]) {
    testWidgets('in $locale, a mark the field cannot read is kept and refused, for money and points',
        (tester) async {
      Intl.defaultLocale = locale;
      addTearDown(() => Intl.defaultLocale = null);
      final server = await _open(tester, 'OWNER', currency: 'GBP');
      for (final button in ['customer-issue-credit', 'customer-earn-points']) {
        await tester.tap(find.byKey(Key(button)));
        await tester.pumpAndSettle();
        for (final mark in ['\u2009', '\u066C', '\uFF0E', '\uFF0C', '-']) {
          await type(tester, '');
          await press(tester, '12${mark}50');
          expect(fieldText(tester), '12${mark}50', reason: 'U+${mark.codeUnitAt(0).toRadixString(16)}');
          expect(amountSays(tester), isNotNull, reason: 'U+${mark.codeUnitAt(0).toRadixString(16)}');
          expect(applyEnabled(tester), isFalse, reason: 'U+${mark.codeUnitAt(0).toRadixString(16)}');
        }
        await apply(tester, 'Goodwill');
        await tester.tap(find.text('Cancel'));
        await tester.pumpAndSettle();
      }
      expect(server.grants, isEmpty);
    });
  }

  testWidgets('a currency with no smaller unit says so when a decimal mark is typed',
      (tester) async {
    Intl.defaultLocale = 'pl';
    addTearDown(() => Intl.defaultLocale = null);
    final server = await _open(tester, 'OWNER', currency: 'JPY');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await press(tester, '12,');
    expect(fieldText(tester), '12,');
    expect(amountSays(tester), 'Whole amounts only.');
    // Never dropped with the digits after it run on: 12,50 is not 1250 yen.
    await press(tester, '50');
    expect(fieldText(tester), '12,50');
    expect(amountSays(tester), 'Whole amounts only.');
    await apply(tester, 'Damaged goods');
    expect(server.grants, isEmpty);
  });

  testWidgets('a business in Kuwait issues credit to a dinar\'s three places, sent as typed',
      (tester) async {
    final server = await _open(tester, 'OWNER', currency: 'KWD');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await type(tester, '1.125');
    expect(fieldText(tester), '1.125');
    await type(tester, '1.1255');
    expect(fieldText(tester), '1.1255', reason: 'kept to be seen');
    expect(amountSays(tester), 'At most 3 decimal places.', reason: 'a fourth place is finer than the fils');
    expect(applyEnabled(tester), isFalse);
    await _fill(tester, '1.125', 'Damaged goods');
    expect(server.grants.single.data, {'amount': '1.125', 'reason': 'Damaged goods'});
  });

  testWidgets('a currency with no minor unit takes fourteen whole digits and no point',
      (tester) async {
    final server = await _open(tester, 'OWNER', currency: 'VND');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await type(tester, '12345678901234');
    expect(fieldText(tester), '12345678901234');
    await type(tester, '123456789012345');
    expect(fieldText(tester), '123456789012345', reason: 'kept to be seen');
    expect(amountSays(tester), 'At most 14 digits.', reason: 'a fifteenth whole digit');
    expect(applyEnabled(tester), isFalse);
    await type(tester, '12345678901234.');
    expect(fieldText(tester), '12345678901234.', reason: 'kept to be seen');
    expect(amountSays(tester), 'Whole amounts only.', reason: 'the dong has no decimals');
    await _fill(tester, '12345678901234', 'Goodwill');
    expect(server.grants.single.data, {'amount': '12345678901234', 'reason': 'Goodwill'});
  });

  testWidgets('a fourteen-digit credit with its two places is sent exactly, never through a double',
      (tester) async {
    final server = await _open(tester, 'OWNER', currency: 'IDR');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await _fill(tester, '99999999999999.99', 'Goodwill');
    expect(server.grants.single.data, {'amount': '99999999999999.99', 'reason': 'Goodwill'});
  });

  testWidgets('redeeming credit follows the same currency', (tester) async {
    final server = await _open(tester, 'CASHIER', currency: 'KWD');
    await tester.tap(find.text('Redeem credit'));
    await tester.pumpAndSettle();
    await _fill(tester, '0.250', '');
    expect(server.grants.single.path, endsWith('/store-credit/redeem'));
    expect(server.grants.single.data, {'amount': '0.25', 'reason': ''});
  });

  testWidgets('points keep two places and sixteen whole digits, even where the money has three',
      (tester) async {
    final server = await _open(tester, 'OWNER', currency: 'KWD');
    await tester.tap(find.byKey(const Key('customer-earn-points')));
    await tester.pumpAndSettle();
    await type(tester, '1.12');
    await type(tester, '1.125');
    expect(amountSays(tester), 'At most 2 decimal places.');
    expect(applyEnabled(tester), isFalse);
    await type(tester, '1234567890123456.25');
    expect(amountSays(tester), isNull);
    await type(tester, '12345678901234567');
    expect(amountSays(tester), 'At most 16 digits before the decimals.', reason: 'a seventeenth whole digit');
    expect(applyEnabled(tester), isFalse);
    await _fill(tester, '1234567890123456.25', 'Goodwill');
    expect(server.grants.single.data, {'points': '1234567890123456.25', 'reason': 'Goodwill'});
  });

  testWidgets('an amount is sent as the plain decimal it is, and zero is not one', (tester) async {
    final server = await _open(tester, 'OWNER');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await _fill(tester, '0.00', 'Goodwill');
    expect(server.grants, isEmpty, reason: 'nothing to issue');
    await _fill(tester, '007.50', 'Goodwill');
    expect(server.grants.single.data, {'amount': '7.5', 'reason': 'Goodwill'});
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await _fill(tester, '.5', 'Goodwill');
    expect(server.grants.last.data, {'amount': '0.5', 'reason': 'Goodwill'});
  });

  testWidgets('a retry typed differently is the same request, with the same key', (tester) async {
    final server = await _open(tester, 'OWNER')
      ..grantStatus = 500;
    for (final typed in ['5', '5.00', '05']) {
      await tester.tap(find.byKey(const Key('customer-issue-credit')));
      await tester.pumpAndSettle();
      await _fill(tester, typed, 'Damaged goods');
      await tester.pumpAndSettle();
    }
    final keys = server.grants.map((r) => r.headers['Idempotency-Key']).toSet();
    expect(server.grants, hasLength(3));
    expect(keys, hasLength(1), reason: 'five is five however it is typed');
  });

  testWidgets('when the balance cannot be read, credit takes the server\'s own four places and the server judges',
      (tester) async {
    await _open(tester, 'OWNER', creditReadable: false);
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await type(tester, '1.1255');
    expect(amountSays(tester), isNull);
    await type(tester, '1.12555');
    expect(amountSays(tester), 'At most 4 decimal places.');
    expect(applyEnabled(tester), isFalse);
  });

  testWidgets('adding points needs a reason, then sends it with an Idempotency-Key', (tester) async {
    final server = await _open(tester, 'MANAGER');
    await tester.tap(find.byKey(const Key('customer-earn-points')));
    await tester.pumpAndSettle();
    // The button and the dialog's title say the same thing.
    expect(find.descendant(of: find.byKey(const Key('customer-earn-points')), matching: find.text('Add points')),
        findsOneWidget);
    expect(find.descendant(of: find.byType(AlertDialog), matching: find.text('Add points')), findsWidgets);

    await _fill(tester, '25', '   ');
    expect(server.grants, isEmpty, reason: 'no reason, nothing sent');
    expect(find.text('Say why. It is kept with your name.'), findsOneWidget);

    await _fill(tester, '25', 'Goodwill after a late order');
    expect(server.grants, hasLength(1));
    final sent = server.grants.single;
    expect(sent.path, '/customer-svc/customers/c-1/loyalty/earn');
    expect(sent.data, {'points': '25', 'reason': 'Goodwill after a late order'});
    expect(isV7(sent.headers['Idempotency-Key'] as String), isTrue);
  });

  testWidgets('a refused add reads in words, and a retry of the same request reuses the key; a changed one does not',
      (tester) async {
    final server = await _open(tester, 'OWNER')
      ..grantStatus = 403;
    Future<void> attempt(String amount, String reason) async {
      await tester.tap(find.byKey(const Key('customer-earn-points')));
      await tester.pumpAndSettle();
      await _fill(tester, amount, reason);
      await tester.pumpAndSettle();
    }

    await attempt('10', 'Apology');
    expect(find.text('Only a manager can do that.'), findsOneWidget);
    await attempt('10', 'Apology');
    await attempt('11', 'Apology');
    final keys = server.grants.map((r) => r.headers['Idempotency-Key']).toList();
    expect(keys, hasLength(3));
    expect(keys[1], keys[0], reason: 'same request, same key');
    expect(keys[2], isNot(keys[0]), reason: 'the request changed');

    // Once it lands, the same request is a new one.
    server.grantStatus = 200;
    await attempt('11', 'Apology');
    server.grantMessage = 'x';
    await attempt('11', 'Apology');
    final after = server.grants.map((r) => r.headers['Idempotency-Key']).toList();
    expect(after[3], keys[2], reason: 'the retry that landed kept its key');
    expect(after[4], isNot(after[3]), reason: 'a fresh add after a success is a fresh request');
  });

  testWidgets('issuing store credit needs a reason and sends it; redeem needs none', (tester) async {
    final server = await _open(tester, 'OWNER');
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await _fill(tester, '5', '');
    expect(server.grants, isEmpty);
    expect(find.text('Say why. It is kept with your name.'), findsOneWidget);
    await _fill(tester, '5', 'Damaged goods');
    expect(server.grants.single.path, '/customer-svc/customers/c-1/store-credit/issue');
    expect(server.grants.single.data, {'amount': '5', 'reason': 'Damaged goods'});
    expect(isV7(server.grants.single.headers['Idempotency-Key'] as String), isTrue);
    await tester.pumpAndSettle();

    await tester.tap(find.text('Redeem credit'));
    await tester.pumpAndSettle();
    await _fill(tester, '2', '');
    expect(server.grants.last.path, endsWith('/store-credit/redeem'));
  });

  testWidgets('a retried issue reuses its key, a changed one does not, and a success lets it go', (tester) async {
    final server = await _open(tester, 'OWNER')
      ..grantStatus = 500;
    Future<void> attempt(String amount) async {
      await tester.tap(find.byKey(const Key('customer-issue-credit')));
      await tester.pumpAndSettle();
      await _fill(tester, amount, 'Damaged goods');
      await tester.pumpAndSettle();
    }

    await attempt('5');
    await attempt('5');
    await attempt('6');
    server.grantStatus = 200;
    await attempt('6');
    await attempt('6');
    final k = server.grants.map((r) => r.headers['Idempotency-Key']).toList();
    expect(k[1], k[0]);
    expect(k[2], isNot(k[0]));
    expect(k[3], k[2], reason: 'the retry that landed kept its key');
    expect(k[4], isNot(k[3]), reason: 'a new issue after a success is new');
  });

  testWidgets('a refused issue reads in words', (tester) async {
    final server = await _open(tester, 'MANAGER')
      ..grantStatus = 403
      ..grantMessage = 'Only an owner or manager can issue store credit.';
    await tester.tap(find.byKey(const Key('customer-issue-credit')));
    await tester.pumpAndSettle();
    await _fill(tester, '5', 'Why not');
    await tester.pumpAndSettle();
    expect(find.text('Only an owner or manager can issue store credit.'), findsOneWidget);
    expect(server.grants, hasLength(1));
  });
}
