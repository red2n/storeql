import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/store_instruments_dialog.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Registering a scale: its capacity and its scale interval, as the plate marks
// them, are read the way the app's language writes a number and sent as the
// decimals typed. One the form cannot read is refused under its field and
// nothing is registered — sent as none, Romanian's 0,005 kg interval left the
// register without one, and a lone mark did the same.
// ---------------------------------------------------------------------------

const _store = '01a0d200-0000-7000-8000-000000000001';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'POST') return jsonResponse('{"data":{}}', 201);
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _open(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1200, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
    child: const MaterialApp(
      home: Scaffold(
        body: StoreInstrumentsDialog(
          store: StoreInfo(id: _store, name: 'Deli', code: 'DL', type: 'STORE', status: 'ACTIVE'),
          isManager: true,
        ),
      ),
    ),
  ));
  await tester.pumpAndSettle();
  await tester.tap(find.text('Register scale'));
  await tester.pumpAndSettle();
  await tester.enterText(
      find.widgetWithText(TextField, 'Name in the shop (e.g. Deli scale 2)'), 'Deli scale 2');
  await tester.enterText(find.widgetWithText(TextField, 'Serial number (from the plate)'), 'SN-1');
  return server;
}

String? _says(WidgetTester tester, String key) =>
    tester.widget<TextField>(find.byKey(Key(key))).decoration?.errorText;

Future<void> _type(WidgetTester tester, String key, String text) async {
  for (var i = 1; i <= text.length; i++) {
    await tester.enterText(find.byKey(Key(key)), text.substring(0, i));
    await tester.pump();
  }
}

Iterable<RequestOptions> _posts(_Server server) => server.requests.where((r) => r.method == 'POST');

Map<String, dynamic> _body(RequestOptions r) =>
    (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>;

void main() {
  tearDown(() => Intl.defaultLocale = null);

  for (final (locale, capacity, interval, sentCapacity, sentInterval) in [
    ('ro', '15,5', '0,005', '15.5', '0.005'),
    ('en_GB', '15.5', '0.005', '15.5', '0.005'),
    ('en', '6.125', '0.002', '6.125', '0.002'),
    ('pl', '6,125', '0.002', '6.125', '0.002'),
    ('ar', '15٫5', '0٫005', '15.5', '0.005'),
  ]) {
    testWidgets('in $locale, $capacity kg at $interval is registered as $sentCapacity at $sentInterval',
        (tester) async {
      Intl.defaultLocale = locale;
      final server = await _open(tester);
      await _type(tester, 'instrument-capacity', capacity);
      await _type(tester, 'instrument-interval', interval);
      expect(_says(tester, 'instrument-capacity'), isNull);
      expect(_says(tester, 'instrument-interval'), isNull);
      await tester.tap(find.text('Register'));
      await tester.pumpAndSettle();
      final post = _posts(server).single;
      expect(post.path, endsWith('/admin/stores/$_store/weighing-instruments'));
      final body = _body(post);
      expect(body['maxCapacity'], sentCapacity);
      expect(body['capacityUom'], 'KG');
      expect(body['scaleInterval'], sentInterval);
    });
  }

  testWidgets('a capacity and an interval left blank are left out', (tester) async {
    final server = await _open(tester);
    await tester.enterText(find.byKey(const Key('instrument-capacity')), ' ');
    await tester.tap(find.text('Register'));
    await tester.pumpAndSettle();
    final body = _body(_posts(server).single);
    expect(body.containsKey('maxCapacity'), isFalse);
    expect(body.containsKey('capacityUom'), isFalse);
    expect(body.containsKey('scaleInterval'), isFalse);
  });

  for (final (locale, field, typed, why) in [
    ('ro', 'instrument-capacity', '1.250',
        'Type the amount without thousands separators. Decimals go after a comma.'),
    ('en_GB', 'instrument-interval', '0,005',
        'Type the amount without thousands separators. Decimals go after a point.'),
    ('en', 'instrument-capacity', '15,5',
        'Type the amount without thousands separators. Decimals go after a point.'),
    ('pl', 'instrument-capacity', '1.250',
        'A point may group thousands here. Type the figure without grouping, with any decimals after a comma.'),
    ('ar', 'instrument-capacity', '-15', 'Type the amount without a sign.'),
    ('en_GB', 'instrument-interval', '.', 'Type the amount in digits.'),
    ('ro', 'instrument-interval', ',', 'Type the amount in digits.'),
    ('ar', 'instrument-capacity', '٫', 'Type the amount in digits.'),
    ('en', 'instrument-capacity', '1e2', 'Only digits and a decimal point.'),
  ]) {
    testWidgets('in $locale, "$typed" in ${field.split('-').last} is refused under it and nothing is registered',
        (tester) async {
      Intl.defaultLocale = locale;
      final server = await _open(tester);
      await tester.enterText(find.byKey(Key(field)), typed);
      await tester.pump();
      expect(_says(tester, field), why);
      await tester.tap(find.text('Register'));
      await tester.pumpAndSettle();
      expect(_posts(server), isEmpty);
      expect(find.text('A figure cannot be read. Correct the one marked.'), findsOneWidget);
    });
  }
}
