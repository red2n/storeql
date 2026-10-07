import 'package:flutter_riverpod/legacy.dart';

/// What a cursor-paged, newest-first log has loaded so far: the rows, and the
/// cursor of the next older page (null at the end).
class PagedLog<T> {
  final List<T> items;
  final String? nextCursor;
  final bool loading;
  final bool loadingMore;
  final Object? error;

  const PagedLog({
    this.items = const [],
    this.nextCursor,
    this.loading = false,
    this.loadingMore = false,
    this.error,
  });

  bool get hasMore => nextCursor != null;
}

/// Loads a log a page at a time. The whole filter is the provider family's key,
/// so changing any of it starts a fresh first page; *Load older* appends.
class PagedLogNotifier<T> extends StateNotifier<PagedLog<T>> {
  PagedLogNotifier(this._fetch) : super(const PagedLog(loading: true)) {
    refresh();
  }

  final Future<(List<T>, String?)> Function(String? after) _fetch;

  Future<void> refresh() async {
    state = const PagedLog(loading: true);
    try {
      final (rows, next) = await _fetch(null);
      if (!mounted) return;
      state = PagedLog(items: rows, nextCursor: next);
    } catch (e) {
      if (!mounted) return;
      state = PagedLog(error: e);
    }
  }

  Future<void> loadOlder() async {
    if (state.loading || state.loadingMore || !state.hasMore) return;
    state = PagedLog(items: state.items, nextCursor: state.nextCursor, loadingMore: true);
    try {
      final (rows, next) = await _fetch(state.nextCursor);
      if (!mounted) return;
      state = PagedLog(items: [...state.items, ...rows], nextCursor: next);
    } catch (e) {
      if (!mounted) return;
      state = PagedLog(items: state.items, nextCursor: state.nextCursor, error: e);
    }
  }
}
