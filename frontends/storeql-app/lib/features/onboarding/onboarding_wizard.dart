import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/reference_fields.dart';
import 'onboarding_notifier.dart';
import 'public_plans.dart';

class OnboardingWizard extends ConsumerStatefulWidget {
  const OnboardingWizard({super.key});

  @override
  ConsumerState<OnboardingWizard> createState() => _OnboardingWizardState();
}

class _OnboardingWizardState extends ConsumerState<OnboardingWizard> {
  final _pageCtrl = PageController();

  // Step 1 fields
  final _bizNameCtrl = TextEditingController();
  final _legalNameCtrl = TextEditingController();
  // Nothing preselected (SJ-D53): the business chooses its own country and
  // currency; choosing a country suggests the currency it trades in.
  String? _country;
  String? _currency;
  // The plan chosen from the price list (21.13); until the business chooses,
  // the platform's default is shown and sent.
  String? _planId;
  final _step1Key = GlobalKey<FormState>();


  // Step 2 fields
  final _storeNameCtrl = TextEditingController();
  final _storeCodeCtrl = TextEditingController();
  final _line1Ctrl = TextEditingController();
  final _cityCtrl = TextEditingController();
  final _pincodeCtrl = TextEditingController();
  String? _storeCountry;
  String? _timezone;
  String _storeType = 'STORE';
  final _step2Key = GlobalKey<FormState>();

  @override
  void dispose() {
    _pageCtrl.dispose();
    _bizNameCtrl.dispose();
    _legalNameCtrl.dispose();
    _storeNameCtrl.dispose();
    _storeCodeCtrl.dispose();
    _line1Ctrl.dispose();
    _cityCtrl.dispose();
    _pincodeCtrl.dispose();
    super.dispose();
  }

  void _animateTo(int page) =>
      _pageCtrl.animateToPage(page, duration: const Duration(milliseconds: 300), curve: Curves.easeInOut);

