import 'unit_price.dart';
import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../shared/util/slot_label.dart';
import '../../core/input_mode.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/storage/app_storage.dart';
import '../../shared/widgets/empty_state.dart';
import 'account_screen.dart' show MyCustomer, SavedAddress, myAddressesProvider, myCustomerProvider;
import 'cart_line.dart';
import 'delivery_slot_picker.dart';
import 'storefront_widgets.dart' show ProductImageThumb;
import 'order_summary.dart';
import 'storefront_providers.dart';
import 'storefront_shell.dart' show StorefrontAuthDialog;
import 'survey_widgets.dart';
import '../../shared/util/short_ref.dart';
import 'package:storeql_app/core/ids.dart';
import '../../core/theme.dart';

class StorefrontCartScreen extends ConsumerStatefulWidget {
  const StorefrontCartScreen({super.key});

  @override
  ConsumerState<StorefrontCartScreen> createState() =>
      _StorefrontCartScreenState();
}

class _StorefrontCartScreenState extends ConsumerState<StorefrontCartScreen> {
  bool _placing = false;
  // Re-entrancy guard distinct from [_placing]: set synchronously before the first await so a
  // double-tap can't fire two concurrent checkouts while still on the pending-order lookup (which
  // happens before [_placing] flips the button's loading spinner on).
  bool _checkoutInFlight = false;
  String _fulfilment = 'PICKUP'; // PICKUP | DELIVERY
  // Substitutions for out-of-stock online lines: the shopper's choice at checkout, on unless they
  // turn it off. A substitute is never charged more than the original and can be handed back.
  bool _allowSubstitutions = true;
  // Selected payment option key: CARD | UPI | WALLET (pay online now) or CASH (pay in person at
  // handover). '' = pay later with no declared method (only when the store disabled every online
  // tender). Which keys are offered comes from the store's enabledPaymentMethods config.
  String _payMethod = '';
  final _addressFormKey = GlobalKey<FormState>();
  final _line1Ctrl = TextEditingController();
  final _line2Ctrl = TextEditingController();
  final _cityCtrl = TextEditingController();
  final _postalCtrl = TextEditingController();
  final _recipientNameCtrl = TextEditingController();
  final _recipientPhoneCtrl = TextEditingController();
  final _contactPhoneCtrl = TextEditingController();

  /// The saved address the delivery form was last filled from, if any (12.10).
  String? _savedAddressId;

  // ── Delivery and collection slots ──────────────────────────────────────
  // The window the shopper chose, if any, and — for a delivery — the store
  // the typed postcode resolves to (tenant-svc's soft delivery-coverage
  // check), read fresh whenever the postcode changes so the picker always
  // asks the right store for its windows.
  SlotOption? _selectedSlot;
  String? _resolvedStoreId;
  String? _resolvedForPostcode;
  Timer? _resolveDebounce;

  static const _addressStorage = AppStorage();

  @override
  void initState() {
    super.initState();
    _postalCtrl.addListener(_scheduleResolveStore);
    _loadSavedAddress();
  }

  /// Prefill the delivery form with the address used on the previous order, so a repeat
  /// customer never retypes it (device-local; a server-side address book can replace this).
  Future<void> _loadSavedAddress() async {
    try {
      final raw = await _addressStorage.read(key: StorageKeys.sfSavedAddress);
      if (raw == null || raw.isEmpty || !mounted) return;
      final j = jsonDecode(raw) as Map<String, dynamic>;
      setState(() {
        if (_line1Ctrl.text.isEmpty) {
          _line1Ctrl.text = j['line1'] as String? ?? '';
        }
        if (_line2Ctrl.text.isEmpty) {
          _line2Ctrl.text = j['line2'] as String? ?? '';
        }
        if (_cityCtrl.text.isEmpty) _cityCtrl.text = j['city'] as String? ?? '';
        if (_postalCtrl.text.isEmpty) {
          _postalCtrl.text = j['postalCode'] as String? ?? '';
        }
        if (_recipientNameCtrl.text.isEmpty) {
          _recipientNameCtrl.text = j['recipientName'] as String? ?? '';
        }
        if (_recipientPhoneCtrl.text.isEmpty) {
          _recipientPhoneCtrl.text = j['recipientPhone'] as String? ?? '';
        }
        if (_contactPhoneCtrl.text.isEmpty) {
          _contactPhoneCtrl.text = j['recipientPhone'] as String? ?? '';
        }
      });
    } catch (_) {
      // Corrupt saved address is non-fatal — start with an empty form.
    }
  }

  Future<void> _saveAddress() async {
    try {
      await _addressStorage.write(
        key: StorageKeys.sfSavedAddress,
        value: jsonEncode({
          'line1': _line1Ctrl.text.trim(),
          'line2': _line2Ctrl.text.trim(),
          'city': _cityCtrl.text.trim(),
          'postalCode': _postalCtrl.text.trim(),
          'recipientName': _recipientNameCtrl.text.trim(),
          'recipientPhone': _recipientPhoneCtrl.text.trim(),
        }),
      );
    } catch (_) {
      // Best effort only.
    }
  }

  /// Fills the delivery form from one of the shopper's saved addresses (12.10). The recipient
  /// name and phone come from the shop's record of them when the form has none yet.
  void _useSavedAddress(SavedAddress a, MyCustomer? me) {
    setState(() {
      _savedAddressId = a.id;
      _line1Ctrl.text = a.line1;
      _line2Ctrl.text = a.line2 ?? '';
      _cityCtrl.text = a.city ?? '';
      _postalCtrl.text = a.pincode ?? '';
      if (me != null) {
        if (_recipientNameCtrl.text.trim().isEmpty) _recipientNameCtrl.text = me.fullName;
        if (_recipientPhoneCtrl.text.trim().isEmpty && me.phone != null) {
          _recipientPhoneCtrl.text = me.phone!;
        }
      }
    });
  }

  @override
  void dispose() {
    _resolveDebounce?.cancel();
    _postalCtrl.removeListener(_scheduleResolveStore);
    _line1Ctrl.dispose();
    _line2Ctrl.dispose();
    _cityCtrl.dispose();
    _postalCtrl.dispose();
    _recipientNameCtrl.dispose();
    _recipientPhoneCtrl.dispose();
    _contactPhoneCtrl.dispose();
    super.dispose();
  }

