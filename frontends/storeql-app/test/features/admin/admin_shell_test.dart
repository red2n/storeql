import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/theme.dart';
import 'package:storeql_app/features/admin/admin_shell.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The back office's navigation. Data export is the owner's alone — taking the
// business's data out, bringing it in, giving notice — so it is listed for the
// owner and nobody else: a manager is never sent to a page that only says it
// is not theirs.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
    RequestOptions o,
    Stream<List<int>>? s,
    Future<void>? c,
  ) async {
    if (o.path.endsWith('/admin/tenant')) {
      return jsonResponse(
          '{"data":{"id":"t","name":"Corner Stores","currency":"GBP"}}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<void> _pump(WidgetTester tester, String role) async {
  // Desktop width: the sectioned list sits beside the page, every entry drawn.
  tester.view.physicalSize = const Size(1400, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = _Server();
  await tester.pumpWidget(ProviderScope(
    key: UniqueKey(),
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
    ],
    child: MaterialApp(
      theme: AppTheme.light,
      home: const AdminShell(
        currentLocation: '/admin/retention',
        child: SizedBox.shrink(),
      ),
    ),
  ));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('the owner finds Data export under Data & security',
      (tester) async {
    await _pump(tester, 'OWNER');
    expect(find.text('Data export'), findsOneWidget);
    expect(find.text('Data retention'), findsOneWidget);
  });

  testWidgets('a manager is not offered Data export, and keeps the rest',
      (tester) async {
    await _pump(tester, 'MANAGER');
    expect(find.text('Data export'), findsNothing);
    expect(find.text('Data retention'), findsOneWidget);
    expect(find.text('Privacy'), findsOneWidget);
  });

  testWidgets('a storekeeper is not offered Data export either', (tester) async {
    await _pump(tester, 'STOREKEEPER');
    expect(find.text('Data export'), findsNothing);
    expect(find.text('Inventory'), findsOneWidget);
  });
}
