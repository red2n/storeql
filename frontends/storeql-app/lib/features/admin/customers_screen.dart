import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/util/status_labels.dart';
import '../../shared/widgets/empty_state.dart';
import '../../shared/widgets/error_view.dart';
import '../../shared/widgets/loading_view.dart';
import '../../shared/widgets/page_header.dart';
import 'customer_providers.dart';
import 'sales_invoices_dialog.dart';
import 'send_text_dialog.dart';
import 'providers/customer_search.dart';
import 'providers/customers_pagination.dart';
import 'guardian_consent_section.dart';
import 'customer_marketing_section.dart';
import 'loyalty_programme_dialog.dart';
import '../../core/auth/auth_notifier.dart';
import '../../core/auth/auth_state.dart';

class CustomersScreen extends ConsumerStatefulWidget {
  const CustomersScreen({super.key});

  @override
  ConsumerState<CustomersScreen> createState() => _CustomersScreenState();
}

class _CustomersScreenState extends ConsumerState<CustomersScreen> {
  final _scrollController = ScrollController();
  final _searchCtrl = TextEditingController();

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scrollController.dispose();
    _searchCtrl.dispose();
    super.dispose();
  }

  /// Whether the list shows the server's search results (rather than every
  /// customer, or the loaded ones filtered because the server would not search).
  bool get _serverSearch {
    final search = ref.read(customerSearchProvider);
    return search.active && !search.localOnly;
  }

  /// Fetch the next page once the user scrolls within 300px of the bottom —
  /// but not on a pull at the top of a short list, which is a refresh.
  void _onScroll() {
    final position = _scrollController.position;
    if (position.pixels > 0 &&
        position.pixels >= position.maxScrollExtent - 300) {
      _loadMore();
    }
  }

  Future<void> _refresh() => _serverSearch
      ? ref.read(customerSearchProvider.notifier).refresh()
      : ref.read(customersPaginationProvider.notifier).refresh();

  Future<void> _loadMore() => _serverSearch
      ? ref.read(customerSearchProvider.notifier).loadMore()
      : ref.read(customersPaginationProvider.notifier).loadMore();

  /// Every keystroke goes to the search, which asks the server once typing
  /// pauses; the field rebuilds so its clear button follows the text.
  void _onSearchChanged(String text) {
    setState(() {});
    ref.read(customerSearchProvider.notifier).search(text);
  }

  void _clearSearch() {
    _searchCtrl.clear();
    _onSearchChanged('');
  }

  @override
  Widget build(BuildContext context) {
    final page = ref.watch(customersPaginationProvider);
    final search = ref.watch(customerSearchProvider);
    final auth = ref.watch(authNotifierProvider).value;
    final management = auth is AuthAuthenticated &&
        (auth.roles.contains('OWNER') ||
            auth.roles.contains('MANAGER') ||
            auth.roles.contains('PLATFORM_ADMIN'));
    // One inset for the title, the search box and the cards, so their edges
    // line up: 16 on a phone, 24 from tablet width.
    final gutter = context.pageGutter;
    return ContentBounds(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          PageHeader(
            title: 'Customers',
            actions: [
              FilledButton.icon(
                onPressed: () => showDialog(
                    context: context, builder: (_) => const _AddCustomerDialog()),
                icon: const Icon(Icons.person_add_alt),
                label: const Text('Add customer'),
              ),
              IconButton(
                icon: const Icon(Icons.refresh),
                tooltip: 'Refresh customers',
                onPressed: _refresh,
              ),
              // The programme is management's: the ladder, the expiry rule, the qualifying window.
              if (management)
                IconButton(
                  key: const Key('loyalty-programme'),
                  icon: const Icon(Icons.stairs_outlined),
                  tooltip: 'Loyalty programme',
                  onPressed: () => showDialog<void>(
                      context: context, builder: (_) => const LoyaltyProgrammeDialog()),
                ),
            ],
          ),
          // The search asks customer-svc, which matches every customer by
          // name, email or phone. Only if it will not (400) does the box
          // filter the customers loaded so far — and then it says so.
          Padding(
            padding: EdgeInsetsDirectional.fromSTEB(
                gutter, 0, gutter, AppSpacing.md),
            child: Align(
              alignment: AlignmentDirectional.centerStart,
              child: ConstrainedBox(
                constraints:
                    const BoxConstraints(maxWidth: AppBreakpoints.formMaxWidth),
                child: TextField(
                  key: const Key('customers-search'),
                  controller: _searchCtrl,
                  onChanged: _onSearchChanged,
                  textInputAction: TextInputAction.search,
                  decoration: InputDecoration(
                    hintText: 'Search by name, email or phone',
                    prefixIcon: const Icon(Icons.search),
                    suffixIcon: _searchCtrl.text.isEmpty
                        ? null
                        : IconButton(
                            icon: const Icon(Icons.close),
                            tooltip: 'Clear search',
                            onPressed: _clearSearch,
                          ),
                  ),
                ),
              ),
            ),
          ),
          Expanded(
            child: search.active && !search.localOnly
                ? _searchResults(search, gutter)
                : _loadedCustomers(page, search, gutter),
          ),
        ],
      ),
    );
  }

  /// What the server found for the search box.
  Widget _searchResults(CustomerSearch search, double gutter) {
    final notifier = ref.read(customerSearchProvider.notifier);
    final error = search.error;
    if (search.customers.isEmpty) {
      if (search.isLoading) {
        return const LoadingView(label: 'Searching customers…');
      }
      if (error != null) {
        return ErrorView(
          message: friendlyError(error, fallback: 'Could not search customers.'),
          onRetry: notifier.refresh,
        );
      }
      return EmptyState(
        icon: Icons.search_off,
        title: 'No customers match “${search.query}”',
        message: 'The search looks at names, email addresses and phone numbers.',
      );
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // The last matches stay while the next query is asked, under a thin
        // bar, rather than the list blanking on every pause in typing.
        SizedBox(
          height: 2,
          child: search.isLoading ? const LinearProgressIndicator() : null,
        ),
        Expanded(
          child: _customerList(
            key: ValueKey('search:${search.query}'),
            customers: search.customers,
            gutter: gutter,
            hasMore: search.hasMore,
            isLoadingMore: search.isLoadingMore,
            // A page of matches may not fill the screen to scroll from.
            offerMore: true,
          ),
        ),
      ],
    );
  }

  /// Every customer, a page at a time; or, when the server would not search,
  /// the ones loaded so far filtered by the search box.
  Widget _loadedCustomers(CustomersPage page, CustomerSearch search, double gutter) {
    if (page.isLoadingInitial) {
      return const LoadingView(label: 'Loading customers…');
    }
    final error = page.error;
    if (error != null && page.customers.isEmpty) {
      return ErrorView(
        message: friendlyError(error, fallback: 'Could not load customers.'),
        onRetry: _refresh,
      );
    }
    if (page.customers.isEmpty) {
      return const EmptyState(
        icon: Icons.people_outline,
        title: 'No customers yet',
      );
    }
    final query = search.localOnly ? search.query : '';
    final customers = query.isEmpty
        ? page.customers
        : page.customers.where((c) => _matchesSearch(c, query)).toList();
    const searchedNote = 'Only the customers loaded so far are searched.';
    if (customers.isEmpty) {
      return EmptyState(
        icon: Icons.search_off,
        title: page.hasMore
            ? 'No loaded customers match “$query”'
            : 'No customers match “$query”',
        message: page.hasMore ? searchedNote : null,
        action: page.isLoadingMore
            ? const CircularProgressIndicator()
            : page.hasMore
                ? OutlinedButton.icon(
                    onPressed: _loadMore,
                    icon: const Icon(Icons.expand_more),
                    label: const Text('Load more customers'),
                  )
                : null,
      );
    }
    // A next page that failed is offered again by a button, never retried by
    // itself over and over.
    final moreFailed = error != null;
    return _customerList(
      key: ValueKey('loaded:$query'),
      customers: customers,
      gutter: gutter,
      hasMore: page.hasMore,
      isLoadingMore: page.isLoadingMore,
      // Under a filter the matches may be too few to scroll to the end, so
      // the next page can also be asked for.
      offerMore: query.isNotEmpty || moreFailed,
      note: moreFailed
          ? friendlyError(error, fallback: 'Could not load more customers.')
          : query.isNotEmpty
              ? searchedNote
              : null,
    );
  }

  /// The customers as cards, with a footer while there are more: a spinner
  /// while the next page loads, else — when [offerMore] — a button for it.
  Widget _customerList({
    required Key key,
    required List<Customer> customers,
    required double gutter,
    required bool hasMore,
    required bool isLoadingMore,
    required bool offerMore,
    String? note,
  }) {
    final cs = Theme.of(context).colorScheme;
    return RefreshIndicator.adaptive(
      onRefresh: _refresh,
      child: ListView.separated(
        key: key,
        controller: _scrollController,
        // Scrollable even when short, so a pull always refreshes.
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsetsDirectional.fromSTEB(gutter, 0, gutter, AppSpacing.lg),
        itemCount: customers.length + (hasMore || isLoadingMore ? 1 : 0),
        separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.xs),
        itemBuilder: (_, i) {
          if (i >= customers.length) {
            if (offerMore && !isLoadingMore) {
              return Padding(
                padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
                child: Column(
                  children: [
                    if (note != null)
                      Text(
                        note,
                        textAlign: TextAlign.center,
                        style: TextStyle(color: cs.onSurfaceVariant),
                      ),
                    TextButton.icon(
                      onPressed: _loadMore,
                      icon: const Icon(Icons.expand_more),
                      label: const Text('Load more customers'),
                    ),
                  ],
                ),
              );
            }
            // The footer is built: the list was scrolled to its end, or the
            // window is taller than what has loaded, so no scroll will ever
            // come. Either way the next page is what it waits for, so ask for
            // it now; loadMore ignores a call while one is on its way.
            if (!isLoadingMore) {
              WidgetsBinding.instance.addPostFrameCallback((_) {
                if (mounted) _loadMore();
              });
            }
            return const Padding(
              padding: EdgeInsets.all(AppSpacing.lg),
              child: Center(child: CircularProgressIndicator()),
            );
          }
          final c = customers[i];
          return Card(
            child: ListTile(
              onTap: () => showDialog(
                context: context,
                builder: (_) => _CustomerDetailDialog(customer: c),
              ),
              leading: CircleAvatar(
                backgroundColor: cs.primaryContainer,
                child: Text(
                  (c.firstName.isNotEmpty ? c.firstName[0] : '?').toUpperCase(),
                  style: TextStyle(color: cs.onPrimaryContainer),
                ),
              ),
              title: Text(c.fullName,
                  style: const TextStyle(fontWeight: FontWeight.bold)),
              subtitle: Text([
                c.email,
                if (c.phone != null && c.phone!.isNotEmpty) c.phone,
              ].whereType<String>().join(' · ')),
              trailing: const Icon(Icons.chevron_right),
            ),
          );
        },
      ),
    );
  }
}

