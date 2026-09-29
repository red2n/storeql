import 'package:dio/dio.dart';
import 'package:flutter_riverpod/legacy.dart';
import '../../core/constants.dart';
import '../../core/network/api_error.dart';

class TenantOnboardingState {
  final int step; // 0=account, 1=business+store, 2=done
  final bool loading;
  final String? error;
  final String? ownerEmail;
  final String? newUserToken;
  final String? tenantName;

  const TenantOnboardingState({
    this.step = 0,
    this.loading = false,
    this.error,
    this.ownerEmail,
    this.newUserToken,
    this.tenantName,
  });

  bool get isDone => step == 2;

  TenantOnboardingState copyWith({
    int? step,
    bool? loading,
    String? error,
    String? ownerEmail,
    String? newUserToken,
    String? tenantName,
  }) =>
      TenantOnboardingState(
        step: step ?? this.step,
        loading: loading ?? this.loading,
        error: error,
        ownerEmail: ownerEmail ?? this.ownerEmail,
        newUserToken: newUserToken ?? this.newUserToken,
        tenantName: tenantName ?? this.tenantName,
      );
}

final tenantOnboardingProvider = StateNotifierProvider.autoDispose<
    TenantOnboardingNotifier, TenantOnboardingState>(
  (ref) => TenantOnboardingNotifier(),
);

class TenantOnboardingNotifier extends StateNotifier<TenantOnboardingState> {
  /// A client of its own, not the app's: these calls are made as the new
  /// owner, so the platform administrator's session must never ride on them.
  /// [dio] is for tests, which answer its requests themselves.
  TenantOnboardingNotifier({Dio? dio})
      : _dio = dio ??
            Dio(BaseOptions(
              baseUrl: ApiConstants.baseUrl,
              connectTimeout: const Duration(seconds: 8),
              receiveTimeout: const Duration(seconds: 15),
              headers: const {'Content-Type': 'application/json'},
            )),
        super(const TenantOnboardingState());

  final Dio _dio;

  /// Step 0 → 1: sign the new owner up and in.
  ///
  /// With the business sign-up (iam-svc `POST /auth/register/business`), never
  /// the shopper's: the owner runs the business with a business login, a
  /// separate identity from any shopper's account on the same email or phone
  /// (29 Sep 2026). So an address the person already shops with is not
  /// refused here, and the login onboarding makes the business's OWNER is
  /// never their shopping account.
  Future<void> registerOwner({
    required String email,
    required String password,
    String? phone,
  }) async {
    state = state.copyWith(loading: true, error: null);
    try {
      await _dio.post(
        '/${ApiConstants.iam}/auth/register/business',
        data: {
          'email': email,
          'password': password,
          if (phone != null && phone.isNotEmpty) 'phone': phone,
        },
      );
      final loginResp = await _dio.post(
        '/${ApiConstants.iam}/auth/login',
        // The business login just made, by name: the same address may also
        // hold a shopper's account, which must never be the one onboarded.
        data: {'email': email, 'password': password, 'accountType': 'STAFF'},
      );
      final data = loginResp.data['data'] as Map<String, dynamic>;
      state = state.copyWith(
        step: 1,
        loading: false,
        ownerEmail: email,
        newUserToken: data['accessToken'] as String,
      );
    } catch (e) {
      state = state.copyWith(
          loading: false, error: _friendly(e, signingUp: true));
    }
  }

  /// Step 1 → 2: Single POST /onboarding that creates tenant + store atomically.
  /// Uses the new user's token — no tenantId in JWT required.
  Future<void> onboard({
    required String businessName,
    String? legalName,
    required String country,
    required String currency,
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
      await _dio.post(
        '/${ApiConstants.tenant}/onboarding',
        data: {
          'businessName': businessName,
          if (legalName != null && legalName.isNotEmpty) 'legalName': legalName,
          'country': country,
          'currency': currency,
          'storeName': storeName,
          'storeCode': storeCode,
          'storeType': storeType,
          if (storeLine1 != null && storeLine1.isNotEmpty) 'storeLine1': storeLine1,
          if (storeCity != null && storeCity.isNotEmpty) 'storeCity': storeCity,
          if (storeCountry != null && storeCountry.isNotEmpty) 'storeCountry': storeCountry,
          if (storePincode != null && storePincode.isNotEmpty) 'storePincode': storePincode,
          if (storeTimezone != null && storeTimezone.isNotEmpty)
            'storeTimezone': storeTimezone,
        },
        options: Options(
          headers: {'Authorization': 'Bearer ${state.newUserToken}'},
        ),
      );
      state = state.copyWith(step: 2, loading: false, tenantName: businessName);
    } catch (e) {
      state = state.copyWith(loading: false, error: _friendly(e));
    }
  }

  void reset() => state = const TenantOnboardingState();

  /// [signingUp] is set for the owner's sign-up, whose one conflict is the
  /// address or phone being taken; a conflict creating the business is its
  /// own, said in the server's words.
  String _friendly(Object e, {bool signingUp = false}) {
    if (e is DioException) {
      final status = e.response?.statusCode;
      // The business sign-up refuses only another business login of no
      // business holding the email or phone; a shopper's account never does.
      if (signingUp && status == 409) {
        return 'A business login with this email or phone already exists.';
      }
      if (status == 401) return 'Authentication failed. Try again.';
    }
    return friendlyError(e,
        fallback: 'Something went wrong. Please try again.');
  }
}
