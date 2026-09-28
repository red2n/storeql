import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/providers/customer_search.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The customer search (customer-svc `GET /customers?q=`): pages follow the
// cursor with the same term, an answer to a query since replaced never
// overwrites the newer one, and only a 400 — a server that will not search —
// turns the screen to its local filter; any other failure is said as one.
// ---------------------------------------------------------------------------

Map<String, dynamic> _c(String id, String first) =>
    {'id': id, 'email': '$first@example.com', 'firstName': first, 'lastName': 'Lee', 'status': 'ACTIVE'};

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  /// Answers held back until the test lets them go, by search term.
  final Map<String, Completer<ResponseBody>> held = {};
  int status = 200;

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) {
    requests.add(o);
    final q = o.queryParameters['q'] as String;
    if (held.containsKey(q)) return held[q]!.future;
    if (status != 200) {
      return Future.value(jsonResponse(jsonEncode({'code': 'X', 'status': status}), status));
    }
    final after = o.queryParameters['after'];
    return Future.value(jsonResponse(jsonEncode({
      'data': after == null
          ? {'items': [_c('c-1', q)], 'nextCursor': 'next-2'}
          : {'items': [_c('c-2', '$q-2')], 'nextCursor': null},
    })));
  }
}

/// Lets the fake server's answers arrive: Dio needs the clock to move.
Future<void> _drain(WidgetTester tester) async {
  for (var i = 0; i < 5; i++) {
    await tester.pump(const Duration(milliseconds: 10));
  }
}

(ProviderContainer, _Server) _container() {
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  final container = ProviderContainer(
    overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
  );
  // Kept alive as the screen keeps it, by listening.
  container.listen(customerSearchProvider, (_, _) {});
  return (container, server);
}

void main() {
  testWidgets('pages of matches follow the cursor, with the same term', (tester) async {
    final (container, server) = _container();
    addTearDown(container.dispose);
    final search = container.read(customerSearchProvider.notifier);

    search.search('  lee ');
    expect(container.read(customerSearchProvider).isLoading, isTrue);
    await tester.pump(CustomerSearchNotifier.debounce);
    await _drain(tester);
    var state = container.read(customerSearchProvider);
    expect(state.query, 'lee', reason: 'trimmed');
    expect(state.customers.map((c) => c.id), ['c-1']);
    expect(state.hasMore, isTrue);
    expect(server.requests.single.queryParameters, {'q': 'lee', 'limit': 20});

    final more = search.loadMore();
    await _drain(tester);
    await more;
    state = container.read(customerSearchProvider);
    expect(state.customers.map((c) => c.id), ['c-1', 'c-2']);
    expect(state.hasMore, isFalse);
    expect(server.requests.last.queryParameters, {'q': 'lee', 'limit': 20, 'after': 'next-2'});

    search.search('lee ');
    await tester.pump(CustomerSearchNotifier.debounce);
    expect(server.requests, hasLength(2), reason: 'the same term again asks nothing');
  });

  testWidgets('an answer to a query since replaced does not overwrite the newer one', (tester) async {
    final (container, server) = _container();
    addTearDown(container.dispose);
    final search = container.read(customerSearchProvider.notifier);
    final slow = server.held['an'] = Completer<ResponseBody>();

    search.search('an');
    await tester.pump(CustomerSearchNotifier.debounce);
    search.search('ann');
    await tester.pump(CustomerSearchNotifier.debounce);
    await _drain(tester);
    expect(container.read(customerSearchProvider).customers.single.firstName, 'ann');

    slow.complete(jsonResponse(jsonEncode({
      'data': {'items': [_c('c-9', 'an')], 'nextCursor': null},
    })));
    await _drain(tester);
    final state = container.read(customerSearchProvider);
    expect(state.query, 'ann');
    expect(state.customers.single.firstName, 'ann', reason: 'the stale answer is dropped');
  });

  testWidgets('a 400 turns to the local filter; any other failure is an error to show', (tester) async {
    final (container, server) = _container();
    addTearDown(container.dispose);
    final search = container.read(customerSearchProvider.notifier);

    server.status = 400;
    search.search('ann');
    await tester.pump(CustomerSearchNotifier.debounce);
    await _drain(tester);
    var state = container.read(customerSearchProvider);
    expect(state.localOnly, isTrue);
    expect(state.error, isNull);

    // Refused once, the rest of this search filters the loaded customers at
    // once: no request, no spinner, between keystrokes.
    search.search('bob');
    state = container.read(customerSearchProvider);
    expect(state.localOnly, isTrue, reason: 'not asked again until the box is cleared');
    expect(state.isLoading, isFalse);

    // Cleared, the next search asks the server again; a failure that is not a
    // refusal is an error to show.
    search.search('');
    server.status = 503;
    search.search('cat');
    await tester.pump(CustomerSearchNotifier.debounce);
    await _drain(tester);
    state = container.read(customerSearchProvider);
    expect(state.localOnly, isFalse, reason: 'the server is asked again for a new search');
    expect(state.error, isNotNull);

    search.search('');
    state = container.read(customerSearchProvider);
    expect(state.active, isFalse, reason: 'cleared at once, no wait');
    expect(state.error, isNull);
  });
}
