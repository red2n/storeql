import 'package:dio/dio.dart';

/// A POS sale captured at the till but not yet accepted by the server.
///
/// A till that stops selling when the network drops is not a POS. When the
/// server is unreachable the sale is completed locally — the customer pays, the
/// receipt prints — and the writes it still owes the server are held here until
/// they can be replayed.
///
/// Replay is safe because every write in a sale is idempotent on a key the till
/// chooses and stores *with the sale*, not one generated per attempt:
///   * `POST /orders`   replays on `orders.idempotency_key` — order-svc returns
///                      the original order for a duplicate key.
///   * `POST /payments` replays on `payment_tenders.idempotency_key` — the
///                      original tender is returned rather than charging again.
///   * gift-card redeem replays on (card, order) — see V13, added for this.
/// So a step whose response was lost (the ambiguous case, and the common one
/// when a network drops) can simply be sent again.
class OfflineSale {
  /// Local id, and the base every Idempotency-Key in this sale is derived from.
  /// Generated once at capture and persisted, so every replay presents the same
  /// keys — this is the whole reason replay is safe.
  final String id;
  final DateTime capturedAt;
  final String storeId;
  final String currency;

  /// Body for `POST /order-svc/orders`.
  final Map<String, dynamic> orderRequest;

  /// The tenders to record against the order, in the order the cashier took them.
  final List<OfflineTender> tenders;

  /// Set once the order has been accepted. Held so a later attempt resumes at the
  /// tenders instead of re-posting the order (the replay would be harmless, just
  /// wasted) — and so the pending list can show the cashier a real order number.
  final String? orderId;

  /// Display-only, captured up front so the pending list never needs the network.
  final double total;
  final int itemCount;

  /// Whether the POS transaction journal entry has been written. Journalling is
  /// the last step of a sale and is idempotent on the order id, so it replays
  /// with everything else.
  final bool posLogDone;

  final int attempts;
  final String? lastError;
  final OfflineSaleStatus status;

  const OfflineSale({
    required this.id,
    required this.capturedAt,
    required this.storeId,
    required this.currency,
    required this.orderRequest,
    required this.tenders,
    required this.total,
    required this.itemCount,
    this.orderId,
    this.posLogDone = false,
    this.attempts = 0,
    this.lastError,
    this.status = OfflineSaleStatus.pending,
  });

  /// Every write in this sale has landed.
  bool get isComplete =>
      orderId != null && tenders.every((t) => t.isComplete) && posLogDone;

  /// Short human reference, printed on the offline receipt and shown in the
  /// pending list, so a cashier holding a piece of paper can find the sale.
  /// The id's random tail, in capitals like every order reference: a real id
  /// is a UUIDv7, whose tail is lower-case hex (`…2b7c41ae` reads `7C41AE`).
  String get reference =>
      (id.length > 6 ? id.substring(id.length - 6) : id).toUpperCase();

  /// Record that one step of tender [i] has landed.
  OfflineSale markTender(int i, {bool? tenderDone, bool? redeemDone}) {
    final updated = [...tenders];
    updated[i] = updated[i].copyWith(tenderDone: tenderDone, redeemDone: redeemDone);
    return copyWith(tenders: updated);
  }

  OfflineSale copyWith({
    String? orderId,
    List<OfflineTender>? tenders,
    bool? posLogDone,
    int? attempts,
    String? lastError,
    bool clearError = false,
    OfflineSaleStatus? status,
  }) =>
      OfflineSale(
        id: id,
        capturedAt: capturedAt,
        storeId: storeId,
        currency: currency,
        orderRequest: orderRequest,
        tenders: tenders ?? this.tenders,
        total: total,
        itemCount: itemCount,
        orderId: orderId ?? this.orderId,
        posLogDone: posLogDone ?? this.posLogDone,
        attempts: attempts ?? this.attempts,
        lastError: clearError ? null : (lastError ?? this.lastError),
        status: status ?? this.status,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'capturedAt': capturedAt.toIso8601String(),
        'storeId': storeId,
        'currency': currency,
        'orderRequest': orderRequest,
        'tenders': tenders.map((t) => t.toJson()).toList(),
        'orderId': orderId,
        'posLogDone': posLogDone,
        'total': total,
        'itemCount': itemCount,
        'attempts': attempts,
        'lastError': lastError,
        'status': status.name,
      };

