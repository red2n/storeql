import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:storeql_app/core/auth/auth_notifier.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/features/admin/inventory_bond_tab.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The Inventory screen's "Bond & duty" tab: the bonded warehouse by name with
// its approval, the duty per unit, what sits in bond with the duty it would
// crystallise, this month's releases and their total; Approve a store posting
// the number and regime; Release refusing before it posts without a store; and
// no management buttons for a storekeeper, who may still release.
// ---------------------------------------------------------------------------

const _store = '01a0b000-0000-7000-8000-000000000001';
const _variant = '01a0b000-0000-7000-8000-0000000000b1';

class _Server implements HttpClientAdapter {
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final path = o.path;
    if (path.endsWith('/admin/stores')) {
      return jsonResponse(
          '{"data":[{"id":"$_store","name":"Leith Bond","code":"LB","type":"WAREHOUSE","status":"ACTIVE"}],"meta":{"nextCursor":null}}');
    }
    if (path.endsWith('/bond/approvals') && o.method == 'GET') {
      return jsonResponse(
          '{"data":[{"storeId":"$_store","approvalNumber":"GBWK123456789","regime":"EXCISE","active":true}]}');
    }
    if (path.contains('/bond/approvals/') && o.method == 'PUT') {
      return jsonResponse(
          '{"data":{"storeId":"$_store","approvalNumber":"GBWK123456789","regime":"EXCISE","active":true}}');
    }
    if (path.endsWith('/bond/duty-rates')) {
      return jsonResponse(
          '{"data":[{"variantId":"$_variant","dutyPerUnit":2.5,"currency":"GBP","note":"70cl at 40%"}]}');
    }
    if (path.endsWith('/bond/stock')) {
      return jsonResponse(
          '{"data":[{"storeId":"$_store","variantId":"$_variant","qty":10,"dutyPerUnit":2.5,"dutyPotential":25.0}]}');
    }
    if (path.endsWith('/bond/releases') && o.method == 'GET') {
      return jsonResponse(
          '{"data":{"releases":[{"id":"01a0b000-0000-7000-8000-0000000000c1","storeId":"$_store","variantId":"$_variant","qty":4,"dutyAmount":10.0,"currency":"GBP","reference":"W5 Sep","releasedAt":"2026-09-24T10:00:00Z"}],"totalDuty":10.0,"currency":"GBP"}}');
    }
    return jsonResponse('{"data":[]}');
  }
}

Future<_Server> _pump(WidgetTester tester, {String role = 'MANAGER'}) async {
  tester.view.physicalSize = const Size(1100, 1600);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.reset);
  final server = _Server();
  final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = server;
  await tester.pumpWidget(ProviderScope(
    overrides: [
      apiClientProvider.overrideWithValue(FakeApiClient(dio)),
      authNotifierProvider.overrideWith(() => RoleAuth(role)),
    ],
    child: const MaterialApp(home: Scaffold(body: InventoryBondTab())),
  ));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  // Dates are written with AppFormat, in the app's en_GB locale.
  setUpAll(initializeDateFormatting);
  testWidgets('the warehouse, its rate, what sits in bond and this month\'s duty are shown',
      (tester) async {
    await _pump(tester);
    expect(find.text('Leith Bond'), findsOneWidget);
    expect(find.textContaining('approval GBWK123456789'), findsOneWidget);
    expect(find.textContaining('per unit'), findsWidgets);
    expect(find.textContaining('Duty on release'), findsOneWidget);
    expect(find.textContaining('25.00'), findsOneWidget);
    expect(find.byKey(const Key('bond-total-duty')), findsOneWidget);
    expect(find.textContaining('W5 Sep'), findsOneWidget);
  });

  testWidgets('Approve a store puts the number and the regime', (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('bond-approve')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('bond-store')));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Leith Bond').last);
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('bond-number')), 'GBWK123456789');
    await tester.tap(find.byKey(const Key('bond-save')));
    await tester.pumpAndSettle();
    final put = server.requests.lastWhere((r) => r.method == 'PUT');
    expect(put.path, endsWith('/admin/inventory/bond/approvals/$_store'));
    final body = put.data is String ? jsonDecode(put.data as String) : put.data;
    expect(body, {'approvalNumber': 'GBWK123456789', 'regime': 'EXCISE'});
    expect(find.textContaining('Approved'), findsOneWidget);
  });

  testWidgets('Release refuses before posting until the store and variant are picked',
      (tester) async {
    final server = await _pump(tester);
    await tester.tap(find.byKey(const Key('bond-release')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('release-qty')), '4');
    await tester.tap(find.byKey(const Key('release-save')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('release-refusal')), findsOneWidget);
    expect(server.requests.where((r) => r.method == 'POST'), isEmpty);
  });

  testWidgets('a storekeeper may release but not approve or rate', (tester) async {
    await _pump(tester, role: 'STOREKEEPER');
    expect(find.byKey(const Key('bond-approve')), findsNothing);
    expect(find.byKey(const Key('duty-rate-set')), findsNothing);
    expect(find.byKey(const Key('bond-release')), findsOneWidget);
  });
}
