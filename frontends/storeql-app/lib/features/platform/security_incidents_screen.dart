import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';
import '../admin/providers/admin_providers.dart';

// ---------------------------------------------------------------------------
// The platform's security incident register (21.15).
//
// tenant-svc holds the stages the law sets for each kind of incident, with
// their clocks and citations, and works out on every read what is due, done or
// overdue. This screen records what was reported, when and under which
// reference, and tells the businesses an incident affects. The reports
// themselves are made on the authorities' own platforms.
//
// The register is `/platform/security` and one incident is
// `/platform/security/:id` (lib/core/router.dart), so an incident can be
// linked to, reloaded, and left with the browser's back button. Times are
// shown and chosen in the viewer's local time, with the zone named; they
// travel in UTC.
// ---------------------------------------------------------------------------

/// Where the register is, and where one incident is.
const _registerPath = '/platform/security';
String _incidentPath(String id) => '$_registerPath/${Uri.encodeComponent(id)}';

/// What the register is for. Under the title from tablet width; behind an info
/// button on a phone, where it would run to eight lines before the register.
const _about =
    'What the Cyber Resilience Act and GDPR ask the platform to report, '
    'and by when: an early warning within 24 hours of becoming aware of '
    'an actively exploited vulnerability or a severe incident, a '
    'notification within 72, a final report after; and word to every '
    'business a breach of its customers’ data affects. The reports '
    'are made on the authorities’ platforms and recorded here with '
    'their references.';

/// A zone named by its offset from UTC: `UTC`, `UTC+01:00`, `UTC-03:30`.
String utcOffsetLabel(Duration offset) {
  if (offset == Duration.zero) return 'UTC';
  final minutes = offset.inMinutes.abs();
  final hh = (minutes ~/ 60).toString().padLeft(2, '0');
  final mm = (minutes % 60).toString().padLeft(2, '0');
  return 'UTC${offset.isNegative ? '-' : '+'}$hh:$mm';
}

/// The viewer's zone at [at], as the register names it.
String zoneOf(DateTime at) => utcOffsetLabel(at.toLocal().timeZoneOffset);

/// A moment on the incident pages: in local time, as [AppFormat.dateTime]
/// writes it, and — when that moment's offset is not the one in force now,
/// which the page's note names (the other side of a summer-time change) — with
/// its own zone after it: `24 Oct 2026 10:00 (UTC+01:00)`. On a 24- or 72-hour
/// deadline an hour's mislabel matters.
///
/// [now] and [offsetOf] are for tests; the offset is the device's for that
/// moment.
String incidentTime(
  String? iso, {
  DateTime? now,
  Duration Function(DateTime at)? offsetOf,
}) {
  final text = AppFormat.dateTime(iso);
  final at = iso == null ? null : DateTime.tryParse(iso);
  if (at == null) return text;
  final offset = offsetOf ?? (DateTime d) => d.toLocal().timeZoneOffset;
  final own = offset(at);
  return own == offset(now ?? DateTime.now())
      ? text
      : '$text (${utcOffsetLabel(own)})';
}

/// Why a chosen moment would be refused, as tenant-svc refuses it, or null:
/// nothing is recorded as happening later than [now], nor before the platform
/// became aware of the incident ([notBefore]). Null [at] means now.
String? momentProblem(DateTime? at, {required DateTime now, DateTime? notBefore}) {
  if (at == null) return null;
  if (at.isAfter(now)) return 'Not later than now';
  if (notBefore != null && at.isBefore(notBefore)) {
    return 'Not before ${AppFormat.dateTime(notBefore.toIso8601String())}, '
        'when the platform became aware';
  }
  return null;
}

/// *Times are your local time (UTC+01:00).* — said once on each page that
/// shows them.
class _ZoneNote extends StatelessWidget {
  const _ZoneNote();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Text(
      'Times are your local time (${zoneOf(DateTime.now())}).',
      key: const Key('incidents-zone'),
      style: theme.textTheme.bodySmall
          ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
    );
  }
}

const incidentKindLabels = {
  'EXPLOITED_VULNERABILITY': 'Actively exploited vulnerability',
  'SEVERE_INCIDENT': 'Severe incident',
  'PERSONAL_DATA_BREACH': 'Personal data breach',
};

