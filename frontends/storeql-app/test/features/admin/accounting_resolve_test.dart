import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/accounting_section.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// An UNCERTAIN accounting push (it may have reached the package) is settled by
// a person: "It reached the package" (the package's reference, an optional
// note) or "It did not" (queued again), each confirmed; once settled the push
// says who settled it and when.
// ---------------------------------------------------------------------------

const _uncertain = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d05';
const _failed = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d06';
const _by = '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d77';

Map<String, dynamic> _sync(String id, String status, {Map<String, dynamic>? resolution, String? externalId}) => {
      'id': id,
      'journalId': '019987b0-0f1e-7c3b-8a4d-3e2f1a0b9d2${id.substring(id.length - 1)}',
      'status': status,
      'attempts': 2,
      'externalId': externalId,
      'lastError': status == 'UNCERTAIN' ? 'No answer from the package' : null,
      'createdAt': '2026-09-23T09:00:00Z',
      'entryDate': '2026-09-23',
      'description': status == 'UNCERTAIN' ? 'Rent' : 'Suspense',
      'sourceType': 'JOURNAL',
      'total': '120.00',
      'resolution': ?resolution,
    };

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];
  Map<String, dynamic>? resolution;

  List<RequestOptions> posts(String end) => requests.where((r) => r.method == 'POST' && r.path.endsWith(end)).toList();

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/tenant-svc/admin/tenant')) {
      return jsonResponse(jsonEncode({'data': {'id': 't', 'name': 'H', 'status': 'ACTIVE', 'currency': 'GBP', 'country': 'GB'}}));
    }
    if (path.endsWith('/accounting/providers')) return jsonResponse('{"data":[]}');
    if (path.endsWith('/accounting/connection')) {
      return jsonResponse(jsonEncode({
        'data': {
          'id': 'c',
          'provider': 'SIMULATED',
          'status': 'ACTIVE',
          'settings': <String, String>{},
          'syncFrom': '2026-09-01',
          'hasRefreshToken': false,
          'counts': {'pending': 0, 'delivered': 0, 'failed': 1, 'uncertain': 1, 'skipped': 0},
        }
      }));
    }
    if (path.endsWith('/resolve')) {
      resolution = {
        'outcome': (o.data as Map)['outcome'],
        'resolvedBy': _by,
        'resolvedAt': '2026-09-30T10:30:00Z',
        'note': (o.data as Map)['note'],
      };
      return jsonResponse(jsonEncode({'data': _sync(_uncertain, 'DELIVERED', resolution: resolution)}));
    }
    if (path.endsWith('/syncs/$_uncertain')) {
      return jsonResponse(jsonEncode({'data': _sync(_uncertain, resolution == null ? 'UNCERTAIN' : 'DELIVERED', resolution: resolution, externalId: resolution == null ? null : 'xero-42')}));
    }
    if (path.endsWith('/accounting/syncs')) {
      return jsonResponse(jsonEncode({
        'data': {
          'items': [_sync(_uncertain, 'UNCERTAIN'), _sync(_failed, 'FAILED')],
          'nextCursor': null,
        }
      }));
    }
    if (path.endsWith('/staff-users')) {
      return jsonResponse(jsonEncode({'data': [{'userId': _by, 'email': 'sam@hollins.example'}]}));
    }
    return jsonResponse(jsonEncode({'data': null}), 404);
  }
}

