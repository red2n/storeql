import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../features/pos/pos_fiscal_receipt.dart';
import '../network/api_client.dart';
import '../storage/app_storage.dart';
import '../constants.dart';
import 'offline_sale.dart';

/// A sale taken offline that has since reached the server.
///
/// The offline receipt was printed without its legal number — the server had
/// not issued one, because it did not know about the sale. Once replay lands
/// the payment, order-svc numbers it, and the cashier holding that piece of
/// paper needs to find the number: this is where the queue leaves it.
class SyncedSale {
  final String id;
  final String orderId;
  final DateTime capturedAt;
  final DateTime syncedAt;
  final double total;
  final String currency;

  /// The legal receipt number, once looked up; null until then.
  final String? fiscalNumber;

  const SyncedSale({
    required this.id,
    required this.orderId,
    required this.capturedAt,
    required this.syncedAt,
    required this.total,
    required this.currency,
    this.fiscalNumber,
  });

  /// The same short reference the offline receipt was printed with
  /// ([OfflineSale.reference]): the id's tail, in capitals.
  String get reference =>
      (id.length > 6 ? id.substring(id.length - 6) : id).toUpperCase();

  SyncedSale withNumber(String? number) => SyncedSale(
        id: id,
        orderId: orderId,
        capturedAt: capturedAt,
        syncedAt: syncedAt,
        total: total,
        currency: currency,
        fiscalNumber: number ?? fiscalNumber,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'orderId': orderId,
        'capturedAt': capturedAt.toUtc().toIso8601String(),
        'syncedAt': syncedAt.toUtc().toIso8601String(),
        'total': total,
        'currency': currency,
        'fiscalNumber': fiscalNumber,
      };

  factory SyncedSale.fromJson(Map<String, dynamic> j) => SyncedSale(
        id: j['id'] as String,
        orderId: j['orderId'] as String? ?? '',
        capturedAt: DateTime.parse(j['capturedAt'] as String),
        syncedAt: DateTime.parse(j['syncedAt'] as String),
        total: (j['total'] as num?)?.toDouble() ?? 0,
        currency: j['currency'] as String? ?? '',
        fiscalNumber: j['fiscalNumber'] as String?,
      );
}

/// Sales replayed from the offline queue, newest first, capped at [keep].
final offlineSyncedProvider =
    StateNotifierProvider<SyncedSalesNotifier, List<SyncedSale>>(
        (ref) => SyncedSalesNotifier(ref));

/// The legal number for one synced sale: what is already known, or one
/// bounded ask of the server, remembered once it answers.
final syncedFiscalNumberProvider =
    FutureProvider.autoDispose.family<String?, String>(
        retry: (count, error) => null, (ref, saleId) async {
  final notifier = ref.watch(offlineSyncedProvider.notifier);
  return notifier.resolveNumber(saleId);
});

class SyncedSalesNotifier extends StateNotifier<List<SyncedSale>> {
  static const int keep = 50;

  final Ref _ref;
  final AppStorage _storage;
  late final Future<void> _ready;

  SyncedSalesNotifier(this._ref, {AppStorage storage = const AppStorage()})
      : _storage = storage,
        super(const []) {
    _ready = _restore();
  }

  Future<void> _restore() async {
    try {
      final raw = await _storage.read(key: StorageKeys.posOfflineSynced);
      if (raw == null || raw.isEmpty) return;
      state = (jsonDecode(raw) as List)
          .map((e) => SyncedSale.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
    } catch (_) {
      // A record of numbers already on the server; losing it costs a lookup,
      // never money. Nothing here may keep the till from opening.
    }
  }

  Future<void> _persist() async {
    try {
      await _storage.write(
        key: StorageKeys.posOfflineSynced,
        value: jsonEncode(state.map((s) => s.toJson()).toList()),
      );
    } catch (_) {
      // Same: best effort.
    }
  }

  /// Called by the queue once every write a sale owed has been accepted.
  Future<void> record(OfflineSale sale) async {
    await _ready;
    final orderId = sale.orderId;
    if (orderId == null || orderId.isEmpty) return;
    state = [
      SyncedSale(
        id: sale.id,
        orderId: orderId,
        capturedAt: sale.capturedAt,
        syncedAt: DateTime.now().toUtc(),
        total: sale.total,
        currency: sale.currency,
      ),
      ...state.where((s) => s.id != sale.id),
    ].take(keep).toList();
    await _persist();
  }

  /// The legal number for [saleId]: remembered if already known, otherwise one
  /// bounded ask that the server holds while the payment lands. Null when it
  /// is still not issued — the screen says so and asks again next time.
  Future<String?> resolveNumber(String saleId) async {
    await _ready;
    final sale = state.where((s) => s.id == saleId).firstOrNull;
    if (sale == null) return null;
    if (sale.fiscalNumber != null) return sale.fiscalNumber;
    final number = await awaitFiscalNumber(
      _ref.read(apiClientProvider).dio,
      sale.orderId,
      waitSeconds: 5,
      attempts: 1,
    );
    if (number == null) return null;
    state = [
      for (final s in state)
        if (s.id == saleId) s.withNumber(number) else s,
    ];
    await _persist();
    return number;
  }
}