/// A loyalty movement in words: what happened to the points.
String _loyaltyEntryLabel(String type) => switch (type.toUpperCase()) {
      'EARN' => 'Points earned',
      'REDEEM' => 'Points redeemed',
      'EXPIRE' => 'Points expired',
      'ADJUST' => 'Adjusted',
      _ => humanizeCode(type),
    };

/// Why and when a loyalty movement happened (`Till sale · 20 Sept 2026`);
/// null when neither is known.
Widget? _ledgerLine(LoyaltyLedgerEntry e) {
  final parts = [
    if (e.reason != null && e.reason!.trim().isNotEmpty) e.reason!.trim(),
    if (e.createdAt.isNotEmpty) AppFormat.date(e.createdAt),
  ];
  return parts.isEmpty ? null : Text(parts.join(' · '));
}

/// What an address is for, in words.
String _addressTypeLabel(String type) => switch (type.toUpperCase()) {
      'HOME' => 'Home',
      'WORK' => 'Work',
      'BILLING' => 'Billing',
      'SHIPPING' => 'Shipping',
      _ => humanizeCode(type),
    };

String _genderLabel(String gender) => switch (gender.toUpperCase()) {
      'MALE' => 'Male',
      'FEMALE' => 'Female',
      'OTHER' => 'Other',
      _ => humanizeCode(gender),
    };

