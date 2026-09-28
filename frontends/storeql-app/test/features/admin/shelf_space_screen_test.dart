import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:intl/intl.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/shelf_space_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Shelf space and range (07.17, 07.18): the gap a shop acts on in the morning,
// the shelving a layout is drawn for, and range changes that are due but not in
// force.
//
// The assertions worth having here are the ones a shop would notice if they went
// wrong: a bay below its presentation minimum is marked, a refused range change
// is reported by its reason rather than silently counted as done, and the store
// shown is the one that was picked.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  Map<String, dynamic> sweep;

  _Server({required this.sweep});

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    if (o.method == 'GET' && o.path.contains('/admin/stores')) {
      return jsonResponse(jsonEncode({
        'data': [
          {'id': 's1', 'name': 'High Street', 'code': 'HS', 'type': 'STORE', 'status': 'ACTIVE'},
          {'id': 's2', 'name': 'Retail Park', 'code': 'RP', 'type': 'STORE', 'status': 'ACTIVE'},
        ],
      }));
    }
    if (o.method == 'GET' && o.path.contains('shelf-gaps')) {
      final store = o.queryParameters['storeId'];
      return jsonResponse(jsonEncode({
        'data': store == 's1'
            ? [
                {
                  'storeId': 's1',
                  'variantId': 'v-empty',
                  'capacity': 60,
                  'minPresentation': 12,
                  'available': '4.000',
                  'gap': '56.000',
                  'belowMinimum': true,
                },
                {
                  'storeId': 's1',
                  'variantId': 'v-full',
                  'capacity': 12,
                  'minPresentation': 3,
                  'available': '20.000',
                  'gap': '0.000',
                  'belowMinimum': false,
                },
              ]
            : [],
      }));
    }
    if (o.method == 'GET' && o.path.contains('/variants/resolve')) {
      // product-svc names the variants the gap report carries only by id.
      return jsonResponse(jsonEncode({
        'data': [
          {'variantId': 'v-empty', 'productName': 'Oat milk 1L', 'sku': 'OAT-1L'},
          {'variantId': 'v-full', 'productName': 'Sourdough loaf', 'sku': 'SD-800'},
        ],
      }));
    }
    if (o.method == 'GET' && o.path.endsWith('/admin/products/p1')) {
      return jsonResponse(jsonEncode({
        'data': {'id': 'p1', 'name': 'Tinned peaches', 'status': 'ACTIVE'},
      }));
    }
    if (o.method == 'GET' && o.path.contains('/merchandising/fixtures')) {
      return jsonResponse(jsonEncode({
        'data': [
          {
            'id': 'f1',
            'code': 'GOND-1',
            'name': 'Aisle 4 gondola',
            'kind': 'GONDOLA',
            'shelfCount': 4,
            'shelfWidthMm': 1200,
            'totalWidthMm': 4800,
            'status': 'ACTIVE',
          },
        ],
      }));
    }
    if (o.method == 'GET' && o.path.contains('/changes/due')) {
      return jsonResponse(jsonEncode({
        'data': [
          {
            'id': 'c1',
            'productId': 'p1',
            'action': 'DELIST',
            'effectiveFrom': '2026-09-19',
            'reason': 'Bottom of the category on margin',
          },
        ],
      }));
    }
    if (o.method == 'POST' && o.path.contains('/changes/apply')) {
      return jsonResponse(jsonEncode({'data': sweep}));
    }
    return jsonResponse(jsonEncode({'data': []}));
  }
}

Future<_Server> _pump(WidgetTester tester, Widget child,
    {Map<String, dynamic>? sweep,
    Size size = const Size(1400, 2400),
    AuthNotifier Function() auth = _owner}) async {
  tester.view.physicalSize = size;
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server(sweep: sweep ?? {'applied': 1, 'notApplied': []});
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        authNotifierProvider.overrideWith(auth),
      ],
      child: MaterialApp(home: Scaffold(body: child)),
    ),
  );
  await tester.pumpAndSettle();
  return server;
}

AuthNotifier _owner() => RoleAuth('OWNER');

/// The card a row sits in.
Finder _cardOf(String key) =>
    find.ancestor(of: find.byKey(Key(key)), matching: find.byType(Card));

