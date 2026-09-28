import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/page_header.dart';
import '../../shared/widgets/status_badge.dart';

// ---------------------------------------------------------------------------
// Plans and packaging (21.8) — the platform's own price list.
//
// What a business can be sold: a plan, its price in each currency it is sold
// in, and what it includes. A plan is written as a draft, priced, given its
// allowances, and only then put on sale; one of the plans on sale is the one a
// business signing up starts on. A plan is never deleted — retiring it takes it
// off sale and leaves the businesses that bought it exactly where they are.
//
// The allowance keys are not typed here: they come from the platform, because a
// key nothing enforces would be a promise nobody keeps. The same goes for what
// is metered (21.10): how many orders and texts a plan includes each period,
// and what each one beyond that costs — or, for a marketing text, that it stops.
// ---------------------------------------------------------------------------

class PlanPrice {
  final String currency;
  final num amount;
  final String effectiveFrom;
  const PlanPrice({required this.currency, required this.amount, required this.effectiveFrom});

  factory PlanPrice.fromJson(Map<String, dynamic> j) => PlanPrice(
        currency: j['currency'] as String? ?? '',
        amount: j['amount'] as num? ?? 0,
        effectiveFrom: j['effectiveFrom'] as String? ?? '',
      );

  /// `£29.00 from 1 Jan 2026` — money in its currency and a date in words, never the NUMERIC as it
  /// arrives (`29.0`) or an ISO date.
  String get says =>
      '${AppFormat.money(amount, currencyCode: currency)} from ${AppFormat.date(effectiveFrom)}';
}

/// What one unit costs, in its currency: `£0.05`. An overage price is set to four places, finer
/// than a currency's minor unit, so a price like 0.035 is shown to the places it has (`£0.035`) —
/// never rounded to a price nobody set. Otherwise it is exactly [AppFormat.money].
String unitPriceSays(num amount, String currency) =>
    AppFormat.money(amount, currencyCode: currency, maxDecimals: 4);

class PlanGrant {
  final String key;
  final String label;
  final int? limitValue;
  final bool? enabled;
  const PlanGrant({required this.key, required this.label, required this.limitValue, required this.enabled});

  factory PlanGrant.fromJson(Map<String, dynamic> j) => PlanGrant(
        key: j['key'] as String? ?? '',
        label: j['label'] as String? ?? (j['key'] as String? ?? ''),
        limitValue: (j['limitValue'] as num?)?.toInt(),
        enabled: j['enabled'] as bool?,
      );

  /// A limit with no number is unlimited; a feature is included or it is not.
  String get says {
    if (enabled != null) return enabled! ? 'included' : 'not included';
    return limitValue == null ? 'unlimited' : '$limitValue';
  }
}

/// What a plan includes of one meter each billing period (21.10).
class PlanMeter {
  final String meter;
  final String label;
  final String? unit;
  final int? included;
  final bool hard;
  const PlanMeter({required this.meter, required this.label, required this.unit, required this.included, required this.hard});

  factory PlanMeter.fromJson(Map<String, dynamic> j) => PlanMeter(
        meter: j['meter'] as String? ?? '',
        label: j['label'] as String? ?? (j['meter'] as String? ?? ''),
        unit: j['unit'] as String?,
        included: (j['included'] as num?)?.toInt(),
        hard: j['hard'] == true,
      );
}

/// What one unit beyond a plan's allowance costs, in a currency, from a date.
class PlanMeterPrice {
  final String meter;
  final String currency;
  final num unitAmount;
  final String effectiveFrom;
  const PlanMeterPrice({required this.meter, required this.currency, required this.unitAmount, required this.effectiveFrom});

  factory PlanMeterPrice.fromJson(Map<String, dynamic> j) => PlanMeterPrice(
        meter: j['meter'] as String? ?? '',
        currency: j['currency'] as String? ?? '',
        unitAmount: j['unitAmount'] as num? ?? 0,
        effectiveFrom: j['effectiveFrom'] as String? ?? '',
      );
}

/// Something the platform counts, and whether use of it may ever be refused.
class MeterKey {
  final String key;
  final String label;
  final String unit;
  final bool refusable;
  final String countedBy;
  const MeterKey({required this.key, required this.label, required this.unit, required this.refusable, required this.countedBy});

  factory MeterKey.fromJson(Map<String, dynamic> j) => MeterKey(
        key: j['key'] as String? ?? '',
        label: j['label'] as String? ?? '',
        unit: j['unit'] as String? ?? '',
        refusable: j['refusable'] == true,
        countedBy: j['countedBy'] as String? ?? '',
      );
}

