import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/auth/password_policy.dart';
import '../../core/format.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../shared/widgets/adaptive_nav_shell.dart';
import 'storefront_providers.dart';
import 'survey_widgets.dart';
import '../../core/theme.dart';

const _destinations = [
  AdaptiveNavDestination(
    label: 'Shop',
    icon: Icons.store_outlined,
    selectedIcon: Icons.store,
  ),
  AdaptiveNavDestination(
    label: 'Cart',
    icon: Icons.shopping_bag_outlined,
    selectedIcon: Icons.shopping_bag,
  ),
];

const _routes = ['/store/products', '/store/cart'];

class StorefrontShell extends ConsumerWidget {
  final String currentLocation;
  final Widget child;

  const StorefrontShell({
    super.key,
    required this.currentLocation,
    required this.child,
  });

  int get _selectedIndex => currentLocation.startsWith('/store/cart') ? 1 : 0;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final count = ref.watch(cartProvider).fold<int>(0, (s, l) => s + l.qty);
    // A deactivated tenant's shop is closed — show a friendly notice instead of
    // letting every product/price call fail with a raw 403.
    final suspended = ref.watch(storefrontSuspendedProvider).value ?? false;

    // Show the preferences sheet once after a customer first signs in or registers.
    ref.listen<bool>(storefrontJustAuthenticatedProvider, (_, justAuth) {
      if (!justAuth) return;
      ref.read(storefrontJustAuthenticatedProvider.notifier).state = false;
      // Only once the stored answer has loaded, or a shopper who answered is asked again (SJ-D62).
      ref
          .read(customerPrefsProvider.notifier)
          .ready
          .catchError((_) {})
          .then((_) {
        if (!context.mounted || ref.read(customerPrefsProvider).prefsAsked) {
          return;
        }
        WidgetsBinding.instance
            .addPostFrameCallback((_) => showPreferencesSheet(context));
      });
    });

    return AdaptiveNavShell(
      title: 'Shop',
      destinations: _destinations,
      selectedIndex: _selectedIndex,
      onDestinationSelected: (i) => context.go(_routes[i]),
      // Only 2 destinations — a bottom bar beats a hamburger-triggered drawer
      // for the phone-first shopping flow (Material's compact-width guidance).
      compactStyle: CompactNavStyle.bottomBar,
      actions: suspended
          ? const []
          : [
              const CurrencyPicker(),
              const _AccountAction(),
              Padding(
                padding: const EdgeInsets.only(right: 8),
                child: Badge(
                  isLabelVisible: count > 0,
                  label: Text('$count'),
                  child: IconButton(
                    icon: const Icon(Icons.shopping_cart_outlined),
                    tooltip: 'Cart',
                    onPressed: () => context.go('/store/cart'),
                  ),
                ),
              ),
            ],
      child: suspended
          ? const _StoreUnavailable()
          : Column(
              children: [
                Expanded(child: child),
                // Sticky cart bar — a constant, low-friction path to checkout
                // while browsing. Hidden on the cart screen (it has its own CTA),
                // and on a product page on a phone, where the page's own Add bar
                // sits above it and the two would stack over the bottom bar (the
                // cart stays one tap away in the app bar and the bottom bar).
                if (!currentLocation.startsWith('/store/cart') &&
                    !(context.isCompact &&
                        currentLocation.startsWith('/store/products/')))
                  const _CartBar(),
              ],
            ),
    );
  }
}

/// Shown when the tenant is deactivated (gateway 403). The shop is closed.
class _StoreUnavailable extends StatelessWidget {
  const _StoreUnavailable();

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.storefront_outlined, size: 72, color: cs.outlineVariant),
            const SizedBox(height: 20),
            Text('This store is currently unavailable',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text('Please check back later or contact the store directly.',
                textAlign: TextAlign.center,
                style: TextStyle(color: cs.outline)),
          ],
        ),
      ),
    );
  }
}

class _CartBar extends ConsumerWidget {
  const _CartBar();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cart = ref.watch(cartProvider);
    if (cart.isEmpty) return const SizedBox.shrink();

