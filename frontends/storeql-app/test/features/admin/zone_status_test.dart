import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/network/api_error.dart';
import 'package:storeql_app/features/admin/stores_screen.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A zone's status is ACTIVE, OUT_OF_SERVICE or RETIRED (tenant-svc, 30 Sep
// 2026); the old INACTIVE is refused. The zones dialog reads each in words and
// offers the other two, and never sends anything else.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int patchStatus = 200;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final p = o.path;
    if (p.endsWith('/admin/stores') && o.method == 'GET') {
      return jsonResponse('{"data":[{"id":"store-1","name":"Main","code":"MAIN","type":"STORE",'
          '"status":"ACTIVE","timezone":"UTC"}],"meta":{}}');
    }
    if (p.endsWith('/zones') && o.method == 'GET') {
      return jsonResponse('{"data":['
          '{"id":"z-1","storeId":"store-1","name":"Dairy","code":"CR1","type":"COLD_ROOM","status":"ACTIVE"},'
          '{"id":"z-2","storeId":"store-1","name":"Back room","code":"BR","type":"BACK_STORE","status":"OUT_OF_SERVICE"},'
          '{"id":"z-3","storeId":"store-1","name":"Old aisle","code":"A9","type":"AISLE","status":"RETIRED"}'
          '],"meta":{}}');
    }
    if (o.method == 'PATCH') {
      if (patchStatus != 200) {
        return jsonResponse('{"error":{"code":"ZONE_STATUS_INVALID","message":""}}', patchStatus);
      }
      return jsonResponse('{"data":{}}');
    }
    return jsonResponse('{"data":[],"meta":{}}');
  }

  List<Map<String, dynamic>> get patches => [
        for (final r in requests.where((r) => r.method == 'PATCH'))
          (r.data is String ? jsonDecode(r.data as String) : r.data) as Map<String, dynamic>
      ];
}

Future<_Server> _pump(WidgetTester tester, {Size size = const Size(1400, 1600), _Server? server}) async {
  final srv = server ?? _Server();
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(
          Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = srv)),
      authNotifierProvider.overrideWith(() => RoleAuth('OWNER')),
    ],
    child: const MaterialApp(home: StoresScreen()),
  ));
  await tester.pumpAndSettle();
  if (size.width < 600) {
    await tester.tap(find.byKey(const Key('store-menu-store-1')));
    await tester.pumpAndSettle();
  }
  await tester.tap(find.text('Zones'));
  await tester.pumpAndSettle();
  return srv;
}

void main() {
  test('the three statuses read in words', () {
    expect(zoneStatuses, ['ACTIVE', 'OUT_OF_SERVICE', 'RETIRED']);
    expect(zoneStatusLabel('ACTIVE'), 'Active');
    expect(zoneStatusLabel('OUT_OF_SERVICE'), 'Out of service');
    expect(zoneStatusLabel('RETIRED'), 'Retired');
    expect(zoneStatusAction('OUT_OF_SERVICE'), 'Mark out of service');
  });

  testWidgets('each zone shows its status in words', (tester) async {
    await _pump(tester);
    expect(find.widgetWithText(StatusBadge, 'Active'), findsOneWidget);
    expect(find.widgetWithText(StatusBadge, 'Out of service'), findsOneWidget);
    expect(find.widgetWithText(StatusBadge, 'Retired'), findsOneWidget);
    expect(find.text('OUT_OF_SERVICE'), findsNothing);
  });

  testWidgets('an active zone offers the other two, and out of service is sent as such', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('zone-status-z-1')));
    await tester.pumpAndSettle();
    expect(find.text('Mark active'), findsNothing, reason: 'it is active already');
    expect(find.text('Retire'), findsOneWidget);
    await tester.tap(find.text('Mark out of service'));
    await tester.pumpAndSettle();
    expect(server.patches.single, {'status': 'OUT_OF_SERVICE'});
    expect(server.requests.singleWhere((r) => r.method == 'PATCH').path,
        '/tenant-svc/admin/stores/store-1/zones/z-1/status');
  });

  testWidgets('a retired zone can be brought back, and INACTIVE is never sent', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('zone-status-z-3')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Mark active'));
    await tester.pumpAndSettle();
    expect(server.patches.single, {'status': 'ACTIVE'});
    expect(server.patches.map((p) => p['status']), isNot(contains('INACTIVE')));
  });

  testWidgets('on a phone the same three are in the zone menu', (tester) async {
    final server = await _pump(tester, size: const Size(390, 844));
    await tester.tap(find.byKey(const Key('zone-actions-z-1')));
    await tester.pumpAndSettle();
    expect(find.text('Edit'), findsOneWidget);
    await tester.tap(find.text('Retire'));
    await tester.pumpAndSettle();
    expect(server.patches.single, {'status': 'RETIRED'});
  });

  testWidgets('a refusal reads in words', (tester) async {
    await _pump(tester, server: _Server()..patchStatus = 400);
    await tester.tap(find.byKey(const Key('zone-status-z-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Retire'));
    await tester.pumpAndSettle();
    expect(find.text('A zone is active, out of service or retired.'), findsOneWidget);
    expect(apiErrorCode(DioException(requestOptions: RequestOptions(path: '/x'))), isNull);
  });
}
