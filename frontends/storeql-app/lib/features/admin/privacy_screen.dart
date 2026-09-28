import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/util/short_ref.dart';
import '../../shared/widgets/status_badge.dart';
import 'customer_providers.dart';

// ---------------------------------------------------------------------------
// The business's side of its customers' privacy under India's DPDP Act (13.12):
// who takes grievances and the period it answers in, the notice per language,
// the queue of requests, and breaches told to customers.
// ---------------------------------------------------------------------------

String _privacy([String suffix = '']) =>
    '/${ApiConstants.customer}/customers/privacy$suffix';

class PrivacySettings {
  final String? grievanceName;
  final String? grievanceEmail;
  final String? grievancePhone;
  final String? grievanceAddress;
  final int responseDays;
  final bool hasGrievanceContact;
  const PrivacySettings({
    this.grievanceName,
    this.grievanceEmail,
    this.grievancePhone,
    this.grievanceAddress,
    required this.responseDays,
    required this.hasGrievanceContact,
  });
  factory PrivacySettings.fromJson(Map<String, dynamic> j) => PrivacySettings(
        grievanceName: j['grievanceName'] as String?,
        grievanceEmail: j['grievanceEmail'] as String?,
        grievancePhone: j['grievancePhone'] as String?,
        grievanceAddress: j['grievanceAddress'] as String?,
        responseDays: (j['responseDays'] as num?)?.toInt() ?? 30,
        hasGrievanceContact: j['hasGrievanceContact'] == true,
      );
}

class PublishedNotice {
  final String language;
  final String languageName;
  final int version;
  final String title;

  /// The notice's text as published: where a correction starts from.
  final String body;
  final String publishedAt;
  const PublishedNotice({
    required this.language,
    required this.languageName,
    required this.version,
    required this.title,
    this.body = '',
    required this.publishedAt,
  });
  factory PublishedNotice.fromJson(Map<String, dynamic> j) => PublishedNotice(
        language: j['language'] as String? ?? '',
        languageName: j['languageName'] as String? ?? '',
        version: (j['version'] as num?)?.toInt() ?? 0,
        title: j['title'] as String? ?? '',
        body: j['body'] as String? ?? '',
        publishedAt: j['publishedAt'] as String? ?? '',
      );
}

class PrivacyRequestRow {
  final String id;
  final String customerId;
  final String kind;
  final String? detail;
  final String? nomineeName;
  final String openedAt;
  final String dueOn;
  final String status;
  final bool overdue;
  final String? resolution;
  const PrivacyRequestRow({
    required this.id,
    required this.customerId,
    required this.kind,
    this.detail,
    this.nomineeName,
    required this.openedAt,
    required this.dueOn,
    required this.status,
    required this.overdue,
    this.resolution,
  });
  factory PrivacyRequestRow.fromJson(Map<String, dynamic> j) =>
      PrivacyRequestRow(
        id: j['id'] as String? ?? '',
        customerId: j['customerId'] as String? ?? '',
        kind: j['kind'] as String? ?? '',
        detail: j['detail'] as String?,
        nomineeName: j['nomineeName'] as String?,
        openedAt: j['openedAt'] as String? ?? '',
        dueOn: j['dueOn'] as String? ?? '',
        status: j['status'] as String? ?? 'OPEN',
        overdue: j['overdue'] == true,
        resolution: j['resolution'] as String?,
      );
}

class BreachIntimation {
  final String id;
  final String subject;
  final String sentAt;
  final int recipients;
  final int failures;
  const BreachIntimation({
    required this.id,
    required this.subject,
    required this.sentAt,
    required this.recipients,
    required this.failures,
  });
  factory BreachIntimation.fromJson(Map<String, dynamic> j) => BreachIntimation(
        id: j['id'] as String? ?? '',
        subject: j['subject'] as String? ?? '',
        sentAt: j['sentAt'] as String? ?? '',
        recipients: (j['recipients'] as num?)?.toInt() ?? 0,
        failures: (j['failures'] as num?)?.toInt() ?? 0,
      );
}

