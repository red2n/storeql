import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/staff_screen.dart';

// ---------------------------------------------------------------------------
// The role model (20.10) on screen: the tenant's own roles beside the built-in
// tiers, a role defined by ticking a subset of its tier's permissions — the
// boxes offered are exactly the tier's, because the server refuses the rest —
// redefined, deleted, and offered in the assign dialog; and what a login's
// permission claim does to the controls the server would refuse.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  int defineStatus = 201;
  int deleteStatus = 204;
  bool withCustom = true;

  /// The staff assignments tenant-svc lists: user and store by id only.
  String staff =
      '[{"id":"a1","userId":"01a0-user","storeId":"01a0-store","role":"SHIFT_LEAD","baseTier":"MANAGER","assignedAt":"2026-09-13T00:00:00Z"}]';

  /// What iam-svc names each login.
  String logins = '[{"userId":"01a0-user","email":"sam@shop.test"}]';

  static const _catalogue = '{"data":['
      '{"code":"sales.void","description":"Void a completed till sale","defaultFor":["MANAGER"]},'
      '{"code":"till.no_sale","description":"Open the cash drawer without a sale","defaultFor":["CASHIER","MANAGER"]},'
      '{"code":"stock.adjust","description":"Adjust stock levels","defaultFor":["MANAGER","STOREKEEPER"]},'
      '{"code":"staff.manage","description":"Assign and remove staff, and define roles","defaultFor":["MANAGER"]}]}';

  String get _roles => '{"data":['
      '{"code":"OWNER","name":"Owner","baseTier":"OWNER","permissions":["sales.void","staff.manage"],"custom":false},'
      '{"code":"MANAGER","name":"Manager","baseTier":"MANAGER","permissions":["sales.void","staff.manage"],"custom":false},'
      '{"code":"STOREKEEPER","name":"Storekeeper","baseTier":"STOREKEEPER","permissions":["stock.adjust"],"custom":false},'
      '{"code":"CASHIER","name":"Cashier","baseTier":"CASHIER","permissions":["till.no_sale"],"custom":false}'
      '${withCustom ? ',{"code":"SHIFT_LEAD","name":"Shift lead","baseTier":"MANAGER","permissions":["staff.manage"],"description":"Runs the floor","custom":true}' : ''}'
      ']}';

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    String body = '{"data":[]}';
    int status = 200;
    if (o.path.endsWith('/admin/roles/permissions')) {
      body = _catalogue;
    } else if (o.path.endsWith('/admin/roles') && o.method == 'GET') {
      body = _roles;
    } else if (o.path.endsWith('/admin/roles') && o.method == 'POST') {
      status = defineStatus;
      body = status == 201
          ? '{"data":{"code":"TRAINEE","name":"Trainee","baseTier":"CASHIER","permissions":[],"custom":true}}'
          : '{"error":{"code":"ROLE_ALREADY_EXISTS","message":"This tenant already has a role TRAINEE"}}';
    } else if (o.path.contains('/admin/roles/') && o.method == 'PUT') {
      body = '{"data":{"code":"SHIFT_LEAD","name":"Shift lead","baseTier":"MANAGER","permissions":["sales.void"],"custom":true}}';
    } else if (o.path.contains('/admin/roles/') && o.method == 'DELETE') {
      status = deleteStatus;
      body = status == 204
          ? ''
          : '{"error":{"code":"ROLE_IN_USE","message":"2 staff assignment(s) still use SHIFT_LEAD; remove them first"}}';
    } else if (o.path.endsWith('/admin/staff')) {
      body = '{"data":$staff,"meta":{}}';
    } else if (o.path.endsWith('/iam-svc/auth/admin/staff-users')) {
      body = '{"data":$logins}';
    } else if (o.path.endsWith('/admin/stores')) {
      body = '{"data":[{"id":"01a0-store","name":"Main","code":"MAIN","type":"STORE","status":"ACTIVE"}],"meta":{}}';
    }
    return ResponseBody.fromString(body, status,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
}

Future<_Server> _pump(WidgetTester tester,
    {_Server? server, Size size = const Size(1200, 900)}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final srv = server ?? _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = srv;
  await tester.pumpWidget(ProviderScope(
    key: UniqueKey(),
    overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
    child: const MaterialApp(home: Scaffold(body: StaffScreen())),
  ));
  await tester.pumpAndSettle();
  return srv;
}