void main() {
  // Dates are written in the app's own locale (en_GB), whose symbols load here.
  setUpAll(initializeDateFormatting);
  // The dates below are read the British way (19 Sep): pinned, since the app itself assumes no
  // country for English.
  setUp(() => Intl.defaultLocale = 'en_GB');

  testWidgets('a bay below its presentation minimum is marked, a full one is not',
      (tester) async {
    await _pump(tester, const ShelfSpaceScreen());

    expect(find.text('56 to fill  ·  shelf holds 60'), findsOneWidget);
    expect(find.text('0 to fill  ·  shelf holds 12'), findsOneWidget);
    // The warning is on the picked-over bay only: 20 units against a shelf of 12
    // is not a gap, however low the reorder level would call it.
    expect(find.text('Below minimum'), findsOneWidget);
    expect(find.textContaining('looks picked over below 12'), findsOneWidget);
  });

  testWidgets('the shelving says what a layout is checked against', (tester) async {
    await _pump(tester, const ShelfSpaceScreen());
    await tester.tap(find.text('Shelving'));
    await tester.pumpAndSettle();

    expect(find.text('Aisle 4 gondola  ·  GOND-1'), findsOneWidget);
    expect(find.text('gondola  ·  4 shelves of 1200mm  ·  4800mm in all'),
        findsOneWidget);
  });

  testWidgets('picking another store reads that store\'s shelves', (tester) async {
    final server = await _pump(tester, const ShelfSpaceScreen());

    await tester.tap(find.byKey(const Key('shelf-store')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Retail Park').last);
    await tester.pumpAndSettle();

    expect(server.requests.last.queryParameters['storeId'], 's2');
    expect(find.byKey(const Key('gaps-empty')), findsOneWidget);
  });

  testWidgets('a due change is shown with its reason and applied on request',
      (tester) async {
    final server = await _pump(tester, const ShelfSpaceScreen());
    await tester.tap(find.text('Range'));
    await tester.pumpAndSettle();

    // 'Sep' and not 'Sep 2026': en_GB abbreviates September as "Sept".
    expect(find.textContaining('De-list  ·  due 19 Sep'), findsOneWidget);
    expect(find.textContaining('2026-09-19'), findsNothing);
    expect(find.text('Bottom of the category on margin'), findsOneWidget);

    await tester.tap(find.byKey(const Key('range-apply')));
    await tester.pumpAndSettle();
    expect(
        server.requests.any((r) =>
            r.method == 'POST' && r.path.contains('/changes/apply')),
        isTrue);
    expect(find.text('1 change in force.'), findsOneWidget);
  });

  testWidgets('a change that could not be applied is named, not counted as done',
      (tester) async {
    // The refusal matters more than the count: the change stays due, so the shop
    // fixes the cause instead of re-entering the decision.
    await _pump(
      tester,
      const ShelfSpaceScreen(),
      sweep: {
        'applied': 0,
        'notApplied': [
          {
            'changeId': 'c1',
            'productId': 'p1',
            'code': 'ASSORTMENT_RANGED_EVERYWHERE',
            'detail': 'This line has no store range at all',
          },
        ],
      },
    );
    await tester.tap(find.text('Range'));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('range-apply')));
    await tester.pumpAndSettle();

    expect(
        find.textContaining('1 could not be applied. This line has no store range'),
        findsOneWidget);
  });

  testWidgets('each gap names its product and SKU, not a variant id',
      (tester) async {
    await _pump(tester, const ShelfSpaceScreen());

    expect(find.text('Oat milk 1L  ·  OAT-1L'), findsOneWidget);
    expect(find.text('Sourdough loaf  ·  SD-800'), findsOneWidget);
    expect(find.textContaining('v-empty'), findsNothing);
  });

  testWidgets('counts of units read as whole numbers, not the ledger\'s decimals',
      (tester) async {
    await _pump(tester, const ShelfSpaceScreen());

    expect(find.text('4 available  ·  looks picked over below 12'), findsOneWidget);
    expect(find.text('20 available  ·  looks picked over below 3'), findsOneWidget);
    expect(find.textContaining('.000'), findsNothing);
  });

  testWidgets('the cards are 8px apart, so their outlines do not double up',
      (tester) async {
    await _pump(tester, const ShelfSpaceScreen());

    final first = tester.getRect(_cardOf('gap-v-empty'));
    final second = tester.getRect(_cardOf('gap-v-full'));
    expect(second.top - first.bottom, 8);
  });

  testWidgets('the store picker is a themed field, not a bare underlined button',
      (tester) async {
    await _pump(tester, const ShelfSpaceScreen());

    // A field draws its outline from the theme's input decoration; a bare
    // DropdownButton draws Flutter's fixed grey underline instead.
    expect(
        find.descendant(
            of: find.byKey(const Key('shelf-store')),
            matching: find.byType(InputDecorator)),
        findsOneWidget);
  });

  testWidgets('the first tab starts at the page edge, under the title',
      (tester) async {
    await _pump(tester, const ShelfSpaceScreen());

    final edge = tester.getTopLeft(find.text('Shelf space')).dx;
    expect(edge, 24);
    expect(tester.getTopLeft(find.text('Gaps to fill')).dx, edge);
    expect(tester.getTopLeft(find.textContaining('What it would take')).dx, edge);
  });

  testWidgets('on a phone the page is inset 16, title, tabs and list alike',
      (tester) async {
    await _pump(tester, const ShelfSpaceScreen(), size: const Size(390, 844));

    expect(tester.takeException(), isNull);
    expect(tester.getTopLeft(find.text('Shelf space')).dx, 16);
    expect(tester.getTopLeft(find.text('Gaps to fill')).dx, 16);
    expect(tester.getTopLeft(find.textContaining('What it would take')).dx, 16);
    expect(tester.getTopLeft(_cardOf('gap-v-empty')).dx, 16);
  });

  testWidgets('on a phone at 200% text the gaps still lay out', (tester) async {
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await _pump(tester, const ShelfSpaceScreen(), size: const Size(390, 844));
    expect(tester.takeException(), isNull);
    // Below the fold at this size: scroll the gaps list to it.
    await tester.scrollUntilVisible(find.text('Below minimum'), 200,
        scrollable: find
            .descendant(of: find.byType(ListView), matching: find.byType(Scrollable))
            .first);
    expect(tester.takeException(), isNull);
  });

  testWidgets('a due range change names the product it is about', (tester) async {
    await _pump(tester, const ShelfSpaceScreen());
    await tester.tap(find.text('Range'));
    await tester.pumpAndSettle();

    expect(find.text('Tinned peaches'), findsOneWidget);
  });

  // ── a storekeeper: the Gaps tab only, at their own stores ──────────────────

  group('a storekeeper', () {
    testWidgets('sees only the Gaps tab, never Shelving or Range', (tester) async {
      await _pump(tester, const ShelfSpaceScreen(), auth: () => RoleAuth('STOREKEEPER'));

      expect(find.text('Gaps to fill'), findsOneWidget);
      expect(find.text('Shelving'), findsNothing);
      expect(find.text('Range'), findsNothing);
    });

    testWidgets('is offered only their own stores', (tester) async {
      await _pump(
        tester,
        const ShelfSpaceScreen(),
        auth: () => _StoreHeld('STOREKEEPER', const ['s2']),
      );

      // High Street (s1) is not theirs; Retail Park (s2) is, and its empty
      // gaps report shows.
      expect(find.text('Retail Park'), findsOneWidget);
      expect(find.text('High Street'), findsNothing);
      expect(find.byKey(const Key('gaps-empty')), findsOneWidget);
    });

    testWidgets('a manager keeps all three tabs', (tester) async {
      await _pump(tester, const ShelfSpaceScreen(), auth: () => RoleAuth('MANAGER'));

      expect(find.text('Gaps to fill'), findsOneWidget);
      expect(find.text('Shelving'), findsOneWidget);
      expect(find.text('Range'), findsOneWidget);
    });
  });
}

class _StoreHeld extends AuthNotifier {
  final String role;
  final List<String> storeIds;
  _StoreHeld(this.role, this.storeIds);

  @override
  Future<AuthState> build() async => AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'u',
        tenantId: 't',
        roles: [role],
        storeIds: storeIds,
      );
}
