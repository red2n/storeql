import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/accounting_section.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Accounting connectors (17.9) on the Integrations screen: the owner connects
// the package the business keeps its books in, sees what has been pushed,
// pushes now, maps accounts, and deals with a journal the package refused; a
// manager sees the same and cannot connect or disconnect.
// ---------------------------------------------------------------------------
const _failed = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d01';
const _delivered = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d02';
const _held = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d03';
// The package's own ids: a Xero organisation, a QuickBooks company, the id Xero gave a journal.
const _xeroOrg = '5f2d8c1a-4b3e-4d2f-9a1b-9d4e7b2a3c61';
const _realm = '9130349876543210';
const _xeroJournal = '7c1e9a52-3b4d-4e5f-8a9b-0c1d2e3f4a5b';

Map<String, String> _settings(String provider) => switch (provider) {
      'XERO' => {'tenantId': _xeroOrg},
      'QUICKBOOKS' => {'realmId': _realm, 'environment': 'SANDBOX'},
      _ => <String, String>{},
    };

Map<String, dynamic> _connection({String provider = 'SIMULATED', bool active = true}) => {
      'id': '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d10',
      'provider': provider,
      'status': active ? 'ACTIVE' : 'DISABLED',
      'settings': _settings(provider),
      'syncFrom': '2026-09-01',
      'hasRefreshToken': provider == 'XERO',
      'lastSyncAt': '2026-09-23T09:00:00Z',
      'lastError': null,
      'disabledReason': null,
      'counts': {'pending': 1, 'delivered': 12, 'failed': 1, 'uncertain': 0, 'skipped': 0},
    };

List<Map<String, dynamic>> _providers() => [
      {'code': 'XERO', 'name': 'Xero', 'settings': ['tenantId'], 'optional': <String>[], 'tokens': 'A Xero custom connection'},
      {'code': 'QUICKBOOKS', 'name': 'QuickBooks Online', 'settings': ['realmId'], 'optional': ['environment'], 'tokens': 'An Intuit app'},
      {'code': 'SAGE', 'name': 'Sage Business Cloud Accounting', 'settings': ['businessId'], 'optional': <String>[], 'tokens': 'A Sage developer app'},
      {'code': 'SIMULATED', 'name': 'Simulated (the platform\'s stand-in)', 'settings': <String>[], 'optional': ['refuse'], 'tokens': 'None'},
    ];

Map<String, dynamic> _sync(String id, String status, String description, {String? externalId, String? error, Object total = '120.00'}) => {
      'id': id,
      'journalId': '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d2${id.substring(id.length - 1)}',
      'status': status,
      'attempts': status == 'DELIVERED' ? 1 : 2,
      'externalId': externalId,
      'lastError': error,
      'nextAttemptAt': status == 'PENDING' ? '2026-09-23T09:05:00Z' : null,
      'createdAt': '2026-09-23T09:00:00Z',
      'deliveredAt': status == 'DELIVERED' ? '2026-09-23T09:00:02Z' : null,
      'entryDate': '2026-09-23',
      'description': description,
      'sourceType': 'JOURNAL',
      // As purchase-svc writes it: a JSON number, in the ledger's (the business's home) currency.
      'total': total,
    };

class _Server implements HttpClientAdapter {
  bool connected;
  final String provider;
  final bool active;
  final List<Map<String, dynamic>>? syncs;
  final List<RequestOptions> requests = [];
  _Server({required this.connected, this.provider = 'SIMULATED', this.active = true, this.syncs});