class Plan {
  final String id;
  final String code;
  final String name;
  final String? description;
  final String status;
  final String billingInterval;
  final int trialDays;
  final bool isDefault;
  final bool isPublic;
  final List<PlanPrice> prices;
  final List<PlanGrant> includes;
  final List<PlanMeter> meters;
  final List<PlanMeterPrice> meterPrices;

  const Plan({
    required this.id,
    required this.code,
    required this.name,
    required this.description,
    required this.status,
    required this.billingInterval,
    required this.trialDays,
    required this.isDefault,
    required this.isPublic,
    required this.prices,
    required this.includes,
    this.meters = const [],
    this.meterPrices = const [],
  });

  factory Plan.fromJson(Map<String, dynamic> j) => Plan(
        id: j['id'] as String,
        code: j['code'] as String? ?? '',
        name: j['name'] as String? ?? '',
        description: j['description'] as String?,
        status: j['status'] as String? ?? '',
        billingInterval: j['billingInterval'] as String? ?? 'MONTH',
        trialDays: j['trialDays'] as int? ?? 0,
        isDefault: j['isDefault'] == true,
        isPublic: j['isPublic'] == true,
        prices: [
          for (final p in j['prices'] as List<dynamic>? ?? const [])
            PlanPrice.fromJson(Map<String, dynamic>.from(p as Map)),
        ],
        includes: [
          for (final g in j['includes'] as List<dynamic>? ?? const [])
            PlanGrant.fromJson(Map<String, dynamic>.from(g as Map)),
        ],
        meters: [
          for (final m in j['meters'] as List<dynamic>? ?? const [])
            PlanMeter.fromJson(Map<String, dynamic>.from(m as Map)),
        ],
        meterPrices: [
          for (final m in j['meterPrices'] as List<dynamic>? ?? const [])
            PlanMeterPrice.fromJson(Map<String, dynamic>.from(m as Map)),
        ],
      );

  /// What a plan says of one meter, as a person reads it: the allowance, then what happens beyond it.
  String meterSays(PlanMeter m) {
    if (m.included == null) return '${m.label}: unlimited';
    if (m.hard) return '${m.label}: ${m.included} a period, then marketing stops';
    final priced = meterPrices.where((x) => x.meter == m.meter).toList();
    if (priced.isEmpty) return '${m.label}: ${m.included} a period, then not charged';
    return '${m.label}: ${m.included} a period, then '
        '${priced.map((x) => unitPriceSays(x.unitAmount, x.currency)).join(' / ')} each';
  }

  bool get sold => status == 'ACTIVE';
  bool get draft => status == 'DRAFT';
}

final meterKeysProvider = FutureProvider.autoDispose<List<MeterKey>>((ref) async {
  final resp = await ref.watch(apiClientProvider).dio.get('$_base/meter-keys');
  return [
    for (final m in (resp.data['data'] as Map)['meters'] as List<dynamic>)
      MeterKey.fromJson(Map<String, dynamic>.from(m as Map)),
  ];
});

class EntitlementKey {
  final String key;
  final String label;
  final bool limit;
  final String enforcedBy;
  const EntitlementKey({required this.key, required this.label, required this.limit, required this.enforcedBy});

  factory EntitlementKey.fromJson(Map<String, dynamic> j) => EntitlementKey(
        key: j['key'] as String? ?? '',
        label: j['label'] as String? ?? '',
        limit: j['limit'] == true,
        enforcedBy: j['enforcedBy'] as String? ?? '',
      );
}

const _base = '/${ApiConstants.tenant}/platform/plans';

String planStatusLabel(String status) => switch (status) {
      'DRAFT' => 'Draft',
      'ACTIVE' => 'On sale',
      'RETIRED' => 'Retired',
      _ => humanizeCode(status),
    };

/// On sale is the good state; a draft is still being written; retired is closed.
StatusTone planStatusTone(String status) => switch (status) {
      'ACTIVE' => StatusTone.success,
      'DRAFT' => StatusTone.info,
      _ => StatusTone.neutral,
    };

String planIntervalLabel(String interval) => interval == 'YEAR' ? 'a year' : 'a month';

final plansProvider = FutureProvider.autoDispose<List<Plan>>((ref) async {
  final resp = await ref.watch(apiClientProvider).dio.get(_base);
  return [
    for (final p in resp.data['data'] as List<dynamic>) Plan.fromJson(Map<String, dynamic>.from(p as Map)),
  ];
});

