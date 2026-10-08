/// Words for the codes the services send (`PARTIALLY_FULFILLED`, `ONLINE`),
/// and the tone each status is shown in. A code is data; a person reads words.
///
/// Every screen that shows an order's status or channel uses the maps here, so
/// an order reads the same on the dashboard, in Orders and in the shopper's
/// order history. A code this file does not know yet still reads as words
/// ([humanizeCode]) and in the neutral tone, never as a raw constant.
library;

/// What a status means to the person looking at it. `StatusBadge` turns each
/// tone into the theme's matching container colours.
enum StatusTone {
  /// Closed, inactive, or nothing to do: cancelled, refunded, archived.
  neutral,

  /// Waiting on someone or in progress: pending, awaiting a price, sent.
  info,

  /// Done and good: fulfilled, delivered, paid, active.
  success,

  /// Needs a look: part fulfilled, due soon, low stock, suspended.
  warning,

  /// Failed or blocked: rejected, overdue, recalled.
  error,

  /// Worth pointing out, not a state: a promotion, *New*.
  accent,
}

/// Words that stay in capitals when a code is turned into words.
const _acronyms = {
  'API', 'CSV', 'EAN', 'EU', 'GTIN', 'HMRC', 'ID', 'MFA', 'MTD', 'PDF', //
  'PIN', 'POS', 'QR', 'SKU', 'SSO', 'UK', 'UPI', 'VAT', 'Z',
};

/// `PARTIALLY_FULFILLED` → `Partially fulfilled`, `HMRC_MTD` → `HMRC MTD`,
/// `store-credit` → `Store credit`. Blank in, blank out. The fallback for a
/// code no label map knows yet — a map with real words is always better.
String humanizeCode(String? code) {
  final words = (code ?? '')
      .trim()
      .split(RegExp(r'[_\s-]+'))
      .where((w) => w.isNotEmpty)
      .toList();
  if (words.isEmpty) return '';
  final out = <String>[];
  for (var i = 0; i < words.length; i++) {
    final upper = words[i].toUpperCase();
    if (_acronyms.contains(upper)) {
      out.add(upper);
    } else {
      final lower = words[i].toLowerCase();
      out.add(i == 0 ? lower[0].toUpperCase() + lower.substring(1) : lower);
    }
  }
  return out.join(' ');
}

/// An order's status in words — the same on every screen that shows one.
String orderStatusLabel(String? status) =>
    switch ((status ?? '').toUpperCase()) {
      'PENDING' || 'PLACED' => 'Pending',
      'AWAITING_PRICE' => 'Awaiting price',
      'CONFIRMED' => 'Confirmed',
      'READY' => 'Ready',
      'PARTIALLY_FULFILLED' => 'Part fulfilled',
      'FULFILLED' => 'Fulfilled',
      'DELIVERED' => 'Delivered',
      'COMPLETED' => 'Completed',
      'PAID' => 'Paid',
      'PARTIALLY_REFUNDED' => 'Part refunded',
      'REFUNDED' => 'Refunded',
      'CANCELLED' => 'Cancelled',
      'VOIDED' => 'Voided',
      _ => humanizeCode(status),
    };

/// The tone an order's status is shown in: blue while someone has to act on
/// it, amber when it only partly went through, green when it is done, grey
/// once it is closed without a sale.
StatusTone orderStatusTone(String? status) =>
    switch ((status ?? '').toUpperCase()) {
      'PENDING' || 'PLACED' || 'AWAITING_PRICE' || 'CONFIRMED' || 'READY' =>
        StatusTone.info,
      'PARTIALLY_FULFILLED' || 'PARTIALLY_REFUNDED' => StatusTone.warning,
      'FULFILLED' || 'DELIVERED' || 'COMPLETED' || 'PAID' => StatusTone.success,
      _ => StatusTone.neutral,
    };

/// Where an order was taken, in words: `POS` is the till.
String channelLabel(String? channel) =>
    switch ((channel ?? '').toUpperCase()) {
      'ONLINE' => 'Online',
      'POS' => 'Till',
      'PHONE' => 'Phone',
      _ => humanizeCode(channel),
    };

/// The material statuses a batch can be in, in the order a person picks from:
/// the five inventory-svc accepts and its database holds a batch to.
const batchMaterialStatuses = [
  'AVAILABLE',
  'QUARANTINE',
  'INSPECTION',
  'DAMAGED',
  'RECALLED',
];

/// A batch's material status in words — the same on the Batches badges, in
/// their filter and in the dialog that changes it.
String materialStatusLabel(String? status) =>
    switch ((status ?? '').toUpperCase()) {
      'AVAILABLE' => 'Available',
      'QUARANTINE' => 'In quarantine',
      'INSPECTION' => 'Under inspection',
      'DAMAGED' => 'Damaged',
      'RECALLED' => 'Recalled',
      _ => humanizeCode(status),
    };