  factory OfflineSale.fromJson(Map<String, dynamic> j) => OfflineSale(
        id: j['id'] as String,
        capturedAt:
            DateTime.tryParse(j['capturedAt'] as String? ?? '') ?? DateTime.now(),
        storeId: j['storeId'] as String? ?? '',
        currency: j['currency'] as String? ?? '',
        orderRequest: Map<String, dynamic>.from(j['orderRequest'] as Map),
        tenders: ((j['tenders'] as List?) ?? [])
            .map((e) => OfflineTender.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
        orderId: j['orderId'] as String?,
        // Defaulted rather than required: a sale queued by a build before
        // journalling existed must still replay, and re-journalling is a no-op
        // server-side anyway.
        posLogDone: j['posLogDone'] as bool? ?? false,
        total: (j['total'] as num?)?.toDouble() ?? 0,
        itemCount: (j['itemCount'] as num?)?.toInt() ?? 0,
        attempts: (j['attempts'] as num?)?.toInt() ?? 0,
        lastError: j['lastError'] as String?,
        status: OfflineSaleStatus.values.firstWhere(
          (s) => s.name == j['status'],
          orElse: () => OfflineSaleStatus.pending,
        ),
      );
}

enum OfflineSaleStatus {
  /// Waiting for the network; will be retried automatically.
  pending,

  /// The server rejected it in a way retrying cannot fix. Needs a human — it is
  /// held rather than dropped, because the customer has already paid.
  failed,
}

/// One tender (part-payment) owed to the server, plus any gift-card redemption
/// that goes with it.
class OfflineTender {
  /// Body for `POST /payment-svc/payments`, minus `orderId` which is filled in at
  /// replay once the order exists.
  final Map<String, dynamic> body;

  /// Set for a GIFT_CARD tender: the card to redeem after the tender is recorded.
  final String? giftCardCode;
  final double amount;

  final bool tenderDone;
  final bool redeemDone;

  const OfflineTender({
    required this.body,
    required this.amount,
    this.giftCardCode,
    this.tenderDone = false,
    this.redeemDone = false,
  });

  bool get isComplete => tenderDone && (giftCardCode == null || redeemDone);

  OfflineTender copyWith({bool? tenderDone, bool? redeemDone}) => OfflineTender(
        body: body,
        amount: amount,
        giftCardCode: giftCardCode,
        tenderDone: tenderDone ?? this.tenderDone,
        redeemDone: redeemDone ?? this.redeemDone,
      );

  Map<String, dynamic> toJson() => {
        'body': body,
        'amount': amount,
        'giftCardCode': giftCardCode,
        'tenderDone': tenderDone,
        'redeemDone': redeemDone,
      };

  factory OfflineTender.fromJson(Map<String, dynamic> j) => OfflineTender(
        body: Map<String, dynamic>.from(j['body'] as Map),
        amount: (j['amount'] as num?)?.toDouble() ?? 0,
        giftCardCode: j['giftCardCode'] as String?,
        tenderDone: j['tenderDone'] as bool? ?? false,
        redeemDone: j['redeemDone'] as bool? ?? false,
      );
}

/// Whether [error] means "the server was not reached", as opposed to "the server
/// answered and said no".
///
/// Only the first kind may be queued: a sale the server has *rejected* would be
/// rejected identically on every replay, and holding it would tell the cashier a
/// lie. `receiveTimeout` counts as unreached even though the request may well
/// have arrived — that ambiguity is precisely what the idempotency keys resolve.
bool isOfflineError(Object error) {
  if (error is! DioException) return false;
  switch (error.type) {
    case DioExceptionType.connectionError:
    case DioExceptionType.connectionTimeout:
    case DioExceptionType.sendTimeout:
    case DioExceptionType.receiveTimeout:
      return true;
    case DioExceptionType.unknown:
      // dio reports a dead socket / failed DNS as `unknown` wrapping the
      // platform error, with no response attached.
      return error.response == null;
    default:
      return false;
  }
}

/// Whether a server *response* to a replay is permanent — retrying will not help,
/// so the sale is parked for a human instead of looping forever.
///
/// 401 is deliberately retryable: the till's token expires while it is offline,
/// and the auth interceptor refreshes on the next attempt. 409 is retryable
/// because payment-svc returns `IDEMPOTENCY_CONFLICT` for a concurrent same-key
/// request and explicitly asks the caller to retry into the replay path.
bool isPermanentRejection(Object error) {
  if (error is! DioException) return false;
  final status = error.response?.statusCode;
  if (status == null) return false;
  if (status == 401 || status == 408 || status == 409 || status == 429) return false;
  return status >= 400 && status < 500;
}