  @override
  Widget build(BuildContext context) {
    final ob = ref.watch(onboardingNotifierProvider);
    final plans = ref.watch(publicPlansProvider);

    // When step 2 is done, router redirect will pick it up via auth state change
    ref.listen<OnboardingState>(onboardingNotifierProvider, (_, next) {
      if (next.step == 1 && !next.loading) _animateTo(1);
      if (next.step == 2 && !next.loading) context.go('/admin/dashboard');
    });

    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      // A login routed here by mistake — a member of staff who signed up
      // instead of being invited — leaves by signing out, not only by
      // creating a business.
      appBar: AppBar(
        automaticallyImplyLeading: false,
        actions: [
          TextButton.icon(
            key: const Key('onboarding-sign-out'),
            onPressed: () => ref.read(authNotifierProvider.notifier).logout(),
            icon: const Icon(Icons.logout),
            label: const Text('Sign out'),
          ),
          const SizedBox(width: AppSpacing.sm),
        ],
      ),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 560),
            child: Column(
              children: [
                Icon(Icons.storefront_rounded, size: 40, color: cs.primary),
                const SizedBox(height: AppSpacing.xs),
                Text('Set up your business',
                    style: Theme.of(context)
                        .textTheme
                        .headlineSmall
                        ?.copyWith(fontWeight: FontWeight.bold)),
                const SizedBox(height: AppSpacing.xs),
                // step indicators
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    _StepDot(active: ob.step == 0, done: ob.step > 0, label: '1'),
                    const _StepLine(),
                    _StepDot(active: ob.step == 1, done: ob.step > 1, label: '2'),
                  ],
                ),
                const SizedBox(height: AppSpacing.lg),
                if (ob.error != null)
                  Padding(
                    padding: EdgeInsets.symmetric(horizontal: context.pageGutter),
                    child: Container(
                      padding: const EdgeInsets.all(AppSpacing.md),
                      decoration: BoxDecoration(
                        color: cs.errorContainer,
                        borderRadius: AppRadius.chip,
                      ),
                      child: Text(ob.error!, style: TextStyle(color: cs.onErrorContainer)),
                    ),
                  ),
                const SizedBox(height: AppSpacing.sm),
                Expanded(
                  child: PageView(
                    controller: _pageCtrl,
                    physics: const NeverScrollableScrollPhysics(),
                    children: [
                      _Step1TenantForm(
                        formKey: _step1Key,
                        bizNameCtrl: _bizNameCtrl,
                        legalNameCtrl: _legalNameCtrl,
                        country: _country,
                        currency: _currency,
                        onCountryChanged: (v) => setState(() {
                          _country = v;
                          _currency = currencyOfCountry(v) ?? _currency;
                        }),
                        onCurrencyChanged: (v) => setState(() => _currency = v!),
                        plans: plans,
                        planId: _planId,
                        onPlanChanged: (v) => setState(() => _planId = v),
                        loading: ob.loading,
                        onNext: () {
                          if (!_step1Key.currentState!.validate()) return;
                          ref.read(onboardingNotifierProvider.notifier).createTenant(
                                businessName: _bizNameCtrl.text.trim(),
                                legalName: _legalNameCtrl.text.trim(),
                                country: _country!,
                                currency: _currency!,
                                planId: _planId ??
                                    PublicPlan.defaultId(plans.value ?? const []),
                              );
                        },
                      ),
                      _Step2StoreForm(
                        formKey: _step2Key,
                        nameCtrl: _storeNameCtrl,
                        codeCtrl: _storeCodeCtrl,
                        line1Ctrl: _line1Ctrl,
                        cityCtrl: _cityCtrl,
                        pincodeCtrl: _pincodeCtrl,
                        // The first store is in the business's own country unless moved.
                        country: _storeCountry ?? _country,
                        timezone: _timezone,
                        storeType: _storeType,
                        onCountryChanged: (v) => setState(() => _storeCountry = v!),
                        onTimezoneChanged: (v) => setState(() => _timezone = v!),
                        onTypeChanged: (v) => setState(() => _storeType = v!),
                        loading: ob.loading,
                        onSubmit: () {
                          if (!_step2Key.currentState!.validate()) return;
                          ref.read(onboardingNotifierProvider.notifier).createStore(
                                name: _storeNameCtrl.text.trim(),
                                code: _storeCodeCtrl.text.trim().toUpperCase(),
                                type: _storeType,
                                line1: _line1Ctrl.text.trim(),
                                city: _cityCtrl.text.trim(),
                                country: _storeCountry ?? _country,
                                pincode: _pincodeCtrl.text.trim(),
                                timezone: _timezone,
                              );
                        },
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ── Step 1: Tenant info ────────────────────────────────────────────────────────

class _Step1TenantForm extends StatelessWidget {
  final GlobalKey<FormState> formKey;
  final TextEditingController bizNameCtrl;
  final TextEditingController legalNameCtrl;
  final String? country;
  final String? currency;
  final ValueChanged<String?> onCountryChanged;
  final ValueChanged<String?> onCurrencyChanged;
  final AsyncValue<List<PublicPlan>> plans;
  final String? planId;
  final ValueChanged<String?> onPlanChanged;
  final bool loading;
  final VoidCallback onNext;

  const _Step1TenantForm({
    required this.formKey,
    required this.bizNameCtrl,
    required this.legalNameCtrl,
    required this.country,
    required this.currency,
    required this.onCountryChanged,
    required this.onCurrencyChanged,
    required this.plans,
    required this.planId,
    required this.onPlanChanged,
    required this.loading,
    required this.onNext,
  });

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: context.pagePadding,
      child: Form(
        key: formKey,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Business details',
                style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold)),
            const SizedBox(height: AppSpacing.xs),
            Text('Tell us about your business.',
                style: Theme.of(context).textTheme.bodyMedium),
            const SizedBox(height: AppSpacing.xl),
            TextFormField(
              controller: bizNameCtrl,
              textInputAction: TextInputAction.next,
              // No field on the wizard has a leading icon: the shared country,
              // currency and time-zone dropdowns have none, and a label must
              // start where the one above it does.
              decoration: const InputDecoration(
                labelText: 'Business name *',
                hintText: 'e.g. Green Valley Supermarket',
              ),
              validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
            ),
            const SizedBox(height: AppSpacing.lg),
            TextFormField(
              controller: legalNameCtrl,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: 'Legal / registered name (optional)',
              ),
            ),
            const SizedBox(height: AppSpacing.lg),
            CountryField(
              label: 'Country *',
              value: country,
              onChanged: onCountryChanged,
            ),
            const SizedBox(height: AppSpacing.lg),
            CurrencyField(
              label: 'Currency *',
              value: currency,
              onChanged: onCurrencyChanged,
            ),
            const SizedBox(height: AppSpacing.lg),
            // The price list (21.13). When it cannot be read the business still
            // signs up: the platform starts it on the default plan.
            plans.when(
              loading: () => const Text('Loading plans…'),
              error: (_, _) => Text(
                'Plans could not be loaded; you will start on the standard plan '
                'and can change it later from Billing.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              data: (list) => list.isEmpty
                  ? const SizedBox.shrink()
                  : _PlanField(
                      plans: list,
                      value: planId ?? PublicPlan.defaultId(list)!,
                      currency: currency,
                      onChanged: onPlanChanged,
                    ),
            ),
            const SizedBox(height: AppSpacing.xxl),
            // The arrow is an icon, after the word: Icons.arrow_forward turns
            // round in Urdu and Arabic, where a "→" in the label would not.
            FilledButton.icon(
              onPressed: loading ? null : onNext,
              iconAlignment: IconAlignment.end,
              icon: loading ? null : const Icon(Icons.arrow_forward),
              label: loading
                  ? SizedBox(
                      height: 20, width: 20,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                  : const Text('Continue'),
            ),
          ],
        ),
      ),
    );
  }
}

