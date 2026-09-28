import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/legacy.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/util/status_labels.dart';
import 'storefront_providers.dart';

// ---------------------------------------------------------------------------
// A shopper's privacy under India's DPDP Act (13.12), and good sense anywhere:
// the notice in their language, consent by purpose withdrawn in one step, who
// takes their grievances, and the requests they make for their rights.
// ---------------------------------------------------------------------------

String _customer(String path) => '/${ApiConstants.customer}$path';

/// The purpose marketing is consented under; its channels are chosen beneath it.
const marketingPurpose = 'MARKETING';

/// A purpose as the notice words it — *Loyalty: points and store credit on what you buy* — split
/// into its name and what it covers, the second a sentence of its own that starts with a capital:
/// (`Loyalty`, `Points and store credit on what you buy`). Words with no colon are all name.
({String name, String? covers}) purposeParts(String text) {
  final at = text.indexOf(':');
  final name = (at < 0 ? text : text.substring(0, at)).trim();
  final rest = at < 0 ? '' : text.substring(at + 1).trim();
  String capital(String s) => s.isEmpty ? s : s[0].toUpperCase() + s.substring(1);
  if (name.isEmpty) return (name: capital(rest), covers: null);
  return (name: name, covers: rest.isEmpty ? null : capital(rest));
}

// ── Marketing channels (PECR reg.22) ────────────────────────────────────────

/// One marketing channel as the shop currently has it recorded.
class MarketingPreference {
  final String channel;
  final bool granted;
  final String basis;

  const MarketingPreference({
    required this.channel,
    required this.granted,
    required this.basis,
  });

  factory MarketingPreference.fromJson(Map<String, dynamic> json) =>
      MarketingPreference(
        channel: json['channel'] as String? ?? '',
        granted: json['granted'] as bool? ?? false,
        basis: json['basis'] as String? ?? 'NONE',
      );
}

/// The shopper's marketing preferences at the shop they are browsing.
///
/// A channel the shop has never recorded simply has no entry: silence is not
/// consent, so the screen shows it off and sending is refused server-side.
final marketingPreferencesProvider =
    FutureProvider.autoDispose<List<MarketingPreference>?>((ref) async {
  final auth = ref.watch(storefrontAuthProvider);
  if (!auth.isSignedIn) return null;
  final dio = ref.watch(storefrontDioProvider);
  try {
    final resp = await dio.get(_customer('/customers/me/marketing'));
    final data = (resp.data['data'] as List?) ?? const [];
    return data
        .map((e) => MarketingPreference.fromJson(e as Map<String, dynamic>))
        .toList();
  } on DioException catch (e) {
    // 404 means this shop holds no record of them yet — which is not an error,
    // it is a shopper who has never bought here and consented to nothing.
    if (e.response?.statusCode == 404) return const <MarketingPreference>[];
    rethrow;
  }
});

/// The wording a channel is agreed against, recorded with the grant: UK GDPR art.7(1) makes the
/// shop prove what was agreed to.
const marketingNotice = 'Email me about offers, new lines and events at this shop. '
    'I can stop this at any time, from here or from any message.';

/// The channels marketing may use, in the words a shopper chooses them by.
const marketingChannels = <String, ({String label, String detail})>{
  'EMAIL': (label: 'Email', detail: 'Offers and news by email'),
  'SMS': (label: 'Text message', detail: 'Short updates by SMS'),
  'PHONE': (label: 'Phone', detail: 'Marketing calls'),
  'POST': (label: 'Post', detail: 'Leaflets and catalogues'),
};

/// Records channel choices in one request. A grant carries the wording it was agreed against; a
/// withdrawal agrees to nothing, so it carries none.
Future<void> putMarketingChannels(Dio dio, Map<String, bool> choices) => dio.put(
      _customer('/customers/me/marketing'),
      data: {
        'channels': [
          for (final e in choices.entries) {'channel': e.key, 'granted': e.value}
        ],
        'notice': choices.values.any((granted) => granted) ? marketingNotice : null,
      },
    );

/// Said wherever a channel is disabled for want of the Marketing purpose, and
/// wherever the server refuses one switched on for the same reason (409
/// MARKETING_PURPOSE_NOT_GRANTED) — one form of words, never a raw code.
const switchOnMarketingFirst = 'Switch on Marketing first';