const _stageLabels = {
  'EARLY_WARNING': 'Early warning',
  'NOTIFICATION': 'Notification',
  'FINAL_REPORT': 'Final report',
  'TENANT_NOTICE': 'Businesses told',
};

const _eventLabels = {
  'EARLY_WARNING_SENT': 'Early warning sent',
  'NOTIFICATION_SENT': 'Notification sent',
  'MITIGATION_AVAILABLE': 'Corrective measure available',
  'FINAL_REPORT_SENT': 'Final report sent',
  'TENANTS_NOTIFIED': 'Businesses told',
  'NOTE': 'Note',
  'CLOSED': 'Closed',
};

/// The stage each report completes; the rest are not stages.
const _stageOf = {
  'EARLY_WARNING_SENT': 'EARLY_WARNING',
  'NOTIFICATION_SENT': 'NOTIFICATION',
  'FINAL_REPORT_SENT': 'FINAL_REPORT',
};

String _incidents([String suffix = '']) =>
    '/${ApiConstants.tenant}/platform/security-incidents$suffix';

class IncidentStage {
  final String stage;
  final String summary;
  final String citation;
  final String? dueAt;
  final String? doneAt;
  final String state;

  const IncidentStage({
    required this.stage,
    required this.summary,
    required this.citation,
    this.dueAt,
    this.doneAt,
    required this.state,
  });

  factory IncidentStage.fromJson(Map<String, dynamic> j) => IncidentStage(
        stage: j['stage'] as String? ?? '',
        summary: j['summary'] as String? ?? '',
        citation: j['citation'] as String? ?? '',
        dueAt: j['dueAt'] as String?,
        doneAt: j['doneAt'] as String?,
        state: j['state'] as String? ?? '',
      );
}

class IncidentEntry {
  final String kind;
  final String occurredAt;
  final String? reference;
  final String? note;

  const IncidentEntry(
      {required this.kind, required this.occurredAt, this.reference, this.note});

  factory IncidentEntry.fromJson(Map<String, dynamic> j) => IncidentEntry(
        kind: j['kind'] as String? ?? '',
        occurredAt: j['occurredAt'] as String? ?? '',
        reference: j['reference'] as String?,
        note: j['note'] as String?,
      );
}

class SecurityIncident {
  final String id;
  final String kind;
  final String title;
  final String summary;
  final String awareAt;
  final bool affectsAllTenants;
  final List<String> tenantIds;
  final String status;
  final List<IncidentStage> stages;
  final List<IncidentEntry> events;
  final int noticesIssued;
  final int noticesAcknowledged;

  const SecurityIncident({
    required this.id,
    required this.kind,
    required this.title,
    required this.summary,
    required this.awareAt,
    required this.affectsAllTenants,
    required this.tenantIds,
    required this.status,
    required this.stages,
    required this.events,
    required this.noticesIssued,
    required this.noticesAcknowledged,
  });

  bool get open => status != 'CLOSED';

  factory SecurityIncident.fromJson(Map<String, dynamic> j) => SecurityIncident(
        id: j['id'] as String? ?? '',
        kind: j['kind'] as String? ?? '',
        title: j['title'] as String? ?? '',
        summary: j['summary'] as String? ?? '',
        awareAt: j['awareAt'] as String? ?? '',
        affectsAllTenants: j['affectsAllTenants'] as bool? ?? false,
        tenantIds: [for (final t in (j['tenantIds'] as List?) ?? const []) t as String],
        status: j['status'] as String? ?? '',
        stages: [
          for (final s in (j['stages'] as List?) ?? const [])
            IncidentStage.fromJson(s as Map<String, dynamic>)
        ],
        events: [
          for (final e in (j['events'] as List?) ?? const [])
            IncidentEntry.fromJson(e as Map<String, dynamic>)
        ],
        noticesIssued: (j['noticesIssued'] as num?)?.toInt() ?? 0,
        noticesAcknowledged: (j['noticesAcknowledged'] as num?)?.toInt() ?? 0,
      );
}

class IncidentSummary {
  final String id;
  final String kind;
  final String title;
  final String awareAt;
  final String status;
  final String? nextStage;
  final String? nextDueAt;
  final bool overdue;

