import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/stores_screen.dart';

// ---------------------------------------------------------------------------
// SJ-D54 at the screen: editing a store sends the store's own time zone. The
// dialog used to start on 'UTC' and send it when the field was empty, which
// moved a store onto the wrong clock just by renaming it.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Auth extends AuthNotifier {
  final List<String> roles;
  final List<String> storeIds;
  _Auth({this.roles = const ['OWNER'], this.storeIds = const []});

  @override
  Future<AuthState> build() async => AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'user-1',
        tenantId: 'tenant-1',
        roles: roles,
        storeIds: storeIds,
      );
}

class _Server implements HttpClientAdapter {
  final String store;
  final List<RequestOptions> requests = [];
  _Server(this.store);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    var body = '{"data":[],"meta":{}}';
    if (o.path.endsWith('/admin/stores') && o.method == 'GET') {
      body = '{"data":[$store],"meta":{}}';
    } else if (o.path.contains('/admin/stores/') && o.method == 'PUT') {
      body = '{"data":$store}';
    } else if (o.path.endsWith('/zones') && o.method == 'GET') {
      body = '{"data":[{"id":"z-1","storeId":"store-1","name":"Dairy chiller","code":"CR1",'
          '"type":"COLD_ROOM","status":"ACTIVE"}],"meta":{}}';
    } else if (o.path.endsWith('/admin/tenant')) {
      body = '{"data":{"id":"tenant-1","name":"Shop","status":"ACTIVE","currency":"USD","country":"US"}}';
    }
    return ResponseBody.fromString(body, 200,
        headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }

  Map<String, dynamic>? get saved {
    final puts = requests.where((r) => r.method == 'PUT' && r.path.contains('/admin/stores/'));
    if (puts.isEmpty) return null;
    final data = puts.last.data;
    return (data is String ? jsonDecode(data) : data) as Map<String, dynamic>;
  }
}

String _store(
        {String? timezone,
        String id = 'store-1',
        String name = 'Main',
        String status = 'ACTIVE',
        String? tillPhone}) =>
    jsonEncode({
      'id': id,
      'name': name,
      'code': 'MAIN',
      'type': 'STORE',
      'status': status,
      'country': 'US',
      'timezone': ?timezone,
      'showPrices': true,
      'enabledPaymentMethods': ['CASH', 'CARD'],
      'tillPhone': ?tillPhone,
    });