final entitlementKeysProvider = FutureProvider.autoDispose<List<EntitlementKey>>((ref) async {
  final resp = await ref.watch(apiClientProvider).dio.get('$_base/entitlement-keys');
  return [
    for (final e in (resp.data['data'] as Map)['entitlements'] as List<dynamic>)
      EntitlementKey.fromJson(Map<String, dynamic>.from(e as Map)),
  ];
});

class PlansScreen extends ConsumerWidget {
  const PlansScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final plans = ref.watch(plansProvider);
    final gutter = context.pageGutter;
    void refresh() => ref.invalidate(plansProvider);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        PageHeader(
          title: 'Plans',
          subtitle: 'What a business can be sold. A plan is written, priced and given its allowances '
              'before it goes on sale; one plan on sale is the one a business signing up starts on.',
          actions: [
            OutlinedButton.icon(
              key: const Key('plan-write'),
              icon: const Icon(Icons.add),
              label: const Text('Write a plan'),
              onPressed: () async {
                final written = await showDialog<bool>(context: context, builder: (_) => const WritePlanDialog());
                if (written == true) refresh();
              },
            ),
            IconButton(icon: const Icon(Icons.refresh), tooltip: 'Refresh', onPressed: refresh),
          ],
        ),
        Expanded(
          child: plans.when(
            loading: () => const LoadingView(label: 'Loading plans…'),
            error: (e, _) => ErrorView(message: friendlyError(e, fallback: 'Could not load plans.'), onRetry: refresh),
            data: (list) => list.isEmpty
                ? const EmptyState(
                    icon: Icons.sell_outlined,
                    title: 'No plans yet',
                    message: 'Write one, price it, then put it on sale.',
                  )
                : ListView.separated(
                    padding: EdgeInsetsDirectional.fromSTEB(gutter, 0, gutter, gutter),
                    itemCount: list.length,
                    separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.md),
                    itemBuilder: (context, i) => _PlanCard(plan: list[i], onChanged: refresh),
                  ),
          ),
        ),
      ],
    );
  }
}

class _PlanCard extends ConsumerStatefulWidget {
  final Plan plan;
  final VoidCallback onChanged;
  const _PlanCard({required this.plan, required this.onChanged});

  @override
  ConsumerState<_PlanCard> createState() => _PlanCardState();
}

class _PlanCardState extends ConsumerState<_PlanCard> {
  String? _error;
  bool _busy = false;

  Dio get _dio => ref.read(apiClientProvider).dio;