  const IncidentSummary({
    required this.id,
    required this.kind,
    required this.title,
    required this.awareAt,
    required this.status,
    this.nextStage,
    this.nextDueAt,
    required this.overdue,
  });

  factory IncidentSummary.fromJson(Map<String, dynamic> j) => IncidentSummary(
        id: j['id'] as String? ?? '',
        kind: j['kind'] as String? ?? '',
        title: j['title'] as String? ?? '',
        awareAt: j['awareAt'] as String? ?? '',
        status: j['status'] as String? ?? '',
        nextStage: j['nextStage'] as String?,
        nextDueAt: j['nextDueAt'] as String?,
        overdue: j['overdue'] as bool? ?? false,
      );
}

/// Incidents by status: `OPEN`, `CLOSED`, or empty for all.
final securityIncidentsProvider = FutureProvider.autoDispose
    .family<List<IncidentSummary>, String>((ref, status) async {
  final resp = await ref.read(apiClientProvider).dio.get(_incidents(),
      queryParameters: {if (status.isNotEmpty) 'status': status});
  return [
    for (final j in (resp.data['data'] as List?) ?? const [])
      IncidentSummary.fromJson(j as Map<String, dynamic>)
  ];
});

final securityIncidentProvider = FutureProvider.autoDispose
    .family<SecurityIncident, String>((ref, id) async {
  final resp = await ref.read(apiClientProvider).dio.get(_incidents('/$id'));
  return SecurityIncident.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// What can still be recorded against an incident, in the order it happens.
/// The server decides; this only keeps impossible choices off the list.
List<String> recordableEvents(SecurityIncident i) {
  final done = {for (final e in i.events) e.kind};
  final stages = {for (final s in i.stages) s.stage};
  return [
    for (final k in const [
      'EARLY_WARNING_SENT',
      'NOTIFICATION_SENT',
      'MITIGATION_AVAILABLE',
      'FINAL_REPORT_SENT'
    ])
      if (!done.contains(k) &&
          (k == 'MITIGATION_AVAILABLE'
              ? i.kind == 'EXPLOITED_VULNERABILITY'
              : stages.contains(_stageOf[k])))
        k,
    'NOTE',
    'CLOSED',
  ];
}

class SecurityIncidentsScreen extends ConsumerStatefulWidget {
  const SecurityIncidentsScreen({super.key});

  @override
  ConsumerState<SecurityIncidentsScreen> createState() =>
      _SecurityIncidentsScreenState();
}

class _SecurityIncidentsScreenState
    extends ConsumerState<SecurityIncidentsScreen> {
  String _status = 'OPEN';

  /// On a phone: whether the explanation is showing.
  bool _aboutOpen = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final compact = context.isCompact;
    final incidents = ref.watch(securityIncidentsProvider(_status));
    return ListView(
      // 16 on a phone, 24 from tablet width.
      padding: context.pagePadding,
      children: [
        PageHeader(
          title: 'Security incidents',
          subtitle: compact ? null : _about,
          // The list's own padding is the gutter.
          padding: const EdgeInsetsDirectional.only(bottom: AppSpacing.lg),
          actions: [
            FilledButton.icon(
              key: const Key('open-incident'),
              onPressed: _open,
              icon: const Icon(Icons.add_moderator_outlined),
              label: const Text('Open incident'),
            ),
            if (compact)
              IconButton(
                key: const Key('incidents-about'),
                isSelected: _aboutOpen,
                icon: const Icon(Icons.info_outline),
                selectedIcon: const Icon(Icons.info),
                tooltip: _aboutOpen
                    ? 'Hide what this register is for'
                    : 'What this register is for',
                onPressed: () => setState(() => _aboutOpen = !_aboutOpen),
              ),
          ],
        ),
        if (compact && _aboutOpen) ...[
          Text(
            _about,
            key: const Key('incidents-about-text'),
            style: theme.textTheme.bodyMedium
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
          const SizedBox(height: AppSpacing.lg),
        ],
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'OPEN', label: Text('Open')),
            ButtonSegment(value: 'CLOSED', label: Text('Closed')),
            ButtonSegment(value: '', label: Text('All')),
          ],
          selected: {_status},
          onSelectionChanged: (s) => setState(() => _status = s.first),
        ),
        const SizedBox(height: AppSpacing.lg),
        incidents.when(
          loading: () => const LoadingView(label: 'Loading incidents…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e,
                fallback: 'Could not load the security incidents.'),
            onRetry: () => ref.invalidate(securityIncidentsProvider(_status)),
          ),
          data: (list) => list.isEmpty
              ? Text(_status == 'OPEN'
                  ? 'No open incidents.'
                  : 'No incidents on the register.')
              : Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Card(
                      margin: EdgeInsets.zero,
                      child: Column(children: [
                        for (final i in list)
                          _SummaryTile(
                              incident: i,
                              onTap: () => context.go(_incidentPath(i.id))),
                      ]),
                    ),
                    const SizedBox(height: AppSpacing.sm),
                    const _ZoneNote(),
                  ],
                ),
        ),
      ],
    );
  }

  Future<void> _open() async {
    final id = await showDialog<String>(
        context: context, builder: (_) => const _OpenIncidentDialog());
    if (id == null || !mounted) return;
    ref.invalidate(securityIncidentsProvider);
    if (id.isNotEmpty) context.go(_incidentPath(id));
  }
}

