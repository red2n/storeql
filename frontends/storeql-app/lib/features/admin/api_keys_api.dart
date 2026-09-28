import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// ---------------------------------------------------------------------------
// A business's own API keys (22.7): what its systems — an ERP, an accounting
// package, an integrator — present instead of a person's sign-in. An owner
// mints one in a staff tier, for some stores or all, until a day or until
// revoked; the key is shown once and never again; an owner or a manager reads
// the list, which never carries the key.
// ---------------------------------------------------------------------------

class ApiKey {
  final String id;
  final String name;
  final String prefix;
  final String role;
  final List<String> storeIds;
  final String createdBy;
  final String createdAt;
  final String? expiresAt;
  final String? lastUsedAt;
  final String? revokedAt;

  /// Whether it acts in the business's sandbox (22.8): `sqk_test_…`, and
  /// never live data.
  final bool sandbox;

  const ApiKey({
    required this.id,
    required this.name,
    required this.prefix,
    required this.role,
    required this.storeIds,
    required this.createdBy,
    required this.createdAt,
    required this.expiresAt,
    required this.lastUsedAt,
    required this.revokedAt,
    this.sandbox = false,
  });

  factory ApiKey.fromJson(Map<String, dynamic> j) => ApiKey(
        id: j['id'] as String,
        name: j['name'] as String? ?? '',
        prefix: j['prefix'] as String? ?? '',
        role: j['role'] as String? ?? '',
        storeIds: [for (final s in j['storeIds'] as List<dynamic>? ?? const []) s.toString()],
        createdBy: j['createdBy'] as String? ?? '',
        createdAt: j['createdAt'] as String? ?? '',
        expiresAt: j['expiresAt'] as String?,
        lastUsedAt: j['lastUsedAt'] as String?,
        revokedAt: j['revokedAt'] as String?,
        sandbox: j['sandbox'] as bool? ?? false,
      );

  bool get revoked => revokedAt != null;

  bool get expired {
    final at = expiresAt;
    if (at == null) return false;
    final when = DateTime.tryParse(at);
    return when != null && !when.isAfter(DateTime.now());
  }

  /// Active, revoked or expired: what the list says beside the key.
  String get status => revoked ? 'Revoked' : (expired ? 'Expired' : 'Active');
}

/// A key as minted: the one time the key itself is seen.
class MintedApiKey {
  final ApiKey key;
  final String secret;
  const MintedApiKey(this.key, this.secret);
}

class ApiKeysApi {
  final Dio _dio;
  ApiKeysApi(this._dio);

  static const _base = '/${ApiConstants.iam}/auth/admin/api-keys';

  /// Every key the business has, in the order they were made.
  Future<List<ApiKey>> list() async {
    final keys = <ApiKey>[];
    String? after;
    for (var page = 0; page < 10; page++) {
      final resp = await _dio.get(
        _base,
        queryParameters: {'limit': 100, 'after': ?after},
      );
      final data = resp.data['data'] as Map<String, dynamic>? ?? const {};
      keys.addAll([
        for (final j in data['items'] as List<dynamic>? ?? const [])
          ApiKey.fromJson(j as Map<String, dynamic>),
      ]);
      after = data['nextCursor'] as String?;
      if (after == null) break;
    }
    return keys;
  }

  Future<MintedApiKey> mint({
    required String name,
    required String role,
    List<String> storeIds = const [],
    DateTime? expiresAt,
    bool sandbox = false,
  }) async {
    final resp = await _dio.post(_base, data: {
      'name': name,
      'role': role,
      if (storeIds.isNotEmpty) 'storeIds': storeIds,
      if (expiresAt != null) 'expiresAt': expiresAt.toUtc().toIso8601String(),
      if (sandbox) 'sandbox': true,
    });
    final data = resp.data['data'] as Map<String, dynamic>;
    return MintedApiKey(ApiKey.fromJson(data), data['key'] as String);
  }

  Future<ApiKey> revoke(String id) async {
    final resp = await _dio.delete('$_base/$id');
    return ApiKey.fromJson(resp.data['data'] as Map<String, dynamic>);
  }
}

final apiKeysApiProvider =
    Provider<ApiKeysApi>((ref) => ApiKeysApi(ref.watch(apiClientProvider).dio));

final apiKeysProvider = FutureProvider.autoDispose<List<ApiKey>>(
  (ref) => ref.watch(apiKeysApiProvider).list(),
);
