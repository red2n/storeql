import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// ---------------------------------------------------------------------------
// Accounting connectors (17.9): the package the business keeps its books in —
// Xero, QuickBooks Online, Sage Business Cloud Accounting, or the platform's
// stand-in — the mapping of its nominal codes onto the package's accounts, and
// every journal's push, once, with what came back on a log.
// ---------------------------------------------------------------------------
class AccountingProvider {
  final String code;
  final String name;
  final List<String> settings;
  final List<String> optional;
  final String tokens;
  const AccountingProvider({
    required this.code,
    required this.name,
    required this.settings,
    required this.optional,
    required this.tokens,
  });

  factory AccountingProvider.fromJson(Map<String, dynamic> j) => AccountingProvider(
        code: j['code'] as String,
        name: j['name'] as String? ?? j['code'] as String,
        settings: [for (final s in j['settings'] as List<dynamic>? ?? const []) s.toString()],
        optional: [for (final s in j['optional'] as List<dynamic>? ?? const []) s.toString()],
        tokens: j['tokens'] as String? ?? '',
      );

  bool get needsCredentials => code != 'SIMULATED';
}

class AccountingCounts {
  final int pending;
  final int delivered;
  final int failed;
  final int uncertain;
  final int skipped;
  const AccountingCounts({
    required this.pending,
    required this.delivered,
    required this.failed,
    required this.uncertain,
    required this.skipped,
  });

  factory AccountingCounts.fromJson(Map<String, dynamic>? j) => AccountingCounts(
        pending: (j?['pending'] as num?)?.toInt() ?? 0,
        delivered: (j?['delivered'] as num?)?.toInt() ?? 0,
        failed: (j?['failed'] as num?)?.toInt() ?? 0,
        uncertain: (j?['uncertain'] as num?)?.toInt() ?? 0,
        skipped: (j?['skipped'] as num?)?.toInt() ?? 0,
      );

  /// What needs a person: a push that failed for good, or one nobody can be sure of.
  int get needingAttention => failed + uncertain;
}

class AccountingConnection {
  final String id;
  final String provider;
  final String status;
  final Map<String, String> settings;
  final String syncFrom;
  final bool hasRefreshToken;
  final String? lastSyncAt;
  final String? lastError;
  final String? disabledReason;
  final AccountingCounts counts;
  const AccountingConnection({
    required this.id,
    required this.provider,
    required this.status,
    required this.settings,
    required this.syncFrom,
    required this.hasRefreshToken,
    required this.lastSyncAt,
    required this.lastError,
    required this.disabledReason,
    required this.counts,
  });

  factory AccountingConnection.fromJson(Map<String, dynamic> j) => AccountingConnection(
        id: j['id'] as String,
        provider: j['provider'] as String? ?? '',
        status: j['status'] as String? ?? '',
        settings: {
          for (final e in (j['settings'] as Map<String, dynamic>? ?? const {}).entries) e.key: e.value.toString(),
        },
        syncFrom: j['syncFrom'] as String? ?? '',
        hasRefreshToken: j['hasRefreshToken'] as bool? ?? false,
        lastSyncAt: j['lastSyncAt'] as String?,
        lastError: j['lastError'] as String?,
        disabledReason: j['disabledReason'] as String?,
        counts: AccountingCounts.fromJson(j['counts'] as Map<String, dynamic>?),
      );

  bool get active => status.toUpperCase() == 'ACTIVE';
}

class ExternalAccount {
  final String id;
  final String code;
  final String name;
  final String type;
  const ExternalAccount({required this.id, required this.code, required this.name, required this.type});

  factory ExternalAccount.fromJson(Map<String, dynamic> j) => ExternalAccount(
        id: j['id'] as String? ?? '',
        code: j['code'] as String? ?? '',
        name: j['name'] as String? ?? '',
        type: j['type'] as String? ?? '',
      );

  /// What a person picks from: the code when the package has one, then the name.
  String get label => code.isEmpty ? name : '$code · $name';
}

class AccountMapping {
  final String nominalCode;
  final String externalAccount;
  final String? externalName;
  const AccountMapping({required this.nominalCode, required this.externalAccount, this.externalName});