/// Green when the batch can be sold, amber while it is held back, blue while
/// it waits to be checked, red once it is damaged or recalled; grey for
/// anything else.
StatusTone materialStatusTone(String? status) =>
    switch ((status ?? '').toUpperCase()) {
      'AVAILABLE' => StatusTone.success,
      'QUARANTINE' => StatusTone.warning,
      'INSPECTION' => StatusTone.info,
      'DAMAGED' || 'RECALLED' => StatusTone.error,
      _ => StatusTone.neutral,
    };

/// A store's till-phone choices (phone-at-the-till), in the order a person
/// picks from on the store form.
const tillPhoneChoices = ['REQUIRED', 'OPTIONAL', 'OFF'];

/// What a store's till-phone choice asks the cashier for, in words — the same
/// on the store form wherever the choice is shown or picked from.
String tillPhoneLabel(String? choice) => switch ((choice ?? '').toUpperCase()) {
      'REQUIRED' => 'Required',
      'OFF' => "Don't ask",
      _ => 'Optional',
    };

/// The conditions a returned item can come back in (return-controls), in the
/// order a person picks from. Nothing is preselected: the person looks.
const returnConditions = ['SEALED', 'OPENED', 'DAMAGED', 'FAULTY'];

/// A returned item's condition in words, with where it goes for the ones the
/// shelf cares about — see [returnConditionHint].
String returnConditionLabel(String? condition) =>
    switch ((condition ?? '').toUpperCase()) {
      'SEALED' => 'Sealed',
      'OPENED' => 'Opened',
      'DAMAGED' => 'Damaged',
      'FAULTY' => 'Faulty',
      _ => humanizeCode(condition),
    };

/// Where a returned item goes for its condition, in words.
String returnConditionHint(String? condition) =>
    switch ((condition ?? '').toUpperCase()) {
      'SEALED' => 'back on sale',
      'OPENED' => 'checked before resale',
      'DAMAGED' || 'FAULTY' => 'off sale',
      _ => '',
    };

/// Why a return needed a manager (order-svc's reason codes), in words.
String returnReasonLabel(String? code) =>
    switch ((code ?? '').toUpperCase()) {
      'WINDOW' => "Past the business's return window",
      'CEILING' => "Over the cashier's refund limit",
      'FAULTY_PAST_WINDOW' => 'Faulty goods past the window',
      'NO_RECEIPT' => 'No receipt to find the sale by',
      _ => humanizeCode(code),
    };

/// How a return is paid back, in words.
String refundMethodLabel(String? method) =>
    switch ((method ?? '').toUpperCase()) {
      'ORIGINAL' => 'Back to how they paid',
      'STORE_CREDIT' => 'Store credit',
      'GIFT_CARD' => 'Gift card',
      _ => humanizeCode(method),
    };

/// What kind of return a record is (return-controls slice 2): an exchange
/// settles against a new basket, a no-receipt return has no sale behind it.
String returnKindLabel(String? kind) => switch ((kind ?? '').toUpperCase()) {
      'EXCHANGE' => 'Exchange',
      'NO_RECEIPT' => 'Return without a receipt',
      'REFUND' || 'RETURN' => 'Return',
      _ => humanizeCode(kind),
    };

/// A refusal of a return, an exchange or a gift-card charge at the till, in
/// words the cashier can act on. Null for a code this does not know, so the
/// caller shows the server's own message.
String? returnRefusalLabel(String? code) =>
    switch ((code ?? '').toUpperCase()) {
      'ORDER_RETURN_CONDITION_REQUIRED' ||
      'ORDER_RETURN_CONDITION_INVALID' =>
        'Say what condition each returned item is in.',
      'ORDER_RETURN_STORE_CREDIT_NEEDS_CUSTOMER' =>
        'Store credit needs a customer. Choose one, or pay it back another way.',
      'ORDER_RECEIPT_NOT_FOUND' =>
        'No sale has that receipt number at this store. Check it and try again.',
      'ORDER_RECEIPT_AMBIGUOUS' =>
        'More than one sale matches that number. Type the full receipt number.',
      'ORDER_NO_RECEIPT_RETURNS_OFF' =>
        'This business does not take returns without a receipt.',
      'ORDER_NO_RECEIPT_OVER_CEILING' =>
        "That is over the most this business gives back without a receipt.",
      'ORDER_NO_RECEIPT_METHOD_INVALID' =>
        'Without a receipt the refund goes to store credit or a gift card only.',
      'ORDER_RETURN_NEEDS_MANAGER' => 'A manager must take this one.',
      'GIFT_CARD_NOT_FOUND' => 'No gift card has that code.',
      'GIFT_CARD_INSUFFICIENT_BALANCE' =>
        "The gift card doesn't have enough on it. Take a smaller amount from it, or another payment.",
      'GIFT_CARD_EXPIRED' => 'That gift card has expired.',
      'GIFT_CARD_CURRENCY_MISMATCH' =>
        "That gift card is in a different currency and can't be used here.",
      'GIFT_CARD_NOT_ACTIVE' => 'That gift card is not active.',
      _ => null,
    };