class _SummaryTile extends StatelessWidget {
  final IncidentSummary incident;
  final VoidCallback onTap;

  const _SummaryTile({required this.incident, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final next = incident.nextStage;
    return ListTile(
      key: Key('incident-${incident.id}'),
      onTap: onTap,
      title: Text(incident.title),
      subtitle: Text([
        incidentKindLabels[incident.kind] ?? incident.kind,
        'aware ${incidentTime(incident.awareAt)}',
        if (next != null)
          'next: ${_stageLabels[next] ?? next} by ${incidentTime(incident.nextDueAt)}',
      ].join(' · ')),
      trailing: incident.overdue
          ? const StatusBadge('Overdue', tone: StatusTone.error)
          : incident.status == 'CLOSED'
              ? const StatusBadge('Closed')
              : const StatusBadge('Open', tone: StatusTone.info),
    );
  }
}

/// One incident at its own address, `/platform/security/:id`: what the law
/// asks and by when, what has been done, and the way back to the register.
class SecurityIncidentDetailScreen extends ConsumerWidget {
  final String id;

  const SecurityIncidentDetailScreen({super.key, required this.id});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final sheet = ref.watch(securityIncidentProvider(id));
    return ListView(
      padding: context.pagePadding,
      children: [
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: TextButton.icon(
              key: const Key('incident-back'),
              onPressed: () => context.go(_registerPath),
              icon: const Icon(Icons.arrow_back),
              label: const Text('All incidents')),
        ),
        sheet.when(
          loading: () => const LoadingView(label: 'Loading the incident…'),
          error: (e, _) => ErrorView(
            message: friendlyError(e, fallback: 'Could not load the incident.'),
            onRetry: () => ref.invalidate(securityIncidentProvider(id)),
          ),
          data: (i) {
            final affected = i.affectsAllTenants
                ? 'every business'
                : '${i.tenantIds.length} ${i.tenantIds.length == 1 ? 'business' : 'businesses'}';
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Semantics(
                  header: true,
                  child: Text(i.title,
                      style: context.isCompact
                          ? theme.textTheme.headlineSmall
                          : theme.textTheme.headlineMedium),
                ),
                const SizedBox(height: AppSpacing.xs),
                Text(
                    '${incidentKindLabels[i.kind] ?? i.kind} · aware ${incidentTime(i.awareAt)} · $affected · ${i.open ? 'open' : 'closed'}'),
                const SizedBox(height: AppSpacing.xs),
                const _ZoneNote(),
                const SizedBox(height: AppSpacing.sm),
                Text(i.summary),
                if (i.open) ...[
                  const SizedBox(height: AppSpacing.lg),
                  Wrap(
                    spacing: AppSpacing.sm,
                    runSpacing: AppSpacing.sm,
                    children: [
                      FilledButton.icon(
                        key: const Key('record-event'),
                        onPressed: () => _changed(context, ref,
                            _RecordDialog(incident: i), 'Recorded.'),
                        icon: const Icon(Icons.edit_note),
                        label: const Text('Record'),
                      ),
                      OutlinedButton.icon(
                        key: const Key('tell-businesses'),
                        onPressed: () => _changed(
                            context, ref, _TellDialog(incident: i), null),
                        icon: const Icon(Icons.campaign_outlined),
                        label: const Text('Tell businesses'),
                      ),
                    ],
                  ),
                ],
                const SizedBox(height: AppSpacing.lg),
                Text('What the law asks', style: theme.textTheme.titleMedium),
                const SizedBox(height: AppSpacing.sm),
                Card(
                    margin: EdgeInsets.zero,
                    child: Column(
                        children: [for (final s in i.stages) _StageTile(stage: s)])),
                const SizedBox(height: AppSpacing.lg),
                Text(
                    'Notices: ${i.noticesIssued} issued, ${i.noticesAcknowledged} acknowledged',
                    key: const Key('notice-counts')),
                const SizedBox(height: AppSpacing.lg),
                Text('Timeline', style: theme.textTheme.titleMedium),
                const SizedBox(height: AppSpacing.sm),
                if (i.events.isEmpty)
                  const Text('Nothing recorded yet.')
                else
                  Card(
                    margin: EdgeInsets.zero,
                    child: Column(children: [
                      for (final e in i.events)
                        ListTile(
                          title: Text(_eventLabels[e.kind] ?? e.kind),
                          subtitle: Text([
                            incidentTime(e.occurredAt),
                            if (e.reference != null) 'ref ${e.reference}',
                            if (e.note != null) e.note!,
                          ].join(' · ')),
                        ),
                    ]),
                  ),
              ],
            );
          },
        ),
      ],
    );
  }

  /// Opens a dialog that writes; when it did, reloads the incident and the
  /// register and says what happened.
  Future<void> _changed(
      BuildContext context, WidgetRef ref, Widget dialog, String? done) async {
    final result = await showDialog<String>(context: context, builder: (_) => dialog);
    if (result == null) return;
    ref.invalidate(securityIncidentProvider(id));
    ref.invalidate(securityIncidentsProvider);
    if (context.mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(done ?? result)));
    }
  }
}