  Future<void> _act(String path) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await _dio.post('$_base/${widget.plan.id}/$path', data: const <String, dynamic>{});
      widget.onChanged();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = switch (apiErrorCode(e)) {
          'PLAN_HAS_NO_PRICE' => 'Give it a price before selling it.',
          'PLAN_ALREADY_SOLD' => 'It is already on sale.',
          'PLAN_NOT_SOLD' => 'Only a plan on sale can be taken off sale, or be the one new businesses start on.',
          _ => friendlyError(e),
        };
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final p = widget.plan;
    final cs = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final heading = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('${p.name} · ${p.code}', style: text.titleMedium),
        const SizedBox(height: AppSpacing.xs),
        Text(
          [
            'billed ${planIntervalLabel(p.billingInterval)}',
            if (p.trialDays > 0) '${p.trialDays} days free',
            if (!p.isPublic) 'not on the public list',
          ].join(' · '),
          style: text.bodySmall?.copyWith(color: cs.outline),
        ),
      ],
    );
    final badges = Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.xs,
      children: [
        if (p.isDefault)
          StatusBadge(
            'New businesses start here',
            key: Key('plan-default-${p.code}'),
            tone: StatusTone.accent,
          ),
        StatusBadge(
          planStatusLabel(p.status),
          key: Key('plan-status-${p.code}'),
          tone: planStatusTone(p.status),
        ),
      ],
    );
    return Card(
      key: Key('plan-${p.code}'),
      child: Padding(
        padding: AppSpacing.cardPadding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Below 600px the badges go under the name: beside it, the default plan's two leave the
            // name a sliver of a phone card and it breaks letter by letter.
            LayoutBuilder(
              builder: (context, constraints) => constraints.maxWidth < AppBreakpoints.medium
                  ? Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [heading, const SizedBox(height: AppSpacing.sm), badges],
                    )
                  : Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [Expanded(child: heading), const SizedBox(width: AppSpacing.md), badges],
                    ),
            ),
            if (p.description != null) ...[
              const SizedBox(height: AppSpacing.sm),
              Text(p.description!, style: text.bodyMedium),
            ],
            const Divider(height: AppSpacing.xl),
            Text('Price', style: text.titleSmall),
            const SizedBox(height: AppSpacing.xs),
            if (p.prices.isEmpty)
              Text('No price yet — it cannot go on sale without one.', style: TextStyle(color: cs.error))
            else
              Text(p.prices.map((x) => x.says).join('  ·  '), style: text.bodyMedium),
            const SizedBox(height: AppSpacing.md),
            Text('What it includes', style: text.titleSmall),
            const SizedBox(height: AppSpacing.xs),
            if (p.includes.isEmpty)
              Text('Nothing named — a business on it is unrestricted.', style: text.bodyMedium)
            else
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.xs,
                children: [
                  for (final g in p.includes)
                    Chip(key: Key('plan-grant-${p.code}-${g.key}'), label: Text('${g.label}: ${g.says}')),
                ],
              ),
            const SizedBox(height: AppSpacing.md),
            Text('Metered use', style: text.titleSmall),
            const SizedBox(height: AppSpacing.xs),
            if (p.meters.isEmpty)
              Text('Nothing metered — orders and texts are uncounted against this plan.', style: text.bodyMedium)
            else
              // Rows of text, not chips: a chip keeps its label on one line and fades it, and a
              // metered line is longer than a phone card, so the price at its end was cut off.
              for (final m in p.meters)
                Padding(
                  padding: const EdgeInsetsDirectional.only(bottom: AppSpacing.xs),
                  child: Text(
                    p.meterSays(m),
                    key: Key('plan-meter-${p.code}-${m.meter}'),
                    style: text.bodyMedium,
                  ),
                ),
            if (_error != null) ...[
              const SizedBox(height: AppSpacing.sm),
              Text(_error!, key: Key('plan-error-${p.code}'), style: TextStyle(color: cs.error)),
            ],
            const SizedBox(height: AppSpacing.sm),
            Wrap(
              spacing: AppSpacing.sm,
              children: [
                TextButton(
                  key: Key('plan-price-${p.code}'),
                  onPressed: _busy
                      ? null
                      : () async {
                          final set = await showDialog<bool>(context: context, builder: (_) => SetPriceDialog(planId: p.id));
                          if (set == true) widget.onChanged();
                        },
                  child: const Text('Set a price'),
                ),
                TextButton(
                  key: Key('plan-includes-${p.code}'),
                  onPressed: _busy
                      ? null
                      : () async {
                          final set = await showDialog<bool>(context: context, builder: (_) => SetIncludesDialog(plan: p));
                          if (set == true) widget.onChanged();
                        },
                  child: const Text('What it includes'),
                ),
                TextButton(
                  key: Key('plan-meters-${p.code}'),
                  onPressed: _busy
                      ? null
                      : () async {
                          final set = await showDialog<bool>(context: context, builder: (_) => SetMetersDialog(plan: p));
                          if (set == true) widget.onChanged();
                        },
                  child: const Text('Metered use'),
                ),
                TextButton(
                  key: Key('plan-meter-price-${p.code}'),
                  onPressed: _busy
                      ? null
                      : () async {
                          final set = await showDialog<bool>(context: context, builder: (_) => SetMeterPriceDialog(planId: p.id));
                          if (set == true) widget.onChanged();
                        },
                  child: const Text('Price beyond the allowance'),
                ),
                if (!p.sold)
                  FilledButton(
                    key: Key('plan-sell-${p.code}'),
                    onPressed: _busy ? null : () => _act('activate'),
                    child: const Text('Put on sale'),
                  ),
                if (p.sold && !p.isDefault)
                  TextButton(
                    key: Key('plan-make-default-${p.code}'),
                    onPressed: _busy ? null : () => _act('default'),
                    child: const Text('New businesses start here'),
                  ),
                if (p.sold)
                  TextButton(
                    key: Key('plan-retire-${p.code}'),
                    onPressed: _busy ? null : () => _act('retire'),
                    child: const Text('Take off sale'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// Writing a plan. It is a draft until somebody puts it on sale.
class WritePlanDialog extends ConsumerStatefulWidget {
  const WritePlanDialog({super.key});

  @override
  ConsumerState<WritePlanDialog> createState() => _WritePlanDialogState();
}

class _WritePlanDialogState extends ConsumerState<WritePlanDialog> {
  final _code = TextEditingController();
  final _name = TextEditingController();
  final _description = TextEditingController();
  final _trial = TextEditingController(text: '0');
  String _interval = 'MONTH';
  bool _public = true;
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _code.dispose();
    _name.dispose();
    _description.dispose();
    _trial.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (_code.text.trim().isEmpty || _name.text.trim().isEmpty) {
      setState(() => _error = 'A plan needs a code and a name.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(_base, data: {
        'code': _code.text.trim(),
        'name': _name.text.trim(),
        'description': ?(_description.text.trim().isEmpty ? null : _description.text.trim()),
        'billingInterval': _interval,
        'trialDays': int.tryParse(_trial.text.trim()) ?? 0,
        'isPublic': _public,
      });
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = switch (apiErrorCode(e)) {
          'PLAN_CODE_TAKEN' => 'A plan already goes by that code.',
          'PLAN_CODE_INVALID' => 'A code is 2 to 40 of A–Z, 0–9, dash and underscore.',
          'PLAN_INTERVAL_UNKNOWN' => 'A plan is billed by the month or by the year.',
          _ => friendlyError(e),
        };
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Write a plan'),
      content: SizedBox(
        width: 460,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('It is a draft until you put it on sale, so nothing here is offered to anybody yet.'),
              const SizedBox(height: AppSpacing.md),
              TextField(key: const Key('plan-code'), controller: _code, decoration: const InputDecoration(labelText: 'Code *', hintText: 'STARTER')),
              const SizedBox(height: AppSpacing.sm),
              TextField(key: const Key('plan-name'), controller: _name, decoration: const InputDecoration(labelText: 'Name *')),
              const SizedBox(height: AppSpacing.sm),
              TextField(controller: _description, maxLines: 2, decoration: const InputDecoration(labelText: 'What it is for')),
              const SizedBox(height: AppSpacing.sm),
              DropdownButtonFormField<String>(
                key: const Key('plan-interval'),
                isExpanded: true,
                initialValue: _interval,
                decoration: const InputDecoration(labelText: 'Billed'),
                items: const [
                  DropdownMenuItem(value: 'MONTH', child: Text('Every month')),
                  DropdownMenuItem(value: 'YEAR', child: Text('Every year')),
                ],
                onChanged: (v) => setState(() => _interval = v ?? 'MONTH'),
              ),
              const SizedBox(height: AppSpacing.sm),
              TextField(
                key: const Key('plan-trial'),
                controller: _trial,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Days free before the first bill'),
              ),
              SwitchListTile.adaptive(
                key: const Key('plan-public'),
                contentPadding: EdgeInsets.zero,
                title: const Text('Show it on the price list'),
                value: _public,
                onChanged: (v) => setState(() => _public = v),
              ),
              if (_error != null) Text(_error!, key: const Key('plan-write-error'), style: TextStyle(color: cs.error)),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(false), child: const Text('Cancel')),
        FilledButton(key: const Key('plan-write-save'), onPressed: _busy ? null : _save, child: Text(_busy ? 'Writing…' : 'Write')),
      ],
    );
  }
}

/// A plan's price in one currency, from a date. An earlier price is never edited.
class SetPriceDialog extends ConsumerStatefulWidget {
  final String planId;
  const SetPriceDialog({super.key, required this.planId});

  @override
  ConsumerState<SetPriceDialog> createState() => _SetPriceDialogState();
}

class _SetPriceDialogState extends ConsumerState<SetPriceDialog> {
  // No currency preselected (SJ-D67): a plan can be priced in any tenant's
  // home currency, and the operator names the one this price is in.
  final _currency = TextEditingController();
  final _amount = TextEditingController();
  DateTime? _from;
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _currency.dispose();
    _amount.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final currency = _currency.text.trim().toUpperCase();
    if (currency.isEmpty) {
      setState(() => _error = 'Choose a currency — a three-letter code, such as USD or INR.');
      return;
    }
    final amount = num.tryParse(_amount.text.trim());
    if (amount == null || amount < 0) {
      setState(() => _error = 'A price is a number, like 49.00.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post('$_base/${widget.planId}/prices', data: {
        'currency': currency,
        'amount': amount,
        'effectiveFrom': ?_from?.toIso8601String().substring(0, 10),
      });
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = apiErrorCode(e) == 'CURRENCY_INVALID'
            ? 'A currency is a three-letter code, such as USD or INR.'
            : friendlyError(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Set a price'),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('From a date. An earlier price is kept, so an invoice raised under it stays explicable.'),
            const SizedBox(height: AppSpacing.md),
            Row(
              children: [
                SizedBox(
                  width: 110,
                  child: TextField(
                    key: const Key('price-currency'),
                    controller: _currency,
                    textCapitalization: TextCapitalization.characters,
                    decoration: const InputDecoration(labelText: 'Currency', hintText: 'e.g. USD'),
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: TextField(
                    key: const Key('price-amount'),
                    controller: _amount,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    decoration: const InputDecoration(labelText: 'Per billing period, before tax'),
                  ),
                ),
              ],
            ),
            ListTile(
              key: const Key('price-from'),
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.event_outlined),
              title: Text(_from == null ? 'From today' : 'From ${AppFormat.date(_from!.toIso8601String())}'),
              onTap: () async {
                final now = DateTime.now();
                final picked = await showDatePicker(context: context, firstDate: now.subtract(const Duration(days: 365)), lastDate: now.add(const Duration(days: 730)), initialDate: now);
                if (picked != null) setState(() => _from = picked);
              },
            ),
            if (_error != null) Text(_error!, key: const Key('price-error'), style: TextStyle(color: cs.error)),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(false), child: const Text('Cancel')),
        FilledButton(key: const Key('price-save'), onPressed: _busy ? null : _save, child: Text(_busy ? 'Saving…' : 'Set')),
      ],
    );
  }
}