  factory AccountMapping.fromJson(Map<String, dynamic> j) => AccountMapping(
        nominalCode: j['nominalCode'] as String? ?? '',
        externalAccount: j['externalAccount'] as String? ?? '',
        externalName: j['externalName'] as String?,
      );

  Map<String, dynamic> toJson() => {
        'nominalCode': nominalCode,
        'externalAccount': externalAccount,
        if (externalName != null && externalName!.isNotEmpty) 'externalName': externalName,
      };
}

class AccountingRun {
  final int queued;
  final int delivered;
  final int failed;
  final int uncertain;
  const AccountingRun({required this.queued, required this.delivered, required this.failed, required this.uncertain});

  factory AccountingRun.fromJson(Map<String, dynamic> j) => AccountingRun(
        queued: (j['queued'] as num?)?.toInt() ?? 0,
        delivered: (j['delivered'] as num?)?.toInt() ?? 0,
        failed: (j['failed'] as num?)?.toInt() ?? 0,
        uncertain: (j['uncertain'] as num?)?.toInt() ?? 0,
      );
}

class AccountingAttempt {
  final int attempt;
  final String at;
  final int? statusCode;
  final String? error;
  final String? snippet;
  final int durationMs;
  const AccountingAttempt({
    required this.attempt,
    required this.at,
    required this.statusCode,
    required this.error,
    required this.snippet,
    required this.durationMs,
  });

  factory AccountingAttempt.fromJson(Map<String, dynamic> j) => AccountingAttempt(
        attempt: (j['attempt'] as num?)?.toInt() ?? 0,
        at: j['at'] as String? ?? '',
        statusCode: (j['statusCode'] as num?)?.toInt(),
        error: j['error'] as String?,
        snippet: j['snippet'] as String?,
        durationMs: (j['durationMs'] as num?)?.toInt() ?? 0,
      );
}

class AccountingSync {
  final String id;
  final String journalId;
  final String status;
  final int attempts;
  final String? externalId;
  final String? lastError;
  final String? nextAttemptAt;
  final String createdAt;
  final String? deliveredAt;
  final String? entryDate;
  final String? description;
  final String? sourceType;
  final String? total;
  final List<AccountingAttempt> attemptLog;
  const AccountingSync({
    required this.id,
    required this.journalId,
    required this.status,
    required this.attempts,
    required this.externalId,
    required this.lastError,
    required this.nextAttemptAt,
    required this.createdAt,
    required this.deliveredAt,
    required this.entryDate,
    required this.description,
    required this.sourceType,
    required this.total,
    required this.attemptLog,
  });

  factory AccountingSync.fromJson(Map<String, dynamic> j) => AccountingSync(
        id: j['id'] as String,
        journalId: j['journalId'] as String? ?? '',
        status: j['status'] as String? ?? '',
        attempts: (j['attempts'] as num?)?.toInt() ?? 0,
        externalId: j['externalId'] as String?,
        lastError: j['lastError'] as String?,
        nextAttemptAt: j['nextAttemptAt'] as String?,
        createdAt: j['createdAt'] as String? ?? '',
        deliveredAt: j['deliveredAt'] as String?,
        entryDate: j['entryDate'] as String?,
        description: j['description'] as String?,
        sourceType: j['sourceType'] as String?,
        total: j['total']?.toString(),
        attemptLog: [
          for (final a in j['attemptLog'] as List<dynamic>? ?? const []) AccountingAttempt.fromJson(a as Map<String, dynamic>),
        ],
      );

  bool get delivered => status == 'DELIVERED';

  /// Whether a person may try it again: anything but a journal already in the package.
  bool get retryable => !delivered;

  /// Whether it is waiting on a person rather than the clock.
  bool get needsAttention => status == 'FAILED' || status == 'UNCERTAIN';
}

class AccountingApi {
  final Dio _dio;
  AccountingApi(this._dio);

  static const _base = '/${ApiConstants.purchase}/accounting';

  Future<List<AccountingProvider>> providers() async {
    final resp = await _dio.get('$_base/providers');
    return [
      for (final j in resp.data['data'] as List<dynamic>? ?? const []) AccountingProvider.fromJson(j as Map<String, dynamic>),
    ];
  }

