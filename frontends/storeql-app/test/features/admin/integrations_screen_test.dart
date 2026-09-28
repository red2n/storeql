import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/integrations_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// A business's API keys (22.7): the owner sees each key with what it is and
// whether it still works, mints one and is shown the key once, and revokes
// one after confirming; a manager reads the list and can change nothing.
// ---------------------------------------------------------------------------

const _active = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9c01';
const _revoked = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9c02';
const _store = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9c03';
const _secret = 'sqk_ZmFrZS1rZXktZm9yLXRoZS10ZXN0LXN1aXRlLTEyMzQ1';

Map<String, dynamic> _key(String id, String name, {String? revokedAt, String? lastUsedAt, List<String> stores = const []}) => {
      'id': id,
      'name': name,
      'prefix': 'sqk_ABCDEFGH',
      'role': 'STOREKEEPER',
      'storeIds': stores,
      'createdBy': '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9c09',
      'createdAt': '2026-09-01T10:00:00Z',
      'expiresAt': null,
      'lastUsedAt': lastUsedAt,
      'revokedAt': revokedAt,
      'revokedBy': null,
    };

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  List<RequestOptions> ofMethod(String method) => requests.where((r) => r.method == method).toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/admin/stores')) {
      return jsonResponse(jsonEncode({
        'data': [
          {'id': _store, 'name': 'Leeds', 'code': 'LDS', 'type': 'STORE', 'status': 'ACTIVE'},
        ],
      }));
    }
    if (o.method == 'POST') {
      return jsonResponse(
        jsonEncode({
          'data': {..._key('019987b0-0f1e-7c3b-8a4d-3e2f1a0b9c04', o.data['name'] as String), 'key': _secret},
        }),
        201,
      );
    }
    if (o.method == 'DELETE') {
      return jsonResponse(jsonEncode({'data': _key(_active, 'Warehouse ERP', revokedAt: '2026-09-23T09:00:00Z')}));
    }
    return jsonResponse(jsonEncode({
      'data': {
        'items': [
          _key(_active, 'Warehouse ERP', lastUsedAt: '2026-09-22T08:30:00Z', stores: [_store]),
          _key(_revoked, 'Old till feed', revokedAt: '2026-08-01T12:00:00Z'),
        ],
        'nextCursor': null,
      },
    }));
  }
}

Future<_Server> _pump(WidgetTester tester, String role) async {
  tester.view.physicalSize = const Size(1400, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        authNotifierProvider.overrideWith(() => RoleAuth(role)),
      ],
      child: const MaterialApp(home: IntegrationsScreen()),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('the owner sees each key: what it is, where it works, when it was used, whether it still works', (tester) async {
    await _pump(tester, 'OWNER');
    expect(find.text('Warehouse ERP'), findsOneWidget);
    expect(find.textContaining('sqk_ABCDEFGH…'), findsNWidgets(2));
    expect(find.textContaining('Storekeeper · 1 store · last used'), findsOneWidget);
    expect(find.textContaining('every store · never used'), findsOneWidget);
    expect(find.text('Active'), findsOneWidget);
    expect(find.text('Revoked'), findsOneWidget);
    expect(find.byKey(const Key('mint-key')), findsOneWidget);
    expect(find.byKey(const Key('revoke-$_active')), findsOneWidget);
    expect(find.byKey(const Key('revoke-$_revoked')), findsNothing, reason: 'a revoked key cannot be revoked again');
  });

  testWidgets('a manager reads the list and can change nothing', (tester) async {
    await _pump(tester, 'MANAGER');
    expect(find.text('Warehouse ERP'), findsOneWidget);
    expect(find.byKey(const Key('mint-key')), findsNothing);
    expect(find.byKey(const Key('revoke-$_active')), findsNothing);
  });

  testWidgets('minting asks for a name, a tier and the stores, then shows the key once', (tester) async {
    final server = await _pump(tester, 'OWNER');
    await tester.tap(find.byKey(const Key('mint-key')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('key-name')), 'Accounts package');
    await tester.tap(find.byKey(const Key('key-role')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Manager').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('store-$_store')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mint-submit')));
    await tester.pumpAndSettle();

    final sent = server.ofMethod('POST').single;
    expect(sent.path, endsWith('/auth/admin/api-keys'));
    expect((sent.data as Map)['name'], 'Accounts package');
    expect((sent.data as Map)['role'], 'MANAGER');
    expect((sent.data as Map)['storeIds'], [_store]);
    expect((sent.data as Map).containsKey('expiresAt'), isFalse);

    expect(find.text(_secret), findsOneWidget, reason: 'the key, shown once');
    expect(find.textContaining('shown once'), findsOneWidget);
    expect(find.byKey(const Key('key-copy')), findsOneWidget);
    await tester.tap(find.byKey(const Key('key-done')));
    await tester.pumpAndSettle();
    expect(find.text(_secret), findsNothing, reason: 'and never again');
  });

  testWidgets('a name is required before anything is sent', (tester) async {
    final server = await _pump(tester, 'OWNER');
    await tester.tap(find.byKey(const Key('mint-key')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mint-submit')));
    await tester.pumpAndSettle();
    expect(find.text('Required'), findsOneWidget);
    expect(server.ofMethod('POST'), isEmpty);
  });

  testWidgets('revoking asks first, then tells the server and the owner', (tester) async {
    final server = await _pump(tester, 'OWNER');
    await tester.tap(find.byKey(const Key('revoke-$_active')));
    await tester.pumpAndSettle();
    expect(find.text('Revoke Warehouse ERP?'), findsOneWidget);
    await tester.tap(find.text('Keep it'));
    await tester.pumpAndSettle();
    expect(server.ofMethod('DELETE'), isEmpty, reason: 'nothing sent when the owner keeps it');

    await tester.tap(find.byKey(const Key('revoke-$_active')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('revoke-confirm')));
    await tester.pumpAndSettle();
    final sent = server.ofMethod('DELETE').single;
    expect(sent.path, endsWith('/auth/admin/api-keys/$_active'));
    expect(find.text('Warehouse ERP revoked'), findsOneWidget);
  });
}