/// What a plan includes. The keys come from the platform: only what something enforces is offered.
class SetIncludesDialog extends ConsumerStatefulWidget {
  final Plan plan;
  const SetIncludesDialog({super.key, required this.plan});

  @override
  ConsumerState<SetIncludesDialog> createState() => _SetIncludesDialogState();
}

class _SetIncludesDialogState extends ConsumerState<SetIncludesDialog> {
  final Map<String, TextEditingController> _limits = {};
  final Map<String, bool> _features = {};
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    for (final c in _limits.values) {
      c.dispose();
    }
    super.dispose();
  }

  /// What the plan already says about a key, or nothing.
  PlanGrant? _granted(String key) {
    for (final g in widget.plan.includes) {
      if (g.key == key) return g;
    }
    return null;
  }

  void _seed(List<EntitlementKey> keys) {
    for (final k in keys) {
      if (k.limit) {
        _limits.putIfAbsent(k.key, () {
          final has = _granted(k.key);
          return TextEditingController(text: has?.limitValue?.toString() ?? '');
        });
      } else {
        _features.putIfAbsent(
          k.key,
          () => _granted(k.key)?.enabled ?? false,
        );
      }
    }
  }

  Future<void> _save(List<EntitlementKey> keys) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    final grants = <Map<String, dynamic>>[];
    for (final k in keys) {
      if (k.limit) {
        final raw = _limits[k.key]?.text.trim() ?? '';
        if (raw == '-') {
          grants.add({'key': k.key}); // named, with no number: unlimited
        } else if (raw.isNotEmpty) {
          final n = int.tryParse(raw);
          if (n == null || n < 0) {
            setState(() {
              _busy = false;
              _error = '${k.label} is a whole number, or “-” for unlimited.';
            });
            return;
          }
          grants.add({'key': k.key, 'limitValue': n});
        }
      } else if (_features[k.key] == true) {
        grants.add({'key': k.key, 'enabled': true});
      }
    }
    try {
      await ref.read(apiClientProvider).dio.put('$_base/${widget.plan.id}/includes', data: {'grants': grants});
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = friendlyError(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final keys = ref.watch(entitlementKeysProvider);
    return AlertDialog(
      title: Text('What ${widget.plan.code} includes'),
      content: SizedBox(
        width: 480,
        child: keys.when(
          loading: () => const SizedBox(height: 120, child: Center(child: CircularProgressIndicator())),
          error: (e, _) => Text(friendlyError(e, fallback: 'Could not load what can be included.')),
          data: (list) {
            _seed(list);
            return SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Only what the platform actually enforces can be promised here. Leave a limit '
                    'empty to say nothing about it; type “-” for unlimited.',
                  ),
                  const SizedBox(height: AppSpacing.md),
                  for (final k in list)
                    if (k.limit)
                      Padding(
                        padding: const EdgeInsetsDirectional.only(bottom: AppSpacing.sm),
                        child: TextField(
                          key: Key('include-${k.key}'),
                          controller: _limits[k.key],
                          decoration: InputDecoration(labelText: k.label, helperText: 'enforced by ${k.enforcedBy}'),
                        ),
                      )
                    else
                      SwitchListTile.adaptive(
                        key: Key('include-${k.key}'),
                        contentPadding: EdgeInsets.zero,
                        title: Text(k.label),
                        subtitle: Text('enforced by ${k.enforcedBy}'),
                        value: _features[k.key] ?? false,
                        onChanged: (v) => setState(() => _features[k.key] = v),
                      ),
                  if (_error != null) Text(_error!, key: const Key('includes-error'), style: TextStyle(color: cs.error)),
                ],
              ),
            );
          },
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(false), child: const Text('Cancel')),
        FilledButton(
          key: const Key('includes-save'),
          onPressed: _busy || !keys.hasValue ? null : () => _save(keys.value!),
          child: Text(_busy ? 'Saving…' : 'Set'),
        ),
      ],
    );
  }
}