/// Turns off every channel that is on, so none is left on once the Marketing
/// purpose is off. customer-svc now does this itself, atomically, on the same
/// transaction as the withdrawal — sending it again here is a no-op once
/// that has already happened, and keeps this screen correct against an older
/// server too; either way, the channels are re-read after, never assumed.
Future<void> withdrawMarketingChannels(WidgetRef ref) async {
  final prefs = await ref.read(marketingPreferencesProvider.future) ??
      const <MarketingPreference>[];
  final on = {for (final p in prefs) if (p.granted) p.channel: false};
  if (on.isNotEmpty) {
    await putMarketingChannels(ref.read(storefrontDioProvider), on);
  }
  ref.invalidate(marketingPreferencesProvider);
}

/// The channels marketing may use — email, text, phone, post — each a PECR reg.22 consent of its
/// own, with the wording they are agreed against beneath them.
///
/// Nested under the Marketing purpose ([purposeGranted] set), a channel follows it: it can be
/// turned on only while Marketing is on, and it can always be turned off. On its own
/// ([purposeGranted] null, when the purposes could not be read) every channel is the shopper's to
/// set, because an opt-out is never hidden behind a failed load.
class MarketingChannels extends ConsumerStatefulWidget {
  const MarketingChannels({super.key, this.purposeGranted, this.busy = false});

  /// Whether the Marketing purpose is on; null when the channels stand alone.
  final bool? purposeGranted;

  /// A write elsewhere on the card is in flight.
  final bool busy;

  @override
  ConsumerState<MarketingChannels> createState() => _MarketingChannelsState();
}

class _MarketingChannelsState extends ConsumerState<MarketingChannels> {
  bool _saving = false;

