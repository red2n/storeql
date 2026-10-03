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

  test('an access refusal as the wire sends it shows the app\'s words, not the server\'s English', () {
    // Every service answers in RFC 9457 problem details, with the legacy `error`
    // member beside them: the detail is "Caller is not assigned to this store".
    final both = _dioWith({
      'type': 'urn:storeql:problem:STORE_ACCESS_DENIED',
      'title': 'Forbidden',
      'status': 403,
      'detail': 'Caller is not assigned to this store',
      'code': 'STORE_ACCESS_DENIED',
      'error': {'code': 'STORE_ACCESS_DENIED', 'message': 'Caller is not assigned to this store'},
    });
    expect(friendlyError(both), 'That is not one of your stores.');

    // And the problem document alone, with no legacy member.
    final problemOnly = _dioWith({
      'code': 'BUSINESS_WIDE_ONLY',
      'detail': 'a limit that applies to the whole business is set by a caller held to no store',
    });
    expect(friendlyError(problemOnly), 'Only an owner or a head-office manager can do this.');
  });

  test('the new refusals read in the app\'s words, whatever the server said', () {
    for (final (code, words) in [
      ('PURCHASE_PO_HAS_NO_LINES', 'Add at least one line to this order before you submit it.'),
      ('LOYALTY_INSUFFICIENT_POINTS', 'This customer does not have that many points to redeem.'),
      ('COUNTRY_INVALID', 'Choose a country from the list.'),
      ('BUSINESS_WIDE_ONLY', 'Only an owner or a head-office manager can do this.'),
    ]) {
      expect(friendlyError(_dioWith({'code': code, 'detail': 'server text'})), words, reason: code);
      expect(friendlyError(_dioWith({'code': code})), words, reason: code);
    }
  });

  test('a missing permission reads in words, never the permission\'s code', () {
    // common-web's TenantContext: "This action needs the stock.transfer permission".
    for (final said in [
      'This action needs the stock.transfer permission',
      'This action needs the sales.refund permission',
      '',
      'PERMISSION_DENIED',
    ]) {
      final shown = friendlyError(_dioWith({
        'type': 'urn:storeql:problem:PERMISSION_DENIED',
        'status': 403,
        'detail': said,
        'code': 'PERMISSION_DENIED',
        'error': {'code': 'PERMISSION_DENIED', 'message': said},
      }));
      expect(shown, 'Your role does not allow this. Ask an owner or a manager.', reason: said);
      expect(shown, isNot(contains('.transfer')), reason: said);
      expect(shown, isNot(contains('.refund')), reason: said);
      expect(shown, isNot(contains('_')), reason: said);
    }
  });

  test('the business-wide refusal is true of a read as well as a change', () {
    // iam-svc refuses the security trail, a read, with BUSINESS_WIDE_ONLY.
    final shown = friendlyError(_dioWith({
      'code': 'BUSINESS_WIDE_ONLY',
      'detail': 'The security trail is business-wide, so it needs a caller who is not held to stores',
    }));
    expect(shown, 'Only an owner or a head-office manager can do this.');
    expect(shown, isNot(contains('changes')));
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

  test('a request the server could not read field by field asks the person to check the fields', () {
    // The server's own line ("Request validation failed") is the developer's; the person
    // needs to know what to do.
    final e = DioException(
      requestOptions: RequestOptions(path: '/admin/stores'),
      response: Response(
        requestOptions: RequestOptions(path: '/admin/stores'),
        statusCode: 400,
        data: {
          'code': 'VALIDATION_FAILED',
          'detail': 'Request validation failed',
          'error': {'code': 'VALIDATION_FAILED', 'message': 'Request validation failed'},
        },
      ),
    );
    expect(friendlyError(e), 'Please check the fields and try again.');
  });
  test('every card machine refusal reads in words, never a code', () {
    for (final code in [
      'TERMINAL_UNSETTLED_APPROVAL',
      'TERMINAL_REQUEST_IN_FLIGHT',
      'TERMINAL_NOT_TIMED_OUT',
      'TERMINAL_ATTEMPT_ALREADY_DECIDED',
      'TERMINAL_OUTCOME_INVALID',
      'TERMINAL_REFUND_TOO_LARGE',
      'TERMINAL_REFUND_UNDECIDED',
      'TERMINAL_NOT_APPROVED',
      'TERMINAL_NOT_A_SALE',
      'TERMINAL_ATTEMPT_NOT_FOUND',
      'TERMINAL_ATTEMPT_ALREADY_RECORDED',
      'TERMINAL_ATTEMPT_REFUNDED',
      'TERMINAL_AMOUNT_MISMATCH',
      'TERMINAL_ATTEMPT_OTHER_ORDER',
      'TERMINAL_WRONG_STORE',
      'TERMINAL_RETIRED',
      'TERMINAL_NOT_FOUND',
      'TERMINAL_AMOUNT_INVALID',
      'TERMINAL_VENDOR_UNAVAILABLE',
      'TERMINAL_CARD_DATA_NOT_ACCEPTED',
      'PAYMENT_ORDER_GIVEN_UP',
      'PAYMENT_REFUND_VIA_TERMINAL',
      'CARD_REFUND_DUE_SETTLED',
    ]) {
      final words = friendlyError(_dioWith({
        'error': {'code': code, 'message': 'attemptId=0190;standing=UNDECIDED'},
      }));
      expect(words, isNot(contains('_')), reason: code);
      expect(words, isNot(contains('attemptId')), reason: code);
      expect(words, endsWith('.'), reason: code);
    }
    expect(
        friendlyError(_dioWith({
          'error': {'code': 'TERMINAL_UNSETTLED_APPROVAL', 'message': ''},
        })),
        contains('earlier card payment to settle'));
  });
}
