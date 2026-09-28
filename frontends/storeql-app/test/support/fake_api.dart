import 'package:dio/dio.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';

/// An [ApiClient] whose Dio a test wires to its own adapter.
class FakeApiClient implements ApiClient {
  @override
  Dio dio;

  FakeApiClient(this.dio);
}

/// A login holding one role in tenant `t`; in the business's sandbox when
/// `sandbox` is set (22.8).
class RoleAuth extends AuthNotifier {
  final String role;
  final bool sandbox;

  RoleAuth(this.role, {this.sandbox = false});

  @override
  Future<AuthState> build() async => AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'u',
        tenantId: 't',
        roles: [role],
        sandbox: sandbox,
      );
}

/// A JSON answer from a test's stand-in server.
ResponseBody jsonResponse(String body, [int status = 200]) =>
    ResponseBody.fromString(
      body,
      status,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