  Future<void> _set(String channel, bool granted) async {
    setState(() => _saving = true);
    try {
      await putMarketingChannels(ref.read(storefrontDioProvider), {channel: granted});
      ref.invalidate(marketingPreferencesProvider);
      _say(granted
          ? 'Saved. We will only send what you have agreed to.'
          : 'Saved. We will stop sending you these.');
    } catch (e) {
      // Worded the same as the disabled switch's own hint below — never the
      // raw code — whether it is refused because Marketing was withdrawn
      // since this screen last read it, or was never on to begin with.
      _say(apiErrorCode(e) == 'MARKETING_PURPOSE_NOT_GRANTED'
          ? switchOnMarketingFirst
          : friendlyError(e, fallback: 'Could not save that just now.'));
      // The purpose may no longer be what this screen last read — re-read it
      // too, rather than leave a switch showing what the server just refused.
      ref.invalidate(marketingPreferencesProvider);
      ref.invalidate(myPrivacyProvider);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  /// Ends the channels left on under a purpose that is off (a consent staff
  /// recorded, or one older than the purpose), in one request.
  Future<void> _endStale(List<String> channels) async {
    setState(() => _saving = true);
    try {
      await putMarketingChannels(
          ref.read(storefrontDioProvider), {for (final c in channels) c: false});
      ref.invalidate(marketingPreferencesProvider);
      _say('Saved. We will stop sending you these.');
    } catch (e) {
      _say(friendlyError(e, fallback: 'Could not save that just now.'));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  void _say(String text) {
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
    }
  }

  /// `Email`, `Email and Post`, `Email, Phone and Post`.
  static String _inWords(List<String> channels) {
    final names = [for (final c in channels) marketingChannels[c]?.label ?? c];
    if (names.length <= 1) return names.join();
    return '${names.sublist(0, names.length - 1).join(', ')} and ${names.last}';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodySmall
        ?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final nested = widget.purposeGranted != null;
    final waiting = widget.purposeGranted == false;
    // Nested, a channel is set in under its purpose; alone, it lines up with any list tile.
    final start = nested ? AppSpacing.lg + AppSpacing.xl : AppSpacing.lg;
    return ref.watch(marketingPreferencesProvider).when(
          loading: () => const Padding(
            padding: EdgeInsetsDirectional.symmetric(vertical: AppSpacing.lg),
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) => ListTile(
            contentPadding: EdgeInsetsDirectional.only(start: start, end: AppSpacing.lg),
            leading: const Icon(Icons.error_outline),
            title: Text(friendlyError(e, fallback: 'Could not load your preferences.')),
            trailing: TextButton(
              onPressed: () => ref.invalidate(marketingPreferencesProvider),
              child: const Text('Retry'),
            ),
          ),
          data: (prefs) {
            final on = {
              for (final p in prefs ?? const <MarketingPreference>[]) p.channel: p.granted,
            };
            // Channels still on under a purpose that is off: a consent staff
            // recorded, or one from before the purpose was asked. Named, with
            // one tap to end them, rather than a hint that contradicts them.
            final stale = waiting
                ? [
                    for (final c in marketingChannels.keys)
                      if (on[c] ?? false) c,
                  ]
                : const <String>[];
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (stale.isNotEmpty)
                  Padding(
                    key: const Key('marketing-stale'),
                    padding: EdgeInsetsDirectional.fromSTEB(
                        start, 0, AppSpacing.lg, AppSpacing.xs),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '${_inWords(stale)} ${stale.length == 1 ? 'is' : 'are'} '
                          'still on from before Marketing was asked. Turn '
                          '${stale.length == 1 ? 'it' : 'them'} off, or turn on '
                          'Marketing to keep ${stale.length == 1 ? 'it' : 'them'}.',
                          style: muted,
                        ),
                        TextButton(
                          key: const Key('marketing-stale-off'),
                          onPressed: _saving || widget.busy
                              ? null
                              : () => _endStale(stale),
                          child: Text(stale.length == 1
                              ? 'Turn this off'
                              : 'Turn these off'),
                        ),
                      ],
                    ),
                  )
                else if (waiting)
                  Padding(
                    padding: EdgeInsetsDirectional.fromSTEB(
                        start, 0, AppSpacing.lg, AppSpacing.xs),
                    child: Text(
                      'Turn on Marketing to choose how you hear from this shop.',
                      style: muted,
                    ),
                  ),
                for (final entry in marketingChannels.entries)
                  SwitchListTile.adaptive(
                    key: Key('marketing-${entry.key}'),
                    contentPadding:
                        EdgeInsetsDirectional.only(start: start, end: AppSpacing.lg),
                    value: on[entry.key] ?? false,
                    // Off can always be chosen; on only under a purpose that is on.
                    onChanged: _saving ||
                            widget.busy ||
                            (waiting && !(on[entry.key] ?? false))
                        ? null
                        : (v) => _set(entry.key, v),
                    title: Text(entry.value.label),
                    // Off, waiting on the purpose above: say why, in the same
                    // words a refusal to switch it on uses. An explicit colour,
                    // not the tile's own disabled dimming (38% opacity, under
                    // 4.5:1) — the hint must stay as readable as any other text.
                    subtitle: waiting && !(on[entry.key] ?? false)
                        ? Text(switchOnMarketingFirst,
                            key: const Key('marketing-channel-hint'),
                            style: TextStyle(color: theme.colorScheme.onSurfaceVariant))
                        : Text(entry.value.detail),
                  ),
                Padding(
                  padding: EdgeInsetsDirectional.fromSTEB(
                      start, AppSpacing.xs, AppSpacing.lg, AppSpacing.sm),
                  child: Text(marketingNotice, style: muted),
                ),
              ],
            );
          },
        );
  }
}

class PrivacyLanguage {
  final String code;
  final String name;
  final bool published;
  const PrivacyLanguage(this.code, this.name, this.published);
  factory PrivacyLanguage.fromJson(Map<String, dynamic> j) => PrivacyLanguage(
        j['code'] as String? ?? '',
        j['name'] as String? ?? '',
        j['published'] == true,
      );
}

class PrivacyPurpose {
  final String code;
  final String text;
  final bool tracking;
  const PrivacyPurpose(this.code, this.text, this.tracking);
  factory PrivacyPurpose.fromJson(Map<String, dynamic> j) => PrivacyPurpose(
        j['code'] as String? ?? '',
        j['text'] as String? ?? '',
        j['tracking'] == true,
      );
}