/// English and the Eighth Schedule's twenty-two, as the service offers them.
const privacyLanguages = <String, String>{
  'en': 'English',
  'as': 'Assamese',
  'bn': 'Bengali',
  'brx': 'Bodo',
  'doi': 'Dogri',
  'gu': 'Gujarati',
  'hi': 'Hindi',
  'kn': 'Kannada',
  'ks': 'Kashmiri',
  'kok': 'Konkani',
  'mai': 'Maithili',
  'ml': 'Malayalam',
  'mni': 'Manipuri',
  'mr': 'Marathi',
  'ne': 'Nepali',
  'or': 'Odia',
  'pa': 'Punjabi',
  'sa': 'Sanskrit',
  'sat': 'Santali',
  'sd': 'Sindhi',
  'ta': 'Tamil',
  'te': 'Telugu',
  'ur': 'Urdu',
};

const _kindLabels = <String, String>{
  'ACCESS': 'Access',
  'CORRECTION': 'Correction',
  'ERASURE': 'Erasure',
  'NOMINATION': 'Nomination',
  'GRIEVANCE': 'Grievance',
};

/// How a settled request ended, in words.
const _settledLabels = <String, String>{
  'RESOLVED': 'Resolved',
  'REFUSED': 'Refused',
};

final privacySettingsProvider =
    FutureProvider.autoDispose<PrivacySettings>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(_privacy('/settings'));
  return PrivacySettings.fromJson(resp.data['data'] as Map<String, dynamic>);
});

final privacyNoticesProvider =
    FutureProvider.autoDispose<List<PublishedNotice>>((ref) async {
  final resp = await ref.read(apiClientProvider).dio.get(_privacy('/notices'));
  return [
    for (final j in (resp.data['data'] as List?) ?? const [])
      PublishedNotice.fromJson(j as Map<String, dynamic>)
  ];
});

/// The queue: open requests soonest due first when [status] is OPEN.
final privacyRequestsProvider = FutureProvider.autoDispose
    .family<List<PrivacyRequestRow>, String?>((ref, status) async {
  final resp = await ref.read(apiClientProvider).dio.get(
        _privacy('/requests'),
        queryParameters: {'status': ?status, 'limit': 100},
      );
  return [
    for (final j in (resp.data['data'] as List?) ?? const [])
      PrivacyRequestRow.fromJson(j as Map<String, dynamic>)
  ];
});

final breachIntimationsProvider =
    FutureProvider.autoDispose<List<BreachIntimation>>((ref) async {
  final resp =
      await ref.read(apiClientProvider).dio.get(_privacy('/breach-intimations'));
  return [
    for (final j in (resp.data['data'] as List?) ?? const [])
      BreachIntimation.fromJson(j as Map<String, dynamic>)
  ];
});

class PrivacyScreen extends ConsumerWidget {
  const PrivacyScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    // The page's gutter, the shared heading, and the forms at a form's width
    // on a desktop (their fields ran edge to edge); the lists a little wider.
    return ListView(
      padding: context.pagePadding,
      children: const [
        ContentBounds(
          maxWidth: AppBreakpoints.expanded,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              PageHeader(
                title: 'Privacy',
                subtitle: "Your customers' privacy as India's DPDP Act asks for it, and as good "
                    'sense asks anywhere: who they write to, the notice they read in their '
                    'language, what they ask for, and what they are told of a breach.',
                padding: EdgeInsetsDirectional.only(bottom: AppSpacing.lg),
              ),
              ContentBounds.form(child: _SettingsCard()),
              SizedBox(height: AppSpacing.lg),
              ContentBounds.form(child: _NoticesCard()),
              SizedBox(height: AppSpacing.lg),
              _RequestsCard(),
              SizedBox(height: AppSpacing.lg),
              _IntimationsCard(),
            ],
          ),
        ),
      ],
    );
  }
}

class _SettingsCard extends ConsumerStatefulWidget {
  const _SettingsCard();

  @override
  ConsumerState<_SettingsCard> createState() => _SettingsCardState();
}

class _SettingsCardState extends ConsumerState<_SettingsCard> {
  final _name = TextEditingController();
  final _email = TextEditingController();
  final _phone = TextEditingController();
  final _address = TextEditingController();
  final _days = TextEditingController();
  bool _filled = false;
  bool _busy = false;

  @override
  void dispose() {
    for (final c in [_name, _email, _phone, _address, _days]) {
      c.dispose();
    }
    super.dispose();
  }