class _StageTile extends StatelessWidget {
  final IncidentStage stage;

  const _StageTile({required this.stage});

  @override
  Widget build(BuildContext context) {
    final (label, tone) = switch (stage.state) {
      'DONE' => ('Done ${incidentTime(stage.doneAt)}', StatusTone.success),
      'DUE' => ('Due ${incidentTime(stage.dueAt)}', StatusTone.warning),
      'OVERDUE' => (
          'Overdue since ${incidentTime(stage.dueAt)}',
          StatusTone.error
        ),
      'WAITING' => ('Waiting', StatusTone.neutral),
      _ => ('No fixed time', StatusTone.neutral),
    };
    final badge = StatusBadge(label, tone: tone);
    final citation =
        Text('${_stageLabels[stage.stage] ?? stage.stage} · ${stage.citation}');
    return LayoutBuilder(builder: (context, constraints) {
      // On a phone, and with large text below laptop width, the state goes
      // under the citation: beside the title it would take the whole row.
      final largeText = MediaQuery.textScalerOf(context).scale(16) > 16 * 1.3;
      final stacked = constraints.maxWidth < AppBreakpoints.medium ||
          (largeText && constraints.maxWidth < AppBreakpoints.expanded);
      return ListTile(
        key: Key('stage-${stage.stage}'),
        title: Text(stage.summary),
        subtitle: stacked
            ? Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  citation,
                  const SizedBox(height: AppSpacing.xs),
                  badge,
                ],
              )
            : citation,
        trailing: stacked ? null : badge,
      );
    });
  }
}

