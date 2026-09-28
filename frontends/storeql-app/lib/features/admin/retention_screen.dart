import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/util/status_labels.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import 'retention_providers.dart';

// ---------------------------------------------------------------------------
// Data retention (21.16).
//
// UK GDPR art.5(1)(e): personal data is kept no longer than necessary, and
// art.30 asks for a record of the periods applied. The law also sets floors —
// VAT records six years in the UK, accounting vouchers eight in Germany — so
// a period may be longer than the law's, never shorter. The business sets one
// per class of data here; a hold stops a purge while a matter is open; and
// every purge a service ran is on the register below.
// ---------------------------------------------------------------------------

/// A day as [AppFormat] writes it everywhere else (*11 Sept 2026*).
String _day(DateTime d) => AppFormat.date(d.toIso8601String());

/// A moment as [AppFormat] writes it (*11 Sept 2026 14:05*).
String _moment(DateTime d) => AppFormat.dateTime(d.toIso8601String());

class RetentionScreen extends ConsumerWidget {
  const RetentionScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authNotifierProvider).value;
    final isManager = auth is AuthAuthenticated && auth.isManager;
    final sheet = ref.watch(retentionSheetProvider);
    final runs = ref.watch(retentionRunsProvider);
    final text = Theme.of(context).textTheme;
    final cs = Theme.of(context).colorScheme;
    // A class by its name, looked up from the sheet; a code the sheet does not
    // name (or before it has loaded) reads as words, never as a constant.
    final classNames = {
      for (final c in sheet.value?.classes ?? const <RetentionClass>[])
        c.code: c.name,
    };
    String className(String code) {
      final name = classNames[code];
      return name == null || name.isEmpty ? humanizeCode(code) : name;
    }

    void refresh() {
      ref.invalidate(retentionSheetProvider);
      ref.invalidate(retentionRunsProvider);
    }

    return ListView(
      padding: context.pagePadding,
      children: [
        const PageHeader(
          title: 'Data retention',
          subtitle:
              'How long each class of data is kept. A period may be longer than the law of '
              'the countries this business trades in requires, never shorter; a hold stops a '
              'purge while a matter is open; every purge run is recorded below.',
          // The list is inset by the page gutter already.
          padding: EdgeInsetsDirectional.only(bottom: AppSpacing.lg),
        ),
        if (sheet.hasError)
          ErrorView(
            message: friendlyError(
              sheet.error!,
              fallback: "The retention schedule couldn't be loaded.",
            ),
            onRetry: refresh,
          )
        else if (!sheet.hasValue)
          const LoadingView(label: 'Loading the schedule…')
        else ...[
          Text(
            'Trading in ${sheet.value!.countries.join(', ')}',
            style: text.titleMedium,
          ),
          // The theme's Card has no margin: each card stands 8 below the one
          // above, so their outlines never touch.
          for (final c in sheet.value!.classes)
            Padding(
              padding: const EdgeInsetsDirectional.only(top: AppSpacing.sm),
              child: _ClassCard(
                retentionClass: c,
                canSet: isManager,
                onChanged: refresh,
              ),
            ),
          const SizedBox(height: AppSpacing.lg),
          Text('Holds', style: text.titleMedium),
          if (sheet.value!.holds.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: AppSpacing.sm),
              child: Text('No hold in force. Every purge runs as scheduled.'),
            ),
          for (final h in sheet.value!.holds)
            ListTile(
              key: Key('retention-hold-${h.id}'),
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.pause_circle_outline),
              title: Text(_describeHold(h, className)),
              subtitle: Text(
                '${h.reason}'
                '${h.placedAt == null ? '' : ' · placed ${_day(h.placedAt!)}'}',
              ),
              trailing: isManager
                  ? TextButton(
                      key: Key('retention-hold-release-${h.id}'),
                      onPressed: () => _release(context, ref, h, refresh),
                      child: const Text('Release'),
                    )
                  : null,
            ),
          if (isManager)
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: TextButton.icon(
                key: const Key('retention-hold-place'),
                icon: const Icon(Icons.add),
                label: const Text('Place a hold'),
                onPressed: () async {
                  final placed = await showDialog<bool>(
                    context: context,
                    builder: (_) => _HoldDialog(classes: sheet.value!.classes),
                  );
                  if (placed == true) refresh();
                },
              ),
            ),
        ],
        const SizedBox(height: AppSpacing.lg),
        Text('Purges run', style: text.titleMedium),
        if (isManager)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
            child: Wrap(
              spacing: AppSpacing.sm,
              runSpacing: AppSpacing.sm,
              children: [
                for (final service in retentionSweeps.keys)
                  FilledButton.tonal(
                    key: Key('retention-run-$service'),
                    onPressed: () => _runNow(context, ref, service, refresh),
                    child: Text('Run $service now'),
                  ),
              ],
            ),
          ),
        if (runs.hasError)
          Text(
            friendlyError(runs.error!, fallback: "The register couldn't be loaded."),
            style: TextStyle(color: cs.error),
          )
        else if (!runs.hasValue)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: AppSpacing.sm),
            child: Text('Loading the register…'),
          )
        else if (runs.value!.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: AppSpacing.sm),
            child: Text('No purge has run yet.'),
          )
        else
          for (final r in runs.value!)
            ListTile(
              key: Key('retention-run-row-${r.id}'),
              contentPadding: EdgeInsets.zero,
              dense: true,
              leading: const Icon(Icons.history),
              title: Text(className(r.dataClass)),
              subtitle: Text(
                '${r.rowsAffected} purged · ${r.heldSkipped} held'
                '${r.cutoff == null ? '' : ' · older than ${_day(r.cutoff!)}'}'
                '${r.finishedAt == null ? '' : ' · ${_moment(r.finishedAt!)}'}',
              ),
            ),
      ],
    );
  }

  static String _describeHold(
    RetentionHold h,
    String Function(String code) className,
  ) {
    final what = switch (h.subjectKind) {
      'CUSTOMER' => 'Customer …${shortRef(h.subjectId ?? '')}',
      'ORDER' => 'Order …${shortRef(h.subjectId ?? '')}',
      _ => 'Everything',
    };
    final dataClass = h.dataClass;
    return '$what · ${dataClass == null ? 'every class' : className(dataClass)}';
  }

  Future<void> _release(
    BuildContext context,
    WidgetRef ref,
    RetentionHold h,
    VoidCallback refresh,
  ) async {
    final reason = await showDialog<String>(
      context: context,
      builder: (_) => const _ReasonDialog(
        title: 'Release this hold',
        label: 'Why it can be released',
        action: 'Release',
      ),
    );
    if (reason == null || !context.mounted) return;
    try {
      await releaseRetentionHold(
        ref.read(apiClientProvider).dio,
        holdId: h.id,
        reason: reason,
      );
      refresh();
    } catch (e) {
      if (context.mounted) _snack(context, friendlyError(e));
    }
  }

  Future<void> _runNow(
    BuildContext context,
    WidgetRef ref,
    String service,
    VoidCallback refresh,
  ) async {
    try {
      final run = await runRetentionSweep(ref.read(apiClientProvider).dio, service);
      refresh();
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              '$service: ${run.rowsAffected} purged, ${run.heldSkipped} held',
              key: const Key('retention-run-result'),
            ),
          ),
        );
      }
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

