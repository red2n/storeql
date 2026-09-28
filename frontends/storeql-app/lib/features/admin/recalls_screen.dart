import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/format.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';
import 'providers/admin_providers.dart';
import 'recall_providers.dart';
import 'widgets/variant_picker.dart';
import '../../shared/util/short_ref.dart';
import '../../core/theme.dart';

final _isoDate = RegExp(r'^\d{4}-\d{2}-\d{2}$');

String _qty(double v) =>
    v == v.roundToDouble() ? v.toStringAsFixed(0) : v.toStringAsFixed(3);

/// Withdrawals and recalls: what is off sale, where, and what each store did
/// about it. Store staff record what they found; a manager opens, closes and
/// cancels.
class RecallsScreen extends ConsumerWidget {
  const RecallsScreen({super.key});

  static const _filters = {
    'OPEN': 'Open',
    'CLOSED': 'Closed',
    'CANCELLED': 'Cancelled',
    null: 'All',
  };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final isManager = auth is AuthAuthenticated && auth.isManager;
    final status = ref.watch(recallStatusFilterProvider);
    final async = ref.watch(recallsProvider(status));

    final gutter = context.pageGutter;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        PageHeader(
          title: 'Recalls',
          padding: EdgeInsetsDirectional.fromSTEB(
            gutter,
            gutter,
            gutter,
            AppSpacing.md,
          ),
          actions: [
            if (isManager)
              FilledButton.icon(
                key: const Key('recall-open'),
                icon: const Icon(Icons.report_outlined),
                label: const Text('Open a recall'),
                onPressed: () async {
                  final opened = await showDialog<RecallDetail>(
                    context: context,
                    barrierDismissible: false,
                    builder: (_) => const _OpenRecallDialog(),
                  );
                  if (opened == null || !context.mounted) return;
                  ref.invalidate(recallsProvider);
                  await _showDetail(context, opened.id);
                },
              ),
          ],
        ),
        _StatusFilter(
          filters: _filters,
          selected: status,
          onSelected: (s) =>
              ref.read(recallStatusFilterProvider.notifier).state = s,
        ),
        const SizedBox(height: AppSpacing.sm),
        Expanded(child: _list(context, ref, async, status)),
      ],
    );
  }

  Widget _list(
    BuildContext context,
    WidgetRef ref,
    AsyncValue<List<RecallSummary>> async,
    String? status,
  ) {
    // The error first: a list that failed to load must not read as "no open
    // recalls", which is the one thing a store must never wrongly believe.
    if (async.hasError) {
      return ErrorView(
        message: friendlyError(
          async.error!,
          fallback:
              "Recalls couldn't be loaded, so none can be assumed to be clear.",
        ),
        onRetry: () => ref.invalidate(recallsProvider),
      );
    }
    if (!async.hasValue) return const LoadingView(label: 'Loading recalls…');
    final recalls = async.value!;
    if (recalls.isEmpty) {
      return EmptyState(
        icon: Icons.verified_outlined,
        title: status == 'OPEN' ? 'No open recalls.' : 'No recalls here.',
      );
    }
    final gutter = context.pageGutter;
    return ListView.separated(
      padding: EdgeInsetsDirectional.fromSTEB(
        gutter,
        0,
        gutter,
        AppSpacing.lg,
      ),
      itemCount: recalls.length,
      separatorBuilder: (_, _) => const Divider(height: 1),
      itemBuilder: (_, i) => _RecallTile(recall: recalls[i]),
    );
  }
}

/// The list's status filter. From tablet width it is one segmented button; on
/// a phone its four equal segments would split *Open* and *Cancelled*
/// mid-word, so it is a row of chips there that scrolls sideways if the words
/// need more than the width.
class _StatusFilter extends StatelessWidget {
  final Map<String?, String> filters;
  final String? selected;
  final ValueChanged<String?> onSelected;

  const _StatusFilter({
    required this.filters,
    required this.selected,
    required this.onSelected,
  });

  @override
  Widget build(BuildContext context) {
    final gutter = context.pageGutter;
    return LayoutBuilder(
      builder: (context, constraints) {
        // The segments share the width equally, so at large text a label
        // breaks mid-word (Ope/n, Cancelle/d) up to desktop width: chips
        // then, as on a phone, by the rule PageHeader keeps.
        final largeText =
            MediaQuery.textScalerOf(context).scale(16) > 16 * 1.3;
        if (AppBreakpoints.classOf(constraints.maxWidth) !=
                WindowClass.compact &&
            !largeText) {
          return Padding(
            padding: EdgeInsetsDirectional.symmetric(horizontal: gutter),
            child: Align(
              alignment: AlignmentDirectional.centerStart,
              child: SegmentedButton<String?>(
                segments: [
                  for (final e in filters.entries)
                    ButtonSegment(value: e.key, label: Text(e.value)),
                ],
                selected: {selected},
                onSelectionChanged: (s) => onSelected(s.first),
              ),
            ),
          );
        }
        return SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          padding: EdgeInsetsDirectional.symmetric(horizontal: gutter),
          child: Row(
            children: [
              for (final e in filters.entries)
                Padding(
                  padding: const EdgeInsetsDirectional.only(
                    end: AppSpacing.sm,
                  ),
                  child: ChoiceChip(
                    label: Text(e.value),
                    selected: selected == e.key,
                    onSelected: (_) => onSelected(e.key),
                  ),
                ),
            ],
          ),
        );
      },
    );
  }
}