/// The plans on sale, one line each — the price in the business's own currency
/// where the plan is priced in it, and the trial — with what the chosen one is.
class _PlanField extends StatelessWidget {
  final List<PublicPlan> plans;
  final String value;
  final String? currency;
  final ValueChanged<String?> onChanged;

  const _PlanField({
    required this.plans,
    required this.value,
    required this.currency,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final chosen = plans.firstWhere((p) => p.id == value, orElse: () => plans.first);
    final about = [
      if (chosen.description != null && chosen.description!.isNotEmpty) chosen.description!,
      if (chosen.includes.isNotEmpty) 'Includes ${chosen.includes.join(', ')}.',
    ].join(' ');
    return DropdownButtonFormField<String>(
      key: ValueKey('plan-$value-$currency'),
      initialValue: value,
      isExpanded: true,
      decoration: InputDecoration(
        labelText: 'Plan *',
        helperText: about.isEmpty ? null : about,
        helperMaxLines: 3,
      ),
      items: [
        for (final p in plans)
          DropdownMenuItem(value: p.id, child: Text(p.label(currency), overflow: TextOverflow.ellipsis)),
      ],
      onChanged: onChanged,
    );
  }
}

// ── Step 2: First store ────────────────────────────────────────────────────────

class _Step2StoreForm extends StatelessWidget {
  final GlobalKey<FormState> formKey;
  final TextEditingController nameCtrl;
  final TextEditingController codeCtrl;
  final TextEditingController line1Ctrl;
  final TextEditingController cityCtrl;
  final TextEditingController pincodeCtrl;
  final String? country;
  final String? timezone;
  final String storeType;
  final ValueChanged<String?> onCountryChanged;
  final ValueChanged<String?> onTimezoneChanged;
  final ValueChanged<String?> onTypeChanged;
  final bool loading;
  final VoidCallback onSubmit;

