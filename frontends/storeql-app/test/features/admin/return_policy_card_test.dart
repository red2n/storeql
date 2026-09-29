import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
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
          '{"data":{"windowDays":30,"cashierCeiling":50.0,"noReceiptAllowed":false}}', 200);
    }
    if (o.path.endsWith('/admin/tenant/fx-rates')) {
      return _json('{"data":{"home":"GBP","rates":[]}}', 200);
    }
    return _json('{"data":null}', 200);
  }

  static ResponseBody _json(String body, int status) => ResponseBody.fromString(body, status,
      headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
}

Future<_Server> _pump(WidgetTester tester, {bool refuse = false}) async {
  tester.view.physicalSize = const Size(1000, 1200);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server()..refuse = refuse;
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
    expect(find.text('50'), findsOneWidget); // the limit, as it stands

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
    expect(body['noReceiptCeiling'], 20.5);
    expect(body['cashierCeiling'], 50);
  });

  testWidgets('a limit that is not a number is stopped here and nothing is sent', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('return-policy-edit')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('policy-ceiling')), 'lots');
    await tester.tap(find.byKey(const Key('policy-save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('policy-error')), findsOneWidget);
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
}
