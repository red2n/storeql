import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/pricing_screen.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// Making Tax Digital (18.5): the digital link from the VAT return on the screen
// to HMRC. The manager registers the number, sees the periods HMRC expects,
// files one with the declaration, and sees what came back — from the same
// figures the screen shows, with nobody re-keying them.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  bool registered;
  final List<RequestOptions> requests = [];
  int submitStatus = 201;
  _Server({required this.registered});

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
    if (o.path.endsWith('/vat-return')) {
      body =
          '{"data":{"box1":40.00,"box2":0,"box3":40.00,"box4":30.00,"box5":10.00,"box6":200.00,"box7":150.00,"box8":0,"box9":0,'
          '"computedBoxes":[1,3,4,5,6,7],"notComputedBoxes":[2,8,9],"fitToFile":true,"caveat":"NI assumption"}}';
    } else if (o.path.endsWith('/mtd/registration') && o.method == 'PUT') {
      registered = true;
      body =
          '{"data":{"registered":true,"vrn":"123456782","provider":"SIMULATED","connected":false,"providers":["SIMULATED"],"hmrcConfigured":false}}';
    } else if (o.path.endsWith('/mtd/registration')) {
      body = registered
          ? '{"data":{"registered":true,"vrn":"123456782","provider":"SIMULATED","connected":false,"providers":["SIMULATED"],"hmrcConfigured":false}}'
          : '{"data":{"registered":false,"providers":["SIMULATED"],"hmrcConfigured":false}}';
    } else if (o.path.endsWith('/mtd/obligations')) {
      body =
          '{"data":[{"periodKey":"26A2","start":"2026-04-01T00:00:00Z","end":"2026-06-30T00:00:00Z","due":"2026-08-07T00:00:00Z","status":"F"},'
          '{"periodKey":"26A3","start":"2026-07-01T00:00:00Z","end":"2026-09-30T00:00:00Z","due":"2026-11-07T00:00:00Z","status":"O"}]}';
    } else if (o.path.endsWith('/mtd/submissions') && o.method == 'POST') {
      status = submitStatus;
      body = status == 201
          ? '{"data":{"id":"sub-1","periodKey":"26A3","status":"ACCEPTED","submittedAt":"2026-09-12T10:00:00Z","box1":40.00,"box5":10.00,"box6":200,"formBundleNumber":"256660290587"}}'
          : '{"error":{"code":"MTD_DUPLICATE_SUBMISSION","message":"Period 26A3 has already been filed and accepted"}}';
    } else if (o.path.endsWith('/mtd/submissions')) {
      body =
          '{"data":[{"id":"sub-0","periodKey":"26A2","status":"ACCEPTED","submittedAt":"2026-07-20T10:00:00Z","box1":12.50,"box5":2.50,"box6":80,"formBundleNumber":"111111111111"},'
          '{"id":"sub-x","periodKey":"26A1","status":"REJECTED","submittedAt":"2026-04-20T10:00:00Z","box1":0,"box5":0,"box6":0,"errorCode":"INVALID_MONETARY_AMOUNT","errorMessage":"boxes 6 to 9 are whole pounds"}]}';
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

Future<_Server> _pump(WidgetTester tester, {bool registered = true}) async {
  tester.view.physicalSize = const Size(1200, 2600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(registered: registered);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
      child: const MaterialApp(home: PricingScreen()),
    ),
  );
  await tester.pumpAndSettle();
  await tester.tap(find.text('VAT Return'));
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
  testWidgets(
    'an unregistered business is told to register, and the simulator is what is on offer',
    (tester) async {
      final server = await _pump(tester, registered: false);
      await tester.dragUntilVisible(
        find.byKey(const Key('mtd-registration')),
        find.byType(ListView).last,
        const Offset(0, -300),
      );
      await tester.pumpAndSettle();
      expect(find.text('No VAT number registered'), findsOneWidget);
      expect(find.textContaining('HMRC is not configured'), findsOneWidget);
      expect(find.text('Register'), findsOneWidget);
      // Nothing else is asked for until a number exists.
      expect(
        server.requests.where((r) => r.path.endsWith('/mtd/obligations')),
        isEmpty,
      );

      await tester.tap(find.byKey(const Key('mtd-register')));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.byKey(const Key('mtd-vrn')),
        'GB 123 4567 82',
      );
      await tester.tap(find.byKey(const Key('mtd-register-save')));
      await tester.pumpAndSettle();
      final put = server.requests.lastWhere((r) => r.method == 'PUT');
      expect(put.path, endsWith('/vat-return/mtd/registration'));
      final body = put.data is String
          ? jsonDecode(put.data as String)
          : put.data;
      expect(body, {'vrn': 'GB 123 4567 82', 'provider': 'SIMULATED'});
      expect(find.textContaining('VAT number 123456782'), findsOneWidget);
    },
  );

  testWidgets(
    'a registered business sees its obligations and filings, and files an open period with the declaration',
    (tester) async {
      final server = await _pump(tester);
      await tester.dragUntilVisible(
        find.byKey(const Key('mtd-file-26A3')),
        find.byType(ListView).last,
        const Offset(0, -300),
      );
      await tester.pumpAndSettle();
      expect(find.textContaining('VAT number 123456782'), findsOneWidget);
      expect(
        find.textContaining('26A2 · 1 Apr 2026 → 30 Jun 2026'),
        findsOneWidget,
      );
      expect(find.text('Filed'), findsOneWidget);
      expect(find.textContaining('Open · due 7 Nov 2026'), findsOneWidget);
      expect(
        find.byKey(const Key('mtd-file-26A2')),
        findsNothing,
        reason: 'a fulfilled period cannot be filed again',
      );
      expect(find.byKey(const Key('mtd-filing-sub-0')), findsOneWidget);
      expect(find.textContaining('form bundle 111111111111'), findsOneWidget);
      expect(find.textContaining('INVALID_MONETARY_AMOUNT'), findsOneWidget);

      await tester.tap(find.byKey(const Key('mtd-file-26A3')));
      await tester.pumpAndSettle();
      // Without the declaration the return cannot be filed.
      expect(
        tester
            .widget<FilledButton>(find.byKey(const Key('mtd-file-submit')))
            .onPressed,
        isNull,
      );
      await tester.tap(find.byKey(const Key('mtd-finalised')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('mtd-file-submit')));
      await tester.pumpAndSettle();
      expect(find.byType(AlertDialog), findsNothing);
      final post = server.requests.lastWhere((r) => r.method == 'POST');
      expect(post.path, endsWith('/vat-return/mtd/submissions'));
      final body = post.data is String
          ? jsonDecode(post.data as String)
          : post.data;
      expect(body['periodKey'], '26A3');
      expect(body['from'], '2026-07-01T00:00:00Z');
      expect(body['to'], '2026-10-01T00:00:00.000Z');
      expect(body['finalised'], isTrue);
      expect((body['client'] as Map)['timezone'], startsWith('UTC'));
      expect((body['client'] as Map)['screens'], contains('width='));
      // No box figure is typed here: the server computes them from the period.
      expect(body.containsKey('box1'), isFalse);
    },
  );

  testWidgets('a refusal is shown in the dialog with HMRC\'s own words', (
    tester,
  ) async {
    final server = await _pump(tester)
      ..submitStatus = 409;
    await tester.dragUntilVisible(
      find.byKey(const Key('mtd-file-26A3')),
      find.byType(ListView).last,
      const Offset(0, -300),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mtd-file-26A3')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mtd-finalised')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mtd-file-submit')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('mtd-file-error')), findsOneWidget);
    expect(find.textContaining('already been filed'), findsOneWidget);
    expect(find.byType(AlertDialog), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST').length, 1);
  });
}