final _nonDigit = RegExp(r'\D');
final _phoneLike = RegExp(r'^[\d\s()+.-]+$');

/// Whether [c] matches what was typed in the search box: any part of the name,
/// email or phone number, ignoring case, and a phone number however either
/// side spaces it, so "07700 900 123" finds 07700900123.
bool _matchesSearch(Customer c, String query) {
  final q = query.toLowerCase();
  final phone = c.phone ?? '';
  if (c.fullName.toLowerCase().contains(q) ||
      c.email.toLowerCase().contains(q) ||
      phone.toLowerCase().contains(q)) {
    return true;
  }
  if (!_phoneLike.hasMatch(q)) return false;
  final digits = q.replaceAll(_nonDigit, '');
  return digits.isNotEmpty && phone.replaceAll(_nonDigit, '').contains(digits);
}

class _AddCustomerDialog extends ConsumerStatefulWidget {
  const _AddCustomerDialog();

  @override
  ConsumerState<_AddCustomerDialog> createState() => _AddCustomerDialogState();
}

class _AddCustomerDialogState extends ConsumerState<_AddCustomerDialog> {
  final _formKey = GlobalKey<FormState>();
  final _emailCtrl = TextEditingController();
  final _phoneCtrl = TextEditingController();
  final _firstCtrl = TextEditingController();
  final _lastCtrl = TextEditingController();
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _emailCtrl.dispose();
    _phoneCtrl.dispose();
    _firstCtrl.dispose();
    _lastCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.customer}/customers',
        data: {
          'email': _emailCtrl.text.trim(),
          'phone': _phoneCtrl.text.trim().isEmpty ? null : _phoneCtrl.text.trim(),
          'firstName': _firstCtrl.text.trim(),
          'lastName': _lastCtrl.text.trim(),
          'gdprConsent': true,
        },
      );
      if (!mounted) return;
      ref.read(customersPaginationProvider.notifier).refresh();
      // A search showing is asked again too, so the change shows there.
      ref.read(customerSearchProvider.notifier).refresh();
      Navigator.pop(context);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = (e is DioException && e.response?.statusCode == 409)
            ? 'A customer with this email already exists.'
            : friendlyError(e, fallback: 'Could not add customer.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return AlertDialog(
      title: const Text('Add customer'),
      content: SizedBox(
        width: 400,
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
              Row(
                children: [
                  Expanded(
                    child: TextFormField(
                      controller: _firstCtrl,
                      decoration:
                          const InputDecoration(labelText: 'First name *'),
                      validator: (v) =>
                          v == null || v.trim().isEmpty ? 'Required' : null,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: TextFormField(
                      controller: _lastCtrl,
                      decoration:
                          const InputDecoration(labelText: 'Last name *'),
                      validator: (v) =>
                          v == null || v.trim().isEmpty ? 'Required' : null,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _emailCtrl,
                keyboardType: TextInputType.emailAddress,
                decoration: const InputDecoration(
                    labelText: 'Email *', prefixIcon: Icon(Icons.email_outlined)),
                validator: (v) =>
                    v == null || !v.contains('@') ? 'Valid email required' : null,
              ),
              const SizedBox(height: 12),
              TextFormField(
                controller: _phoneCtrl,
                keyboardType: TextInputType.phone,
                decoration: const InputDecoration(
                    labelText: 'Phone', prefixIcon: Icon(Icons.phone_outlined)),
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
              : const Text('Add'),
        ),
      ],
    );
  }
}

class _CustomerDetailDialog extends ConsumerWidget {
  final Customer customer;
  const _CustomerDetailDialog({required this.customer});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    // Watch the detail provider so an in-place edit refreshes name/phone/etc.
    final c = ref.watch(customerDetailProvider(customer.id)).value ?? customer;
    final loyaltyAsync = ref.watch(customerLoyaltyProvider(customer.id));
    final creditAsync = ref.watch(customerStoreCreditProvider(customer.id));
    final ledgerAsync = ref.watch(customerLoyaltyLedgerProvider(customer.id));

    return AlertDialog(
      title: Row(
        children: [
          Expanded(child: Text(c.fullName)),
          IconButton(
            icon: const Icon(Icons.edit_outlined),
            tooltip: 'Edit details',
            onPressed: () => showDialog(
              context: context,
              builder: (_) => _EditCustomerDialog(customer: c),
            ),
          ),
        ],
      ),
      content: SizedBox(
        width: 480,
        height: 480,
        child: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text([
                c.email,
                if (c.phone != null && c.phone!.isNotEmpty) c.phone,
                if (c.gender != null && c.gender!.isNotEmpty) _genderLabel(c.gender!),
                if (c.dob != null && c.dob!.isNotEmpty) 'Born ${AppFormat.date(c.dob)}',
              ].whereType<String>().join(' · '), style: TextStyle(color: cs.outline)),
              const SizedBox(height: 16),
              Row(
                children: [
                  Expanded(
                    child: _StatCard(
                      icon: Icons.stars_outlined,
                      label: 'Loyalty points',
                      value: loyaltyAsync.maybeWhen(
                        data: (l) => l.pointsBalance.toStringAsFixed(0),
                        orElse: () => '…',
                      ),
                      sub: loyaltyAsync.maybeWhen(
                        data: (l) => l.tierLine,
                        orElse: () => '',
                      ),
                      color: cs.primaryContainer,
                      fg: cs.onPrimaryContainer,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: _StatCard(
                      icon: Icons.card_giftcard_outlined,
                      label: 'Store credit',
                      value: creditAsync.maybeWhen(
                        data: (c) =>
                            AppFormat.money(c.balance, currencyCode: c.currency),
                        orElse: () => '…',
                      ),
                      sub: '',
                      color: cs.tertiaryContainer,
                      fg: cs.onTertiaryContainer,
                    ),
                  ),
                ],
              ),
              // The warning a customer is owed: points about to die (13.x).
              loyaltyAsync.maybeWhen(
                data: (l) => l.expiringSoon == null
                    ? const SizedBox.shrink()
                    : Padding(
                        padding: const EdgeInsets.only(top: AppSpacing.sm),
                        child: Row(children: [
                          Icon(Icons.hourglass_bottom_outlined,
                              size: 16, color: context.status.warning),
                          const SizedBox(width: AppSpacing.xs),
                          Flexible(
                            child: Text(l.expiringLine,
                                key: const Key('loyalty-expiring'),
                                style: TextStyle(color: context.status.warning)),
                          ),
                        ]),
                      ),
                orElse: () => const SizedBox.shrink(),
              ),
              const SizedBox(height: 16),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  OutlinedButton.icon(
                    onPressed: () => _points(context, ref, 'earn'),
                    icon: const Icon(Icons.add, size: 18),
                    label: const Text('Earn points'),
                  ),
                  OutlinedButton.icon(
                    onPressed: () => _points(context, ref, 'redeem'),
                    icon: const Icon(Icons.remove, size: 18),
                    label: const Text('Redeem points'),
                  ),
                  OutlinedButton.icon(
                    onPressed: () => _storeCredit(context, ref, 'issue'),
                    icon: const Icon(Icons.add_card, size: 18),
                    label: const Text('Issue credit'),
                  ),
                  OutlinedButton.icon(
                    onPressed: () => _storeCredit(context, ref, 'redeem'),
                    icon: const Icon(Icons.payment, size: 18),
                    label: const Text('Redeem credit'),
                  ),
                  // A text to the number on the record (13.7). Marketing goes only with
                  // the customer's recorded SMS consent; the server refuses in words.
                  if (c.phone != null && c.phone!.trim().isNotEmpty)
                    OutlinedButton.icon(
                      key: const Key('customer-send-text'),
                      onPressed: () => showDialog<void>(
                        context: context,
                        builder: (_) => SendTextDialog(customer: c),
                      ),
                      icon: const Icon(Icons.sms_outlined, size: 18),
                      label: const Text('Send text'),
                    ),
                ],
              ),
              const SizedBox(height: 16),
              _AddressesSection(customerId: customer.id),
              const SizedBox(height: 16),
              // 18.9: a business buyer's registration decides whether its sales are
              // invoiced, and what the invoice names it as.
              VatRegistrationSection(customerId: customer.id),
              const SizedBox(height: 12),
              GuardianConsentSection(customerId: customer.id),
              const SizedBox(height: 12),
              CustomerMarketingSection(customerId: customer.id),
              const SizedBox(height: 16),
              Text('Loyalty ledger',
                  style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 8),
              ledgerAsync.when(
                loading: () => const Padding(
                    padding: EdgeInsets.all(16),
                    child: Center(child: CircularProgressIndicator())),
                error: (e, _) => Text(
                    friendlyError(e, fallback: 'Could not load ledger.'),
                    style: TextStyle(color: cs.error)),
                data: (entries) => entries.isEmpty
                    ? Text('No loyalty activity yet.',
                        style: TextStyle(color: cs.outline))
                    : Column(
                        children: [
                          for (final e in entries.take(20))
                            ListTile(
                              dense: true,
                              contentPadding: EdgeInsets.zero,
                              leading: Icon(
                                e.points >= 0
                                    ? Icons.arrow_upward
                                    : Icons.arrow_downward,
                                size: 16,
                                color: e.points >= 0
                                    ? context.status.success
                                    : cs.error,
                              ),
                              title: Text(_loyaltyEntryLabel(e.type)),
                              subtitle: _ledgerLine(e),
                              trailing: Text(
                                  '${e.points >= 0 ? '+' : ''}${e.points.toStringAsFixed(0)}'),
                            ),
                        ],
                      ),
              ),
            ],
          ),
        ),
      ),
      // Two groups: the data requests at the start, Close at the end. Dialog
      // actions sit in an OverflowBar, not a Row, so a Spacer can't go here
      // (it threw "Incorrect use of ParentDataWidget" when the dialog opened).
      actions: [
        Wrap(
          spacing: AppSpacing.sm,
          children: [
            TextButton(
              onPressed: () => _anonymize(context, ref),
              style: TextButton.styleFrom(foregroundColor: cs.error),
              child: const Text('Anonymize'),
            ),
            TextButton(
              onPressed: () => _export(context, ref),
              child: const Text('Export data'),
            ),
          ],
        ),
        TextButton(
            onPressed: () => Navigator.pop(context), child: const Text('Close')),
      ],
      actionsAlignment: MainAxisAlignment.spaceBetween,
    );
  }

  void _refresh(WidgetRef ref) {
    ref.invalidate(customerLoyaltyProvider(customer.id));
    ref.invalidate(customerLoyaltyLedgerProvider(customer.id));
    ref.invalidate(customerStoreCreditProvider(customer.id));
  }

  /// A subject access or portability request (UK GDPR art.15/art.20) that
  /// reached the shop by phone, letter or email rather than through the
  /// storefront's own "Download my data".
  ///
  /// The file is assembled across services and can fail — order-svc holds the
  /// purchases — and when it does, nothing is handed over: a partial answer to
  /// "everything you hold about me" is a wrong answer, not a short one.
  Future<void> _export(BuildContext context, WidgetRef ref) async {
    final messenger = ScaffoldMessenger.of(context);
    messenger.showSnackBar(
      const SnackBar(content: Text('Gathering this customer\'s data…')),
    );
    try {
      final resp = await ref
          .read(apiClientProvider)
          .dio
          .get('/${ApiConstants.customer}/customers/${customer.id}/export');
      final pretty =
          const JsonEncoder.withIndent('  ').convert(resp.data['data']);
      await Clipboard.setData(ClipboardData(text: pretty));
      if (!context.mounted) return;
      messenger.hideCurrentSnackBar();
      showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Customer data export'),
          content: SizedBox(
            width: 620,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                    'Copied to the clipboard. Machine-readable JSON, which is '
                    'what art.20 asks for.'),
                const SizedBox(height: 12),
                Flexible(
                  child: SingleChildScrollView(
                    child: SelectableText(pretty,
                        style: const TextStyle(
                            fontFamily: 'monospace', fontSize: 12)),
                  ),
                ),
              ],
            ),
          ),
          actions: [
            FilledButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('Close')),
          ],
        ),
      );
    } catch (e) {
      if (!context.mounted) return;
      messenger.hideCurrentSnackBar();
      messenger.showSnackBar(
        SnackBar(
          content: Text(friendlyError(e,
              fallback: 'The export could not be assembled. Nothing partial '
                  'has been produced — try again shortly.')),
        ),
      );
    }
  }

  /// GDPR erase (DELETE /customers/{id}) — irreversible, so confirm first.
  Future<void> _anonymize(BuildContext context, WidgetRef ref) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Anonymize customer?'),
        content: const Text(
            'This permanently erases the customer\'s personal details (GDPR). '
            'Order history is kept but de-identified. This cannot be undone.'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel')),
          FilledButton(
            style: FilledButton.styleFrom(
                backgroundColor: Theme.of(ctx).colorScheme.error),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('Anonymize'),
          ),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .delete('/${ApiConstants.customer}/customers/${customer.id}');
      ref.read(customersPaginationProvider.notifier).refresh();
      // A search showing is asked again too, so the change shows there.
      ref.read(customerSearchProvider.notifier).refresh();
      if (!context.mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Customer anonymized.')),
      );
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
            content: Text(friendlyError(e, fallback: 'Could not anonymize.'))),
      );
    }
  }

  Future<void> _points(BuildContext context, WidgetRef ref, String action) async {
    final res = await _amountReason(context,
        action == 'earn' ? 'Earn points' : 'Redeem points', 'Points');
    if (res == null) return;
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.customer}/customers/${customer.id}/loyalty/$action',
        data: {'points': res.amount, 'reason': res.reason},
      );
      _refresh(ref);
      if (!context.mounted) return;
      _toast(context, 'Points updated.');
    } catch (e) {
      if (!context.mounted) return;
      _toast(context, friendlyError(e, fallback: 'Could not update points.'),
          error: true);
    }
  }

  Future<void> _storeCredit(
      BuildContext context, WidgetRef ref, String action) async {
    final res = await _amountReason(context,
        action == 'issue' ? 'Issue store credit' : 'Redeem store credit', 'Amount');
    if (res == null) return;
    try {
      await ref.read(apiClientProvider).dio.post(
        '/${ApiConstants.customer}/customers/${customer.id}/store-credit/$action',
        data: {'amount': res.amount, 'reason': res.reason},
      );
      _refresh(ref);
      if (!context.mounted) return;
      _toast(context, 'Store credit updated.');
    } catch (e) {
      if (!context.mounted) return;
      _toast(
          context, friendlyError(e, fallback: 'Could not update store credit.'),
          error: true);
    }
  }

  void _toast(BuildContext context, String msg, {bool error = false}) {
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(msg),
      backgroundColor: error ? Theme.of(context).colorScheme.error : null,
    ));
  }
}