  List<RequestOptions> of(String method, String pathEnd) => requests.where((r) => r.method == method && r.path.endsWith(pathEnd)).toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/tenant-svc/admin/tenant')) {
      return jsonResponse(jsonEncode({
        'data': {'id': '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d30', 'name': 'Hollins Grocers', 'status': 'ACTIVE', 'currency': 'GBP', 'country': 'GB'},
      }));
    }
    if (path.endsWith('/accounting/providers')) return jsonResponse(jsonEncode({'data': _providers()}));
    if (path.endsWith('/accounting/connection')) {
      if (o.method == 'PUT') {
        connected = true;
        return jsonResponse(jsonEncode({'data': _connection(provider: (o.data as Map)['provider'] as String)}));
      }
      if (o.method == 'DELETE') {
        connected = false;
        return jsonResponse(jsonEncode({'data': null}));
      }
      if (!connected) return jsonResponse(jsonEncode({'code': 'ACCOUNTING_NOT_CONNECTED'}), 404);
      return jsonResponse(jsonEncode({'data': _connection(provider: provider, active: active)}));
    }
    if (path.endsWith('/connection/sync')) {
      return jsonResponse(jsonEncode({'data': {'queued': 3, 'delivered': 2, 'failed': 1, 'uncertain': 0}}));
    }
    if (path.endsWith('/connection/accounts')) {
      return jsonResponse(jsonEncode({
        'data': [
          {'id': 'SIM-1001', 'code': '1001', 'name': 'Stock', 'type': 'ASSET'},
          {'id': 'SIM-1200', 'code': '1200', 'name': 'Bank', 'type': 'BANK'},
        ],
      }));
    }
    if (path.endsWith('/connection/mappings')) {
      if (o.method == 'PUT') return jsonResponse(jsonEncode({'data': (o.data as Map)['mappings']}));
      return jsonResponse(jsonEncode({'data': [{'nominalCode': '1001', 'externalAccount': 'SIM-1001', 'externalName': null}]}));
    }
    if (path.contains('/syncs/') && o.method == 'POST') {
      return jsonResponse(jsonEncode({'data': _sync(_failed, path.endsWith('/skip') ? 'SKIPPED' : 'PENDING', 'Suspense')}));
    }
    if (path.endsWith('/accounting/syncs')) {
      return jsonResponse(jsonEncode({
        'data': {
          'items': syncs ??
              [
                _sync(_failed, 'FAILED', 'Suspense', error: 'Simulated package refused: unknown account \'9999\''),
                _sync(_delivered, 'DELIVERED', 'Rent', externalId: 'sim-0199'),
              ],
          'nextCursor': null,
        },
      }));
    }
    return jsonResponse(jsonEncode({'data': null}), 404);
  }
}