/// [store] is one store's JSON, or several joined by commas. [auth] is the
/// signed-in staff member; an owner, unrestricted, unless a test says otherwise.
Future<_Server> _pump(WidgetTester tester, String store,
    {Size size = const Size(1400, 1600), AuthNotifier Function()? auth}) async {
  final server = _Server(store);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(_FakeApiClient(dio)),
      authNotifierProvider.overrideWith(auth ?? _Auth.new),
    ],
    child: const MaterialApp(home: StoresScreen()),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  testWidgets('the Slots action opens the store\'s delivery & collection slots',
      (tester) async {
    await _pump(tester, _store(timezone: 'Europe/Warsaw'));

    await tester.tap(find.byKey(const Key('store-slots-store-1')));
    await tester.pumpAndSettle();

    expect(find.text('Delivery & collection slots'), findsOneWidget);
    expect(find.textContaining('Europe/Warsaw'), findsOneWidget);
    expect(find.text('No delivery windows yet.'), findsOneWidget);
  });

  testWidgets('editing a store sends its own time zone, never UTC', (tester) async {
    final server = await _pump(tester, _store(timezone: 'America/Chicago'));
    await tester.tap(find.text('Main').first);
    await tester.pumpAndSettle();

    expect(find.text('America/Chicago'), findsWidgets);
    expect(find.text('UTC'), findsNothing);
    await tester.tap(find.text('Save changes'));
    await tester.pumpAndSettle();

    expect(server.saved, isNotNull);
    expect(server.saved!['timezone'], 'America/Chicago');
  });

  testWidgets('a store with no zone must be given one before it can be saved', (tester) async {
    final server = await _pump(tester, _store());
    await tester.tap(find.text('Main').first);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Save changes'));
    await tester.pumpAndSettle();

    // Nothing is sent, so nothing is filled in on the store's behalf.
    expect(server.saved, isNull);
  });

  group('the store list', () {
    const phone = Size(390, 844);

    List<Map<String, dynamic>> patches(_Server server) => [
          for (final r in server.requests)
            if (r.method == 'PATCH')
              (r.data is String ? jsonDecode(r.data as String) : r.data)
                  as Map<String, dynamic>,
        ];

    testWidgets('on a phone the row keeps its text and the tools are in one menu',
        (tester) async {
      await _pump(tester, _store(timezone: 'UTC'), size: phone);

      // The trailing row no longer takes the whole tile (a debug build threw
      // "Trailing widget consumes the entire tile width").
      expect(tester.takeException(), isNull);
      expect(find.text('Zones'), findsNothing);
      expect(find.text('Instruments'), findsNothing);
      final card = tester.getSize(find.byType(Card).first).width;
      final room = tester.renderObject<RenderBox>(find.text('Main')).constraints.maxWidth;
      expect(room, greaterThan(card / 2));

      await tester.tap(find.byKey(const Key('store-menu-store-1')));
      await tester.pumpAndSettle();
      for (final item in ['Edit store', 'Zones', 'Instruments', 'Delivery', 'Close store']) {
        expect(find.text(item), findsOneWidget, reason: item);
      }
    });

    testWidgets('a store\'s zones read in words, the edit tools in one menu on a phone',
        (tester) async {
      await _pump(tester, _store(timezone: 'UTC'), size: phone);
      await tester.tap(find.byKey(const Key('store-menu-store-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Zones'));
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      expect(find.text('CR1 · Cold room'), findsOneWidget);
      expect(find.widgetWithText(StatusBadge, 'Active'), findsOneWidget);
      expect(find.textContaining('COLD_ROOM'), findsNothing);
      expect(find.byKey(const Key('zone-actions-z-1')), findsOneWidget);
      expect(find.byTooltip('Deactivate'), findsNothing);
    });

    testWidgets('the status is a labelled switch in words, not a raw constant',
        (tester) async {
      final server = await _pump(tester, _store(timezone: 'UTC'));

      expect(find.text('ACTIVE'), findsNothing);
      expect(find.text('Open'), findsOneWidget);
      final toggle = find.byKey(const Key('store-status-store-1'));
      expect(tester.widget<Switch>(toggle).value, isTrue);

      // Closing is consequential, so it is confirmed first.
      await tester.tap(toggle);
      await tester.pumpAndSettle();
      expect(find.text('Close Main?'), findsOneWidget);
      await tester.tap(find.text('Close store'));
      await tester.pumpAndSettle();
      expect(patches(server).single['status'], 'INACTIVE');
    });

    testWidgets('a closed store reads Closed and opens from its menu on a phone',
        (tester) async {
      final server =
          await _pump(tester, _store(timezone: 'UTC', status: 'INACTIVE'), size: phone);

      expect(find.text('Closed'), findsOneWidget);
      expect(find.text('INACTIVE'), findsNothing);
      await tester.tap(find.byKey(const Key('store-menu-store-1')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Open store'));
      await tester.pumpAndSettle();
      expect(patches(server).single['status'], 'ACTIVE');
    });

    testWidgets('the last store\'s controls stay clear of the Add Store button',
        (tester) async {
      await _pump(
        tester,
        [for (var i = 0; i < 12; i++) _store(timezone: 'UTC', id: 'store-$i', name: 'Shop $i')]
            .join(','),
        size: phone,
      );
      await tester.drag(find.byType(ListView), const Offset(0, -5000));
      await tester.pumpAndSettle();

      final last = tester.getRect(find.ancestor(
          of: find.text('Shop 11'), matching: find.byType(Card)));
      final fab = tester.getRect(find.byType(FloatingActionButton));
      expect(last.bottom, lessThanOrEqualTo(fab.top));
    });

    testWidgets('on a phone at 200% text the row still lays out', (tester) async {
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await _pump(tester, _store(timezone: 'UTC'), size: phone);
      expect(tester.takeException(), isNull);
      expect(find.byKey(const Key('store-menu-store-1')), findsOneWidget);
      expect(find.text('Open'), findsOneWidget);
    });
  });

  group('who may set delivery & collection slots', () {
    const phone = Size(390, 844);

    testWidgets(
        'a storekeeper is never offered it — not the inline button, not the phone menu',
        (tester) async {
      await _pump(tester, _store(timezone: 'UTC'),
          auth: () =>
              _Auth(roles: const ['STOREKEEPER'], storeIds: const ['store-1']));
      expect(find.byKey(const Key('store-slots-store-1')), findsNothing);

      await _pump(tester, _store(timezone: 'UTC'),
          size: phone,
          auth: () =>
              _Auth(roles: const ['STOREKEEPER'], storeIds: const ['store-1']));
      await tester.tap(find.byKey(const Key('store-menu-store-1')));
      await tester.pumpAndSettle();
      expect(find.text('Delivery & collection slots'), findsNothing);
    });

    testWidgets('a manager held to a different store is not offered it here',
        (tester) async {
      await _pump(tester, _store(timezone: 'UTC'),
          auth: () =>
              _Auth(roles: const ['MANAGER'], storeIds: const ['store-9']));
      expect(find.byKey(const Key('store-slots-store-1')), findsNothing);
    });

    testWidgets('a manager held to this store is offered it', (tester) async {
      await _pump(tester, _store(timezone: 'UTC'),
          auth: () =>
              _Auth(roles: const ['MANAGER'], storeIds: const ['store-1']));
      expect(find.byKey(const Key('store-slots-store-1')), findsOneWidget);
    });

    testWidgets(
        'a manager with no store held against them is unrestricted, like an owner',
        (tester) async {
      await _pump(tester, _store(timezone: 'UTC'),
          auth: () => _Auth(roles: const ['MANAGER'], storeIds: const []));
      expect(find.byKey(const Key('store-slots-store-1')), findsOneWidget);
    });

    testWidgets('an owner is always offered it, whatever storeIds the token carries',
        (tester) async {
      await _pump(tester, _store(timezone: 'UTC'),
          auth: () =>
              _Auth(roles: const ['OWNER'], storeIds: const ['store-9']));
      expect(find.byKey(const Key('store-slots-store-1')), findsOneWidget);
    });
  });

  group('phone-at-the-till (the store form)', () {
    test('StoreInfo reads tillPhone; absent reads as Optional', () {
      expect(StoreInfo.fromJson(jsonDecode(_store())).tillPhone, 'OPTIONAL');
      expect(
          StoreInfo.fromJson(jsonDecode(_store(tillPhone: 'REQUIRED'))).tillPhone,
          'REQUIRED');
      expect(StoreInfo.fromJson(jsonDecode(_store(tillPhone: 'OFF'))).tillPhone,
          'OFF');
    });

    testWidgets(
        'Add Store offers the three choices, Optional preselected, and sends it',
        (tester) async {
      final server = await _pump(tester, _store(timezone: 'UTC'));
      await tester.tap(find.text('Add Store').first);
      await tester.pumpAndSettle();
      // The Type dropdown's widest item ("Dark store (online only)") used to
      // overflow its half of the Code/Type row by 102px on every open.
      expect(tester.takeException(), isNull);

      expect(find.text('Required'), findsOneWidget);
      expect(find.text('Optional'), findsOneWidget);
      expect(find.text("Don't ask"), findsOneWidget);
      final picker =
          tester.widget<SegmentedButton<String>>(find.byType(SegmentedButton<String>));
      expect(picker.selected, {'OPTIONAL'});

      await tester.enterText(
          find.widgetWithText(TextFormField, 'Store name *'), 'Branch 2');
      await tester.enterText(find.widgetWithText(TextFormField, 'Code *'), 'BR2');
      await tester.tap(find.byKey(const ValueKey('timezone-null')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('UTC').first);
      await tester.pumpAndSettle();

      await tester.ensureVisible(find.text('Create store'));
      await tester.tap(find.text('Create store'));
      await tester.pumpAndSettle();

      final created = server.requests.singleWhere(
          (r) => r.method == 'POST' && r.path.endsWith('/admin/stores'));
      final body = (created.data is String
          ? jsonDecode(created.data as String)
          : created.data) as Map<String, dynamic>;
      expect(body['tillPhone'], 'OPTIONAL');
    });

    testWidgets('editing a store preselects its choice and saves a change',
        (tester) async {
      final server =
          await _pump(tester, _store(timezone: 'UTC', tillPhone: 'REQUIRED'));
      await tester.tap(find.text('Main').first);
      await tester.pumpAndSettle();

      final picker =
          tester.widget<SegmentedButton<String>>(find.byType(SegmentedButton<String>));
      expect(picker.selected, {'REQUIRED'});

      await tester.ensureVisible(find.text("Don't ask"));
      await tester.tap(find.text("Don't ask"));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Save changes'));
      await tester.tap(find.text('Save changes'));
      await tester.pumpAndSettle();

      expect(server.saved!['tillPhone'], 'OFF');
    });
  });
}
