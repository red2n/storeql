import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/format.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import 'storefront_providers.dart';

String _remedyWord(String remedy) => switch (remedy) {
  'REFUND' => 'a refund',
  'REPLACEMENT' => 'a replacement',
  'REPAIR' => 'a repair',
  _ => remedy.toLowerCase(),
};

/// A recalled line in words — the product, its lot and its best-before date,
/// the date written the way every other date in the app is, so the sentence
/// never mixes `2026-10-01` with *12 Sept 2026*.
String _describeLine(RecallNoticeLine l) => [
      l.productName ?? 'the product',
      if (l.batchNo != null) 'lot ${l.batchNo}',
      if (l.expiryDate != null) 'best before ${AppFormat.date(l.expiryDate)}',
    ].join(', ');

/// The shopper's product safety recalls (05.10), above their orders: the
/// notice as the shop wrote it — headline first, the product and its lot, the
/// hazard, what to do, where to turn — and the remedy they choose, once.
class RecallNoticesSection extends ConsumerWidget {
  const RecallNoticesSection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(myRecallNoticesProvider);
    // The page's gutter, and the reading width of the orders under it.
    final gutter = context.pageGutter;
    final padding =
        EdgeInsetsDirectional.fromSTEB(gutter, AppSpacing.lg, gutter, 0);
    // A failed load says so: a shopper must never read "no recalls" off an
    // error, which is the one thing this section must not get wrong.
    if (async.hasError) {
      return Padding(
        padding: padding,
        child: ContentBounds.form(
          child: SizedBox(
            width: double.infinity,
            child: Text(
              "Your safety recalls couldn't be checked. ${friendlyError(async.error!)}",
              key: const Key('recall-notices-error'),
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
        ),
      );
    }
    final notices = async.value;
    if (notices == null || notices.isEmpty) return const SizedBox.shrink();
    return Column(
      children: [
        for (final n in notices)
          Padding(
            padding: padding,
            child: ContentBounds.form(child: RecallNoticeCard(notice: n)),
          ),
      ],
    );
  }
}

class RecallNoticeCard extends ConsumerStatefulWidget {
  final MyRecallNotice notice;
  const RecallNoticeCard({super.key, required this.notice});

  @override
  ConsumerState<RecallNoticeCard> createState() => _RecallNoticeCardState();
}

class _RecallNoticeCardState extends ConsumerState<RecallNoticeCard> {
  late MyRecallNotice _notice = widget.notice;
  bool _busy = false;
  String? _error;

  @override
  void didUpdateWidget(RecallNoticeCard old) {
    super.didUpdateWidget(old);
    // A refetch hands the card a fresh notice: show that, not what it last knew.
    if (!identical(old.notice, widget.notice)) {
      _notice = widget.notice;
      _error = null;
    }
  }

  Future<void> _choose(String remedy) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final updated = await chooseMyRecallRemedy(
        ref.read(storefrontDioProvider),
        noticeId: _notice.id,
        remedy: remedy,
      );
      // The shopper may have left the page while the choice was on its way.
      if (!mounted) return;
      setState(() => _notice = updated);
      ref.invalidate(myRecallNoticesProvider);
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = friendlyError(e));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final n = _notice;
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final contact = [n.contactPhone, n.contactUrl].whereType<String>().join(' · ');
    // The card's own error roles: a remedy is the action this red card asks
    // for, so its buttons belong to it rather than to the sage secondary.
    final remedyStyle = FilledButton.styleFrom(
      backgroundColor: cs.error,
      foregroundColor: cs.onError,
    );
    return Card(
      key: Key('recall-notice-${n.id}'),
      color: cs.errorContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.warning_amber_rounded, color: cs.onErrorContainer),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'PRODUCT SAFETY RECALL',
                    style: text.titleMedium?.copyWith(
                      color: cs.onErrorContainer,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 1,
                    ),
                  ),
                ),
                Text(n.reference,
                    style: TextStyle(color: cs.onErrorContainer)),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              n.lines.map(_describeLine).join('; ') +
                  (n.soldAt == null
                      ? ''
                      : ' — bought ${AppFormat.date(n.soldAt!.toIso8601String())}'),
              style: TextStyle(
                color: cs.onErrorContainer,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Stop using this product immediately. ${n.customerNotice}',
              style: TextStyle(color: cs.onErrorContainer),
            ),
            const SizedBox(height: 4),
            Text(
              'Why: ${n.reason}',
              style: TextStyle(color: cs.onErrorContainer),
            ),
            if (contact.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text('Contact: $contact',
                  style: TextStyle(color: cs.onErrorContainer)),
            ],
            const SizedBox(height: 12),
            if (n.isResolved)
              Text(
                switch (n.resolution) {
                  'REFUNDED' => 'Settled: you were refunded.',
                  'REPLACED' => 'Settled: you were given a replacement.',
                  'REPAIRED' => 'Settled: the product was repaired.',
                  _ => 'Settled.',
                },
                key: const Key('recall-notice-settled'),
                style: TextStyle(color: cs.onErrorContainer),
              )
            else if (n.remedy != null)
              Text(
                'You chose ${_remedyWord(n.remedy!)}. Bring the product to any '
                'of our stores${contact.isEmpty ? '' : ', or get in touch: $contact'}.',
                key: const Key('recall-notice-chosen'),
                style: TextStyle(color: cs.onErrorContainer),
              )
            else ...[
              Text(
                'Your remedy — you choose'
                '${n.singleRemedyReason == null ? '' : ' (${n.singleRemedyReason})'}:',
                style: TextStyle(color: cs.onErrorContainer),
              ),
              const SizedBox(height: 4),
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                children: [
                  for (final r in n.remedies)
                    FilledButton(
                      key: Key('recall-choose-$r'),
                      style: remedyStyle,
                      onPressed: _busy ? null : () => _choose(r),
                      child: Text('I want ${_remedyWord(r)}'),
                    ),
                ],
              ),
            ],
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(
                _error!,
                key: const Key('recall-notice-refused'),
                style: TextStyle(color: cs.error),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
