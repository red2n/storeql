// Central handling for the backend's structured error envelope.
//
// Every StoreQL endpoint returns `{ data, error: { code, message }, meta }` and
// uses stable machine codes (e.g. `INVENTORY_INSUFFICIENT_STOCK`) with a
// human-safe `message`. Screens used to string-match the HTTP status
// (`e.toString().contains('404')`) and throw the real contract away; these
// helpers read it instead so the UI can show the server's message and branch on
// the code.

import 'package:dio/dio.dart';

import 'api_response.dart';

/// The structured `{ code, message }` the backend returned for [error], or null
/// when the failure carries no envelope (network error, non-Dio throw).
ApiError? apiErrorOf(Object error) {
  if (error is DioException) {
    final data = error.response?.data;
    if (data is Map && data['error'] is Map) {
      return ApiError.fromJson(Map<String, dynamic>.from(data['error'] as Map));
    }
    // RFC 9457 problem details without the legacy member: the stable code and
    // the detail written for a person sit at the top level.
    if (data is Map && data['code'] is String) {
      return ApiError.fromJson(<String, dynamic>{
        'code': data['code'],
        'message': data['detail'] ?? data['title'] ?? '',
        if (data['details'] is List) 'details': data['details'],
      });
    }
  }
  return null;
}

/// The stable machine error code (e.g. `INVENTORY_INSUFFICIENT_STOCK`), or null.
String? apiErrorCode(Object error) => apiErrorOf(error)?.code;

