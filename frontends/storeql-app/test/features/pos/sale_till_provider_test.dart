import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/pos/cash_providers.dart';
import 'package:storeql_app/features/pos/pos_providers.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The drawer sales name (`saleTillProvider`), on its own: the open till of the
// person signed in, at this store, as payment-svc says. What it must never do:
// take a failed read for "no till", hand back the last store's or the last
// person's drawer while a new one is being read, or hold a sale up.
// ---------------------------------------------------------------------------

const _drawerA = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c00a1';
const _drawerB = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c00b2';
const _none = 'none';
const _down = 'down';

class _Auth extends AuthNotifier {
  _Auth(this.userId);

  final String? userId;

  @override
  Future<AuthState> build() async => userId == null
      ? const AuthUnauthenticated()
      : AuthAuthenticated(
          accessToken: 'a',
          refreshToken: 'r',
          userId: userId!,
          tenantId: 't',
          roles: const ['CASHIER'],
        );

  void signInAs(String id) => state = AsyncData(AuthAuthenticated(
        accessToken: 'a2',
        refreshToken: 'r2',
        userId: id,
        tenantId: 't',
        roles: const ['CASHIER'],
      ));

  /// The same person, a refreshed token.
  void refreshToken() {
    final now = state.value as AuthAuthenticated;
    state = AsyncData(AuthAuthenticated(
      accessToken: '${now.accessToken}+',
      refreshToken: now.refreshToken,
      userId: now.userId,
      tenantId: now.tenantId,
      roles: now.roles,
    ));
  }
}

/// payment-svc's "which till is open for me here", per store and caller.
class _Payments implements HttpClientAdapter {
  /// Answers in turn per `caller|store` (the last repeats): a session id,
  /// [_none] (404) or [_down] (503).
  final Map<String, List<String>> answers = {};
  final Map<String, int> _asked = {};
  String caller = 'u-a';
  Completer<void>? hold;
  final List<RequestOptions> asks = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    asks.add(o);
    final h = hold;
    if (h != null) await h.future;
    final key = '$caller|${o.queryParameters['storeId']}';
    final list = answers[key] ?? const [_none];
    final n = _asked[key] = (_asked[key] ?? 0) + 1;
    final answer = list[n > list.length ? list.length - 1 : n - 1];
    if (answer == _down) {
      return jsonResponse(
          '{"error":{"code":"SERVICE_UNAVAILABLE","message":"Down."}}', 503);
    }
    if (answer == _none) {
      return jsonResponse(
          '{"error":{"code":"TILL_SESSION_NOT_OPEN","message":"None."}}', 404);
    }
    return jsonResponse(
        jsonEncode({
          'data': {
            'id': answer,
            'status': 'OPEN',
            'storeId': o.queryParameters['storeId'],
          }
        }),
        200);
  }
}

class _Rig {
  _Rig({String? store = 'store-1', this.user = 'u-a', bool listen = true})
      : payments = _Payments() {
    final dio = Dio(BaseOptions(baseUrl: 'http://test'))
      ..httpClientAdapter = payments;
    container = ProviderContainer(overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => _Auth(user)),
      posStoreProvider.overrideWith((ref) => store),
    ]);
    addTearDown(container.dispose);
    // The POS shell watches the drawer from the moment the terminal is on.
    if (listen) container.listen(saleTillProvider, (_, _) {});
  }

  final _Payments payments;
  final String? user;
  late final ProviderContainer container;

  SaleTillNotifier get till => container.read(saleTillProvider.notifier);
  AsyncValue<String?> get state => container.read(saleTillProvider);
  _Auth get auth => container.read(authNotifierProvider.notifier) as _Auth;

  /// The first answer, once the sign-in is known (as it is long before the POS
  /// shell is on screen).
  Future<String?> read() async {
    await container.read(authNotifierProvider.future);
    return container.read(saleTillProvider.future);
  }

  /// Lets whatever is in flight land.
  Future<void> settleAll() async {
    for (var i = 0; i < 20; i++) {
      await Future<void>.delayed(Duration.zero);
    }
  }
}

