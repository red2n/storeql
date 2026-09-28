import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import 'privacy_screen.dart';

// ---------------------------------------------------------------------------
// Security notices the platform has sent this business (21.15).
//
// When a security incident affects a business — a breach of its customers'
// data above all, where the business is the controller with 72 hours to tell
// its supervisory authority — the platform sends it a notice here. The owner
// or a manager acknowledges it, once.
// ---------------------------------------------------------------------------

String _notices([String suffix = '']) =>
    '/${ApiConstants.tenant}/admin/tenant/security-notices$suffix';

/// One duty the business owes on a breach notice (13.12), and what it recorded.
class NoticeDuty {
  final String duty;
  final String citation;
  final String summary;
  final String? dueAt;
  final String state;
  final String? doneAt;
  final String? reference;
  const NoticeDuty({
    required this.duty,
    required this.citation,
    required this.summary,
    this.dueAt,
    required this.state,
    this.doneAt,
    this.reference,
  });
  bool get done => state == 'DONE';
  factory NoticeDuty.fromJson(Map<String, dynamic> j) => NoticeDuty(
        duty: j['duty'] as String? ?? '',
        citation: j['citation'] as String? ?? '',
        summary: j['summary'] as String? ?? '',
        dueAt: j['dueAt'] as String?,
        state: j['state'] as String? ?? 'WAITING',
        doneAt: j['doneAt'] as String?,
        reference: j['reference'] as String?,
      );
}

const _dutyLabels = <String, String>{
  'PRINCIPALS_TOLD': 'Each affected person told',
  'BOARD_INTIMATED': 'The Data Protection Board told',
  'BOARD_REPORTED': 'Reported to the Board',
  'AUTHORITY_NOTIFIED': 'The supervisory authority notified',
  'SUBJECTS_TOLD': 'The people affected told',
};

/// The duties that mean telling customers: done through the privacy screen's
/// intimation, whose id becomes the reference.
const _tellingDuties = {'PRINCIPALS_TOLD', 'SUBJECTS_TOLD'};

class SecurityNotice {
  final String id;
  final String incidentId;
  final String title;
  final String body;
  final String issuedAt;
  final String? acknowledgedAt;
  final String? regime;
  final bool binding;
  final String? bindsFrom;
  final List<NoticeDuty> duties;
  const SecurityNotice({
    required this.id,
    required this.incidentId,
    required this.title,
    required this.body,
    required this.issuedAt,
    this.acknowledgedAt,
    this.regime,
    this.binding = true,
    this.bindsFrom,
    this.duties = const [],
  });
  bool get acknowledged => acknowledgedAt != null;
  factory SecurityNotice.fromJson(Map<String, dynamic> j) => SecurityNotice(
        id: j['id'] as String? ?? '',
        incidentId: j['incidentId'] as String? ?? '',
        title: j['title'] as String? ?? '',
        body: j['body'] as String? ?? '',
        issuedAt: j['issuedAt'] as String? ?? '',
        acknowledgedAt: j['acknowledgedAt'] as String?,
        regime: j['regime'] as String?,
        binding: j['binding'] != false,
        bindsFrom: j['bindsFrom'] as String?,
        duties: [
          for (final d in (j['duties'] as List?) ?? const [])
            NoticeDuty.fromJson(d as Map<String, dynamic>)
        ],
      );
}

final securityNoticesProvider =
    FutureProvider.autoDispose<List<SecurityNotice>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(_notices());
  return [
    for (final j in (resp.data['data'] as List?) ?? const [])
      SecurityNotice.fromJson(j as Map<String, dynamic>)
  ];
});

class SecurityNoticesScreen extends ConsumerStatefulWidget {
  const SecurityNoticesScreen({super.key});

  @override
  ConsumerState<SecurityNoticesScreen> createState() =>
      _SecurityNoticesScreenState();
}