/// Codes whose server text is written for a developer, so the app's own
/// sentence replaces it wherever the code arrives, whatever the server said.
///
/// Two kinds sit here. A GTIN and an id are the developer's vocabulary. The
/// access refusals are the checks' own: the server says "Caller is not assigned
/// to this store" or "a limit that applies to the whole business is set by a
/// caller held to no store", which names the check that said no, where a person
/// at the till needs to know who may do this. The sentence is the same
/// wherever the refusal arrives, so staff learn it once.
const Map<String, String> _plainWords = {
  // The server's line names its check ("Request validation failed"); the person needs to know
  // what to do. A screen that can point at the field does so itself.
  'VALIDATION_FAILED': 'Please check the fields and try again.',
  'PRODUCT_BARCODE_INVALID':
      "That barcode's check digit is wrong. Check the digits and try again.",
  'PRICING_PROPOSAL_STALE':
      'The competitor price behind this proposal is out of date. '
          'Record a fresh sighting and run the rule again.',
  // Access and permission refusals: a store that is not the caller's, a setting
  // or a record that belongs to the whole business, a right the caller's role
  // lacks, and the steps only an owner takes.
  'STORE_ACCESS_DENIED': 'That is not one of your stores.',
  // Said of a read as well as a change: iam-svc refuses the security trail, a
  // read, with it.
  'BUSINESS_WIDE_ONLY': 'Only an owner or a head-office manager can do this.',
  // The server names the permission by its key ("This action needs the
  // stock.transfer permission"), which is the developer's vocabulary.
  'PERMISSION_DENIED': 'Your role does not allow this. Ask an owner or a manager.',
  'TENANT_ACCESS_DENIED': 'Only the owner of the business changes this.',
  'STAFF_OWNER_TIER_OWNER_ONLY': 'Only an owner of the business can make another owner.',
  'ROLE_STAFF_MANAGE_OWNER_ONLY':
      'Only an owner of the business can give a role the right to manage staff.',
  'STAFF_BUSINESS_WIDE_OWNER_ONLY':
      'Only an owner of the business gives or removes a head-office assignment.',
  'PURCHASE_PO_HAS_NO_LINES':
      'Add at least one line to this order before you submit it.',
  'LOYALTY_INSUFFICIENT_POINTS':
      'This customer does not have that many points to redeem.',
  // True wherever it arrives: every form that sends a country the services
  // check (a store, onboarding and a new business on the platform console, a
  // supplier, a customer's VAT registration) picks it from CountryField, the
  // list of every country.
  'COUNTRY_INVALID': 'Choose a country from the list.',
  'POS_SESSION_NOT_YOURS': 'Only a manager ends another person\'s session.',
  // Card machines (07.16). payment-svc's text names its own steps (a tender
  // that names an attempt, a settle); at the till a person needs to know what
  // happened to the money and what to do next.
  'TERMINAL_UNSETTLED_APPROVAL':
      'This card machine has an earlier card payment to settle, so it cannot '
          'take another card yet.',
  'TERMINAL_REQUEST_IN_FLIGHT':
      'The card machine is still busy with this payment. Wait a moment, then '
          'try again.',
  'TERMINAL_NOT_TIMED_OUT':
      'The card machine has answered that payment, so there is nothing to '
          'record for it.',
  'TERMINAL_ATTEMPT_ALREADY_DECIDED':
      'Someone has already recorded what the card machine shows for that '
          'payment.',
  'TERMINAL_OUTCOME_INVALID':
      'Say whether the card machine shows the payment approved or nothing '
          'taken.',
  'TERMINAL_REFUND_TOO_LARGE':
      'That is more than is still on the card from that payment.',
  'TERMINAL_REFUND_UNDECIDED':
      'The last try at putting this money back on the card did not answer. '
          'Record what the card machine shows for it first.',
  'TERMINAL_NOT_APPROVED':
      'That card payment took no money, or nobody has recorded yet what the '
          'card machine shows for it.',
  'TERMINAL_NOT_A_SALE':
      'That is money being put back on a card, not a card payment.',
  'TERMINAL_ATTEMPT_NOT_FOUND': 'That card payment could not be found.',
  'TERMINAL_ATTEMPT_ALREADY_RECORDED':
      'That card payment is already recorded on its sale.',
  'TERMINAL_ATTEMPT_REFUNDED':
      'Some of that card payment has gone back on the card, so it cannot be '
          'recorded on the sale.',
  'TERMINAL_AMOUNT_MISMATCH':
      'That is not the amount the card machine took. Record exactly what it '
          'took.',
  'TERMINAL_ATTEMPT_OTHER_ORDER':
      'That card payment was taken for another sale.',
  'TERMINAL_WRONG_STORE': 'That card machine belongs to another store.',
  'TERMINAL_RETIRED':
      'That card machine has been retired. Use another card machine, or '
          'another tender.',
  'TERMINAL_NOT_FOUND': 'That card machine is not one of this business\'s.',
  'TERMINAL_AMOUNT_INVALID':
      'The card machine cannot take that amount in this currency.',
  'TERMINAL_VENDOR_UNAVAILABLE':
      'That card machine cannot be reached from this business yet.',
  'TERMINAL_CARD_DATA_NOT_ACCEPTED':
      'Card numbers are never typed into the till: the card machine reads the '
          'card.',
  'PAYMENT_ORDER_GIVEN_UP':
      'That sale has been cancelled or voided, so no card is taken or recorded '
          'for it. Anything a card machine took for it goes back on the card.',
  'PAYMENT_REFUND_VIA_TERMINAL':
      'That card payment was taken on a card machine, so the money goes back '
          'on the card through the card machine.',
  'CARD_REFUND_DUE_SETTLED':
      'That money has already gone back on the card, or been settled.',
};