/// Where a product recall or withdrawal came from, in the order a person picks
/// from: no country's regulator is named, `REGULATOR` is whichever one applies.
const recallSourceChoices = [
  'REGULATOR',
  'MANUFACTURER',
  'SUPPLIER',
  'INTERNAL',
  'OTHER',
];

/// A recall's source in words. `FSA` and `FSS` are no longer offered but stay
/// readable on the recalls already opened under them.
String recallSourceLabel(String? source) =>
    switch ((source ?? '').toUpperCase()) {
      'REGULATOR' => 'A regulator',
      'MANUFACTURER' => 'The manufacturer',
      'SUPPLIER' => 'The supplier',
      'INTERNAL' => 'Our own check',
      'OTHER' => 'Other',
      'FSA' => 'Food Standards Agency',
      'FSS' => 'Food Standards Scotland',
      _ => humanizeCode(source),
    };

/// What each stable error code the gateway and the services answer with means to
/// a person reading the system-health failures (their `code`). Only the codes
/// a refused or failed request carries: a normal business answer (a 404, a 409)
/// is not a failure and never reaches that list.
const _failureCodeWords = {
  'UNAUTHORIZED': 'The sign-in was missing or had expired',
  'TOKEN_LOCKED': 'Too many invalid sign-ins from one address, so it was paused',
  'LOGIN_LOCKED': 'Too many failed sign-in attempts, so sign-in was paused',
  'RATE_LIMITED': 'Too many requests too quickly',
  'PLAN_RATE_LIMIT_REACHED': "The business reached its plan's requests-a-minute allowance",
  'PAYLOAD_TOO_LARGE': 'The request was too large',
  'TENANT_INACTIVE': 'The business is switched off',
  'API_KEY_ROUTE_FORBIDDEN': "An API key tried something only a person's sign-in may do",
  'API_KEY_CHECK_UNAVAILABLE': 'An API key could not be checked just now',
  'AUTH_KEYS_UNAVAILABLE': 'Sign-ins could not be verified just now',
  'MFA_ENROLMENT_REQUIRED': 'The sign-in has to set up a second step first',
  'UPSTREAM_UNAVAILABLE': 'A service could not be reached',
  'UPSTREAM_TIMEOUT': 'A service took too long to answer',
  'UPSTREAM_ERROR': 'A service answered with an error',
  'UPSTREAM_CIRCUIT_OPEN': 'A service is being left alone after repeated failures',
  'INTERNAL_ERROR': 'The service hit an unexpected error',
  'PERMISSION_DENIED': "The person's role does not allow it",
  'STORE_ACCESS_DENIED': 'The person tried a store that is not theirs',
  'BUSINESS_WIDE_ONLY': 'Only people not held to particular stores may do this',
};

/// Why a request failed, in words: its stable [code] when the page knows it,
/// else what its HTTP [status] means. The raw code and status are shown beside
/// it, small, for the person quoting it; they are never the words themselves.
String failureReason(int? status, String? code) {
  final said = _failureCodeWords[(code ?? '').toUpperCase()];
  if (said != null) return said;
  return switch (status) {
    401 => 'The sign-in was refused',
    403 => 'The request was not allowed',
    413 => 'The request was too large',
    429 => 'Too many requests',
    500 => 'The service hit an error',
    502 => 'A service could not be reached',
    503 => 'A service was unavailable',
    504 => 'A service took too long to answer',
    final s? when s >= 500 => 'A service failed',
    _ => 'The request failed',
  };
}

/// What each part of the platform does, for the system-health areas: the gateway
/// names a request's area by the service it went to (`order-svc`), a person
/// thinks of what it is for. A service this does not know reads as its own name
/// in words.
const _routeGroupWords = {
  'iam': 'Sign-in and accounts',
  'tenant': 'Business and stores',
  'product': 'Products',
  'inventory': 'Stock',
  'pricing': 'Prices',
  'cart': 'Baskets',
  'order': 'Orders',
  'payment': 'Payments',
  'purchase': 'Purchasing',
  'customer': 'Customers',
  'notification': 'Messages',
  'reporting': 'Reports',
};

/// A request area in words: `order-svc` and `order` both read *Orders*.
String routeGroupLabel(String? group) {
  final name = (group ?? '').trim().toLowerCase().replaceFirst(RegExp(r'-svc$'), '');
  if (name.isEmpty) return 'Other';
  return _routeGroupWords[name] ?? humanizeCode(name);
}

/// Whether a failed request went wrong (*Failed*: the system could not answer)
/// or was turned away (*Refused*: a bad sign-in, a missing right, a limit).
String failureKindLabel(int? status) => switch (status) {
      401 || 403 || 413 || 429 => 'Refused',
      _ => 'Failed',
    };

/// The tone of [failureKindLabel]: red when the system failed, amber when it
/// refused.
StatusTone failureKindTone(int? status) => switch (status) {
      401 || 403 || 413 || 429 => StatusTone.warning,
      _ => StatusTone.error,
    };