Future<_Server> _pump(WidgetTester tester,
    {required bool owner,
    bool connected = true,
    String provider = 'SIMULATED',
    bool active = true,
    List<Map<String, dynamic>>? syncs}) async {
  tester.view.physicalSize = const Size(1400, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(connected: connected, provider: provider, active: active, syncs: syncs);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
      child: MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: AccountingSection(owner: owner),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('with nothing connected the owner is offered a package; a manager is not', (tester) async {
    await _pump(tester, owner: true, connected: false);
    expect(find.byKey(const Key('accounting-none')), findsOneWidget);
    expect(find.byKey(const Key('accounting-connect')), findsOneWidget);
    expect(find.byKey(const Key('accounting-card')), findsNothing);

    await _pump(tester, owner: false, connected: false);
    expect(find.byKey(const Key('accounting-connect')), findsNothing);
  });

  testWidgets('connecting Xero asks for the organisation in words and its tokens, and the day to push from on a calendar', (tester) async {
    final server = await _pump(tester, owner: true, connected: false);
    await tester.tap(find.byKey(const Key('accounting-connect')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('acct-setting-tenantId')), findsOneWidget, reason: 'Xero is offered first');
    expect(find.text('Xero organisation *'), findsOneWidget, reason: 'the setting by what it is, not its key');
    expect(find.textContaining('tenantId'), findsNothing);
    expect(find.byKey(const Key('acct-access')), findsOneWidget);

    await tester.enterText(find.byKey(const Key('acct-setting-tenantId')), _xeroOrg);
    await tester.enterText(find.byKey(const Key('acct-access')), 'tok');
    await tester.enterText(find.byKey(const Key('acct-refresh')), 'ref');
    await tester.enterText(find.byKey(const Key('acct-client-id')), 'cid');
    await tester.enterText(find.byKey(const Key('acct-client-secret')), 'sec');

    // The day is chosen on a calendar, never typed as yyyy-MM-dd; it opens on the first of this month.
    final now = DateTime.now();
    expect(find.textContaining('yyyy-MM-dd'), findsNothing);
    await tester.tap(find.byKey(const Key('acct-sync-from')));
    await tester.pumpAndSettle();
    expect(find.byType(DatePickerDialog), findsOneWidget);
    await tester.tap(find.descendant(of: find.byType(DatePickerDialog), matching: find.text('15')));
    await tester.tap(find.text('OK'));
    await tester.pumpAndSettle();
    expect(find.byType(DatePickerDialog), findsNothing);

    await tester.tap(find.byKey(const Key('acct-submit')));
    await tester.pumpAndSettle();
    final sent = server.of('PUT', '/accounting/connection').single;
    final body = sent.data as Map;
    expect(body['provider'], 'XERO');
    expect((body['settings'] as Map)['tenantId'], _xeroOrg);
    expect((body['credentials'] as Map)['accessToken'], 'tok');
    expect((body['credentials'] as Map)['clientSecret'], 'sec');
    expect(body['syncFrom'], '${now.year}-${now.month.toString().padLeft(2, '0')}-15', reason: 'sent as ISO, as before');
    expect(find.text('Xero connected'), findsOneWidget);
  });

  testWidgets('QuickBooks asks for the company and the environment in words, and sends the codes', (tester) async {
    final server = await _pump(tester, owner: true, connected: false);
    await tester.tap(find.byKey(const Key('accounting-connect')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('acct-provider')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('QuickBooks Online').last);
    await tester.pumpAndSettle();
    expect(find.text('QuickBooks company *'), findsOneWidget);
    expect(find.text('Environment'), findsOneWidget);
    expect(find.textContaining('realmId'), findsNothing);

    await tester.enterText(find.byKey(const Key('acct-setting-realmId')), _realm);
    await tester.tap(find.byKey(const Key('acct-setting-environment')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Sandbox').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('acct-access')), 'tok');
    await tester.tap(find.byKey(const Key('acct-submit')));
    await tester.pumpAndSettle();
    final body = server.of('PUT', '/accounting/connection').single.data as Map;
    expect(body['provider'], 'QUICKBOOKS');
    expect(body['settings'], {'realmId': _realm, 'environment': 'SANDBOX'});
    final now = DateTime.now();
    expect(body['syncFrom'], '${now.year}-${now.month.toString().padLeft(2, '0')}-01', reason: 'the first of this month unless another day is chosen');
  });

  testWidgets('the stand-in needs no tokens and sends none', (tester) async {
    final server = await _pump(tester, owner: true, connected: false);
    await tester.tap(find.byKey(const Key('accounting-connect')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('acct-provider')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Simulated (the platform\'s stand-in)').last);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('acct-access')), findsNothing);
    expect(find.byKey(const Key('acct-setting-refuse')), findsOneWidget, reason: 'an optional setting is offered, not required');
    await tester.tap(find.byKey(const Key('acct-submit')));
    await tester.pumpAndSettle();
    final body = server.of('PUT', '/accounting/connection').single.data as Map;
    expect(body['provider'], 'SIMULATED');
    expect(body.containsKey('credentials'), isFalse);
    expect(body['settings'], isEmpty);
  });

  testWidgets('connected: the card says what is where, Push now pushes, and the pushes are listed with what needs a person', (tester) async {
    final server = await _pump(tester, owner: false);
    expect(find.byKey(const Key('accounting-card')), findsOneWidget);
    expect(find.text('Simulated package'), findsOneWidget);
    expect(find.text('Delivered 12'), findsOneWidget);
    expect(find.text('Failed 1'), findsOneWidget);
    expect(find.byKey(const Key('accounting-disconnect')), findsNothing, reason: 'a manager cannot disconnect');

    await tester.tap(find.byKey(const Key('accounting-sync')));
    await tester.pumpAndSettle();
    expect(server.of('POST', '/connection/sync'), hasLength(1));
    expect(find.text('3 queued, 2 pushed, 1 refused'), findsOneWidget);

    expect(find.text('Rent'), findsOneWidget);
    expect(find.textContaining('in the package as sim-0199'), findsOneWidget);
    expect(find.text('Suspense'), findsOneWidget);
    expect(find.textContaining('unknown account'), findsOneWidget);
    expect(find.byKey(const Key('sync-retry-$_failed')), findsOneWidget);
    expect(find.byKey(const Key('sync-retry-$_delivered')), findsNothing, reason: 'a delivered journal is not pushed again');

    await tester.tap(find.byKey(const Key('sync-retry-$_failed')));
    await tester.pumpAndSettle();
    expect(server.of('POST', '/syncs/$_failed/retry'), hasLength(1));

    await tester.tap(find.byKey(const Key('sync-skip-$_failed')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('sync-skip-reason')), 'entered by hand');
    await tester.tap(find.byKey(const Key('sync-skip-confirm')));
    await tester.pumpAndSettle();
    final skipped = server.of('POST', '/syncs/$_failed/skip').single;
    expect((skipped.data as Map)['reason'], 'entered by hand');
  });

  testWidgets('a push\'s total is money in the business\'s home currency, since the push carries none', (tester) async {
    await _pump(tester, owner: true, syncs: [
      _sync(_failed, 'FAILED', 'Suspense', error: 'refused', total: 186.0),
      _sync(_delivered, 'DELIVERED', 'Rent', externalId: 'sim-0199', total: 1245.6),
    ]);
    expect(find.textContaining('£186.00'), findsOneWidget);
    expect(find.textContaining('£1,245.60'), findsOneWidget);
    expect(find.textContaining('186.0 ·'), findsNothing, reason: 'never the raw number');
  });

  testWidgets('the card names the package\'s settings in words, its ids by a short ref, and whether it pushes as a badge', (tester) async {
    await _pump(tester, owner: true, provider: 'XERO', syncs: [
      _sync(_delivered, 'DELIVERED', 'Rent', externalId: _xeroJournal),
    ]);
    expect(find.text('Xero'), findsOneWidget);
    expect(find.textContaining('Xero organisation …7b2a3c61'), findsOneWidget);
    expect(find.textContaining('tenantId'), findsNothing);
    expect(find.textContaining(_xeroOrg), findsNothing, reason: 'the full id is never written out');
    expect(find.widgetWithText(StatusBadge, 'Pushing'), findsOneWidget);
    expect(find.widgetWithText(Chip, 'Pushing'), findsNothing);
    expect(find.textContaining('in the package as …2e3f4a5b'), findsOneWidget);
    expect(find.textContaining(_xeroJournal), findsNothing);
  });

  testWidgets('QuickBooks: the company by a short ref and the environment in words; switched off, a badge says so', (tester) async {
    await _pump(tester, owner: true, provider: 'QUICKBOOKS', active: false);
    expect(find.textContaining('QuickBooks company …76543210'), findsOneWidget);
    expect(find.textContaining('Sandbox environment'), findsOneWidget);
    expect(find.textContaining('SANDBOX'), findsNothing);
    expect(find.widgetWithText(StatusBadge, 'Switched off'), findsOneWidget);
    expect(find.widgetWithText(Chip, 'Switched off'), findsNothing);
  });

  testWidgets('a push still waiting reads Waiting in the info tone, as a waiting delivery does', (tester) async {
    await _pump(tester, owner: true, syncs: [_sync(_held, 'PENDING', 'Accruals')]);
    final badge = tester.widget<StatusBadge>(find.widgetWithText(StatusBadge, 'Waiting'));
    expect(badge.tone, StatusTone.info);
  });

  testWidgets('a push in a state the app does not know yet still reads as words', (tester) async {
    await _pump(tester, owner: true, syncs: [_sync(_held, 'ON_HOLD', 'Accruals')]);
    expect(find.widgetWithText(StatusBadge, 'On hold'), findsOneWidget);
    expect(find.text('ON_HOLD'), findsNothing);
  });

  testWidgets('mapping accounts shows what is mapped, offers the package\'s chart, and saves the whole mapping', (tester) async {
    final server = await _pump(tester, owner: true);
    await tester.tap(find.byKey(const Key('accounting-map')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('map-code-0')), findsOneWidget);
    expect((tester.widget(find.byKey(const Key('map-code-0'))) as TextField).controller!.text, '1001');
    await tester.tap(find.byKey(const Key('map-add')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('map-code-1')), '2109');
    await tester.tap(find.byKey(const Key('map-account-1')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('1200 · Bank').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('map-save')));
    await tester.pumpAndSettle();
    final sent = server.of('PUT', '/connection/mappings').single;
    final mappings = (sent.data as Map)['mappings'] as List;
    expect(mappings, hasLength(2));
    expect(mappings[1], {'nominalCode': '2109', 'externalAccount': 'SIM-1200'});
  });

  testWidgets('the owner disconnects after confirming', (tester) async {
    final server = await _pump(tester, owner: true);
    await tester.tap(find.byKey(const Key('accounting-disconnect')));
    await tester.pumpAndSettle();
    expect(find.text('Disconnect Simulated package?'), findsOneWidget);
    await tester.tap(find.byKey(const Key('accounting-disconnect-confirm')));
    await tester.pumpAndSettle();
    expect(server.of('DELETE', '/accounting/connection'), hasLength(1));
    expect(find.byKey(const Key('accounting-none')), findsOneWidget);
  });
}
