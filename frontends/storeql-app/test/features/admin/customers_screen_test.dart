import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/customers_screen.dart';
import 'package:storeql_app/shared/widgets/loading_view.dart';

import '../../support/fake_api.dart';

import 'package:intl/intl.dart';
// ---------------------------------------------------------------------------
// Customers (back office): the search box asks customer-svc — every customer
// the business has, not only the page loaded — once typing pauses; a server
// that will not search falls back to the loaded customers and says so; and a
// customer's card reads as words, money and dates, never as codes.
// ---------------------------------------------------------------------------

Map<String, dynamic> _customer(String id, String first, String last, String email,
        {String? phone, String? dob, String? gender}) =>
    {
      'id': id,
      'email': email,
      'phone': phone,
      'firstName': first,
      'lastName': last,
      'status': 'ACTIVE',
      'dob': dob,
      'gender': gender,
    };

final _ann = _customer('c-1', 'Ann', 'Lee', 'ann@example.com',
    phone: '07700 900123', dob: '1990-03-14', gender: 'FEMALE');
final _bob = _customer('c-2', 'Bob', 'Stone', 'bob@example.com');

/// On page forty: never among the customers the list has loaded.
final _zara = _customer('c-99', 'Zara', 'Quinn', 'zara@example.com');

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  /// What a search answers with: 200, or 400 for a customer-svc that will not search.
  int searchStatus = 200;

  /// How many pages the plain list has: two customers a page, Ann and Bob on
  /// the first. Forty by default, so there is always more than a window holds.
  int pages = 40;

  List<RequestOptions> get searches =>
      requests.where((r) => r.queryParameters.containsKey('q')).toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path == '/customer-svc/customers') {
      final q = o.queryParameters['q'] as String?;
      if (q == null) {
        // The plain list, a page at a time: Ann and Bob first, then guests.
        final after = o.queryParameters['after'] as String?;
        final page = after == null ? 1 : int.parse(after.substring('cursor-'.length));
        final items = page == 1
            ? [_ann, _bob]
            : [
                for (final x in ['a', 'b'])
                  _customer('c-$page$x', 'Guest', '$page$x', 'guest$page$x@example.com'),
              ];
        return jsonResponse(jsonEncode({
          'data': {'items': items, 'nextCursor': page < pages ? 'cursor-${page + 1}' : null},
        }));
      }
      if (searchStatus != 200) {
        return jsonResponse(
            jsonEncode({'code': 'VALIDATION_FAILED', 'detail': 'unknown parameter q', 'status': searchStatus}),
            searchStatus);
      }
      final needle = q.toLowerCase();
      // A term that looks like a phone number matches on its digits alone,
      // however either side spaces it — the way customer-svc is to answer it.
      final looksLikePhone = RegExp(r'^[\d\s()+.-]+$').hasMatch(q.trim());
      String digits(String? v) => (v ?? '').replaceAll(RegExp(r'\D'), '');
      final matches = [_ann, _bob, _zara].where((c) =>
          '${c['firstName']} ${c['lastName']}'.toLowerCase().contains(needle) ||
          (c['email'] as String).contains(needle) ||
          (looksLikePhone &&
              digits(q).isNotEmpty &&
              digits(c['phone'] as String?).contains(digits(q))));
      return jsonResponse(jsonEncode({
        'data': {'items': matches.toList(), 'nextCursor': null},
      }));
    }
    if (path == '/customer-svc/customers/c-1') return jsonResponse(jsonEncode({'data': _ann}));
    if (path.endsWith('/loyalty')) {
      return jsonResponse(jsonEncode({'data': {'pointsBalance': 30, 'lifetimePoints': 50}}));
    }
    if (path.endsWith('/loyalty/ledger')) {
      return jsonResponse(jsonEncode({
        'data': [
          {'type': 'EARN', 'points': 50, 'balanceAfter': 50, 'reason': 'Till sale', 'createdAt': '2026-09-20T10:00:00Z'},
          {'type': 'REDEEM', 'points': -15, 'balanceAfter': 35, 'reason': null, 'createdAt': '2026-09-21T10:00:00Z'},
          {'type': 'EXPIRE', 'points': -5, 'balanceAfter': 30, 'reason': null, 'createdAt': '2026-09-22T10:00:00Z'},
        ],
      }));
    }
    if (path.endsWith('/store-credit')) {
      return jsonResponse(jsonEncode({'data': {'balance': 12.5, 'currency': 'GBP'}}));
    }
    if (path.endsWith('/addresses')) {
      return jsonResponse(jsonEncode({
        'data': [
          {'id': 'a-1', 'type': 'HOME', 'line1': '1 High St', 'city': 'Leeds', 'country': 'GB', 'isDefault': true},
          {'id': 'a-2', 'type': 'WORK', 'line1': '2 Mill Rd', 'city': 'Leeds', 'country': 'GB', 'isDefault': false},
        ],
      }));
    }
    // The VAT registration and guardian consent sections are not under test.
    return jsonResponse(jsonEncode({'code': 'NOT_FOUND', 'status': 404}), 404);
  }
}

