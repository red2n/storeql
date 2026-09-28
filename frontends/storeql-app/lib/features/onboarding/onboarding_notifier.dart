import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';
import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';

// State for the multi-step wizard
class OnboardingState {
  final int step; // 0 = tenant, 1 = store, 2 = done
  final bool loading;
  final String? error;
  final String? tenantId;

  const OnboardingState({
    this.step = 0,
    this.loading = false,
    this.error,
    this.tenantId,
  });

  OnboardingState copyWith({
    int? step,
    bool? loading,
    String? error,
    String? tenantId,
  }) =>
      OnboardingState(
        step: step ?? this.step,
        loading: loading ?? this.loading,
        error: error,
        tenantId: tenantId ?? this.tenantId,
      );
}

final onboardingNotifierProvider =
    StateNotifierProvider.autoDispose<OnboardingNotifier, OnboardingState>(
  (ref) => OnboardingNotifier(ref),
);

class OnboardingNotifier extends StateNotifier<OnboardingState> {
  final Ref _ref;

  OnboardingNotifier(this._ref) : super(const OnboardingState());

  // Step 1: POST /tenant-svc/onboarding/tenants
  Future<void> createTenant({
    required String businessName,
    String? legalName,
    required String country,
    required String currency,
    // The plan chosen from the price list (21.13); none means the platform's
    // default, which the service picks.
    String? planId,
  }) async {
    state = state.copyWith(loading: true, error: null);
    try {
      final dio = _ref.read(apiClientProvider).dio;
      final resp = await dio.post(
        '/${ApiConstants.tenant}/onboarding/tenants',
        data: {
          'businessName': businessName,
          if (legalName != null && legalName.isNotEmpty) 'legalName': legalName,
          'country': country,
          'currency': currency,
          'planId': ?planId,
        },
      );
      final tenantId = resp.data['data']['id'] as String;
      // iam-svc binds the OWNER role via a Kafka TenantCreated event — poll
      // until the new JWT contains tenantId (up to 5 attempts, 600ms apart).
      await _pollUntilTenantId(maxAttempts: 5, delay: const Duration(milliseconds: 600));
      state = state.copyWith(step: 1, loading: false, tenantId: tenantId);
    } catch (e) {
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  // Step 2: POST /tenant-svc/onboarding/stores
  Future<void> createStore({
    required String name,
    required String code,
    String type = 'STORE',
    String? line1,
    String? city,
    String? country,
    String? pincode,
    String? timezone,
  }) async {
    state = state.copyWith(loading: true, error: null);
    try {
      final dio = _ref.read(apiClientProvider).dio;
      await dio.post(
        '/${ApiConstants.tenant}/onboarding/stores',
        data: {
          'name': name,
          'code': code,
          'type': type,
          'line1': ?line1,
          'city': ?city,
          'country': ?country,
          'pincode': ?pincode,
          'timezone': ?timezone,
        },
      );
      // Refresh once more so JWT reflects the completed tenant
      await _ref.read(authNotifierProvider.notifier).refresh();
      state = state.copyWith(step: 2, loading: false);
    } catch (e) {
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  /// Refresh JWT in a loop until `tenantId` is present in the claims.
  /// Stops early on success; falls through after [maxAttempts] regardless.
  Future<void> _pollUntilTenantId({
    required int maxAttempts,
    required Duration delay,
  }) async {
    for (var i = 0; i < maxAttempts; i++) {
      await _ref.read(authNotifierProvider.notifier).refresh();
      final auth = _ref.read(authNotifierProvider).value;
      if (auth is AuthAuthenticated && auth.tenantId != null) return;
      if (i < maxAttempts - 1) await Future.delayed(delay);
    }
  }

  String _friendly(Object e) {
    if (e is DioException && e.response?.statusCode == 409) {
      // A login owns one business (21.13); any other refusal says why itself.
      final body = e.response?.data;
      final code = body is Map ? body['code'] : null;
      if (code == null || code == 'TENANT_ALREADY_OWNED') {
        return 'This login already owns a business. Sign in to it, or sign up with another login.';
      }
    }
    return friendlyError(e,
        fallback: 'Something went wrong. Please try again.');
  }
}