class _ClassCard extends ConsumerWidget {
  final RetentionClass retentionClass;
  final bool canSet;
  final VoidCallback onChanged;

  const _ClassCard({
    required this.retentionClass,
    required this.canSet,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = retentionClass;
    final cs = Theme.of(context).colorScheme;
    final floor = c.floorDays == null
        ? 'No statutory floor: the business decides.'
        : 'The law: at least ${describeDays(c.floorDays!)} (${c.floorScope}) — ${c.floorCitation}';
    final period = c.isSet
        ? c.isKept
              ? 'Kept ${describeDays(c.periodDays!)}; never deleted by the platform.'
              : c.periodDays == 0
              ? '${c.purgeKind == 'DELETE' ? 'Deleted' : 'Anonymised'} at once, by ${c.purgedBy}.'
              : '${c.purgeKind == 'DELETE' ? 'Deleted' : 'Anonymised'} after ${describeDays(c.periodDays!)}, by ${c.purgedBy}.'
        : 'Not set — nothing is purged, and the record art.30 asks for is missing.';
    return Card(
      key: Key('retention-class-${c.code}'),
      child: ListTile(
        title: Text(c.name),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(c.description),
            const SizedBox(height: 4),
            Text(floor, style: TextStyle(color: cs.outline)),
            Text(
              period,
              key: Key('retention-period-${c.code}'),
              style: TextStyle(
                color: c.isSet ? cs.onSurface : cs.error,
                fontWeight: FontWeight.w600,
              ),
            ),
          ],
        ),
        isThreeLine: true,
        trailing: canSet
            ? TextButton(
                key: Key('retention-set-${c.code}'),
                onPressed: () async {
                  final saved = await showDialog<bool>(
                    context: context,
                    builder: (_) => _SetPeriodDialog(retentionClass: c),
                  );
                  if (saved == true) onChanged();
                },
                child: Text(c.isSet ? 'Change' : 'Set'),
              )
            : null,
      ),
    );
  }
}

class _SetPeriodDialog extends ConsumerStatefulWidget {
  final RetentionClass retentionClass;
  const _SetPeriodDialog({required this.retentionClass});

  @override
  ConsumerState<_SetPeriodDialog> createState() => _SetPeriodDialogState();
}

class _SetPeriodDialogState extends ConsumerState<_SetPeriodDialog> {
  late final _days = TextEditingController(
    text: '${widget.retentionClass.periodDays ?? widget.retentionClass.floorDays ?? ''}',
  );
  String? _error;
  bool _saving = false;

