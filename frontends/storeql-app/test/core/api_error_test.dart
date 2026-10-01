import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/network/api_error.dart';

DioException _dioWith(Map<String, dynamic>? body, {DioExceptionType? type}) {
  final opts = RequestOptions(path: '/x');
  return DioException(
    requestOptions: opts,
    type: type ?? DioExceptionType.badResponse,
    response: body == null
        ? null
        : Response(requestOptions: opts, data: body, statusCode: 422),
  );
}

void main() {
  test('apiErrorOf extracts the structured envelope', () {
    final e = _dioWith({
      'error': {'code': 'INVENTORY_INSUFFICIENT_STOCK', 'message': 'Not enough stock'}
    });
    final err = apiErrorOf(e);
    expect(err, isNotNull);
    expect(err!.code, 'INVENTORY_INSUFFICIENT_STOCK');
    expect(apiErrorCode(e), 'INVENTORY_INSUFFICIENT_STOCK');
  });

  test('a void refused because a sold gift card was spent says what to do instead', () {
    final e = _dioWith({
      'error': {'code': 'ORDER_GIFT_CARD_SPENT', 'message': ''}
    });
    expect(friendlyError(e), contains('Take the goods back as a return'));
  });

  test('friendlyError prefers the server message', () {
    final e = _dioWith({
      'error': {'code': 'X', 'message': 'Not enough stock'}
    });
    expect(friendlyError(e, fallback: 'fallback'), 'Not enough stock');
  });

  test('friendlyError gives a network line for connection errors', () {
    final e = _dioWith(null, type: DioExceptionType.connectionError);
    expect(friendlyError(e), contains("reach the server"));
  });

  test('friendlyError falls back for a non-Dio error', () {
    expect(friendlyError(Exception('boom'), fallback: 'try again'), 'try again');
    expect(apiErrorOf(Exception('boom')), isNull);
  });
}
