import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_error.dart';
import '../../core/format.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/status_badge.dart';
import '../../shared/util/short_ref.dart';
import 'markdown_providers.dart';
import 'providers/admin_providers.dart';

// ── Reduce to clear (05.4) and the ladder that plans it (03.9) ───────────────
//
// The morning's job on a fresh counter: what is coming up to its date, what the
// ladder says to sticker it at, the sticker issued with the code the till will
// scan, and the sticker taken off again when it should not sell. The prices
// come from pricing-svc; this screen never works one out.

const _reasons = {
  'SHORT_DATED': 'Short dated',
  'CLEARANCE': 'Clearance',
  'DAMAGED_PACK': 'Damaged pack',
  'OVERSTOCK': 'Overstock',
};

String _money(String? currency, double? v) =>
    v == null ? '—' : AppFormat.money(v, currencyCode: currency);

/// A batch's expiry as a date (`14 Sept 2026`), or '—' without one.
String _expiry(String? iso) =>
    iso == null || iso.isEmpty ? '—' : AppFormat.date(iso);

/// A sticker's status in words and tone.
(String, StatusTone) _stickerStatus(String status) => switch (status) {
      'ACTIVE' => ('Active', StatusTone.success),
      'EXPIRED' => ('Expired', StatusTone.neutral),
      'CANCELLED' => ('Taken off', StatusTone.neutral),
      _ => (humanizeCode(status), StatusTone.neutral),
    };

String _fmtQty(double q) =>
    q == q.roundToDouble() ? q.toInt().toString() : q.toStringAsFixed(3);

class InventoryMarkdownTab extends ConsumerStatefulWidget {
  const InventoryMarkdownTab({super.key});

  @override
  ConsumerState<InventoryMarkdownTab> createState() =>
      _InventoryMarkdownTabState();
}

class _InventoryMarkdownTabState extends ConsumerState<InventoryMarkdownTab> {
  String? _storeId;
  int _withinDays = 7;
  String _status = 'ACTIVE';