/// Where a recall came from, in words: the same on the form and the detail.
const recallSourceLabels = {
  'SUPPLIER': 'Supplier',
  'FSA': 'Food Standards Agency',
  'FSS': 'Food Standards Scotland',
  'INTERNAL': 'Our own',
  'OTHER': 'Other',
};

Future<void> _showDetail(BuildContext context, String recallId) =>
    showDialog<void>(
      context: context,
      builder: (_) => _RecallDetailDialog(recallId: recallId),
    );

/// A recall's status in words.
String _recallStatusLabel(String status) => switch (status) {
  'OPEN' => 'Open',
  'CLOSED' => 'Closed',
  'CANCELLED' => 'Cancelled',
  _ => humanizeCode(status),
};

class _KindBadge extends StatelessWidget {
  final String kind;
  const _KindBadge(this.kind);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final recall = kind == 'RECALL';
    return Container(
      padding: const EdgeInsetsDirectional.symmetric(
        horizontal: AppSpacing.sm,
        vertical: 2,
      ),
      decoration: BoxDecoration(
        color: recall ? cs.errorContainer : cs.tertiaryContainer,
        borderRadius: AppRadius.badge,
      ),
      // Its own size, like every status badge: without one it inherits the
      // size of whatever it sits beside — a list tile's 16px title.
      child: Text(
        recall ? 'Recall' : 'Withdrawal',
        style: theme.textTheme.labelMedium?.copyWith(
          color: recall ? cs.onErrorContainer : cs.onTertiaryContainer,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _RecallTile extends ConsumerWidget {
  final RecallSummary recall;
  const _RecallTile({required this.recall});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final open = recall.status == 'OPEN';
    final toAct = open && recall.storesOutstanding > 0;
    // Where the recall stands, in words beside its kind: a store still to act
    // is the one state that asks for something, so it is the loud one.
    final badge = toAct
        ? const StatusBadge(
            'Stores to act',
            tone: StatusTone.error,
            icon: Icons.pending_actions,
          )
        : StatusBadge(
            _recallStatusLabel(recall.status),
            tone: open ? StatusTone.info : StatusTone.neutral,
          );
    final progress = !open
        ? null
        : toAct
        ? '${recall.storesOutstanding} of ${recall.storesAffected} stores still to act'
        : recall.storesAffected == 0
        ? 'No stock held'
        : 'Every store has acted';
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
      title: Wrap(
        spacing: AppSpacing.sm,
        runSpacing: AppSpacing.xs,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Text(
            recall.reference,
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          _KindBadge(recall.kind),
          badge,
        ],
      ),
      subtitle: Text(
        [
          hazardLabel(recall.hazard),
          'opened ${recall.openedAt == null ? '-' : AppFormat.dateOf(recall.openedAt!)}',
          '${_qty(recall.qtyHeld)} held',
          ?progress,
        ].join(' · '),
      ),
      trailing: const Icon(Icons.chevron_right),
      onTap: () => _showDetail(context, recall.id),
    );
  }
}

// ── Detail ───────────────────────────────────────────────────────────────────

class _RecallDetailDialog extends ConsumerWidget {
  final String recallId;
  const _RecallDetailDialog({required this.recallId});

  void _refresh(WidgetRef ref) {
    ref.invalidate(recallDetailProvider(recallId));
    ref.invalidate(recallsProvider);
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(recallDetailProvider(recallId));
    final auth = ref.watch(authNotifierProvider).value;
    final isManager = auth is AuthAuthenticated && auth.isManager;
    final allowedStores = auth is AuthAuthenticated
        ? auth.storeIds
        : const <String>[];
    final storeNames = {
      for (final s in ref.watch(storesProvider).value ?? const <StoreInfo>[])
        s.id: s.name,
    };

    Widget body;
    if (async.hasError) {
      body = ErrorView(
        message: friendlyError(
          async.error!,
          fallback: "This recall couldn't be loaded.",
        ),
        onRetry: () => ref.invalidate(recallDetailProvider(recallId)),
      );
    } else if (!async.hasValue) {
      body = const LoadingView(label: 'Loading recall…');
    } else {
      body = _detailBody(
        context,
        ref,
        async.value!,
        isManager,
        allowedStores,
        storeNames,
      );
    }
    return Dialog(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 760, maxHeight: 820),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.xl),
          child: body,
        ),
      ),
    );
  }

  Widget _detailBody(
    BuildContext context,
    WidgetRef ref,
    RecallDetail r,
    bool isManager,
    List<String> allowedStores,
    Map<String, String> storeNames,
  ) {
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final labels =
        ref
            .watch(
              variantLabelsProvider(
                variantIdsKey([
                  ...r.items.map((i) => i.variantId),
                  ...r.batches.map((b) => b.variantId),
                ]),
              ),
            )
            .value ??
        const <String, VariantLabel>{};
    String storeName(String id) => storeNames[id] ?? '…${shortRef(id)}';
    bool canActAt(String storeId) =>
        r.isOpen && (allowedStores.isEmpty || allowedStores.contains(storeId));

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: Wrap(
                spacing: AppSpacing.sm,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text(r.reference, style: text.headlineSmall),
                  _KindBadge(r.kind),
                  if (!r.isOpen) StatusBadge(_recallStatusLabel(r.status)),
                ],
              ),
            ),
            IconButton(
              tooltip: 'Close',
              icon: const Icon(Icons.close),
              onPressed: () => Navigator.pop(context),
            ),
          ],
        ),
        Expanded(
          child: ListView(
            children: [
              Text(
                '${hazardLabel(r.hazard)} · from ${recallSourceLabels[r.source] ?? humanizeCode(r.source)}'
                '${r.sourceReference == null ? '' : ' ${r.sourceReference}'}'
                '${r.openedAt == null ? '' : ' · opened ${AppFormat.dateOf(r.openedAt!)}'}',
              ),
              const SizedBox(height: AppSpacing.sm),
              Text(r.reason),
              if (r.customerNotice != null) ...[
                const SizedBox(height: AppSpacing.md),
                Container(
                  padding: const EdgeInsets.all(AppSpacing.md),
                  color: cs.errorContainer,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Notice for customers',
                        style: TextStyle(
                          color: cs.onErrorContainer,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        r.customerNotice!,
                        style: TextStyle(color: cs.onErrorContainer),
                      ),
                    ],
                  ),
                ),
              ],
              if (r.isRecall && r.remedies.isNotEmpty) ...[
                const SizedBox(height: AppSpacing.sm),
                Text(
                  'Buyers may choose ${remediesLabel(r.remedies)}'
                  '${r.singleRemedyReason == null ? '' : ' — ${r.singleRemedyReason}'}'
                  '. Contact: ${[r.contactPhone, r.contactUrl].whereType<String>().join(' · ')}'
                  '${r.soldFrom == null ? '' : ' · sales from ${AppFormat.date(r.soldFrom)}'}',
                  key: const Key('recall-offer'),
                ),
              ],
              if (r.endNotes != null) ...[
                const SizedBox(height: AppSpacing.sm),
                Text(
                  'Close-out: ${r.endNotes}',
                  style: TextStyle(color: cs.outline),
                ),
              ],
              const SizedBox(height: AppSpacing.lg),
              Text('Affected', style: text.titleMedium),
              for (final line in r.items)
                ListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  title: Text(variantDisplayName(line.variantId, labels)),
                  subtitle: Text(describeScope(line)),
                ),
              const SizedBox(height: AppSpacing.lg),
              Text('Stores', style: text.titleMedium),
              if (r.stores.isEmpty)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: AppSpacing.sm),
                  child: Text(
                    'No store held any of this stock when the recall opened. '
                    'Stock that arrives while it is open is taken off sale as it arrives.',
                  ),
                ),
              for (final store in r.stores)
                _StoreSection(
                  recall: r,
                  store: store,
                  storeName: storeName(store.storeId),
                  labels: labels,
                  canAct: canActAt(store.storeId),
                  onChanged: () => _refresh(ref),
                ),
              if (r.isRecall) ...[
                const SizedBox(height: AppSpacing.lg),
                _BuyersSection(
                  recall: r,
                  storeName: storeName,
                  canAct: r.isOpen,
                  onChanged: () => _refresh(ref),
                ),
              ],
              if (r.storeActions.isNotEmpty) ...[
                const SizedBox(height: AppSpacing.lg),
                Text('What stores recorded', style: text.titleMedium),
                for (final a in r.storeActions)
                  ListTile(
                    dense: true,
                    contentPadding: EdgeInsets.zero,
                    title: Text(
                      '${storeName(a.storeId)}: ${dispositionLabel(a.disposition)}',
                    ),
                    subtitle: Text(
                      'Found ${_qty(a.qtyFound)} of ${_qty(a.systemQty)} held'
                      '${r.isRecall ? (a.noticeDisplayed ? ' · notice displayed' : ' · notice NOT displayed') : ''}'
                      '${a.notes == null ? '' : ' · ${a.notes}'}',
                    ),
                  ),
              ],
            ],
          ),
        ),
        if (isManager && r.isOpen) ...[
          const Divider(),
          Wrap(
            alignment: WrapAlignment.end,
            spacing: AppSpacing.sm,
            children: [
              TextButton(
                key: const Key('recall-cancel'),
                onPressed: () => _cancel(context, ref, r),
                child: const Text('Cancel recall'),
              ),
              FilledButton(
                key: const Key('recall-close'),
                onPressed: () => _close(context, ref, r, storeName),
                child: const Text('Close recall'),
              ),
            ],
          ),
        ],
      ],
    );
  }

  Future<void> _close(
    BuildContext context,
    WidgetRef ref,
    RecallDetail r,
    String Function(String) storeName,
  ) async {
    final notes = await showDialog<String>(
      context: context,
      builder: (_) => const _TextDialog(
        title: 'Close this recall',
        label: 'Close-out notes',
        hint: 'How much was recovered, and anything still outstanding',
        action: 'Close recall',
        required: false,
      ),
    );
    if (notes == null || !context.mounted) return;
    try {
      await closeRecall(
        ref.read(apiClientProvider).dio,
        recallId: r.id,
        notes: notes,
      );
      _refresh(ref);
    } catch (e) {
      if (!context.mounted) return;
      var message = friendlyError(e);
      if (apiErrorCode(e) == 'RECALL_STORES_OUTSTANDING' && e is DioException) {
        final details =
            ((e.response?.data as Map?)?['error'] as Map?)?['details'];
        if (details is List && details.isNotEmpty) {
          message =
              'Still holding recalled stock: '
              '${details.map((d) => storeName('$d')).join(', ')}. Each store records a '
              'return or destruction before the recall can close.';
        }
      }
      _snack(context, message);
    }
  }

  Future<void> _cancel(
    BuildContext context,
    WidgetRef ref,
    RecallDetail r,
  ) async {
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => const _TextDialog(
        title: 'Cancel this recall',
        label: 'Why it was opened in error',
        hint: 'Its stock goes back on sale',
        action: 'Cancel recall',
      ),
    );
    if (reason == null || !context.mounted) return;
    try {
      await cancelRecall(
        ref.read(apiClientProvider).dio,
        recallId: r.id,
        reason: reason,
      );
      _refresh(ref);
    } catch (e) {
      if (context.mounted) _snack(context, friendlyError(e));
    }
  }
}

