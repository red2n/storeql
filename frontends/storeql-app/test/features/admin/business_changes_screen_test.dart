import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/business_changes_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Changes to stores and staff (tenant-svc, 30 Sep 2026): who opened a store,
// moved its status or till-phone setting, gave someone a role or took it away,
// defined, changed or deleted a role — when, by whom (by name), what it was and
// what it became, at which store or the whole business, all in words. Filtered
// by kind, store, person and period; paged with the cursor; read-only.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int status = 200;
  bool empty = false;

  List<RequestOptions> get reads => requests.where((r) => r.path.endsWith('/admin/tenant/audit')).toList();

  @override
  void close({bool force = false}) {}

  Map<String, dynamic> _e(String id, String type, String at,
          {String? actor = 'u-1', String? store, String? subject, String? code, String? from, String? to}) =>
      {
        'id': id,
        'type': type,
        'actorId': actor,
        'storeId': store,
        'subjectId': subject,
        'subjectCode': code,
        'from': from,
        'to': to,
        'occurredAt': at,
      }..removeWhere((k, v) => v == null);

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/admin/tenant/audit')) {
      if (status != 200) {
        return jsonResponse(jsonEncode({'error': {'code': 'STORE_ACCESS_DENIED', 'message': 'STORE_ACCESS_DENIED'}}), status);
      }
      if (empty) return jsonResponse('{"data":[],"meta":{}}');
      if (o.queryParameters['after'] == 'c1') {
        return jsonResponse(jsonEncode({
          'data': [_e('e-9', 'STORE_CREATED', '2026-09-01T09:00:00Z', store: 's2', to: 'WAREHOUSE')],
          'meta': {},
        }));
      }
      if (o.queryParameters['store'] == 's1') {
        return jsonResponse(jsonEncode({
          'data': [_e('e-2', 'STORE_STATUS_CHANGED', '2026-09-29T09:00:00Z', store: 's1', from: 'ACTIVE', to: 'INACTIVE')],
          'meta': {},
        }));
      }
      return jsonResponse(jsonEncode({
        'data': [
          _e('e-1', 'STORE_STATUS_CHANGED', '2026-09-29T09:00:00Z', store: 's1', from: 'ACTIVE', to: 'INACTIVE'),
          _e('e-3', 'STORE_TILL_PHONE_CHANGED', '2026-09-28T09:00:00Z', store: 's1', from: 'OPTIONAL', to: 'REQUIRED'),
          _e('e-4', 'STAFF_ASSIGNED', '2026-09-27T09:00:00Z', store: 's1', subject: 'u-2', code: 'CASHIER', to: 'CASHIER'),
          _e('e-5', 'STAFF_ASSIGNED', '2026-09-26T09:00:00Z', subject: 'u-2', code: 'MANAGER', to: 'MANAGER (business-wide)'),
          _e('e-6', 'STAFF_UNASSIGNED', '2026-09-25T09:00:00Z', store: 's1', subject: 'u-2', code: 'CASHIER', from: 'CASHIER'),
          _e('e-7', 'ROLE_CHANGED', '2026-09-24T09:00:00Z',
              code: 'SHIFT_LEAD',
              from: 'Shift lead (MANAGER): sales.void,staff.manage',
              to: 'Shift lead (MANAGER): sales.void'),
          _e('e-8', 'ROLE_DELETED', '2026-09-23T09:00:00Z', actor: null, code: 'TRAINEE', from: 'Trainee (CASHIER): '),
          _e('e-10', 'SOMETHING_ELSE', '2026-09-22T09:00:00Z'),
        ],
        'meta': {'nextCursor': 'c1'},
      }));
    }
    if (o.path.endsWith('/admin/stores')) {
      return jsonResponse(
          '{"data":[{"id":"s1","name":"High Street","code":"HS","type":"STORE","status":"ACTIVE"},'
          '{"id":"s2","name":"Depot","code":"DP","type":"WAREHOUSE","status":"ACTIVE"}],"meta":{}}');
    }
    if (o.path.endsWith('/admin/staff')) {
      return jsonResponse(
          '{"data":[{"id":"a1","userId":"u-1","storeId":"s1","role":"MANAGER","assignedAt":""},'
          '{"id":"a2","userId":"u-2","storeId":"s1","role":"CASHIER","assignedAt":""}],"meta":{}}');
    }
    if (o.path.endsWith('/admin/roles')) {
      return jsonResponse('{"data":['
          '{"code":"MANAGER","name":"Manager","baseTier":"MANAGER","permissions":[],"custom":false},'
          '{"code":"CASHIER","name":"Cashier","baseTier":"CASHIER","permissions":[],"custom":false},'
          '{"code":"SHIFT_LEAD","name":"Shift lead","baseTier":"MANAGER","permissions":[],"custom":true}]}');
    }
    if (o.path.contains('/auth/admin/staff-users')) {
      return jsonResponse('{"data":[{"userId":"u-1","email":"ana@shop.test"},{"userId":"u-2","email":"ben@shop.test"}]}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester,
    {Size size = const Size(1200, 1800), List<String> storeIds = const [], _Server? server}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final srv = server ?? _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = srv;
  await tester.pumpWidget(ProviderScope(
    key: UniqueKey(),
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth('MANAGER', storeIds: storeIds)),
    ],
    child: const MaterialApp(home: Scaffold(body: BusinessChangesScreen())),
  ));
  await tester.pumpAndSettle();
  return srv;
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('each change reads in words: what, who by name, from to, at which store', (tester) async {
    await _pump(tester);
    expect(find.text('Store status changed: High Street'), findsOneWidget);
    expect(find.text('Open → Closed'), findsOneWidget);
    expect(find.text('Till phone setting changed: High Street'), findsOneWidget);
    expect(find.text('Optional → Required'), findsOneWidget);
    expect(find.text('ben@shop.test given Cashier'), findsOneWidget);
    expect(find.text('At High Street'), findsWidgets);
    expect(find.text('ben@shop.test taken off Cashier'), findsOneWidget);
    expect(find.textContaining('by ana@shop.test'), findsWidgets);
    // A head-office assignment names no store.
    expect(find.text('ben@shop.test given Manager'), findsOneWidget);
    expect(find.text('Across the whole business'), findsOneWidget);
    // A role change reads as words, from and to.
    expect(find.text('Role changed: Shift lead'), findsOneWidget);
    expect(
        find.text('Shift lead, on Manager, holding Sales void, Staff manage → '
            'Shift lead, on Manager, holding Sales void'),
        findsOneWidget);
    // A role deleted by nobody recorded; a role with no name left reads from its code.
    expect(find.text('Role deleted: Trainee'), findsOneWidget);
    expect(find.text('Trainee, on Cashier, holding nothing'), findsOneWidget);
    expect(find.textContaining('by nobody recorded'), findsOneWidget);
    // A kind the app has no words for still reads as words.
    expect(find.text('A change to the business: something else'), findsOneWidget);
    // Never a code.
    for (final code in ['STORE_STATUS_CHANGED', 'ACTIVE', 'INACTIVE', 'REQUIRED', 'business-wide', 'sales.void']) {
      expect(find.textContaining(code), findsNothing, reason: code);
    }
  });

  testWidgets('the first read asks for the period and a page; nothing else', (tester) async {
    final srv = await _pump(tester);
    final q = srv.reads.first.queryParameters;
    expect(q['limit'], 50);
    expect(q.containsKey('from') && q.containsKey('to'), isTrue);
    expect(q.keys.toSet().intersection({'type', 'actor', 'store'}), isEmpty);
  });

  testWidgets('the store filter asks the server for that store', (tester) async {
    final srv = await _pump(tester);
    await tester.tap(find.byKey(const Key('changes-store')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('High Street').last);
    await tester.pumpAndSettle();
    expect(srv.reads.last.queryParameters['store'], 's1');
  });

  testWidgets('the kind filter and the Who filter ask the server by type and actor', (tester) async {
    final srv = await _pump(tester);
    await tester.tap(find.byKey(const Key('changes-type')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Role changed').last);
    await tester.pumpAndSettle();
    expect(srv.reads.last.queryParameters['type'], 'ROLE_CHANGED');
    await tester.tap(find.byKey(const Key('changes-actor')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('ana@shop.test').last);
    await tester.pumpAndSettle();
    expect(srv.reads.last.queryParameters['actor'], 'u-1');
    expect(srv.reads.last.queryParameters['type'], 'ROLE_CHANGED');
  });

  testWidgets('Load older appends the next page with the cursor', (tester) async {
    final srv = await _pump(tester);
    await tester.ensureVisible(find.byKey(const Key('changes-load-older')));
    await tester.tap(find.byKey(const Key('changes-load-older')));
    await tester.pumpAndSettle();
    expect(srv.reads.last.queryParameters['after'], 'c1');
    expect(find.text('Store opened: Depot'), findsOneWidget);
    expect(find.text('A warehouse'), findsOneWidget);
    expect(find.byKey(const Key('changes-load-older')), findsNothing);
  });

  testWidgets('an empty period says so', (tester) async {
    await _pump(tester, server: _Server()..empty = true);
    expect(find.text('No changes in this period'), findsOneWidget);
  });

  testWidgets('a refusal reads in words', (tester) async {
    await _pump(tester, server: _Server()..status = 403);
    expect(find.text('That is not one of your stores.'), findsOneWidget);
  });

  testWidgets('a manager held to stores reads what the server returns for them, with no different controls',
      (tester) async {
    final srv = await _pump(tester, storeIds: ['s1']);
    expect(srv.reads, isNotEmpty);
    expect(find.text('Store status changed: High Street'), findsOneWidget);
  });

  testWidgets('on a phone the filters fold behind one button and the rows do not overflow', (tester) async {
    await _pump(tester, size: const Size(390, 844));
    expect(find.byKey(const Key('changes-type')), findsNothing);
    expect(find.text('Filters'), findsOneWidget);
    expect(find.text('Store status changed: High Street'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('nothing on the screen writes: every request is a GET', (tester) async {
    final srv = await _pump(tester);
    expect(srv.requests.every((r) => r.method == 'GET'), isTrue);
  });

  test('roleChangeWords leaves text in another shape as it is', () {
    expect(roleChangeWords('something else'), 'something else');
    expect(roleChangeWords(null), '');
  });
}
