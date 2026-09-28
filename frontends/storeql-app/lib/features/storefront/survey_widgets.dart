import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/device.dart';
import '../../core/spacing.dart';
import 'storefront_providers.dart';
import '../../core/theme.dart';

// ── Shared bottom-sheet chrome ────────────────────────────────────────────────

const _sheetShape = RoundedRectangleBorder(
  borderRadius: AppRadius.sheet,
);

// ── 1. Gender picker ──────────────────────────────────────────────────────────

class GenderPickerSheet extends ConsumerStatefulWidget {
  const GenderPickerSheet({super.key});

  @override
  ConsumerState<GenderPickerSheet> createState() => _GenderPickerSheetState();
}

class _GenderPickerSheetState extends ConsumerState<GenderPickerSheet> {
  CustomerGender? _selected;

  static const _options = [
    (label: 'Male', value: CustomerGender.male),
    (label: 'Female', value: CustomerGender.female),
    (label: 'Other', value: CustomerGender.other),
    (label: 'Prefer not to say', value: CustomerGender.preferNotToSay),
  ];

  Future<void> _skip() async {
    await ref.read(customerPrefsProvider.notifier).skipGender();
    if (mounted) Navigator.of(context).pop(false);
  }

  Future<void> _save() async {
    if (_selected == null) return;
    await ref.read(customerPrefsProvider.notifier).setGender(_selected!);
    if (mounted) Navigator.of(context).pop(true);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return _SheetFrame(
      title: 'Tell us about yourself',
      trailing: TextButton(onPressed: _skip, child: const Text('Skip')),
      children: [
        const Gap(AppSpacing.xs),
        Text(
          'This helps us personalise your experience.',
          style: Theme.of(
            context,
          ).textTheme.bodySmall?.copyWith(color: cs.outline),
        ),
        const Gap(AppSpacing.lg),
        Wrap(
          spacing: AppSpacing.sm,
          runSpacing: AppSpacing.xs,
          children: _options
              .map(
                (o) => ChoiceChip(
                  label: Text(o.label),
                  selected: _selected == o.value,
                  onSelected: (_) => setState(() => _selected = o.value),
                  selectedColor: cs.primaryContainer,
                ),
              )
              .toList(),
        ),
        const Gap(AppSpacing.xl),
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            onPressed: _selected != null ? _save : null,
            child: const Text('Save preference'),
          ),
        ),
      ],
    );
  }
}

Future<void> showGenderPickerSheet(BuildContext context) async {
  final result = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    shape: _sheetShape,
    builder: (_) => const GenderPickerSheet(),
  );
  // Drag-dismiss (null result) treated the same as Skip
  if (result == null && context.mounted) {
    await ProviderScope.containerOf(
      context,
    ).read(customerPrefsProvider.notifier).skipGender();
  }
}

// ── 2. Post-order survey ──────────────────────────────────────────────────────

class PostOrderSurveySheet extends ConsumerStatefulWidget {
  final String orderId;
  const PostOrderSurveySheet({required this.orderId, super.key});

  @override
  ConsumerState<PostOrderSurveySheet> createState() =>
      _PostOrderSurveySheetState();
}

class _PostOrderSurveySheetState extends ConsumerState<PostOrderSurveySheet> {
  int? _rating;
  double _nps = 7;
  final _commentCtrl = TextEditingController();

  static const _emojis = ['😞', '😕', '😐', '😊', '😄'];

  @override
  void dispose() {
    _commentCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_rating == null) return;
    final info = DeviceInfoCapture.capture(MediaQuery.sizeOf(context));
    final response = SurveyResponse(
      orderId: widget.orderId,
      experienceRating: _rating!,
      nps: _nps.round(),
      comment: _commentCtrl.text.trim().isEmpty ? null : _commentCtrl.text.trim(),
      platform: info.platform,
      formFactor: info.formFactor,
      submittedAt: DateTime.now(),
    );
    await ref.read(customerPrefsProvider.notifier).addSurvey(response);
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return _SheetFrame(
      title: 'Quick feedback',
      roomForKeyboard: true,
      trailing: TextButton(
        onPressed: () => Navigator.of(context).pop(),
        child: const Text('Skip'),
      ),
      children: [
        const Gap(AppSpacing.xs),
        Text(
          'Takes less than a minute.',
          style: Theme.of(
            context,
          ).textTheme.bodySmall?.copyWith(color: cs.outline),
        ),
        const Gap(AppSpacing.lg),
        Text(
          'How was your experience?',
          style: Theme.of(context).textTheme.labelLarge,
        ),
        const Gap(AppSpacing.sm),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: List.generate(5, (i) {
            final isSelected = _rating == i + 1;
            return Semantics(
              button: true,
              selected: isSelected,
              label: 'Rate ${i + 1} of 5',
              excludeSemantics: true,
              onTap: () => setState(() => _rating = i + 1),
              // An InkWell, not a GestureDetector: reachable by keyboard (12.11).
              child: InkWell(
                customBorder: const CircleBorder(),
                onTap: () => setState(() => _rating = i + 1),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 150),
                  padding: const EdgeInsets.all(AppSpacing.sm),
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color:
                        isSelected ? cs.primaryContainer : Colors.transparent,
                  ),
                  child: Text(_emojis[i], style: const TextStyle(fontSize: 28)),
                ),
              ),
            );
          }),
        ),
        const Gap(AppSpacing.lg),
        Text(
          'Would you recommend us? (${_nps.round()}/10)',
          style: Theme.of(context).textTheme.labelLarge,
        ),
        Slider(
          value: _nps,
          min: 1,
          max: 10,
          divisions: 9,
          activeColor: cs.primary,
          onChanged: (v) => setState(() => _nps = v),
        ),
        const Gap(AppSpacing.md),
        Text(
          'Any comments? (optional)',
          style: Theme.of(context).textTheme.labelLarge,
        ),
        const Gap(AppSpacing.sm),
        TextFormField(
          controller: _commentCtrl,
          maxLines: 3,
          maxLength: 300,
          decoration: const InputDecoration(
            hintText: 'Tell us more…',
            counterText: '',
          ),
        ),
        const Gap(AppSpacing.xl),
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            onPressed: _rating != null ? _submit : null,
            child: const Text('Submit'),
          ),
        ),
      ],
    );
  }
}

