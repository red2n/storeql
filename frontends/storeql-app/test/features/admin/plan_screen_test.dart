import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/plan_screen.dart';

import '../../support/fake_api.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// The plan a business is on (21.8): what it allows against what is in use, a
// count the owning service could not give shown as unknown rather than guessed
// at, and a business on no plan told plainly that nothing is limited.
// ---------------------------------------------------------------------------

Map<String, dynamic> _onAPlan() => {
      'plan': {
        'id': 'p1',
        'code': 'STARTER',
        'name': 'Starter',
        'status': 'ACTIVE',
        'billingInterval': 'MONTH',
        'trialDays': 14,
        'isDefault': true,
        'isPublic': true,
        'sortOrder': 1,
        'prices': [
          {'currency': 'GBP', 'amount': 29, 'effectiveFrom': '2026-01-01'},
        ],
        'includes': [
          {'key': 'stores.max', 'label': 'Stores and warehouses', 'limitValue': 2},
          {'key': 'feature.storefront', 'label': 'The online shop', 'enabled': true},
        ],
      },
      'usage': [
        {'key': 'stores.max', 'label': 'Stores and warehouses', 'limitValue': 2, 'used': 2, 'over': false},
        {'key': 'staff.max', 'label': 'Staff logins', 'limitValue': 10, 'used': 3, 'over': false},
        // product-svc owns the count, so tenant-svc leaves it out entirely.
        {'key': 'products.max', 'label': 'Products', 'limitValue': 500, 'over': false},
      ],
    };

Map<String, dynamic> _onNoPlan() => {
      'usage': <Map<String, dynamic>>[],
      'note': 'This business is on no plan',
    };

Map<String, dynamic> _quietUsage() => {
      'periodStart': '2026-09-22',
      'periodEnd': '2026-10-22',
      'trial': false,
      'currency': 'GBP',
      'onAPlan': true,
      'meters': <Map<String, dynamic>>[],
      'alerts': <Map<String, dynamic>>[],
      'history': <Map<String, dynamic>>[],
    };

Map<String, dynamic> _withHistory() => {
      ..._quietUsage(),
      'meters': [
        {
          'meter': 'ORDERS', 'label': 'Orders taken', 'unit': 'order', 'used': 12, 'included': 1000,
          'hard': false, 'over': 0, 'currency': 'GBP', 'unitAmount': 0.05, 'estimate': 0,
        },
      ],
      'history': [
        {
          'meter': 'ORDERS', 'periodStart': '2026-08-22', 'periodEnd': '2026-09-22', 'used': 1100,
          'included': 1000, 'overage': 100, 'currency': 'GBP', 'unitAmount': 0.05, 'amount': 5,
        },
      ],
    };

class _Server implements HttpClientAdapter {
  final Map<String, dynamic> body;
  final Map<String, dynamic> usage;
  _Server(this.body, this.usage);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async =>
      jsonResponse(jsonEncode({'data': o.path.endsWith('/usage') ? usage : body}));
}

