import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/staff_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Head-office (business-wide) managers (tenant-svc, 30 Sep 2026): an assignment
// with no store reads "Whole business"; only an owner is offered giving one, and
// only for the manager tier (the built-in or a role standing on it); it is sent
// as {userId, role, businessWide: true} with no storeId, and removed with
// businessWide=true. Its refusals read in words. A manager held to stores is not
// offered defining roles, which are the whole business's.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int assignStatus = 201;
  String assignBody = '{"data":{}}';

  String staff = jsonEncode([
    {'id': 'a1', 'userId': 'u-hq', 'businessWide': true, 'role': 'MANAGER', 'assignedAt': '2026-09-13T00:00:00Z'},
    {'id': 'a2', 'userId': 'u-shop', 'storeId': 's1', 'role': 'CASHIER', 'assignedAt': '2026-09-13T00:00:00Z'},
  ]);

  static const _roles = '{"data":['
      '{"code":"MANAGER","name":"Manager","baseTier":"MANAGER","permissions":[],"custom":false},'
      '{"code":"CASHIER","name":"Cashier","baseTier":"CASHIER","permissions":[],"custom":false},'
      '{"code":"SHIFT_LEAD","name":"Shift lead","baseTier":"MANAGER","permissions":[],"custom":true},'
      '{"code":"TILL_HELPER","name":"Till helper","baseTier":"CASHIER","permissions":[],"custom":true}]}';

  List<RequestOptions> get assigns =>
      requests.where((r) => r.method == 'POST' && r.path.endsWith('/admin/staff')).toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/admin/roles') && o.method == 'GET') return jsonResponse(_roles);
    if (o.path.endsWith('/admin/staff') && o.method == 'POST') return jsonResponse(assignBody, assignStatus);
    if (o.path.contains('/admin/staff/') && o.method == 'DELETE') return jsonResponse('', 204);
    if (o.path.endsWith('/admin/staff')) return jsonResponse('{"data":$staff,"meta":{}}');
    if (o.path.endsWith('/auth/admin/staff-users') && o.method == 'POST') {
      return jsonResponse('{"data":{"userId":"u-new","created":false}}');
    }
    if (o.path.contains('/auth/admin/staff-users')) {
      return jsonResponse('{"data":[{"userId":"u-hq","email":"hq@shop.test"},{"userId":"u-shop","email":"till@shop.test"}]}');
    }
    if (o.path.endsWith('/admin/stores')) {
      return jsonResponse('{"data":[{"id":"s1","name":"Main","code":"MAIN","type":"STORE","status":"ACTIVE"}],"meta":{}}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester, {String role = 'OWNER', List<String> storeIds = const []}) async {
  tester.view.physicalSize = const Size(1200, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final srv = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = srv;
  await tester.pumpWidget(ProviderScope(
    key: UniqueKey(),
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role, storeIds: storeIds)),
    ],
    child: const MaterialApp(home: Scaffold(body: StaffScreen())),
  ));
  await tester.pumpAndSettle();
  return srv;
}

Future<void> _openAssign(WidgetTester tester) async {
  await tester.tap(find.text('Assign Staff').first);
  await tester.pumpAndSettle();
  await tester.enterText(find.byType(TextFormField).first, 'new@shop.test');
}