Future<_Server> _pump(WidgetTester tester, {_Server? server}) async {
  tester.view.physicalSize = const Size(1200, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  server ??= _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        authNotifierProvider.overrideWith(() => RoleAuth('OWNER')),
      ],
      child: const MaterialApp(home: Scaffold(body: CustomersScreen())),
    ),
  );
  await _settle(tester);
  return server;
}

final _search = find.byKey(const Key('customers-search'));

/// Lets requests answer and animations finish. Not pumpAndSettle: the list's
/// footer spinner turns for as long as another page waits to be scrolled to.
Future<void> _settle(WidgetTester tester) async {
  for (var i = 0; i < 12; i++) {
    await tester.pump(const Duration(milliseconds: 50));
  }
}

void main() {
  // This file's UI dates (e.g. day-before-month, "Sept") are about
  // AppFormat writing en_GB correctly, not about which locale the app
  // defaults to (core/l10n/app_locales_test.dart owns that) — pinned
  // explicitly so it stays true whatever the app's own fallback is.
  setUp(() => Intl.defaultLocale = 'en_GB');
  tearDown(() => Intl.defaultLocale = null);
  // The customer's card writes its dates with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);

  testWidgets('a window taller than the first page loads the next without a scroll', (tester) async {
    final server = await _pump(tester, server: _Server()..pages = 2);
    await tester.pumpAndSettle();
    expect(server.requests.where((r) => r.queryParameters['after'] == 'cursor-2'), hasLength(1));
    expect(find.text('Guest 2a'), findsOneWidget);
    expect(find.text('Guest 2b'), findsOneWidget);
    // Nothing more to wait for, so no spinner turning for ever.
    expect(find.byType(CircularProgressIndicator), findsNothing);
  });

  testWidgets('a search asks the server once typing pauses, and finds a customer not loaded yet', (tester) async {
    final server = await _pump(tester);
    expect(find.text('Ann Lee'), findsOneWidget);
    expect(find.text('Zara Quinn'), findsNothing, reason: 'not on the page loaded');

    // Typed a letter at a time: nothing is asked until the typing pauses.
    for (final typed in ['z', 'za', 'zar', 'zara']) {
      await tester.enterText(_search, typed);
      await tester.pump(const Duration(milliseconds: 100));
    }
    expect(server.searches, isEmpty, reason: 'debounced, not a request a keystroke');
    await tester.pump(const Duration(milliseconds: 300));
    await _settle(tester);

    expect(server.searches, hasLength(1));
    expect(server.searches.single.queryParameters['q'], 'zara');
    expect(server.searches.single.queryParameters['limit'], isNotNull);
    expect(find.text('Zara Quinn'), findsOneWidget, reason: 'found on the server, not among the loaded');
    expect(find.text('Ann Lee'), findsNothing);
    expect(find.textContaining('loaded so far'), findsNothing, reason: 'every customer was searched');

    // Cleared, the whole list is back, and nothing more is asked of the search.
    await tester.tap(find.byTooltip('Clear search'));
    await _settle(tester);
    expect(find.text('Ann Lee'), findsOneWidget);
    expect(find.text('Zara Quinn'), findsNothing);
    expect(server.searches, hasLength(1));
  });

  testWidgets('a search the server has no match for says so', (tester) async {
    await _pump(tester);
    await tester.enterText(_search, 'nobody');
    await tester.pump(const Duration(milliseconds: 300));
    await _settle(tester);
    expect(find.text('No customers match “nobody”'), findsOneWidget);
    expect(find.text('Ann Lee'), findsNothing);
  });

  testWidgets('a server that will not search (400) falls back to the loaded customers, and says so', (tester) async {
    final server = await _pump(tester);
    server.searchStatus = 400;
    await tester.enterText(_search, 'ann');
    await tester.pump(const Duration(milliseconds: 300));
    await _settle(tester);

    expect(server.searches, hasLength(1), reason: 'the server was asked first');
    expect(find.text('Ann Lee'), findsOneWidget);
    expect(find.text('Bob Stone'), findsNothing);
    expect(find.text('Only the customers loaded so far are searched.'), findsOneWidget);
  });

  testWidgets(
      'once the server has refused, each new term filters the loaded customers '
      'at once, with no spinner and no request between keystrokes', (tester) async {
    final server = await _pump(tester);
    server.searchStatus = 400;
    await tester.enterText(_search, 'ann');
    await tester.pump(const Duration(milliseconds: 300));
    await _settle(tester);
    expect(find.text('Ann Lee'), findsOneWidget);
    expect(server.searches, hasLength(1));

    await tester.enterText(_search, 'bob');
    await tester.pump();
    expect(find.byType(LoadingView), findsNothing);
    expect(find.text('Bob Stone'), findsOneWidget);
    expect(find.text('Ann Lee'), findsNothing);
    await tester.pump(const Duration(milliseconds: 300));
    await _settle(tester);
    expect(find.byType(LoadingView), findsNothing);
    expect(find.text('Bob Stone'), findsOneWidget);
    expect(server.searches, hasLength(1), reason: 'not asked again');

    // Cleared, the next search asks the server again.
    await tester.tap(find.byTooltip('Clear search'));
    await _settle(tester);
    server.searchStatus = 200;
    await tester.enterText(_search, 'zara');
    await tester.pump(const Duration(milliseconds: 300));
    await _settle(tester);
    expect(server.searches, hasLength(2));
    expect(find.text('Zara Quinn'), findsOneWidget);
  });

  testWidgets('a term longer than the server searches is looked for among the loaded ones, unasked',
      (tester) async {
    final server = await _pump(tester);
    await tester.enterText(_search, 'a' * 101);
    await tester.pump(const Duration(milliseconds: 300));
    await _settle(tester);
    expect(server.searches, isEmpty);
    expect(find.byType(LoadingView), findsNothing);
  });

  testWidgets('a phone number finds its customer however it is spaced', (tester) async {
    final server = await _pump(tester);
    for (final typed in ['07700900123', '07700 900 123']) {
      await tester.enterText(_search, typed);
      await tester.pump(const Duration(milliseconds: 300));
      await _settle(tester);
      expect(server.searches.last.queryParameters['q'], typed);
      expect(find.text('Ann Lee'), findsOneWidget, reason: typed);
      await tester.tap(find.byTooltip('Clear search'));
      await _settle(tester);
    }
  });

  testWidgets('a customer\'s card reads as words, money and dates, not codes', (tester) async {
    await _pump(tester);
    await tester.tap(find.text('Ann Lee'));
    await _settle(tester);

    // Store credit in its currency's form.
    expect(find.text('£12.50'), findsOneWidget);
    expect(find.textContaining('GBP'), findsNothing);
    // The birth date as a person writes it, and the gender as a word.
    expect(find.text('ann@example.com · 07700 900123 · Female · Born 14 Mar 1990'), findsOneWidget);
    expect(find.textContaining('1990-03-14'), findsNothing);

    // Editing, the birthday reads as a date too; the ISO day is what is sent.
    await tester.tap(find.byTooltip('Edit details'));
    await tester.pumpAndSettle();
    final dob = tester.widget<TextFormField>(find.widgetWithText(TextFormField, 'Date of birth'));
    expect(dob.controller!.text, '14 Mar 1990');
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(find.textContaining('DOB'), findsNothing);
    // Addresses by their kind in words.
    expect(find.text('Home'), findsOneWidget);
    expect(find.text('Work'), findsOneWidget);
    expect(find.text('HOME'), findsNothing);
    expect(find.text('WORK'), findsNothing);
    // Loyalty movements by what happened, dated.
    expect(find.text('Points earned'), findsOneWidget);
    expect(find.text('Points redeemed'), findsOneWidget);
    expect(find.text('Points expired'), findsOneWidget);
    expect(find.text('Till sale · 20 Sept 2026'), findsOneWidget);
    for (final code in ['EARN', 'REDEEM', 'EXPIRE']) {
      expect(find.text(code), findsNothing);
    }
  });
}