void _snack(BuildContext context, String message) {
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(message),
      backgroundColor: Theme.of(context).colorScheme.error,
    ),
  );
}

/// The people who bought the recalled packs (05.10): how many the recall
/// reached, how many it could write to, and each buyer's remedy as it is
/// chosen and settled. A refund settles through a return of the order, so the
/// goods and the money are on one record.
class _BuyersSection extends ConsumerWidget {
  final RecallDetail recall;
  final String Function(String) storeName;
  final bool canAct;
  final VoidCallback onChanged;

  const _BuyersSection({
    required this.recall,
    required this.storeName,
    required this.canAct,
    required this.onChanged,
  });

  void _refresh(WidgetRef ref) {
    ref.invalidate(recallNoticesProvider(recall.id));
    ref.invalidate(recallBuyersProgressProvider(recall.id));
    onChanged();
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    final progress = ref.watch(recallBuyersProgressProvider(recall.id));
    final notices = ref.watch(recallNoticesProvider(recall.id));
    return Column(
      key: const Key('recall-buyers'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Buyers', style: text.titleMedium),
        if (progress.hasError || notices.hasError)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
            child: Text(
              friendlyError(
                progress.error ?? notices.error!,
                fallback: "The buyers couldn't be loaded.",
              ),
              style: TextStyle(color: cs.error),
            ),
          )
        else if (!progress.hasValue || !notices.hasValue)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: AppSpacing.sm),
            child: Text('Loading buyers…'),
          )
        else ...[
          Text(
            '${recall.ordersAffected} ${recall.ordersAffected == 1 ? 'order' : 'orders'} '
            'drew on the packs in scope · '
            '${progress.value!.identified} told · '
            '${progress.value!.unidentified} till sales with no buyer known · '
            '${progress.value!.remedyChosen} chose a remedy · '
            '${progress.value!.resolved} settled',
            key: const Key('recall-buyers-progress'),
            style: TextStyle(color: cs.outline),
          ),
          if (notices.value!.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: AppSpacing.sm),
              child: Text(
                'No buyer notices yet. They are issued as the sales the recall '
                'found reach the order service.',
              ),
            ),
          for (final n in notices.value!)
            ListTile(
              key: Key('recall-notice-${n.id}'),
              dense: true,
              contentPadding: EdgeInsets.zero,
              title: Text(
                'Order …${shortRef(n.orderId)} · ${storeName(n.storeId)}'
                '${n.soldAt == null ? '' : ' · ${AppFormat.dateOf(n.soldAt!)}'}',
              ),
              subtitle: Text(
                '${n.lines.map((l) => l.describe()).join('; ')} · '
                '${noticeProgressLabel(n)}',
              ),
              trailing: canAct && !n.isResolved
                  ? PopupMenuButton<String>(
                      key: Key('recall-notice-settle-${n.id}'),
                      tooltip: 'Settle',
                      icon: const Icon(Icons.more_horiz),
                      onSelected: (action) => _act(context, ref, n, action),
                      itemBuilder: (_) => [
                        if (n.remedy == null)
                          for (final r in n.remedies)
                            PopupMenuItem(
                              value: 'CHOOSE_$r',
                              child: Text('Buyer chose ${remedyLabel(r)}'),
                            ),
                        const PopupMenuItem(
                          value: 'REFUND',
                          child: Text('Refund — take the goods back'),
                        ),
                        const PopupMenuItem(
                          value: 'REPLACED',
                          child: Text('Replacement handed over'),
                        ),
                        const PopupMenuItem(
                          value: 'REPAIRED',
                          child: Text('Repaired'),
                        ),
                        const PopupMenuItem(
                          value: 'DECLINED',
                          child: Text('Buyer wanted nothing'),
                        ),
                      ],
                    )
                  : null,
            ),
        ],
      ],
    );
  }

  Future<void> _act(
    BuildContext context,
    WidgetRef ref,
    RecallNotice n,
    String action,
  ) async {
    final dio = ref.read(apiClientProvider).dio;
    try {
      if (action.startsWith('CHOOSE_')) {
        await chooseRecallRemedy(
          dio,
          noticeId: n.id,
          remedy: action.substring('CHOOSE_'.length),
        );
      } else if (action == 'REFUND') {
        await refundRecallNotice(dio, notice: n);
      } else {
        await resolveRecallNotice(dio, noticeId: n.id, resolution: action);
      }
      _refresh(ref);
    } catch (e) {
      if (context.mounted) _snack(context, friendlyError(e));
    }
  }
}

