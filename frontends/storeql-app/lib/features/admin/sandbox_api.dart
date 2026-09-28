import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// ---------------------------------------------------------------------------
// The business's sandbox (22.8): a second tenant of its own where an integrator
// tries products, stock, orders, keys and webhooks against nothing real — no
// message leaves it, no money moves, nothing is billed. The owner makes one,
// opens it (the token is traded for one that names the sandbox), and removes
// it; a manager reads whether there is one.
// ---------------------------------------------------------------------------
class Sandbox {
  final String id;
  final String name;
  final String status;
  final String? sandboxOf;
  final String createdAt;
  final String? deactivatedReason;
  const Sandbox({
    required this.id,
    required this.name,
    required this.status,
    required this.sandboxOf,
    required this.createdAt,
    required this.deactivatedReason,
  });

  factory Sandbox.fromJson(Map<String, dynamic> j) => Sandbox(
        id: j['id'] as String,
        name: j['name'] as String? ?? '',
        status: j['status'] as String? ?? '',
        sandboxOf: j['sandboxOf'] as String?,
        createdAt: j['createdAt'] as String? ?? '',
        deactivatedReason: j['deactivatedReason'] as String?,
      );

  bool get active => status.toUpperCase() == 'ACTIVE';
}

class SandboxApi {
  final Dio _dio;
  SandboxApi(this._dio);

  static const _base = '/${ApiConstants.tenant}/admin/tenant/sandbox';

  /// The business's sandbox, or null when it has none.
  Future<Sandbox?> get() async {
    try {
      final resp = await _dio.get(_base);
      return Sandbox.fromJson(resp.data['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      rethrow;
    }
  }

  Future<Sandbox> create() async {
    final resp = await _dio.post(_base);
    return Sandbox.fromJson(resp.data['data'] as Map<String, dynamic>);
  }

  Future<Sandbox> remove() async {
    final resp = await _dio.delete(_base);
    return Sandbox.fromJson(resp.data['data'] as Map<String, dynamic>);
  }
}

final sandboxApiProvider =
    Provider<SandboxApi>((ref) => SandboxApi(ref.watch(apiClientProvider).dio));

/// The business's sandbox, or null when it has none.
final sandboxProvider = FutureProvider.autoDispose<Sandbox?>(
  (ref) => ref.watch(sandboxApiProvider).get(),
);