Future<void> _openRoles(WidgetTester tester) async {
  await tester.tap(find.text('Roles'));
  await tester.pumpAndSettle();
}

void main() {
  group('the roles tab', () {
    testWidgets('lists the built-in tiers and the tenant\'s own, saying what each holds',
        (tester) async {
      await _pump(tester);
      await _openRoles(tester);
      expect(find.text('Owner'), findsOneWidget);
      expect(find.text('Shift lead'), findsOneWidget);
      expect(find.textContaining('On Manager · staff.manage'), findsOneWidget);
      expect(find.textContaining('MANAGER'), findsNothing);
      expect(find.text('OWNER'), findsNothing);
      expect(find.textContaining('Built in · till.no_sale'), findsOneWidget);
      // Only the custom role can be redefined or deleted.
      expect(find.byKey(const Key('edit-role-SHIFT_LEAD')), findsOneWidget);
      expect(find.byKey(const Key('delete-role-SHIFT_LEAD')), findsOneWidget);
      expect(find.byKey(const Key('edit-role-MANAGER')), findsNothing);
    });

    testWidgets('a role is defined by ticking what its tier holds, and sent upper-cased',
        (tester) async {
      final server = await _pump(tester);
      await _openRoles(tester);
      await tester.tap(find.byKey(const Key('define-role')));
      await tester.pumpAndSettle();
      // Standing on CASHIER: only the drawer is on offer, not voids.
      expect(find.byKey(const Key('role-perm-till.no_sale')), findsOneWidget);
      expect(find.byKey(const Key('role-perm-sales.void')), findsNothing);
      FilledButton save() => tester.widget<FilledButton>(find.byKey(const Key('role-save')));
      expect(save().onPressed, isNull);
      await tester.enterText(find.byKey(const Key('role-code')), 'trainee');
      await tester.pumpAndSettle();
      expect(find.textContaining('Give the role a name'), findsOneWidget);
      await tester.enterText(find.byKey(const Key('role-name')), 'Trainee');
      await tester.pumpAndSettle();
      expect(save().onPressed, isNotNull);
      await tester.tap(find.byKey(const Key('role-save')));
      await tester.pumpAndSettle();
      final sent = server.requests.singleWhere((r) => r.method == 'POST' && r.path.endsWith('/admin/roles'));
      expect(sent.data['code'], 'TRAINEE');
      expect(sent.data['baseTier'], 'CASHIER');
      expect(sent.data['permissions'], isEmpty);
      expect(find.text('TRAINEE defined.'), findsOneWidget);
    });

    testWidgets('a built-in name and a bad code are stopped before they are sent', (tester) async {
      final server = await _pump(tester);
      await _openRoles(tester);
      await tester.tap(find.byKey(const Key('define-role')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('role-code')), 'MANAGER');
      await tester.enterText(find.byKey(const Key('role-name')), 'x');
      await tester.pumpAndSettle();
      expect(find.text('MANAGER is a built-in role.'), findsOneWidget);
      await tester.enterText(find.byKey(const Key('role-code')), 'shift lead');
      await tester.pumpAndSettle();
      expect(find.textContaining('upper-case letters, digits or underscores'), findsOneWidget);
      expect(tester.widget<FilledButton>(find.byKey(const Key('role-save'))).onPressed, isNull);
      expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
    });

    testWidgets('changing the tier drops the ticks the new tier cannot hold, and a refusal is shown in words',
        (tester) async {
      final server = await _pump(tester, server: _Server()..defineStatus = 409);
      await _openRoles(tester);
      await tester.tap(find.byKey(const Key('define-role')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('role-tier')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('MANAGER').last);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('role-perm-sales.void')), findsOneWidget);
      await tester.tap(find.byKey(const Key('role-perm-sales.void')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('role-tier')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('CASHIER').last);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('role-perm-sales.void')), findsNothing);
      await tester.enterText(find.byKey(const Key('role-code')), 'TRAINEE');
      await tester.enterText(find.byKey(const Key('role-name')), 'Trainee');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('role-save')));
      await tester.pumpAndSettle();
      final sent = server.requests.singleWhere((r) => r.method == 'POST' && r.path.endsWith('/admin/roles'));
      expect(sent.data['permissions'], isEmpty, reason: 'the void tick did not survive the tier change');
      expect(find.text('This tenant already has a role TRAINEE'), findsOneWidget);
      expect(find.text('Define a role'), findsOneWidget);
    });

    testWidgets('redefining keeps the code and tier fixed and sends the new set', (tester) async {
      final server = await _pump(tester);
      await _openRoles(tester);
      await tester.tap(find.byKey(const Key('edit-role-SHIFT_LEAD')));
      await tester.pumpAndSettle();
      expect(find.text('Redefine SHIFT_LEAD'), findsOneWidget);
      expect(tester.widget<TextField>(find.byKey(const Key('role-code'))).enabled, isFalse);
      // staff.manage was ticked; tick sales.void too.
      await tester.tap(find.byKey(const Key('role-perm-sales.void')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('role-save')));
      await tester.pumpAndSettle();
      final sent = server.requests.singleWhere((r) => r.method == 'PUT');
      expect(sent.path, contains('/admin/roles/SHIFT_LEAD'));
      expect(sent.data['permissions'], ['sales.void', 'staff.manage']);
      expect(sent.data.containsKey('baseTier'), isFalse);
      expect(find.textContaining('holders carry it from their next sign-in'), findsOneWidget);
    });

    testWidgets('deleting a role names it by its name, in the question and the answer',
        (tester) async {
      final server = await _pump(tester);
      await _openRoles(tester);
      await tester.tap(find.byKey(const Key('delete-role-SHIFT_LEAD')));
      await tester.pumpAndSettle();
      expect(find.text('Delete Shift lead?'), findsOneWidget);
      expect(find.textContaining('SHIFT_LEAD'), findsNothing);
      await tester.tap(find.byKey(const Key('role-delete-confirm')));
      await tester.pumpAndSettle();
      final sent = server.requests.singleWhere((r) => r.method == 'DELETE');
      expect(sent.path, endsWith('/admin/roles/SHIFT_LEAD'));
      expect(find.text('Shift lead deleted.'), findsOneWidget);
      expect(find.textContaining('SHIFT_LEAD'), findsNothing);
    });

    testWidgets('a role in use cannot be deleted, and the reason is shown', (tester) async {
      final server = await _pump(tester, server: _Server()..deleteStatus = 409);
      await _openRoles(tester);
      await tester.tap(find.byKey(const Key('delete-role-SHIFT_LEAD')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('role-delete-confirm')));
      await tester.pumpAndSettle();
      expect(server.requests.where((r) => r.method == 'DELETE').length, 1);
      expect(find.textContaining('still use SHIFT_LEAD'), findsOneWidget);
    });
  });

  group('the people tab', () {
    testWidgets('an assignment through a custom role says which tier it stands on', (tester) async {
      await _pump(tester);
      // The role and its tier by name, as the Roles tab names them.
      expect(find.text('Shift lead'), findsOneWidget);
      expect(find.text('on Manager'), findsOneWidget);
      expect(find.text('SHIFT_LEAD'), findsNothing);
    });

    testWidgets('built-in roles, and one the roles list has not named, read as words',
        (tester) async {
      await _pump(
          tester,
          server: _Server()
            ..staff = '['
                '{"id":"a1","userId":"u-1","storeId":"01a0-store","role":"OWNER","assignedAt":""},'
                '{"id":"a2","userId":"u-2","storeId":"01a0-store","role":"STOREKEEPER","assignedAt":""},'
                '{"id":"a3","userId":"u-3","storeId":"01a0-store","role":"TRAINEE","baseTier":"CASHIER","assignedAt":""}]');
      expect(find.text('Owner'), findsOneWidget);
      expect(find.text('Storekeeper'), findsOneWidget);
      expect(find.text('Trainee'), findsOneWidget);
      expect(find.text('on Cashier'), findsOneWidget);
      for (final code in ['OWNER', 'STOREKEEPER', 'TRAINEE', 'on CASHIER']) {
        expect(find.text(code), findsNothing, reason: code);
      }
    });

    testWidgets('staff are named by their login and their store, not by ids', (tester) async {
      await _pump(tester);
      expect(find.text('sam@shop.test'), findsOneWidget);
      expect(find.text('Main'), findsOneWidget);
      expect(find.textContaining('01a0-user'), findsNothing);
      expect(find.textContaining('01a0-store'), findsNothing);
    });

    testWidgets('a login iam-svc will not name falls back to a short reference', (tester) async {
      await _pump(tester, server: _Server()..logins = '[]');
      // The last eight characters of the id, never the whole of it.
      expect(find.text('1a0-user'), findsOneWidget);
      expect(find.text('01a0-user'), findsNothing);
    });

    testWidgets('on a phone the name has its own line and the actions sit in one menu',
        (tester) async {
      await _pump(
        tester,
        size: const Size(390, 844),
        server: _Server()
          ..staff = '[{"id":"a1","userId":"01a0-user","storeId":"01a0-store",'
              '"role":"TRAINEE","baseTier":"CASHIER","assignedAt":""}]'
          ..logins = '[{"userId":"01a0-user","email":"trainee.one@shop.test"}]',
      );
      expect(tester.takeException(), isNull);
      final name = find.text('trainee.one@shop.test');
      expect(name, findsOneWidget);
      // Its own line, above the role — not squeezed beside it.
      expect(tester.getRect(name).bottom,
          lessThanOrEqualTo(tester.getTopLeft(find.text('Trainee')).dy));
      // And most of the row's width to itself.
      final card = tester.getSize(find.byType(Card).first).width;
      final room = tester.renderObject<RenderBox>(name).constraints.maxWidth;
      expect(room, greaterThan(card / 2));
      // The two actions are one menu on a phone.
      expect(find.byTooltip('Remove'), findsNothing);
      await tester.tap(find.byKey(const Key('staff-actions-a1')));
      await tester.pumpAndSettle();
      expect(find.text('Reset second step'), findsOneWidget);
      expect(find.text('Remove'), findsOneWidget);
    });

    testWidgets('on a phone at 200% text the row still lays out', (tester) async {
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await _pump(tester, size: const Size(390, 844));
      expect(tester.takeException(), isNull);
      expect(find.text('sam@shop.test'), findsOneWidget);
      expect(find.byKey(const Key('staff-actions-a1')), findsOneWidget);
    });

    testWidgets('the title, the tabs and the cards share one inset', (tester) async {
      for (final (size, inset) in [(const Size(1200, 900), 24.0), (const Size(390, 844), 16.0)]) {
        await _pump(tester, size: size);
        expect(tester.getTopLeft(find.text('Staff')).dx, inset, reason: '$size');
        expect(tester.getTopLeft(find.text('People')).dx, inset, reason: '$size');
        expect(tester.getTopLeft(find.byType(Card).first).dx, inset, reason: '$size');
      }
    });

    testWidgets('the assign dialog offers the tenant\'s own roles beside the tiers', (tester) async {
      await _pump(tester);
      await tester.tap(find.text('Assign Staff').first);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('assign-role')));
      await tester.pumpAndSettle();
      expect(find.text('Shift lead · on Manager'), findsWidgets);
      expect(find.text('Storekeeper'), findsWidgets);
      // Words, never the codes.
      expect(find.text('STOREKEEPER'), findsNothing);
      expect(find.textContaining('SHIFT_LEAD'), findsNothing);
    });
  });

  group('a login\'s permissions', () {
    AuthAuthenticated login(List<String> roles, List<String>? perms) => AuthAuthenticated(
        accessToken: 'a', refreshToken: 'r', userId: 'u', tenantId: 't', roles: roles, permissions: perms);

    test('no claim: judged by the tier', () {
      expect(login(['MANAGER'], null).hasPermission('sales.void'), isTrue);
      expect(login(['CASHIER'], null).hasPermission('sales.void'), isFalse);
      expect(login(['CASHIER'], null).hasPermission('till.no_sale'), isTrue);
      expect(login(['STOREKEEPER'], null).hasPermission('stock.adjust'), isTrue);
    });

    test('a claim narrows, even to nothing', () {
      expect(login(['MANAGER'], ['staff.manage']).hasPermission('sales.void'), isFalse);
      expect(login(['MANAGER'], ['staff.manage']).hasPermission('staff.manage'), isTrue);
      expect(login(['CASHIER'], []).hasPermission('till.no_sale'), isFalse);
    });

    test('an owner is never narrowed', () {
      expect(login(['OWNER'], []).hasPermission('sales.void'), isTrue);
      expect(login(['PLATFORM_ADMIN'], []).hasPermission('finance.journal'), isTrue);
    });
  });
}
