import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/offline/offline_sale.dart';

// ---------------------------------------------------------------------------
// A tender names the till session it was taken at. Money that has been taken
// must be recorded whatever became of that session while the sale was open (or
// waited in the offline queue), so a refusal of the session is answered once by
// sending the tender again naming none — under the same key, because the
// refusal wrote nothing. Any other refusal is the caller's to see.
// ---------------------------------------------------------------------------

class _Payments implements HttpClientAdapter {
  _Payments(this.answers);

  /// What each call in turn answers: null for 201, else the error code (409).
  final List<String?> answers;
  final List<RequestOptions> requests = [];

  @override
  void close({bool force = false}) {}

  @override
  Future<ResponseBody> fetch(
      RequestOptions o, Stream<List<int>>? s, Future<void>? c) async {
    requests.add(o);
    final code = answers[requests.length - 1];
    return ResponseBody.fromString(
      jsonEncode(code == null
          ? {'data': {}}
          : {
              'error': {'code': code, 'message': ''}
            }),
      code == null ? 201 : 409,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }
}

const _key = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c0001';
const _drawer = '01a0c830-0e7a-7b3c-9d2e-5f1a2b3c0002';

Map<String, dynamic> get _tender => {
      'orderId': 'o-1',
      'amount': 12.5,
      'method': 'CASH',
      'tillSessionId': _drawer,
    };

Dio _dio(_Payments p) =>
    Dio(BaseOptions(baseUrl: 'http://test'))..httpClientAdapter = p;

void main() {
  test('a tender is sent once, naming its drawer, under its key', () async {
    final server = _Payments([null]);

    await postTender(_dio(server), _tender, idempotencyKey: _key);

    expect(server.requests, hasLength(1));
    expect(server.requests.single.path, '/payment-svc/payments');
    expect(server.requests.single.data['tillSessionId'], _drawer);
    expect(server.requests.single.headers['Idempotency-Key'], _key);
  });

  for (final code in [
    'TILL_SESSION_NOT_OPEN',
    'TILL_SESSION_OTHER_STORE',
    'TILL_SESSION_NOT_FOUND',
  ]) {
    test('$code: the money is recorded again naming no drawer, same key',
        () async {
      final server = _Payments([code, null]);

      await postTender(_dio(server), _tender, idempotencyKey: _key);

      expect(server.requests, hasLength(2));
      expect(server.requests[1].data.containsKey('tillSessionId'), isFalse);
      expect(server.requests[1].data['amount'], 12.5);
      expect(server.requests[1].data['orderId'], 'o-1');
      expect(server.requests[1].headers['Idempotency-Key'], _key);
    });
  }

  test('any other refusal is not hidden, and nothing is sent twice', () async {
    final server = _Payments(['PAYMENT_CARD_NEEDS_TERMINAL']);

    await expectLater(
      postTender(_dio(server), _tender, idempotencyKey: _key),
      throwsA(isA<DioException>()),
    );
    expect(server.requests, hasLength(1));
  });

  test('a tender that names no drawer is not retried for one', () async {
    final server = _Payments(['TILL_SESSION_NOT_OPEN']);
    final bare = {..._tender}..remove('tillSessionId');

    await expectLater(
      postTender(_dio(server), bare, idempotencyKey: _key),
      throwsA(isA<DioException>()),
    );
    expect(server.requests, hasLength(1));
  });

  test('the second answer is the caller\'s: a refusal there is thrown',
      () async {
    final server = _Payments(['TILL_SESSION_NOT_OPEN', 'PAYMENT_ORDER_PAID']);

    await expectLater(
      postTender(_dio(server), _tender, idempotencyKey: _key),
      throwsA(isA<DioException>()),
    );
    expect(server.requests, hasLength(2));
  });
}