/// A dialog that posts once, shows the server's refusal in place, and closes
/// with a message when it succeeds.
abstract class _PostingDialogState<T extends ConsumerStatefulWidget>
    extends ConsumerState<T> {
  bool busy = false;
  String? error;

  Future<void> post(String path, Map<String, dynamic> body,
      {required String fallback,
      required String Function(Map<String, dynamic> data) done}) async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final resp =
          await ref.read(apiClientProvider).dio.post(path, data: body);
      if (!mounted) return;
      final data = resp.data is Map ? resp.data['data'] : null;
      Navigator.of(context)
          .pop(done(data is Map<String, dynamic> ? data : const {}));
    } catch (e) {
      if (!mounted) return;
      setState(() {
        busy = false;
        error = friendlyError(e, fallback: fallback);
      });
    }
  }

  Widget errorLine(BuildContext context) => error == null
      ? const SizedBox.shrink()
      : Padding(
          padding: const EdgeInsetsDirectional.only(top: AppSpacing.sm),
          child: Text(error!,
              key: const Key('dialog-error'),
              style: TextStyle(color: Theme.of(context).colorScheme.error)),
        );
}

class _RecordDialog extends ConsumerStatefulWidget {
  final SecurityIncident incident;

  const _RecordDialog({required this.incident});

  @override
  ConsumerState<_RecordDialog> createState() => _RecordDialogState();
}

class _RecordDialogState extends _PostingDialogState<_RecordDialog> {
  late final List<String> _options = recordableEvents(widget.incident);
  late String _kind = _options.first;
  final _form = GlobalKey<FormState>();

  /// When it happened, in local time; null is now.
  DateTime? _occurredAt;
  final _reference = TextEditingController();
  final _note = TextEditingController();

  /// When the platform became aware: nothing is reported before it.
  late final DateTime? _aware = DateTime.tryParse(widget.incident.awareAt)?.toLocal();

  @override
  void dispose() {
    _reference.dispose();
    _note.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final aware = _aware;
    return AlertDialog(
      title: const Text('Record'),
      content: SizedBox(
        width: 480,
        child: SingleChildScrollView(
          child: Form(
            key: _form,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                DropdownButtonFormField<String>(
                  key: const Key('record-kind'),
                  isExpanded: true,
                  initialValue: _kind,
                  decoration: const InputDecoration(labelText: 'What happened'),
                  items: [
                    for (final k in _options)
                      DropdownMenuItem(value: k, child: Text(_eventLabels[k] ?? k)),
                  ],
                  onChanged: (v) => setState(() => _kind = v ?? _kind),
                ),
                _MomentField(
                  fieldKey: const Key('record-occurred-at'),
                  label: 'When',
                  blank: 'Now',
                  firstDate: aware ?? DateTime(DateTime.now().year - 5),
                  notBefore: aware,
                  onChanged: (v) => _occurredAt = v,
                ),
                TextField(
                  key: const Key('record-reference'),
                  controller: _reference,
                  maxLength: 120,
                  decoration: const InputDecoration(
                      labelText: 'Authority reference',
                      hintText: 'The case number the report was filed under'),
                ),
                TextField(
                  key: const Key('record-note'),
                  controller: _note,
                  maxLength: 2000,
                  maxLines: 3,
                  decoration: InputDecoration(
                      labelText: _kind == 'NOTE' ? 'Note (required)' : 'Note'),
                ),
                errorLine(context),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: busy ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
          key: const Key('record-submit'),
          onPressed: busy
              ? null
              : () {
                  if (!(_form.currentState?.validate() ?? false)) return;
                  final at = _occurredAt;
                  post(
                    _incidents('/${widget.incident.id}/events'),
                    {
                      'kind': _kind,
                      if (at != null) 'occurredAt': at.toUtc().toIso8601String(),
                      if (_reference.text.trim().isNotEmpty)
                        'reference': _reference.text.trim(),
                      if (_note.text.trim().isNotEmpty) 'note': _note.text.trim(),
                    },
                    fallback: 'Could not record that.',
                    done: (_) => 'Recorded.',
                  );
                },
          child: const Text('Record'),
        ),
      ],
    );
  }
}

class _TellDialog extends ConsumerStatefulWidget {
  final SecurityIncident incident;

  const _TellDialog({required this.incident});

  @override
  ConsumerState<_TellDialog> createState() => _TellDialogState();
}

class _TellDialogState extends _PostingDialogState<_TellDialog> {
  final _message = TextEditingController();