    final cs = Theme.of(context).colorScheme;
    final showPrices = ref.watch(storefrontShowPricesProvider);
    final count = cart.fold<int>(0, (s, l) => s + l.qty);
    final total = cart.fold<double>(0, (s, l) => s + l.lineTotal);
    final currency = cart.first.currency;
    final money = AppFormat.money(total, currencyCode: currency);

    return Material(
      color: cs.primary,
      elevation: 8,
      child: SafeArea(
        top: false,
        child: Semantics(
          button: true,
          label: showPrices
              ? '$count item${count == 1 ? '' : 's'} in the cart, $money. View cart'
              : '$count item${count == 1 ? '' : 's'} in the cart. View cart',
          excludeSemantics: true,
          onTap: () => context.go('/store/cart'),
          child: InkWell(
            onTap: () => context.go('/store/cart'),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              child: Row(
                children: [
                  Badge(
                    label: Text('$count'),
                    child: Icon(Icons.shopping_bag, color: cs.onPrimary),
                  ),
                  const SizedBox(width: 16),
                  // Both texts wrap rather than run off the bar when text is enlarged (WCAG 1.4.4).
                  Expanded(
                    child: Text(
                      showPrices
                          ? money
                          : '$count item${count == 1 ? '' : 's'}',
                      style: TextStyle(
                        color: cs.onPrimary,
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Flexible(
                    child: Text(
                      'View cart',
                      textAlign: TextAlign.end,
                      style: TextStyle(
                        color: cs.onPrimary,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  Icon(Icons.chevron_right, color: cs.onPrimary),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// App-bar account button: shows the signed-in email (with sign-out) or a
/// "Sign in" entry point to the customer auth dialog.
/// The currency the shopper sees prices in (03.x): the shop's own and every
/// currency it keeps a rate for. Shown only when there is a choice; prices are
/// always charged in the shop's own currency, and the menu says so.
class CurrencyPicker extends ConsumerWidget {
  const CurrencyPicker({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final shop = ref.watch(storefrontCurrenciesProvider).value;
    if (shop == null || shop.currencies.length < 2) return const SizedBox.shrink();
    final chosen = ref.watch(displayCurrencyProvider) ?? shop.home;
    return PopupMenuButton<String>(
      key: const Key('currency-picker'),
      tooltip: 'Show prices in another currency',
      initialValue: chosen,
      onSelected: (c) =>
          ref.read(displayCurrencyProvider.notifier).state = c == shop.home ? null : c,
      itemBuilder: (_) => [
        for (final c in shop.currencies)
          PopupMenuItem(
            value: c,
            child: Text(c == shop.home ? '$c (you pay in $c)' : '$c, shown at the shop\'s rate'),
          ),
      ],
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          const Icon(Icons.currency_exchange_outlined, size: 18),
          const SizedBox(width: 4),
          Text(chosen, key: const Key('currency-picker-value')),
        ]),
      ),
    );
  }
}

class _AccountAction extends ConsumerWidget {
  const _AccountAction();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(storefrontAuthProvider);
    if (!auth.isSignedIn) {
      return PopupMenuButton<String>(
        icon: const Icon(Icons.person_outline),
        tooltip: 'Account',
        onSelected: (v) {
          if (v == 'signin') {
            showDialog(
                context: context, builder: (_) => const StorefrontAuthDialog());
          } else if (v == 'accessibility') {
            context.go('/store/accessibility');
          } else if (v == 'feedback') {
            showFeedbackSheet(context);
          }
        },
        itemBuilder: (_) => const [
          PopupMenuItem(value: 'signin', child: Text('Sign in')),
          PopupMenuItem(value: 'accessibility', child: Text('Accessibility')),
          PopupMenuItem(value: 'feedback', child: Text('Send feedback')),
        ],
      );
    }
    return PopupMenuButton<String>(
      icon: const Icon(Icons.account_circle),
      tooltip: auth.email ?? 'Account',
      onSelected: (v) {
        if (v == 'orders') {
          context.go('/store/orders');
        } else if (v == 'account') {
          context.go('/store/account');
        } else if (v == 'preferences') {
          showPreferencesSheet(context);
        } else if (v == 'privacy') {
          context.go('/store/privacy');
        } else if (v == 'accessibility') {
          context.go('/store/accessibility');
        } else if (v == 'feedback') {
          showFeedbackSheet(context);
        } else if (v == 'logout') {
          ref.read(storefrontAuthProvider.notifier).logout();
        } else if (v == 'delete_account') {
          showDialog(context: context, builder: (_) => const _DeleteAccountDialog());
        }
      },
      itemBuilder: (_) => [
        PopupMenuItem(
          enabled: false,
          child: Text(auth.email ?? 'Signed in',
              style: const TextStyle(fontWeight: FontWeight.bold)),
        ),
        const PopupMenuItem(value: 'orders', child: Text('My orders')),
        const PopupMenuItem(value: 'account', child: Text('My account')),
        const PopupMenuItem(value: 'preferences', child: Text('My preferences')),
        const PopupMenuItem(
            value: 'privacy', child: Text('Privacy & marketing')),
        const PopupMenuItem(
            value: 'accessibility', child: Text('Accessibility')),
        const PopupMenuItem(value: 'feedback', child: Text('Send feedback')),
        const PopupMenuItem(value: 'logout', child: Text('Sign out')),
        const PopupMenuDivider(),
        const PopupMenuItem(
            value: 'delete_account', child: Text('Delete my account')),
      ],
    );
  }
}

/// SJ-D43: the storefront's self-service "delete my account" — separate from
/// the admin's "Anonymize" action on [_CustomerDetailDialog] in the admin
/// console, which is a shop erasing one of its own customers. This is the
/// person deleting their own login, platform-wide: it asks for the password
/// again (a session left open on a shared device must not be enough), then
/// signs the device out along with it. A shop's own records of this person —
/// their orders, loyalty, customer profile with that shop — are unaffected;
/// deleting them is a separate request to that shop.
class _DeleteAccountDialog extends ConsumerStatefulWidget {
  const _DeleteAccountDialog();

  @override
  ConsumerState<_DeleteAccountDialog> createState() =>
      _DeleteAccountDialogState();
}

class _DeleteAccountDialogState extends ConsumerState<_DeleteAccountDialog> {
  final _passwordCtrl = TextEditingController();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_passwordCtrl.text.isEmpty) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref
          .read(storefrontAuthProvider.notifier)
          .deleteAccount(_passwordCtrl.text);
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Your account has been deleted.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        final status = e is DioException ? e.response?.statusCode : null;
        _error = status == 401
            ? 'Incorrect password.'
            : friendlyError(e, fallback: 'Could not delete the account.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Delete my account?'),
      content: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
                'This permanently deletes your login. It cannot be undone. '
                'Orders and loyalty you have with individual shops are not '
                'affected — ask each shop separately if you want those erased '
                'too.'),
            const SizedBox(height: 16),
            if (_error != null) ...[
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                    color: cs.errorContainer,
                    borderRadius: AppRadius.chip),
                child:
                    Text(_error!, style: TextStyle(color: cs.onErrorContainer)),
              ),
              const SizedBox(height: 12),
            ],
            TextField(
              controller: _passwordCtrl,
              obscureText: true,
              autofocus: true,
              decoration: const InputDecoration(
                  labelText: 'Confirm your password',
                  prefixIcon: Icon(Icons.lock_outline)),
              onSubmitted: (_) => _loading ? null : _submit(),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: cs.error),
          onPressed: _loading ? null : _submit,
          child: _loading
              ? SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: cs.onError))
              : const Text('Delete account'),
        ),
      ],
    );
  }
}

