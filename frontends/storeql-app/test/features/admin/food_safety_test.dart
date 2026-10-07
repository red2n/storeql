import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/food_safety_screen.dart';

// ---------------------------------------------------------------------------
// Food-safety checks, from the screen.
//
// The server judges every check and keeps the limits it judged against. What
// the app must get right is what it sends: a temperature as a reading and
// never as a verdict, a checklist as a verdict and never as a reading, one
// Idempotency-Key per attempt however often Save is pressed, and a failure
// followed by the question of what was done about it. These tests assert the
// requests that leave the app, not only what is drawn.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Adapter implements HttpClientAdapter {
  final List<RequestOptions> posts = [];
  String points = '{"data":[]}';
  int pointsStatus = 200;

  /// Statuses for successive POSTs to /records; the last one repeats.
  List<int> recordStatuses = [201];
  String recordResponse = '{"data":{}}';

  @override
  void close({bool force = false}) {}

  ResponseBody _json(String body, int status) =>
      ResponseBody.fromString(body, status, headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      });

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<List<int>>? stream,
      Future<void>? cancel) async {
    final path = options.path;
    if (options.method == 'POST' || options.method == 'PUT') {
      posts.add(options);
      if (path.endsWith('/food-safety/records')) {
        final n = posts.where((p) => p.path.endsWith('/food-safety/records')).length;
        final status = recordStatuses[
            (n - 1).clamp(0, recordStatuses.length - 1)];
        return status < 300
            ? _json(recordResponse, status)
            : _json('{"error":{"code":"UNAVAILABLE","message":"Service unavailable"}}', status);
      }
      return _json('{"data":{}}', 200);
    }
    if (path.contains('/admin/stores')) {
      return _json(
          '{"data":[{"id":"store-1","name":"High Street","code":"HS","type":"STORE","status":"ACTIVE"}]}',
          200);
    }
    if (path.endsWith('/food-safety/points')) return _json(points, pointsStatus);
    return _json('{"data":[]}', 200);
  }
}

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

const _chiller = '''
{"id":"pt-1","storeId":"store-1","name":"Dairy chiller 1",
 "checkType":{"id":"t-1","code":"CHILLED_STORAGE","name":"Chilled storage","kind":"TEMPERATURE","maxValue":8.00,"statutory":true,"platform":true},
 "maxValue":8.00,"frequencyHours":4,"active":true,"dueStatus":"OK","openFailures":0}''';

const _overdueFreezer = '''
{"id":"pt-2","storeId":"store-1","name":"Zzz freezer",
 "checkType":{"id":"t-2","code":"FROZEN_STORAGE","name":"Frozen storage","kind":"TEMPERATURE","maxValue":-18.00},
 "maxValue":-18.00,"frequencyHours":4,"active":true,"dueStatus":"OVERDUE","openFailures":0}''';

const _hotCabinet = '''
{"id":"pt-4","storeId":"store-1","name":"Hot cabinet",
 "checkType":{"id":"t-4","code":"HOT_HOLDING","name":"Hot holding","kind":"TEMPERATURE","minValue":63.00,"statutory":true},
 "minValue":63.00,"frequencyHours":2,"active":true,"dueStatus":"DUE","openFailures":0}''';

const _opening = '''
{"id":"pt-3","storeId":"store-1","name":"Opening checks",
 "checkType":{"id":"t-3","code":"OPENING_CHECKS","name":"Opening checks","kind":"PASS_FAIL"},
 "frequencyHours":24,"active":true,"dueStatus":"DUE","openFailures":0}''';

const _failedRecord = '''
{"data":{"id":"rec-1","pointId":"pt-1","pointName":"Dairy chiller 1","checkTypeName":"Chilled storage",
 "kind":"TEMPERATURE","value":9.50,"maxValue":8.00,"result":"FAIL","openFailure":true,
 "correctiveActionCount":0,"recordedAt":"2026-09-11T08:00:00Z"}}''';

