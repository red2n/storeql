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

/// The material statuses a batch can be in, in the order a person picks from.
const batchMaterialStatuses = ['AVAILABLE', 'QUARANTINE', 'HOLD', 'REJECTED'];

/// A batch's material status in words — the same on the Batches badges, in
/// their filter and in the dialog that changes it.
String materialStatusLabel(String? status) =>
    switch ((status ?? '').toUpperCase()) {
      'AVAILABLE' => 'Available',
      'QUARANTINE' => 'In quarantine',
      'HOLD' => 'On hold',
      'REJECTED' => 'Rejected',
      _ => humanizeCode(status),
    };

/// Green when the batch can be sold, amber while it is held back, red once it
/// is rejected; grey for anything else.
StatusTone materialStatusTone(String? status) =>
    switch ((status ?? '').toUpperCase()) {
      'AVAILABLE' => StatusTone.success,
      'QUARANTINE' || 'HOLD' => StatusTone.warning,
      'REJECTED' => StatusTone.error,
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