/// Customer sign-in / sign-up dialog (iam self-service).
class StorefrontAuthDialog extends ConsumerStatefulWidget {
  const StorefrontAuthDialog({super.key});

  @override
  ConsumerState<StorefrontAuthDialog> createState() =>
      _StorefrontAuthDialogState();
}

class _StorefrontAuthDialogState extends ConsumerState<StorefrontAuthDialog> {
  final _formKey = GlobalKey<FormState>();
  final _emailCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  final _phoneCtrl = TextEditingController();
  bool _register = false;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
    _phoneCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    final notifier = ref.read(storefrontAuthProvider.notifier);
    try {
      if (_register) {
        await notifier.register(
            _emailCtrl.text.trim(), _passwordCtrl.text, _phoneCtrl.text.trim());
      } else {
        await notifier.login(_emailCtrl.text.trim(), _passwordCtrl.text);
      }
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(_register ? 'Account created.' : 'Signed in.')),
      );
      // Signal the shell to show the preferences sheet if not yet asked.
      ref.read(storefrontJustAuthenticatedProvider.notifier).state = true;
    } catch (e) {
      setState(() {
        _loading = false;
        final status = e is DioException ? e.response?.statusCode : null;
        _error = status == 401
            ? 'Incorrect email or password.'
            : status == 409
                ? 'An account with this email already exists.'
                : friendlyError(e,
                    fallback:
                        'Could not ${_register ? 'register' : 'sign in'}.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    // Only in sign-up mode: signing in never touches the policy endpoint.
    final policy = _register ? watchPasswordPolicy(ref) : PasswordPolicy.fallback;
    return AlertDialog(
      title: Text(_register ? 'Create account' : 'Sign in'),
      content: SizedBox(
        width: 360,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_error != null) ...[
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                      color: cs.errorContainer,
                      borderRadius: AppRadius.chip),
                  child: Text(_error!,
                      style: TextStyle(color: cs.onErrorContainer)),
                ),
                const SizedBox(height: 12),
              ],
              TextFormField(
                controller: _emailCtrl,
                keyboardType: TextInputType.emailAddress,
                decoration: const InputDecoration(
                    labelText: 'Email', prefixIcon: Icon(Icons.email_outlined)),
                validator: (v) =>
                    v == null || !v.contains('@') ? 'Valid email required' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _passwordCtrl,
                obscureText: true,
                decoration: InputDecoration(
                  labelText: 'Password',
                  prefixIcon: const Icon(Icons.lock_outline),
                  // The published policy's rule, before it is typed — never
                  // learned only from a refusal.
                  helperText: _register ? passwordRuleText(policy) : null,
                  helperMaxLines: 2,
                  errorMaxLines: 2,
                ),
                // A sign-in asks only that a password is typed — the server says
                // whether it is the right one; a new password is held to the
                // published policy before anything is sent.
                validator: (v) => v == null || v.isEmpty
                    ? 'Enter your password'
                    : _register
                        ? passwordLengthProblemPlain(v, policy)
                        : null,
              ),
              if (!_register) ...[
                Align(
                  alignment: AlignmentDirectional.centerEnd,
                  child: TextButton(
                    key: const Key('forgot-password'),
                    onPressed: _loading
                        ? null
                        : () {
                            Navigator.pop(context);
                            // Named, so the forgot-password page can lead a
                            // shopper back to the shop rather than to staff
                            // sign-in once they're done.
                            context.go('/forgot-password?from=storefront');
                          },
                    child: const Text('Forgot password?'),
                  ),
                ),
              ],
              if (_register) ...[
                const SizedBox(height: 12),
                TextFormField(
                  controller: _phoneCtrl,
                  keyboardType: TextInputType.phone,
                  decoration: const InputDecoration(
                      labelText: 'Phone number',
                      prefixIcon: Icon(Icons.phone_outlined)),
                  validator: (v) =>
                      v == null || v.trim().isEmpty ? 'Phone number required' : null,
                ),
              ],
              const SizedBox(height: 8),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton(
                  onPressed: _loading
                      ? null
                      : () => setState(() {
                            _register = !_register;
                            _error = null;
                          }),
                  child: Text(_register
                      ? 'Have an account? Sign in'
                      : 'New here? Create an account'),
                ),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.pop(context),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: _loading
              ?  SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(
                      strokeWidth: 2, color: Theme.of(context).colorScheme.onPrimary))
              : Text(_register ? 'Create account' : 'Sign in'),
        ),
      ],
    );
  }
}
