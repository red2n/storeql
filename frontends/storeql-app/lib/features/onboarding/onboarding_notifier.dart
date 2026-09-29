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
  final int step; // 0 = business details, 1 = first store, 2 = done
  final bool loading;
  final String? error;
  // Set once the business and its first store exist. Pressing the button again
  // then only waits for the sign-in to catch up: the business is never sent twice.
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

  // Step 1 → 2. Nothing is sent yet: the business and its first store are
  // created together by [finish]. Created here on its own, the business made
  // the next token refresh name it and grant OWNER — and the router takes a
  // login whose token names a business out of the wizard, so the first store
  // was never asked for (and, where the grant was slow, was refused for want
  // of a business in the token).
  void toFirstStore() => state = state.copyWith(step: 1);

  // Step 2 → 1, while nothing exists yet: a refusal of the business itself (a
  // plan no longer on sale, say) is answered on the store step, and is changed
  // on the details step. The refusal goes back with it.
  void backToDetails() => state = state.copyWith(step: 0, error: state.error);

  // Step 2: POST /tenant-svc/onboarding — the business, its first store and that
  // store's DEFAULT zone in one call, as the platform console's assisted
  // onboarding does. Only then is the token refreshed until it names the
  // business; that refresh is what takes the new owner to the dashboard.
  Future<void> finish({
    required String businessName,
    String? legalName,
    required String country,
    required String currency,
    // The plan chosen from the price list (21.13); none means the platform's
    // default, which the service picks.
    String? planId,
    required String storeName,
    required String storeCode,
    String storeType = 'STORE',
    String? storeLine1,
    String? storeCity,
    String? storeCountry,
    String? storePincode,
    String? storeTimezone,
  }) async {
    state = state.copyWith(loading: true, error: null);
    try {
      if (state.tenantId == null) {
        final dio = _ref.read(apiClientProvider).dio;
        final resp = await dio.post(
          '/${ApiConstants.tenant}/onboarding',
          data: {
            'businessName': businessName,
            if (legalName != null && legalName.isNotEmpty) 'legalName': legalName,
            'country': country,
            'currency': currency,
            'planId': ?planId,
            'storeName': storeName,
            'storeCode': storeCode,
            'storeType': storeType,
            if (storeLine1 != null && storeLine1.isNotEmpty) 'storeLine1': storeLine1,
            if (storeCity != null && storeCity.isNotEmpty) 'storeCity': storeCity,
            if (storeCountry != null && storeCountry.isNotEmpty) 'storeCountry': storeCountry,
            if (storePincode != null && storePincode.isNotEmpty) 'storePincode': storePincode,
            'storeTimezone': ?storeTimezone,
          },
        );
        if (!mounted) return;
        state = state.copyWith(tenantId: resp.data['data']['tenant']['id'] as String);
      }
      // iam-svc binds the OWNER role via a Kafka TenantCreated event — poll
      // until the new JWT contains tenantId (up to 10 attempts, 600ms apart).
      final signedIn =
          await _pollUntilTenantId(maxAttempts: 10, delay: const Duration(milliseconds: 600));
      if (!mounted) return;
      state = signedIn
          ? state.copyWith(step: 2, loading: false)
          : state.copyWith(
              loading: false,
              error: 'Your business and its first store are set up. Signing you in to it is '
                  'taking longer than usual — press Finish setup again in a moment.');
    } catch (e) {
      if (!mounted) return;
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  /// Refresh JWT in a loop until `tenantId` is present in the claims: true once
  /// it is, false after [maxAttempts]. The router leaves the wizard the moment a
  /// refreshed token names the business, and this notifier goes with it, so a
  /// disposed notifier counts as done — its ref is not touched again.
  Future<bool> _pollUntilTenantId({
    required int maxAttempts,
    required Duration delay,
  }) async {
    for (var i = 0; i < maxAttempts; i++) {
      await _ref.read(authNotifierProvider.notifier).refresh();
      if (!mounted) return true;
      final auth = _ref.read(authNotifierProvider).value;
      if (auth is AuthAuthenticated && auth.tenantId != null) return true;
      if (i < maxAttempts - 1) await Future.delayed(delay);
      if (!mounted) return false;
    }
    return false;
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
