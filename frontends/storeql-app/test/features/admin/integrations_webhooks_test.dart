import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/integrations_screen.dart';
import 'package:storeql_app/shared/widgets/status_badge.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Webhooks (22.6) on the Integrations screen: the owner sees each endpoint
// with its events and whether it still works, adds one and is shown its
// secret once, reads the deliveries and sends one again, rotates a secret;
// a manager reads, pings and resends, and changes nothing else.
// ---------------------------------------------------------------------------

const _live = '019987c0-0f1e-7c3b-8a4d-3e2f1a0b9d01';
const _off = '019987c0-0f1e-7c3b-8a4d-3e2f1a0b9d02';
const _delivered = '019987c0-0f1e-7c3b-8a4d-3e2f1a0b9d03';
const _dead = '019987c0-0f1e-7c3b-8a4d-3e2f1a0b9d04';
// Fixtures are never secret-shaped: a real signing secret is whsec_ + base64, and a fixture that
// looked like one raised GitHub secret-scanning alert #1. This one carries hyphens and says what
// it is; the widget shows whatever string the server answers.
const _secret = 'whsec_not-a-real-secret-widget-test-fixture';

Map<String, dynamic> _endpoint(String id, String description, {bool enabled = true, String? reason}) => {
      'id': id,
      'url': 'https://erp.example.com/storeql/$description',
      'description': description,
      'events': ['OrderPlaced', 'StockReceived'],
      'enabled': enabled,
      'disabledReason': reason,
      'consecutiveFailures': reason == null ? 0 : 20,
      'lastDeliveredAt': enabled ? '2026-09-22T08:30:00Z' : null,
      'createdAt': '2026-09-01T10:00:00Z',
      'updatedAt': '2026-09-01T10:00:00Z',
    };

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  List<RequestOptions> of(String method) => requests.where((r) => r.method == method).toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/admin/stores')) return jsonResponse(jsonEncode({'data': []}));
    if (path.endsWith('/auth/admin/api-keys')) {
      return jsonResponse(jsonEncode({'data': {'items': [], 'nextCursor': null}}));
    }
    if (path.endsWith('/webhooks/events')) {
      return jsonResponse(jsonEncode({
        'data': [
          {'type': 'OrderPlaced', 'description': 'An order was placed'},
          {'type': 'StockReceived', 'description': 'Stock was booked in'},
          {'type': 'PriceChanged', 'description': 'A price was changed'},
        ],
      }));
    }
    if (path.endsWith('/webhooks/endpoints') && o.method == 'GET') {
      return jsonResponse(jsonEncode({
        'data': [
          _endpoint(_live, 'Warehouse ERP'),
          _endpoint(_off, 'Old accounts', enabled: false, reason: '20 deliveries failed in a row; the last: HTTP 503'),
        ],
      }));
    }
    if (path.endsWith('/webhooks/endpoints') && o.method == 'POST') {
      return jsonResponse(
        jsonEncode({
          'data': {..._endpoint('019987c0-0f1e-7c3b-8a4d-3e2f1a0b9d05', o.data['description'] as String), 'secret': _secret},
        }),
        201,
      );
    }
    if (path.endsWith('/ping')) return jsonResponse(jsonEncode({'data': {'deliveryId': _delivered}}), 202);
    if (path.endsWith('/secret')) return jsonResponse(jsonEncode({'data': {'secret': 'whsec_not-a-real-rotated-secret-widget-test'}}));
    if (path.endsWith('/redeliver')) {
      return jsonResponse(jsonEncode({'data': {'id': _dead, 'endpointId': _live, 'eventId': 'x', 'eventType': 'OrderPlaced', 'status': 'PENDING', 'attempts': 5, 'createdAt': '2026-09-22T09:00:00Z'}}));
    }
    if (path.endsWith('/webhooks/deliveries')) {
      return jsonResponse(jsonEncode({
        'data': {
          'items': [
            {'id': _delivered, 'endpointId': _live, 'eventId': 'e1', 'eventType': 'Ping', 'status': 'DELIVERED', 'attempts': 1, 'lastStatus': 200, 'createdAt': '2026-09-22T09:10:00Z'},
            {'id': _dead, 'endpointId': _live, 'eventId': 'e2', 'eventType': 'OrderPlaced', 'status': 'DEAD', 'attempts': 5, 'lastStatus': 503, 'lastError': 'HTTP 503', 'createdAt': '2026-09-22T09:00:00Z'},
          ],
          'nextCursor': null,
        },
      }));
    }
    if (o.method == 'PUT') {
      return jsonResponse(jsonEncode({'data': _endpoint(_off, 'Old accounts', enabled: o.data['enabled'] == true)}));
    }
    if (o.method == 'DELETE') return jsonResponse(jsonEncode({'data': 'removed'}));
    return jsonResponse(jsonEncode({'data': null}));
  }
}

