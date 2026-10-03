import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/return_policy_card.dart';

// ---------------------------------------------------------------------------
// Return-controls: the Return policy card on the business settings screen. It
// reads the policy in force, and Edit puts the window, the cashier's limit
// (empty = no limit) and the no-receipt choice back; a refusal is shown in words.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool refuse = false;

  /// The business's home currency, and the cashier's limit in force.
  String home = 'GBP';
  String ceiling = '50.0';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/admin/return-policy')) {
      if (o.method == 'PUT') {
        return refuse
            ? _json('{"error":{"code":"VALIDATION","message":"window must be at most 3650 days"}}', 400)
            : _json('{"data":{"windowDays":14,"noReceiptAllowed":false}}', 200);
      }
      return _json(
          '{"data":{"windowDays":30,"cashierCeiling":$ceiling,"noReceiptAllowed":false}}', 200);
    }
    if (o.path.endsWith('/admin/tenant/fx-rates')) {
      return _json('{"data":{"home":"$home","rates":[]}}', 200);
    }
    return _json('{"data":null}', 200);
  }

  static ResponseBody _json(String body, int status) => ResponseBody.fromString(body, status,
      headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
}

Future<_Server> _pump(WidgetTester tester,
    {bool refuse = false, String home = 'GBP', String ceiling = '50.0'}) async {
  tester.view.physicalSize = const Size(1000, 1200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server()
    ..refuse = refuse
    ..home = home
    ..ceiling = ceiling;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: Scaffold(body: ReturnPolicyCard())),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('the card loads the policy in force and says it in words', (tester) async {
    await _pump(tester);
    final summary = tester.widget<Text>(find.byKey(const Key('return-policy-summary'))).data!;
    expect(summary, contains('within 30 days'));
    expect(summary, contains('up to '));
    expect(summary, contains('50'));
    expect(summary, contains('Returns with no receipt are not taken.'));
  });

  testWidgets('Edit saves the window, an emptied limit as none, and no-receipt off',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('return-policy-edit')));
    await tester.pumpAndSettle();
    // The limit, as it stands, written at the currency's places.
    expect(find.text('50.00'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('policy-window')), '14');
    await tester.enterText(find.byKey(const Key('policy-ceiling')), '');
    await tester.tap(find.byKey(const Key('policy-save')));
    await tester.pumpAndSettle();

    final put = server.requests.singleWhere((r) => r.method == 'PUT');
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body, {
      'windowDays': 14,
      'cashierCeiling': null,
      'noReceiptAllowed': false,
      'noReceiptCeiling': null,
    });
    expect(find.text('Return policy saved.'), findsOneWidget);
    expect(find.byKey(const Key('policy-save')), findsNothing);
  });

  testWidgets('no-receipt returns on sends their limit', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('return-policy-edit')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('policy-no-receipt')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('policy-no-receipt-ceiling')), '20.5');
    await tester.tap(find.byKey(const Key('policy-save')));
    await tester.pumpAndSettle();
    final put = server.requests.singleWhere((r) => r.method == 'PUT');
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body['noReceiptAllowed'], true);
    expect(body['noReceiptCeiling'], '20.5');
    expect(body['cashierCeiling'], '50');
  });

  testWidgets('a limit that is not a number is stopped here and nothing is sent', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('return-policy-edit')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('policy-ceiling')), 'lots');
    await tester.pump();
    expect(tester.widget<TextField>(find.byKey(const Key('policy-ceiling'))).decoration?.errorText,
        'Only digits and a decimal point.');
    await tester.tap(find.byKey(const Key('policy-save')));
    await tester.pumpAndSettle();
    expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
  });

  testWidgets("the server's refusal is shown and the dialog stays open", (tester) async {
    await _pump(tester, refuse: true);
    await tester.tap(find.byKey(const Key('return-policy-edit')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('policy-save')));
    await tester.pumpAndSettle();
    expect(find.text('window must be at most 3650 days'), findsOneWidget);
    expect(find.byKey(const Key('policy-save')), findsOneWidget);
  });

  // The limits are money in the home currency, to its places, read the way
  // the app's language writes a number and sent as the decimals typed: read
  // with a point, Romanian's 25,50 was "not a number" and a blank limit was
  // never in doubt — only text that is blank is no limit.
  group('limits are read as typed, or refused', () {
    tearDown(() => Intl.defaultLocale = null);

    Future<_Server> open(WidgetTester tester, String home, {String ceiling = '50.0'}) async {
      final server = await _pump(tester, home: home, ceiling: ceiling);
      await tester.tap(find.byKey(const Key('return-policy-edit')));
      await tester.pumpAndSettle();
      return server;
    }

    String? says(WidgetTester tester, String key) =>
        tester.widget<TextField>(find.byKey(Key(key))).decoration?.errorText;

    String fieldText(WidgetTester tester, String key) =>
        tester.widget<TextField>(find.byKey(Key(key))).controller!.text;

    Future<void> press(WidgetTester tester, String key, String text) async {
      for (var i = 1; i <= text.length; i++) {
        await tester.enterText(find.byKey(Key(key)), text.substring(0, i));
        await tester.pump();
      }
    }

    Map<String, dynamic> put(_Server server) {
      final r = server.requests.singleWhere((r) => r.method == 'PUT');
      return (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>;
    }

    for (final (locale, home, ceiling, written) in [
      ('ro', 'RON', '50.0', '50,00'),
      ('en_GB', 'KWD', '1.234', '1.234'),
      ('pl', 'KWD', '1.5', '1,500'),
      ('ar', 'JPY', '1500', '1500'),
      ('en', 'USD', '12.5', '12.50'),
    ]) {
      testWidgets('in $locale, a limit of $ceiling $home starts as $written and is kept unchanged', (tester) async {
        Intl.defaultLocale = locale;
        final server = await open(tester, home, ceiling: ceiling);
        expect(fieldText(tester, 'policy-ceiling'), written);
        expect(says(tester, 'policy-ceiling'), isNull);
        await tester.tap(find.byKey(const Key('policy-save')));
        await tester.pumpAndSettle();
        expect(put(server)['cashierCeiling'], double.parse(ceiling) == double.parse(ceiling).roundToDouble()
            ? double.parse(ceiling).toInt().toString()
            : ceiling.replaceFirst(RegExp(r'0+$'), ''));
      });
    }

    for (final (locale, home, cashier, noReceipt, sent) in [
      ('ro', 'RON', '25,50', '10', ('25.5', '10')),
      ('en_GB', 'GBP', '100', '7.25', ('100', '7.25')),
      ('en', 'USD', '0.5', '20', ('0.5', '20')),
      ('pl', 'PLN', '12,5', '3,75', ('12.5', '3.75')),
      ('ar', 'KWD', '1٫125', '0٫5', ('1.125', '0.5')),
    ]) {
      testWidgets('in $locale, limits of $cashier and $noReceipt $home are saved as typed', (tester) async {
        Intl.defaultLocale = locale;
        final server = await open(tester, home);
        await press(tester, 'policy-ceiling', cashier);
        await tester.tap(find.byKey(const Key('policy-no-receipt')));
        await tester.pumpAndSettle();
        await press(tester, 'policy-no-receipt-ceiling', noReceipt);
        await tester.tap(find.byKey(const Key('policy-save')));
        await tester.pumpAndSettle();
        expect(put(server)['cashierCeiling'], sent.$1);
        expect(put(server)['noReceiptCeiling'], sent.$2);
      });
    }

    // The window is the whole number of days typed, or it is refused under
    // its field and nothing is saved. Read as a number literal, 0x1e was
    // saved as a window of thirty days.
    for (final (locale, typed) in const [
      ('en_GB', '0x1e'),
      ('ro', '+0x1F'),
      ('pl', '0X1E'),
      ('ar', '0x1e'),
      ('en', '30.'),
    ]) {
      testWidgets('in $locale, a window of "$typed" days is refused, never saved as another figure',
          (tester) async {
        Intl.defaultLocale = locale;
        final server = await open(tester, 'GBP');
        await press(tester, 'policy-window', typed);
        await tester.tap(find.byKey(const Key('policy-save')));
        await tester.pumpAndSettle();
        expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
        expect(says(tester, 'policy-window'), isNotNull);

        // Emptied, it is asked for in words: never saved as a default.
        await tester.enterText(find.byKey(const Key('policy-window')), '');
        await tester.pump();
        expect(says(tester, 'policy-window'), isNull);
        await tester.tap(find.byKey(const Key('policy-save')));
        await tester.pumpAndSettle();
        expect(find.text('The window is a whole number of days.'), findsOneWidget);
        expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
      });
    }

    for (final (locale, home, typed) in [
      ('ro', 'RON', '25.50'),
      ('en', 'EUR', '25,50'),
      ('pl', 'PLN', '1.250'),
      ('en_GB', 'JPY', '25.5'),
      ('ar', 'KWD', '٫'),
      ('en_GB', 'GBP', '-5'),
    ]) {
      testWidgets('in $locale, a limit of "$typed" $home is refused, never saved as no limit', (tester) async {
        Intl.defaultLocale = locale;
        final server = await open(tester, home);
        await press(tester, 'policy-ceiling', typed);
        expect(says(tester, 'policy-ceiling'), isNotNull);
        await tester.tap(find.byKey(const Key('policy-save')));
        await tester.pumpAndSettle();
        expect(server.requests.where((r) => r.method == 'PUT'), isEmpty);
      });
    }
  });
}
