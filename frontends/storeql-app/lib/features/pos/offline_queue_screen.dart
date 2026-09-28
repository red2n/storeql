import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/format.dart';
import '../../core/offline/offline_queue.dart';
import '../../core/offline/offline_sale.dart';
import '../../core/offline/offline_synced.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/empty_state.dart';

/// Sales the till took but the server has not accepted yet.
///
/// This screen exists because an invisible queue is worse than no queue: the
/// cashier has taken real money, and needs to be able to see that it is still
/// owed to the server, retry it, and — for a sale the server has permanently
/// refused — decide what to do about it.
class OfflineQueueScreen extends ConsumerWidget {
  const OfflineQueueScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final sales = ref.watch(offlineQueueProvider);
    final synced = ref.watch(offlineSyncedProvider);

    if (sales.isEmpty) {
      return Column(
        children: [
          const Expanded(
            child: EmptyState(
              icon: Icons.cloud_done_outlined,
              title: 'Everything is synced',
              message:
                  'Sales taken while offline appear here until the server has them.',
            ),
          ),
          if (synced.isNotEmpty) _SyncedSection(synced: synced),
        ],
      );
    }

    final waiting = sales.where((s) => s.status == OfflineSaleStatus.pending).length;
    return Column(
      children: [
        _QueueBanner(total: sales.length, attention: sales.length - waiting),
        Expanded(
          child: ListView.separated(
            itemCount: sales.length,
            separatorBuilder: (_, _) => const Divider(height: 1),
            itemBuilder: (_, i) => _SaleTile(sale: sales[i]),
          ),
        ),
        if (synced.isNotEmpty) _SyncedSection(synced: synced),
      ],
    );
  }
}

/// The queue's standing message: how many sales the server does not have yet,
/// how many of those need a person, and *Sync now*. A persistent message with
/// an action is a [MaterialBanner] (UI-GUIDE §7.1); it turns to the error
/// container while a sale the server refused is waiting for someone.
class _QueueBanner extends ConsumerWidget {
  const _QueueBanner({required this.total, required this.attention});

  final int total;

  /// Sales the server refused outright, parked for a person.
  final int attention;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final alarm = attention > 0;
    final ink = alarm ? cs.onErrorContainer : cs.onSurface;
    return MaterialBanner(
      backgroundColor: alarm ? cs.errorContainer : cs.surfaceContainerHigh,
      padding: EdgeInsetsDirectional.fromSTEB(
          context.pageGutter, AppSpacing.md, AppSpacing.sm, AppSpacing.xs),
      leading: Icon(
        alarm ? Icons.error_outline : Icons.cloud_off_outlined,
        color: alarm ? cs.onErrorContainer : cs.onSurfaceVariant,
      ),
      content: Text(
        '$total sale${total == 1 ? '' : 's'} not yet on the server'
        '${alarm ? ' · $attention need attention' : ''}',
        style: TextStyle(color: ink, fontWeight: FontWeight.w600),
      ),
      actions: [
        FilledButton.tonalIcon(
          onPressed: () => ref.read(offlineQueueProvider.notifier).sync(),
          icon: const Icon(Icons.sync, size: 18),
          label: const Text('Sync now'),
        ),
      ],
    );
  }
}

/// Sales that reached the server, with the legal receipt number each was given
/// on replay. The offline receipt in the customer's hand has no number on it;
/// this is where the cashier finds it, to reprint or to write on the copy.
class _SyncedSection extends StatelessWidget {
  const _SyncedSection({required this.synced});
  final List<SyncedSale> synced;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return ConstrainedBox(
      constraints: const BoxConstraints(maxHeight: 220),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Material(
            color: cs.surfaceContainerHigh,
            child: Padding(
              padding: EdgeInsetsDirectional.symmetric(
                  horizontal: context.pageGutter, vertical: AppSpacing.md),
              child: Row(
                children: [
                  Icon(Icons.receipt_long_outlined,
                      size: 18, color: cs.onSurfaceVariant),
                  const SizedBox(width: AppSpacing.sm),
                  const Expanded(
                    child: Text(
                      'Synced · receipt numbers issued on replay',
                      style: TextStyle(fontWeight: FontWeight.w600),
                    ),
                  ),
                ],
              ),
            ),
          ),
          Flexible(
            child: ListView.separated(
              shrinkWrap: true,
              itemCount: synced.length,
              separatorBuilder: (_, _) => const Divider(height: 1),
              itemBuilder: (_, i) => _SyncedTile(sale: synced[i]),
            ),
          ),
        ],
      ),
    );
  }
}

