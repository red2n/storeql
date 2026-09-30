import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/network/api_error.dart';
import 'package:storeql_app/features/admin/fx_rates_card.dart';
import 'package:storeql_app/features/admin/retention_screen.dart';
import 'package:storeql_app/features/admin/security_notices_screen.dart';
import 'package:storeql_app/features/admin/stores_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A manager held to stores is refused the business-wide settings (tenant-svc,
// 30 Sep 2026: 403 BUSINESS_WIDE_ONLY). The app does not offer what the server
// would refuse: no new store, no exchange rate, no retention change, no notice
// acknowledged — and says who does. An owner and a head-office manager (no
// store on the token) keep every control. The refusal reads in words wherever it
// can still arrive.
// ---------------------------------------------------------------------------

const _note = 'Only an owner or a head-office manager changes this.';

class _Server implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    final p = o.path;
    var body = '{"data":[],"meta":{}}';
    if (p.endsWith('/admin/tenant/fx-rates')) {
      body = '{"data":{"home":"GBP","rates":[]}}';
    } else if (p.endsWith('/admin/tenant/retention/runs')) {
      body = '{"data":[]}';
    } else if (p.endsWith('/admin/tenant/retention')) {
      body = '{"data":{"country":"GB","countries":["GB"],"classes":[],"holds":[]}}';
    } else if (p.endsWith('/admin/stores')) {
      body = '{"data":[{"id":"s1","name":"Main","code":"MAIN","type":"STORE","status":"ACTIVE"}],"meta":{}}';
    } else if (p.contains('security-notices')) {
      body = '{"data":[{"id":"n1","incidentId":"i1","title":"Security notice","body":"Read this.",'
          '"issuedAt":"2026-09-14T08:00:00Z","acknowledgedAt":null,"acknowledged":false}]}';
    }
    return jsonResponse(body);
  }
}

Future<void> _pump(WidgetTester tester, Widget screen, {required String role, List<String> storeIds = const []}) async {
  tester.view.physicalSize = const Size(1200, 1400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Server();
  await tester.pumpWidget(ProviderScope(
    key: UniqueKey(),
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role, storeIds: storeIds)),
    ],
    child: MaterialApp(home: Scaffold(body: screen)),
  ));
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(initializeDateFormatting);

  group('a manager held to stores', () {
    testWidgets('is not offered a new store, and is told who opens one', (tester) async {
      await _pump(tester, const StoresScreen(), role: 'MANAGER', storeIds: ['s1']);
      expect(find.text('Add Store'), findsNothing);
      expect(find.text(_note), findsOneWidget);
    });

    testWidgets('sees the exchange rates but is not offered setting one', (tester) async {
      await _pump(tester, const FxRatesCard(management: false, heldToStores: true), role: 'MANAGER', storeIds: ['s1']);
      expect(find.text('Exchange rates'), findsOneWidget);
      expect(find.byKey(const Key('fx-set-rate')), findsNothing);
      expect(find.text(_note), findsOneWidget);
    });

    testWidgets('reads retention but may place no hold and run no purge', (tester) async {
      await _pump(tester, const RetentionScreen(), role: 'MANAGER', storeIds: ['s1']);
      expect(find.byKey(const Key('retention-hold-place')), findsNothing);
      expect(find.text(_note), findsOneWidget);
    });

    testWidgets('reads a security notice but does not acknowledge it', (tester) async {
      await _pump(tester, const SecurityNoticesScreen(), role: 'MANAGER', storeIds: ['s1']);
      expect(find.text('Security notice'), findsOneWidget);
      expect(find.byKey(const Key('acknowledge-n1')), findsNothing);
      expect(find.text(_note), findsOneWidget);
    });
  });

  group('an owner and a head-office manager', () {
    for (final role in ['OWNER', 'MANAGER']) {
      testWidgets('$role keeps every one of those controls, and no note', (tester) async {
        await _pump(tester, const StoresScreen(), role: role);
        expect(find.text('Add Store'), findsOneWidget);
        expect(find.text(_note), findsNothing);
        await _pump(tester, const RetentionScreen(), role: role);
        expect(find.byKey(const Key('retention-hold-place')), findsOneWidget);
        expect(find.text(_note), findsNothing);
        await _pump(tester, const SecurityNoticesScreen(), role: role);
        expect(find.byKey(const Key('acknowledge-n1')), findsOneWidget);
        await _pump(tester, const FxRatesCard(management: true), role: role);
        expect(find.byKey(const Key('fx-set-rate')), findsOneWidget);
      });
    }
  });

  group('BUSINESS_WIDE_ONLY', () {
    DioException refused(String message) => DioException(
          requestOptions: RequestOptions(path: '/x'),
          response: Response(
            requestOptions: RequestOptions(path: '/x'),
            statusCode: 403,
            data: {'error': {'code': 'BUSINESS_WIDE_ONLY', 'message': message}},
          ),
        );

    test('reads in words when the server sent only the code', () {
      expect(friendlyError(refused('BUSINESS_WIDE_ONLY')), _note);
      expect(friendlyError(refused('')), _note);
    });

    test('keeps a server message that says more', () {
      expect(friendlyError(refused('Roles are set for the whole business.')), 'Roles are set for the whole business.');
    });
  });
}
