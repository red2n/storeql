import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import 'pos_providers.dart';

/// An open cashier POS session (clock-in). While one is active, the terminal is
/// bound to a store and the session is heartbeated so the server's idle-sweep
/// keeps it alive. Closing it = clock-out.
class PosSession {
  final String id;
  final String storeId;
  final String startedAt;
  final int idleTimeoutSeconds;

  const PosSession({
    required this.id,
    required this.storeId,
    required this.startedAt,
    required this.idleTimeoutSeconds,
  });

  factory PosSession.fromJson(Map<String, dynamic> j) => PosSession(
        id: j['id'] as String? ?? '',
        storeId: j['storeId'] as String? ?? '',
        startedAt: j['startedAt'] as String? ?? '',
        idleTimeoutSeconds: (j['idleTimeoutSeconds'] as num?)?.toInt() ?? 900,
      );
}

class PosSessionNotifier extends StateNotifier<PosSession?> {
  PosSessionNotifier(this._ref) : super(null) {
    restore();
  }

  final Ref _ref;
  bool _restored = false;

  /// Re-attach to this cashier's already-open session after an app restart /
  /// navigation, so a refresh doesn't strand a session server-side.
  Future<void> restore() async {
    if (_restored) return;
    _restored = true;
    final auth = _ref.read(authNotifierProvider).value;
    final userId = auth is AuthAuthenticated ? auth.userId : null;
    if (userId == null) return;
    try {
      final resp = await _ref
          .read(apiClientProvider)
          .dio
          // The caller's own open sessions: the list of everyone's is management's.
          .get('/${ApiConstants.iam}/auth/pos/sessions/mine');
      final list = (resp.data['data'] as List?) ?? [];
      for (final e in list) {
        final m = e as Map<String, dynamic>;
        if (m['userId'] == userId &&
            (m['status'] as String?)?.toUpperCase() == 'ACTIVE') {
          final s = PosSession.fromJson(m);
          state = s;
          _ref.read(posStoreProvider.notifier).state = s.storeId;
          return;
        }
      }
    } catch (_) {
      // No session to restore (or offline) — start unclocked.
    }
  }

  /// Clock in: open a session bound to [storeId].
  Future<void> clockIn(String storeId, {int idleTimeoutSeconds = 900}) async {
    final resp = await _ref.read(apiClientProvider).dio.post(
      '/${ApiConstants.iam}/auth/pos/sessions',
      data: {'storeId': storeId, 'idleTimeoutSeconds': idleTimeoutSeconds},
    );
    final s = PosSession.fromJson(resp.data['data'] as Map<String, dynamic>);
    state = s;
    _ref.read(posStoreProvider.notifier).state = s.storeId;
  }

  /// Heartbeat — keeps the session off the idle sweep. Best-effort.
  Future<void> touch() async {
    final s = state;
    if (s == null) return;
    try {
      await _ref
          .read(apiClientProvider)
          .dio
          .put('/${ApiConstants.iam}/auth/pos/sessions/${s.id}/activity');
    } catch (_) {
      // A failed heartbeat must never interrupt a sale.
    }
  }

  /// Clock out: close the session.
  Future<void> clockOut() async {
    final s = state;
    if (s == null) return;
    try {
      await _ref
          .read(apiClientProvider)
          .dio
          .delete('/${ApiConstants.iam}/auth/pos/sessions/${s.id}');
    } finally {
      state = null;
    }
  }
}

final posSessionProvider =
    StateNotifierProvider<PosSessionNotifier, PosSession?>(
        (ref) => PosSessionNotifier(ref));
