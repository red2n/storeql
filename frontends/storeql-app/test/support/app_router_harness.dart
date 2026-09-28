import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/router.dart';

import 'fake_api.dart';

// ---------------------------------------------------------------------------
// The app's own router (lib/core/router.dart), opened at a link the way a
// browser opens one on a reload: as the platform's initial route.
//
// One shell per test file. The shells' screens are deferred libraries, and the
// VM loads them as one unit whose load is remembered in the zone of the first
// test that asked; a later test in the same file that needs another shell's
// library would wait on that finished test's zone for ever.
// ---------------------------------------------------------------------------

/// A stand-in server: `'GET /path'` → a JSON body; anything else is a 404.
class ReplyServer implements HttpClientAdapter {
  final Map<String, String> replies;
  final requests = <RequestOptions>[];

  ReplyServer(this.replies);

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final body = replies['${o.method} ${o.path}'];
    return body == null
        ? jsonResponse('{"error":{"code":"NOT_FOUND","message":"no reply"}}', 404)
        : jsonResponse(body);
  }
}

/// Opens the app at [link], signed in with [role], answered by [replies], and
/// returns its router.
Future<GoRouter> followLink(
  WidgetTester tester,
  String link, {
  required String role,
  required Map<String, String> replies,
}) async {
  tester.view.physicalSize = const Size(1400, 1000);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  // What the browser's address bar holds on a reload or a followed link.
  tester.platformDispatcher.defaultRouteNameTestValue = link;
  addTearDown(tester.platformDispatcher.clearDefaultRouteNameTestValue);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = ReplyServer(replies);
  late GoRouter router;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
    ],
    child: Consumer(builder: (context, ref, _) {
      router = ref.watch(routerProvider);
      return MaterialApp.router(routerConfig: router);
    }),
  ));
  await tester.pumpAndSettle();
  return router;
}