Future<_Server> _pump(WidgetTester tester, String role, {Size size = const Size(1400, 2800), double textScale = 1}) async {
  tester.view.physicalSize = size;
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

  testWidgets('the owner sees each endpoint: what it is, where, which events, whether it still works', (tester) async {
    await _pump(tester, 'OWNER');
    expect(find.text('Warehouse ERP'), findsOneWidget);
    expect(find.text('https://erp.example.com/storeql/Warehouse ERP'), findsOneWidget);
    expect(find.textContaining('Order placed, Stock received · last delivered'), findsOneWidget, reason: 'the events in words');
    expect(find.textContaining('OrderPlaced'), findsNothing);
    expect(find.textContaining('never delivered'), findsOneWidget);
    expect(find.widgetWithText(StatusBadge, 'Active'), findsOneWidget);
    expect(find.widgetWithText(StatusBadge, 'Off'), findsOneWidget);
    expect(find.byType(Chip), findsNothing, reason: 'a state is a badge, not a hand-coloured chip');
    expect(find.textContaining('Switched off: 20 deliveries failed in a row'), findsOneWidget);
    expect(find.byKey(const Key('add-webhook')), findsOneWidget);
    expect(find.byKey(const Key('rotate-$_live')), findsOneWidget);
    expect(find.byKey(const Key('remove-$_live')), findsOneWidget);
    expect(find.text('Switch on'), findsOneWidget, reason: 'the switched-off endpoint offers to be switched on');
  });

  testWidgets('a manager reads, pings and sees deliveries, and changes nothing else', (tester) async {
    final server = await _pump(tester, 'MANAGER');
    expect(find.text('Warehouse ERP'), findsOneWidget);
    expect(find.byKey(const Key('add-webhook')), findsNothing);
    expect(find.byKey(const Key('rotate-$_live')), findsNothing);
    expect(find.byKey(const Key('remove-$_live')), findsNothing);
    expect(find.byKey(const Key('toggle-$_live')), findsNothing);
    await tester.tap(find.byKey(const Key('ping-$_live')));
    await tester.pumpAndSettle();
    expect(server.of('POST').single.path, endsWith('/webhooks/endpoints/$_live/ping'));
    expect(find.text('Test delivery queued'), findsOneWidget);
  });

  testWidgets('adding an endpoint asks for the address, what it is and the events, then shows the secret once', (tester) async {
    final server = await _pump(tester, 'OWNER');
    await tester.tap(find.byKey(const Key('add-webhook')));
    await tester.pumpAndSettle();
    // Each event by its name in words, with its sentence under it.
    expect(find.descendant(of: find.byKey(const Key('event-OrderPlaced')), matching: find.text('Order placed')), findsOneWidget);
    expect(find.descendant(of: find.byKey(const Key('event-OrderPlaced')), matching: find.text('An order was placed')), findsOneWidget);
    expect(find.text('StockReceived'), findsNothing);
    await tester.enterText(find.byKey(const Key('webhook-url')), 'http://erp.example.com/hook');
    await tester.enterText(find.byKey(const Key('webhook-description')), 'Accounts package');
    await tester.tap(find.byKey(const Key('webhook-submit')));
    await tester.pumpAndSettle();
    expect(find.text('An https:// address'), findsOneWidget, reason: 'plain HTTP is refused before anything is sent');
    expect(server.of('POST'), isEmpty);

    await tester.enterText(find.byKey(const Key('webhook-url')), 'https://erp.example.com/hook');
    await tester.tap(find.byKey(const Key('webhook-submit')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('webhook-error')), findsOneWidget, reason: 'at least one event');
    expect(server.of('POST'), isEmpty);

    await tester.tap(find.byKey(const Key('event-OrderPlaced')));
    await tester.tap(find.byKey(const Key('event-PriceChanged')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('webhook-submit')));
    await tester.pumpAndSettle();
    final sent = server.of('POST').single;
    expect(sent.path, endsWith('/webhooks/endpoints'));
    expect((sent.data as Map)['url'], 'https://erp.example.com/hook');
    expect((sent.data as Map)['description'], 'Accounts package');
    expect((sent.data as Map)['events'], ['OrderPlaced', 'PriceChanged']);
    expect(find.text(_secret), findsOneWidget, reason: 'the secret, shown once');
    expect(find.textContaining('webhook-signature'), findsOneWidget);
    await tester.tap(find.byKey(const Key('key-done')));
    await tester.pumpAndSettle();
    expect(find.text(_secret), findsNothing);
  });

  testWidgets('the deliveries show how each went, and one can be sent again', (tester) async {
    final server = await _pump(tester, 'OWNER');
    await tester.tap(find.byKey(const Key('deliveries-$_live')));
    await tester.pumpAndSettle();
    final ping = find.byKey(const Key('delivery-$_delivered'));
    final dead = find.byKey(const Key('delivery-$_dead'));
    expect(find.descendant(of: ping, matching: find.text('Test delivery')), findsOneWidget);
    expect(find.descendant(of: ping, matching: find.widgetWithText(StatusBadge, 'Delivered')), findsOneWidget);
    expect(find.descendant(of: dead, matching: find.text('Order placed')), findsOneWidget);
    expect(find.descendant(of: dead, matching: find.widgetWithText(StatusBadge, 'Failed')), findsOneWidget);
    expect(find.textContaining('dead'), findsNothing, reason: 'the status in words, never the code');
    expect(find.textContaining('5 tries · last answer 503 · HTTP 503'), findsOneWidget);
    await tester.tap(find.byKey(const Key('redeliver-$_dead')));
    await tester.pumpAndSettle();
    expect(server.of('POST').single.path, endsWith('/webhooks/deliveries/$_dead/redeliver'));
    expect(find.text('Queued to send again'), findsOneWidget);
  });

  testWidgets('rotating asks first, then shows the new secret once; removing asks first', (tester) async {
    final server = await _pump(tester, 'OWNER');
    await tester.tap(find.byKey(const Key('rotate-$_live')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Rotate the secret for Warehouse ERP?'), findsOneWidget);
    await tester.tap(find.byKey(const Key('rotate-confirm')));
    await tester.pumpAndSettle();
    expect(server.of('POST').single.path, endsWith('/webhooks/endpoints/$_live/secret'));
    expect(find.text('whsec_not-a-real-rotated-secret-widget-test'), findsOneWidget);
    await tester.tap(find.byKey(const Key('key-done')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('remove-$_live')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Keep it'));
    await tester.pumpAndSettle();
    expect(server.of('DELETE'), isEmpty);
    await tester.tap(find.byKey(const Key('remove-$_live')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('remove-confirm')));
    await tester.pumpAndSettle();
    expect(server.of('DELETE').single.path, endsWith('/webhooks/endpoints/$_live'));
    expect(find.text('Warehouse ERP removed'), findsOneWidget);
  });

  for (final scale in [1.0, 2.0]) {
    testWidgets('on a phone at ${scale}x text an endpoint reads across the card and its actions sit in one menu', (tester) async {
      final server = await _pump(tester, 'OWNER', size: const Size(390, 3200), textScale: scale);
      expect(tester.takeException(), isNull);
      final card = find.byKey(const Key('webhook-$_live'));
      final icon = tester.getRect(find.descendant(of: card, matching: find.byIcon(Icons.webhook_outlined)));
      final url = tester.getRect(find.text('https://erp.example.com/storeql/Warehouse ERP'));
      expect(url.left, moreOrLessEquals(icon.left), reason: 'the details under the icon, not indented beside it');
      expect(find.descendant(of: card, matching: find.widgetWithText(StatusBadge, 'Active')), findsOneWidget);
      expect(find.byKey(const Key('ping-$_live')), findsNothing, reason: 'no row of five buttons on a phone');
      expect(find.byKey(const Key('remove-$_live')), findsNothing);

      await tester.ensureVisible(find.byKey(const Key('webhook-actions-$_live')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('webhook-actions-$_live')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('remove-$_live')), findsOneWidget);
      await tester.tap(find.byKey(const Key('ping-$_live')));
      await tester.pumpAndSettle();
      expect(server.of('POST').single.path, endsWith('/webhooks/endpoints/$_live/ping'));
      expect(find.text('Test delivery queued'), findsOneWidget);
    });
  }

  testWidgets('on a phone a manager\'s menu holds only Ping and Deliveries', (tester) async {
    await _pump(tester, 'MANAGER', size: const Size(390, 3200));
    await tester.tap(find.byKey(const Key('webhook-actions-$_live')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('ping-$_live')), findsOneWidget);
    expect(find.byKey(const Key('deliveries-$_live')), findsOneWidget);
    expect(find.byKey(const Key('toggle-$_live')), findsNothing);
    expect(find.byKey(const Key('rotate-$_live')), findsNothing);
    expect(find.byKey(const Key('remove-$_live')), findsNothing);
  });
}
