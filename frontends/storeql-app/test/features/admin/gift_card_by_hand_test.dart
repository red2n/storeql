import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/ids.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/network/api_error.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/sales_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A gift card given or reloaded by hand is an owner's or a manager's, says why
// (Goodwill, Promotion, Compensation, Migration), and carries an
// Idempotency-Key that a retry of the same submit repeats. A cashier or a
// storekeeper is not offered it and is told a card is sold at the till.
// Redeeming stays anyone's and asks for no reason.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;

  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  /// Answers to POST /gift-cards, one per call; empty means a success.
  final List<int> issueStatuses = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions options, Stream<List<int>>? stream, Future<void>? cancel) async {
    requests.add(options);
    final path = options.path;
    if (options.method == 'POST' && path.endsWith('/gift-cards') && issueStatuses.isNotEmpty) {
      final status = issueStatuses.removeAt(0);
      if (status >= 400) {
        return jsonResponse(
            '{"error":{"code":"GIFT_CARD_REASON_REQUIRED","message":""}}', status);
      }
    }
    final Object body;
    if (path.endsWith('/transactions')) {
      body = {'data': []};
    } else if (options.method == 'GET' && path.contains('/gift-cards/')) {
      body = {
        'data': {
          'code': 'GC-7777',
          'currentBalance': 50,
          'initialBalance': 50,
          'status': 'ACTIVE',
          'currency': 'GBP',
        }
      };
    } else if (options.method == 'POST' && path.endsWith('/gift-cards')) {
      body = {
        'data': {'code': 'GC-1234'}
      };
    } else {
      body = {'data': {}};
    }
    return ResponseBody.fromString(jsonEncode(body), 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType]
    });
  }

  List<RequestOptions> posts(String fragment) => requests
      .where((r) => r.method == 'POST' && r.path.endsWith(fragment))
      .toList();
}

Future<_Server> _pump(WidgetTester tester, {String role = 'MANAGER'}) async {
  tester.view.physicalSize = const Size(1100, 1600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
      storesProvider.overrideWith((ref) async => const [
            StoreInfo(
                id: 's1',
                name: 'High Street',
                code: 'HS',
                type: 'STORE',
                status: 'ACTIVE',
                country: 'GB'),
          ]),
    ],
    child: const MaterialApp(home: Scaffold(body: SalesScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

Future<void> _chooseReason(WidgetTester tester, String label) async {
  await tester.tap(find.byKey(const Key('gift-card-reason')));
  await tester.pumpAndSettle();
  await tester.tap(find.text(label).last);
  await tester.pumpAndSettle();
}

Future<void> _fillIssue(WidgetTester tester) async {
  await tester.tap(find.widgetWithText(OutlinedButton, 'Issue'));
  await tester.pumpAndSettle();
  await tester.tap(find.byType(DropdownButtonFormField<String>).first);
  await tester.pumpAndSettle();
  await tester.tap(find.text('High Street').last);
  await tester.pumpAndSettle();
  await tester.enterText(find.widgetWithText(TextField, 'Amount'), '25');
}

void main() {
  testWidgets('a card is not issued until it says why, then sends the reason and a key',
      (tester) async {
    final server = await _pump(tester);
    await _fillIssue(tester);
    await tester.tap(find.widgetWithText(FilledButton, 'Issue'));
    await tester.pumpAndSettle();
    expect(find.textContaining('say why the card is given'), findsOneWidget);
    expect(server.posts('/gift-cards'), isEmpty);
    expect(find.text('Paid by *'), findsNothing);

    await _chooseReason(tester, 'Goodwill');
    await tester.tap(find.widgetWithText(FilledButton, 'Issue'));
    await tester.pumpAndSettle();
    final issue = server.posts('/gift-cards').single;
    expect(issue.data, {'storeId': 's1', 'amount': 25.0, 'reason': 'GOODWILL'});
    expect(issue.data, isNot(contains('paidBy')));
    expect(isV7(issue.headers['Idempotency-Key'] as String), isTrue);
    expect(find.text('Gift card issued'), findsOneWidget);
  });

  testWidgets('the four reasons are offered in words', (tester) async {
    await _pump(tester);
    await _fillIssue(tester);
    await tester.tap(find.byKey(const Key('gift-card-reason')));
    await tester.pumpAndSettle();
    for (final w in ['Goodwill', 'Promotion', 'Compensation', 'Migration from another system']) {
      expect(find.text(w), findsWidgets);
    }
    expect(find.text('GOODWILL'), findsNothing);
  });

  testWidgets('a retry of the same submit repeats the key; a changed amount takes a new one',
      (tester) async {
    final server = await _pump(tester);
    server.issueStatuses.add(500);
    await _fillIssue(tester);
    await _chooseReason(tester, 'Promotion');
    await tester.tap(find.widgetWithText(FilledButton, 'Issue'));
    await tester.pumpAndSettle();
    server.issueStatuses.add(500);
    await tester.tap(find.widgetWithText(FilledButton, 'Issue'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Amount'), '30');
    await tester.tap(find.widgetWithText(FilledButton, 'Issue'));
    await tester.pumpAndSettle();
    final keys = [for (final r in server.posts('/gift-cards')) r.headers['Idempotency-Key']];
    expect(keys, hasLength(3));
    expect(keys[0], keys[1]);
    expect(keys[2], isNot(keys[0]));
  });

  testWidgets('a reload asks why and sends a key; a redemption asks neither', (tester) async {
    final server = await _pump(tester);
    await tester.enterText(find.widgetWithText(TextField, 'Gift card code'), 'GC-7777');
    await tester.tap(find.text('Look up'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('Reload'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Amount'), '10');
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    expect(find.text('Reload gift card'), findsOneWidget);
    expect(server.posts('/reload'), isEmpty);
    await _chooseReason(tester, 'Compensation');
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    final reload = server.posts('/reload').single;
    expect(reload.data, {'amount': 10.0, 'reason': 'COMPENSATION'});
    expect(isV7(reload.headers['Idempotency-Key'] as String), isTrue);

    await tester.tap(find.text('Redeem'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('gift-card-reason')), findsNothing);
    await tester.enterText(find.widgetWithText(TextField, 'Amount'), '5');
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    final redeem = server.posts('/redeem').single;
    expect(redeem.data, {'amount': 5.0});
    expect(redeem.headers.containsKey('Idempotency-Key'), isFalse);
  });

  for (final role in ['CASHIER', 'STOREKEEPER']) {
    testWidgets('a $role is offered no issue or reload and is told a card is sold at the till',
        (tester) async {
      await _pump(tester, role: role);
      expect(find.widgetWithText(OutlinedButton, 'Issue'), findsNothing);
      expect(find.byKey(const Key('gift-card-sold-at-till')), findsOneWidget);
      await tester.enterText(find.widgetWithText(TextField, 'Gift card code'), 'GC-7777');
      await tester.tap(find.text('Look up'));
      await tester.pumpAndSettle();
      expect(find.text('Reload'), findsNothing);
      expect(find.text('Redeem'), findsOneWidget);
    });
  }

  test('the refusal of a hand-made card reads in words', () {
    final e = DioException(
        requestOptions: RequestOptions(path: '/gift-cards'),
        response: Response(
            requestOptions: RequestOptions(path: '/gift-cards'),
            statusCode: 403,
            data: {'error': {'code': 'GIFT_CARD_NEEDS_SALE', 'message': ''}}));
    expect(friendlyError(e), contains('sold at the till'));
  });
}