  const _Step2StoreForm({
    required this.formKey,
    required this.nameCtrl,
    required this.codeCtrl,
    required this.line1Ctrl,
    required this.cityCtrl,
    required this.pincodeCtrl,
    required this.country,
    required this.timezone,
    required this.storeType,
    required this.onCountryChanged,
    required this.onTimezoneChanged,
    required this.onTypeChanged,
    required this.loading,
    required this.onSubmit,
  });

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: context.pagePadding,
      child: Form(
        key: formKey,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text('Your first store',
                style: Theme.of(context).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.bold)),
            const SizedBox(height: AppSpacing.xs),
            Text('You can add more stores later from the admin panel.',
                style: Theme.of(context).textTheme.bodyMedium),
            const SizedBox(height: AppSpacing.xl),
            TextFormField(
              controller: nameCtrl,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(
                labelText: 'Store name *',
                hintText: 'e.g. Main Street Branch',
              ),
              validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
            ),
            const SizedBox(height: AppSpacing.lg),
            TextFormField(
              controller: codeCtrl,
              textInputAction: TextInputAction.next,
              textCapitalization: TextCapitalization.characters,
              decoration: const InputDecoration(
                labelText: 'Store code * (e.g. STR-001)',
                hintText: 'Short unique code',
              ),
              validator: (v) => v == null || v.trim().isEmpty ? 'Required' : null,
            ),
            const SizedBox(height: AppSpacing.lg),
            DropdownButtonFormField<String>(
              initialValue: storeType,
              decoration: const InputDecoration(labelText: 'Type'),
              items: const [
                DropdownMenuItem(value: 'STORE', child: Text('Retail Store')),
                DropdownMenuItem(value: 'WAREHOUSE', child: Text('Warehouse')),
              ],
              onChanged: onTypeChanged,
            ),
            const SizedBox(height: AppSpacing.lg),
            TextFormField(
              controller: line1Ctrl,
              textInputAction: TextInputAction.next,
              decoration: const InputDecoration(labelText: 'Address line 1'),
            ),
            const SizedBox(height: AppSpacing.lg),
            Row(
              children: [
                Expanded(
                  child: TextFormField(
                    controller: cityCtrl,
                    textInputAction: TextInputAction.next,
                    decoration: const InputDecoration(labelText: 'City'),
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: TextFormField(
                    controller: pincodeCtrl,
                    textInputAction: TextInputAction.next,
                    decoration: const InputDecoration(labelText: 'Pincode / ZIP'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.lg),
            CountryField(value: country, onChanged: onCountryChanged),
            const SizedBox(height: AppSpacing.lg),
            TimezoneField(
              label: 'Timezone *',
              value: timezone,
              onChanged: onTimezoneChanged,
            ),
            const SizedBox(height: AppSpacing.xxl),
            FilledButton(
              onPressed: loading ? null : onSubmit,
              child: loading
                  ?  SizedBox(
                      height: 20, width: 20,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
                  : const Text('Create store & finish setup'),
            ),
          ],
        ),
      ),
    );
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

class _StepDot extends StatelessWidget {
  final bool active;
  final bool done;
  final String label;

  const _StepDot({required this.active, required this.done, required this.label});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final bg = done ? cs.primary : active ? cs.primaryContainer : cs.surfaceContainerHighest;
    final fg = done ? cs.onPrimary : active ? cs.onPrimaryContainer : cs.onSurfaceVariant;
    return Container(
      width: 32,
      height: 32,
      decoration: BoxDecoration(color: bg, shape: BoxShape.circle),
      child: Center(
        child: done
            ? Icon(Icons.check, size: 18, color: fg)
            : Text(label, style: TextStyle(color: fg, fontWeight: FontWeight.bold)),
      ),
    );
  }
}

class _StepLine extends StatelessWidget {
  const _StepLine();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 48,
      height: 2,
      color: Theme.of(context).colorScheme.outlineVariant,
    );
  }
}
