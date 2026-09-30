import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_client.dart';
import 'package:storeql_app/core/network/api_error.dart';
import 'package:storeql_app/features/admin/staff_screen.dart';

import '../../support/fake_api.dart';

// ---------------------------------------------------------------------------
// The refusals the services added on 30 Sep 2026 read as plain sentences where
// a person meets them: never a code, never a vague failure, and never an id.
// A server message that says more than the fallback (which permissions, which
// store) is kept.
// ---------------------------------------------------------------------------

DioException _refusal(int status, String code, [String? message]) {
  final o = RequestOptions(path: '/x');
  return DioException(
    requestOptions: o,
    type: DioExceptionType.badResponse,
    response: Response(
      requestOptions: o,
      statusCode: status,
      data: {'error': {'code': code, 'message': ?message}},
    ),
  );
}

void main() {
  group('friendlyError', () {
    test('a barcode with a wrong check digit says so, in place of the developer\'s wording', () {
      final words = friendlyError(_refusal(400, 'PRODUCT_BARCODE_INVALID',
          'The barcode looks like a GTIN but its check digit is wrong; check the digits'));
      expect(words, "That barcode's check digit is wrong. Check the digits and try again.");
      expect(words, isNot(contains('GTIN')));
    });

    test('a stale repricing proposal says the price is out of date, never naming an id', () {
      final words = friendlyError(_refusal(409, 'PRICING_PROPOSAL_STALE',
          'the rival price behind proposal 01a0b000-0000-7000-8000-00000000000c was seen on 2026-08-01'));
      expect(words, contains('out of date'));
      expect(words, isNot(contains('01a0b000')));
    });

    final fallbacks = {
      'PRODUCT_SKU_DUPLICATE': 'already has that SKU',
      'PRODUCT_BARCODE_DUPLICATE': 'already has that barcode',
      'PRODUCT_CATEGORY_CYCLE': 'under itself',
      'STAFF_OWNER_TIER_OWNER_ONLY': 'Only an owner',
      'ROLE_EXCEEDS_CALLER': 'do not hold yourself',
      'ROLE_STAFF_MANAGE_OWNER_ONLY': 'Only an owner',
      'STORE_ACCESS_DENIED': 'not one of your stores',
    };
    fallbacks.forEach((code, fragment) {
      test('$code reads as a sentence when the server sent no words of its own', () {
        for (final said in [null, '', 'An unexpected error occurred.', code]) {
          final words = friendlyError(_refusal(403, code, said));
          expect(words, contains(fragment), reason: 'said: $said');
          expect(words, isNot(contains('_')), reason: 'no code in the words');
        }
      });
    });

    test('a server message that says more is kept', () {
      expect(
        friendlyError(_refusal(403, 'STORE_ACCESS_DENIED', 'The security trail is business-wide; ask an owner')),
        'The security trail is business-wide; ask an owner',
      );
      expect(
        friendlyError(_refusal(403, 'ROLE_EXCEEDS_CALLER',
            'That role holds permissions you do not hold yourself: stock.adjust')),
        contains('stock.adjust'),
      );
    });

    test('a code the app has no words for still shows the server\'s message, then the fallback', () {
      expect(friendlyError(_refusal(400, 'SOMETHING_NEW', 'Try again later.')), 'Try again later.');
      expect(friendlyError(Exception('boom'), fallback: 'Could not do that.'), 'Could not do that.');
    });
  });

  group('Assign Staff', () {
    testWidgets('an owner-only refusal is shown in words, not as "check the email" or a code', (tester) async {
      tester.view.physicalSize = const Size(1200, 1400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final dio = Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = _Assign();
      await tester.pumpWidget(ProviderScope(
        overrides: [apiClientProvider.overrideWithValue(FakeApiClient(dio))],
        child: const MaterialApp(home: Scaffold(body: StaffScreen())),
      ));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Assign Staff').first);
      await tester.pumpAndSettle();
      await tester.enterText(find.widgetWithText(TextFormField, 'Staff email *'), 'pat@shop.test');
      await tester.tap(find.widgetWithText(DropdownButtonFormField<String>, 'Store *'));
      await tester.pumpAndSettle();
      await tester.tap(find.textContaining('Main').last);
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Assign'));
      await tester.pumpAndSettle();
      expect(find.text('Only an owner of the business can make another owner.'), findsOneWidget);
      expect(find.text('Check the email is valid.'), findsNothing);
      expect(find.textContaining('STAFF_OWNER'), findsNothing);
    });
  });
}

/// Refuses the assignment with a 403 that names its code and nothing else.
class _Assign implements HttpClientAdapter {
  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    if (o.path.endsWith('/auth/admin/staff-users') && o.method == 'POST') {
      return jsonResponse('{"data":{"userId":"u-1","created":false}}');
    }
    if (o.path.endsWith('/admin/staff') && o.method == 'POST') {
      return jsonResponse('{"error":{"code":"STAFF_OWNER_TIER_OWNER_ONLY"}}', 403);
    }
    if (o.path.endsWith('/admin/stores')) {
      return jsonResponse(
          '{"data":[{"id":"s-1","name":"Main","code":"MAIN","type":"STORE","status":"ACTIVE"}],"meta":{}}');
    }
    if (o.path.endsWith('/admin/roles')) return jsonResponse('{"data":[]}');
    return jsonResponse('{"data":[],"meta":{}}');
  }
}