  /// The business's connection, or null when it has none.
  Future<AccountingConnection?> connection() async {
    try {
      final resp = await _dio.get('$_base/connection');
      return AccountingConnection.fromJson(resp.data['data'] as Map<String, dynamic>);
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      rethrow;
    }
  }

  Future<AccountingConnection> connect({
    required String provider,
    required Map<String, String> settings,
    Map<String, String>? credentials,
    required String syncFrom,
  }) async {
    final resp = await _dio.put('$_base/connection', data: {
      'provider': provider,
      'settings': settings,
      'credentials': ?credentials,
      'syncFrom': syncFrom,
    });
    return AccountingConnection.fromJson(resp.data['data'] as Map<String, dynamic>);
  }

  Future<void> disconnect() => _dio.delete('$_base/connection');

  Future<AccountingConnection> setEnabled(bool enabled) async {
    final resp = await _dio.post('$_base/connection/${enabled ? 'enable' : 'disable'}');
    return AccountingConnection.fromJson(resp.data['data'] as Map<String, dynamic>);
  }

  Future<List<ExternalAccount>> accounts() async {
    final resp = await _dio.get('$_base/connection/accounts');
    return [
      for (final j in resp.data['data'] as List<dynamic>? ?? const []) ExternalAccount.fromJson(j as Map<String, dynamic>),
    ];
  }

  Future<List<AccountMapping>> mappings() async {
    final resp = await _dio.get('$_base/connection/mappings');
    return [
      for (final j in resp.data['data'] as List<dynamic>? ?? const []) AccountMapping.fromJson(j as Map<String, dynamic>),
    ];
  }

  Future<List<AccountMapping>> replaceMappings(List<AccountMapping> mappings) async {
    final resp = await _dio.put('$_base/connection/mappings', data: {
      'mappings': [for (final m in mappings) m.toJson()],
    });
    return [
      for (final j in resp.data['data'] as List<dynamic>? ?? const []) AccountMapping.fromJson(j as Map<String, dynamic>),
    ];
  }

  Future<AccountingRun> syncNow() async {
    final resp = await _dio.post('$_base/connection/sync');
    return AccountingRun.fromJson(resp.data['data'] as Map<String, dynamic>);
  }

  Future<List<AccountingSync>> syncs({String? status, int limit = 20}) async {
    final resp = await _dio.get('$_base/syncs', queryParameters: {'limit': limit, 'status': ?status});
    final data = resp.data['data'] as Map<String, dynamic>? ?? const {};
    return [
      for (final j in data['items'] as List<dynamic>? ?? const []) AccountingSync.fromJson(j as Map<String, dynamic>),
    ];
  }

  Future<AccountingSync> sync(String id) async {
    final resp = await _dio.get('$_base/syncs/$id');
    return AccountingSync.fromJson(resp.data['data'] as Map<String, dynamic>);
  }

  Future<AccountingSync> retry(String id) async {
    final resp = await _dio.post('$_base/syncs/$id/retry');
    return AccountingSync.fromJson(resp.data['data'] as Map<String, dynamic>);
  }

  Future<AccountingSync> skip(String id, String reason) async {
    final resp = await _dio.post('$_base/syncs/$id/skip', data: {'reason': reason});
    return AccountingSync.fromJson(resp.data['data'] as Map<String, dynamic>);
  }
}

final accountingApiProvider =
    Provider<AccountingApi>((ref) => AccountingApi(ref.watch(apiClientProvider).dio));

final accountingProvidersProvider = FutureProvider.autoDispose<List<AccountingProvider>>(
  (ref) => ref.watch(accountingApiProvider).providers(),
);

/// The business's connection, or null when it has none.
final accountingConnectionProvider = FutureProvider.autoDispose<AccountingConnection?>(
  (ref) => ref.watch(accountingApiProvider).connection(),
);

/// The last pushes, newest first; empty when nothing is connected.
final accountingSyncsProvider = FutureProvider.autoDispose<List<AccountingSync>>((ref) async {
  final connection = await ref.watch(accountingConnectionProvider.future);
  if (connection == null) return const [];
  return ref.watch(accountingApiProvider).syncs();
});