void showPostOrderSurveySheet(BuildContext context, String orderId) {
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    shape: _sheetShape,
    builder: (_) => PostOrderSurveySheet(orderId: orderId),
  );
}

// ── 3. App feedback ───────────────────────────────────────────────────────────

class FeedbackSheet extends ConsumerStatefulWidget {
  const FeedbackSheet({super.key});

  @override
  ConsumerState<FeedbackSheet> createState() => _FeedbackSheetState();
}

class _FeedbackSheetState extends ConsumerState<FeedbackSheet> {
  String? _category;
  int? _starRating;
  final _textCtrl = TextEditingController();
  bool _submitting = false;

  static const _categories = [
    (label: 'Bug 🐛', value: 'Bug'),
    (label: 'Feature request 💡', value: 'Feature request'),
    (label: 'Compliment 🙏', value: 'Compliment'),
    (label: 'Accessibility', value: 'Accessibility'),
    (label: 'Other', value: 'Other'),
  ];

  @override
  void dispose() {
    _textCtrl.dispose();
    super.dispose();
  }

  bool get _canSubmit =>
      _category != null && _textCtrl.text.trim().isNotEmpty && !_submitting;

  Future<void> _submit() async {
    setState(() => _submitting = true);
    final info = DeviceInfoCapture.capture(MediaQuery.sizeOf(context));
    final entry = AppFeedbackEntry(
      category: _category!,
      text: _textCtrl.text.trim(),
      starRating: _starRating,
      platform: info.platform,
      formFactor: info.formFactor,
      submittedAt: DateTime.now(),
    );
    await ref.read(customerPrefsProvider.notifier).addFeedback(entry);
    if (mounted) {
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Thanks for your feedback!')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return _SheetFrame(
      title: 'Send feedback',
      roomForKeyboard: true,
      trailing: IconButton(
        icon: const Icon(Icons.close),
        tooltip: 'Close',
        onPressed: () => Navigator.of(context).pop(),
      ),
      children: [
        const Gap(AppSpacing.md),
        Text('Category', style: Theme.of(context).textTheme.labelLarge),
        const Gap(AppSpacing.sm),
        Wrap(
          spacing: AppSpacing.sm,
          runSpacing: AppSpacing.xs,
          children: _categories
              .map(
                (c) => FilterChip(
                  label: Text(c.label),
                  selected: _category == c.value,
                  onSelected: (_) => setState(() => _category = c.value),
                  selectedColor: cs.primaryContainer,
                ),
              )
              .toList(),
        ),
        const Gap(AppSpacing.lg),
        TextField(
          controller: _textCtrl,
          minLines: 3,
          maxLines: 6,
          maxLength: 500,
          onChanged: (_) => setState(() {}),
          decoration: const InputDecoration(
            labelText: 'Describe your feedback',
            counterText: '',
          ),
        ),
        const Gap(AppSpacing.lg),
        Text(
          'Overall rating (optional)',
          style: Theme.of(context).textTheme.labelLarge,
        ),
        const Gap(AppSpacing.sm),
        Row(
          children: List.generate(5, (i) {
            final filled = _starRating != null && i < _starRating!;
            return IconButton(
              icon: Icon(
                filled ? Icons.star : Icons.star_border,
                color: filled ? cs.primary : cs.outline,
              ),
              tooltip: '${i + 1} star${i == 0 ? '' : 's'}',
              onPressed: () => setState(() {
                // Tapping the same star again clears the rating
                _starRating = _starRating == i + 1 ? null : i + 1;
              }),
            );
          }),
        ),
        const Gap(AppSpacing.xl),
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            onPressed: _canSubmit ? _submit : null,
            child: _submitting
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Text('Submit feedback'),
          ),
        ),
      ],
    );
  }
}

