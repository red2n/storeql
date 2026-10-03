import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/pos/cash_screen.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The till's cash reports (payment-svc, 30 Sep 2026). The X report shows every
// term of the expected cash in words; the close takes an optional note and says
// over or short; a manager settles the store's own day, is told when its time
// zone was assumed, is shown the stored report when the day is asked for
// again, and gives a reason to correct one.
// ---------------------------------------------------------------------------

const _storeId = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c0001';
const _openId = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3cabcd';

class _Server implements HttpClientAdapter {
  /// The day report each ask answers: [settleStatus] 201 writes it, 200 answers the stored one.
  int settleStatus = 201;
  bool zoneAssumed = false;
  int version = 1;
  bool storedExists = true;
  final List<RequestOptions> requests = [];

  static ResponseBody _json(Object body, int status) => ResponseBody.fromString(
        jsonEncode(body),
        status,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );

  Map<String, dynamic> get _day => {
        'id': 'z-1',
        'storeId': _storeId,
        'businessDate': '2026-09-29',
        'totalSales': 500,
        'totalRefunds': 20,
        'netSales': 480,
        'openingFloat': 100,
        'cashSales': 300,
        'cashRefunds': 20,
        'payIns': 15,
        'payOuts': 5,
        'cashDrops': 200,
        'expectedCash': 190,
        'countedCash': 185,
        'overShort': -5,
        'transactionCount': 42,
        'currency': 'GBP',
        'version': version,
        if (version > 1) 'correctionReason': 'Miscounted the coins',
        'timeZone': zoneAssumed ? 'UTC' : 'Pacific/Auckland',
        'zoneAssumed': zoneAssumed,
        'regenerated': settleStatus == 201,
      };

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final p = o.path;
    if (p.endsWith('/tenant-svc/admin/tenant')) {
      return _json({
        'data': {'id': 't-1', 'name': 'Corner Shop', 'currency': 'GBP'}
      }, 200);
    }
    if (p.endsWith('/till-sessions/current')) {
      return _json({
        'data': {'id': _openId, 'storeId': _storeId, 'status': 'OPEN'}
      }, 200);
    }
    if (p.endsWith('/x-report')) {
      return _json({
        'data': {
          'tillSessionId': _openId,
          'floatAmount': 100,
          'cashSales': 300,
          'cashRefunds': 20,
          'payIns': 15,
          'payOuts': 5,
          'cashDropsTotal': 200,
          'expectedCashInTill': 190,
          'grossSales': 500,
          'totalRefunds': 20,
          'netSales': 480,
          'basis': 'WINDOW',
        }
      }, 200);
    }
    if (p.endsWith('/close')) {
      return _json({
        'data': {
          'tillSessionId': _openId,
          'expectedCashInTill': 190,
          'countedCash': 185,
          'overShort': -5,
          'note': (o.data as Map)['note'],
        }
      }, 200);
    }
    if (o.method == 'POST' && p.endsWith('/z-report')) {
      final body = o.data as Map;
      if (body['correctionOf'] != null) version = 2;
      return _json({'data': _day}, settleStatus);
    }
    if (o.method == 'GET' && p.endsWith('/z-report')) {
      if (!storedExists) {
        return _json({
          'error': {'code': 'Z_REPORT_NOT_FOUND', 'message': ''}
        }, 404);
      }
      return _json({
        'data': {..._day, 'regenerated': false}
      }, 200);
    }
    return _json({'data': []}, 200);
  }

  List<RequestOptions> posts(String tail) => requests
      .where((r) => r.method == 'POST' && r.path.endsWith(tail))
      .toList();
}

Future<_Server> _pump(WidgetTester tester, {String role = 'MANAGER', _Server? server}) async {
  tester.view.physicalSize = const Size(900, 2200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final srv = server ?? _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = srv;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
      posStoreProvider.overrideWith((ref) => _storeId),
      posStoresProvider.overrideWith((ref) async => const [
            StoreInfo(id: _storeId, name: 'High Street', code: 'HS', type: 'STORE', status: 'ACTIVE'),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: CashScreen())),
  ));
  await tester.pumpAndSettle();
  return srv;
}