  @override
  void dispose() {
    _days.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final c = widget.retentionClass;
    final days = int.tryParse(_days.text.trim());
    if (days == null || days < 0 || days > 36500) {
      setState(() => _error = 'Enter a number of days from 0 to 36500.');
      return;
    }
    if (c.floorDays != null && days < c.floorDays!) {
      setState(
        () => _error =
            'The law requires at least ${c.floorDays} days (${describeDays(c.floorDays!)}) — '
            '${c.floorCitation}. Longer is allowed, shorter is not.',
      );
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await setRetentionPeriod(
        ref.read(apiClientProvider).dio,
        dataClass: c.code,
        periodDays: days,
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
    final c = widget.retentionClass;
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: Text('Keep ${c.name.toLowerCase()} for'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.sm),
              child: Text(
                _error!,
                key: const Key('retention-set-error'),
                style: TextStyle(color: cs.error),
              ),
            ),
          Text(c.description, style: TextStyle(color: cs.outline)),
          const SizedBox(height: AppSpacing.md),
          TextField(
            key: const Key('retention-period'),
            controller: _days,
            keyboardType: TextInputType.number,
            decoration: InputDecoration(
              labelText: 'Days',
              helperText: c.floorDays == null
                  ? '0 means at once'
                  : 'At least ${c.floorDays} (${describeDays(c.floorDays!)}): ${c.floorCitation}',
              helperMaxLines: 3,
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('retention-save'),
          onPressed: _saving ? null : _save,
          child: const Text('Save'),
        ),
      ],
    );
  }
}

class _HoldDialog extends ConsumerStatefulWidget {
  final List<RetentionClass> classes;
  const _HoldDialog({required this.classes});

  @override
  ConsumerState<_HoldDialog> createState() => _HoldDialogState();
}

class _HoldDialogState extends ConsumerState<_HoldDialog> {
  final _subject = TextEditingController();
  final _reason = TextEditingController();
  String? _dataClass;
  String _kind = 'CUSTOMER';
  String? _error;
  bool _saving = false;

  @override
  void dispose() {
    _subject.dispose();
    _reason.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_reason.text.trim().isEmpty) {
      setState(() => _error = 'Say why the hold is placed.');
      return;
    }
    if (_kind != 'ALL' && _subject.text.trim().isEmpty) {
      setState(() => _error = 'Enter the id of the ${_kind.toLowerCase()} held.');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      await placeRetentionHold(
        ref.read(apiClientProvider).dio,
        dataClass: _dataClass,
        subjectKind: _kind,
        subjectId: _kind == 'ALL' ? null : _subject.text.trim(),
        reason: _reason.text,
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
      title: const Text('Place a hold'),
      content: SizedBox(
        width: 480,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                child: Text(
                  _error!,
                  key: const Key('retention-hold-error'),
                  style: TextStyle(color: cs.error),
                ),
              ),
            DropdownButtonFormField<String?>(
              initialValue: _dataClass,
              isExpanded: true,
              decoration: const InputDecoration(labelText: 'Class of data'),
              items: [
                const DropdownMenuItem<String?>(
                  value: null,
                  child: Text('Every class'),
                ),
                for (final c in widget.classes)
                  DropdownMenuItem<String?>(value: c.code, child: Text(c.name)),
              ],
              onChanged: (v) => setState(() => _dataClass = v),
            ),
            const SizedBox(height: AppSpacing.md),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'CUSTOMER', label: Text('A customer')),
                ButtonSegment(value: 'ORDER', label: Text('An order')),
                ButtonSegment(value: 'ALL', label: Text('Everything')),
              ],
              selected: {_kind},
              onSelectionChanged: (s) => setState(() => _kind = s.first),
            ),
            if (_kind != 'ALL')
              TextField(
                key: const Key('retention-hold-subject'),
                controller: _subject,
                decoration: InputDecoration(
                  labelText: _kind == 'CUSTOMER' ? 'Customer id' : 'Order id',
                ),
              ),
            TextField(
              key: const Key('retention-hold-reason'),
              controller: _reason,
              maxLines: 2,
              decoration: const InputDecoration(
                labelText: 'Why *',
                hintText: 'An open dispute, a recall, an investigation',
              ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('retention-hold-save'),
          onPressed: _saving ? null : _save,
          child: const Text('Place hold'),
        ),
      ],
    );
  }
}

class _ReasonDialog extends StatefulWidget {
  final String title;
  final String label;
  final String action;
  const _ReasonDialog({
    required this.title,
    required this.label,
    required this.action,
  });

  @override
  State<_ReasonDialog> createState() => _ReasonDialogState();
}

class _ReasonDialogState extends State<_ReasonDialog> {
  final _ctrl = TextEditingController();

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.title),
    content: TextField(
      key: const Key('retention-reason'),
      controller: _ctrl,
      autofocus: true,
      decoration: InputDecoration(labelText: widget.label),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Cancel'),
      ),
      FilledButton(
        key: const Key('retention-reason-confirm'),
        onPressed: () {
          if (_ctrl.text.trim().isEmpty) return;
          Navigator.pop(context, _ctrl.text.trim());
        },
        child: Text(widget.action),
      ),
    ],
  );
}