class PrivacyNoticeText {
  final String language;
  final String languageName;
  final int version;
  final String title;
  final String body;
  const PrivacyNoticeText({
    required this.language,
    required this.languageName,
    required this.version,
    required this.title,
    required this.body,
  });
  factory PrivacyNoticeText.fromJson(Map<String, dynamic> j) =>
      PrivacyNoticeText(
        language: j['language'] as String? ?? 'en',
        languageName: j['languageName'] as String? ?? '',
        version: (j['version'] as num?)?.toInt() ?? 0,
        title: j['title'] as String? ?? '',
        body: j['body'] as String? ?? '',
      );
}

class GrievanceContact {
  final String? name;
  final String? email;
  final String? phone;
  final String? address;
  final int responseDays;
  final bool hasContact;
  const GrievanceContact({
    this.name,
    this.email,
    this.phone,
    this.address,
    required this.responseDays,
    required this.hasContact,
  });
  factory GrievanceContact.fromJson(Map<String, dynamic> j) =>
      GrievanceContact(
        name: j['grievanceName'] as String?,
        email: j['grievanceEmail'] as String?,
        phone: j['grievancePhone'] as String?,
        address: j['grievanceAddress'] as String?,
        responseDays: (j['responseDays'] as num?)?.toInt() ?? 30,
        hasContact: j['hasGrievanceContact'] == true,
      );
}

/// The notice as served: in the language asked for, else English, else none.
class PrivacyNoticeView {
  final String requested;
  final String? served;
  final PrivacyNoticeText? notice;
  final List<PrivacyLanguage> languages;
  final List<PrivacyPurpose> purposes;
  final GrievanceContact contact;
  final bool dpdp;
  final String? dpdpFrom;
  const PrivacyNoticeView({
    required this.requested,
    this.served,
    this.notice,
    required this.languages,
    required this.purposes,
    required this.contact,
    required this.dpdp,
    this.dpdpFrom,
  });
  factory PrivacyNoticeView.fromJson(Map<String, dynamic> j) =>
      PrivacyNoticeView(
        requested: j['requested'] as String? ?? 'en',
        served: j['served'] as String?,
        notice: j['notice'] == null
            ? null
            : PrivacyNoticeText.fromJson(j['notice'] as Map<String, dynamic>),
        languages: [
          for (final l in (j['languages'] as List?) ?? const [])
            PrivacyLanguage.fromJson(l as Map<String, dynamic>)
        ],
        purposes: [
          for (final p in (j['purposes'] as List?) ?? const [])
            PrivacyPurpose.fromJson(p as Map<String, dynamic>)
        ],
        contact: GrievanceContact.fromJson(
            (j['settings'] as Map<String, dynamic>?) ?? const {}),
        dpdp: j['dpdp'] == true,
        dpdpFrom: j['dpdpFrom'] as String?,
      );
}

class PurposeConsent {
  final String purpose;
  final String text;
  final bool tracking;
  final bool granted;
  const PurposeConsent({
    required this.purpose,
    required this.text,
    required this.tracking,
    required this.granted,
  });
  factory PurposeConsent.fromJson(Map<String, dynamic> j) => PurposeConsent(
        purpose: j['purpose'] as String? ?? '',
        text: j['text'] as String? ?? '',
        tracking: j['tracking'] == true,
        granted: j['granted'] == true,
      );
}

/// What the shopper has agreed to, and what bears on it.
class MyPrivacy {
  final List<PurposeConsent> consents;
  final bool child;
  final bool canTrack;
  final String? guardianName;
  const MyPrivacy({
    required this.consents,
    required this.child,
    required this.canTrack,
    this.guardianName,
  });
  factory MyPrivacy.fromJson(Map<String, dynamic> j) => MyPrivacy(
        consents: [
          for (final c in (j['consents'] as List?) ?? const [])
            PurposeConsent.fromJson(c as Map<String, dynamic>)
        ],
        child: j['child'] == true,
        canTrack: j['canTrack'] == true,
        guardianName:
            (j['guardian'] as Map<String, dynamic>?)?['guardianName'] as String?,
      );
}

class PrivacyRequest {
  final String id;
  final String kind;
  final String? detail;
  final String dueOn;
  final String status;
  final bool overdue;
  final String? resolution;
  const PrivacyRequest({
    required this.id,
    required this.kind,
    this.detail,
    required this.dueOn,
    required this.status,
    required this.overdue,
    this.resolution,
  });
  factory PrivacyRequest.fromJson(Map<String, dynamic> j) => PrivacyRequest(
        id: j['id'] as String? ?? '',
        kind: j['kind'] as String? ?? '',
        detail: j['detail'] as String?,
        dueOn: j['dueOn'] as String? ?? '',
        status: j['status'] as String? ?? 'OPEN',
        overdue: j['overdue'] == true,
        resolution: j['resolution'] as String?,
      );
}