  /// The postcode changed (typed, or a saved address filled it): re-resolve
  /// which store would fulfil a delivery there, debounced so a shopper still
  /// typing does not fire a request per keystroke. A blank postcode clears
  /// the last resolution at once — nothing to resolve.
  void _scheduleResolveStore() {
    _resolveDebounce?.cancel();
    final pincode = _postalCtrl.text.trim();
    if (pincode.isEmpty) {
      if (_resolvedStoreId != null || _resolvedForPostcode != null) {
        setState(() {
          _resolvedStoreId = null;
          _resolvedForPostcode = null;
          _selectedSlot = null;
        });
      }
      return;
    }
    if (pincode == _resolvedForPostcode) return;
    _resolveDebounce = Timer(const Duration(milliseconds: 500), () => _resolveStore(pincode));
  }

  /// Soft delivery-coverage check (tenant-svc `/fulfilment/resolve`), for the
  /// slot picker's benefit only: the order's own placement resolves it again,
  /// authoritatively. An unreadable postcode (not yet covered, no network)
  /// leaves the picker unmounted rather than blocking anything here.
  Future<void> _resolveStore(String pincode) async {
    try {
      final dio = ref.read(storefrontDioProvider);
      final resp = await dio.get('/${ApiConstants.tenant}/fulfilment/resolve',
          queryParameters: {'pincode': pincode});
      final data = resp.data['data'] as Map<String, dynamic>;
      if (!mounted) return;
      setState(() {
        _resolvedForPostcode = pincode;
        _resolvedStoreId = data['storeId'] as String?;
        _selectedSlot = null;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _resolvedForPostcode = pincode;
        _resolvedStoreId = null;
        _selectedSlot = null;
      });
    }
  }

  static String? _requiredField(String? v) =>
      v == null || v.trim().isEmpty ? 'Required' : null;

  /// The payment choices this store offers for the current fulfilment. Pay-now (online capture)
  /// options exist only in priced shops; CASH is settled in person at handover, so it reads
  /// "Cash on delivery" / "Cash at pickup". A store that disabled every applicable tender still
  /// gets a generic pay-later option so checkout never dead-ends.
  static List<_PayOption> _payOptions(
      bool showPrices, List<String> enabled, bool delivery) {
    final opts = <_PayOption>[
      if (showPrices && enabled.contains('CARD'))
        const _PayOption('CARD', true, 'Card', Icons.credit_card),
      if (showPrices && enabled.contains('UPI'))
        const _PayOption('UPI', true, 'UPI', Icons.qr_code_2),
      if (showPrices && enabled.contains('WALLET'))
        const _PayOption(
            'WALLET', true, 'Wallet', Icons.account_balance_wallet_outlined),
      if (enabled.contains('CASH'))
        _PayOption('CASH', false,
            delivery ? 'Cash on delivery' : 'Cash at pickup',
            Icons.payments_outlined),
    ];
    if (opts.isEmpty) {
      opts.add(_PayOption('', false,
          delivery ? 'Pay on delivery' : 'Pay at pickup',
          Icons.schedule_outlined));
    }
    return opts;
  }

  _PayOption _selectedOption(List<_PayOption> options) =>
      options.firstWhere((o) => o.method == _payMethod,
          orElse: () => options.first);

