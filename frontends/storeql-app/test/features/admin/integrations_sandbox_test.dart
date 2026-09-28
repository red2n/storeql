import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/integrations_screen.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The business's sandbox on the Integrations screen (22.8): the owner makes
// one, sees it, opens it and removes it after confirming; a manager sees it
// and changes nothing; a sandbox key is marked on the list and minted with the
// switch; inside the sandbox the screen says so, offers the way back, and every
// key minted is a sandbox key.
// ---------------------------------------------------------------------------
const _live = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9c10';
const _sandbox = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9c11';
const _liveKey = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9c12';
const _sandboxKey = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9c13';
const _store = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9c14';

Map<String, dynamic> _sandboxJson() => {
      'id': _sandbox,
      'name': 'Hollins Grocers (sandbox)',
      'status': 'ACTIVE',
      'mode': 'SANDBOX',
      'sandboxOf': _live,
      'country': 'GB',
      'currency': 'GBP',
      'createdAt': '2026-09-23T09:00:00Z',
    };

Map<String, dynamic> _key(String id, String name, {bool sandbox = false}) => {
      'id': id,
      'name': name,
      'prefix': sandbox ? 'sqk_test_ABC' : 'sqk_ABCDEFGH',
      'role': 'STOREKEEPER',
      'storeIds': const <String>[],
      'createdBy': '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9c09',
      'createdAt': '2026-09-01T10:00:00Z',
      'expiresAt': null,
      'lastUsedAt': null,
      'revokedAt': null,
      'revokedBy': null,
      'sandbox': sandbox,
    };

class _Server implements HttpClientAdapter {
  bool hasSandbox;
  final List<RequestOptions> requests = [];
  _Server({required this.hasSandbox});

  List<RequestOptions> of(String method, String pathEnd) =>
      requests.where((r) => r.method == method && r.path.endsWith(pathEnd)).toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/admin/tenant/sandbox')) {
      if (o.method == 'POST') {
        hasSandbox = true;
        return jsonResponse(jsonEncode({'data': _sandboxJson()}), 201);
      }
      if (o.method == 'DELETE') {
        hasSandbox = false;
        return jsonResponse(jsonEncode({
          'data': {..._sandboxJson(), 'status': 'INACTIVE', 'deactivatedReason': 'SANDBOX_DELETED'},
        }));
      }
      if (!hasSandbox) {
        return jsonResponse(
          jsonEncode({'code': 'SANDBOX_NOT_FOUND', 'error': {'code': 'SANDBOX_NOT_FOUND', 'message': 'This business has no sandbox'}}),
          404,
        );
      }
      return jsonResponse(jsonEncode({'data': _sandboxJson()}));
    }
    if (o.path.endsWith('/admin/stores')) {
      return jsonResponse(jsonEncode({
        'data': [
          {'id': _store, 'name': 'Leeds', 'code': 'LDS', 'type': 'STORE', 'status': 'ACTIVE'},
        ],
      }));
    }
    if (o.path.contains('/admin/webhooks')) {
      return jsonResponse(jsonEncode({'data': <dynamic>[]}));
    }
    if (o.path.endsWith('/auth/admin/api-keys') && o.method == 'POST') {
      final sandbox = (o.data as Map)['sandbox'] == true;
      return jsonResponse(
        jsonEncode({
          'data': {..._key('019987b0-0f1e-7c3b-8a4d-3e2f1a0b9c15', o.data['name'] as String, sandbox: sandbox), 'key': sandbox ? 'sqk_test_${'x' * 40}' : 'sqk_${'y' * 40}'},
        }),
        201,
      );
    }
    return jsonResponse(jsonEncode({
      'data': {
        'items': [
          _key(_liveKey, 'Warehouse ERP'),
          _key(_sandboxKey, 'ERP rehearsal', sandbox: true),
        ],
        'nextCursor': null,
      },
    }));
  }
}

