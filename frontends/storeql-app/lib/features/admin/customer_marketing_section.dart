import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import 'guardian_consent_section.dart' show customerPrivacyProvider;

// ---------------------------------------------------------------------------
// A customer's marketing channels, taken over the counter:
// withdrawing the Marketing purpose switches off every channel that is on,
// atomically, server-side, with source PURPOSE_WITHDRAWN — this section only
// re-reads what the server now shows, never guesses it. A channel may not be
// switched on while Marketing stands withdrawn (409
// MARKETING_PURPOSE_NOT_GRANTED), worded here the same way everywhere: "Switch
// on Marketing first."
// ---------------------------------------------------------------------------

const marketingPurposeKey = 'MARKETING';

/// Said wherever a channel is disabled for want of the Marketing purpose, and
/// wherever the server refuses one switched on for the same reason (409
/// MARKETING_PURPOSE_NOT_GRANTED) — one form of words, never a raw code.
const switchOnMarketingFirst = 'Switch on Marketing first';

String _customerOf(String customerId, [String suffix = '']) =>
    '/${ApiConstants.customer}/customers/$customerId$suffix';

/// The four channels marketing may use, in the words a person chooses them
/// by — mirrors `lib/features/storefront/privacy_rights.dart`'s
/// `marketingChannels`, kept separate so the admin shell carries no
/// dependency on the storefront's (a different deferred library).
const _channels = <String, String>{
  'EMAIL': 'Email',
  'SMS': 'Text message',
  'PHONE': 'Phone',
  'POST': 'Post',
};

const _marketingNotice =
    'Offers, new lines and events at this shop. '
    'The customer can stop this at any time.';

class StaffMarketingPreference {
  final String channel;
  final bool granted;
  const StaffMarketingPreference({
    required this.channel,
    required this.granted,
  });

  factory StaffMarketingPreference.fromJson(Map<String, dynamic> j) =>
      StaffMarketingPreference(
        channel: j['channel'] as String? ?? '',
        granted: j['granted'] as bool? ?? false,
      );
}

/// A customer's recorded marketing channels, as staff see them. A channel the
/// shop has never recorded simply has no entry — silence is not consent.
final customerMarketingProvider = FutureProvider.autoDispose
    .family<List<StaffMarketingPreference>, String>((ref, customerId) async {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get(_customerOf(customerId, '/marketing'));
      final data = (resp.data['data'] as List?) ?? const [];
      return data
          .map(
            (e) => StaffMarketingPreference.fromJson(e as Map<String, dynamic>),
          )
          .toList();
    });

/// The Marketing purpose and its channels, taken over the counter: nested the
/// same way the shopper's own preference centre nests them — a channel
/// follows the purpose, on only while it is on, always free to turn off.
class CustomerMarketingSection extends ConsumerStatefulWidget {
  const CustomerMarketingSection({super.key, required this.customerId});
  final String customerId;

  @override
  ConsumerState<CustomerMarketingSection> createState() =>
      _CustomerMarketingSectionState();
}

class _CustomerMarketingSectionState
    extends ConsumerState<CustomerMarketingSection> {
  bool _busy = false;

  void _reread() {
    ref.invalidate(customerPrivacyProvider(widget.customerId));
    ref.invalidate(customerMarketingProvider(widget.customerId));
  }

  Future<void> _setPurpose(bool granted) async {
    setState(() => _busy = true);
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .put(
            _customerOf(widget.customerId, '/privacy/consents'),
            data: {
              'choices': [
                {'purpose': marketingPurposeKey, 'granted': granted},
              ],
            },
          );
    } catch (e) {
      _say(friendlyError(e, fallback: 'Could not save that just now.'));
    } finally {
      // Withdrawn, the server has already switched every channel off on the
      // same transaction — re-read rather than guess what it now shows,
      // granted or not.
      _reread();
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _setChannel(String channel, bool granted) async {
    setState(() => _busy = true);
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .put(
            _customerOf(widget.customerId, '/marketing'),
            data: {
              'channels': [
                {'channel': channel, 'granted': granted},
              ],
              if (granted) 'notice': _marketingNotice,
            },
          );
      ref.invalidate(customerMarketingProvider(widget.customerId));
    } catch (e) {
      _say(
        apiErrorCode(e) == 'MARKETING_PURPOSE_NOT_GRANTED'
            ? switchOnMarketingFirst
            : friendlyError(e, fallback: 'Could not save that just now.'),
      );
      // A refusal here means Marketing no longer stands as this section last
      // read it (withdrawn since, perhaps by the customer themselves) — the
      // purpose switch above must catch up too.
      _reread();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _say(String text) {
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final privacyAsync = ref.watch(customerPrivacyProvider(widget.customerId));
    final channelsAsync = ref.watch(
      customerMarketingProvider(widget.customerId),
    );
    if (privacyAsync.isLoading || channelsAsync.isLoading) {
      return const Padding(
        padding: EdgeInsetsDirectional.symmetric(vertical: AppSpacing.sm),
        child: SizedBox(
          height: 20,
          width: 20,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
      );
    }
    if (privacyAsync.hasError || channelsAsync.hasError) {
      return Text(
        friendlyError(
          (privacyAsync.error ?? channelsAsync.error)!,
          fallback: 'Marketing preferences not loaded.',
        ),
        style: theme.textTheme.bodySmall,
      );
    }
    final purposeGranted =
        privacyAsync.value?.granted[marketingPurposeKey] ?? false;
    final on = {
      for (final p in channelsAsync.value ?? const <StaffMarketingPreference>[])
        p.channel: p.granted,
    };

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Marketing', style: theme.textTheme.titleSmall),
        SwitchListTile.adaptive(
          key: const Key('customer-marketing-purpose'),
          contentPadding: EdgeInsets.zero,
          value: purposeGranted,
          onChanged: _busy ? null : _setPurpose,
          title: const Text('Marketing'),
          subtitle: const Text(
            'Offers, news and events, by the channels below',
          ),
        ),
        for (final entry in _channels.entries)
          SwitchListTile.adaptive(
            key: Key('customer-marketing-${entry.key}'),
            contentPadding: const EdgeInsetsDirectional.only(
              start: AppSpacing.xl,
            ),
            // Off can always be chosen; on only while Marketing is on.
            value: on[entry.key] ?? false,
            onChanged: _busy || (!purposeGranted && !(on[entry.key] ?? false))
                ? null
                : (v) => _setChannel(entry.key, v),
            title: Text(entry.value),
            // An explicit colour, not the tile's own disabled dimming (38%
            // opacity, under 4.5:1) — the hint must stay as readable as any
            // other text.
            subtitle: !purposeGranted && !(on[entry.key] ?? false)
                ? Text(
                    switchOnMarketingFirst,
                    key: const Key('customer-marketing-hint'),
                    style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
                  )
                : null,
          ),
      ],
    );
  }
}