class _StoreSection extends ConsumerWidget {
  final RecallDetail recall;
  final RecallStoreProgress store;
  final String storeName;
  final Map<String, VariantLabel> labels;
  final bool canAct;
  final VoidCallback onChanged;

  const _StoreSection({
    required this.recall,
    required this.store,
    required this.storeName,
    required this.labels,
    required this.canAct,
    required this.onChanged,
  });

  static String _matchLabel(String match) => switch (match) {
    'LOT_UNKNOWN' => 'lot not recorded — check the pack',
    'DATE_UNKNOWN' => 'date not recorded — check the pack',
    _ => 'in scope',
  };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final batches = recall.batches
        .where((b) => b.storeId == store.storeId)
        .toList();
    return Card(
      margin: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.md),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    storeName,
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                ),
                if (store.outstanding)
                  Text(
                    'To do',
                    style: TextStyle(
                      color: cs.error,
                      fontWeight: FontWeight.w600,
                    ),
                  )
                else
                  Text('Done', style: TextStyle(color: cs.primary)),
              ],
            ),
            Text(
              '${_qty(store.qtyHeld)} taken off sale'
              '${store.qtyFound == null ? '' : ' · ${_qty(store.qtyFound!)} found'}',
            ),
            for (final b in batches)
              Padding(
                padding: const EdgeInsets.only(top: AppSpacing.xs),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        '${variantDisplayName(b.variantId, labels)} · '
                        '${b.batchNo == null ? 'no lot' : 'lot ${b.batchNo}'}'
                        '${b.expiryDate == null ? '' : ' · ${AppFormat.date(b.expiryDate)}'} · '
                        '${_qty(b.qtyAtQuarantine)} · '
                        '${b.released ? 'released: ${b.releaseReason ?? ''}' : _matchLabel(b.match)}'
                        '${b.quarantinedOn == 'ARRIVAL' ? ' · held on arrival' : ''}',
                        style: b.released ? TextStyle(color: cs.outline) : null,
                      ),
                    ),
                    if (canAct && b.releasable)
                      TextButton(
                        key: Key('recall-release-${b.batchId}'),
                        onPressed: () => _release(context, ref, b),
                        child: const Text('Not affected'),
                      ),
                  ],
                ),
              ),
            if (canAct)
              Align(
                alignment: Alignment.centerRight,
                child: OutlinedButton(
                  key: Key('recall-record-${store.storeId}'),
                  onPressed: () => _record(context, ref),
                  child: const Text('Record what was found'),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _release(
    BuildContext context,
    WidgetRef ref,
    RecallHeldBatch b,
  ) async {
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => const _TextDialog(
        title: 'Pack not affected',
        label: 'What the pack shows',
        hint: 'e.g. Lot L-2290, best before 3 Nov',
        action: 'Put back on sale',
      ),
    );
    if (reason == null || !context.mounted) return;
    try {
      await releaseRecalledBatch(
        ref.read(apiClientProvider).dio,
        recallId: recall.id,
        batchId: b.batchId,
        reason: reason,
      );
      onChanged();
    } catch (e) {
      if (context.mounted) _snack(context, friendlyError(e));
    }
  }

  Future<void> _record(BuildContext context, WidgetRef ref) async {
    final saved = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (_) => _StoreActionDialog(
        recall: recall,
        storeId: store.storeId,
        storeName: storeName,
        qtyHeld: store.qtyHeld,
      ),
    );
    if (saved == true) onChanged();
  }
}