/// The kinds of request, in the words a person asks with.
const privacyRequestKinds = <String, String>{
  'ACCESS': 'A copy of what you hold about me',
  'CORRECTION': 'Correct my details',
  'ERASURE': 'Erase my data',
  'NOMINATION': 'Nominate someone to act for me',
  'GRIEVANCE': 'Raise a grievance',
};

String privacyRequestLabel(String kind) =>
    privacyRequestKinds[kind] ?? humanizeCode(kind);

/// How a settled request ended, in words — the words the shop's own privacy
/// screen uses.
String privacyRequestOutcome(String status) => switch (status.toUpperCase()) {
      'RESOLVED' => 'Answered',
      'REFUSED' => 'Refused',
      'CLOSED' => 'Closed',
      _ => humanizeCode(status),
    };

/// The language the shopper reads the notice in.
final privacyLanguageProvider = StateProvider<String>((_) => 'en');

/// The notice, public: read before signing up.
final privacyNoticeProvider =
    FutureProvider.autoDispose<PrivacyNoticeView>((ref) async {
  final language = ref.watch(privacyLanguageProvider);
  final resp = await ref.watch(storefrontDioProvider).get(
        _customer('/customers/privacy/notice'),
        queryParameters: {'language': language},
      );
  return PrivacyNoticeView.fromJson(resp.data['data'] as Map<String, dynamic>);
});

/// The signed-in shopper's consents; null when the shop holds no record yet.
final myPrivacyProvider = FutureProvider.autoDispose<MyPrivacy?>((ref) async {
  if (!ref.watch(storefrontAuthProvider).isSignedIn) return null;
  final dio = ref.watch(storefrontDioProvider);
  try {
    final resp = await dio.get(_customer('/customers/me/privacy'));
    return MyPrivacy.fromJson(resp.data['data'] as Map<String, dynamic>);
  } on DioException catch (e) {
    if (e.response?.statusCode == 404) return null;
    rethrow;
  }
});

final myPrivacyRequestsProvider =
    FutureProvider.autoDispose<List<PrivacyRequest>>((ref) async {
  if (!ref.watch(storefrontAuthProvider).isSignedIn) return const [];
  final resp = await ref
      .watch(storefrontDioProvider)
      .get(_customer('/customers/me/privacy/requests'));
  return [
    for (final r in (resp.data['data'] as List?) ?? const [])
      PrivacyRequest.fromJson(r as Map<String, dynamic>)
  ];
});