  @override
  Widget build(BuildContext context) {
    final cart = ref.watch(cartProvider);
    final notifier = ref.read(cartProvider.notifier);
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    final showPrices = ref.watch(storefrontShowPricesProvider);
    final configAsync = ref.watch(storefrontConfigProvider);
    // A dark store sells delivery-only (ship-from-store and dark-store picking): no collection
    // is offered there, so the choice is not shown and the checkout is a delivery.
    final pickupOffered = configAsync.value?.pickupOffered ?? true;
    if (!pickupOffered && _fulfilment != 'DELIVERY') {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) setState(() => _fulfilment = 'DELIVERY');
      });
    }
    final storeName = configAsync.value?.storeName ?? '-';
    final scheme = configAsync.value?.depositScheme;
    final currency = cart.isNotEmpty ? cart.first.currency : '';
    final total = cart.fold<double>(0, (s, l) => s + l.lineTotal);
    final enabledMethods = ref.watch(storefrontPaymentMethodsProvider);
    // Default first, so the address the shopper marked is the one offered at the top.
    final savedAddresses = [...(ref.watch(myAddressesProvider).value ?? const <SavedAddress>[])]
      ..sort((a, b) => (b.isDefault ? 1 : 0) - (a.isDefault ? 1 : 0));
    // Watched, not read at the moment of a pick, so the record is loaded by the time it is needed.
    final me = ref.watch(myCustomerProvider).value;
    final payOptions =
        _payOptions(showPrices, enabledMethods, _fulfilment == 'DELIVERY');
    final selectedPay = _selectedOption(payOptions);

    // Delivery and collection slots: for a collection, the store the shopper is
    // browsing (chosen at the top of the storefront); for a delivery, the store
    // the typed postcode resolves to — unknown until it does, in which case the
    // picker simply is not mounted yet.
    final currentStoreId = ref.watch(storefrontStoreProvider);
    final resolvedPickerStoreId =
        _fulfilment == 'DELIVERY' ? _resolvedStoreId : currentStoreId;
    final pickerStoreId = (resolvedPickerStoreId != null && resolvedPickerStoreId.isNotEmpty)
        ? resolvedPickerStoreId
        : null;
    final slotsOffered = pickerStoreId == null
        ? false
        : ref
                .watch(fulfilmentSlotsProvider((store: pickerStoreId, type: _fulfilment)))
                .value
                ?.offered ??
            false;
    final slotMissing = slotsOffered && _selectedSlot == null;

    if (cart.isEmpty) {
      return EmptyState(
        icon: Icons.shopping_bag_outlined,
        title: 'Your cart is empty',
        action: OutlinedButton.icon(
          onPressed: () => context.go('/store/products'),
          icon: const Icon(Icons.storefront),
          label: const Text('Browse products'),
        ),
      );
    }

    final gutter = context.pageGutter;
    final bottomInset = MediaQuery.paddingOf(context).bottom;
    final itemCount = cart.fold<int>(0, (s, l) => s + l.qty);
    final totalText = AppFormat.money(total, currencyCode: currency);
    // Swiped away on a touch screen; a mouse uses the bin the minus becomes at one.
    final swipe = !pointerFirst;
    // Every line in one card, the dividers inset past the thumbnails.
    final lines = Card(
      child: Column(
        children: [
          for (var i = 0; i < cart.length; i++) ...[
            if (i > 0)
              const Divider(height: 1, indent: CartLineTile.dividerIndent),
            _lineTile(cart[i], i,
                showPrices: showPrices, notifier: notifier, swipe: swipe),
          ],
        ],
      ),
    );
    final depositNote = scheme == null
        ? null
        : Text(
            key: const Key('deposit-note'),
            'Drinks in ${scheme.inWords} carry a refundable deposit of '
            '${AppFormat.money(scheme.depositEach, currencyCode: scheme.currency)} '
            'each, added to the order as its own line. It is paid back '
            'when the empty container is returned.',
            style: theme.textTheme.bodySmall,
          );
    final reviewButton = FilledButton.icon(
      key: const Key('review-order-button'),
      onPressed: (_placing || slotMissing) ? null : _checkout,
      icon: _placing
          ? SizedBox(
              height: 18,
              width: 18,
              child: CircularProgressIndicator(strokeWidth: 2, color: cs.onPrimary))
          : Icon(selectedPay.payNow ? Icons.lock_outline : Icons.receipt_long),
      label: Text(_placing
          ? (selectedPay.payNow ? 'Processing payment…' : 'Placing order…')
          : 'Review order'),
    );

    // The amount in the currency the shopper chose to see prices in (03.x);
    // paid in the shop's own.
    final shownNote = Consumer(builder: (context, ref, _) {
      final shownIn = ref.watch(displayCurrencyProvider);
      final shop = ref.watch(storefrontCurrenciesProvider).value;
      final shown =
          shownIn == null || shop == null ? null : shop.shown(total, shownIn);
      if (shown == null || shownIn == currency) return const SizedBox.shrink();
      return Text(
        '≈ ${AppFormat.money(shown, currencyCode: shownIn)} at the shop\'s rate; '
        'you pay in $currency',
        key: const Key('cart-total-shown'),
        textAlign: TextAlign.end,
        style: theme.textTheme.bodySmall,
      );
    });

    // The price breakdown and the one action. [withAction]: the button ends
    // it; on a phone it sits in the bar under the scroll instead.
    Widget summary({required bool withAction}) => OrderSummary(
          itemCount: itemCount,
          subtotal: showPrices ? totalText : null,
          total: showPrices ? totalText : null,
          notes: [
            if (showPrices) shownNote,
            if (showPrices && depositNote != null) depositNote,
          ],
          action: withAction ? reviewButton : null,
        );

    // How the order reaches the shopper and how it is paid.
    Widget checkout() => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisSize: MainAxisSize.min,
          children: [
            // A dark store sells delivery-only (ship-from-store and dark-store picking):
            // there is no choice to make there, so it says so instead.
            if (pickupOffered)
              // As wide as the panel, so the two choices read as one control.
              SizedBox(
                width: double.infinity,
                child: SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(
                        value: 'PICKUP',
                        label: Text('Collect from store'),
                        icon: Icon(Icons.storefront_outlined)),
                    ButtonSegment(
                        value: 'DELIVERY',
                        label: Text('Deliver to home'),
                        icon: Icon(Icons.local_shipping_outlined)),
                  ],
                  selected: {_fulfilment},
                  onSelectionChanged: (s) => setState(() {
                    _fulfilment = s.first;
                    // A different fulfilment type offers a different store's
                    // windows (or none) — last time's choice does not carry over.
                    _selectedSlot = null;
                  }),
                ),
              )
            else
              Row(
                key: const Key('delivery-only'),
                children: [
                  Icon(Icons.local_shipping_outlined,
                      size: 18, color: cs.onSurfaceVariant),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(
                    child: Text('Delivery only from this shop',
                        style: theme.textTheme.bodyMedium
                            ?.copyWith(color: cs.onSurfaceVariant)),
                  ),
                ],
              ),
            const SizedBox(height: AppSpacing.xs),
            // Substitutions for out-of-stock online lines: on unless the shopper turns them off.
            SwitchListTile.adaptive(
              key: const Key('allow-substitutions'),
              contentPadding: EdgeInsets.zero,
              title: const Text('Allow substitutions'),
              subtitle: const Text(
                  'If something is out of stock, the shop may pack a similar item. '
                  'You never pay more, and you can hand it back for a refund.'),
              value: _allowSubstitutions,
              onChanged: (v) => setState(() => _allowSubstitutions = v),
            ),
            if (_fulfilment == 'DELIVERY') ...[
              const SizedBox(height: 12),
              // The shopper's address book at this shop, when they keep one (12.10).
              // Picking one fills the form; the form stays editable afterwards.
              if (savedAddresses.isNotEmpty) ...[
                DropdownButtonFormField<String?>(
                  key: const Key('cart-saved-address'),
                  isExpanded: true,
                  initialValue: _savedAddressId,
                  decoration: const InputDecoration(
                      labelText: 'Use a saved address', isDense: true),
                  items: [
                    const DropdownMenuItem<String?>(
                        value: null, child: Text('Type an address')),
                    for (final a in savedAddresses)
                      DropdownMenuItem<String?>(
                          value: a.id,
                          child: Text(
                              '${a.oneLine}${a.isDefault ? ' (default)' : ''}',
                              overflow: TextOverflow.ellipsis)),
                  ],
                  onChanged: (id) {
                    if (id == null) {
                      setState(() => _savedAddressId = null);
                      return;
                    }
                    _useSavedAddress(savedAddresses.firstWhere((a) => a.id == id), me);
                  },
                ),
                const SizedBox(height: 12),
              ],
              Form(
                key: _addressFormKey,
                child: Column(
                  children: [
                    TextFormField(
                      controller: _line1Ctrl,
                      decoration: const InputDecoration(
                          labelText: 'Address line 1',
                          isDense: true),
                      validator: _requiredField,
                    ),
                    const SizedBox(height: 8),
                    TextFormField(
                      controller: _line2Ctrl,
                      decoration: const InputDecoration(
                          labelText: 'Address line 2 (optional)',
                          isDense: true),
                    ),
                    const SizedBox(height: 8),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(
                          child: TextFormField(
                            controller: _cityCtrl,
                            decoration: const InputDecoration(
                                labelText: 'City', isDense: true),
                            validator: _requiredField,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: TextFormField(
                            controller: _postalCtrl,
                            decoration: const InputDecoration(
                                labelText: 'Postal code', isDense: true),
                            validator: _requiredField,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    TextFormField(
                      controller: _recipientNameCtrl,
                      decoration: const InputDecoration(
                          labelText: 'Recipient name', isDense: true),
                      validator: _requiredField,
                    ),
                    const SizedBox(height: 8),
                    TextFormField(
                      controller: _recipientPhoneCtrl,
                      decoration: const InputDecoration(
                          labelText: 'Recipient phone', isDense: true),
                      keyboardType: TextInputType.phone,
                      validator: _requiredField,
                    ),
                  ],
                ),
              ),
            ],
            if (_fulfilment == 'PICKUP') ...[
              const SizedBox(height: 12),
              TextField(
                controller: _contactPhoneCtrl,
                keyboardType: TextInputType.phone,
                decoration: const InputDecoration(
                  labelText: 'Contact phone *',
                  hintText: 'We\'ll notify you when your order is ready',
                  isDense: true,
                  prefixIcon: Icon(Icons.phone_outlined),
                ),
              ),
            ],
            if (pickerStoreId != null) ...[
              const SizedBox(height: 12),
              DeliverySlotPicker(
                key: ValueKey('slot-picker-$pickerStoreId-$_fulfilment'),
                storeId: pickerStoreId,
                fulfilmentType: _fulfilment,
                selected: _selectedSlot,
                onSelected: (s) => setState(() => _selectedSlot = s),
              ),
            ],
            const SizedBox(height: 12),
            Text('Payment', style: theme.textTheme.titleSmall),
            const SizedBox(height: 6),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: [
                for (final o in payOptions)
                  ChoiceChip(
                    avatar: Icon(o.icon,
                        size: 16,
                        color: selectedPay.method == o.method
                            ? cs.onSecondaryContainer
                            : cs.onSurfaceVariant),
                    label: Text(
                        o.payNow ? '${o.label} · pay now' : o.label),
                    selected: selectedPay.method == o.method,
                    onSelected: (_) =>
                        setState(() => _payMethod = o.method),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            Container(
              padding:
                  const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: cs.secondaryContainer,
                borderRadius: AppRadius.chip,
              ),
              child: Row(
                children: [
                  Icon(
                      _fulfilment == 'DELIVERY'
                          ? Icons.local_shipping_outlined
                          : Icons.storefront_outlined,
                      size: 18,
                      color: cs.onSecondaryContainer),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _fulfilmentBannerText(
                          showPrices, storeName, currency, total, selectedPay),
                      style: TextStyle(
                          color: cs.onSecondaryContainer, fontSize: 12),
                    ),
                  ),
                ],
              ),
            ),
          ],
        );

    return LayoutBuilder(builder: (context, constraints) {
      final windowClass = AppBreakpoints.classOf(constraints.maxWidth);
      if (windowClass >= WindowClass.expanded) {
        // Two columns: the items on the start side, checkout and the summary
        // with its button beside them.
        final available = constraints.maxWidth < AppBreakpoints.contentMaxWidth
            ? constraints.maxWidth
            : AppBreakpoints.contentMaxWidth;
        final half = (available - 2 * gutter - AppSpacing.xl) / 2;
        return ContentBounds(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: SingleChildScrollView(
                  padding: EdgeInsetsDirectional.fromSTEB(
                      gutter, gutter, 0, gutter + bottomInset),
                  child: lines,
                ),
              ),
              const SizedBox(width: AppSpacing.xl),
              SizedBox(
                width: half > 420 ? 420.0 : half,
                child: SingleChildScrollView(
                  padding: EdgeInsetsDirectional.fromSTEB(
                      0, gutter, gutter, gutter + bottomInset),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Card(
                        child: Padding(
                          padding: AppSpacing.cardPadding,
                          child: checkout(),
                        ),
                      ),
                      const SizedBox(height: AppSpacing.lg),
                      summary(withAction: true),
                    ],
                  ),
                ),
              ),
            ],
          ),
        );
      }

      // One column and one scroll: the items, checkout, then the summary — no
      // panel that squeezes the list. A form's width at most.
      final compact = windowClass == WindowClass.compact;
      final scroll = SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(gutter, AppSpacing.sm, gutter,
            gutter + (compact ? 0 : bottomInset)),
        child: ContentBounds.form(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              lines,
              const SizedBox(height: AppSpacing.xl),
              checkout(),
              const SizedBox(height: AppSpacing.xl),
              summary(withAction: !compact),
            ],
          ),
        ),
      );
      if (!compact) return scroll;

      // A phone: the total and the button stay in view under the scroll.
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(child: scroll),
          OrderSummaryBar(
            itemCount: itemCount,
            total: showPrices ? totalText : null,
            action: reviewButton,
          ),
        ],
      );
    });
  }

  /// One cart line, stepped by the amber stepper; at one its minus takes the
  /// line out, as a swipe does on a touch screen, with *Undo* offered.
  Widget _lineTile(CartLine l, int index,
          {required bool showPrices,
          required CartNotifier notifier,
          required bool swipe}) =>
      CartLineTile(
        key: ValueKey(l.variantId),
        id: l.variantId,
        // The product's own picture, as the shop shows it; a line added before
        // lines knew their product keeps initials seeded by its name.
        image: l.productId == null
            ? null
            : ProductImageThumb(
                productId: l.productId!,
                label: l.productName,
                fontSize: 16,
                borderRadius: AppRadius.chip,
              ),
        thumbSeed: l.productId ?? l.productName,
        name: l.productName,
        detail: showPrices
            ? '${l.sku}  ·  ${AppFormat.money(l.unitPrice, currencyCode: l.currency)}'
            : l.sku,
        extra: showPrices ? CartLineUnitPrice(variantId: l.variantId) : null,
        qty: l.qty,
        onInc: () => notifier.setQty(l.variantId, l.qty + 1),
        onDec: () => notifier.setQty(l.variantId, l.qty - 1),
        onRemove: () => _removeLine(l, index),
        lineTotal: showPrices
            ? AppFormat.money(l.lineTotal, currencyCode: l.currency)
            : null,
        swipeToRemove: swipe,
      );

  /// Takes [line] out of the cart and offers it back where it was.
  void _removeLine(CartLine line, int index) {
    final notifier = ref.read(cartProvider.notifier);
    notifier.remove(line.variantId);
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(SnackBar(
        content: Text('Removed ${line.productName}'),
        duration: const Duration(seconds: 5),
        // Waits for a screen-reader user to reach it; goes by itself otherwise.
        persist: MediaQuery.accessibleNavigationOf(context),
        action: SnackBarAction(
          label: 'Undo',
          onPressed: () => _restoreLine(notifier, line, index),
        ),
      ));
  }

  /// Puts a removed line back at [index], unless it is in the cart again.
  void _restoreLine(CartNotifier notifier, CartLine line, int index) =>
      notifier.insert(index, line);

  /// The checkout's fulfilment/payment summary line. Three independent axes: fulfilment
  /// (pickup/delivery), whether a price is known (showPrices), and the selected payment option —
  /// catalog-mode stores have no known price, so pay-now options are never offered there.
  String _fulfilmentBannerText(bool showPrices, String storeName,
      String currency, double total, _PayOption pay) {
    final where =
        _fulfilment == 'DELIVERY' ? 'Deliver to your address' : 'Collect from $storeName';
    if (pay.payNow) return '$where · paid online by ${pay.label.toLowerCase()}';
    if (!showPrices) {
      return _fulfilment == 'DELIVERY'
          ? '$where · price & payment confirmed on delivery'
          : '$where · price & payment confirmed in store';
    }
    final amount = AppFormat.money(total, currencyCode: currency);
    return '$where · ${pay.label.toLowerCase()}: $amount';
  }

  Future<void> _checkout() async {
    final cart = ref.read(cartProvider);
    if (cart.isEmpty) return;

    // Order placement requires a signed-in customer so the store has at least a
    // phone number on file (mandatory for pay-later follow-up / delivery contact).
    if (!ref.read(storefrontAuthProvider).isSignedIn) {
      await showDialog<void>(
        context: context,
        builder: (_) => const StorefrontAuthDialog(),
      );
      if (!mounted || !ref.read(storefrontAuthProvider).isSignedIn) return;
    }

    final delivery = _fulfilment == 'DELIVERY';
    if (delivery && !(_addressFormKey.currentState?.validate() ?? false)) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Please fill in all delivery address fields.'),
      ));
      return;
    }
    if (!delivery && _contactPhoneCtrl.text.trim().isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
        content: Text('Please enter a contact phone number for collection.'),
      ));
      return;
    }
    // Re-entrancy guard, set synchronously before the first await: a double-tap landing while
    // this call is still on the pending-order lookup below must not fire a second checkout. This
    // is deliberately separate from [_placing] (which only flips once we commit to placing the
    // order) so the button doesn't show a loading spinner for the whole pending-order-dialog
    // detour — it just silently ignores the extra tap.
    if (_checkoutInFlight) return;
    _checkoutInFlight = true;
    try {
      await _doCheckout(cart, delivery);
    } finally {
      _checkoutInFlight = false;
    }
  }

  /// Order review sheet — the last look before money/stock moves. Returns true on confirm.
  Future<bool> _confirmReviewSheet(
      List<CartLine> cart, bool delivery, _PayOption pay) async {
    final showPrices = ref.read(storefrontShowPricesProvider);
    final storeName = ref.read(storefrontConfigProvider).value?.storeName ?? '-';
    final currency = cart.first.currency;
    final total = cart.fold<double>(0, (s, l) => s + l.lineTotal);
    final totalText = AppFormat.money(total, currencyCode: currency);
    final itemCount = cart.fold<int>(0, (s, l) => s + l.qty);
    final confirmed = await showModalBottomSheet<bool>(
      context: context,
      showDragHandle: true,
      builder: (ctx) {
        final cs = Theme.of(ctx).colorScheme;
        Widget row(IconData icon, String label, String value) => Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(icon, size: 18, color: cs.onSurfaceVariant),
                  const SizedBox(width: 10),
                  SizedBox(
                      width: 90,
                      child: Text(label,
                          style: TextStyle(color: cs.onSurfaceVariant))),
                  Expanded(
                      child: Text(value,
                          style:
                              const TextStyle(fontWeight: FontWeight.w600))),
                ],
              ),
            );
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Review your order',
                    style: Theme.of(ctx).textTheme.titleLarge),
                const SizedBox(height: 12),
                row(Icons.shopping_bag_outlined, 'Items',
                    '$itemCount item${itemCount == 1 ? '' : 's'}'),
                if (showPrices)
                  row(Icons.receipt_long_outlined, 'Total',
                      '$totalText (incl. VAT)'),
                row(
                    delivery
                        ? Icons.local_shipping_outlined
                        : Icons.storefront_outlined,
                    delivery ? 'Deliver to' : 'Collect at',
                    delivery
                        ? '${_line1Ctrl.text.trim()}, ${_cityCtrl.text.trim()} ${_postalCtrl.text.trim()}\n${_recipientNameCtrl.text.trim()} · ${_recipientPhoneCtrl.text.trim()}'
                        : '$storeName\nWe\'ll call ${_contactPhoneCtrl.text.trim()} when it\'s ready'),
                // The chosen delivery/collection window (delivery-and-collection-slots),
                // the last look before the order holds it.
                if (_selectedSlot != null)
                  row(
                      Icons.schedule_outlined,
                      delivery ? 'Delivery window' : 'Collection window',
                      slotWhen(
                          date: _selectedSlot!.date,
                          startTime: _selectedSlot!.startTime,
                          endTime: _selectedSlot!.endTime)),
                row(pay.icon, 'Payment',
                    pay.payNow ? '${pay.label} — charged now' : pay.label),
                const SizedBox(height: 16),
                FilledButton.icon(
                  onPressed: () => Navigator.pop(ctx, true),
                  icon: Icon(pay.payNow ? Icons.lock_outline : Icons.check),
                  label: Text(pay.payNow && showPrices
                      ? 'Pay $totalText'
                      : 'Place order'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(ctx, false),
                  child: const Text('Back to cart'),
                ),
              ],
            ),
          ),
        );
      },
    );
    return confirmed == true;
  }

  Future<void> _doCheckout(List<CartLine> cart, bool delivery) async {
    // Guard: if the customer already has a pending order, ask before firing another.
    final pendingOrder = await _findPendingOrder();
    if (!mounted) return;
    if (pendingOrder != null) {
      final action = await _showPendingOrderDialog(pendingOrder);
      if (!mounted) return;
      if (action == 'update') {
        // Navigate to the orders screen so the customer can review/contact the store.
        // When order-svc exposes a PATCH /orders/{id}/items endpoint this becomes
        // a direct edit flow instead.
        context.go('/store/orders');
        return;
      } else if (action != 'new') {
        // null = dialog dismissed / cancelled — do nothing
        return;
      }
      // action == 'new' → fall through and place a second order
    }

    final showPrices = ref.read(storefrontShowPricesProvider);
    final storeName = ref.read(storefrontConfigProvider).value?.storeName ?? '-';
    // Catalog mode (store hides prices) has no known price to charge online, so pay-now options
    // are never offered there; priced shops offer the store's enabled online tenders plus cash
    // at handover.
    final enabledMethods = ref.read(storefrontPaymentMethodsProvider);
    final pay = _selectedOption(_payOptions(showPrices, enabledMethods, delivery));
    final payNow = pay.payNow;

    // Last look before anything is committed: review sheet with items, destination and payment.
    if (!await _confirmReviewSheet(cart, delivery, pay)) return;
    if (!mounted) return;

    final dio = ref.read(storefrontDioProvider);
    final storeId = ref.read(storefrontStoreProvider);
    // In catalog mode, CartLine.currency is '' (no price was ever fetched). The
    // currency is then left out and order-svc stamps the tenant's own; guessing
    // one here once stamped pounds on every catalog-mode order (SJ-D53).
    final currency = cart.first.currency;
    final cartTotal = cart.fold<double>(0, (s, l) => s + l.lineTotal);
    final idemBase = newId();
    setState(() => _placing = true);
    try {
      // 1. Place the order (created PENDING). In catalog mode we send no client price — the
      // server resolves it (when pricing enforcement is on).
      final resp = await dio.post(
        '/${ApiConstants.order}/orders',
        data: {
          'storeId': storeId,
          'channel': 'ONLINE',
          'fulfilmentType': _fulfilment,
          'allowSubstitutions': _allowSubstitutions,
          if (currency.isNotEmpty) 'currency': currency,
          'items': [
            for (final l in cart)
              // In catalog mode unitPrice is 0 (no price was ever fetched); the
              // server prices the order when pricing enforcement is on, otherwise
              // it's recorded as a 0-value request to be priced/fulfilled later.
              {'variantId': l.variantId, 'qty': l.qty, 'unitPrice': l.unitPrice},
          ],
          // The chosen delivery/collection window (delivery-and-collection-slots):
          // sent only when the store offered one and the shopper picked it — a
          // store with no windows checks out exactly as before.
          if (_selectedSlot != null) ...{
            'slotWindowId': _selectedSlot!.windowId,
            'slotStartsAt': _selectedSlot!.startsAt.toUtc().toIso8601String(),
          },
          'contactPhone': delivery
              ? _recipientPhoneCtrl.text.trim()
              : _contactPhoneCtrl.text.trim(),
          // The customer's declared tender (CASH = settle in person at handover). Omitted when
          // the store disabled every applicable method and checkout fell back to generic
          // pay-later.
          if (pay.method.isNotEmpty) 'paymentMethod': pay.method,
          if (delivery) ...{
            'deliveryLine1': _line1Ctrl.text.trim(),
            if (_line2Ctrl.text.trim().isNotEmpty)
              'deliveryLine2': _line2Ctrl.text.trim(),
            'deliveryCity': _cityCtrl.text.trim(),
            'deliveryPostalCode': _postalCtrl.text.trim(),
            'deliveryRecipientName': _recipientNameCtrl.text.trim(),
            'deliveryRecipientPhone': _recipientPhoneCtrl.text.trim(),
          },
        },
        options: Options(headers: {'Idempotency-Key': derivedId(idemBase, 'order')}),
      );
      final data = resp.data['data'] as Map<String, dynamic>;
      final orderId = data['id'] as String? ?? '';
      final total = (data['total'] as num?)?.toDouble() ?? cartTotal;
      // 09.16: the server put the return-scheme deposit on the order as its own
      // line; the shopper sees it and pays the total that carries it.
      final depositAmount = (data['depositAmount'] as num?)?.toDouble() ?? 0;
      // The window this order holds (delivery-and-collection-slots), for the
      // confirmation and the on-device order history; null for an order with
      // none, and never a price — the window carries no fee in this cut.
      final orderSlot = OrderSlot.maybe(data['slot']);
      // Order orchestration: a delivery the shop serving the postcode cannot fill alone comes in
      // parts from several shops — one checkout, paid once for all of them.
      final group = data['group'] as Map<String, dynamic>?;
      final parts = [
        for (final p in (group?['parts'] as List?) ?? const [])
          CheckoutPart.fromJson(p as Map<String, dynamic>),
      ];
      final split = parts.length > 1;
      final payTotal =
          split ? (group!['total'] as num?)?.toDouble() ?? total : total;
      // What the shopper pays: the whole checkout's total when it comes in parts.
      final totalText = AppFormat.money(payTotal, currencyCode: currency);
      final depositText = AppFormat.money(depositAmount, currencyCode: currency);
      final storeNames =
          split ? await _storeNames() : const <String, String>{};
      if (split && payNow) {
        if (!mounted) return;
        // No spinner behind the sheet: the shopper is deciding, nothing is in flight.
        setState(() => _placing = false);
        final go = await _confirmSplitSheet(parts, storeNames,
            group!['currency'] as String? ?? currency, payTotal);
        if (!mounted) return;
        if (!go) {
          ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
            content: Text('Nothing was charged. Your order is held for a '
                'short while and released if it is not paid.'),
          ));
          return;
        }
        setState(() => _placing = true);
      }

      // 2. "Pay now" captures payment online immediately (capture → PaymentCaptured → order
      // confirms). "Pay later" — catalog mode, or a priced shop's customer choosing to defer —
      // skips payment-svc entirely; the order is a request, priced/paid at pickup or delivery.
      if (payNow) {
        await dio.post(
          '/${ApiConstants.payment}/payments/online',
          // A split checkout is paid once, for all its parts.
          data: split
              ? {'groupId': group!['id'], 'amount': payTotal, 'method': pay.method}
              : {
                  'orderId': orderId,
                  'amount': total,
                  'method': pay.method,
                  'storeId': storeId,
                },
          options: Options(headers: {'Idempotency-Key': derivedId(idemBase, 'pay')}),
        );
      }

      // Remember the delivery address so the next checkout is prefilled.
      if (delivery) await _saveAddress();

      // Remember this order on-device so it shows in "My orders" (guest fallback).
      if (split) {
        for (final p in parts) {
          await ref.read(storefrontOrdersProvider.notifier).add(
                StorefrontOrderRecord(
                  orderId: p.orderId,
                  total: showPrices ? p.total : 0,
                  currency: showPrices ? currency : '',
                  itemCount: p.units,
                  placedAt: DateTime.now(),
                  storeName: storeNames[p.storeId] ?? storeName,
                  fulfilmentType: _fulfilment,
                  slot: orderSlot,
                ),
              );
        }
      } else {
        await ref.read(storefrontOrdersProvider.notifier).add(
              StorefrontOrderRecord(
                orderId: orderId,
                total: showPrices ? total : 0,
                currency: showPrices ? currency : '',
                itemCount: cart.fold<int>(0, (s, l) => s + l.qty),
                placedAt: DateTime.now(),
                storeName: storeName,
                fulfilmentType: _fulfilment,
                slot: orderSlot,
              ),
            );
      }
      // Signed-in customers get a server-backed list — refresh it so the new
      // order shows on the next visit to "My orders".
      ref.invalidate(serverOrdersProvider);

      ref.read(cartProvider.notifier).clear();
      if (!mounted) return;
      setState(() => _placing = false);
      await showDialog<void>(
        context: context,
        builder: (ctx) => AlertDialog(
          icon: Icon(Icons.check_circle_outline,
              color: Theme.of(ctx).colorScheme.primary, size: 40),
          title: Text(payNow ? 'Payment successful' : 'Order placed'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text('Order #${shortRef(orderId)}'),
              if (split) ...[
                const SizedBox(height: 6),
                Text(splitSummary(parts, storeNames),
                    key: const Key('split-parts'), textAlign: TextAlign.center),
              ],
              if (payNow) ...[
                const SizedBox(height: 6),
                Text('$totalText paid',
                    style: const TextStyle(fontWeight: FontWeight.bold)),
              ],
              if (depositAmount > 0) ...[
                const SizedBox(height: 6),
                Text(
                    key: const Key('deposit-charged'),
                    'Includes a refundable container deposit of $depositText',
                    style: TextStyle(color: Theme.of(ctx).colorScheme.outline)),
              ],
              const SizedBox(height: 6),
              Text(
                  payNow
                      ? 'Your order is confirmed.'
                      : 'Your order request has been received.',
                  style: TextStyle(color: Theme.of(ctx).colorScheme.outline)),
              const SizedBox(height: 6),
              Text(
                  delivery ? 'Deliver to your address' : 'Collect from $storeName',
                  style: const TextStyle(fontWeight: FontWeight.bold),
                  textAlign: TextAlign.center),
              if (orderSlot != null) ...[
                const SizedBox(height: 6),
                Text(
                    slotWhen(
                        date: orderSlot.date,
                        startTime: orderSlot.startTime,
                        endTime: orderSlot.endTime),
                    key: const Key('order-slot-label'),
                    style: TextStyle(color: Theme.of(ctx).colorScheme.outline)),
              ],
              if (!payNow)
                Text(
                    showPrices
                        ? (pay.method == 'CASH'
                            ? (delivery
                                ? 'Pay $totalText in cash on delivery.'
                                : 'Pay $totalText in cash at pickup.')
                            : (delivery
                                ? 'Pay $totalText on delivery.'
                                : 'Pay $totalText at pickup.'))
                        : (delivery
                            ? 'Price & payment will be confirmed on delivery.'
                            : 'Price & payment will be confirmed in store.'),
                    style: TextStyle(
                        color: Theme.of(ctx).colorScheme.outline, fontSize: 12)),
            ],
          ),
          actions: [
            FilledButton(
              onPressed: () {
                Navigator.pop(ctx);
                context.go('/store/products');
              },
              child: const Text('Continue shopping'),
            ),
          ],
        ),
      );
      // Show post-order survey at most once per day — after the dialog so the
      // customer has a natural pause before the next prompt.
      final capturedOrderId = orderId;
      final shownToday =
          await ref.read(customerPrefsProvider.notifier).wasSurveyShownToday();
      if (mounted && !shownToday) {
        showPostOrderSurveySheet(context, capturedOrderId);
      }
      _line1Ctrl.clear();
      _line2Ctrl.clear();
      _cityCtrl.clear();
      _postalCtrl.clear();
      _recipientNameCtrl.clear();
      _recipientPhoneCtrl.clear();
      _contactPhoneCtrl.clear();
      setState(() {
        _fulfilment =
            (ref.read(storefrontConfigProvider).value?.pickupOffered ?? true)
                ? 'PICKUP'
                : 'DELIVERY';
        _payMethod = '';
        _selectedSlot = null;
      });
      // Refill the address form from the just-saved address so a follow-up
      // delivery order in the same session starts prefilled too.
      await _loadSavedAddress();
    } catch (e) {
      if (!mounted) return;
      final code = apiErrorCode(e);
      // The window filled or closed while the shopper was checking out: the
      // choice no longer holds, so it is cleared and the picker re-reads
      // rather than leaving a stale "Full" occurrence selected.
      if (code == 'ORDER_SLOT_FULL' || code == 'ORDER_SLOT_CLOSED') {
        final sid = _fulfilment == 'DELIVERY'
            ? _resolvedStoreId
            : ref.read(storefrontStoreProvider);
        if (sid != null && sid.isNotEmpty) {
          ref.invalidate(fulfilmentSlotsProvider((store: sid, type: _fulfilment)));
        }
        setState(() {
          _placing = false;
          _selectedSlot = null;
        });
      } else {
        setState(() => _placing = false);
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_checkoutErrorMessage(e)),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    }
  }

  /// Maps checkout failures to something a shopper can act on; falls back to the
  /// backend's structured message via [friendlyError].
  static String _checkoutErrorMessage(Object e) {
    switch (apiErrorCode(e)) {
      case 'ORDER_INSUFFICIENT_STOCK':
        return 'Sorry — some items in your cart just sold out. '
            'Please adjust the quantities and try again.';
      case 'ORDER_UNFULFILLABLE':
        return 'Sorry — our shops can\'t gather everything in your cart right now. '
            'Please adjust the quantities and try again.';
      case 'ORDER_INVENTORY_UNAVAILABLE':
        return 'We couldn\'t confirm stock right now. Please try again in a moment.';
      case 'PAYMENT_METHOD_DISABLED':
        return 'That payment method isn\'t available at this store any more. '
            'Please pick another one.';
      // Delivery and collection slots (delivery-and-collection-slots): the
      // window filled or closed while checking out, or the choice sent didn't
      // hold — the picker above re-reads so another can be chosen.
      case 'ORDER_SLOT_FULL':
      case 'ORDER_SLOT_CLOSED':
        return 'That window has just filled — pick another.';
      case 'ORDER_SLOT_REQUIRED':
        return 'Please choose a delivery or collection window.';
      case 'ORDER_SLOT_UNKNOWN':
        return 'That window is no longer available. Please choose another.';
      case 'ORDER_SLOT_NOT_APPLICABLE':
        return 'A window cannot be chosen for this order.';
      default:
        return friendlyError(e, fallback: 'Checkout failed.');
    }
  }

  // ── Split delivery (order orchestration) ─────────────────────────────────

  /// The shops' names, for saying where each part comes from; none when they cannot be read.
  Future<Map<String, String>> _storeNames() async {
    try {
      final stores = await ref.read(storefrontStoresProvider.future);
      return {for (final s in stores) s.id: s.name};
    } catch (_) {
      return const {};
    }
  }

  /// Before any money moves: the order comes in parts, from which shops, for how much each, and
  /// one payment for all of it. Returns true when the shopper pays.
  Future<bool> _confirmSplitSheet(List<CheckoutPart> parts,
      Map<String, String> storeNames, String currency, double payTotal) async {
    final paid = await showModalBottomSheet<bool>(
      context: context,
      showDragHandle: true,
      builder: (ctx) {
        final cs = Theme.of(ctx).colorScheme;
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 4, 20, 16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('Your order comes in ${parts.length} parts',
                    style: Theme.of(ctx).textTheme.titleLarge),
                const SizedBox(height: 8),
                Text(
                    'Not everything is at one shop, so ${parts.length} of our shops '
                    'will each send part of it. You pay once.',
                    style: TextStyle(color: cs.onSurfaceVariant)),
                const SizedBox(height: 8),
                for (final p in parts)
                  ListTile(
                    key: Key('split-part-${p.orderId}'),
                    contentPadding: EdgeInsets.zero,
                    leading: Icon(Icons.local_shipping_outlined,
                        color: cs.onSurfaceVariant),
                    title: Text(storeNames[p.storeId] ?? 'Another shop'),
                    subtitle:
                        Text('${p.units} item${p.units == 1 ? '' : 's'}'),
                    // Its own style, or a trailing slot's money inherits 11px labelSmall.
                    trailing: Text(AppFormat.money(p.total, currencyCode: currency),
                        style: Theme.of(ctx)
                            .textTheme
                            .titleSmall
                            ?.copyWith(fontWeight: FontWeight.w600)),
                  ),
                const SizedBox(height: 12),
                FilledButton.icon(
                  key: const Key('split-pay'),
                  onPressed: () => Navigator.pop(ctx, true),
                  icon: const Icon(Icons.lock_outline),
                  label: Text('Pay ${AppFormat.money(payTotal, currencyCode: currency)}'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(ctx, false),
                  child: const Text('Not now'),
                ),
              ],
            ),
          ),
        );
      },
    );
    return paid == true;
  }

  // ── Pending-order guard ──────────────────────────────────────────────────

  /// Returns the most recent pending order for this customer, or null if none.
  ///
  /// Queries the server order list for any order whose status indicates it has
  /// not yet been fulfilled. Checkout enforces sign-in before this runs, so a
  /// signed-in identity (and thus the server-backed list) is always available.
  Future<_PendingOrder?> _findPendingOrder() async {
    try {
      final orders = await ref.read(serverOrdersProvider.future);
      if (orders == null || orders.isEmpty) return null;
      const pendingStatuses = {'PENDING', 'RECEIVED', 'CONFIRMED', 'PROCESSING'};
      final pending = orders
          .where((o) => pendingStatuses.contains(o.status.toUpperCase()))
          .toList()
        ..sort((a, b) => b.placedAt.compareTo(a.placedAt));
      if (pending.isEmpty) return null;
      final o = pending.first;
      return _PendingOrder(orderId: o.id, placedAt: o.placedAt, status: o.status);
    } catch (_) {
      // Fail open — never block checkout if the status check errors.
      return null;
    }
  }

  Future<String?> _showPendingOrderDialog(_PendingOrder order) {
    final shortId = shortRef(order.orderId);
    final placedStr = AppFormat.dateTime(order.placedAt.toIso8601String());
    final cs = Theme.of(context).colorScheme;
    return showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        icon: Icon(Icons.pending_actions_outlined,
            size: 40, color: cs.primary),
        title: const Text('You have a pending order'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Order #$shortId placed at $placedStr is still being '
                'processed by the store.'),
            const SizedBox(height: 12),
            const Text('Would you like to update that order, or go ahead '
                'and place a new one?'),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Cancel'),
          ),
          OutlinedButton(
            onPressed: () => Navigator.pop(ctx, 'new'),
            child: const Text('Place new order'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, 'update'),
            child: const Text('View pending order'),
          ),
        ],
      ),
    );
  }
}


class _PendingOrder {
  final String orderId;
  final DateTime placedAt;
  final String status;
  const _PendingOrder(
      {required this.orderId,
      required this.placedAt,
      required this.status});
}

/// One selectable payment option in checkout. [payNow] = captured online immediately via
/// payment-svc; otherwise the tender is settled in person at pickup/delivery.
class _PayOption {
  final String method; // CASH | CARD | UPI | WALLET | '' (undeclared pay-later)
  final bool payNow;
  final String label;
  final IconData icon;
  const _PayOption(this.method, this.payNow, this.label, this.icon);
}
