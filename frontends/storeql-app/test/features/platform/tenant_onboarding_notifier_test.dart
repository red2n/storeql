import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/features/platform/tenant_onboarding_notifier.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The platform console's assisted onboarding (29 Sep 2026). A shopper's
// account and a business account are separate identities, so the owner the
// console creates is signed up with the business sign-up (iam-svc POST
// /auth/register/business), phone and all, and signed in asking for the
// business login by name (accountType STAFF). It used the shopper's sign-up:
// the business was then owned by a shopping account, and an owner who already
// shopped with the address was refused.
// ---------------------------------------------------------------------------

const _business = '/iam-svc/auth/register/business';
const _shopper = '/iam-svc/auth/register';
const _login = '/iam-svc/auth/login';
const _onboard = '/tenant-svc/onboarding';

const _email = 'owner@example.com';
const _password = 'a phrase long enough';

class _Server implements HttpClientAdapter {
  final Map<String, ResponseBody Function(RequestOptions)> routes;
  final List<RequestOptions> requests = [];

  _Server(this.routes);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    requests.add(o);
    final route = routes['${o.method} ${o.path}'];
    return route == null
        ? jsonResponse('{"error":{"code":"NOT_FOUND","message":"no route"}}', 404)
        : route(o);
  }

  List<RequestOptions> to(String path) =>
      requests.where((r) => r.path == path).toList();
}

/// An access token as iam-svc mints one. Only its payload would be read.
String _token(Map<String, Object?> claims) {
  String part(Object o) =>
      base64Url.encode(utf8.encode(jsonEncode(o))).replaceAll('=', '');
  return '${part({'alg': 'RS256', 'kid': 'k'})}.${part(claims)}.signature';
}

/// The token pair iam-svc answers a sign-up or a sign-in with.
String _tokens(String access) => jsonEncode({
  'data': {
    'accessToken': access,
    'refreshToken': 'refresh-1',
    'tokenType': 'Bearer',
    'expiresInSeconds': 900,
  },
});

/// A staff login of no business and no role: what the business sign-up makes.
final _signUpToken = _token({
  'sub': '019987a0-0f1e-7c3b-8a4d-3e2f1a0b9c81',
  'type': 'STAFF',
  'roles': <String>[],
  'email': _email,
  'amr': ['pwd'],
});

/// The same login signed in again: the token the business is created with.
/// Its `iat` tells it apart from the sign-up's, so the test sees which is kept.
final _signInToken = _token({
  'sub': '019987a0-0f1e-7c3b-8a4d-3e2f1a0b9c81',
  'type': 'STAFF',
  'roles': <String>[],
  'email': _email,
  'amr': ['pwd'],
  'iat': 2,
});

_Server _server({
  ResponseBody Function(RequestOptions)? signUp,
  ResponseBody Function(RequestOptions)? onboard,
}) =>
    _Server({
      'POST $_business':
          signUp ?? ((_) => jsonResponse(_tokens(_signUpToken), 201)),
      'POST $_login': (_) => jsonResponse(_tokens(_signInToken)),
      'POST $_onboard': onboard ??
          ((_) => jsonResponse(
                '{"data":{"tenant":{"id":"019987a0-0f1e-7c3b-8a4d-3e2f1a0b9c90"}}}',
                201,
              )),
    });

TenantOnboardingNotifier _notifier(_Server server) => TenantOnboardingNotifier(
      dio: Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server,
    );

void main() {
  test(
    'the owner is signed up with the business sign-up, phone included, and '
    'signed in as a business login',
    () async {
      final server = _server();
      final notifier = _notifier(server);

      await notifier.registerOwner(
        email: _email,
        password: _password,
        phone: '+81 3 1234 5678',
      );

      final signUps = server.to(_business);
      expect(signUps, hasLength(1));
      expect(signUps.single.method, 'POST');
      expect(signUps.single.data, {
        'email': _email,
        'password': _password,
        'phone': '+81 3 1234 5678',
      });
      expect(server.to(_shopper), isEmpty,
          reason: "never the shopper's sign-up");

      final signIns = server.to(_login);
      expect(signIns, hasLength(1));
      expect(signIns.single.data, {
        'email': _email,
        'password': _password,
        'accountType': 'STAFF',
      });

      expect(notifier.state.step, 1);
      expect(notifier.state.error, isNull);
      expect(notifier.state.ownerEmail, _email);
      expect(notifier.state.newUserToken, _signInToken);
      expect(server.requests.map((r) => r.path).toList(), [_business, _login],
          reason: 'signed up first, then in');
    },
  );

  test('with no phone given, none is sent', () async {
    final server = _server();
    final notifier = _notifier(server);

    await notifier.registerOwner(email: _email, password: _password, phone: '');

    expect(server.to(_business).single.data, {
      'email': _email,
      'password': _password,
    });
    expect(server.to(_login).single.data['accountType'], 'STAFF');
    expect(notifier.state.step, 1);
  });

  test('the business is created with the business login signed in', () async {
    final server = _server();
    final notifier = _notifier(server);
    await notifier.registerOwner(email: _email, password: _password);

    await notifier.onboard(
      businessName: 'Kyoto Market',
      country: 'JP',
      currency: 'JPY',
      storeName: 'Kyoto Market Gion',
      storeCode: 'KY-1',
      storeTimezone: 'Asia/Tokyo',
    );

    final onboarded = server.to(_onboard);
    expect(onboarded, hasLength(1));
    expect(onboarded.single.headers['Authorization'], 'Bearer $_signInToken');
    expect(notifier.state.isDone, isTrue);
  });

  test(
    'an email or phone another business sign-up holds is said so, and nobody '
    'is signed in',
    () async {
      final server = _server(
        signUp: (_) => jsonResponse(
          '{"error":{"code":"USER_ALREADY_EXISTS",'
          '"message":"Email or phone already registered"}}',
          409,
        ),
      );
      final notifier = _notifier(server);

      await notifier.registerOwner(
        email: _email,
        password: _password,
        phone: '+44 20 7946 0000',
      );

      expect(notifier.state.step, 0);
      expect(notifier.state.loading, isFalse);
      expect(notifier.state.error,
          'A business login with this email or phone already exists.');
      expect(server.to(_login), isEmpty);
      expect(notifier.state.newUserToken, isNull);
    },
  );

  test(
    "a conflict creating the business is said in the server's words, not as a "
    'taken email',
    () async {
      final server = _server(
        onboard: (_) => jsonResponse(
          '{"error":{"code":"CODE_ALREADY_EXISTS",'
          '"message":"A record with that code already exists"}}',
          409,
        ),
      );
      final notifier = _notifier(server);
      await notifier.registerOwner(email: _email, password: _password);

      await notifier.onboard(
        businessName: 'Pune Provisions',
        country: 'IN',
        currency: 'INR',
        storeName: 'Pune Provisions Kothrud',
        storeCode: 'PN-1',
        storeTimezone: 'Asia/Kolkata',
      );

      expect(notifier.state.step, 1);
      expect(notifier.state.error, 'A record with that code already exists');
    },
  );
}