/// The notice in the shopper's language, the purposes, and who to write to.
class PrivacyNoticeSection extends ConsumerWidget {
  const PrivacyNoticeSection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodyMedium
        ?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final async = ref.watch(privacyNoticeProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Your privacy notice', style: theme.textTheme.titleLarge),
        const SizedBox(height: AppSpacing.xs),
        Text(
          'What this shop collects, why, and how to exercise your rights — '
          'in the language you choose.',
          style: muted,
        ),
        const SizedBox(height: AppSpacing.md),
        async.when(
          // A new language keeps the card — and the open notice, and the
          // picker — up while it loads, so the text they asked to read in it
          // is not folded away again.
          skipLoadingOnReload: true,
          loading: () => const Padding(
            padding: EdgeInsetsDirectional.symmetric(vertical: AppSpacing.xl),
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) => ListTile(
            leading: const Icon(Icons.error_outline),
            title: Text(friendlyError(e,
                fallback: 'Could not load the privacy notice.')),
            trailing: TextButton(
              onPressed: () => ref.invalidate(privacyNoticeProvider),
              child: const Text('Retry'),
            ),
          ),
          data: (view) => _notice(context, ref, view, muted),
        ),
      ],
    );
  }

  Widget _notice(BuildContext context, WidgetRef ref, PrivacyNoticeView view,
      TextStyle? muted) {
    final theme = Theme.of(context);
    final published = view.languages.where((l) => l.published).toList();
    final choices = published.isEmpty
        ? view.languages.where((l) => l.code == 'en').toList()
        : published;
    final current = choices.any((l) => l.code == view.requested)
        ? view.requested
        : (view.served ?? 'en');
    final notice = view.notice;
    return Card(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsetsDirectional.fromSTEB(
                AppSpacing.lg, AppSpacing.lg, AppSpacing.lg, 0),
            child: DropdownButtonFormField<String>(
              key: const Key('privacy-language'),
              isExpanded: true,
              initialValue: current,
              decoration: const InputDecoration(labelText: 'Language'),
              items: [
                for (final l in choices)
                  DropdownMenuItem(value: l.code, child: Text(l.name)),
              ],
              onChanged: (v) {
                if (v != null) {
                  ref.read(privacyLanguageProvider.notifier).state = v;
                }
              },
            ),
          ),
          if (notice != null && view.served != view.requested)
            Padding(
              padding: const EdgeInsetsDirectional.fromSTEB(
                  AppSpacing.lg, AppSpacing.sm, AppSpacing.lg, 0),
              child: Text(
                'Not yet in that language; shown in ${notice.languageName}.',
                style: muted,
              ),
            ),
          // The full notice waits behind its title, so on a phone the switches below are not a
          // screen or more away. One tap opens it; nothing in it is hidden for good.
          ExpansionTile(
            key: const Key('privacy-notice'),
            shape: const Border(),
            collapsedShape: const Border(),
            tilePadding:
                const EdgeInsetsDirectional.symmetric(horizontal: AppSpacing.lg),
            childrenPadding: const EdgeInsetsDirectional.fromSTEB(
                AppSpacing.lg, 0, AppSpacing.lg, AppSpacing.lg),
            expandedAlignment: AlignmentDirectional.centerStart,
            expandedCrossAxisAlignment: CrossAxisAlignment.start,
            title: Text(
              notice?.title ?? 'What we ask, and who to ask',
              style: theme.textTheme.titleMedium,
            ),
            subtitle: Text(
              notice == null
                  ? 'This shop has not published its notice yet.'
                  : 'Version ${notice.version}. What this shop collects, why, and who to ask.',
            ),
            children: [
              if (notice != null) ...[
                Text(notice.body),
                const SizedBox(height: AppSpacing.md),
              ],
              Text('What we ask your consent for', style: theme.textTheme.titleSmall),
              for (final p in view.purposes)
                Padding(
                  padding: const EdgeInsetsDirectional.only(top: AppSpacing.xs),
                  child: Text('• ${p.text}'),
                ),
              const SizedBox(height: AppSpacing.md),
              Text('Questions or grievances', style: theme.textTheme.titleSmall),
              const SizedBox(height: AppSpacing.xs),
              if (!view.contact.hasContact)
                Text('This shop has not named a contact yet.', style: muted)
              else
                Text([
                  if (view.contact.name != null) view.contact.name!,
                  if (view.contact.email != null) view.contact.email!,
                  if (view.contact.phone != null) view.contact.phone!,
                  if (view.contact.address != null) view.contact.address!,
                ].join(' · ')),
              Text(
                'A request is answered within ${view.contact.responseDays} days.',
                style: muted,
              ),
              if (view.dpdp || view.dpdpFrom != null) ...[
                const SizedBox(height: AppSpacing.sm),
                Text(
                  view.dpdp
                      ? "India's Digital Personal Data Protection Act binds this shop. "
                          'You may complain to the Data Protection Board of India '
                          'if a grievance is not answered.'
                      : "India's Digital Personal Data Protection Act binds this "
                          'shop from ${AppFormat.date(view.dpdpFrom)}.',
                  style: muted,
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }
}

/// Consent by purpose, each its own switch, and all of it withdrawn at once.
class ConsentsSection extends ConsumerStatefulWidget {
  const ConsentsSection({super.key});

  @override
  ConsumerState<ConsentsSection> createState() => _ConsentsSectionState();
}

class _ConsentsSectionState extends ConsumerState<ConsentsSection> {
  bool _busy = false;

  Future<void> _choose(String purpose, bool granted) async {
    setState(() => _busy = true);
    try {
      await ref.read(storefrontDioProvider).put(
        _customer('/customers/me/privacy/consents'),
        data: {
          'choices': [
            {'purpose': purpose, 'granted': granted}
          ],
          'language': ref.read(privacyLanguageProvider),
        },
      );
      ref.invalidate(myPrivacyProvider);
      // Marketing off is every channel off: one is never left on without the other.
      if (purpose == marketingPurpose && !granted) {
        await withdrawMarketingChannels(ref);
      }
      _say(granted
          ? (purpose == marketingPurpose ? 'Saved. Now choose the channels below.' : 'Saved.')
          : 'Withdrawn.');
    } catch (e) {
      _say(friendlyError(e, fallback: 'Could not save that just now.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _withdrawAll() async {
    setState(() => _busy = true);
    try {
      await ref
          .read(storefrontDioProvider)
          .delete(_customer('/customers/me/privacy/consents'));
      ref.invalidate(myPrivacyProvider);
      // Every consent includes the channels marketing is sent on.
      await withdrawMarketingChannels(ref);
      _say('Every consent withdrawn.');
    } catch (e) {
      _say(friendlyError(e, fallback: 'Could not withdraw just now.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _say(String text) {
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text(text)));
    }
  }

  /// One purpose: its name, and what it covers as a sentence of its own.
  Widget _purposeTile(PurposeConsent c, MyPrivacy mine) {
    final parts = purposeParts(c.text);
    return SwitchListTile.adaptive(
      key: Key('consent-${c.purpose}'),
      value: c.granted,
      onChanged: _busy || (c.tracking && !mine.canTrack)
          ? null
          : (v) => _choose(c.purpose, v),
      title: Text(parts.name),
      subtitle: parts.covers == null ? null : Text(parts.covers!),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodyMedium
        ?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final async = ref.watch(myPrivacyProvider);
    final channelsOn = ref
            .watch(marketingPreferencesProvider)
            .value
            ?.any((p) => p.granted) ??
        false;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Your consents', style: theme.textTheme.titleLarge),
        const SizedBox(height: AppSpacing.xs),
        Text(
          'Each purpose on its own. Withdrawing is as easy as giving: one tap, '
          'nothing to fill in.',
          style: muted,
        ),
        const SizedBox(height: AppSpacing.md),
        async.when(
          loading: () => const Padding(
            padding: EdgeInsetsDirectional.symmetric(vertical: AppSpacing.xl),
            child: Center(child: CircularProgressIndicator()),
          ),
          error: (e, _) => ListTile(
            leading: const Icon(Icons.error_outline),
            title: Text(friendlyError(e, fallback: 'Could not load your consents.')),
            trailing: TextButton(
              onPressed: () => ref.invalidate(myPrivacyProvider),
              child: const Text('Retry'),
            ),
          ),
          data: (mine) {
            if (mine == null) {
              return Text('Sign in to see what you have agreed to.', style: muted);
            }
            return Card(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (mine.child && !mine.canTrack)
                    const ListTile(
                      key: Key('privacy-child'),
                      leading: Icon(Icons.family_restroom_outlined),
                      title: Text('A parent or guardian must consent first'),
                      subtitle: Text(
                        'You are under 18. Offers, personalisation and analytics '
                        'stay off until a parent or guardian gives consent at the '
                        'shop. Loyalty is yours to choose.',
                      ),
                    ),
                  if (mine.child && mine.canTrack)
                    ListTile(
                      leading: const Icon(Icons.family_restroom_outlined),
                      title: Text('${mine.guardianName} has consented for you'),
                    ),
                  for (final c in mine.consents) ...[
                    _purposeTile(c, mine),
                    // Marketing is asked once: its channels are chosen beneath it and follow it.
                    if (c.purpose == marketingPurpose)
                      MarketingChannels(purposeGranted: c.granted, busy: _busy),
                  ],
                  Padding(
                    padding: const EdgeInsets.all(AppSpacing.sm),
                    child: Align(
                      alignment: AlignmentDirectional.centerEnd,
                      child: TextButton.icon(
                        key: const Key('privacy-withdraw-all'),
                        onPressed: _busy ||
                                !(mine.consents.any((c) => c.granted) || channelsOn)
                            ? null
                            : _withdrawAll,
                        icon: const Icon(Icons.block_outlined),
                        label: const Text('Withdraw every consent'),
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ],
    );
  }
}

/// What the shopper has asked for, and the form to ask.
class RequestsSection extends ConsumerWidget {
  const RequestsSection({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final muted = theme.textTheme.bodyMedium
        ?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    final async = ref.watch(myPrivacyRequestsProvider);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Your requests', style: theme.textTheme.titleLarge),
        const SizedBox(height: 4),
        Text(
          'Ask for a copy of your data, a correction, erasure, to nominate '
          'someone to act for you, or to raise a grievance. Each is answered '
          'by the day shown.',
          style: muted,
        ),
        const SizedBox(height: 12),
        async.when(
          loading: () => const SizedBox.shrink(),
          error: (e, _) => Text(
              friendlyError(e, fallback: 'Could not load your requests.'),
              style: muted),
          data: (requests) => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final r in requests)
                Card(
                  key: Key('request-${r.id}'),
                  child: ListTile(
                    leading: Icon(r.status == 'OPEN'
                        ? Icons.hourglass_top_outlined
                        : Icons.task_alt),
                    title: Text(privacyRequestLabel(r.kind)),
                    subtitle: Text(r.status == 'OPEN'
                        ? (r.overdue
                            ? 'Overdue: was due by ${AppFormat.date(r.dueOn)}'
                            : 'Due by ${AppFormat.date(r.dueOn)}')
                        : [
                            privacyRequestOutcome(r.status),
                            if ((r.resolution ?? '').trim().isNotEmpty)
                              r.resolution!.trim(),
                          ].join(': ')),
                  ),
                ),
            ],
          ),
        ),
        const SizedBox(height: 8),
        FilledButton.tonalIcon(
          key: const Key('privacy-ask'),
          onPressed: () => showDialog<void>(
            context: context,
            builder: (_) => const RequestDialog(),
          ),
          icon: const Icon(Icons.gavel_outlined),
          label: const Text('Ask for your rights'),
        ),
      ],
    );
  }
}

class RequestDialog extends ConsumerStatefulWidget {
  const RequestDialog({super.key});

  @override
  ConsumerState<RequestDialog> createState() => _RequestDialogState();
}

class _RequestDialogState extends ConsumerState<RequestDialog> {
  String _kind = 'ACCESS';
  final _detail = TextEditingController();
  final _nominee = TextEditingController();
  final _nomineeContact = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _detail.dispose();
    _nominee.dispose();
    _nomineeContact.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(storefrontDioProvider).post(
        _customer('/customers/me/privacy/requests'),
        data: {
          'kind': _kind,
          if (_detail.text.trim().isNotEmpty) 'detail': _detail.text.trim(),
          if (_kind == 'NOMINATION') 'nomineeName': _nominee.text.trim(),
          if (_kind == 'NOMINATION' && _nomineeContact.text.trim().isNotEmpty)
            'nomineeContact': _nomineeContact.text.trim(),
        },
      );
      ref.invalidate(myPrivacyRequestsProvider);
      if (mounted) Navigator.of(context).pop();
    } catch (e) {
      setState(() => _error =
          friendlyError(e, fallback: 'Could not send the request just now.'));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Ask for your rights'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            DropdownButtonFormField<String>(
              key: const Key('request-kind'),
              isExpanded: true,
              initialValue: _kind,
              items: [
                for (final e in privacyRequestKinds.entries)
                  DropdownMenuItem(value: e.key, child: Text(e.value)),
              ],
              onChanged: (v) => setState(() => _kind = v ?? 'ACCESS'),
              decoration: const InputDecoration(labelText: 'Request'),
            ),
            TextField(
              key: const Key('request-detail'),
              controller: _detail,
              maxLines: 3,
              maxLength: 2000,
              decoration: const InputDecoration(
                  labelText: 'Tell us more (optional)', counterText: ''),
            ),
            if (_kind == 'NOMINATION') ...[
              TextField(
                key: const Key('request-nominee'),
                controller: _nominee,
                decoration: const InputDecoration(labelText: 'Who may act for you'),
              ),
              TextField(
                controller: _nomineeContact,
                decoration:
                    const InputDecoration(labelText: 'How to reach them (optional)'),
              ),
            ],
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(_error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          key: const Key('request-send'),
          onPressed: _busy ? null : _send,
          child: const Text('Send'),
        ),
      ],
    );
  }
}