void main() {
  group('what is read', () {
    test('the open till of this person at this store', () async {
      final rig = _Rig();
      rig.payments.answers['u-a|store-1'] = [_drawerA];

      expect(await rig.read(), _drawerA);

      final ask = rig.payments.asks.single;
      expect(ask.method, 'GET');
      expect(ask.path, '/payment-svc/admin/cash/till-sessions/current');
      expect(ask.queryParameters['storeId'], 'store-1');
      expect(rig.state.drawer, _drawerA);
      expect(rig.till.settled, isTrue);
    });

    test('no till open (404) is "none", read once, with no retry loop',
        () async {
      final rig = _Rig();

      expect(await rig.read(), isNull);
      await rig.settleAll();

      expect(rig.state.hasError, isFalse);
      expect(rig.state.drawer, isNull);
      expect(rig.till.settled, isTrue);
      expect(rig.payments.asks, hasLength(1),
          reason: 'an answer is not retried behind anyone\'s back');
    });

    test('nobody signed in, or no store: nothing is asked', () async {
      final noUser = _Rig(user: null);
      expect(await noUser.read(), isNull);
      final noStore = _Rig(store: null);
      expect(await noStore.read(), isNull);

      expect(noUser.payments.asks, isEmpty);
      expect(noStore.payments.asks, isEmpty);
    });
  });

  group('a read that failed', () {
    test('is an error, not "no till"; it names no drawer', () async {
      final rig = _Rig();
      rig.payments.answers['u-a|store-1'] = [_down, _drawerA];

      await expectLater(rig.read(), throwsA(isA<DioException>()));

      expect(rig.state.hasError, isTrue,
          reason: 'a failed read must not be cached as the answer "none"');
      expect(rig.state.drawer, isNull);
      expect(rig.till.settled, isFalse);
    });

    test('is read again by settle, and the drawer is then named', () async {
      final rig = _Rig();
      rig.payments.answers['u-a|store-1'] = [_down, _drawerA];
      await expectLater(rig.read(), throwsA(isA<DioException>()));

      await rig.till.settle();

      expect(rig.state.drawer, _drawerA);
      expect(rig.till.settled, isTrue);
      expect(rig.payments.asks, hasLength(2));
    });

    test('is read again by settle even when nothing is listening', () async {
      final rig = _Rig(listen: false);
      rig.payments.answers['u-a|store-1'] = [_down, _drawerA];
      await rig.container.read(authNotifierProvider.future);

      await rig.till.settle(); // first read, fails
      await rig.till.settle(); // read again

      expect(rig.till.drawer, _drawerA);
    });

    test('that fails again leaves the sale to go on naming none, at once',
        () async {
      final rig = _Rig();
      rig.payments.answers['u-a|store-1'] = [_down];
      await expectLater(rig.read(), throwsA(isA<DioException>()));

      await rig.till.settle(); // must not throw

      expect(rig.till.drawer, isNull);
      expect(rig.till.settled, isFalse, reason: 'it will be tried again');
    });
  });

  group('a read that is slow', () {
    test('is waited for, up to a limit', () async {
      final rig = _Rig();
      await rig.container.read(authNotifierProvider.future);
      rig.payments.hold = Completer<void>();
      rig.payments.answers['u-a|store-1'] = [_drawerA];
      await rig.settleAll();
      expect(rig.till.settled, isFalse);

      final waiting = rig.till.settle();
      rig.payments.hold!.complete();
      await waiting;

      expect(rig.till.drawer, _drawerA);
    });

    test('but never for longer than the limit, and never with an error',
        () async {
      final rig = _Rig();
      await rig.container.read(authNotifierProvider.future);
      rig.payments.hold = Completer<void>(); // the server never answers

      final watch = Stopwatch()..start();
      await rig.till.settle(wait: const Duration(milliseconds: 60));

      expect(watch.elapsedMilliseconds, lessThan(2000));
      expect(rig.till.drawer, isNull);
      rig.payments.hold!.complete();
    });
  });

  group('who and where', () {
    test('a different store is read again, and the last store\'s drawer is '
        'not named while it is', () async {
      final rig = _Rig();
      rig.payments.answers['u-a|store-1'] = [_drawerA];
      rig.payments.answers['u-a|store-2'] = [_drawerB];
      expect(await rig.read(), _drawerA);
      rig.payments.hold = Completer<void>();

      rig.container.read(posStoreProvider.notifier).state = 'store-2';
      await rig.settleAll();

      expect(rig.state.drawer, isNull,
          reason: 'store-1\'s drawer, while store-2\'s is being read');
      expect(rig.till.settled, isFalse);
      rig.payments.hold!.complete();
      await rig.settleAll();
      expect(rig.state.drawer, _drawerB);
    });

    test('another person is read again, and the last one\'s drawer is not '
        'named while it is', () async {
      final rig = _Rig();
      rig.payments.answers['u-a|store-1'] = [_drawerA];
      rig.payments.answers['u-b|store-1'] = [_none];
      expect(await rig.read(), _drawerA);

      rig.payments.caller = 'u-b';
      rig.payments.hold = Completer<void>();
      rig.auth.signInAs('u-b');
      await rig.settleAll();
      expect(rig.state.drawer, isNull, reason: 'A\'s drawer is not B\'s');

      rig.payments.hold!.complete();
      await rig.settleAll();
      expect(rig.state.drawer, isNull);
      expect(rig.till.settled, isTrue, reason: 'B has none: an answer');
      expect(rig.payments.asks, hasLength(2));
    });

    test('signing out leaves none; the same person\'s refreshed token is not '
        'another person', () async {
      final rig = _Rig();
      rig.payments.answers['u-a|store-1'] = [_drawerA];
      expect(await rig.read(), _drawerA);

      rig.auth.refreshToken();
      await rig.settleAll();
      expect(rig.state.drawer, _drawerA);
      expect(rig.payments.asks, hasLength(1),
          reason: 'a token refresh is not a reason to ask again');

      rig.auth.state = const AsyncData(AuthUnauthenticated());
      await rig.settleAll();
      expect(rig.state.drawer, isNull);
    });
  });

  group('what the Cash screen and the server tell it', () {
    test('opened and closed set it at once, and say nothing twice', () async {
      final rig = _Rig();
      expect(await rig.read(), isNull);
      var changes = 0;
      rig.container.listen(saleTillProvider, (_, _) => changes++);

      rig.till.opened(_drawerA);
      expect(rig.state.drawer, _drawerA);
      rig.till.opened(_drawerA);
      rig.till.set(_drawerA);
      expect(changes, 1, reason: 'the same answer again is no change');

      rig.till.closed();
      expect(rig.state.drawer, isNull);
      expect(changes, 2);
    });

    test('a till opened while the first read is still going is not undone '
        'by that older answer', () async {
      final rig = _Rig();
      await rig.container.read(authNotifierProvider.future);
      rig.payments.hold = Completer<void>();
      rig.payments.answers['u-a|store-1'] = [_none];
      await rig.settleAll();
      expect(rig.till.settled, isFalse);

      rig.till.opened(_drawerA);
      rig.payments.hold!.complete();
      await rig.settleAll();

      expect(rig.state.drawer, _drawerA,
          reason: 'the read asked before the till was opened found none');
    });

    test('a drawer the server refused is read again; another is left alone',
        () async {
      final rig = _Rig();
      rig.payments.answers['u-a|store-1'] = [_drawerA, _drawerB];
      expect(await rig.read(), _drawerA);

      rig.till.refused(_drawerB); // not the one held
      await rig.settleAll();
      expect(rig.payments.asks, hasLength(1));
      expect(rig.state.drawer, _drawerA);

      rig.till.refused(_drawerA);
      await rig.settleAll();
      expect(rig.payments.asks, hasLength(2));
      expect(rig.state.drawer, _drawerB);
    });

    test('and is read again even when nothing is listening', () async {
      final rig = _Rig(listen: false);
      rig.payments.answers['u-a|store-1'] = [_drawerA, _none];
      await rig.container.read(authNotifierProvider.future);
      await rig.till.settle();
      expect(rig.till.drawer, _drawerA);

      rig.till.refused(_drawerA);
      await rig.settleAll();

      expect(rig.payments.asks, hasLength(2));
      expect(rig.till.drawer, isNull);
      expect(rig.till.settled, isTrue);
    });
  });
}