/// Codes with words for when the server sent none of its own (or only the
/// generic line), so a person never reads a code or a vague failure. A server
/// message that says more is kept: these are the refusals whose own text names
/// the particulars the sentence cannot (which tier a role stands on, which
/// state a zone is in).
///
/// `ROLE_EXCEEDS_CALLER` is the one permission refusal that stays here. Its
/// server text lists the permissions the role holds beyond the caller's own,
/// in the same keys the Roles screen prints beside each role, and that list is
/// what tells the caller what to take off. Every other access refusal is in
/// [_plainWords].
const Map<String, String> _fallbackWords = {
  'PRODUCT_SKU_DUPLICATE': 'Another item in this business already has that SKU.',
  'PRODUCT_BARCODE_DUPLICATE':
      'Another item in this business already has that barcode.',
  'PRODUCT_CATEGORY_CYCLE':
      'A category cannot be placed under itself or one of its own subcategories.',
  'ROLE_EXCEEDS_CALLER':
      'That role holds permissions you do not hold yourself, so you cannot give it.',
  'RECALL_NOTICE_ORDER_MISMATCH': 'That recall notice belongs to a different sale.',
  'RECALL_NOTICE_RESOLVED': 'That recall notice has already been settled.',
  'PARKED_SALE_NOT_OPEN': 'That held sale was already picked up at another till.',
  'PARKED_SALE_NOT_FOUND': 'That held sale is no longer there.',
  'STAFF_BUSINESS_WIDE_TIER':
      'A head-office assignment is for a manager, or a role of your own that stands on the manager tier.',
  'STAFF_STORE_AND_BUSINESS_WIDE':
      'Choose a store or the whole business, not both.',
  'STAFF_STORE_REQUIRED': 'Choose a store, or the whole business.',
  'GIFT_CARD_NEEDS_SALE':
      'A gift card is sold at the till. An owner or a manager gives or reloads one by hand.',
  'GIFT_CARD_REASON_REQUIRED': 'Say why the card is given.',
  'ORDER_GIFT_CARD_INSTORE_ONLY': 'A gift card is sold in store, not online.',
  'ORDER_GIFT_CARD_SPENT':
      'A gift card this sale loaded has been spent since, so the sale cannot be voided or cancelled. Take the goods back as a return instead.',
  'ORDER_PENDING_LIMIT_INVALID':
      'The unpaid hold is a whole number of hours, at least 1.',
  'ORDER_PRICE_WAIT_INVALID':
      'A price wait is at least 1 minute, and the cancel wait is not shorter than the one that tells a manager.',
  'ZONE_STATUS_INVALID':
      'A zone is active, out of service or retired.',
  'ZONE_STATUS_CHANGED':
      'Someone changed that zone at the same moment. Reload and try again.',
  'Z_REPORT_SESSIONS_OPEN':
      'A till is still open at this store that day. Close it before settling the day.',
  'Z_REPORT_CORRECTION_REASON_REQUIRED': 'Say why the day is being corrected.',
  'Z_REPORT_NOT_LATEST':
      'A newer version of that day already exists. Open it and correct that one.',
  'Z_REPORT_NOT_FOUND': 'There is no report for that day.',
  'POS_SESSION_REASON_REQUIRED': 'Say why the session is being ended.',
  'POS_SESSION_NOT_FOUND': 'That session is no longer open.',
  'GIFT_CARD_TOO_MANY': 'A sale can carry at most 20 gift cards.',
  'GIFT_CARD_AMOUNT_INVALID': 'That gift card amount is not one this currency can pay.',
  'GIFT_CARD_NOT_FOUND': 'No gift card with that code.',
  'GIFT_CARD_NOT_ACTIVE': 'That gift card is not active, so it cannot be topped up.',
  'GIFT_CARD_CURRENCY_MISMATCH': "That gift card is in another currency than this sale.",
};

const String _genericServerLine = 'An unexpected error occurred.';

/// A user-facing message for any thrown [error]. Takes the app's own sentence
/// for a code in [_plainWords] first, whatever the server said; then the
/// backend's structured `message` (already written to be safe to show), or the
/// words in [_fallbackWords] when it said nothing; then a friendly network-error
/// line, then [fallback]. Never surfaces a raw `DioException`/stack string.
String friendlyError(
  Object error, {
  String fallback = 'Something went wrong. Please try again.',
}) {
  final apiErr = apiErrorOf(error);
  if (apiErr != null) {
    final plain = _plainWords[apiErr.code];
    if (plain != null) return plain;
    final said = apiErr.message.trim();
    final saidNothing =
        said.isEmpty || said == _genericServerLine || said == apiErr.code;
    if (saidNothing) {
      final words = _fallbackWords[apiErr.code];
      if (words != null) return words;
    }
    if (said.isNotEmpty) return apiErr.message;
  }
  if (error is DioException) {
    switch (error.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.connectionError:
        return "Can't reach the server. Check your connection and try again.";
      default:
        break;
    }
  }
  return fallback;
}