  @override
  void dispose() {
    _message.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final i = widget.incident;
    return AlertDialog(
      title: const Text('Tell businesses'),
      content: SizedBox(
        width: 480,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(i.affectsAllTenants
                ? 'Every business on the platform gets this notice, once.'
                : 'Each of the ${i.tenantIds.length} businesses affected gets this notice, once.'),
            TextField(
              key: const Key('tell-message'),
              controller: _message,
              maxLength: 4000,
              maxLines: 5,
              onChanged: (_) => setState(() {}),
              decoration: const InputDecoration(
                  labelText: 'What happened, and what they should do'),
            ),
            errorLine(context),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: busy ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
          key: const Key('tell-submit'),
          onPressed: busy || _message.text.trim().isEmpty
              ? null
              : () => post(
                    _incidents('/${i.id}/notices'),
                    {'message': _message.text.trim()},
                    fallback: 'Could not send the notices.',
                    done: (d) =>
                        '${d['issued'] ?? 0} new notices; ${d['total'] ?? 0} in all, ${d['acknowledged'] ?? 0} acknowledged.',
                  ),
          child: const Text('Send'),
        ),
      ],
    );
  }
}

class _OpenIncidentDialog extends ConsumerStatefulWidget {
  const _OpenIncidentDialog();

  @override
  ConsumerState<_OpenIncidentDialog> createState() => _OpenIncidentDialogState();
}

class _OpenIncidentDialogState extends _PostingDialogState<_OpenIncidentDialog> {
  final _form = GlobalKey<FormState>();
  String _kind = 'EXPLOITED_VULNERABILITY';
  final _title = TextEditingController();
  final _summary = TextEditingController();

  /// When the platform became aware, in local time, to the minute: now until
  /// it is changed.
  DateTime _awareAt =
      DateTime.now().copyWith(second: 0, millisecond: 0, microsecond: 0);
  final Set<String> _tenants = {};

  @override
  void dispose() {
    _title.dispose();
    _summary.dispose();
    super.dispose();
  }

  String? _required(String? v, int max, String what) {
    final t = v?.trim() ?? '';
    if (t.isEmpty) return '$what is required';
    if (t.length > max) return '$what is at most $max characters';
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final tenants = ref.watch(allTenantsProvider);
    return AlertDialog(
      title: const Text('Open incident'),
      content: SizedBox(
        width: 520,
        child: SingleChildScrollView(
          child: Form(
            key: _form,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                DropdownButtonFormField<String>(
                  key: const Key('incident-kind'),
                  isExpanded: true,
                  initialValue: _kind,
                  decoration: const InputDecoration(labelText: 'Kind'),
                  items: [
                    for (final e in incidentKindLabels.entries)
                      DropdownMenuItem(value: e.key, child: Text(e.value)),
                  ],
                  onChanged: (v) => setState(() => _kind = v ?? _kind),
                ),
                TextFormField(
                  key: const Key('incident-title'),
                  controller: _title,
                  decoration: const InputDecoration(labelText: 'Title'),
                  validator: (v) => _required(v, 200, 'A title'),
                ),
                TextFormField(
                  key: const Key('incident-summary'),
                  controller: _summary,
                  maxLines: 4,
                  decoration: const InputDecoration(labelText: 'What happened'),
                  validator: (v) => _required(v, 4000, 'What happened'),
                ),
                _MomentField(
                  fieldKey: const Key('incident-aware-at'),
                  label: 'Aware since',
                  helper: 'Every deadline runs from this moment.',
                  initialValue: _awareAt,
                  firstDate: DateTime(DateTime.now().year - 5),
                  onChanged: (v) => _awareAt = v ?? _awareAt,
                ),
                const SizedBox(height: AppSpacing.lg),
                Text('Businesses affected — none chosen means every business',
                    style: Theme.of(context).textTheme.labelLarge),
                const SizedBox(height: AppSpacing.sm),
                tenants.when(
                  loading: () => const LinearProgressIndicator(),
                  error: (e, _) => Text(
                    'The businesses could not be loaded, so this incident '
                    'cannot be scoped yet. Close and try again.',
                    key: const Key('tenants-unavailable'),
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  ),
                  data: (list) => Wrap(
                    spacing: AppSpacing.sm,
                    runSpacing: AppSpacing.sm,
                    children: [
                      for (final t in list)
                        FilterChip(
                          key: Key('tenant-${t.id}'),
                          label: Text(t.name),
                          selected: _tenants.contains(t.id),
                          onSelected: (on) => setState(() =>
                              on ? _tenants.add(t.id) : _tenants.remove(t.id)),
                        ),
                    ],
                  ),
                ),
                errorLine(context),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: busy ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
          key: const Key('incident-submit'),
          // Never while the business list is unknown: a failed read must not
          // quietly widen an incident to every business.
          onPressed: busy || !tenants.hasValue
              ? null
              : () {
                  if (!(_form.currentState?.validate() ?? false)) return;
                  post(
                    _incidents(),
                    {
                      'kind': _kind,
                      'title': _title.text.trim(),
                      'summary': _summary.text.trim(),
                      'awareAt': _awareAt.toUtc().toIso8601String(),
                      'tenantIds': _tenants.toList(),
                    },
                    fallback: 'Could not open the incident.',
                    done: (d) => d['id'] as String? ?? '',
                  );
                },
          child: const Text('Open'),
        ),
      ],
    );
  }
}

/// A moment chosen with the date picker and then the time picker, never typed:
/// shown in the viewer's local time with the zone named, as the register shows
/// times, and sent in UTC by whoever holds it. With a [blank] (*Now*) it may be
/// left empty, and a clear button returns to it once a moment is chosen.
class _MomentField extends StatelessWidget {
  /// On the tappable field, for focus and for tests.
  final Key fieldKey;
  final String label;