Future<_Server> _pump(WidgetTester tester) async {
  tester.view.physicalSize = const Size(1400, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      // The section asks nothing until the sign-in is known.
      authNotifierProvider.overrideWith(() => RoleAuth('OWNER')),
    ],
    child: const MaterialApp(
      home: Scaffold(body: SingleChildScrollView(child: AccountingSection(owner: true))),
    ),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  setUpAll(initializeDateFormatting);

  testWidgets('an uncertain push offers the two answers, a failed one still just tries again', (tester) async {
    await _pump(tester);
    expect(find.byKey(const Key('sync-landed-$_uncertain')), findsOneWidget);
    expect(find.byKey(const Key('sync-not-landed-$_uncertain')), findsOneWidget);
    expect(find.byKey(const Key('sync-retry-$_uncertain')), findsNothing);
    expect(find.byKey(const Key('sync-retry-$_failed')), findsOneWidget);
    expect(find.byKey(const Key('sync-landed-$_failed')), findsNothing);
  });

  testWidgets('"It reached the package" needs the package\'s reference and sends it with the note', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('sync-landed-$_uncertain')));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('sync-landed-confirm')));
    await tester.pumpAndSettle();
    expect(server.posts('/resolve'), isEmpty, reason: 'no reference, nothing sent');
    expect(find.text("Give the package's reference for it."), findsOneWidget);

    await tester.enterText(find.byKey(const Key('sync-landed-reference')), 'xero-42');
    await tester.enterText(find.byKey(const Key('sync-landed-note')), 'Found it in the ledger');
    await tester.tap(find.byKey(const Key('sync-landed-confirm')));
    await tester.pumpAndSettle();
    final sent = server.posts('/syncs/$_uncertain/resolve').single;
    expect(sent.data, {'outcome': 'LANDED', 'externalId': 'xero-42', 'note': 'Found it in the ledger'});
    expect(find.text('Recorded as delivered to the package'), findsOneWidget);
  });

  testWidgets('"It did not" asks first, then queues it again', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('sync-not-landed-$_uncertain')));
    await tester.pumpAndSettle();
    expect(find.text('It never reached the package?'), findsOneWidget);
    expect(server.posts('/resolve'), isEmpty);

    await tester.tap(find.byKey(const Key('sync-not-landed-confirm')));
    await tester.pumpAndSettle();
    expect(server.posts('/syncs/$_uncertain/resolve').single.data, {'outcome': 'NOT_LANDED'});
    expect(find.text('Queued to go on the next push'), findsOneWidget);
  });

  testWidgets('cancelling the confirmation sends nothing', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('sync-not-landed-$_uncertain')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Cancel'));
    await tester.pumpAndSettle();
    expect(server.posts('/resolve'), isEmpty);
  });

  testWidgets('a settled push says who settled it and when, in words', (tester) async {
    final server = await _pump(tester)
      ..resolution = {
        'outcome': 'LANDED',
        'resolvedBy': _by,
        'resolvedAt': '2026-09-30T10:30:00Z',
        'note': 'Found it in the ledger',
      };
    await tester.tap(find.text('Rent'));
    await tester.pumpAndSettle();
    final text = tester.widget<Text>(find.byKey(const Key('sync-resolution'))).data!;
    expect(text, contains('Settled as reached the package'));
    expect(text, contains('by sam@hollins.example'));
    expect(text, contains('30'));
    expect(text, isNot(contains(_by)));
    expect(find.text('Found it in the ledger'), findsOneWidget);
    expect(server.requests.where((r) => r.path.endsWith('/syncs/$_uncertain') && r.method == 'GET'), isNotEmpty);
  });

  testWidgets('a push nobody settled shows no settlement line', (tester) async {
    await _pump(tester);
    await tester.tap(find.text('Rent'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('sync-resolution')), findsNothing);
  });

  testWidgets('on a phone the answers are in the actions menu', (tester) async {
    tester.view.physicalSize = const Size(400, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final server = _Server();
    final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
    await tester.pumpWidget(ProviderScope(
      overrides: [
        apiClientProvider.overrideWithValue(FakeApiClient(dio)),
        authNotifierProvider.overrideWith(() => RoleAuth('OWNER')),
      ],
      child: const MaterialApp(home: Scaffold(body: SingleChildScrollView(child: AccountingSection(owner: true)))),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('sync-actions-$_uncertain')));
    await tester.pumpAndSettle();
    expect(find.text('It reached the package'), findsOneWidget);
    expect(find.text('It did not'), findsOneWidget);
    expect(find.text('Try again now'), findsNothing);
  });
}