class _SyncedTile extends ConsumerWidget {
  const _SyncedTile({required this.sale});
  final SyncedSale sale;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final number = ref.watch(syncedFiscalNumberProvider(sale.id));
    return ListTile(
      dense: true,
      leading: Icon(Icons.cloud_done_outlined, color: cs.onSurfaceVariant),
      // In capitals, as the offline receipt was printed (SyncedSale.reference).
      title: Text('Sale #${sale.reference}  ·  '
          '${AppFormat.money(sale.total, currencyCode: sale.currency)}'),
      subtitle: number.when(
        loading: () => const Text('Looking up the receipt number…'),
        error: (_, _) => Text('Receipt number not available yet — check again.',
            style: TextStyle(color: cs.onSurfaceVariant)),
        data: (n) => n == null
            ? Text('Receipt number not issued yet — check again shortly.',
                style: TextStyle(color: cs.onSurfaceVariant))
            : Text('Receipt no. $n',
                style: TextStyle(
                    color: cs.primary, fontWeight: FontWeight.w600)),
      ),
      trailing: number.hasValue && number.value == null
          ? IconButton(
              tooltip: 'Check again',
              icon: const Icon(Icons.refresh),
              onPressed: () =>
                  ref.invalidate(syncedFiscalNumberProvider(sale.id)),
            )
          : null,
    );
  }
}

class _SaleTile extends ConsumerWidget {
  final OfflineSale sale;

  const _SaleTile({required this.sale});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final failed = sale.status == OfflineSaleStatus.failed;
    final at = AppFormat.dateTime(sale.capturedAt.toUtc().toIso8601String());

    return ListTile(
      leading: Icon(
        failed ? Icons.error_outline : Icons.schedule,
        color: failed ? cs.error : cs.onSurfaceVariant,
      ),
      title: Text('Sale #${sale.reference}  ·  '
          '${AppFormat.money(sale.total, currencyCode: sale.currency)}'),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('${sale.itemCount} item${sale.itemCount == 1 ? '' : 's'} · $at'
              '${sale.attempts > 0 ? ' · ${sale.attempts} attempt${sale.attempts == 1 ? '' : 's'}' : ''}'),
          if (sale.lastError != null)
            Text(sale.lastError!,
                style: TextStyle(color: failed ? cs.error : cs.onSurfaceVariant)),
        ],
      ),
      isThreeLine: sale.lastError != null,
      trailing: failed
          ? Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                IconButton(
                  tooltip: 'Try again',
                  icon: const Icon(Icons.refresh),
                  onPressed: () => ref.read(offlineQueueProvider.notifier).retry(sale.id),
                ),
                IconButton(
                  tooltip: 'Discard',
                  icon: Icon(Icons.delete_outline, color: cs.error),
                  onPressed: () => _confirmDiscard(context, ref),
                ),
              ],
            )
          : const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2)),
    );
  }

  /// Discarding tells the server nothing ever happened, while the customer has
  /// paid — so it is confirmed explicitly and only offered once the server has
  /// refused the sale outright.
  Future<void> _confirmDiscard(BuildContext context, WidgetRef ref) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Discard sale #${sale.reference}?'),
        content: const Text(
            'The server will never be told about this sale. The customer has '
            'already paid, so only discard it once it has been settled another way.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Keep')),
          FilledButton(
            // The error pair: its label in onError, not onPrimary.
            style: FilledButton.styleFrom(
                backgroundColor: Theme.of(ctx).colorScheme.error,
                foregroundColor: Theme.of(ctx).colorScheme.onError),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Discard'),
          ),
        ],
      ),
    );
    if (ok == true) await ref.read(offlineQueueProvider.notifier).discard(sale.id);
  }
}