class _StatCard extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;
  final String sub;
  final Color color;
  final Color fg;
  const _StatCard({
    required this.icon,
    required this.label,
    required this.value,
    required this.sub,
    required this.color,
    required this.fg,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration:
          BoxDecoration(color: color, borderRadius: AppRadius.badge),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, color: fg),
          const SizedBox(height: 8),
          Text(label, style: TextStyle(color: fg, fontSize: 12)),
          Text(value,
              style: TextStyle(
                  color: fg, fontSize: 20, fontWeight: FontWeight.bold)),
          if (sub.isNotEmpty) Text(sub, style: TextStyle(color: fg, fontSize: 11)),
        ],
      ),
    );
  }
}

class _AmountReason {
  final double amount;
  final String reason;
  const _AmountReason(this.amount, this.reason);
}

Future<_AmountReason?> _amountReason(
    BuildContext context, String title, String amountLabel) {
  final amountCtrl = TextEditingController();
  final reasonCtrl = TextEditingController();
  return showDialog<_AmountReason>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: amountCtrl,
            autofocus: true,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: InputDecoration(labelText: amountLabel),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: reasonCtrl,
            decoration: const InputDecoration(labelText: 'Reason'),
          ),
        ],
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
        FilledButton(
          onPressed: () {
            final amt = double.tryParse(amountCtrl.text.trim());
            if (amt == null || amt <= 0) return;
            Navigator.pop(ctx, _AmountReason(amt, reasonCtrl.text.trim()));
          },
          child: const Text('Apply'),
        ),
      ],
    ),
  );
}

