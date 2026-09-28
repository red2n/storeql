import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants.dart';
import '../../../core/network/api_client.dart';
import '../../../shared/util/short_ref.dart';

// ---------------------------------------------------------------------------
// Staff by name, not by id. tenant-svc's staff assignments and the audit
// trail carry only a login's user id; iam-svc holds the login itself and
// answers `GET /auth/admin/staff-users?ids=` with the email of each of this
// business's staff among them (another business's, a customer's or an unknown
// id is left out). Screens show that email, and a short reference only while
// a login cannot be resolved.
// ---------------------------------------------------------------------------

/// The ids to resolve as one stable key: sorted, de-duplicated, comma-joined,
/// so screens asking for the same people share one read.
String staffIdsKey(Iterable<String> ids) {
  final set = ids.where((s) => s.isNotEmpty).toSet().toList()..sort();
  return set.join(',');
}

/// User id → login email for the business's staff among [ids], asked of
/// iam-svc in pages of 100 (the most it answers at once). An id missing from
/// the map is one iam-svc would not name.
Future<Map<String, String>> fetchStaffLogins(Dio dio, List<String> ids) async {
  final out = <String, String>{};
  for (var i = 0; i < ids.length; i += 100) {
    final page = ids.sublist(i, i + 100 > ids.length ? ids.length : i + 100);
    final resp = await dio.get(
      '/${ApiConstants.iam}/auth/admin/staff-users',
      queryParameters: {'ids': page.join(',')},
    );
    for (final e in (resp.data['data'] as List?) ?? const []) {
      final m = e as Map<String, dynamic>;
      final id = m['userId'] as String?;
      final email = m['email'] as String?;
      if (id != null && email != null && email.isNotEmpty) out[id] = email;
    }
  }
  return out;
}

/// User id → login email for the business's staff among [idsCsv] (a
/// [staffIdsKey]).
final staffLoginsProvider =
    FutureProvider.autoDispose.family<Map<String, String>, String>((ref, idsCsv) async {
  if (idsCsv.isEmpty) return const {};
  return fetchStaffLogins(ref.read(apiClientProvider).dio, idsCsv.split(','));
});

/// Names that stay known while a screen lives, however often the ids it
/// shows change. A screen whose list reloads (a filter, *Load older*) would
/// otherwise start a new [staffLoginsProvider] key each time and show ids for
/// a round trip; this keeps every name already read, and asks iam-svc only
/// for the ids it has not asked about yet.
///
/// The screen watches the map and calls [StaffNameCache.resolve] with the ids
/// it is about to show. Disposed with the last screen watching it.
final staffNameCacheProvider =
    NotifierProvider.autoDispose<StaffNameCache, Map<String, String>>(
        StaffNameCache.new);

class StaffNameCache extends Notifier<Map<String, String>> {
  final _asked = <String>{};

  @override
  Map<String, String> build() {
    _asked.clear();
    return const {};
  }

  /// Asks for the [ids] not yet asked about; their names join the map when
  /// iam-svc answers. A failed read is asked again next time.
  Future<void> resolve(Iterable<String> ids) async {
    if (!ref.mounted) return;
    final missing = ids
        .where((id) => id.isNotEmpty && !_asked.contains(id))
        .toSet()
        .toList()
      ..sort();
    if (missing.isEmpty) return;
    _asked.addAll(missing);
    try {
      final found =
          await fetchStaffLogins(ref.read(apiClientProvider).dio, missing);
      if (!ref.mounted || found.isEmpty) return;
      state = {...state, ...found};
    } catch (_) {
      if (ref.mounted) _asked.removeAll(missing);
    }
  }
}

/// How a member of staff is named on screen: their login email when it is
/// known, else the last eight characters of their id.
String staffDisplayName(String userId, Map<String, String> logins) =>
    logins[userId] ?? shortRef(userId);
