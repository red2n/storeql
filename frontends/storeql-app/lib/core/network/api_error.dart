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

/// Codes whose server text is written for a developer (a GTIN, an id), so the
/// plain sentence below replaces it wherever the code arrives.
const Map<String, String> _plainWords = {
  'PRODUCT_BARCODE_INVALID':
      "That barcode's check digit is wrong. Check the digits and try again.",
  'PRICING_PROPOSAL_STALE':
      'The competitor price behind this proposal is out of date. '
          'Record a fresh sighting and run the rule again.',
};

/// Codes with words for when the server sent none of its own (or only the
/// generic line), so a person never reads a code or a vague failure. A server
/// message that says more (which store, which permission) is kept.
const Map<String, String> _fallbackWords = {
  'PRODUCT_SKU_DUPLICATE': 'Another item in this business already has that SKU.',
  'PRODUCT_BARCODE_DUPLICATE':
      'Another item in this business already has that barcode.',
  'PRODUCT_CATEGORY_CYCLE':
      'A category cannot be placed under itself or one of its own subcategories.',
  'STAFF_OWNER_TIER_OWNER_ONLY': 'Only an owner of the business can make another owner.',
  'ROLE_EXCEEDS_CALLER':
      'That role holds permissions you do not hold yourself, so you cannot give it.',
  'ROLE_STAFF_MANAGE_OWNER_ONLY':
      'Only an owner of the business can give a role the right to manage staff.',
  'STORE_ACCESS_DENIED': 'That is not one of your stores.',
  'RECALL_NOTICE_ORDER_MISMATCH': 'That recall notice belongs to a different sale.',
  'RECALL_NOTICE_RESOLVED': 'That recall notice has already been settled.',
  'PARKED_SALE_NOT_OPEN': 'That held sale was already picked up at another till.',
  'PARKED_SALE_NOT_FOUND': 'That held sale is no longer there.',
  'BUSINESS_WIDE_ONLY': 'Only an owner or a head-office manager changes this.',
  'STAFF_BUSINESS_WIDE_OWNER_ONLY':
      'Only an owner of the business gives or removes a head-office assignment.',
  'STAFF_BUSINESS_WIDE_TIER':
      'A head-office assignment is for a manager, or a role of your own that stands on the manager tier.',
  'STAFF_STORE_AND_BUSINESS_WIDE':
      'Choose a store or the whole business, not both.',
  'STAFF_STORE_REQUIRED': 'Choose a store, or the whole business.',
};

const String _genericServerLine = 'An unexpected error occurred.';

/// A user-facing message for any thrown [error]. Prefers the backend's structured
/// `message` (already written to be safe to show), then a friendly network-error
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