// ── Addresses ────────────────────────────────────────────────────────────────

class _AddressesSection extends ConsumerStatefulWidget {
  final String customerId;
  const _AddressesSection({required this.customerId});

  @override
  ConsumerState<_AddressesSection> createState() => _AddressesSectionState();
}

class _AddressesSectionState extends ConsumerState<_AddressesSection> {
  final Set<String> _deleting = {};
  String get customerId => widget.customerId;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final async = ref.watch(customerAddressesProvider(customerId));
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Text('Addresses', style: Theme.of(context).textTheme.labelLarge),
            const Spacer(),
            TextButton.icon(
              icon: const Icon(Icons.add_location_alt_outlined, size: 18),
              label: const Text('Add'),
              onPressed: () => showDialog(
                context: context,
                builder: (_) => _AddressFormDialog(customerId: customerId),
              ),
            ),
          ],
        ),
        async.when(
          loading: () => const Padding(
              padding: EdgeInsets.all(12),
              child: Center(child: CircularProgressIndicator())),
          error: (e, _) => Text(
              friendlyError(e, fallback: 'Could not load addresses.'),
              style: TextStyle(color: cs.error)),
          data: (addresses) => addresses.isEmpty
              ? Text('No addresses saved.', style: TextStyle(color: cs.outline))
              : Column(
                  children: [
                    for (final a in addresses)
                      ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(
                            a.type.toUpperCase() == 'WORK'
                                ? Icons.work_outline
                                : Icons.home_outlined,
                            size: 20),
                        title: Row(
                          children: [
                            Flexible(child: Text(_addressTypeLabel(a.type))),
                            if (a.isDefault) ...[
                              const SizedBox(width: 6),
                              _DefaultChip(),
                            ],
                          ],
                        ),
                        subtitle: Text(a.oneLine),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            IconButton(
                              visualDensity: VisualDensity.compact,
                              icon: const Icon(Icons.edit_outlined, size: 18),
                              tooltip: 'Edit address',
                              onPressed: () => showDialog(
                                context: context,
                                builder: (_) => _AddressFormDialog(
                                    customerId: customerId, address: a),
                              ),
                            ),
                            IconButton(
                              visualDensity: VisualDensity.compact,
                              icon: const Icon(Icons.delete_outline, size: 18),
                              tooltip: 'Delete address',
                              onPressed: _deleting.contains(a.id)
                                  ? null
                                  : () => _delete(a.id),
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
        ),
      ],
    );
  }

  Future<void> _delete(String addressId) async {
    if (_deleting.contains(addressId)) return;
    setState(() => _deleting.add(addressId));
    try {
      await ref.read(apiClientProvider).dio.delete(
          '/${ApiConstants.customer}/customers/$customerId/addresses/$addressId');
      ref.invalidate(customerAddressesProvider(customerId));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
            content:
                Text(friendlyError(e, fallback: 'Could not delete address.'))),
      );
    } finally {
      if (mounted) setState(() => _deleting.remove(addressId));
    }
  }
}