String get _today {
  final n = DateTime.now();
  return '${n.year.toString().padLeft(4, '0')}-${n.month.toString().padLeft(2, '0')}-${n.day.toString().padLeft(2, '0')}';
}

Future<void> _settle(WidgetTester tester, {String counted = '185'}) async {
  await tester.enterText(find.byKey(const Key('day-report-counted')), counted);
  await tester.ensureVisible(find.byKey(const Key('day-report-settle')));
  await tester.tap(find.byKey(const Key('day-report-settle')));
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(initializeDateFormatting);

  group('the X report', () {
    testWidgets('shows every term of the expected cash in words', (tester) async {
      await _pump(tester, role: 'CASHIER');
      for (final term in [
        'Opening float',
        'Cash sales',
        'Cash refunds',
        'Paid in',
        'Paid out',
        'Cash drops',
        'Expected cash in till',
      ]) {
        expect(find.text(term), findsOneWidget, reason: term);
      }
      // What comes off the drawer reads as a minus.
      expect(find.text('£300.00'), findsWidgets);
      expect(find.text('-£20.00'), findsWidgets);
      expect(find.text('-£200.00'), findsOneWidget);
      expect(find.text('£190.00'), findsOneWidget);
    });

    testWidgets('a cashier is offered none of the management tools', (tester) async {
      await _pump(tester, role: 'CASHIER');
      expect(find.byKey(const Key('till-management')), findsNothing);
      expect(find.byKey(const Key('day-report-card')), findsNothing);
    });
  });

  group('closing the till', () {
    testWidgets('sends the counted cash and an optional note, and says short', (tester) async {
      final server = await _pump(tester, role: 'CASHIER');
      await tester.ensureVisible(find.widgetWithText(FilledButton, 'Close till'));
      await tester.tap(find.widgetWithText(FilledButton, 'Close till'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('close-counted')), '185');
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('close-difference')), findsOneWidget);
      expect(find.textContaining('Short by £5.00'), findsOneWidget);
      await tester.enterText(find.byKey(const Key('close-note')), ' Coins miscounted ');
      await tester.tap(find.descendant(
          of: find.byType(AlertDialog), matching: find.widgetWithText(FilledButton, 'Close till')));
      await tester.pumpAndSettle();

      expect(server.posts('/close').single.data, {'countedCash': 185.0, 'note': 'Coins miscounted'});
      expect(find.text('Till closed'), findsOneWidget);
      expect(find.byKey(const Key('till-closed-over-short')), findsOneWidget);
      expect(find.text('Short by £5.00'), findsOneWidget);
      expect(find.text('Note: Coins miscounted'), findsOneWidget);
    });

    testWidgets('with no note the request carries none', (tester) async {
      final server = await _pump(tester, role: 'CASHIER');
      await tester.ensureVisible(find.widgetWithText(FilledButton, 'Close till'));
      await tester.tap(find.widgetWithText(FilledButton, 'Close till'));
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(
          of: find.byType(AlertDialog), matching: find.widgetWithText(FilledButton, 'Close till')));
      await tester.pumpAndSettle();
      expect(server.posts('/close').single.data, {'countedCash': 190.0});
    });
  });

  group('the day report', () {
    testWidgets(
        'settling sends the store and the count, leaves today to the store\'s own clock, and shows every term',
        (tester) async {
      final server = await _pump(tester);
      expect(find.text('Today at the store'), findsOneWidget);
      await _settle(tester);
      final body = server.posts('/z-report').single.data as Map;
      // No day is sent until one is picked: the device may sit in another time zone.
      expect(body, {'storeId': _storeId, 'countedCash': 185.0});
      expect(find.byKey(const Key('day-report-written-note')), findsOneWidget);
      for (final term in [
        'Cash sales',
        'Cash refunds',
        'Paid in',
        'Paid out',
        'Cash drops',
        'Expected cash',
        'Cash counted',
      ]) {
        expect(find.text(term), findsWidgets, reason: term);
      }
      expect(find.text('Short by £5.00'), findsOneWidget);
      expect(find.textContaining("The store's day of"), findsOneWidget);
      expect(find.textContaining('Pacific/Auckland'), findsOneWidget);
      expect(find.byKey(const Key('day-report-zone-assumed')), findsNothing);
    });

    testWidgets('a day picked is the day asked for', (tester) async {
      final server = await _pump(tester);
      await tester.ensureVisible(find.byKey(const Key('day-report-date')));
      await tester.tap(find.byKey(const Key('day-report-date')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('OK'));
      await tester.pumpAndSettle();
      expect(find.text('Today at the store'), findsNothing);
      await _settle(tester);
      expect((server.posts('/z-report').single.data as Map)['businessDate'], _today);
    });

    testWidgets('marks a day counted in an assumed time zone', (tester) async {
      await _pump(tester, server: _Server()..zoneAssumed = true);
      await _settle(tester);
      expect(find.byKey(const Key('day-report-zone-assumed')), findsOneWidget);
      expect(find.textContaining('time zone could not be read'), findsOneWidget);
    });

    testWidgets('asking for a settled day shows the stored report and says so', (tester) async {
      await _pump(tester, server: _Server()..settleStatus = 200);
      await _settle(tester);
      expect(find.byKey(const Key('day-report-stored-note')), findsOneWidget);
      expect(find.byKey(const Key('day-report-written-note')), findsNothing);
    });

    testWidgets('the stored report can be read without a count; none says so', (tester) async {
      final server = _Server();
      await _pump(tester, server: server);
      await tester.ensureVisible(find.byKey(const Key('day-report-stored')));
      await tester.tap(find.byKey(const Key('day-report-stored')));
      await tester.pumpAndSettle();
      expect(server.requests.where((r) => r.method == 'GET' && r.path.endsWith('/z-report')), hasLength(1));
      expect(find.byKey(const Key('day-report-stored-note')), findsOneWidget);

      server.storedExists = false;
      await tester.tap(find.byKey(const Key('day-report-stored')));
      await tester.pumpAndSettle();
      expect(find.text('This day has not been settled yet.'), findsOneWidget);
      expect(find.byKey(const Key('day-report-title')), findsNothing);
    });

    testWidgets('a correction needs a reason, names the report it replaces and shows its version',
        (tester) async {
      final server = await _pump(tester, server: _Server()..settleStatus = 200);
      await _settle(tester);
      await tester.ensureVisible(find.byKey(const Key('day-report-correct')));
      await tester.tap(find.byKey(const Key('day-report-correct')));
      await tester.pumpAndSettle();
      final save = find.widgetWithText(FilledButton, 'Save correction');
      expect(tester.widget<FilledButton>(save).onPressed, isNull, reason: 'no reason yet');
      await tester.enterText(find.byKey(const Key('correction-counted')), '190');
      await tester.enterText(find.byKey(const Key('correction-reason')), '   ');
      await tester.pumpAndSettle();
      expect(tester.widget<FilledButton>(save).onPressed, isNull);
      await tester.enterText(find.byKey(const Key('correction-reason')), 'Miscounted the coins');
      await tester.pumpAndSettle();
      await tester.tap(save);
      await tester.pumpAndSettle();

      final body = server.posts('/z-report').last.data as Map;
      expect(body['correctionOf'], 'z-1');
      expect(body['reason'], 'Miscounted the coins');
      expect(body['countedCash'], 190.0);
      expect(find.textContaining('version 2'), findsOneWidget);
      expect(find.textContaining('Miscounted the coins'), findsOneWidget);
    });

    testWidgets('a refusal reads in words', (tester) async {
      final server = _Server();
      await _pump(tester, server: server);
      // A count is required first.
      await tester.ensureVisible(find.byKey(const Key('day-report-settle')));
      await tester.tap(find.byKey(const Key('day-report-settle')));
      await tester.pumpAndSettle();
      expect(find.text('Enter the cash counted for the day.'), findsOneWidget);
      expect(server.posts('/z-report'), isEmpty);
    });
  });
}