Future<_Server> _pump(WidgetTester tester, String role,
    {bool hasSandbox = true, bool inside = false, Size size = const Size(1400, 2600), double textScale = 1}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(hasSandbox: hasSandbox);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        authNotifierProvider.overrideWith(() => RoleAuth(role, sandbox: inside)),
      ],
      child: MaterialApp(
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(textScale)),
          child: child!,
        ),
        home: const IntegrationsScreen(),
      ),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('with no sandbox the owner is offered one, and making it asks the server', (tester) async {
    final server = await _pump(tester, 'OWNER', hasSandbox: false);
    expect(find.byKey(const Key('sandbox-none')), findsOneWidget);
    expect(find.byKey(const Key('sandbox-create')), findsOneWidget);
    expect(find.byKey(const Key('sandbox-enter')), findsNothing);

    await tester.tap(find.byKey(const Key('sandbox-create')));
    await tester.pumpAndSettle();
    final sent = server.of('POST', '/admin/tenant/sandbox').single;
    expect(sent.path, contains('/tenant-svc/admin/tenant/sandbox'));
    expect(find.text('Hollins Grocers (sandbox)'), findsWidgets, reason: 'shown once made');
    expect(find.byKey(const Key('sandbox-enter')), findsOneWidget);
    expect(find.byKey(const Key('sandbox-create')), findsNothing);
  });

  testWidgets('with a sandbox the owner sees it, may open it and removes it after confirming', (tester) async {
    final server = await _pump(tester, 'OWNER');
    expect(find.byKey(const Key('sandbox-card')), findsOneWidget);
    expect(find.text('Hollins Grocers (sandbox)'), findsOneWidget);
    expect(find.textContaining('Sandbox plan · made'), findsOneWidget);
    expect(find.textContaining('SANDBOX'), findsNothing);
    expect(find.textContaining(_sandbox), findsNothing, reason: 'the sandbox by its name, never its id');
    expect(find.descendant(of: find.byKey(const Key('sandbox-card')), matching: find.textContaining('tenant')), findsNothing);
    expect(find.byKey(const Key('sandbox-enter')), findsOneWidget);

    await tester.tap(find.byKey(const Key('sandbox-delete')));
    await tester.pumpAndSettle();
    expect(find.text('Remove Hollins Grocers (sandbox)?'), findsOneWidget);
    await tester.tap(find.text('Keep it'));
    await tester.pumpAndSettle();
    expect(server.of('DELETE', '/admin/tenant/sandbox'), isEmpty);

    await tester.tap(find.byKey(const Key('sandbox-delete')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('sandbox-delete-confirm')));
    await tester.pumpAndSettle();
    expect(server.of('DELETE', '/admin/tenant/sandbox'), hasLength(1));
    expect(find.text('Sandbox removed'), findsOneWidget);
    expect(find.byKey(const Key('sandbox-none')), findsOneWidget, reason: 'and there is none now');
  });

  testWidgets('a manager sees the sandbox and can change nothing', (tester) async {
    await _pump(tester, 'MANAGER');
    expect(find.text('Hollins Grocers (sandbox)'), findsOneWidget);
    expect(find.byKey(const Key('sandbox-create')), findsNothing);
    expect(find.byKey(const Key('sandbox-enter')), findsNothing);
    expect(find.byKey(const Key('sandbox-delete')), findsNothing);
  });

  testWidgets('a sandbox key is marked on the list, and minted with the switch', (tester) async {
    final server = await _pump(tester, 'OWNER');
    expect(find.byKey(const Key('sandbox-key-$_sandboxKey')), findsOneWidget);
    expect(find.byKey(const Key('sandbox-key-$_liveKey')), findsNothing);
    expect(find.textContaining('sqk_test_ABC…'), findsOneWidget);

    await tester.tap(find.byKey(const Key('mint-key')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('key-name')), 'ERP rehearsal 2');
    expect(find.byKey(const Key('key-sandbox')), findsOneWidget);
    await tester.tap(find.byKey(const Key('key-sandbox')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('mint-submit')));
    await tester.pumpAndSettle();
    final sent = server.of('POST', '/auth/admin/api-keys').single;
    expect((sent.data as Map)['sandbox'], isTrue);
    expect(find.textContaining('sqk_test_'), findsWidgets, reason: 'the key, shown once');
  });

  testWidgets('a live key is minted without the flag', (tester) async {
    final server = await _pump(tester, 'OWNER');
    await tester.tap(find.byKey(const Key('mint-key')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('key-name')), 'Accounts');
    await tester.tap(find.byKey(const Key('mint-submit')));
    await tester.pumpAndSettle();
    final sent = server.of('POST', '/auth/admin/api-keys').single;
    expect((sent.data as Map).containsKey('sandbox'), isFalse);
  });

  testWidgets('inside the sandbox the screen says so, offers the way back, and every key is a sandbox key', (tester) async {
    await _pump(tester, 'OWNER', inside: true);
    expect(find.byKey(const Key('sandbox-inside')), findsOneWidget);
    expect(find.byKey(const Key('sandbox-leave')), findsOneWidget);
    expect(find.byKey(const Key('sandbox-create')), findsNothing);
    expect(find.byKey(const Key('sandbox-delete')), findsNothing);

    await tester.tap(find.byKey(const Key('mint-key')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('key-sandbox')), findsNothing, reason: 'nothing to choose: every key minted here is a sandbox key');
    expect(find.byKey(const Key('key-sandbox-note')), findsOneWidget);
  });

  test('the platform reads a tenant\'s mode and what it is a sandbox of', () {
    final t = PlatformTenant.fromJson(_sandboxJson());
    expect(t.sandbox, isTrue);
    expect(t.sandboxOf, _live);
    final live = PlatformTenant.fromJson({'id': _live, 'name': 'Hollins Grocers', 'status': 'ACTIVE', 'country': 'GB', 'currency': 'GBP', 'createdAt': ''});
    expect(live.sandbox, isFalse);
    expect(live.mode, 'LIVE');
  });

  for (final scale in [1.0, 2.0]) {
    testWidgets('inside the sandbox on a phone at ${scale}x text the notice reads across the card, the way back under it', (tester) async {
      await _pump(tester, 'OWNER', inside: true, size: const Size(390, 3200), textScale: scale);
      expect(tester.takeException(), isNull);
      final card = tester.getRect(find.byKey(const Key('sandbox-inside')));
      final notice = find.textContaining('You are in the sandbox.');
      final text = tester.getRect(notice);
      final back = tester.getRect(find.byKey(const Key('sandbox-leave')));
      final icon = tester.getRect(find.descendant(of: find.byKey(const Key('sandbox-inside')), matching: find.byIcon(Icons.science_outlined)));
      expect(text.top, greaterThanOrEqualTo(icon.bottom), reason: 'the sentence under the icon, not squeezed beside it');
      expect(text.width, greaterThan(card.width * 0.8), reason: 'the sentence keeps the card\'s width');
      expect(back.top, greaterThanOrEqualTo(text.bottom), reason: 'Back to live under the sentence');
    });

    testWidgets('on a phone at ${scale}x text the section headings and key tiles fit', (tester) async {
      await _pump(tester, 'OWNER', size: const Size(390, 3200), textScale: scale);
      expect(tester.takeException(), isNull);
      expect(find.byKey(const Key('sandbox-enter')), findsOneWidget);
      // Inset by the phone's gutter.
      expect(tester.getTopLeft(find.text('Integrations')).dx, 16);
    });
  }
}