/// What a plan includes of each meter each billing period (21.10). Only a meter that may be
/// refused can be made a hard ceiling: an order never is.
class SetMetersDialog extends ConsumerStatefulWidget {
  final Plan plan;
  const SetMetersDialog({super.key, required this.plan});

  @override
  ConsumerState<SetMetersDialog> createState() => _SetMetersDialogState();
}

class _SetMetersDialogState extends ConsumerState<SetMetersDialog> {
  final Map<String, TextEditingController> _included = {};
  final Map<String, bool> _hard = {};
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    for (final c in _included.values) {
      c.dispose();
    }
    super.dispose();
  }

  PlanMeter? _named(String key) {
    for (final m in widget.plan.meters) {
      if (m.meter == key) return m;
    }
    return null;
  }

  void _seed(List<MeterKey> keys) {
    for (final k in keys) {
      _included.putIfAbsent(k.key, () {
        final has = _named(k.key);
        if (has == null) return TextEditingController();
        return TextEditingController(text: has.included?.toString() ?? '-');
      });
      _hard.putIfAbsent(k.key, () => _named(k.key)?.hard ?? false);
    }
  }

  Future<void> _save(List<MeterKey> keys) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    final meters = <Map<String, dynamic>>[];
    for (final k in keys) {
      final raw = _included[k.key]?.text.trim() ?? '';
      if (raw.isEmpty) continue;
      if (raw == '-') {
        meters.add({'meter': k.key}); // named, with no number: unlimited
        continue;
      }
      final n = int.tryParse(raw);
      if (n == null || n < 0) {
        setState(() {
          _busy = false;
          _error = '${k.label} is a whole number, or “-” for unlimited.';
        });
        return;
      }
      meters.add({'meter': k.key, 'included': n, 'hard': k.refusable && (_hard[k.key] ?? false)});
    }
    try {
      await ref.read(apiClientProvider).dio.put('$_base/${widget.plan.id}/meters', data: {'meters': meters});
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = switch (apiErrorCode(e)) {
          'PLAN_METER_NOT_REFUSABLE' => 'Orders are never refused. Price what is used beyond the allowance instead.',
          'PLAN_METER_HARD_UNLIMITED' => 'A hard ceiling needs a number to stop at.',
          _ => friendlyError(e),
        };
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final keys = ref.watch(meterKeysProvider);
    return AlertDialog(
      title: Text('What ${widget.plan.code} includes each period'),
      content: SizedBox(
        width: 480,
        child: keys.when(
          loading: () => const SizedBox(height: 120, child: Center(child: CircularProgressIndicator())),
          error: (e, _) => Text(friendlyError(e, fallback: 'Could not load what the platform counts.')),
          data: (list) {
            _seed(list);
            return SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'How many each billing period. Beyond it, use is charged at the plan’s overage price — '
                    'or, for what may be refused, stopped. Leave empty to say nothing; “-” for unlimited.',
                  ),
                  const SizedBox(height: AppSpacing.md),
                  for (final k in list) ...[
                    TextField(
                      key: Key('meter-${k.key}'),
                      controller: _included[k.key],
                      keyboardType: TextInputType.number,
                      decoration: InputDecoration(labelText: '${k.label} (${k.unit}s)', helperText: 'counted by ${k.countedBy}'),
                    ),
                    if (k.refusable)
                      SwitchListTile.adaptive(
                        key: Key('meter-hard-${k.key}'),
                        contentPadding: EdgeInsets.zero,
                        title: const Text('Stop marketing beyond it, rather than charge'),
                        subtitle: const Text('What a customer must be told — an order ready, a recall — always goes.'),
                        value: _hard[k.key] ?? false,
                        onChanged: (v) => setState(() => _hard[k.key] = v),
                      ),
                    const SizedBox(height: AppSpacing.sm),
                  ],
                  if (_error != null) Text(_error!, key: const Key('meters-error'), style: TextStyle(color: cs.error)),
                ],
              ),
            );
          },
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(false), child: const Text('Cancel')),
        FilledButton(
          key: const Key('meters-save'),
          onPressed: _busy || !keys.hasValue ? null : () => _save(keys.value!),
          child: Text(_busy ? 'Saving…' : 'Set'),
        ),
      ],
    );
  }
}