  void _fill(PrivacySettings s) {
    if (_filled) return;
    _filled = true;
    _name.text = s.grievanceName ?? '';
    _email.text = s.grievanceEmail ?? '';
    _phone.text = s.grievancePhone ?? '';
    _address.text = s.grievanceAddress ?? '';
    _days.text = '${s.responseDays}';
  }

  Future<void> _save() async {
    setState(() => _busy = true);
    try {
      await ref.read(apiClientProvider).dio.put(_privacy('/settings'), data: {
        'grievanceName': _name.text.trim(),
        'grievanceEmail': _email.text.trim(),
        'grievancePhone': _phone.text.trim(),
        'grievanceAddress': _address.text.trim(),
        'responseDays': int.tryParse(_days.text.trim()),
      });
      ref.invalidate(privacySettingsProvider);
      _say('Saved.');
    } catch (e) {
      _say(friendlyError(e, fallback: 'Could not save the settings.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _say(String t) {
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(t)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final async = ref.watch(privacySettingsProvider);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: async.when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (e, _) => Text(friendlyError(e, fallback: 'Could not load.')),
          data: (s) {
            _fill(s);
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Grievances and the period you answer in',
                    style: theme.textTheme.titleMedium),
                const SizedBox(height: AppSpacing.xs),
                Text(
                  'The person your customers write to with questions and '
                  'grievances (DPDP Act s.8(9)), and the days you give yourself '
                  'to answer a request: ninety at most (Rules r.14).',
                  style: theme.textTheme.bodySmall,
                ),
                const SizedBox(height: AppSpacing.md),
                // 12 between fields, as the other admin forms have them: edge to edge, their
                // outlines touched.
                TextField(
                    key: const Key('privacy-grievance-name'),
                    controller: _name,
                    decoration: const InputDecoration(labelText: 'Name')),
                const SizedBox(height: AppSpacing.md),
                TextField(
                    key: const Key('privacy-grievance-email'),
                    controller: _email,
                    keyboardType: TextInputType.emailAddress,
                    decoration: const InputDecoration(labelText: 'Email')),
                const SizedBox(height: AppSpacing.md),
                TextField(
                    key: const Key('privacy-grievance-phone'),
                    controller: _phone,
                    keyboardType: TextInputType.phone,
                    decoration: const InputDecoration(labelText: 'Phone')),
                const SizedBox(height: AppSpacing.md),
                TextField(
                    key: const Key('privacy-grievance-address'),
                    controller: _address,
                    decoration: const InputDecoration(labelText: 'Address')),
                const SizedBox(height: AppSpacing.md),
                TextField(
                  key: const Key('privacy-response-days'),
                  controller: _days,
                  keyboardType: TextInputType.number,
                  decoration:
                      const InputDecoration(labelText: 'Days to answer a request'),
                ),
                const SizedBox(height: AppSpacing.md),
                Align(
                  alignment: AlignmentDirectional.centerEnd,
                  child: FilledButton(
                    key: const Key('privacy-save-settings'),
                    onPressed: _busy ? null : _save,
                    child: const Text('Save'),
                  ),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}

class _NoticesCard extends ConsumerStatefulWidget {
  const _NoticesCard();

  @override
  ConsumerState<_NoticesCard> createState() => _NoticesCardState();
}

class _NoticesCardState extends ConsumerState<_NoticesCard> {
  String _language = 'en';
  final _title = TextEditingController();
  final _body = TextEditingController();
  bool _busy = false;

  /// The language whose published version the fields were last filled from, and what they were
  /// filled with: a correction starts from the notice as it stands rather than a blank page.
  String? _filledFor;
  String _filledTitle = '';
  String _filledBody = '';

  @override
  void dispose() {
    _title.dispose();
    _body.dispose();
    super.dispose();
  }

  /// Whether the fields still hold what was last put in them, so changing the language may
  /// replace them without losing anything typed.
  bool get _untouched => _title.text == _filledTitle && _body.text == _filledBody;

  /// Fills the fields from [notice] — the published version of the chosen language, or blank when
  /// there is none — unless something has been typed since they were last filled.
  void _fillFrom(PublishedNotice? notice) {
    if (!_untouched) return;
    _filledTitle = notice?.title ?? '';
    _filledBody = notice?.body ?? '';
    _title.text = _filledTitle;
    _body.text = _filledBody;
  }

  Future<void> _publish() async {
    setState(() => _busy = true);
    try {
      await ref.read(apiClientProvider).dio.post(_privacy('/notices'), data: {
        'language': _language,
        'title': _title.text.trim(),
        'body': _body.text.trim(),
      });
      // What was published is now the version the fields stand on.
      _filledFor = _language;
      _filledTitle = _title.text;
      _filledBody = _body.text;
      ref.invalidate(privacyNoticesProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content:
                Text('Published in ${privacyLanguages[_language] ?? _language}.')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
            content: Text(friendlyError(e, fallback: 'Could not publish.'))));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final async = ref.watch(privacyNoticesProvider);
    final published = {
      for (final n in async.value ?? const <PublishedNotice>[]) n.language: n
    };
    // Once the published versions are read, the chosen language's is where the fields start.
    if (async.hasValue && _filledFor != _language) {
      _filledFor = _language;
      _fillFrom(published[_language]);
    }
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('The privacy notice, per language',
                style: theme.textTheme.titleMedium),
            const SizedBox(height: AppSpacing.xs),
            Text(
              'What you collect and why, and how a person exercises their rights '
              '(DPDP Act s.5, Rules r.3). In English, and in any of the Eighth '
              "Schedule's languages a customer may ask for. Every publish is a "
              'new version: a consent names the version the person read.',
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: AppSpacing.sm),
            DropdownButtonFormField<String>(
              key: const Key('privacy-notice-language'),
              isExpanded: true,
              initialValue: _language,
              decoration: const InputDecoration(labelText: 'Language'),
              items: [
                for (final e in privacyLanguages.entries)
                  DropdownMenuItem(
                    value: e.key,
                    child: Text(published.containsKey(e.key)
                        ? '${e.value} — version ${published[e.key]!.version}'
                        : '${e.value} — not yet published'),
                  ),
              ],
              onChanged: (v) => setState(() => _language = v ?? 'en'),
            ),
            const SizedBox(height: AppSpacing.md),
            TextField(
              key: const Key('privacy-notice-title'),
              controller: _title,
              maxLength: 200,
              decoration: const InputDecoration(labelText: 'Title', counterText: ''),
            ),
            const SizedBox(height: AppSpacing.md),
            TextField(
              key: const Key('privacy-notice-body'),
              controller: _body,
              maxLines: 8,
              maxLength: 20000,
              decoration: const InputDecoration(
                  labelText: 'The notice', counterText: '', alignLabelWithHint: true),
            ),
            const SizedBox(height: AppSpacing.md),
            Align(
              alignment: AlignmentDirectional.centerEnd,
              child: FilledButton.icon(
                key: const Key('privacy-publish'),
                onPressed: _busy ? null : _publish,
                icon: const Icon(Icons.publish_outlined),
                label: const Text('Publish'),
              ),
            ),
            const SizedBox(height: AppSpacing.sm),
            async.when(
              loading: () => const SizedBox.shrink(),
              error: (e, _) => Text(friendlyError(e, fallback: 'Could not load.')),
              data: (list) => list.isEmpty
                  ? Text('No notice published yet: consent cannot be informed '
                      'without one.', style: theme.textTheme.bodySmall)
                  : Wrap(
                      spacing: AppSpacing.sm,
                      runSpacing: AppSpacing.xs,
                      children: [
                        for (final n in list)
                          Chip(
                            key: Key('notice-${n.language}'),
                            label: Text('${n.languageName} v${n.version}'),
                          ),
                      ],
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

class _RequestsCard extends ConsumerStatefulWidget {
  const _RequestsCard();

  @override
  ConsumerState<_RequestsCard> createState() => _RequestsCardState();
}

class _RequestsCardState extends ConsumerState<_RequestsCard> {
  bool _openOnly = true;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final status = _openOnly ? 'OPEN' : null;
    final async = ref.watch(privacyRequestsProvider(status));
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                    child: Text('Requests from customers',
                        style: theme.textTheme.titleMedium)),
                SegmentedButton<bool>(
                  segments: const [
                    ButtonSegment(value: true, label: Text('Open')),
                    ButtonSegment(value: false, label: Text('All')),
                  ],
                  selected: {_openOnly},
                  onSelectionChanged: (s) => setState(() => _openOnly = s.first),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.xs),
            Text(
              'Access, correction, erasure, a nomination or a grievance (DPDP Act '
              'ss.11–14), each due by the day your published period runs out. '
              'Soonest due first.',
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: AppSpacing.sm),
            async.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Text(friendlyError(e, fallback: 'Could not load.')),
              data: (list) => list.isEmpty
                  ? Text(_openOnly ? 'Nothing waiting.' : 'No requests yet.',
                      style: theme.textTheme.bodySmall)
                  : Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [for (final r in list) _row(context, r)],
                    ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _row(BuildContext context, PrivacyRequestRow r) {
    final cs = Theme.of(context).colorScheme;
    final open = r.status == 'OPEN';
    final due = AppFormat.date(r.dueOn);
    final settled = _settledLabels[r.status] ?? humanizeCode(r.status);
    return ListTile(
      key: Key('privacy-request-${r.id}'),
      leading: Icon(
        open ? Icons.hourglass_top_outlined : Icons.task_alt,
        color: r.overdue ? cs.error : null,
      ),
      // Who asked, by name: the person the answer goes to.
      title: Text('${_kindLabels[r.kind] ?? humanizeCode(r.kind)} from '
          '${_customerName(ref, r.customerId)}'
          '${r.nomineeName != null ? ' — ${r.nomineeName}' : ''}'),
      subtitle: Text([
        if (r.detail != null) r.detail!,
        open
            ? (r.overdue ? 'Overdue: due $due' : 'Due $due')
            : '$settled: ${r.resolution ?? ''}',
      ].join('\n')),
      isThreeLine: r.detail != null,
      trailing: open
          ? FilledButton.tonal(
              key: Key('resolve-${r.id}'),
              onPressed: () => showDialog<void>(
                context: context,
                builder: (_) => _ResolveDialog(request: r),
              ),
              child: const Text('Answer'),
            )
          : (r.overdue
              ? const StatusBadge('Overdue', tone: StatusTone.error)
              : null),
    );
  }
}

/// A customer by name — their full name, else their email — from their record. While it loads, or
/// when it cannot be read, the end of their id stands in for it, never the whole of it.
String _customerName(WidgetRef ref, String customerId) {
  final placeholder = 'customer #${shortRef(customerId)}';
  if (customerId.isEmpty) return 'a customer';
  final c = ref.watch(customerDetailProvider(customerId)).value;
  if (c == null) return placeholder;
  if (c.fullName.isNotEmpty) return c.fullName;
  if (c.email.trim().isNotEmpty) return c.email.trim();
  return placeholder;
}

class _ResolveDialog extends ConsumerStatefulWidget {
  const _ResolveDialog({required this.request});
  final PrivacyRequestRow request;

  @override
  ConsumerState<_ResolveDialog> createState() => _ResolveDialogState();
}

class _ResolveDialogState extends ConsumerState<_ResolveDialog> {
  String _status = 'RESOLVED';
  final _resolution = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _resolution.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        _privacy('/requests/${widget.request.id}/resolve'),
        data: {'status': _status, 'resolution': _resolution.text.trim()},
      );
      ref.invalidate(privacyRequestsProvider);
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      setState(() => _error = friendlyError(e, fallback: 'Could not answer.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('Answer: ${_kindLabels[widget.request.kind] ?? widget.request.kind}'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SegmentedButton<String>(
            segments: const [
              ButtonSegment(value: 'RESOLVED', label: Text('Resolved')),
              ButtonSegment(value: 'REFUSED', label: Text('Refused')),
            ],
            selected: {_status},
            onSelectionChanged: (s) => setState(() => _status = s.first),
          ),
          const SizedBox(height: AppSpacing.md),
          TextField(
            key: const Key('resolve-text'),
            controller: _resolution,
            maxLines: 4,
            maxLength: 2000,
            decoration: const InputDecoration(
                labelText: 'What was done', counterText: ''),
          ),
          if (_error != null)
            Text(_error!,
                style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ],
      ),
      actions: [
        TextButton(
            onPressed: _busy ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
            key: const Key('resolve-send'),
            onPressed: _busy ? null : _send,
            child: const Text('Record')),
      ],
    );
  }
}

class _IntimationsCard extends ConsumerWidget {
  const _IntimationsCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final async = ref.watch(breachIntimationsProvider);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Breaches told to customers', style: theme.textTheme.titleMedium),
            const SizedBox(height: AppSpacing.xs),
            Text(
              'When personal data is breached, each affected person is told '
              'without delay, in plain words (Rules r.7(1)). What was sent and to '
              'how many is kept for the report to the Board.',
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: AppSpacing.sm),
            async.when(
              loading: () => const SizedBox.shrink(),
              error: (e, _) => Text(friendlyError(e, fallback: 'Could not load.')),
              data: (list) => list.isEmpty
                  // Only the button would show otherwise: say that none were sent.
                  ? Padding(
                      padding: const EdgeInsetsDirectional.only(bottom: AppSpacing.sm),
                      child: Text('No breach has been told to customers.',
                          key: const Key('intimations-none'),
                          style: theme.textTheme.bodySmall),
                    )
                  : Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        for (final i in list)
                          ListTile(
                            key: Key('intimation-${i.id}'),
                            leading: const Icon(Icons.campaign_outlined),
                            title: Text(i.subject),
                            subtitle: Text(
                                'Sent ${AppFormat.dateTime(i.sentAt)} to ${i.recipients}'
                                '${i.failures > 0 ? ', ${i.failures} not reached' : ''}'),
                          ),
                      ],
                    ),
            ),
            Align(
              alignment: AlignmentDirectional.centerEnd,
              child: FilledButton.tonalIcon(
                key: const Key('privacy-tell-customers'),
                onPressed: () => showDialog<void>(
                  context: context,
                  builder: (_) => const BreachIntimationDialog(),
                ),
                icon: const Icon(Icons.campaign_outlined),
                label: const Text('Tell customers of a breach'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Tells every reachable customer of a breach; returns the intimation's id.
class BreachIntimationDialog extends ConsumerStatefulWidget {
  const BreachIntimationDialog({super.key, this.noticeId});

  /// The platform's security notice this answers, when there is one.
  final String? noticeId;

  @override
  ConsumerState<BreachIntimationDialog> createState() =>
      _BreachIntimationDialogState();
}

class _BreachIntimationDialogState extends ConsumerState<BreachIntimationDialog> {
  final _subject = TextEditingController(text: 'About your data');
  final _body = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _subject.dispose();
    _body.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final resp = await ref.read(apiClientProvider).dio.post(
        _privacy('/breach-intimations'),
        data: {
          if (widget.noticeId != null) 'noticeId': widget.noticeId,
          'subject': _subject.text.trim(),
          'body': _body.text.trim(),
        },
      );
      ref.invalidate(breachIntimationsProvider);
      if (mounted) {
        Navigator.of(context)
            .pop((resp.data['data'] as Map<String, dynamic>)['id'] as String?);
      }
    } catch (e) {
      setState(() => _error = friendlyError(e, fallback: 'Could not send.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Tell customers of a breach'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              'Everyone you can reach, by email or text: what happened, what it '
              'may mean for them, what you have done, what they can do, and who '
              'to write to.',
            ),
            const SizedBox(height: AppSpacing.md),
            TextField(
              key: const Key('intimation-subject'),
              controller: _subject,
              maxLength: 200,
              decoration:
                  const InputDecoration(labelText: 'Subject', counterText: ''),
            ),
            const SizedBox(height: AppSpacing.md),
            TextField(
              key: const Key('intimation-body'),
              controller: _body,
              maxLines: 6,
              maxLength: 4000,
              decoration: const InputDecoration(
                  labelText: 'In plain words', counterText: ''),
            ),
            if (_error != null)
              Text(_error!,
                  style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
        ),
      ),
      actions: [
        TextButton(
            onPressed: _busy ? null : () => Navigator.of(context).pop(),
            child: const Text('Cancel')),
        FilledButton(
            key: const Key('intimation-send'),
            onPressed: _busy ? null : _send,
            child: const Text('Send')),
      ],
    );
  }
}