Future<void> _pickRole(WidgetTester tester, String name) async {
  await tester.tap(find.byKey(const Key('assign-role')));
  await tester.pumpAndSettle();
  await tester.tap(find.text(name).last);
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('a business-wide row reads Whole business, a store row its store', (tester) async {
    await _pump(tester);
    expect(find.text('Whole business'), findsOneWidget);
    expect(find.text('Main'), findsOneWidget);
  });

  testWidgets('an owner giving the manager tier is offered the whole business and sends the flag, no store',
      (tester) async {
    final srv = await _pump(tester);
    await _openAssign(tester);
    await _pickRole(tester, 'Manager');
    await tester.tap(find.text('Store or whole business *'));
    await tester.pumpAndSettle();
    expect(find.text('Whole business (head office)'), findsOneWidget);
    await tester.tap(find.text('Whole business (head office)').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Assign').last);
    await tester.pumpAndSettle();
    expect(srv.assigns, hasLength(1));
    final body = srv.assigns.single.data as Map;
    expect(body, {'userId': 'u-new', 'role': 'MANAGER', 'businessWide': true});
    expect(body.containsKey('storeId'), isFalse);
  });

  testWidgets('a role of the business\'s own standing on the manager tier is offered it too', (tester) async {
    await _pump(tester);
    await _openAssign(tester);
    await _pickRole(tester, 'Shift lead · on Manager');
    await tester.tap(find.text('Store or whole business *'));
    await tester.pumpAndSettle();
    expect(find.text('Whole business (head office)'), findsOneWidget);
  });

  testWidgets('a cashier-tier role is not offered the whole business, only the stores', (tester) async {
    await _pump(tester);
    await _openAssign(tester);
    await tester.tap(find.text('Store *'));
    await tester.pumpAndSettle();
    expect(find.text('Whole business (head office)'), findsNothing);
    expect(find.text('Main (MAIN)'), findsWidgets);
  });

  testWidgets('a manager is never offered the whole business, even for the manager tier', (tester) async {
    await _pump(tester, role: 'MANAGER');
    await _openAssign(tester);
    await _pickRole(tester, 'Manager');
    expect(find.text('Store or whole business *'), findsNothing);
    await tester.tap(find.text('Store *'));
    await tester.pumpAndSettle();
    expect(find.text('Whole business (head office)'), findsNothing);
  });

  testWidgets('choosing a store still sends the store, as before', (tester) async {
    final srv = await _pump(tester);
    await _openAssign(tester);
    await tester.tap(find.text('Store *'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Main (MAIN)').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Assign').last);
    await tester.pumpAndSettle();
    expect(srv.assigns.single.data, {'userId': 'u-new', 'storeId': 's1', 'role': 'CASHIER'});
  });

  testWidgets('removing a head-office row sends businessWide=true and no store', (tester) async {
    final srv = await _pump(tester);
    await tester.tap(find.byTooltip('Remove').first);
    await tester.pumpAndSettle();
    expect(find.textContaining('across the whole business'), findsOneWidget);
    await tester.tap(find.text('Remove').last);
    await tester.pumpAndSettle();
    final del = srv.requests.singleWhere((r) => r.method == 'DELETE');
    expect(del.path, endsWith('/admin/staff/u-hq'));
    expect(del.queryParameters, {'businessWide': true});
  });

  testWidgets('a manager is not offered removing a head-office row, only the store one', (tester) async {
    await _pump(tester, role: 'MANAGER');
    expect(find.byTooltip('Remove'), findsOneWidget);
  });

  for (final (code, words) in const [
    ('STAFF_BUSINESS_WIDE_OWNER_ONLY', 'Only an owner of the business gives or removes a head-office assignment.'),
    ('STAFF_BUSINESS_WIDE_TIER', 'A head-office assignment is for a manager'),
    ('STAFF_STORE_AND_BUSINESS_WIDE', 'Choose a store or the whole business, not both.'),
    ('STAFF_STORE_REQUIRED', 'Choose a store, or the whole business.'),
  ]) {
    testWidgets('$code reads in words, never as "check the email"', (tester) async {
      final srv = await _pump(tester);
      srv.assignStatus = 400;
      srv.assignBody = jsonEncode({'error': {'code': code, 'message': code}});
      await _openAssign(tester);
      await tester.tap(find.text('Store *'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Main (MAIN)').last);
      await tester.pumpAndSettle();
      await tester.tap(find.text('Assign').last);
      await tester.pumpAndSettle();
      expect(find.textContaining(words), findsOneWidget);
      expect(find.textContaining('Check the email'), findsNothing);
      expect(find.textContaining(code), findsNothing);
    });
  }

  testWidgets('a manager held to stores is not offered defining a role, and is told who does', (tester) async {
    await _pump(tester, role: 'MANAGER', storeIds: ['s1']);
    await tester.tap(find.text('Roles'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('define-role')), findsNothing);
    expect(find.byKey(const Key('edit-role-SHIFT_LEAD')), findsNothing);
    expect(find.text('Only an owner or a head-office manager changes this.'), findsOneWidget);
  });

  testWidgets('an owner and a head-office manager are offered defining a role', (tester) async {
    for (final role in ['OWNER', 'MANAGER']) {
      await _pump(tester, role: role);
      await tester.tap(find.text('Roles'));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('define-role')), findsOneWidget, reason: role);
      expect(find.byKey(const Key('roles-business-wide-note')), findsNothing, reason: role);
    }
  });
}
