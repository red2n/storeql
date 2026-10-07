import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/auth/auth_state.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/recalls_screen.dart';
import 'package:storeql_app/shared/util/status_labels.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// Catalogue: inventory/rcl-product-recalls gap 1. A recall's source is not a
// list of one country's regulators: the form offers a regulator, the
// manufacturer, the supplier, our own check and other; recalls opened under
// FSA or FSS still read in words.
// ---------------------------------------------------------------------------

class _Server implements HttpClientAdapter {
  final List<RequestOptions> posts = [];
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    if (o.method == 'POST') {
      posts.add(o);
      return jsonResponse('{"data":{}}', 201);
    }
    if (o.path.endsWith('/admin/inventory/recalls')) {
      return jsonResponse('{"data":['
          '{"id":"r-1","reference":"OLD-1","kind":"RECALL","hazard":"ALLERGEN","status":"OPEN","source":"FSA","openedAt":"2026-09-11T08:00:00Z","scopeLines":1,"storesAffected":1,"storesOutstanding":1,"qtyHeld":1},'
          '{"id":"r-2","reference":"NEW-2","kind":"RECALL","hazard":"ALLERGEN","status":"OPEN","source":"REGULATOR","openedAt":"2026-09-12T08:00:00Z","scopeLines":1,"storesAffected":1,"storesOutstanding":1,"qtyHeld":1}'
          ']}');
    }
    if (o.path.contains('/admin/inventory/recalls/')) {
      final id = o.path.split('/').last;
      final src = id == 'r-1' ? 'FSA' : 'REGULATOR';
      return jsonResponse('{"data":{"id":"$id","reference":"REF-$id","kind":"RECALL","hazard":"ALLERGEN",'
          '"reason":"Undeclared peanut","customerNotice":"Do not eat.","remedies":["REFUND"],'
          '"ordersAffected":0,"qtySold":0,"source":"$src","status":"OPEN","openedAt":"2026-09-11T08:00:00Z",'
          '"items":[],"batches":[],"storeActions":[],"stores":[]}}');
    }
    if (o.path.endsWith('/admin/products')) {
      return jsonResponse(
          '{"data":[{"id":"p-1","name":"Crunchy peanut butter","status":"ACTIVE"}]}');
    }
    if (o.path.endsWith('/admin/products/p-1/variants')) {
      return jsonResponse(
          '{"data":[{"id":"v-1","productId":"p-1","sku":"PB-340","status":"ACTIVE"}]}');
    }
    return jsonResponse('{"data":[]}');
  }
}

class _Auth extends AuthNotifier {
  @override
  Future<AuthState> build() async => const AuthAuthenticated(
        accessToken: 'a',
        refreshToken: 'r',
        userId: 'u',
        tenantId: 't',
        roles: ['MANAGER'],
      );
}

Future<_Server> _pump(WidgetTester tester) async {
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))
    ..httpClientAdapter = server;
  tester.view.physicalSize = const Size(1400, 1200);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => _Auth()),
    ],
    child: const MaterialApp(home: Scaffold(body: RecallsScreen())),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  setUpAll(initializeDateFormatting);

  test('every source reads in words, and FSA and FSS stay readable', () {
    expect(recallSourceChoices,
        ['REGULATOR', 'MANUFACTURER', 'SUPPLIER', 'INTERNAL', 'OTHER']);
    expect(recallSourceChoices.map(recallSourceLabel), [
      'A regulator',
      'The manufacturer',
      'The supplier',
      'Our own check',
      'Other',
    ]);
    expect(recallSourceLabel('FSA'), 'Food Standards Agency');
    expect(recallSourceLabel('FSS'), 'Food Standards Scotland');
  });

  testWidgets('a recall opened under FSA, and one under a regulator, read in words',
      (tester) async {
    await _pump(tester);
    await tester.tap(find.text('OLD-1'));
    await tester.pumpAndSettle();
    expect(find.textContaining('from Food Standards Agency'), findsOneWidget);
    await tester.sendKeyEvent(LogicalKeyboardKey.escape);
    await tester.pumpAndSettle();
    await tester.tap(find.text('NEW-2'));
    await tester.pumpAndSettle();
    expect(find.textContaining('from A regulator'), findsOneWidget);
  });

  testWidgets('the open form offers the five sources and sends the one picked',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('recall-open')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('The supplier').last);
    await tester.pumpAndSettle();
    for (final w in [
      'A regulator',
      'The manufacturer',
      'Our own check',
      'Other',
    ]) {
      expect(find.text(w), findsOneWidget, reason: w);
    }
    expect(find.text('Food Standards Agency'), findsNothing);
    expect(find.text('Food Standards Scotland'), findsNothing);
    await tester.tap(find.text('A regulator').last);
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('recall-reference')), 'R-9');
    await tester.enterText(find.byKey(const Key('recall-reason')), 'Glass');
    await tester.enterText(find.byKey(const Key('recall-notice')), 'Do not use.');
    await tester.ensureVisible(find.byKey(const Key('recall-remedy-REFUND')));
    await tester.tap(find.byKey(const Key('recall-remedy-REFUND')));
    await tester.pumpAndSettle();
    await tester.enterText(
        find.byKey(const Key('recall-single-remedy-reason')), 'Cannot be replaced');
    await tester.enterText(find.byKey(const Key('recall-contact-phone')), '0800 100 200');
    await tester.ensureVisible(find.text('Product *'));
    await tester.tap(find.text('Product *'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Crunchy peanut butter').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Variant *'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('PB-340').last);
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('recall-add-item')));
    await tester.tap(find.byKey(const Key('recall-add-item')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('recall-open-save')));
    await tester.pumpAndSettle();
    final body = jsonDecode(server.posts.single.data is String
        ? server.posts.single.data as String
        : jsonEncode(server.posts.single.data)) as Map<String, dynamic>;
    expect(body['source'], 'REGULATOR');
  });
}