  void _refresh() {
    if (_storeId == null) return;
    ref.invalidate(markdownPlanProvider);
    ref.invalidate(markdownsProvider);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final storesAsync = ref.watch(storesProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: EdgeInsetsDirectional.fromSTEB(
            context.pageGutter,
            AppSpacing.lg,
            context.pageGutter,
            0,
          ),
          child: Wrap(
            spacing: 12,
            runSpacing: 12,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              SizedBox(
                width: 220,
                child: storesAsync.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text(
                    'Could not load stores',
                    style: TextStyle(color: cs.error),
                  ),
                  data: (stores) => DropdownButtonFormField<String>(
                    key: const Key('markdown-store'),
                    initialValue: _storeId,
                    isExpanded: true,
                    decoration: const InputDecoration(
                      labelText: 'Store',
                      isDense: true,
                      prefixIcon: Icon(Icons.store_outlined),
                    ),
                    items: stores
                        .map(
                          (s) => DropdownMenuItem(
                            value: s.id,
                            child: Text(
                              '${s.name} (${s.code})',
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        )
                        .toList(),
                    onChanged: (v) => setState(() => _storeId = v),
                  ),
                ),
              ),
              SizedBox(
                width: 180,
                child: DropdownButtonFormField<int>(
                  initialValue: _withinDays,
                  isExpanded: true,
                  decoration: const InputDecoration(
                    labelText: 'Expiring within',
                    isDense: true,
                    prefixIcon: Icon(Icons.event_busy_outlined),
                  ),
                  items: const [
                    DropdownMenuItem(value: 3, child: Text('3 days')),
                    DropdownMenuItem(value: 7, child: Text('7 days')),
                    DropdownMenuItem(value: 14, child: Text('14 days')),
                    DropdownMenuItem(value: 30, child: Text('30 days')),
                  ],
                  onChanged: (v) => setState(() => _withinDays = v ?? 7),
                ),
              ),
              OutlinedButton.icon(
                key: const Key('markdown-ladder'),
                onPressed: () => showDialog(
                  context: context,
                  builder: (_) => _LadderDialog(storeId: _storeId),
                ).then((_) => _refresh()),
                icon: const Icon(Icons.stairs_outlined, size: 18),
                label: const Text('Ladder'),
              ),
              IconButton(
                tooltip: 'Refresh',
                onPressed: _refresh,
                icon: const Icon(Icons.refresh),
              ),
            ],
          ),
        ),
        Expanded(
          child: _storeId == null
              ? const Center(
                  child: Text(
                    'Pick a store to see what to reduce this morning.',
                  ),
                )
              : ListView(
                  padding: EdgeInsetsDirectional.fromSTEB(
                    context.pageGutter,
                    AppSpacing.lg,
                    context.pageGutter,
                    AppSpacing.xl,
                  ),
                  children: [
                    Text(
                      'To sticker',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 4),
                    _PlanList(
                      storeId: _storeId!,
                      withinDays: _withinDays,
                      onChanged: _refresh,
                    ),
                    const SizedBox(height: AppSpacing.xl),
                    // The heading, then the filter beside it where there is
                    // room and under it on a phone.
                    Wrap(
                      spacing: AppSpacing.md,
                      runSpacing: AppSpacing.sm,
                      alignment: WrapAlignment.spaceBetween,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        Text(
                          'Stickered',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        SegmentedButton<String>(
                          showSelectedIcon: false,
                          style: const ButtonStyle(
                            visualDensity: VisualDensity.compact,
                          ),
                          segments: const [
                            ButtonSegment(
                              value: 'ACTIVE',
                              label: Text('Active'),
                            ),
                            ButtonSegment(
                              value: 'EXPIRED',
                              label: Text('Expired'),
                            ),
                            ButtonSegment(
                              value: 'CANCELLED',
                              label: Text('Taken off'),
                            ),
                            ButtonSegment(value: '', label: Text('All')),
                          ],
                          selected: {_status},
                          onSelectionChanged: (s) =>
                              setState(() => _status = s.first),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    _MarkdownList(
                      storeId: _storeId!,
                      status: _status,
                      onChanged: _refresh,
                    ),
                  ],
                ),
        ),
      ],
    );
  }
}

class _PlanList extends ConsumerWidget {
  final String storeId;
  final int withinDays;
  final VoidCallback onChanged;
  const _PlanList({
    required this.storeId,
    required this.withinDays,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(
      markdownPlanProvider((storeId: storeId, withinDays: withinDays)),
    );
    return async.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(24),
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => Text(
        friendlyError(e, fallback: 'Could not load the plan.'),
        style: TextStyle(color: cs.error),
      ),
      data: (plan) {
        if (!plan.inventoryReachable) {
          return Card(
            color: cs.errorContainer.withValues(alpha: 0.45),
            child: ListTile(
              leading: Icon(Icons.cloud_off, color: cs.onErrorContainer),
              title: const Text('Inventory could not be read'),
              subtitle: const Text(
                'Nothing to plan from until it can. Try again in a moment.',
              ),
            ),
          );
        }
        if (plan.suggestions.isEmpty) {
          return Card(
            child: ListTile(
              leading: const Icon(Icons.check_circle_outline),
              title: Text('Nothing expiring within $withinDays days'),
              subtitle: Text('Ladder: ${_sourceLabel(plan.ladderSource)}'),
            ),
          );
        }
        final names = ref
            .watch(
              variantLabelsProvider(
                variantIdsKey(plan.suggestions.map((s) => s.variantId)),
              ),
            )
            .value;
        return Column(
          children: [
            for (final s in plan.suggestions)
              Card(
                key: Key('markdown-plan-${s.batchId}'),
                child: ListTile(
                  leading: CircleAvatar(
                    backgroundColor: s.daysToExpiry <= 1
                        ? cs.errorContainer
                        : cs.secondaryContainer,
                    child: Text(
                      '${s.daysToExpiry}d',
                      style: const TextStyle(fontSize: 12),
                    ),
                  ),
                  title: Text(
                    '${names?[s.variantId]?.productName.isNotEmpty == true ? names![s.variantId]!.productName : '…${shortRef(s.variantId)}'}'
                    ' · batch ${s.batchNo ?? '—'}',
                  ),
                  // On a phone the sticker (or the way to make one) goes
                  // under the line: at the end it would take the whole tile.
                  subtitle: context.isCompact
                      ? Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(_planLine(s)),
                            const SizedBox(height: AppSpacing.xs),
                            _planAction(context, s),
                          ],
                        )
                      : Text(_planLine(s)),
                  trailing: context.isCompact ? null : _planAction(context, s),
                ),
              ),
          ],
        );
      },
    );
  }

  /// The sticker already on a batch, or the button that makes one.
  Widget _planAction(BuildContext context, MarkdownSuggestion s) =>
      s.existing != null
          ? Chip(
              avatar: const Icon(Icons.label_outline, size: 16),
              label: Text(
                '${s.existing!.labelCode} · ${_money(s.existing!.currency, s.existing!.markdownPrice)}',
              ),
            )
          : FilledButton.tonal(
              key: Key('markdown-sticker-${s.batchId}'),
              onPressed: s.currentPrice == null
                  ? null
                  : () => showDialog(
                      context: context,
                      builder: (_) => _StickerDialog(
                        storeId: storeId,
                        suggestion: s,
                      ),
                    ).then((_) => onChanged()),
              child: const Text('Sticker'),
            );

  static String _sourceLabel(String source) => switch (source) {
    'STORE' => "this store's own",
    'TENANT' => "the business's",
    _ => 'the default (3 days 25 %, 1 day 50 %, the day 75 %)',
  };

  static String _planLine(MarkdownSuggestion s) {
    final b = StringBuffer();
    b.write('expires ${_expiry(s.expiryDate)} · ${_fmtQty(s.remainingQty)} left');
    if (s.currentPrice == null) {
      b.write(' · no POS price to reduce from');
    } else if (s.suggestedPrice == null) {
      b.write(
        ' · ${_money(s.currency, s.currentPrice)} · no step on the ladder yet',
      );
    } else {
      b.write(
        ' · ${_money(s.currency, s.currentPrice)} → ${_money(s.currency, s.suggestedPrice)}'
        ' (${s.percentOff!.toStringAsFixed(0)} % off, ${s.stepDays}-day step)',
      );
    }
    return b.toString();
  }
}