Future<void> _pump(WidgetTester tester, Map<String, dynamic> body, [Map<String, dynamic>? usage]) async {
  tester.view.physicalSize = const Size(1200, 2000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Server(body, usage ?? _quietUsage());
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
      child: const MaterialApp(home: Scaffold(body: PlanScreen())),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  // Dates are written out in words (`22 Sept 2026`), which needs the locale's date data.
  setUpAll(initializeDateFormatting);

  testWidgets('the plan says what it allows against what is in use', (tester) async {
    await _pump(tester, _onAPlan());

    // The plan's name alone: its code is the platform's handle for it, not a word for the owner.
    expect(find.text('Starter'), findsOneWidget);
    expect(find.textContaining('STARTER'), findsNothing);
    expect(find.text('billed every month'), findsOneWidget);
    expect(find.text('Stores and warehouses: 2'), findsOneWidget);
    expect(find.text('The online shop: included'), findsOneWidget);

    expect(find.text('2 of 2'), findsOneWidget, reason: 'the store allowance is full');
    expect(find.text('3 of 10'), findsOneWidget);
    expect(find.byKey(const Key('allowance-stores.max')), findsOneWidget);
    expect(find.byKey(const Key('allowance-staff.max')), findsOneWidget);
  });

  testWidgets('a count the owning service holds is left unknown, not guessed at', (tester) async {
    await _pump(tester, _onAPlan());

    expect(find.text('up to 500'), findsOneWidget);
    expect(find.text('counted by the service that holds them'), findsOneWidget);
    // Nothing to draw when there is no count, so no bar for that row.
    expect(find.byType(LinearProgressIndicator), findsNWidgets(2));
  });

  testWidgets('a business on no plan is told nothing is limited', (tester) async {
    await _pump(tester, _onNoPlan());

    expect(find.byKey(const Key('plan-none')), findsOneWidget);
    expect(find.text('This business is on no plan'), findsOneWidget);
    expect(find.byKey(const Key('plan-name')), findsNothing);
    expect(find.text('What it allows'), findsNothing, reason: 'nothing to show');
  });

  // ── metered use (21.10) ─────────────────────────────────────────────────────

  testWidgets('this period\'s use is read against the plan, with what it will cost beyond it', (tester) async {
    await _pump(tester, _onAPlan(), {
      'periodStart': '2026-09-22',
      'periodEnd': '2026-10-22',
      'trial': false,
      'currency': 'GBP',
      'onAPlan': true,
      'meters': [
        {
          'meter': 'ORDERS', 'label': 'Orders taken', 'unit': 'order', 'used': 1234, 'included': 1000,
          'hard': false, 'over': 234, 'currency': 'GBP', 'unitAmount': 0.05, 'estimate': 11.7,
        },
        {
          'meter': 'SMS', 'label': 'Text messages', 'unit': 'text part', 'used': 50, 'included': 50,
          'hard': true, 'over': 0, 'currency': 'GBP', 'estimate': 0,
        },
      ],
      'alerts': [
        {'meter': 'ORDERS', 'threshold': 80, 'used': 800, 'included': 1000, 'raisedAt': '2026-10-01T09:00:00Z'},
        {'meter': 'ORDERS', 'threshold': 100, 'used': 1000, 'included': 1000, 'raisedAt': '2026-10-09T09:00:00Z'},
        {'meter': 'SMS', 'threshold': 100, 'used': 50, 'included': 50, 'raisedAt': '2026-10-10T09:00:00Z'},
      ],
      'history': [
        {
          'meter': 'ORDERS', 'periodStart': '2026-08-22', 'periodEnd': '2026-09-22', 'used': 1100,
          'included': 1000, 'overage': 100, 'currency': 'GBP', 'unitAmount': 0.05, 'amount': 5,
        },
        {
          'meter': 'ORDERS', 'periodStart': '2026-07-22', 'periodEnd': '2026-08-22', 'used': 1500,
          'included': 1000, 'overage': 500, 'currency': 'GBP', 'amount': 0, 'notCharged': 'TRIAL',
        },
      ],
    });

    expect(find.text('22 Sept 2026 to 21 Oct 2026'), findsOneWidget, reason: 'the last day, not the next period\'s first');
    expect(find.text('1234 of 1000'), findsOneWidget);
    expect(find.text('234 beyond the plan: £11.70 so far, on the next invoice.'), findsOneWidget);
    expect(
      find.text('All 50 used: marketing texts are refused until the next period. Messages a customer must get still go.'),
      findsOneWidget,
    );
    expect(find.text('100 · £5.00'), findsOneWidget);
    expect(find.text('500 · not charged — trial'), findsOneWidget);
    // Each earlier period by its first and last day, as dates rather than ISO strings.
    expect(find.text('22 Aug 2026 to 21 Sept 2026'), findsOneWidget);
    expect(find.textContaining('2026-08-22'), findsNothing);
  });

  testWidgets('a trial says nothing used is charged', (tester) async {
    await _pump(tester, _onAPlan(), {
      ..._quietUsage(),
      'trial': true,
      'meters': [
        {
          'meter': 'ORDERS', 'label': 'Orders taken', 'unit': 'order', 'used': 12, 'included': 10,
          'hard': false, 'over': 2, 'currency': 'GBP', 'unitAmount': 0.05, 'estimate': 0,
        },
      ],
    });
    expect(find.textContaining('a trial: nothing used is charged'), findsOneWidget);
    expect(find.text('2 beyond the plan — free while the trial runs.'), findsOneWidget);
  });

  testWidgets('texts past a hard ceiling: marketing stops, and what still goes is charged', (tester) async {
    await _pump(tester, _onAPlan(), {
      ..._quietUsage(),
      'meters': [
        {
          'meter': 'SMS', 'label': 'Text messages', 'unit': 'text part', 'used': 6, 'included': 4,
          'hard': true, 'over': 2, 'currency': 'GBP', 'unitAmount': 0.035, 'estimate': 0.07,
        },
      ],
      'alerts': [
        {'meter': 'SMS', 'threshold': 100, 'used': 4, 'included': 4, 'raisedAt': '2026-10-10T09:00:00Z'},
      ],
    });
    expect(
      find.text('All 4 used: marketing texts are refused until the next period. Messages a customer '
          'must get still go, and each beyond 4 is charged: £0.07 so far.'),
      findsOneWidget,
    );
  });

  testWidgets('a price finer than a penny keeps its places rather than rounding up', (tester) async {
    await _pump(tester, _onAPlan(), {
      ..._quietUsage(),
      'meters': [
        {
          'meter': 'SMS', 'label': 'Text messages', 'unit': 'text part', 'used': 45, 'included': 50,
          'hard': false, 'over': 0, 'currency': 'GBP', 'unitAmount': 0.035, 'estimate': 0,
        },
      ],
      'alerts': [
        {'meter': 'SMS', 'threshold': 80, 'used': 40, 'included': 50, 'raisedAt': '2026-10-10T09:00:00Z'},
      ],
    });
    expect(find.text('Nearly all used: each one beyond 50 costs £0.035.'), findsOneWidget);
  });

  group('on a phone', () {
    Future<void> pumpPhone(WidgetTester tester, {double textScale = 1}) async {
      tester.view.physicalSize = const Size(390, 844);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Server(_onAPlan(), _withHistory());
      await tester.pumpWidget(
        ProviderScope(
          overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
          child: MaterialApp(
            home: MediaQuery(
              data: MediaQueryData(size: const Size(390, 844), textScaler: TextScaler.linear(textScale)),
              child: const Scaffold(body: PlanScreen()),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('the page is inset 16, not the tablet\'s 24', (tester) async {
      await pumpPhone(tester);
      expect(tester.getTopLeft(find.text('Plan')).dx, 16);
      expect(tester.getTopLeft(find.byKey(const Key('allowance-stores.max'))).dx, 16);
    });

    testWidgets('earlier periods are rows, with every figure on the screen', (tester) async {
      await pumpPhone(tester);
      await tester.scrollUntilVisible(find.byKey(const Key('usage-history')), 200,
          scrollable: find.byType(Scrollable).first);
      await tester.pumpAndSettle();
      // No sideways table below 600px: the included allowance and what went beyond it start
      // off-screen in one.
      expect(find.byType(DataTable), findsNothing);
      for (final text in ['22 Aug 2026 to 21 Sept 2026', 'Beyond the plan: 100 · £5.00']) {
        final found = find.text(text);
        expect(found, findsOneWidget, reason: text);
        expect(tester.getRect(found).right, lessThanOrEqualTo(390 - 16), reason: text);
      }
      expect(find.textContaining('1100 used of 1000'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('at 200% text nothing overflows', (tester) async {
      await pumpPhone(tester, textScale: 2);
      await tester.scrollUntilVisible(find.byKey(const Key('usage-history')), 200,
          scrollable: find.byType(Scrollable).first);
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  });

  testWidgets('from tablet width the earlier periods are a table', (tester) async {
    await _pump(tester, _onAPlan(), _withHistory());
    expect(find.byType(DataTable), findsOneWidget);
    expect(find.text('Beyond the plan'), findsOneWidget);
    expect(tester.getTopLeft(find.text('Plan')).dx, 24);
  });
}