/// The frame every storefront sheet shares: the safe area, a scroll so enlarged text is never cut off,
/// the padding (with room for the keyboard on a sheet that has a text field), and a header whose
/// title is announced as a heading and wraps rather than overflows (12.11), with one action beside it.
class _SheetFrame extends StatelessWidget {
  final String title;
  final Widget trailing;
  final List<Widget> children;
  final bool roomForKeyboard;

  const _SheetFrame({
    required this.title,
    required this.trailing,
    required this.children,
    this.roomForKeyboard = false,
  });

  @override
  Widget build(BuildContext context) {
    final column = Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: Semantics(
                header: true,
                child: Text(
                  title,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
              ),
            ),
            trailing,
          ],
        ),
        ...children,
      ],
    );
    return SafeArea(
      top: false,
      child: SingleChildScrollView(
        padding: roomForKeyboard
            ? EdgeInsets.only(
                left: AppSpacing.xl,
                right: AppSpacing.xl,
                top: AppSpacing.xl,
                bottom:
                    AppSpacing.xl + MediaQuery.of(context).viewInsets.bottom,
              )
            : AppSpacing.pagePadding,
        child: column,
      ),
    );
  }
}

void showFeedbackSheet(BuildContext context) {
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    shape: _sheetShape,
    builder: (_) => const FeedbackSheet(),
  );
}

// ── 4. Personal preferences ───────────────────────────────────────────────────

class PreferencesSheet extends ConsumerStatefulWidget {
  const PreferencesSheet({super.key});

  @override
  ConsumerState<PreferencesSheet> createState() => _PreferencesSheetState();
}

class _PreferencesSheetState extends ConsumerState<PreferencesSheet> {
  Set<String> _shoppingFor = {};
  String _notifications = 'None';

  static const _shoppingOptions = ['Myself', 'Family', 'Business'];
  static const _notifOptions = ['Order updates', 'Promotions', 'Both', 'None'];

  @override
  void initState() {
    super.initState();
    // Pre-fill from saved state if the user opens this from the menu
    final existing = ref.read(customerPrefsProvider).prefs;
    if (existing != null) {
      _shoppingFor = Set<String>.from(existing.shoppingFor);
      _notifications = existing.notifications;
    }
  }

  Future<void> _skip() async {
    await ref.read(customerPrefsProvider.notifier).skipPrefs();
    if (mounted) Navigator.of(context).pop();
  }

  Future<void> _save() async {
    final prefs = CustomerPrefs(
      shoppingFor: _shoppingFor.toList(),
      notifications: _notifications,
    );
    await ref.read(customerPrefsProvider.notifier).setPrefs(prefs);
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return _SheetFrame(
      title: 'Your preferences',
      trailing: TextButton(onPressed: _skip, child: const Text('Skip')),
      children: [
        const Gap(AppSpacing.xs),
        Text(
          'Helps us show you relevant products and updates.',
          style: Theme.of(
            context,
          ).textTheme.bodySmall?.copyWith(color: cs.outline),
        ),
        const Gap(AppSpacing.lg),
        Text('Shopping for', style: Theme.of(context).textTheme.labelLarge),
        const Gap(AppSpacing.sm),
        Wrap(
          spacing: AppSpacing.sm,
          runSpacing: AppSpacing.xs,
          children: _shoppingOptions
              .map(
                (o) => FilterChip(
                  label: Text(o),
                  selected: _shoppingFor.contains(o),
                  onSelected: (on) => setState(() {
                    if (on) {
                      _shoppingFor = {..._shoppingFor, o};
                    } else {
                      _shoppingFor = _shoppingFor.where((s) => s != o).toSet();
                    }
                  }),
                  selectedColor: cs.primaryContainer,
                ),
              )
              .toList(),
        ),
        const Gap(AppSpacing.lg),
        Text('Notifications', style: Theme.of(context).textTheme.labelLarge),
        const Gap(AppSpacing.sm),
        Wrap(
          spacing: AppSpacing.sm,
          runSpacing: AppSpacing.xs,
          children: _notifOptions
              .map(
                (o) => ChoiceChip(
                  label: Text(o),
                  selected: _notifications == o,
                  onSelected: (_) => setState(() => _notifications = o),
                  selectedColor: cs.primaryContainer,
                ),
              )
              .toList(),
        ),
        const Gap(AppSpacing.xl),
        SizedBox(
          width: double.infinity,
          child: FilledButton(
            onPressed: _shoppingFor.isNotEmpty ? _save : null,
            child: const Text('Save preferences'),
          ),
        ),
      ],
    );
  }
}

Future<void> showPreferencesSheet(BuildContext context) async {
  final result = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    shape: _sheetShape,
    builder: (_) => const PreferencesSheet(),
  );
  // Drag-dismiss treated as skip (marks asked so it won't auto-show again)
  if (result == null && context.mounted) {
    await ProviderScope.containerOf(
      context,
    ).read(customerPrefsProvider.notifier).skipPrefs();
  }
}