class _DefaultChip extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
      decoration: BoxDecoration(
          color: cs.secondaryContainer, borderRadius: AppRadius.chip),
      child: Text('Default',
          style: TextStyle(fontSize: 10, color: cs.onSecondaryContainer)),
    );
  }
}

/// Edit a customer's profile (email is immutable — registered identity).
class _EditCustomerDialog extends ConsumerStatefulWidget {
  final Customer customer;
  const _EditCustomerDialog({required this.customer});

  @override
  ConsumerState<_EditCustomerDialog> createState() =>
      _EditCustomerDialogState();
}

class _EditCustomerDialogState extends ConsumerState<_EditCustomerDialog> {
  final _formKey = GlobalKey<FormState>();
  late final _firstCtrl = TextEditingController(text: widget.customer.firstName);
  late final _lastCtrl = TextEditingController(text: widget.customer.lastName);
  late final _phoneCtrl = TextEditingController(text: widget.customer.phone ?? '');
  /// The date of birth as customer-svc keeps it (`1990-05-14`), or null. The
  /// field shows it as a date (*14 May 1990*); the request carries this.
  late String? _dob = (widget.customer.dob ?? '').trim().isEmpty ? null : widget.customer.dob!.trim();
  late final _dobCtrl =
      TextEditingController(text: _dob == null ? '' : AppFormat.date(_dob));
  late String? _gender = widget.customer.gender;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _firstCtrl.dispose();
    _lastCtrl.dispose();
    _phoneCtrl.dispose();
    _dobCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      await ref.read(apiClientProvider).dio.put(
        '/${ApiConstants.customer}/customers/${widget.customer.id}',
        data: {
          'firstName': _firstCtrl.text.trim(),
          'lastName': _lastCtrl.text.trim(),
          'phone': _phoneCtrl.text.trim(),
          'dob': ?_dob,
          if (_gender != null) 'gender': _gender,
        },
      );
      ref.invalidate(customerDetailProvider(widget.customer.id));
      ref.read(customersPaginationProvider.notifier).refresh();
      // A search showing is asked again too, so the change shows there.
      ref.read(customerSearchProvider.notifier).refresh();
      if (!mounted) return;
      Navigator.pop(context);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Customer updated.')),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not save customer.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Edit customer'),
      content: SizedBox(
        width: 380,
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (_error != null) ...[
                Text(_error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error)),
                const SizedBox(height: 8),
              ],
              TextFormField(
                controller: _firstCtrl,
                decoration: const InputDecoration(labelText: 'First name'),
                validator: (v) =>
                    (v == null || v.trim().isEmpty) ? 'Required' : null,
              ),
              TextFormField(
                controller: _lastCtrl,
                decoration: const InputDecoration(labelText: 'Last name'),
                validator: (v) =>
                    (v == null || v.trim().isEmpty) ? 'Required' : null,
              ),
              TextFormField(
                controller: _phoneCtrl,
                keyboardType: TextInputType.phone,
                decoration: const InputDecoration(labelText: 'Phone'),
              ),
              TextFormField(
                controller: _dobCtrl,
                readOnly: true,
                decoration: const InputDecoration(
                  labelText: 'Date of birth',
                  suffixIcon: Icon(Icons.calendar_today_outlined),
                ),
                onTap: () async {
                  final now = DateTime.now();
                  final picked = await showDatePicker(
                    context: context,
                    initialDate: DateTime.tryParse(_dob ?? '') ??
                        DateTime(now.year - 30),
                    firstDate: DateTime(1900),
                    lastDate: now,
                  );
                  if (picked != null) {
                    _dob = '${picked.year.toString().padLeft(4, '0')}-'
                        '${picked.month.toString().padLeft(2, '0')}-'
                        '${picked.day.toString().padLeft(2, '0')}';
                    _dobCtrl.text = AppFormat.date(_dob);
                  }
                },
              ),
              const SizedBox(height: 8),
              DropdownButtonFormField<String>(
                initialValue: _gender,
                decoration: const InputDecoration(labelText: 'Gender'),
                items: const [
                  DropdownMenuItem(value: 'MALE', child: Text('Male')),
                  DropdownMenuItem(value: 'FEMALE', child: Text('Female')),
                  DropdownMenuItem(value: 'OTHER', child: Text('Other')),
                ],
                onChanged: (v) => setState(() => _gender = v),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: _loading ? null : () => Navigator.pop(context),
            child: const Text('Cancel')),
        FilledButton(
          onPressed: _loading ? null : _save,
          child: _loading
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Save'),
        ),
      ],
    );
  }
}