/// What one unit beyond a plan's allowance costs, from a date. An earlier price is kept, so a period
/// is charged at the price in force the day it began.
class SetMeterPriceDialog extends ConsumerStatefulWidget {
  final String planId;
  const SetMeterPriceDialog({super.key, required this.planId});

  @override
  ConsumerState<SetMeterPriceDialog> createState() => _SetMeterPriceDialogState();
}

class _SetMeterPriceDialogState extends ConsumerState<SetMeterPriceDialog> {
  // No currency preselected (SJ-D67): a plan can be priced in any tenant's
  // home currency, and the operator names the one this price is in.
  final _currency = TextEditingController();
  final _amount = TextEditingController();
  String? _meter;
  DateTime? _from;
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _currency.dispose();
    _amount.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final currency = _currency.text.trim().toUpperCase();
    final amount = num.tryParse(_amount.text.trim());
    if (_meter == null) {
      setState(() => _error = 'Choose what is being priced.');
      return;
    }
    if (currency.isEmpty) {
      setState(() => _error = 'Choose a currency — a three-letter code, such as USD or INR.');
      return;
    }
    if (amount == null || amount < 0) {
      setState(() => _error = 'A price is a number, like 0.05 — up to four places.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post('$_base/${widget.planId}/meter-prices', data: {
        'meter': _meter,
        'currency': currency,
        'unitAmount': amount,
        'effectiveFrom': ?_from?.toIso8601String().substring(0, 10),
      });
      if (mounted) Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _busy = false;
        _error = apiErrorCode(e) == 'CURRENCY_INVALID'
            ? 'A currency is a three-letter code, such as USD or INR.'
            : friendlyError(e);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final keys = ref.watch(meterKeysProvider);
    return AlertDialog(
      title: const Text('Price beyond the allowance'),
      content: SizedBox(
        width: 420,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Each one beyond what the plan includes, before tax. A period is charged at the price in force the day it began.'),
            const SizedBox(height: AppSpacing.md),
            DropdownButtonFormField<String>(
              key: const Key('meter-price-meter'),
              initialValue: _meter,
              decoration: const InputDecoration(labelText: 'What'),
              items: [
                for (final k in keys.value ?? const <MeterKey>[])
                  DropdownMenuItem(value: k.key, child: Text('${k.label}, per ${k.unit}')),
              ],
              onChanged: (v) => setState(() => _meter = v),
            ),
            const SizedBox(height: AppSpacing.sm),
            Row(
              children: [
                SizedBox(
                  width: 110,
                  child: TextField(
                    key: const Key('meter-price-currency'),
                    controller: _currency,
                    textCapitalization: TextCapitalization.characters,
                    decoration: const InputDecoration(labelText: 'Currency', hintText: 'e.g. USD'),
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: TextField(
                    key: const Key('meter-price-amount'),
                    controller: _amount,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    decoration: const InputDecoration(labelText: 'Each, up to four places'),
                  ),
                ),
              ],
            ),
            ListTile(
              key: const Key('meter-price-from'),
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.event_outlined),
              title: Text(_from == null ? 'From today' : 'From ${AppFormat.date(_from!.toIso8601String())}'),
              onTap: () async {
                final now = DateTime.now();
                final picked = await showDatePicker(context: context, firstDate: now.subtract(const Duration(days: 365)), lastDate: now.add(const Duration(days: 730)), initialDate: now);
                if (picked != null) setState(() => _from = picked);
              },
            ),
            if (_error != null) Text(_error!, key: const Key('meter-price-error'), style: TextStyle(color: cs.error)),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: _busy ? null : () => Navigator.of(context).pop(false), child: const Text('Cancel')),
        FilledButton(key: const Key('meter-price-save'), onPressed: _busy ? null : _save, child: Text(_busy ? 'Saving…' : 'Set')),
      ],
    );
  }
}