class _SecurityNoticesScreenState extends ConsumerState<SecurityNoticesScreen> {
  final Set<String> _busy = {};

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final notices = ref.watch(securityNoticesProvider);
    return ListView(
      padding: context.pagePadding,
      children: [
        Text('Security notices', style: theme.textTheme.headlineMedium),
        const SizedBox(height: 4),
        Text(
          'What the platform has told this business about a security incident '
          'that affects it, and what to do. Where customers’ personal data '
          'was involved, this business is the controller: it decides whether '
          'to tell its supervisory authority, within 72 hours of becoming '
          'aware. Acknowledge each notice once it has been read.',
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
        const SizedBox(height: AppSpacing.lg),
        notices.when(
          loading: () => const LoadingView(label: 'Loading notices…'),
          error: (e, _) => ErrorView(
            message:
                friendlyError(e, fallback: 'Could not load the security notices.'),
            onRetry: () => ref.invalidate(securityNoticesProvider),
          ),
          data: (list) => list.isEmpty
              ? const Text(
                  'The platform has sent this business no security notices.')
              // The theme gives a Card no margin, so the gap is put here.
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    for (var i = 0; i < list.length; i++) ...[
                      if (i > 0) const SizedBox(height: AppSpacing.md),
                      _notice(context, list[i]),
                    ],
                  ],
                ),
        ),
      ],
    );
  }

  Widget _notice(BuildContext context, SecurityNotice n) {
    final theme = Theme.of(context);
    return Card(
      key: Key('notice-${n.id}'),
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(n.title, style: theme.textTheme.titleMedium),
            Text('Sent ${AppFormat.dateTime(n.issuedAt)}',
                style: theme.textTheme.bodySmall),
            const SizedBox(height: AppSpacing.sm),
            Text(n.body),
            const SizedBox(height: AppSpacing.sm),
            n.acknowledged
                ? Chip(
                    label: Text(
                        'Acknowledged ${AppFormat.dateTime(n.acknowledgedAt)}'))
                : FilledButton.icon(
                    key: Key('acknowledge-${n.id}'),
                    onPressed: _busy.contains(n.id) ? null : () => _acknowledge(n),
                    icon: const Icon(Icons.task_alt),
                    label: const Text('Acknowledge'),
                  ),
            if (n.regime != null) ...[
              const SizedBox(height: AppSpacing.md),
              Text(
                n.regime == 'DPDP'
                    ? "Your own duties under India's DPDP Act"
                        '${n.binding ? '' : ' (from ${AppFormat.date(n.bindsFrom)})'}'
                    : 'Your own duties under the GDPR',
                key: Key('duties-${n.id}'),
                style: theme.textTheme.titleSmall,
              ),
              for (final d in n.duties) _duty(context, n, d),
            ],
          ],
        ),
      ),
    );
  }

  Widget _duty(BuildContext context, SecurityNotice n, NoticeDuty d) {
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final overdue = d.state == 'OVERDUE';
    final when = d.done
        ? 'Done ${AppFormat.dateTime(d.doneAt)}'
            '${d.reference != null ? ' · ${d.reference}' : ''}'
        : d.dueAt != null
            ? '${overdue ? 'Overdue: was due' : 'Due'} ${AppFormat.dateTime(d.dueAt)}'
            : 'Without delay';
    // The deadline is what to act on, so it has a line of its own in the
    // title's size — in the error colour once it has passed — and the rule and
    // its citation sit under it in the small grey.
    final titleStyle = theme.textTheme.titleSmall;
    final text = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(_dutyLabels[d.duty] ?? d.duty, style: titleStyle),
        const SizedBox(height: AppSpacing.xs),
        Text(
          when,
          key: Key('due-${n.id}-${d.duty}'),
          style: titleStyle?.copyWith(
            color: overdue
                ? cs.error
                : d.done
                    ? cs.onSurfaceVariant
                    : cs.onSurface,
          ),
        ),
        const SizedBox(height: AppSpacing.xs),
        Text(
          '${d.summary} — ${d.citation}',
          style:
              theme.textTheme.bodySmall?.copyWith(color: cs.onSurfaceVariant),
        ),
      ],
    );
    final action = d.done
        ? null
        : TextButton(
            key: Key('record-${n.id}-${d.duty}'),
            onPressed: _busy.contains(n.id) ? null : () => _record(n, d),
            child: Text(
                _tellingDuties.contains(d.duty) ? 'Tell customers' : 'Record'),
          );
    return Padding(
      key: Key('duty-${n.id}-${d.duty}'),
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
      child: LayoutBuilder(builder: (context, constraints) {
        // Beside the text where there is room; under it on a phone or with
        // large text, where a button at the end would leave the words no width.
        final largeText =
            MediaQuery.textScalerOf(context).scale(16) > 16 * 1.3;
        final stacked = constraints.maxWidth < 480 ||
            (largeText && constraints.maxWidth < AppBreakpoints.expanded);
        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              d.done ? Icons.task_alt : Icons.radio_button_unchecked,
              color: d.done ? null : (overdue ? cs.error : null),
            ),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  text,
                  if (stacked && action != null) action,
                ],
              ),
            ),
            if (!stacked && action != null) ...[
              const SizedBox(width: AppSpacing.md),
              action,
            ],
          ],
        );
      }),
    );
  }

  /// Records a duty done; a telling duty first sends the intimation and keeps
  /// its id as the reference.
  Future<void> _record(SecurityNotice n, NoticeDuty d) async {
    String? reference;
    String? note;
    if (_tellingDuties.contains(d.duty)) {
      reference = await showDialog<String>(
        context: context,
        builder: (_) => BreachIntimationDialog(noticeId: n.id),
      );
      if (reference == null) return;
      note = 'Every reachable customer told through the platform.';
    } else {
      final r = await showDialog<(String?, String?)>(
        context: context,
        builder: (_) => _RecordDutyDialog(label: _dutyLabels[d.duty] ?? d.duty),
      );
      if (r == null) return;
      reference = r.$1;
      note = r.$2;
    }
    setState(() => _busy.add(n.id));
    try {
      await ref.read(apiClientProvider).dio.post(
        _notices('/${n.id}/reports'),
        data: {
          'duty': d.duty,
          if (reference != null && reference.isNotEmpty) 'reference': reference,
          if (note != null && note.isNotEmpty) 'note': note,
        },
      );
      ref.invalidate(securityNoticesProvider);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(friendlyError(e, fallback: 'Could not record that.'))));
      }
    } finally {
      if (mounted) setState(() => _busy.remove(n.id));
    }
  }

  Future<void> _acknowledge(SecurityNotice n) async {
    setState(() => _busy.add(n.id));
    try {
      await ref.read(apiClientProvider).dio.post(_notices('/${n.id}/acknowledge'));
      ref.invalidate(securityNoticesProvider);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(friendlyError(e,
                fallback: 'Could not acknowledge the notice.'))));
      }
    } finally {
      if (mounted) setState(() => _busy.remove(n.id));
    }
  }
}

/// The reference and note for a duty done outside the platform.
class _RecordDutyDialog extends StatefulWidget {
  const _RecordDutyDialog({required this.label});
  final String label;

  @override
  State<_RecordDutyDialog> createState() => _RecordDutyDialogState();
}

class _RecordDutyDialogState extends State<_RecordDutyDialog> {
  final _reference = TextEditingController();
  final _note = TextEditingController();

  @override
  void dispose() {
    _reference.dispose();
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(widget.label),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            key: const Key('duty-reference'),
            controller: _reference,
            maxLength: 120,
            decoration: const InputDecoration(
                labelText: "The Board's or authority's reference", counterText: ''),
          ),
          TextField(
            key: const Key('duty-note'),
            controller: _note,
            maxLines: 3,
            maxLength: 2000,
            decoration: const InputDecoration(labelText: 'Note', counterText: ''),
          ),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
          key: const Key('duty-save'),
          onPressed: () => Navigator.of(context)
              .pop((_reference.text.trim(), _note.text.trim())),
          child: const Text('Record'),
        ),
      ],
    );
  }
}