/// Add or edit a customer address.
class _AddressFormDialog extends ConsumerStatefulWidget {
  final String customerId;
  final CustomerAddress? address;
  const _AddressFormDialog({required this.customerId, this.address});

  @override
  ConsumerState<_AddressFormDialog> createState() => _AddressFormDialogState();
}

class _AddressFormDialogState extends ConsumerState<_AddressFormDialog> {
  final _formKey = GlobalKey<FormState>();
  late String _type = widget.address?.type ?? 'HOME';
  late final _line1 = TextEditingController(text: widget.address?.line1 ?? '');
  late final _line2 = TextEditingController(text: widget.address?.line2 ?? '');
  late final _city = TextEditingController(text: widget.address?.city ?? '');
  late final _state = TextEditingController(text: widget.address?.state ?? '');
  late final _country = TextEditingController(text: widget.address?.country ?? '');
  late final _pincode = TextEditingController(text: widget.address?.pincode ?? '');
  late bool _isDefault = widget.address?.isDefault ?? false;
  bool _loading = false;
  String? _error;

  bool get _isEdit => widget.address != null;

  @override
  void dispose() {
    _line1.dispose();
    _line2.dispose();
    _city.dispose();
    _state.dispose();
    _country.dispose();
    _pincode.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _loading = true;
      _error = null;
    });
    final data = {
      'type': _type,
      'line1': _line1.text.trim(),
      'line2': _line2.text.trim(),
      'city': _city.text.trim(),
      'state': _state.text.trim(),
      'country': _country.text.trim(),
      'pincode': _pincode.text.trim(),
      'isDefault': _isDefault,
    };
    final base = '/${ApiConstants.customer}/customers/${widget.customerId}/addresses';
    try {
      final dio = ref.read(apiClientProvider).dio;
      if (_isEdit) {
        await dio.put('$base/${widget.address!.id}', data: data);
      } else {
        await dio.post(base, data: data);
      }
      ref.invalidate(customerAddressesProvider(widget.customerId));
      if (!mounted) return;
      Navigator.pop(context);
    } catch (e) {
      setState(() {
        _loading = false;
        _error = friendlyError(e, fallback: 'Could not save address.');
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text(_isEdit ? 'Edit address' : 'Add address'),
      content: SizedBox(
        width: 380,
        child: Form(
          key: _formKey,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (_error != null) ...[
                  Text(_error!,
                      style:
                          TextStyle(color: Theme.of(context).colorScheme.error)),
                  const SizedBox(height: 8),
                ],
                DropdownButtonFormField<String>(
                  initialValue: _type,
                  decoration: const InputDecoration(labelText: 'Type'),
                  items: const [
                    DropdownMenuItem(value: 'HOME', child: Text('Home')),
                    DropdownMenuItem(value: 'WORK', child: Text('Work')),
                    DropdownMenuItem(value: 'BILLING', child: Text('Billing')),
                    DropdownMenuItem(value: 'SHIPPING', child: Text('Shipping')),
                  ],
                  onChanged: (v) => setState(() => _type = v ?? 'HOME'),
                ),
                TextFormField(
                  controller: _line1,
                  decoration: const InputDecoration(labelText: 'Address line 1'),
                  validator: (v) =>
                      (v == null || v.trim().isEmpty) ? 'Required' : null,
                ),
                TextFormField(
                  controller: _line2,
                  decoration:
                      const InputDecoration(labelText: 'Address line 2'),
                ),
                TextFormField(
                  controller: _city,
                  decoration: const InputDecoration(labelText: 'City'),
                ),
                TextFormField(
                  controller: _state,
                  decoration: const InputDecoration(labelText: 'State / region'),
                ),
                TextFormField(
                  controller: _country,
                  decoration: const InputDecoration(labelText: 'Country'),
                  validator: (v) =>
                      (v == null || v.trim().isEmpty) ? 'Required' : null,
                ),
                TextFormField(
                  controller: _pincode,
                  decoration: const InputDecoration(labelText: 'Postcode / PIN'),
                ),
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Default address'),
                  value: _isDefault,
                  onChanged: (v) => setState(() => _isDefault = v),
                ),
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
            onPressed: _loading ? null : () => Navigator.pop(context),
            child: const Text('Cancel')),
        FilledButton(
          onPressed: _loading ? null : _save,
          child: _loading
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : Text(_isEdit ? 'Save' : 'Add'),
        ),
      ],
    );
  }
}