  /// Said after the zone, under the field.
  final String? helper;
  final DateTime? initialValue;

  /// The earliest day the date picker offers; the latest is today.
  final DateTime firstDate;

  /// Nothing earlier than this is accepted (as the server refuses it).
  final DateTime? notBefore;

  /// What an empty field means, when it may be empty.
  final String? blank;
  final ValueChanged<DateTime?> onChanged;

  const _MomentField({
    required this.fieldKey,
    required this.label,
    this.helper,
    this.initialValue,
    required this.firstDate,
    this.notBefore,
    this.blank,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return FormField<DateTime>(
      initialValue: initialValue,
      validator: (v) => v == null
          ? (blank == null ? 'Choose a date and time' : null)
          : momentProblem(v, now: DateTime.now(), notBefore: notBefore),
      builder: (field) {
        final value = field.value;
        final zone = zoneOf(value ?? DateTime.now());
        void set(DateTime? v) {
          field.didChange(v);
          onChanged(v);
        }

        final shown = value == null
            ? (blank ?? '')
            : AppFormat.dateTime(value.toIso8601String());
        return Semantics(
          button: true,
          child: InkWell(
            key: fieldKey,
            borderRadius: AppRadius.input,
            onTap: () => _pick(field.context, value, set),
            child: InputDecorator(
              decoration: InputDecoration(
                labelText: label,
                helperText: helper == null
                    ? 'Your local time, $zone.'
                    : 'Your local time, $zone. $helper',
                helperMaxLines: 2,
                errorText: field.errorText,
                errorMaxLines: 2,
                suffixIcon: value != null && blank != null
                    ? IconButton(
                        icon: const Icon(Icons.close),
                        tooltip: 'Back to ${blank!.toLowerCase()}',
                        onPressed: () => set(null),
                      )
                    : const Icon(Icons.edit_calendar_outlined),
              ),
              child: Text(shown),
            ),
          ),
        );
      },
    );
  }

  /// The day, then the time of day; nothing changes if either is cancelled.
  Future<void> _pick(BuildContext context, DateTime? value,
      ValueChanged<DateTime?> set) async {
    final now = DateTime.now();
    final first = firstDate.isAfter(now) ? now : firstDate;
    var start = value ?? now;
    if (start.isAfter(now)) start = now;
    if (start.isBefore(first)) start = first;
    final day = await showDatePicker(
      context: context,
      initialDate: start,
      firstDate: first,
      lastDate: now,
      helpText: label,
    );
    if (day == null || !context.mounted) return;
    final time = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(start),
      helpText: '$label · your local time',
    );
    if (time == null) return;
    set(DateTime(day.year, day.month, day.day, time.hour, time.minute));
  }
}
