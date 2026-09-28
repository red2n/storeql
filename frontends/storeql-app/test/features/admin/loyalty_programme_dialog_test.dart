import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/customer_providers.dart';
import 'package:storeql_app/features/admin/loyalty_programme_dialog.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The loyalty programme dialog (13.x): the platform's default shown as such,
// the ladder edited and saved with its months and reason, a refusal shown by
// name, and the sweep run now saying what it did. The account model's lines
// (tier · next tier · multiplier; points about to expire) are checked too.
// ---------------------------------------------------------------------------

class _FakeApiClient implements ApiClient {
  @override
  Dio dio;
  _FakeApiClient(this.dio);
}

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  bool refuseSave = false;

  @override
  void close({bool force = false}) {}

  static const _default =
      '{"tiers":[{"name":"BRONZE","threshold":0,"multiplier":1},{"name":"SILVER","threshold":1000,"multiplier":1},'
      '{"name":"GOLD","threshold":5000,"multiplier":1},{"name":"PLATINUM","threshold":20000,"multiplier":1}],"isDefault":true}';

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/admin/loyalty/programme') && o.method == 'GET') {
      return _json('{"data":$_default}', 200);
    }
    if (o.path.endsWith('/admin/loyalty/programme') && o.method == 'PUT') {
      if (refuseSave) {
        return _json(
            '{"code":"LOYALTY_TIERS_INVALID","status":400,"error":{"code":"LOYALTY_TIERS_INVALID","message":"thresholds must be ascending"}}',
            400);
      }
      final body = o.data is String ? jsonDecode(o.data as String) : o.data;
      final tiers = (body['tiers'] as List).length;
      return _json(
          '{"data":{"tiers":${jsonEncode(body['tiers'])},"expiryMonths":${body['expiryMonths']},'
          '"qualifyingMonths":${body['qualifyingMonths']},"reason":${jsonEncode(body['reason'])},'
          '"setAt":"2026-09-23T10:00:00Z","isDefault":false,"count":$tiers}}',
          200);
    }
    if (o.path.endsWith('/admin/loyalty/expiry/run') && o.method == 'POST') {
      return _json('{"data":{"customers":2,"points":35.0,"retiered":1}}', 200);
    }
    return _json('{"data":null}', 200);
  }

  static ResponseBody _json(String body, int status) => ResponseBody.fromString(body, status,
      headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
}

Future<_Server> _pump(WidgetTester tester, {bool refuseSave = false}) async {
  tester.view.physicalSize = const Size(1200, 1000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  final server = _Server()..refuseSave = refuseSave;
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(_FakeApiClient(dio))],
      child: MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (context) => Center(
              child: FilledButton(
                onPressed: () => showDialog<void>(
                    context: context, builder: (_) => const LoyaltyProgrammeDialog()),
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.tap(find.text('Open'));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  // The app loads intl's date data through flutter_localizations; a plain test loads it itself.
  setUpAll(initializeDateFormatting);

  testWidgets('the default programme is shown as such, with its four tiers', (tester) async {
    await _pump(tester);
    expect(find.textContaining("The platform's default"), findsOneWidget);
    expect(find.byKey(const Key('tier-name-3')), findsOneWidget);
    expect(find.byKey(const Key('tier-name-4')), findsNothing);
  });

  testWidgets('the ladder is edited and saved with its months and reason', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('tier-remove-3')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('tier-threshold-1')), '10');
    await tester.enterText(find.byKey(const Key('tier-multiplier-1')), '1.5');
    await tester.enterText(find.byKey(const Key('programme-expiry')), '12');
    await tester.enterText(find.byKey(const Key('programme-qualifying')), '12');
    await tester.enterText(find.byKey(const Key('programme-reason')), 'the autumn scheme');
    await tester.tap(find.byKey(const Key('programme-save')));
    await tester.pumpAndSettle();
    final put = server.requests.lastWhere((r) => r.method == 'PUT');
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body['expiryMonths'], 12);
    expect(body['qualifyingMonths'], 12);
    expect(body['reason'], 'the autumn scheme');
    expect((body['tiers'] as List).length, 3);
    expect(body['tiers'][1]['name'], 'SILVER');
    expect(body['tiers'][1]['threshold'], 10);
    expect(body['tiers'][1]['multiplier'], 1.5);
    expect(find.textContaining('Programme saved: 3 tiers, points live 12 months.'), findsOneWidget);
  });

  testWidgets('a refusal is shown by its reason, and the sweep says what it did', (tester) async {
    await _pump(tester, refuseSave: true);
    await tester.enterText(find.byKey(const Key('programme-reason')), 'x');
    await tester.tap(find.byKey(const Key('programme-save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('programme-refusal')), findsOneWidget);
    expect(find.textContaining('thresholds must be ascending'), findsOneWidget);
    await tester.tap(find.byKey(const Key('programme-sweep')));
    await tester.pumpAndSettle();
    expect(find.textContaining('35 points expired for 2 customers; 1 re-tiered.'), findsOneWidget);
  });

  test('the account lines read: the tier, the way up, the benefit; and what is about to die', () {
    final a = LoyaltyAccount.fromJson({
      'pointsBalance': 27,
      'lifetimePoints': 27,
      'tier': 'SILVER',
      'qualifyingPoints': 27,
      'multiplier': 1.5,
      'nextTier': {'name': 'GOLD', 'threshold': 50, 'pointsToGo': 23},
      'expiringSoon': {'points': 20, 'on': '2026-10-23T11:00:00Z'},
      'expiryMonths': 12,
    });
    expect(a.tierLine, 'SILVER · GOLD in 23 pts · ×1.5');
    expect(a.expiringLine, '20 pts expire 23 Oct 2026');
    final top = LoyaltyAccount.fromJson({'pointsBalance': 0, 'tier': 'PLATINUM', 'multiplier': 1});
    expect(top.tierLine, 'PLATINUM');
    expect(top.expiringLine, '');
  });
}