class _MarkdownList extends ConsumerWidget {
  final String storeId;
  final String status;
  final VoidCallback onChanged;
  const _MarkdownList({
    required this.storeId,
    required this.status,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(
      markdownsProvider((storeId: storeId, status: status)),
    );
    return async.when(
      loading: () => const Padding(
        padding: EdgeInsets.all(24),
        child: Center(child: CircularProgressIndicator()),
      ),
      error: (e, _) => Text(
        friendlyError(e, fallback: 'Could not load markdowns.'),
        style: TextStyle(color: cs.error),
      ),
      data: (rows) {
        if (rows.isEmpty) {
          return const Card(
            child: ListTile(title: Text('No stickers to show.')),
          );
        }
        final names = ref
            .watch(
              variantLabelsProvider(
                variantIdsKey(rows.map((m) => m.variantId)),
              ),
            )
            .value;
        return Column(
          children: [
            for (final m in rows)
              Card(
                key: Key('markdown-${m.id}'),
                child: ListTile(
                  leading: Icon(switch (m.status) {
                    'ACTIVE' => Icons.label,
                    'EXPIRED' => Icons.event_busy,
                    _ => Icons.label_off_outlined,
                  }, color: m.status == 'ACTIVE' ? cs.primary : cs.outline),
                  title: Text(
                    '${m.labelCode} · ${names?[m.variantId]?.productName.isNotEmpty == true ? names![m.variantId]!.productName : '…${shortRef(m.variantId)}'}',
                  ),
                  // The status as a badge under the details, and one action
                  // at the end, so the label keeps the width on a phone.
                  subtitle: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '${_money(m.currency, m.markdownPrice)} (was ${_money(m.currency, m.originalPrice)}, ${m.percentOff.toStringAsFixed(0)} % off)'
                        ' · ${_fmtQty(m.remainingQty)} of ${_fmtQty(m.qty)} left'
                        ' · expires ${_expiry(m.expiryDate)} · ${_reasons[m.reason] ?? m.reason}'
                        '${m.batchNo != null ? ' · batch ${m.batchNo}' : ''}'
                        '${m.cancelReason != null ? ' · taken off: ${m.cancelReason}' : ''}',
                      ),
                      const SizedBox(height: AppSpacing.xs),
                      Builder(builder: (context) {
                        final (words, tone) = _stickerStatus(m.status);
                        return StatusBadge(words, tone: tone);
                      }),
                    ],
                  ),
                  trailing: m.status == 'CANCELLED'
                      ? null
                      : TextButton(
                          key: Key('markdown-cancel-${m.id}'),
                          onPressed: () => showDialog(
                            context: context,
                            builder: (_) => _CancelDialog(markdown: m),
                          ).then((_) => onChanged()),
                          child: const Text('Take off'),
                        ),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _StickerDialog extends ConsumerStatefulWidget {
  final String storeId;
  final MarkdownSuggestion suggestion;
  const _StickerDialog({required this.storeId, required this.suggestion});

  @override
  ConsumerState<_StickerDialog> createState() => _StickerDialogState();
}

class _StickerDialogState extends ConsumerState<_StickerDialog> {
  late final TextEditingController _qty;
  late final TextEditingController _percent;
  final _price = TextEditingController();
  String _reason = 'SHORT_DATED';
  bool _busy = false;
  String? _error;
  Markdown? _issued;

  @override
  void initState() {
    super.initState();
    final s = widget.suggestion;
    _qty = TextEditingController(text: _qty0(s.remainingQty));
    _percent = TextEditingController(
      text: s.percentOff == null ? '' : s.percentOff!.toStringAsFixed(0),
    );
  }

  static String _qty0(double q) =>
      q == q.roundToDouble() ? q.toInt().toString() : q.toString();

  @override
  void dispose() {
    _qty.dispose();
    _percent.dispose();
    _price.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final s = widget.suggestion;
    final qty = double.tryParse(_qty.text.trim());
    final percent = double.tryParse(_percent.text.trim());
    final price = double.tryParse(_price.text.trim());
    if (qty == null || qty <= 0) {
      setState(() => _error = 'How many packs are being stickered?');
      return;
    }
    if ((percent == null) == (price == null)) {
      setState(() => _error = 'Give a percentage off or a price, not both.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final m = await createMarkdown(
        ref,
        storeId: widget.storeId,
        variantId: s.variantId,
        batchId: s.batchId,
        batchNo: s.batchNo,
        expiryDate: s.expiryDate ?? '',
        qty: qty,
        percentOff: percent,
        markdownPrice: price,
        reason: _reason,
      );
      if (mounted) setState(() => _issued = m);
    } catch (e) {
      if (mounted) {
        setState(
          () => _error = friendlyError(
            e,
            fallback: 'The sticker could not be issued.',
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.suggestion;
    final cs = Theme.of(context).colorScheme;
    final issued = _issued;
    if (issued != null) {
      return AlertDialog(
        title: const Text('Sticker issued'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Print this code on ${_qty0(issued.qty)} sticker(s):'),
            const SizedBox(height: 12),
            SelectableText(
              issued.labelCode,
              key: const Key('markdown-issued-code'),
              style: const TextStyle(
                fontFamily: 'monospace',
                fontSize: 28,
                letterSpacing: 2,
              ),
            ),
            const SizedBox(height: 12),
            Text(
              '${_money(issued.currency, issued.markdownPrice)} (was ${_money(issued.currency, issued.originalPrice)}, ${issued.percentOff.toStringAsFixed(0)} % off)',
            ),
            Text(
              'The till reads the price from the code; no promotion applies on top.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
        actions: [
          FilledButton(
            key: const Key('markdown-done'),
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Done'),
          ),
        ],
      );
    }
    return AlertDialog(
      title: const Text('Sticker at a lower price'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Batch ${s.batchNo ?? '—'} · expires ${_expiry(s.expiryDate)} (${s.daysToExpiry} days)'
              ' · ${_fmtQty(s.remainingQty)} left · now ${_money(s.currency, s.currentPrice)}',
            ),
            if (s.suggestedPrice != null)
              Text(
                'The ladder says ${s.percentOff!.toStringAsFixed(0)} % off → ${_money(s.currency, s.suggestedPrice)}',
                style: TextStyle(color: cs.primary),
              ),
            const SizedBox(height: 12),
            TextField(
              key: const Key('markdown-qty'),
              controller: _qty,
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
              decoration: const InputDecoration(labelText: 'Packs to sticker'),
            ),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    key: const Key('markdown-percent'),
                    controller: _percent,
                    keyboardType: const TextInputType.numberWithOptions(
                      decimal: true,
                    ),
                    decoration: const InputDecoration(labelText: '% off'),
                  ),
                ),
                const Padding(
                  padding: EdgeInsets.symmetric(horizontal: 8),
                  child: Text('or'),
                ),
                Expanded(
                  child: TextField(
                    key: const Key('markdown-price'),
                    controller: _price,
                    keyboardType: const TextInputType.numberWithOptions(
                      decimal: true,
                    ),
                    decoration: const InputDecoration(
                      labelText: 'Sticker price',
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            DropdownButtonFormField<String>(
              key: const Key('markdown-reason'),
              initialValue: _reason,
              decoration: const InputDecoration(labelText: 'Reason'),
              items: [
                for (final e in _reasons.entries)
                  DropdownMenuItem(value: e.key, child: Text(e.value)),
              ],
              onChanged: (v) => setState(() => _reason = v ?? 'SHORT_DATED'),
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!, style: TextStyle(color: cs.error)),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('markdown-confirm'),
          onPressed: _busy ? null : _submit,
          child: _busy
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Text('Issue sticker'),
        ),
      ],
    );
  }
}

class _CancelDialog extends ConsumerStatefulWidget {
  final Markdown markdown;
  const _CancelDialog({required this.markdown});

  @override
  ConsumerState<_CancelDialog> createState() => _CancelDialogState();
}

class _CancelDialogState extends ConsumerState<_CancelDialog> {
  final _reason = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_reason.text.trim().isEmpty) {
      setState(() => _error = 'Say why the stickers are coming off.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await cancelMarkdown(ref, widget.markdown.id, _reason.text.trim());
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = friendlyError(
            e,
            fallback: 'The stickers could not be taken off.',
          );
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = widget.markdown;
    return AlertDialog(
      title: const Text('Take the stickers off'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${m.labelCode} stops scanning. What already sold at the price stays sold.',
          ),
          const SizedBox(height: 12),
          TextField(
            key: const Key('markdown-cancel-reason'),
            controller: _reason,
            decoration: const InputDecoration(labelText: 'Reason'),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ],
        ],
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: const Text('Keep'),
        ),
        FilledButton(
          key: const Key('markdown-cancel-confirm'),
          onPressed: _busy ? null : _submit,
          child: const Text('Take off'),
        ),
      ],
    );
  }
}

class _LadderDialog extends ConsumerStatefulWidget {
  final String? storeId;
  const _LadderDialog({required this.storeId});

  @override
  ConsumerState<_LadderDialog> createState() => _LadderDialogState();
}

class _LadderDialogState extends ConsumerState<_LadderDialog> {
  List<(TextEditingController, TextEditingController)>? _rows;
  String? _source;
  bool _forStore = false;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    for (final r in _rows ?? const []) {
      r.$1.dispose();
      r.$2.dispose();
    }
    super.dispose();
  }

  void _load(MarkdownLadder l) {
    if (_rows != null) return;
    _source = l.source;
    _forStore = l.source == 'STORE';
    _rows = [
      for (final s in l.steps)
        (
          TextEditingController(text: s.daysToExpiry.toString()),
          TextEditingController(text: s.percentOff.toStringAsFixed(0)),
        ),
    ];
  }

  Future<void> _save() async {
    final steps = <MarkdownStep>[];
    for (final r in _rows!) {
      final d = int.tryParse(r.$1.text.trim());
      final p = double.tryParse(r.$2.text.trim());
      if (d == null || d < 0 || p == null || p <= 0 || p > 100) {
        setState(
          () => _error =
              'Each step needs days (0 or more) and a percentage off (1–100).',
        );
        return;
      }
      steps.add(MarkdownStep(daysToExpiry: d, percentOff: p));
    }
    if (steps.isEmpty) {
      setState(() => _error = 'A ladder needs at least one step.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await setMarkdownLadder(
        ref,
        _forStore && widget.storeId != null ? widget.storeId! : '',
        steps,
      );
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = friendlyError(e, fallback: 'The ladder could not be saved.');
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(markdownLadderProvider(widget.storeId ?? ''));
    return AlertDialog(
      title: const Text('Markdown ladder'),
      content: SizedBox(
        width: 420,
        child: async.when(
          loading: () => const SizedBox(
            height: 80,
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) => Text(
            friendlyError(e, fallback: 'Could not load the ladder.'),
            style: TextStyle(color: cs.error),
          ),
          data: (l) {
            _load(l);
            return Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'How much off at how many days to expiry. Reading: ${_PlanList._sourceLabel(_source ?? l.source)}.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                const SizedBox(height: 8),
                for (var i = 0; i < _rows!.length; i++)
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          key: Key('ladder-days-$i'),
                          controller: _rows![i].$1,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(
                            labelText: 'Days to expiry',
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: TextField(
                          key: Key('ladder-percent-$i'),
                          controller: _rows![i].$2,
                          keyboardType: TextInputType.number,
                          decoration: const InputDecoration(labelText: '% off'),
                        ),
                      ),
                      IconButton(
                        tooltip: 'Remove step',
                        onPressed: () => setState(() {
                          final r = _rows!.removeAt(i);
                          r.$1.dispose();
                          r.$2.dispose();
                        }),
                        icon: const Icon(Icons.remove_circle_outline),
                      ),
                    ],
                  ),
                TextButton.icon(
                  key: const Key('ladder-add'),
                  onPressed: () => setState(
                    () => _rows!.add((
                      TextEditingController(),
                      TextEditingController(),
                    )),
                  ),
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('Add step'),
                ),
                if (widget.storeId != null)
                  SwitchListTile.adaptive(
                    key: const Key('ladder-for-store'),
                    contentPadding: EdgeInsets.zero,
                    title: const Text('For this store only'),
                    subtitle: const Text('Off: the whole business.'),
                    value: _forStore,
                    onChanged: (v) => setState(() => _forStore = v),
                  ),
                if (_error != null)
                  Text(_error!, style: TextStyle(color: cs.error)),
              ],
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: const Text('Close'),
        ),
        FilledButton(
          key: const Key('ladder-save'),
          onPressed: _busy || _rows == null ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}
