import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/format.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/platform/tenants_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Switching a business off (tenant-svc, 30 Sep 2026) needs the administrator's
// reason: the dialog asks for it, will not send without it, and sends it;
// switching on asks for none. A switched-off business shows why, by whom and
// when. A refusal reads in words.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  String? refusal;

  final tenants = <Map<String, dynamic>>[
    {
      'id': 'a',
      'name': 'Live Shop',
      'status': 'ACTIVE',
      'country': 'GB',
      'currency': 'GBP',
      'createdAt': '2026-01-05T09:00:00Z',
    },
    {
      'id': 'b',
      'name': 'Off Shop',
      'status': 'INACTIVE',
      'country': 'IN',
      'currency': 'INR',
      'createdAt': '2026-01-06T09:00:00Z',
      'deactivatedNote': 'Chargeback dispute unresolved',
      'deactivatedBy': '0198aaaa-bbbb-7ccc-8ddd-0123456789ab',
      'deactivatedAt': '2026-09-20T10:00:00Z',
    },
  ];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'PATCH') {
      if (refusal != null) {
        return jsonResponse(jsonEncode({'error': {'code': 'TENANT_STATUS_REASON_REQUIRED', 'message': refusal}}), 400);
      }
      return jsonResponse('{"data":{}}');
    }
    return jsonResponse(jsonEncode({'data': tenants}));
  }

  List<RequestOptions> get patches => requests.where((r) => r.method == 'PATCH').toList();
}

Future<_Server> _pump(WidgetTester tester, {double width = 2400}) async {
  tester.view.physicalSize = Size(width, 1400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
    child: const MaterialApp(home: Scaffold(body: TenantsScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('deactivating asks for a reason, will not send without one, and sends it', (tester) async {
    final server = await _pump(tester);

    await tester.tap(find.widgetWithText(TextButton, 'Deactivate'));
    await tester.pumpAndSettle();
    expect(find.text('Deactivate "Live Shop"?'), findsOneWidget);
    FilledButton confirm() => tester.widget<FilledButton>(find.byKey(const Key('suspend-confirm')));
    expect(confirm().onPressed, isNull, reason: 'no reason, nothing to send');

    await tester.enterText(find.byKey(const Key('suspend-reason')), '   ');
    await tester.pump();
    expect(confirm().onPressed, isNull, reason: 'blank is not a reason');

    await tester.enterText(find.byKey(const Key('suspend-reason')), '  Fraud review  ');
    await tester.pump();
    await tester.tap(find.byKey(const Key('suspend-confirm')));
    await tester.pumpAndSettle();

    expect(server.patches, hasLength(1));
    expect(server.patches.single.path, endsWith('/platform/tenants/a/status'));
    expect(server.patches.single.data, {'status': 'INACTIVE', 'reason': 'Fraud review'});
  });

  testWidgets('cancelling the reason dialog sends nothing', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.widgetWithText(TextButton, 'Deactivate'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(server.patches, isEmpty);
  });

  testWidgets('activating needs no reason and sends none', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.widgetWithText(TextButton, 'Activate'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('suspend-reason')), findsNothing);
    await tester.tap(find.widgetWithText(FilledButton, 'Activate'));
    await tester.pumpAndSettle();
    expect(server.patches.single.data, {'status': 'ACTIVE'});
  });

  testWidgets('a refusal reads in words', (tester) async {
    (await _pump(tester)).refusal = 'Say why the business is being switched off.';
    await tester.tap(find.widgetWithText(TextButton, 'Deactivate'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('suspend-reason')), 'x');
    await tester.pump();
    await tester.tap(find.byKey(const Key('suspend-confirm')));
    await tester.pumpAndSettle();
    expect(find.text('Say why the business is being switched off.'), findsOneWidget);
    expect(find.textContaining('TENANT_STATUS'), findsNothing);
  });

  for (final width in [2400.0, 400.0]) {
    testWidgets('a switched-off business shows why, by whom and when (${width.toInt()} wide)', (tester) async {
      await _pump(tester, width: width);
      expect(find.byKey(const Key('suspension-note')), findsOneWidget, reason: 'only the one switched off');
      expect(find.text('Reason: Chargeback dispute unresolved'), findsOneWidget);
      expect(
        find.text('Switched off by the platform administrator (${'0198aaaa-bbbb-7ccc-8ddd-0123456789ab'.substring(28)}) on ${AppFormat.date('2026-09-20T10:00:00Z')}'),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
    });
  }
}
