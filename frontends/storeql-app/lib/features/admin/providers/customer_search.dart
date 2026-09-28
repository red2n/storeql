import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants.dart';
import '../../../core/network/api_client.dart';
import '../customer_providers.dart';

/// What the Customers screen's search box has found.
///
/// customer-svc searches every customer the business has, by name, email or
/// phone, ignoring case (`GET /customers?q=&after=&limit=`), a page at a time
/// on the same cursor as the plain list. So a search finds the customer on
/// page forty, not only among the ones scrolled past so far.
class CustomerSearch {
  /// The text searched for, trimmed; empty when nothing is being searched.
  final String query;

  /// The matches so far, in the server's order. While [isLoading], the last
  /// query's matches stay here, so the list does not blank on every keystroke.
  final List<Customer> customers;

  /// The cursor for the next page of matches; null when there are no more.
  final String? nextCursor;

  /// Waiting for the first page of [query]: typing has not paused yet, or the
  /// server has not answered.
  final bool isLoading;
  final bool isLoadingMore;

  /// Why the search failed, for the screen to say in words.
  final Object? error;

  /// The server would not search (it answered 400: a customer-svc from before
  /// the search, or a term it does not accept). The screen then filters the
  /// customers it has loaded, and says that is all it searched.
  final bool localOnly;

  const CustomerSearch({
    this.query = '',
    this.customers = const [],
    this.nextCursor,
    this.isLoading = false,
    this.isLoadingMore = false,
    this.error,
    this.localOnly = false,
  });

  bool get active => query.isNotEmpty;
  bool get hasMore => nextCursor != null;
}

/// Searches the customers on the server, [debounce] after typing pauses, one
/// cursor page at a time. An answer to a query that has since been replaced is
/// dropped, so a slow reply never overwrites a newer one.
class CustomerSearchNotifier extends Notifier<CustomerSearch> {
  /// How long typing must pause before the server is asked.
  static const debounce = Duration(milliseconds: 300);
  static const _pageSize = 20;

  /// The longest term customer-svc searches for; a longer one it refuses
  /// (400), so it is not sent.
  static const maxServerTerm = 100;

  Timer? _timer;

  /// The server refused a term it should have searched (a customer-svc from
  /// before the search): every term after it, until the box is cleared, is
  /// looked for among the loaded customers at once, with no request and no
  /// spinner between keystrokes.
  bool _serverWillNotSearch = false;

  /// Bumped for every new query; an answer carrying an older one is stale.
  int _generation = 0;

  @override
  CustomerSearch build() {
    ref.onDispose(() => _timer?.cancel());
    return const CustomerSearch();
  }

  /// Searches for [text] once typing pauses. Blank text ends the search at
  /// once; the same text again (a trailing space) changes nothing.
  void search(String text) {
    final q = text.trim();
    if (q == state.query) return;
    _timer?.cancel();
    final generation = ++_generation;
    if (q.isEmpty) {
      _serverWillNotSearch = false;
      state = const CustomerSearch();
      return;
    }
    if (_serverWillNotSearch || q.length > maxServerTerm) {
      state = CustomerSearch(query: q, localOnly: true);
      return;
    }
    state = CustomerSearch(
      query: q,
      customers: state.localOnly ? const [] : state.customers,
      isLoading: true,
    );
    _timer = Timer(debounce, () => _first(q, generation));
  }

  /// Asks again for the current query's first page, now (a retry, a pull, a
  /// customer added or changed). Nothing to do with no search, or one the
  /// server would not run: the loaded list's own refresh covers that.
  Future<void> refresh() async {
    final q = state.query;
    if (q.isEmpty || state.localOnly) return;
    _timer?.cancel();
    final generation = ++_generation;
    state = CustomerSearch(query: q, customers: state.customers, isLoading: true);
    await _first(q, generation);
  }

  /// The next page of matches, when there is one and nothing is loading.
  Future<void> loadMore() async {
    final s = state;
    if (!s.active || s.localOnly || s.isLoading || s.isLoadingMore || !s.hasMore) {
      return;
    }
    final generation = _generation;
    state = CustomerSearch(
      query: s.query,
      customers: s.customers,
      nextCursor: s.nextCursor,
      isLoadingMore: true,
    );
    try {
      final (more, next) = await _fetch(s.query, s.nextCursor);
      if (!ref.mounted || generation != _generation) return;
      state = CustomerSearch(
        query: s.query,
        customers: [...s.customers, ...more],
        nextCursor: next,
      );
    } catch (e) {
      if (!ref.mounted || generation != _generation) return;
      // The matches so far stay; the next page can be asked for again.
      state = CustomerSearch(
        query: s.query,
        customers: s.customers,
        nextCursor: s.nextCursor,
        error: e,
      );
    }
  }

  Future<void> _first(String q, int generation) async {
    try {
      final (customers, next) = await _fetch(q, null);
      if (!ref.mounted || generation != _generation) return;
      state = CustomerSearch(query: q, customers: customers, nextCursor: next);
    } catch (e) {
      if (!ref.mounted || generation != _generation) return;
      if (_refused(e)) _serverWillNotSearch = true;
      state = _refused(e)
          ? CustomerSearch(query: q, localOnly: true)
          : CustomerSearch(query: q, error: e);
    }
  }

  static bool _refused(Object e) =>
      e is DioException && e.response?.statusCode == 400;

  Future<(List<Customer>, String?)> _fetch(String q, String? after) async {
    final resp = await ref.read(apiClientProvider).dio.get(
      '/${ApiConstants.customer}/customers',
      queryParameters: {
        'q': q,
        'limit': _pageSize,
        'after': ?after,
      },
    );
    final data = resp.data['data'] as Map<String, dynamic>?;
    final items = (data?['items'] as List?) ?? const [];
    return (
      [for (final e in items) Customer.fromJson(e as Map<String, dynamic>)],
      data?['nextCursor'] as String?,
    );
  }
}

final customerSearchProvider =
    NotifierProvider.autoDispose<CustomerSearchNotifier, CustomerSearch>(
  CustomerSearchNotifier.new,
);