Future<_Adapter> _pump(WidgetTester tester,
    {String role = 'STOREKEEPER', String points = '{"data":[]}', int pointsStatus = 200}) async {
  final adapter = _Adapter()
    ..points = points
    ..pointsStatus = pointsStatus;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = adapter;
  tester.view.physicalSize = const Size(1400, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => _Auth(role)),
    ],
    child: const MaterialApp(home: Scaffold(body: FoodSafetyScreen())),
  ));
  await tester.pumpAndSettle();
  return adapter;
}

Map<String, dynamic> _body(RequestOptions o) =>
    (o.data is String ? jsonDecode(o.data as String) : o.data) as Map<String, dynamic>;

void main() {
  testWidgets('a warm reading is shown to fail, saved as a failure, and asks what was done',
      (tester) async {
    final adapter = await _pump(tester, points: '{"data":[$_chiller]}');
    adapter.recordResponse = _failedRecord;

    await tester.tap(find.text('Record'));
    await tester.pumpAndSettle();
    expect(find.text('Limit ≤ 8.00 °C — a legal limit'), findsOneWidget);

    await tester.enterText(find.byKey(const Key('fs-reading')), '9.5');
    await tester.pump();
    expect(find.textContaining('Outside the limit'), findsOneWidget);

    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    final record = adapter.posts.single;
    expect(record.path, endsWith('/inventory-svc/admin/inventory/food-safety/records'));
    expect(_body(record)['value'], 9.5);
    expect(_body(record).containsKey('passed'), isFalse,
        reason: 'a temperature is judged by the server, never declared a pass here');
    expect(record.headers['Idempotency-Key'], isNotEmpty);

    expect(find.text('Record what was done'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('fs-action')), 'Moved stock to the walk-in');
    await tester.pump();
    await tester.tap(find.text('Save action'));
    await tester.pumpAndSettle();

    final action = adapter.posts.last;
    expect(action.path, endsWith('/food-safety/records/rec-1/corrective-actions'));
    expect(_body(action)['action'], 'Moved stock to the walk-in');
    expect(_body(action)['foodDisposition'], 'NONE');
    expect(find.text('Record what was done'), findsNothing);
  });

  testWidgets('pressing Save again after a failure resends the same key', (tester) async {
    final adapter = await _pump(tester, points: '{"data":[$_chiller]}');
    adapter
      ..recordStatuses = [503, 201]
      ..recordResponse =
          '{"data":{"id":"rec-2","pointId":"pt-1","pointName":"Dairy chiller 1","kind":"TEMPERATURE","value":4.0,"result":"PASS","openFailure":false,"correctiveActionCount":0}}';

    await tester.tap(find.text('Record'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('fs-reading')), '4');
    await tester.pump();
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();
    expect(find.text('Service unavailable'), findsOneWidget);

    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    expect(adapter.posts, hasLength(2));
    expect(adapter.posts[1].headers['Idempotency-Key'],
        adapter.posts[0].headers['Idempotency-Key'],
        reason: 'a retry of one reading must not record it twice');
    expect(find.text('Record what was done'), findsNothing);
  });

  testWidgets('a checklist sends a verdict and never a reading', (tester) async {
    final adapter = await _pump(tester, points: '{"data":[$_opening]}');
    adapter.recordResponse =
        '{"data":{"id":"rec-3","pointId":"pt-3","pointName":"Opening checks","kind":"PASS_FAIL","result":"PASS","openFailure":false,"correctiveActionCount":0}}';

    await tester.tap(find.text('Record'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('fs-reading')), findsNothing);
    await tester.tap(find.text('Passed'));
    await tester.pump();
    await tester.tap(find.text('Save'));
    await tester.pumpAndSettle();

    final body = _body(adapter.posts.single);
    expect(body['passed'], isTrue);
    expect(body.containsKey('value'), isFalse);
  });

  testWidgets('an overdue check is listed before one that is done', (tester) async {
    await _pump(tester, points: '{"data":[$_chiller,$_overdueFreezer]}');
    final titles = tester
        .widgetList<ListTile>(find.byType(ListTile))
        .map((t) => (t.title as Text).data)
        .toList();
    expect(titles.first, 'Zzz freezer');
    expect(find.text('1 overdue'), findsOneWidget);
  });

  testWidgets('a storekeeper records and reads, but does not set up or sign off',
      (tester) async {
    await _pump(tester);
    expect(find.text('Today'), findsOneWidget);
    expect(find.text('Diary'), findsOneWidget);
    expect(find.text('Setup'), findsNothing);
    expect(find.text('Reviews'), findsNothing);
  });

  testWidgets('a manager also sets up the checks and signs them off', (tester) async {
    await _pump(tester, role: 'MANAGER');
    expect(find.text('Setup'), findsOneWidget);
    expect(find.text('Reviews'), findsOneWidget);
  });

  testWidgets('checks that cannot be loaded say so, not that nothing is due', (tester) async {
    await _pump(tester, points: '{}', pointsStatus: 500);
    expect(find.textContaining('No checks are set up'), findsNothing);
    expect(find.text('Retry'), findsOneWidget);
  });

  testWidgets('switching a check off asks why and sends the reason', (tester) async {
    final adapter = await _pump(tester, role: 'MANAGER', points: '{"data":[$_chiller]}');
    await tester.tap(find.text('Setup'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Switch off'));
    await tester.pumpAndSettle();
    expect(find.text('Confirm'), findsOneWidget);
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();
    expect(adapter.posts, isEmpty, reason: 'no switch without a reason');

    await tester.enterText(find.byKey(const Key('fs-reason')), 'Chiller replaced');
    await tester.pump();
    await tester.tap(find.text('Confirm'));
    await tester.pumpAndSettle();

    final post = adapter.posts.single;
    expect(post.path, endsWith('/inventory-svc/admin/food-safety/points/pt-1/deactivate'));
    expect(_body(post)['reason'], 'Chiller replaced');
  });

  // A reading is typed the way the app's language writes a number and read
  // with the shared amount reader, signed: every key stays where it was
  // typed, and one it cannot read is refused under the field with Save held
  // back. A filter used to drop such a key unsaid and read what was left, so
  // a failed statutory reading was recorded as a pass.
  group('a reading is read as typed, or refused in words', () {
    setUpAll(initializeDateFormatting);
    tearDown(() => Intl.defaultLocale = null);

    String fieldText(WidgetTester tester) =>
        tester.widget<TextField>(find.byKey(const Key('fs-reading'))).controller!.text;

    String? says(WidgetTester tester) =>
        tester.widget<TextField>(find.byKey(const Key('fs-reading'))).decoration?.errorText;

    bool saveEnabled(WidgetTester tester) =>
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Save')).onPressed != null;

    /// Presses [keys] one at a time, as a person types.
    Future<void> press(WidgetTester tester, String keys) async {
      for (final k in keys.split('')) {
        await tester.enterText(find.byKey(const Key('fs-reading')), fieldText(tester) + k);
        await tester.pump();
      }
    }

    testWidgets('in Arabic, 62\u066B5 at a hot cabinet is 62.5, shown to fail and saved as typed',
        (tester) async {
      Intl.defaultLocale = 'ar';
      final adapter = await _pump(tester, points: '{"data":[$_hotCabinet]}');
      adapter.recordResponse = _failedRecord;
      await tester.tap(find.text('Record'));
      await tester.pumpAndSettle();
      await press(tester, '62\u066B5');
      expect(fieldText(tester), '62\u066B5', reason: 'the decimal mark stays where it was typed');
      expect(says(tester), isNull);
      expect(find.text('Within the limit'), findsNothing, reason: 'never read as 625');
      expect(find.textContaining('Outside the limit'), findsOneWidget);
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(_body(adapter.posts.single)['value'], 62.5);
    });

    testWidgets('in English, 62,5 is refused in words and nothing is saved', (tester) async {
      Intl.defaultLocale = 'en_GB';
      final adapter = await _pump(tester, points: '{"data":[$_hotCabinet]}');
      await tester.tap(find.text('Record'));
      await tester.pumpAndSettle();
      await press(tester, '62,5');
      expect(fieldText(tester), '62,5');
      expect(says(tester),
          'Type the amount without thousands separators. Decimals go after a point.');
      expect(find.text('Within the limit'), findsNothing);
      expect(saveEnabled(tester), isFalse);
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(adapter.posts, isEmpty);
    });

    testWidgets('a freezer\'s \u221218 is minus eighteen, never 18', (tester) async {
      Intl.defaultLocale = 'en_GB';
      final adapter = await _pump(tester, points: '{"data":[$_overdueFreezer]}');
      adapter.recordResponse =
          '{"data":{"id":"rec-4","pointId":"pt-2","pointName":"Zzz freezer","kind":"TEMPERATURE","value":-18.0,"result":"PASS","openFailure":false,"correctiveActionCount":0}}';
      await tester.tap(find.text('Record'));
      await tester.pumpAndSettle();
      await press(tester, '1-8');
      expect(fieldText(tester), '1-8');
      expect(says(tester), 'Only one sign, before the digits.');
      expect(saveEnabled(tester), isFalse);

      await tester.enterText(find.byKey(const Key('fs-reading')), '');
      await press(tester, '\u221218');
      expect(fieldText(tester), '\u221218');
      expect(says(tester), isNull);
      expect(find.text('Within the limit'), findsOneWidget);
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(_body(adapter.posts.single)['value'], -18);
    });

    testWidgets('a point\'s limits are written and read in the app\'s language, a minus kept',
        (tester) async {
      Intl.defaultLocale = 'ro';
      final adapter =
          await _pump(tester, role: 'MANAGER', points: '{"data":[$_chiller]}');
      await tester.tap(find.text('Setup'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Edit'));
      await tester.pumpAndSettle();
      TextField field(String key) => tester.widget<TextField>(find.byKey(Key(key)));
      expect(field('fs-point-max').controller!.text, '8,00',
          reason: 'written as Romanian writes it, so it reads back unchanged');
      expect(field('fs-point-max').decoration?.errorText, isNull);

      await tester.enterText(find.byKey(const Key('fs-point-max')), '5.5');
      await tester.pump();
      expect(field('fs-point-max').decoration?.errorText,
          'Type the amount without thousands separators. Decimals go after a comma.');
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      expect(adapter.posts, isEmpty, reason: 'never saved at the check type\'s laxer limit');

      await tester.enterText(find.byKey(const Key('fs-point-max')), '5,5');
      await tester.enterText(find.byKey(const Key('fs-point-min')), '\u22121');
      await tester.pump();
      await tester.tap(find.text('Save'));
      await tester.pumpAndSettle();
      final put = adapter.posts.single;
      expect(_body(put)['minValue'], -1);
      expect(_body(put)['maxValue'], 5.5);
    });

    // A sign or a mark alone is no limit and is not blank either: refused
    // under the field, never saved as blank, which takes the check type's
    // own (laxer) limit.
    for (final (locale, min, max) in [
      ('ro', '-', ','),
      ('en_GB', '\u2212', '.'),
      ('en', '+', '.'),
      ('pl', '-', ','),
      ('ar', '-', '\u066B'),
    ]) {
      testWidgets('in $locale, a limit of "$min" or "$max" alone is refused and nothing is saved',
          (tester) async {
        Intl.defaultLocale = locale;
        final adapter =
            await _pump(tester, role: 'MANAGER', points: '{"data":[$_chiller]}');
        await tester.tap(find.text('Setup'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Edit'));
        await tester.pumpAndSettle();
        TextField field(String key) => tester.widget<TextField>(find.byKey(Key(key)));
        await tester.enterText(find.byKey(const Key('fs-point-min')), min);
        await tester.enterText(find.byKey(const Key('fs-point-max')), max);
        await tester.pump();
        expect(field('fs-point-min').decoration?.errorText, 'Type the digits after the sign.');
        expect(field('fs-point-max').decoration?.errorText, 'Type the amount in digits.');
        await tester.tap(find.text('Save'));
        await tester.pumpAndSettle();
        expect(adapter.posts, isEmpty, reason: 'never saved at the check type\'s laxer limit');
      });
    }
  });
}
