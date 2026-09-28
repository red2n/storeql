import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';

// ---------------------------------------------------------------------------
// Webhooks to a business's own systems (22.6): the events it asked to be told
// of, delivered to the addresses it gave, signed with a secret it is shown
// once. An owner registers, changes, rotates and removes endpoints; an owner
// or a manager reads them, reads the delivery log, pings and resends.
// ---------------------------------------------------------------------------

class WebhookEventType {
  final String type;
  final String description;
  const WebhookEventType(this.type, this.description);

  factory WebhookEventType.fromJson(Map<String, dynamic> j) =>
      WebhookEventType(j['type'] as String? ?? '', j['description'] as String? ?? '');
}

class WebhookEndpoint {
  final String id;
  final String url;
  final String description;
  final List<String> events;
  final bool enabled;
  final String? disabledReason;
  final int consecutiveFailures;
  final String? lastDeliveredAt;

  const WebhookEndpoint({
    required this.id,
    required this.url,
    required this.description,
    required this.events,
    required this.enabled,
    required this.disabledReason,
    required this.consecutiveFailures,
    required this.lastDeliveredAt,
  });

  factory WebhookEndpoint.fromJson(Map<String, dynamic> j) => WebhookEndpoint(
        id: j['id'] as String,
        url: j['url'] as String? ?? '',
        description: j['description'] as String? ?? '',
        events: [for (final e in j['events'] as List<dynamic>? ?? const []) e.toString()],
        enabled: j['enabled'] == true,
        disabledReason: j['disabledReason'] as String?,
        consecutiveFailures: (j['consecutiveFailures'] as num?)?.toInt() ?? 0,
        lastDeliveredAt: j['lastDeliveredAt'] as String?,
      );
}

/// An endpoint as registered: the one time its secret is seen.
class RegisteredWebhook {
  final WebhookEndpoint endpoint;
  final String secret;
  const RegisteredWebhook(this.endpoint, this.secret);
}

class WebhookDelivery {
  final String id;
  final String endpointId;
  final String eventType;
  final String status;
  final int attempts;
  final String? nextAttemptAt;
  final String? deliveredAt;
  final int? lastStatus;
  final String? lastError;
  final String createdAt;
  final List<WebhookAttempt> attemptLog;

  const WebhookDelivery({
    required this.id,
    required this.endpointId,
    required this.eventType,
    required this.status,
    required this.attempts,
    required this.nextAttemptAt,
    required this.deliveredAt,
    required this.lastStatus,
    required this.lastError,
    required this.createdAt,
    required this.attemptLog,
  });

  factory WebhookDelivery.fromJson(Map<String, dynamic> j) => WebhookDelivery(
        id: j['id'] as String,
        endpointId: j['endpointId'] as String? ?? '',
        eventType: j['eventType'] as String? ?? '',
        status: j['status'] as String? ?? '',
        attempts: (j['attempts'] as num?)?.toInt() ?? 0,
        nextAttemptAt: j['nextAttemptAt'] as String?,
        deliveredAt: j['deliveredAt'] as String?,
        lastStatus: (j['lastStatus'] as num?)?.toInt(),
        lastError: j['lastError'] as String?,
        createdAt: j['createdAt'] as String? ?? '',
        attemptLog: [
          for (final a in j['attemptLog'] as List<dynamic>? ?? const [])
            WebhookAttempt.fromJson(a as Map<String, dynamic>),
        ],
      );
}

class WebhookAttempt {
  final int attempt;
  final String attemptedAt;
  final int? statusCode;
  final String? error;
  final int durationMs;
  const WebhookAttempt({
    required this.attempt,
    required this.attemptedAt,
    required this.statusCode,
    required this.error,
    required this.durationMs,
  });

  factory WebhookAttempt.fromJson(Map<String, dynamic> j) => WebhookAttempt(
        attempt: (j['attempt'] as num?)?.toInt() ?? 0,
        attemptedAt: j['attemptedAt'] as String? ?? '',
        statusCode: (j['statusCode'] as num?)?.toInt(),
        error: j['error'] as String?,
        durationMs: (j['durationMs'] as num?)?.toInt() ?? 0,
      );
}

class WebhooksApi {
  final Dio _dio;
  WebhooksApi(this._dio);

  static const _base = '/${ApiConstants.notification}/admin/webhooks';

  Future<List<WebhookEventType>> events() async {
    final resp = await _dio.get('$_base/events');
    return [
      for (final j in resp.data['data'] as List<dynamic>? ?? const [])
        WebhookEventType.fromJson(j as Map<String, dynamic>),
    ];
  }

  Future<List<WebhookEndpoint>> endpoints() async {
    final resp = await _dio.get('$_base/endpoints');
    return [
      for (final j in resp.data['data'] as List<dynamic>? ?? const [])
        WebhookEndpoint.fromJson(j as Map<String, dynamic>),
    ];
  }

  Future<RegisteredWebhook> register({
    required String url,
    required String description,
    required List<String> events,
  }) async {
    final resp = await _dio.post('$_base/endpoints', data: {
      'url': url,
      'description': description,
      'events': events,
    });
    final data = resp.data['data'] as Map<String, dynamic>;
    return RegisteredWebhook(WebhookEndpoint.fromJson(data), data['secret'] as String);
  }

  Future<WebhookEndpoint> setEnabled(String id, bool enabled) async {
    final resp = await _dio.put('$_base/endpoints/$id', data: {'enabled': enabled});
    return WebhookEndpoint.fromJson(resp.data['data'] as Map<String, dynamic>);
  }

  Future<void> remove(String id) => _dio.delete('$_base/endpoints/$id');

  Future<String> rotateSecret(String id) async {
    final resp = await _dio.post('$_base/endpoints/$id/secret');
    return (resp.data['data'] as Map<String, dynamic>)['secret'] as String;
  }

  Future<String> ping(String id) async {
    final resp = await _dio.post('$_base/endpoints/$id/ping');
    return (resp.data['data'] as Map<String, dynamic>)['deliveryId'] as String;
  }

  Future<List<WebhookDelivery>> deliveries(String endpointId, {int limit = 20}) async {
    final resp = await _dio.get(
      '$_base/deliveries',
      queryParameters: {'endpointId': endpointId, 'limit': limit},
    );
    final data = resp.data['data'] as Map<String, dynamic>? ?? const {};
    return [
      for (final j in data['items'] as List<dynamic>? ?? const [])
        WebhookDelivery.fromJson(j as Map<String, dynamic>),
    ];
  }

  Future<WebhookDelivery> redeliver(String id) async {
    final resp = await _dio.post('$_base/deliveries/$id/redeliver');
    return WebhookDelivery.fromJson(resp.data['data'] as Map<String, dynamic>);
  }
}

final webhooksApiProvider =
    Provider<WebhooksApi>((ref) => WebhooksApi(ref.watch(apiClientProvider).dio));

final webhookEventsProvider = FutureProvider.autoDispose<List<WebhookEventType>>(
  (ref) => ref.watch(webhooksApiProvider).events(),
);

final webhookEndpointsProvider = FutureProvider.autoDispose<List<WebhookEndpoint>>(
  (ref) => ref.watch(webhooksApiProvider).endpoints(),
);
