import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/spacing.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/admin/providers/admin_providers.dart';
import 'package:storeql_app/features/admin/terminals_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The card machines on a business's counters (07.16): each row says which store
// it is at and the make in words, a new machine is added at a store picked from
// the business's own list, and retiring one — which keeps it on record — never
// looks like deleting it.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.path.endsWith('/terminals/vendors')) {
      return jsonResponse(jsonEncode({
        'data': ['STRIPE_TERMINAL', 'ADYEN', 'SIMULATED'],
      }));
    }
    if (o.path.endsWith('/terminals') && o.method == 'POST') {
      return jsonResponse(jsonEncode({'data': {'id': 't-9'}}), 201);
    }
    if (o.path.endsWith('/terminals')) {
      return jsonResponse(jsonEncode({
        'data': [
          {
            'id': 't-1',
            'storeId': 's1',
            'label': 'Till 1',
            'vendor': 'STRIPE_TERMINAL',
            'status': 'ACTIVE',
            'serial': 'WSC-1',
            'createdAt': '2026-09-01T09:00:00Z',
          },
          {
            'id': 't-2',
            'storeId': 's2',
            'label': 'Till 1',
            'vendor': 'ADYEN',
            'status': 'ACTIVE',
          },
          {
            'id': 't-3',
            'storeId': 's1',
            'label': 'Till 2',
            'vendor': 'ADYEN',
            'status': 'RETIRED',
            'retiredReason': 'screen cracked',
          },
        ],
      }));
    }
    return jsonResponse('{"data":[]}');
  }
}

const _stores = [
  StoreInfo(id: 's1', name: 'High Street', code: 'HS', type: 'STORE', status: 'ACTIVE', country: 'GB'),
  StoreInfo(id: 's2', name: 'Station Kiosk', code: 'SK', type: 'STORE', status: 'ACTIVE', country: 'GB'),
  StoreInfo(id: 's3', name: 'Night Kitchen', code: 'NK', type: 'DARK_STORE', status: 'ACTIVE', country: 'GB'),
];

Future<_Server> _pump(WidgetTester tester, {Size size = const Size(1200, 1400)}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: <Override>[
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      storesProvider.overrideWith((ref) async => _stores),
    ],
    child: MaterialApp(theme: AppTheme.light, home: const Scaffold(body: TerminalsScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

String _subtitleOf(WidgetTester tester, String id) {
  final tile = tester.widget<ListTile>(find.byKey(Key('terminal-$id')));
  return (tile.subtitle! as Text).data!;
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('each machine says which store it is at, so two "Till 1"s can be told apart',
      (tester) async {
    await _pump(tester);
    expect(find.text('Till 1'), findsNWidgets(2));
    expect(_subtitleOf(tester, 't-1'), startsWith('High Street · '));
    expect(_subtitleOf(tester, 't-2'), startsWith('Station Kiosk · '));
    expect(_subtitleOf(tester, 't-3'), startsWith('High Street · '));
  });

  testWidgets('the make is its name, not its code', (tester) async {
    await _pump(tester);
    expect(_subtitleOf(tester, 't-1'), contains('Stripe Terminal'));
    expect(_subtitleOf(tester, 't-2'), contains('Adyen'));
    expect(find.textContaining('STRIPE_TERMINAL'), findsNothing);
    expect(find.textContaining('ADYEN'), findsNothing);
  });

  testWidgets('retiring is a power-off, never a bin: the machine stays on record', (tester) async {
    await _pump(tester);
    final retire = find.byKey(const Key('terminal-retire-t-1'));
    expect(find.descendant(of: retire, matching: find.byIcon(Icons.power_settings_new)), findsOneWidget);
    expect(find.byIcon(Icons.delete_outline), findsNothing);
    expect(find.byIcon(Icons.delete), findsNothing);
  });

  testWidgets('a machine is added at a store picked by name, and the store id is what is sent',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('terminal-add')));
    await tester.pumpAndSettle();

    // No store typed by hand: the store is a dropdown of the business's own.
    expect(find.descendant(of: find.byKey(const Key('terminal-store')), matching: find.byType(EditableText)),
        findsNothing);
    // Nothing is sent until a store, a name and a make are given.
    expect(tester.widget<FilledButton>(find.byKey(const Key('terminal-add-save'))).onPressed, isNull);

    await tester.tap(find.byKey(const Key('terminal-store')));
    await tester.pumpAndSettle();
    // A dark store has no till, so no card machine goes on its counter.
    expect(find.text('Night Kitchen'), findsNothing);
    await tester.tap(find.text('Station Kiosk').last);
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('terminal-label')), 'Till 2');
    await tester.tap(find.byKey(const Key('terminal-vendor')));
    await tester.pumpAndSettle();
    expect(find.text('Stripe Terminal').hitTestable(), findsOneWidget);
    await tester.tap(find.text('Adyen').last);
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('terminal-add-save')));
    await tester.pumpAndSettle();
    final post = server.requests.singleWhere((r) => r.method == 'POST');
    final body = post.data as Map<String, dynamic>;
    expect(body['storeId'], 's2');
    expect(body['label'], 'Till 2');
    expect(body['vendor'], 'ADYEN');
    expect(find.byKey(const Key('terminal-add-dialog')), findsNothing);
  });

  testWidgets('on a phone the page sits 16 in from the edge and nothing overflows', (tester) async {
    await _pump(tester, size: const Size(390, 1400));
    expect(tester.getTopLeft(find.text('Card machines')).dx, AppSpacing.lg);
    expect(tester.getTopLeft(find.byKey(const Key('terminal-t-1'))).dx, AppSpacing.lg);
    expect(tester.takeException(), isNull);
  });
}