class _StoreActionDialog extends ConsumerStatefulWidget {
  final RecallDetail recall;
  final String storeId;
  final String storeName;
  final double qtyHeld;

  const _StoreActionDialog({
    required this.recall,
    required this.storeId,
    required this.storeName,
    required this.qtyHeld,
  });

  @override
  ConsumerState<_StoreActionDialog> createState() => _StoreActionDialogState();
}

class _StoreActionDialogState extends ConsumerState<_StoreActionDialog> {
  final _qtyCtrl = TextEditingController();
  final _notes = TextEditingController();
  String _disposition = 'HELD_FOR_COLLECTION';
  bool _notice = false;
  bool _saving = false;
  String? _error;

  @override
  void dispose() {
    _qtyCtrl.dispose();
    _notes.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final qty = double.tryParse(_qtyCtrl.text.trim());
    if (qty == null || qty < 0) {
      setState(() => _error = 'Enter how much was found — 0 if none.');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await recordRecallStoreAction(
        ref.read(apiClientProvider).dio,
        recallId: widget.recall.id,
        storeId: widget.storeId,
        qtyFound: qty,
        disposition: _disposition,
        noticeDisplayed: _notice,
        notes: _notes.text,
      );
      if (mounted) Navigator.pop(context, true);
    } catch (e) {
      setState(() {
        _saving = false;
        _error = friendlyError(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text('${widget.recall.reference} at ${widget.storeName}'),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                  child: Text(_error!, style: TextStyle(color: cs.error)),
                ),
              Text('The system took ${_qty(widget.qtyHeld)} off sale here.'),
              const SizedBox(height: AppSpacing.sm),
              TextField(
                key: const Key('recall-qty-found'),
                controller: _qtyCtrl,
                keyboardType: const TextInputType.numberWithOptions(
                  decimal: true,
                ),
                decoration: const InputDecoration(
                  labelText: 'Found on the shelves and in the back *',
                ),
              ),
              const SizedBox(height: AppSpacing.md),
              const Text('What became of it'),
              RadioGroup<String>(
                groupValue: _disposition,
                onChanged: (v) =>
                    setState(() => _disposition = v ?? _disposition),
                child: const Column(
                  children: [
                    RadioListTile(
                      value: 'HELD_FOR_COLLECTION',
                      title: Text('Held for collection'),
                      subtitle: Text('Stays on the books until it goes'),
                    ),
                    RadioListTile(
                      value: 'RETURNED_TO_SUPPLIER',
                      title: Text('Returned to supplier'),
                    ),
                    RadioListTile(value: 'DESTROYED', title: Text('Destroyed')),
                  ],
                ),
              ),
              if (widget.recall.isRecall)
                CheckboxListTile(
                  key: const Key('recall-notice-displayed'),
                  value: _notice,
                  onChanged: (v) => setState(() => _notice = v ?? false),
                  title: const Text(
                    'The recall notice is displayed at the tills',
                  ),
                ),
              TextField(
                controller: _notes,
                decoration: const InputDecoration(labelText: 'Notes'),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('recall-action-save'),
          onPressed: _saving ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}

class _TextDialog extends StatefulWidget {
  final String title;
  final String label;
  final String hint;
  final String action;
  final bool required;

  const _TextDialog({
    required this.title,
    required this.label,
    required this.hint,
    required this.action,
    this.required = true,
  });

  @override
  State<_TextDialog> createState() => _TextDialogState();
}

class _TextDialogState extends State<_TextDialog> {
  final _ctrl = TextEditingController();

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.title),
      content: SizedBox(
        width: 420,
        child: TextField(
          key: const Key('recall-text'),
          controller: _ctrl,
          autofocus: true,
          maxLines: 3,
          decoration: InputDecoration(
            labelText: widget.label,
            hintText: widget.hint,
          ),
          onChanged: (_) => setState(() {}),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Back'),
        ),
        FilledButton(
          key: const Key('recall-text-confirm'),
          onPressed: widget.required && _ctrl.text.trim().isEmpty
              ? null
              : () => Navigator.pop(context, _ctrl.text.trim()),
          child: Text(widget.action),
        ),
      ],
    );
  }
}

// ── Opening ──────────────────────────────────────────────────────────────────

class _OpenRecallDialog extends ConsumerStatefulWidget {
  const _OpenRecallDialog();

  @override
  ConsumerState<_OpenRecallDialog> createState() => _OpenRecallDialogState();
}

class _OpenRecallDialogState extends ConsumerState<_OpenRecallDialog> {
  final _reference = TextEditingController();
  final _reason = TextEditingController();
  final _notice = TextEditingController();
  final _sourceRef = TextEditingController();
  final _lot = TextEditingController();
  final _from = TextEditingController();
  final _to = TextEditingController();
  final _singleRemedyReason = TextEditingController();
  final _contactPhone = TextEditingController();
  final _contactUrl = TextEditingController();
  final _soldFrom = TextEditingController();
  final Set<String> _remedies = {};
  String _kind = 'RECALL';
  String _hazard = 'ALLERGEN';
  String _source = 'SUPPLIER';
  String? _productId;
  String? _variantId;
  final List<RecallScopeLine> _items = [];
  bool _saving = false;
  String? _error;

  static const _hazards = [
    'MICROBIOLOGICAL',
    'ALLERGEN',
    'FOREIGN_BODY',
    'CHEMICAL',
    'LABELLING',
    'QUALITY',
    'OTHER',
  ];
  static const _sources = recallSourceLabels;

  @override
  void dispose() {
    for (final c in [
      _reference,
      _reason,
      _notice,
      _sourceRef,
      _lot,
      _from,
      _to,
      _singleRemedyReason,
      _contactPhone,
      _contactUrl,
      _soldFrom,
    ]) {
      c.dispose();
    }
    super.dispose();
  }

  void _addItem() {
    final from = _from.text.trim();
    final to = _to.text.trim();
    if (_variantId == null) {
      setState(() => _error = 'Choose the product and variant affected.');
      return;
    }
    if ((from.isNotEmpty && !_isoDate.hasMatch(from)) ||
        (to.isNotEmpty && !_isoDate.hasMatch(to))) {
      setState(() => _error = 'Dates are written YYYY-MM-DD.');
      return;
    }
    setState(() {
      _error = null;
      _items.add(
        RecallScopeLine(
          variantId: _variantId!,
          batchNo: _lot.text.trim().isEmpty ? null : _lot.text.trim(),
          expiryFrom: from.isEmpty ? null : from,
          expiryTo: to.isEmpty ? null : to,
        ),
      );
      _lot.clear();
      _from.clear();
      _to.clear();
    });
  }

  Future<void> _save() async {
    String? problem;
    if (_reference.text.trim().isEmpty) {
      problem = 'Enter the notice reference.';
    } else if (_reason.text.trim().isEmpty) {
      problem = "Enter what's wrong.";
    } else if (_kind == 'RECALL' && _notice.text.trim().isEmpty) {
      problem =
          'A recall tells customers what to do. Enter the notice for the tills.';
    } else if (_kind == 'RECALL' && _remedies.isEmpty) {
      problem = 'A recall offers buyers a remedy. Tick at least one.';
    } else if (_kind == 'RECALL' &&
        _contactPhone.text.trim().isEmpty &&
        _contactUrl.text.trim().isEmpty) {
      problem =
          'A recall notice names a free number or a web page buyers can turn to.';
    } else if (_soldFrom.text.trim().isNotEmpty &&
        !_isoDate.hasMatch(_soldFrom.text.trim())) {
      problem = 'Dates are written YYYY-MM-DD.';
    } else if (_items.isEmpty) {
      problem = 'Add at least one affected item.';
    }
    if (problem != null) {
      setState(() => _error = problem);
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final opened = await openRecall(
        ref.read(apiClientProvider).dio,
        reference: _reference.text,
        kind: _kind,
        hazard: _hazard,
        reason: _reason.text,
        customerNotice: _kind == 'RECALL' ? _notice.text : null,
        source: _source,
        sourceReference: _sourceRef.text,
        items: _items,
        remedies: _kind == 'RECALL' ? (_remedies.toList()..sort()) : const [],
        singleRemedyReason: _kind == 'RECALL' ? _singleRemedyReason.text : null,
        contactPhone: _kind == 'RECALL' ? _contactPhone.text : null,
        contactUrl: _kind == 'RECALL' ? _contactUrl.text : null,
        soldFrom: _soldFrom.text,
      );
      if (mounted) Navigator.pop(context, opened);
    } catch (e) {
      setState(() {
        _saving = false;
        _error = friendlyError(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final labels =
        ref
            .watch(
              variantLabelsProvider(
                variantIdsKey(_items.map((i) => i.variantId)),
              ),
            )
            .value ??
        const <String, VariantLabel>{};
    return AlertDialog(
      title: const Text('Open a recall'),
      content: SizedBox(
        width: 560,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null)
                Padding(
                  padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                  child: Text(
                    _error!,
                    key: const Key('recall-open-error'),
                    style: TextStyle(color: cs.error),
                  ),
                ),
              Text(
                'Everything in scope is taken off sale at every store as soon as this is saved.',
                style: TextStyle(color: cs.outline),
              ),
              const SizedBox(height: AppSpacing.md),
              SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'WITHDRAWAL', label: Text('Withdrawal')),
                  ButtonSegment(value: 'RECALL', label: Text('Recall')),
                ],
                selected: {_kind},
                onSelectionChanged: (s) => setState(() => _kind = s.first),
              ),
              Text(
                _kind == 'RECALL'
                    ? 'Off sale, and customers who bought it are told.'
                    : "Off sale only; it hasn't reached customers.",
                style: TextStyle(color: cs.outline),
              ),
              TextField(
                key: const Key('recall-reference'),
                controller: _reference,
                decoration: const InputDecoration(
                  labelText: 'Notice reference *',
                ),
              ),
              Row(
                children: [
                  Expanded(
                    child: DropdownButtonFormField<String>(
                      initialValue: _source,
                      isExpanded: true,
                      decoration: const InputDecoration(labelText: 'From'),
                      items: [
                        for (final e in _sources.entries)
                          DropdownMenuItem(value: e.key, child: Text(e.value)),
                      ],
                      onChanged: (v) => setState(() => _source = v ?? _source),
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: TextField(
                      controller: _sourceRef,
                      decoration: const InputDecoration(
                        labelText: "Their reference",
                      ),
                    ),
                  ),
                ],
              ),
              DropdownButtonFormField<String>(
                initialValue: _hazard,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Hazard'),
                items: [
                  for (final h in _hazards)
                    DropdownMenuItem(value: h, child: Text(hazardLabel(h))),
                ],
                onChanged: (v) => setState(() => _hazard = v ?? _hazard),
              ),
              TextField(
                key: const Key('recall-reason'),
                controller: _reason,
                maxLines: 2,
                decoration: const InputDecoration(labelText: "What's wrong *"),
              ),
              if (_kind == 'RECALL') ...[
                TextField(
                  key: const Key('recall-notice'),
                  controller: _notice,
                  maxLines: 3,
                  decoration: const InputDecoration(
                    labelText: 'Notice for customers *',
                    hintText:
                        'Name the product as printed on the pack and what to do. '
                        'Plain words: no "precautionary", no "voluntary".',
                  ),
                ),
                const SizedBox(height: AppSpacing.md),
                Text(
                  'Buyers are told and choose a remedy',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                Text(
                  'Every sale of the packs in scope is found and its buyer written to. '
                  'In the EU a recall offers at least two of these, or says why only one.',
                  style: TextStyle(color: cs.outline),
                ),
                Wrap(
                  spacing: AppSpacing.sm,
                  children: [
                    for (final r in const ['REFUND', 'REPLACEMENT', 'REPAIR'])
                      FilterChip(
                        key: Key('recall-remedy-$r'),
                        label: Text(remedyLabel(r)),
                        selected: _remedies.contains(r),
                        onSelected: (on) => setState(
                          () => on ? _remedies.add(r) : _remedies.remove(r),
                        ),
                      ),
                  ],
                ),
                if (_remedies.length == 1)
                  TextField(
                    key: const Key('recall-single-remedy-reason'),
                    controller: _singleRemedyReason,
                    decoration: const InputDecoration(
                      labelText: 'Why only one remedy',
                      hintText: 'e.g. food cannot be repaired or replaced once opened',
                    ),
                  ),
                Row(
                  children: [
                    Expanded(
                      child: TextField(
                        key: const Key('recall-contact-phone'),
                        controller: _contactPhone,
                        decoration: const InputDecoration(
                          labelText: 'Free phone number',
                        ),
                      ),
                    ),
                    const SizedBox(width: AppSpacing.md),
                    Expanded(
                      child: TextField(
                        key: const Key('recall-contact-url'),
                        controller: _contactUrl,
                        decoration: const InputDecoration(
                          labelText: 'Web page',
                          hintText: 'https://…',
                        ),
                      ),
                    ),
                  ],
                ),
              ],
              TextField(
                key: const Key('recall-sold-from'),
                controller: _soldFrom,
                decoration: const InputDecoration(
                  labelText: 'Sales from',
                  hintText: 'YYYY-MM-DD — blank for every sale of the packs in scope',
                ),
              ),
              const SizedBox(height: AppSpacing.lg),
              Text(
                'Affected items',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              for (var i = 0; i < _items.length; i++)
                ListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  title: Text(variantDisplayName(_items[i].variantId, labels)),
                  subtitle: Text(describeScope(_items[i])),
                  trailing: IconButton(
                    tooltip: 'Remove',
                    icon: const Icon(Icons.delete_outline),
                    onPressed: () => setState(() => _items.removeAt(i)),
                  ),
                ),
              VariantPicker(
                productId: _productId,
                variantId: _variantId,
                onProduct: (p) => setState(() {
                  _productId = p;
                  _variantId = null;
                }),
                onVariant: (v) => setState(() => _variantId = v),
              ),
              TextField(
                key: const Key('recall-lot'),
                controller: _lot,
                decoration: const InputDecoration(
                  labelText: 'Lot or batch code',
                  hintText: 'Leave blank for every lot',
                ),
              ),
              Row(
                children: [
                  Expanded(
                    child: TextField(
                      key: const Key('recall-from'),
                      controller: _from,
                      decoration: const InputDecoration(
                        labelText: 'Dated from',
                        hintText: 'YYYY-MM-DD',
                      ),
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: TextField(
                      key: const Key('recall-to'),
                      controller: _to,
                      decoration: const InputDecoration(
                        labelText: 'Dated to',
                        hintText: 'YYYY-MM-DD',
                      ),
                    ),
                  ),
                ],
              ),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  key: const Key('recall-add-item'),
                  icon: const Icon(Icons.add),
                  label: const Text('Add item'),
                  onPressed: _addItem,
                ),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('recall-open-save'),
          onPressed: _saving ? null : _save,
          child: const Text('Take off sale'),
        ),
      ],
    );
  }
}
