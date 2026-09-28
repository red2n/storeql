import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/constants.dart';
import '../../core/format.dart';
import '../../core/input_mode.dart';
import '../../core/network/api_client.dart';
import '../../core/network/api_error.dart';
import '../../core/spacing.dart';
import '../../core/theme.dart';
import '../../shared/widgets/barcode_scanner_sheet.dart';
import '../../shared/widgets/empty_state.dart';
import '../admin/customer_providers.dart';
import '../admin/providers/admin_providers.dart';
import 'pos_age_check.dart';
import 'pos_providers.dart';
import 'pos_recall_check.dart';
import 'pos_weighed_item.dart';
import 'variable_measure_barcode.dart';
import 'weighing_instruments.dart';
import 'pos_session_providers.dart';

/// The register screen. From a tablet in portrait up it's a two-pane
/// supermarket till — a persistent product catalog on the start side, the live
/// sale on the end side. Narrower it collapses to the sale with a "Browse"
/// sheet for the catalog.
class PosCartScreen extends ConsumerStatefulWidget {
  const PosCartScreen({super.key});

  @override
  ConsumerState<PosCartScreen> createState() => _PosCartScreenState();
}

class _PosCartScreenState extends ConsumerState<PosCartScreen> {
  final _barcodeCtrl = TextEditingController();
  final _barcodeFocus = FocusNode();
  bool _scanning = false;

  /// From this content width the catalog stays beside the sale. An iPad in
  /// portrait has 739 beside the POS rail, so it gets both panes; a phone and
  /// a narrow window get the sale with the catalog behind Browse.
  static const double _splitWidth = 720;

  /// Below this window height (scaled with the text) the sale's fixed rows —
  /// store, customer, barcode, actions, the folded totals — would leave the
  /// lines no room: a phone on its side (844 × 390) is wide enough for both
  /// panes but has under 300 for the sale. There the panes stack and the top
  /// of the sale scrolls with its lines; the totals stay pinned below.
  ///
  /// Measured against the shell around the till: beside the rail (800 wide
  /// and up) the pane has the window less the app bar, and its rows need
  /// about 560 of window; under a bottom bar the pane also loses the bar, and
  /// at those widths the sale is at its narrowest (360), so its rows wrap
  /// taller — about 680.
  static const double _shortBesideRail = 560;
  static const double _shortOverBottomBar = 680;

  @override
  void initState() {
    super.initState();
    // A keyboard handler rather than Shortcuts, as the navigation shell does
    // for Ctrl+K: it works wherever focus is, including after a click on the
    // catalog has taken it off the barcode field.
    HardwareKeyboard.instance.addHandler(_onKey);
  }

  @override
  void dispose() {
    HardwareKeyboard.instance.removeHandler(_onKey);
    _barcodeCtrl.dispose();
    _barcodeFocus.dispose();
    super.dispose();
  }

  /// F2 puts the cursor back in the barcode field from anywhere on the Sale
  /// screen, ready for the scanner or a typed SKU.
  bool _onKey(KeyEvent event) {
    if (event is! KeyDownEvent ||
        event.logicalKey != LogicalKeyboardKey.f2 ||
        !mounted ||
        _scanning) {
      return false;
    }
    // Not while a dialog or the Browse sheet is over the screen: focus is then
    // in that route — neither on this page nor on a scope above it.
    final primary = FocusManager.instance.primaryFocus;
    final onThisPage = primary == null ||
        primary.enclosingScope == _barcodeFocus.enclosingScope ||
        _barcodeFocus.ancestors.contains(primary);
    if (!onThisPage) return false;
    _barcodeFocus.requestFocus();
    return true;
  }

  Future<void> _scan(String raw) async {
    final code = raw.trim();
    if (code.isEmpty || _scanning) return;
    setState(() => _scanning = true);
    PosLine? line;
    try {
      // A reduced-price sticker first (05.4): the code names the markdown and
      // its price. Then a labelling scale's label, then the catalogue.
      line =
          await scanMarkdownLabel(ref, code) ??
          await _scanLabel(code) ??
          await scanBarcode(ref, code);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(_friendly(e)),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    } finally {
      // Off before the age check, not after it: the check can stop for the
      // cashier, and a spinner behind that question says the till is still busy
      // when it is the cashier it is waiting for.
      if (mounted) setState(() => _scanning = false);
    }
    if (line != null) {
      // Checked before it reaches the sale: the age check, and for an item sold
      // by weight, the reading from the scale.
      final ready = await _prepareForSale(line);
      if (ready != null) {
        ref.read(posCartProvider.notifier).addOrIncrement(ready);
      }
      _barcodeCtrl.clear();
    }
    _barcodeFocus.requestFocus();
  }

  /// A label from a certified labelling scale: the item code and a price or a
  /// weight, read exactly. The line arrives already weighed, on that scale.
  /// Null when the code is not one of this store's labels.
  Future<PosLine?> _scanLabel(String code) async {
    final storeId = ref.read(posStoreProvider);
    if (storeId == null || code.length != 13) return null;
    List<WeighingInstrument> certified;
    try {
      certified = await ref.read(certifiedInstrumentsProvider(storeId).future);
    } catch (_) {
      // The register cannot be read, so no label can be trusted: the code is
      // looked up as an ordinary barcode, and the gate below refuses a sale by
      // weight with the reason.
      return null;
    }
    for (final scale in certified.where(
      (i) => i.isLabelling && i.labelScheme != null,
    )) {
      final reading = readVariableMeasureBarcode(code, scale.labelScheme!);
      if (reading == null) continue;
      final line = await scanBarcode(ref, reading.itemCode);
      final qty =
          reading.weight ?? quantityFromPrice(reading.price!, line.unitPrice);
      if (qty == null || qty <= 0) {
        throw StateError('The label prices an item that has no unit price.');
      }
      return line.copyWith(
        qty: qty,
        soldBy: 'WEIGHT',
        unit: 'kg',
        weighingInstrumentId: scale.id,
      );
    }
    return null;
  }

  String _friendly(Object e) {
    // A sticker pricing-svc refused says why in its own words: past its date,
    // every pack sold, taken off.
    if ((apiErrorCode(e) ?? '').startsWith('PRICING_MARKDOWN')) {
      return friendlyError(
        e,
        fallback: 'This reduced-price sticker cannot be sold.',
      );
    }
    // Prefer the backend's structured error; PRODUCT_NOT_FOUND (or a bare 404)
    // means the scanned barcode matched nothing.
    if (apiErrorCode(e) == 'PRODUCT_NOT_FOUND' ||
        (e is DioException && e.response?.statusCode == 404)) {
      return 'No product found for that barcode.';
    }
    return friendlyError(e, fallback: 'Scan failed. Please try again.');
  }

  Future<void> _scanWithCamera() async {
    final code = await scanBarcodeWithCamera(context);
    if (code != null && code.isNotEmpty) {
      _barcodeCtrl.text = code;
      await _scan(code);
    }
  }

  Future<void> _addOffer(PosOffer offer) async {
    // Picking from the catalog is the other way into the sale, so it gets the
    // same checks — otherwise browsing would be the way round them.
    final line = await _prepareForSale(offer.toLine());
    if (!mounted) return;
    // On a keyboard till the click on the catalog took the cursor out of the
    // barcode field; put it back so the scanner can carry on. Not on touch,
    // where focusing the field would open the on-screen keyboard.
    if (pointerFirst) _barcodeFocus.requestFocus();
    if (line == null) return;
    ref.read(posCartProvider.notifier).addOrIncrement(line);
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Added ${offer.name}'),
        duration: const Duration(milliseconds: 600),
      ),
    );
  }

  /// Everything a line must pass before it reaches the sale. Null keeps it out.
  Future<PosLine?> _prepareForSale(PosLine line) async {
    // The recall first: there is no point checking the age of a customer for
    // an item that cannot be sold to anyone.
    if (!await _passesRecallCheck(line)) return null;
    if (!await _passesAgeCheck(line)) return null;
    // A sticker prices the pack it is on, however the product is usually
    // sold: nothing to weigh.
    if (line.reduced) return line;
    final saleUnit = await fetchSaleUnit(
      ref.read(apiClientProvider).dio,
      line.variantId,
    );
    if (!mounted) return null;
    if (saleUnit is SoldEach) return line;
    if (saleUnit is SaleUnitUnknown) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(saleUnit.message),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
      return null;
    }
    final measure = saleUnit as SoldByMeasure;
    // Already weighed on a certified labelling scale: the reading is the label's.
    if (line.weighingInstrumentId != null) return line;
    // Selling by weight on an instrument not passed as fit for trade is an
    // offence (W&M Act 1985 s.11), so the till asks which certified scale the
    // reading comes from, and refuses when there is none — or when it cannot
    // find out.
    String? instrumentId;
    if (measure.soldBy == 'WEIGHT') {
      final storeId = ref.read(posStoreProvider);
      List<WeighingInstrument> certified;
      try {
        certified = storeId == null
            ? const []
            : await ref.read(certifiedInstrumentsProvider(storeId).future);
      } catch (e) {
        if (!mounted) return null;
        _snack(instrumentsUnavailableMessage(e), error: true);
        return null;
      }
      if (!mounted) return null;
      final counters = certified.where((i) => !i.isLabelling).toList();
      if (counters.isEmpty) {
        _snack(noCertifiedScaleMessage, error: true);
        return null;
      }
      final picked = await pickInstrument(
        context,
        counters,
        itemName: line.name,
      );
      if (picked == null) return null;
      instrumentId = picked.id;
    }
    if (!mounted) return null;
    final qty = await showDialog<double>(
      context: context,
      barrierDismissible: false,
      builder: (_) => MeasuredQuantityDialog(
        itemName: line.name,
        unit: measure,
        unitPrice: line.unitPrice,
        currency: line.currency,
      ),
    );
    if (qty == null) return null;
    return line.copyWith(
      qty: qty,
      soldBy: measure.soldBy,
      unit: measure.unit,
      weighingInstrumentId: instrumentId,
    );
  }

  /// The recall check, against the list the till keeps: blocked outright when
  /// every pack is recalled, a pack check when only some lots or dates are.
  Future<bool> _passesRecallCheck(PosLine line) async {
    // The pack's own lot and expiry, when a 2D code carried them (07.15): with
    // them the till stops the recalled lot and sells every other one, instead of
    // asking the cashier to read the jar for every pack of a recalled line.
    final result = checkRecall(
      line.variantId,
      ref.read(activeRecallsProvider).items,
      batchNo: line.batchNo,
      expiry: line.expiry,
    );
    if (result is RecallClear) return true;
    if (!mounted) return false;
    if (result is RecallBlocked) {
      await showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (_) =>
            RecallStopSaleDialog(itemName: line.name, item: result.item),
      );
      return false;
    }
    return await showDialog<bool>(
          context: context,
          barrierDismissible: false,
          builder: (_) => RecallCheckPackDialog(
            itemName: line.name,
            items: (result as RecallCheckPack).items,
          ),
        ) ??
        false;
  }

  /// The age check, asked before an item reaches the sale.
  ///
  /// Blocked outright when the till cannot find out — see [AgeCheckBlocked].
  /// Asked once per sale per age: confirming 18 covers the next bottle but not
  /// an item with a higher minimum.
  Future<bool> _passesAgeCheck(PosLine line) async {
    final country = await resolveSaleCountry(ref);
    final result = await checkAgeRestriction(
      ref.read(apiClientProvider).dio,
      line.variantId,
      country,
    );
    if (result is AgeCheckNotRestricted) return true;
    if (!mounted) return false;
    if (result is AgeCheckBlocked) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(result.message),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
      return false;
    }
    final check = result as AgeCheckRestricted;
    final cart = ref.read(posCartProvider.notifier);
    if (cart.coversAgeCheck(check.minimumAge, check.bornBefore)) return true;
    final decision = await showDialog<AgeDecision>(
      context: context,
      barrierDismissible: false,
      builder: (_) => AgeVerificationDialog(itemName: line.name, check: check),
    );
    if (decision == null) return false;
    final passed = decision is AgePassed;
    if (passed) cart.recordAgePass(check.minimumAge, check.bornBefore);
    // The decision stands either way; the record is what makes it a defence.
    final storeId = ref.read(posStoreProvider);
    if (storeId != null) {
      final recorded = await recordAgeCheck(
        ref.read(apiClientProvider).dio,
        storeId: storeId,
        variantId: line.variantId,
        check: check,
        decision: decision,
        posSessionId: ref.read(posSessionProvider)?.id,
      );
      if (!recorded && mounted) {
        _snack(
          passed
              ? 'The age check could not be recorded. The sale continues; tell a manager.'
              : 'The refusal could not be recorded. Tell a manager so it is written down.',
          error: true,
        );
      }
    }
    return passed;
  }

  /// Narrow-screen catalog: open the same catalog pane as a full-height sheet.
  Future<void> _browse() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => SizedBox(
        height: MediaQuery.of(context).size.height * 0.85,
        child: _CatalogPane(
          onPick: (offer) {
            _addOffer(offer);
            Navigator.pop(context);
          },
        ),
      ),
    );
    _barcodeFocus.requestFocus();
  }

  Future<void> _park() async {
    final items = ref.read(posCartProvider);
    final storeId = ref.read(posStoreProvider);
    if (items.isEmpty || storeId == null) return;
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.order}/pos/parked-sales',
            data: {
              'storeId': storeId,
              'items': [
                for (final l in items)
                  {
                    'variantId': l.variantId,
                    'qty': l.qty,
                    'unitPrice': l.unitPrice,
                    if (l.weighingInstrumentId != null)
                      'weighingInstrumentId': l.weighingInstrumentId,
                    if (l.markdownId != null) 'markdownId': l.markdownId,
                  },
              ],
            },
          );
      ref.read(posCartProvider.notifier).clear();
      ref.read(posDiscountProvider.notifier).state = 0;
      ref.read(posDiscountReasonProvider.notifier).state = '';
      ref.invalidate(parkedSalesProvider);
      _snack('Sale held.');
    } catch (e) {
      _snack(friendlyError(e, fallback: 'Could not hold sale.'), error: true);
    }
  }

  Future<void> _resume() async {
    final selected = await showDialog<ParkedSale>(
      context: context,
      builder: (ctx) => Consumer(
        builder: (ctx, ref, _) {
          final async = ref.watch(parkedSalesProvider);
          return AlertDialog(
            title: const Text('Resume held sale'),
            content: SizedBox(
              width: 380,
              child: async.when(
                loading: () => const SizedBox(
                  height: 80,
                  child: Center(child: CircularProgressIndicator()),
                ),
                error: (e, _) => Text(
                  friendlyError(e, fallback: 'Could not load held sales.'),
                ),
                data: (sales) => sales.isEmpty
                    ? const Text('No held sales.')
                    : Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          for (final s in sales)
                            ListTile(
                              title: Text(s.customerName ?? 'Held sale'),
                              // A held sale carries no currency: the amount alone.
                              subtitle: Text(
                                '${s.lines.length} item${s.lines.length == 1 ? '' : 's'}'
                                ' · ${AppFormat.money(s.subtotal)}',
                              ),
                              onTap: () => Navigator.pop(ctx, s),
                            ),
                        ],
                      ),
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('Close'),
              ),
            ],
          );
        },
      ),
    );
    if (selected == null || !mounted) return;

    final current = ref.read(posCartProvider);
    if (current.isNotEmpty) {
      final discard = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Discard current sale?'),
          content: Text(
            'The current sale has ${current.length} item'
            '${current.length == 1 ? '' : 's'} that haven\'t been held or '
            'charged. Resuming the held sale will discard them.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('Discard & resume'),
            ),
          ],
        ),
      );
      if (discard != true) return;
    }

    ref.read(posCartProvider.notifier).loadLines(selected.lines);
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .delete('/${ApiConstants.order}/pos/parked-sales/${selected.id}');
      ref.invalidate(parkedSalesProvider);
    } catch (_) {
      // Resumed locally even if the delete failed; it will expire server-side.
    }
  }

  Future<void> _noSale() async {
    final storeId = ref.read(posStoreProvider);
    try {
      await ref
          .read(apiClientProvider)
          .dio
          .post(
            '/${ApiConstants.order}/pos/no-sale',
            data: {'storeId': storeId, 'reason': 'No sale'},
          );
      _snack('Drawer opened (no sale logged).');
    } catch (e) {
      _snack(friendlyError(e, fallback: 'Could not log no-sale.'), error: true);
    }
  }

  void _snack(String msg, {bool error = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(msg),
        backgroundColor: error ? Theme.of(context).colorScheme.error : null,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    // Short is the window's height, not the pane's: the keyboard shrinks the
    // pane, and a barcode field that moved when it opened would lose focus.
    final window = MediaQuery.sizeOf(context);
    final need = window.width < AppBreakpoints.rail
        ? _shortOverBottomBar
        : _shortBesideRail;
    final short =
        window.height < MediaQuery.textScalerOf(context).scale(need);
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final phone = AppBreakpoints.classOf(width) == WindowClass.compact;
        // On a phone, or in a window too short for the full breakdown to leave
        // the sale lines any room (a laptop browser, the keyboard up on a
        // tablet in landscape), the totals fold into one row.
        final tight = phone || short || constraints.maxHeight < 680;
        if (width >= _splitWidth && !short) {
          // The sale keeps a till-receipt width; the catalog takes the rest.
          final saleWidth = (width * 0.46).clamp(360.0, 420.0).toDouble();
          return Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Expanded(child: _CatalogPane(onPick: _addOffer)),
              const VerticalDivider(width: 1),
              SizedBox(
                width: saleWidth,
                child: _salePane(
                  showBrowse: false,
                  phone: false,
                  tight: tight,
                  narrowLines: true,
                  scrollHeader: false,
                ),
              ),
            ],
          );
        }
        return _salePane(
          showBrowse: true,
          phone: phone,
          tight: tight,
          narrowLines: phone,
          scrollHeader: short,
        );
      },
    );
  }

  /// The running sale. [phone]: Browse is an icon, so the barcode field keeps
  /// most of its row, and a walk-in's phone field shares a row with the
  /// customer button. [tight]: the totals fold into one row. [narrowLines]: the
  /// pane is under 600 wide, so each sale line puts its stepper under its name.
  /// [scrollHeader]: a short window — the rows above the lines scroll with
  /// them instead of staying put, so nothing overflows; the totals stay.
  Widget _salePane({
    required bool showBrowse,
    required bool phone,
    required bool tight,
    required bool narrowLines,
    required bool scrollHeader,
  }) {
    final items = ref.watch(posCartProvider);
    final cs = Theme.of(context).colorScheme;
    final header = <Widget>[
      const RecallListBanner(),
      _StoreSelector(),
      _CustomerBar(compact: phone),
      Padding(
        padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
        child: Row(
          children: [
            Expanded(
              child: TextField(
                controller: _barcodeCtrl,
                focusNode: _barcodeFocus,
                autofocus: true,
                enabled: !_scanning,
                decoration: InputDecoration(
                  isDense: true,
                  // The full hint ellipsizes in a phone's ~160px; say the same shorter.
                  hintText: phone
                      ? 'Scan or type SKU…'
                      : 'Scan barcode or type SKU…',
                  prefixIcon: const Icon(Icons.qr_code_scanner),
                  // The way back to this field, where there is a keyboard.
                  suffixText: pointerFirst ? 'F2' : null,
                  suffixIcon: _scanning
                      ? const Padding(
                          padding: EdgeInsets.all(12),
                          child: SizedBox(
                            height: 18,
                            width: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          ),
                        )
                      : IconButton(
                          icon: const Icon(Icons.add_circle_outline),
                          color: cs.primary,
                          onPressed: () => _scan(_barcodeCtrl.text),
                        ),
                ),
                onSubmitted: _scan,
              ),
            ),
            const SizedBox(width: 8),
            IconButton.filledTonal(
              tooltip: 'Scan with camera',
              onPressed: _scanning ? null : _scanWithCamera,
              icon: const Icon(Icons.camera_alt_outlined),
            ),
            if (showBrowse) ...[
              const SizedBox(width: 8),
              // A labelled button would leave a phone's barcode field about
              // 100px for text; as an icon it keeps most of the row.
              if (phone)
                IconButton.outlined(
                  tooltip: 'Browse products',
                  onPressed: _scanning ? null : _browse,
                  icon: const Icon(Icons.grid_view),
                )
              else
                OutlinedButton.icon(
                  onPressed: _scanning ? null : _browse,
                  icon: const Icon(Icons.grid_view, size: 18),
                  label: const Text('Browse'),
                  style: OutlinedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 14,
                    ),
                  ),
                ),
            ],
          ],
        ),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(8, 0, 8, 4),
        child: Row(
          children: [
            // Hold and Resume wrap onto a second line rather than push No
            // sale off the row when the text is large.
            Expanded(
              child: Wrap(
                children: [
                  TextButton.icon(
                    onPressed: items.isEmpty ? null : _park,
                    icon: const Icon(Icons.pause_circle_outline, size: 18),
                    label: const Text('Hold'),
                  ),
                  TextButton.icon(
                    onPressed: _resume,
                    icon: const Icon(Icons.play_circle_outline, size: 18),
                    label: const Text('Resume'),
                  ),
                ],
              ),
            ),
            TextButton.icon(
              onPressed: _noSale,
              icon: const Icon(Icons.point_of_sale, size: 18),
              label: const Text('No sale'),
            ),
          ],
        ),
      ),
      const Divider(height: 1),
    ];
    final emptyBody = Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            Icons.shopping_cart_outlined,
            size: 48,
            color: cs.outlineVariant,
          ),
          const SizedBox(height: AppSpacing.md),
          Text(
            'Scan or tap a product to start',
            textAlign: TextAlign.center,
            style: TextStyle(color: cs.onSurfaceVariant),
          ),
        ],
      ),
    );
    // Pinned under the header, the empty till scrolls rather than overflowing
    // when the room left is less than its icon and words need.
    final empty = LayoutBuilder(
      builder: (context, c) => SingleChildScrollView(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            minHeight: c.maxHeight.isFinite ? c.maxHeight : 0,
          ),
          child: emptyBody,
        ),
      ),
    );
    Widget line(BuildContext _, int idx) =>
        _SaleLine(line: items[idx], narrow: narrowLines);
    Widget separator(BuildContext _, int _) => const Divider(height: 1);
    final totals = <Widget>[
      const Divider(height: 1),
      _TotalsBar(collapsible: tight),
    ];

    if (scrollHeader) {
      return Column(
        children: [
          Expanded(
            child: CustomScrollView(
              slivers: [
                SliverToBoxAdapter(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: header,
                  ),
                ),
                if (items.isEmpty)
                  SliverFillRemaining(hasScrollBody: false, child: emptyBody)
                else
                  SliverList.separated(
                    itemCount: items.length,
                    separatorBuilder: separator,
                    itemBuilder: line,
                  ),
              ],
            ),
          ),
          ...totals,
        ],
      );
    }
    return Column(
      children: [
        ...header,
        Expanded(
          child: items.isEmpty
              ? empty
              : ListView.separated(
                  itemCount: items.length,
                  separatorBuilder: separator,
                  itemBuilder: line,
                ),
        ),
        ...totals,
      ],
    );
  }
}

/// One line on the running sale: name, unit price, qty stepper, line total, and
/// a swipe-to-remove gesture. In a pane under 600 wide ([narrow]) the line
/// total sits on the name's row and the stepper beside the price under it — a
/// trailing stepper there would leave the name a word or two.
class _SaleLine extends ConsumerWidget {
  final PosLine line;
  final bool narrow;
  const _SaleLine({required this.line, this.narrow = false});

  /// A measured line changes by reading the scale again, never by one.
  Future<void> _remeasure(
    BuildContext context,
    WidgetRef ref,
    PosLine line,
  ) async {
    final qty = await showDialog<double>(
      context: context,
      barrierDismissible: false,
      builder: (_) => MeasuredQuantityDialog(
        itemName: line.name,
        unit: SoldByMeasure(
          soldBy: line.soldBy,
          unit: line.unit ?? defaultUnitFor(line.soldBy),
          catchWeight: false,
        ),
        unitPrice: line.unitPrice,
        currency: line.currency,
        initial: line.qty,
        confirmLabel: 'Update',
      ),
    );
    if (qty != null) {
      ref
          .read(posCartProvider.notifier)
          .setQty(line.variantId, qty, markdownId: line.markdownId);
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final notifier = ref.read(posCartProvider.notifier);
    final showPrices = ref.watch(posShowPricesProvider);
    final theme = Theme.of(context);
    final cs = theme.colorScheme;
    String money(double v) => AppFormat.money(v, currencyCode: line.currency);
    // Figures set their own type: a list row's trailing slot would otherwise
    // give them its 11px label style.
    final figure = theme.textTheme.titleSmall?.copyWith(
      fontWeight: FontWeight.bold,
    );

    final details = line.reduced
        ? Wrap(
            crossAxisAlignment: WrapCrossAlignment.center,
            spacing: 6,
            children: [
              Container(
                key: Key('reduced-${line.markdownId}'),
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                decoration: BoxDecoration(
                  color: cs.tertiaryContainer,
                  borderRadius: AppRadius.badge,
                ),
                child: Text(
                  // A sticker the law will not let be called reduced is still a markdown.
                  line.originalPrice != null ? 'REDUCED' : 'MARKDOWN',
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: cs.onTertiaryContainer,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
              if (showPrices) Text(money(line.unitPrice)),
              if (showPrices && line.originalPrice != null)
                Text(
                  'was ${money(line.originalPrice!)}',
                  style: const TextStyle(
                    decoration: TextDecoration.lineThrough,
                  ),
                ),
              Text('· ${line.sku}'),
            ],
          )
        : Text(
            showPrices ? '${money(line.unitPrice)} · ${line.sku}' : line.sku,
          );

    final quantity = <Widget>[
      if (line.measured)
        TextButton(
          onPressed: () => _remeasure(context, ref, line),
          child: Text(
            line.qtyLabel,
            style: const TextStyle(fontWeight: FontWeight.bold),
          ),
        )
      else ...[
        IconButton(
          visualDensity: VisualDensity.compact,
          icon: const Icon(Icons.remove_circle_outline),
          tooltip: 'Decrease quantity',
          onPressed: () => notifier.setQty(
            line.variantId,
            line.qty - 1,
            markdownId: line.markdownId,
          ),
        ),
        Text(line.qtyLabel, style: figure),
        IconButton(
          visualDensity: VisualDensity.compact,
          icon: const Icon(Icons.add_circle_outline),
          tooltip: 'Increase quantity',
          onPressed: () => notifier.setQty(
            line.variantId,
            line.qty + 1,
            markdownId: line.markdownId,
          ),
        ),
      ],
    ];

    final total = showPrices
        ? Text(money(line.lineTotal), textAlign: TextAlign.end, style: figure)
        : null;

    return Dismissible(
      key: ValueKey(line.variantId),
      direction: DismissDirection.endToStart,
      background: Container(
        color: cs.errorContainer,
        alignment: AlignmentDirectional.centerEnd,
        padding: const EdgeInsetsDirectional.only(end: 20),
        child: Icon(Icons.delete_outline, color: cs.onErrorContainer),
      ),
      onDismissed: (_) =>
          notifier.setQty(line.variantId, 0, markdownId: line.markdownId),
      child: narrow
          ? ListTile(
              dense: true,
              title: Row(
                children: [
                  Expanded(
                    child: Text(
                      line.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  if (total != null) ...[const SizedBox(width: 8), total],
                ],
              ),
              subtitle: Row(
                children: [
                  Expanded(child: details),
                  ...quantity,
                ],
              ),
            )
          : ListTile(
              dense: true,
              title: Text(
                line.name,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              subtitle: details,
              trailing: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  ...quantity,
                  // Totals line up down the list; a long one still fits.
                  if (total != null)
                    ConstrainedBox(
                      constraints: const BoxConstraints(minWidth: 72),
                      child: total,
                    ),
                ],
              ),
            ),
    );
  }
}

/// The catalog pane: category filter + search + tap-to-add product grid. Each
/// tile resolves its own POS price + live stock lazily (only visible tiles
/// fetch), mirroring the storefront's per-card offer pattern.
class _CatalogPane extends ConsumerStatefulWidget {
  final void Function(PosOffer) onPick;
  const _CatalogPane({required this.onPick});

  @override
  ConsumerState<_CatalogPane> createState() => _CatalogPaneState();
}

class _CatalogPaneState extends ConsumerState<_CatalogPane> {
  final _searchCtrl = TextEditingController();

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final categoriesAsync = ref.watch(posCategoriesProvider);
    final selectedCat = ref.watch(posSelectedCategoryProvider);
    final query = ref.watch(posSearchProvider);
    final inStockOnly = ref.watch(posInStockOnlyProvider);
    final productsAsync = ref.watch(
      posCatalogProvider((categoryId: selectedCat, query: query)),
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // Search bar + in-stock toggle
        Padding(
          padding: const EdgeInsetsDirectional.fromSTEB(12, 12, 4, 8),
          child: Row(
            children: [
              Expanded(
                child: SearchBar(
                  controller: _searchCtrl,
                  hintText: 'Search products…',
                  leading: const Icon(Icons.search),
                  trailing: query.isEmpty
                      ? null
                      : [
                          IconButton(
                            icon: const Icon(Icons.clear),
                            tooltip: 'Clear search',
                            onPressed: () {
                              _searchCtrl.clear();
                              ref.read(posSearchProvider.notifier).state = '';
                            },
                          ),
                        ],
                  onChanged: (v) =>
                      ref.read(posSearchProvider.notifier).state = v.trim(),
                ),
              ),
              const SizedBox(width: 8),
              FilterChip(
                avatar: Icon(
                  Icons.inventory_2_outlined,
                  size: 16,
                  color: inStockOnly
                      ? cs.onSecondaryContainer
                      : cs.onSurfaceVariant,
                ),
                label: const Text('In stock'),
                selected: inStockOnly,
                visualDensity: VisualDensity.compact,
                onSelected: (v) =>
                    ref.read(posInStockOnlyProvider.notifier).state = v,
              ),
              const SizedBox(width: 8),
            ],
          ),
        ),
        // A chip's height while the categories load, and taller with large
        // text — a fixed height would clip the labels.
        ConstrainedBox(
          constraints: const BoxConstraints(minHeight: 40),
          child: categoriesAsync.when(
            loading: () => const SizedBox.shrink(),
            error: (_, _) => const SizedBox.shrink(),
            data: (cats) => SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12),
              child: Row(
                children: [
                  Padding(
                    padding: const EdgeInsetsDirectional.only(end: 8),
                    child: ChoiceChip(
                      label: const Text('All'),
                      selected: selectedCat == null,
                      onSelected: (_) =>
                          ref.read(posSelectedCategoryProvider.notifier).state =
                              null,
                    ),
                  ),
                  for (final c in cats)
                    Padding(
                      padding: const EdgeInsetsDirectional.only(end: 8),
                      child: ChoiceChip(
                        label: Text(c.name),
                        selected: selectedCat == c.id,
                        onSelected: (_) => ref
                            .read(posSelectedCategoryProvider.notifier)
                            .state = c.id,
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
        const SizedBox(height: 8),
        Expanded(
          child: productsAsync.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text(
                  friendlyError(e, fallback: 'Could not load products.'),
                  textAlign: TextAlign.center,
                ),
              ),
            ),
            data: (products) {
              // Apply in-stock filter using lazy-resolved offer data.
              // Products whose offer hasn't loaded yet are kept (show while loading).
              List<ProductInfo> displayProducts = products;
              if (inStockOnly) {
                displayProducts = products.where((p) {
                  final offer = ref.watch(posProductOfferProvider(p)).value;
                  return offer == null || offer.inStock;
                }).toList();
              }

              if (displayProducts.isEmpty) {
                return const EmptyState(
                  icon: Icons.inventory_2_outlined,
                  title: 'No in-stock products.',
                );
              }
              return GridView.builder(
                padding: const EdgeInsets.all(12),
                gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
                  maxCrossAxisExtent: 170,
                  childAspectRatio: 1.15,
                  crossAxisSpacing: 10,
                  mainAxisSpacing: 10,
                ),
                itemCount: displayProducts.length,
                itemBuilder: (_, i) => _OfferTile(
                  product: displayProducts[i],
                  onPick: widget.onPick,
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

/// A product tile that resolves its POS price + stock and adds to the sale on tap.
class _OfferTile extends ConsumerWidget {
  final ProductInfo product;
  final void Function(PosOffer) onPick;
  const _OfferTile({required this.product, required this.onPick});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cs = Theme.of(context).colorScheme;
    final showPrices = ref.watch(posShowPricesProvider);
    final offerAsync = ref.watch(posProductOfferProvider(product));
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: offerAsync.maybeWhen(
          data: (o) => o == null ? null : () => onPick(o),
          orElse: () => null,
        ),
        child: Padding(
          padding: const EdgeInsets.all(10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Text(
                  product.name,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: 13,
                  ),
                ),
              ),
              const SizedBox(height: 6),
              offerAsync.when(
                loading: () =>
                    Text('…', style: TextStyle(color: cs.onSurfaceVariant)),
                error: (_, _) =>
                    Text('—', style: TextStyle(color: cs.onSurfaceVariant)),
                data: (o) {
                  if (o == null) {
                    return Text(
                      'No variant',
                      style: TextStyle(color: cs.error, fontSize: 11),
                    );
                  }
                  // Catalog mode: no price anywhere — show stock status instead.
                  if (!showPrices) {
                    return Row(
                      children: [
                        Expanded(
                          child: Text(
                            o.inStock ? 'In stock' : 'Out of stock',
                            style: TextStyle(
                              color: o.inStock
                                  ? context.status.success
                                  : cs.error,
                              fontWeight: FontWeight.w600,
                              fontSize: 12,
                            ),
                          ),
                        ),
                        _StockDot(inStock: o.inStock),
                      ],
                    );
                  }
                  return Row(
                    children: [
                      Expanded(
                        child: Text(
                          AppFormat.money(o.unitPrice, currencyCode: o.currency),
                          // A price is a figure: on-surface ink, not the accent.
                          style: TextStyle(
                            color: cs.onSurface,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ),
                      _StockDot(inStock: o.inStock),
                    ],
                  );
                },
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _StockDot extends StatelessWidget {
  final bool inStock;
  const _StockDot({required this.inStock});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Tooltip(
      message: inStock ? 'In stock' : 'Out of stock',
      child: Icon(
        Icons.circle,
        size: 10,
        color: inStock ? context.status.success : cs.error,
      ),
    );
  }
}

/// Shows the store this terminal is clocked in to (fixed for the session) plus
/// how long the session has been open.
class _StoreSelector extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final storesAsync = ref.watch(posStoresProvider);
    final session = ref.watch(posSessionProvider);
    final selected = ref.watch(posStoreProvider);
    final cs = Theme.of(context).colorScheme;

    final storeId = session?.storeId ?? selected;
    final storeName = storesAsync.maybeWhen(
      data: (stores) {
        for (final s in stores) {
          if (s.id == storeId) return s.name;
        }
        return null;
      },
      orElse: () => null,
    );

    // surfaceContainerHighest is kept for loading skeletons; the bar sits one
    // step down, where the timer's onSurfaceVariant still clears AA.
    return Container(
      width: double.infinity,
      color: cs.surfaceContainerHigh,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Row(
        children: [
          Icon(Icons.store, size: 18, color: cs.primary),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              storeName ?? 'Store',
              style: const TextStyle(fontWeight: FontWeight.w600),
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (session != null) ...[
            Icon(Icons.schedule, size: 14, color: cs.onSurfaceVariant),
            const SizedBox(width: 4),
            Text(
              _elapsed(session.startedAt),
              style: TextStyle(color: cs.onSurfaceVariant, fontSize: 12),
            ),
          ],
        ],
      ),
    );
  }

  String _elapsed(String startedAtIso) {
    final start = DateTime.tryParse(startedAtIso);
    if (start == null) return '';
    final d = DateTime.now().difference(start.toLocal());
    if (d.inHours > 0) return '${d.inHours}h ${d.inMinutes % 60}m';
    return '${d.inMinutes}m';
  }
}

/// Shows the customer attached to the sale. For walk-in sales a mandatory phone
/// field is shown inline — the store needs a contact number for every order.
///
/// On a phone ([compact]) a walk-in's phone field and the button that attaches
/// a customer share one row, which leaves the sale lines more of the screen.
/// Decided by width only: the keyboard changes the height, and a field that
/// moved when the keyboard opened would lose its focus.
class _CustomerBar extends ConsumerStatefulWidget {
  final bool compact;
  const _CustomerBar({this.compact = false});

  @override
  ConsumerState<_CustomerBar> createState() => _CustomerBarState();
}

class _CustomerBarState extends ConsumerState<_CustomerBar> {
  late final TextEditingController _phoneCtrl;

  @override
  void initState() {
    super.initState();
    _phoneCtrl = TextEditingController(text: ref.read(posWalkInPhoneProvider));
  }

  @override
  void dispose() {
    _phoneCtrl.dispose();
    super.dispose();
  }

  Future<void> _pickCustomer() async {
    final picked = await showDialog<Customer?>(
      context: context,
      builder: (_) => const _CustomerPickerDialog(),
    );
    if (picked == null || !mounted) return;
    ref.read(posCustomerProvider.notifier).state =
        picked.id.isEmpty ? null : picked;
  }

  @override
  Widget build(BuildContext context) {
    final customer = ref.watch(posCustomerProvider);
    final tillPhone = ref.watch(posTillPhoneProvider);
    final walkInPhone = ref.watch(posWalkInPhoneProvider);
    final cs = Theme.of(context).colorScheme;

    // When a customer is attached, clear the walk-in phone so it doesn't linger.
    if (customer != null && walkInPhone.isNotEmpty) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          ref.read(posWalkInPhoneProvider.notifier).state = '';
          _phoneCtrl.clear();
        }
      });
    }

    // Walk-in phone (phone-at-the-till): shown whenever no customer account is
    // linked and this store's till asks at all — hidden outright under Don't ask.
    final phoneField = (customer != null || tillPhone == 'OFF')
        ? null
        : TextField(
            key: const Key('pos-customer-phone-field'),
            controller: _phoneCtrl,
            keyboardType: TextInputType.phone,
            decoration: InputDecoration(
              isDense: true,
              labelText: posPhoneFieldLabel(tillPhone),
              hintText: posPhoneFieldHint(tillPhone),
              prefixIcon: const Icon(Icons.phone_outlined, size: 18),
              // A blank field is only a warning where the store requires a
              // number; under Optional it is exactly what the customer chose.
              suffixIcon: walkInPhone.isNotEmpty
                  ? Icon(
                      Icons.check_circle_outline,
                      size: 18,
                      color: context.status.success,
                    )
                  : (tillPhone == 'REQUIRED'
                      ? Icon(
                          Icons.warning_amber_outlined,
                          size: 18,
                          color: context.status.warning,
                        )
                      : null),
            ),
            onChanged: (v) =>
                ref.read(posWalkInPhoneProvider.notifier).state = v.trim(),
          );

    if (widget.compact && phoneField != null) {
      return Padding(
        padding: const EdgeInsetsDirectional.fromSTEB(12, 8, 4, 4),
        child: Row(
          children: [
            Expanded(child: phoneField),
            IconButton(
              icon: const Icon(Icons.person_add_alt),
              tooltip: 'Add customer',
              onPressed: _pickCustomer,
            ),
          ],
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsetsDirectional.fromSTEB(12, 4, 4, 0),
          child: Row(
            children: [
              Icon(Icons.person_outline, size: 18, color: cs.onSurfaceVariant),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  customer == null
                      ? 'Walk-in customer'
                      : (customer.fullName.isEmpty
                            ? customer.email
                            : customer.fullName),
                  style: TextStyle(
                    color: customer == null ? cs.onSurfaceVariant : cs.onSurface,
                    fontWeight: customer == null
                        ? FontWeight.normal
                        : FontWeight.w600,
                  ),
                ),
              ),
              if (customer != null)
                IconButton(
                  icon: const Icon(Icons.close, size: 18),
                  tooltip: 'Remove customer',
                  onPressed: () =>
                      ref.read(posCustomerProvider.notifier).state = null,
                ),
              TextButton.icon(
                icon: Icon(
                  customer == null ? Icons.person_add_alt : Icons.swap_horiz,
                  size: 18,
                ),
                label: Text(customer == null ? 'Add' : 'Change'),
                onPressed: _pickCustomer,
              ),
            ],
          ),
        ),
        if (phoneField != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 4, 12, 4),
            child: phoneField,
          ),
      ],
    );
  }
}

/// Subtotal, optional order discount, net total, plus Clear / Tender actions.
///
/// [collapsible] (a phone, or a short window): the breakdown folds into one
/// row above the buttons — the total, with a line saying what is in it — and a
/// tap on that row opens it, discount button included.
class _TotalsBar extends ConsumerStatefulWidget {
  final bool collapsible;
  const _TotalsBar({this.collapsible = false});

  @override
  ConsumerState<_TotalsBar> createState() => _TotalsBarState();
}

class _TotalsBarState extends ConsumerState<_TotalsBar> {
  bool _expanded = false;

  Future<void> _editDiscount(String currency, double subtotal) async {
    // Read before the dialog and set through afterwards: the bar may be
    // rebuilt in another layout while the dialog is open.
    final discountState = ref.read(posDiscountProvider.notifier);
    final reasonState = ref.read(posDiscountReasonProvider.notifier);
    final ctrl = TextEditingController(
      text: discountState.state > 0
          ? discountState.state.toStringAsFixed(2)
          : '',
    );
    final reasonCtrl = TextEditingController(text: reasonState.state);
    final symbol = AppFormat.currencySymbol(currency);
    // The server refuses a discount with no reason and records the one given against the cashier,
    // so Apply stays disabled until both fields are filled rather than failing at tender time.
    final result = await showDialog<(double, String)>(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, setDialogState) {
          final amount = double.tryParse(ctrl.text) ?? 0;
          final reason = reasonCtrl.text.trim();
          final canApply = amount > 0 && reason.isNotEmpty;
          return AlertDialog(
            title: const Text('Order discount'),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: ctrl,
                  autofocus: true,
                  keyboardType: const TextInputType.numberWithOptions(
                    decimal: true,
                  ),
                  decoration: InputDecoration(
                    labelText: 'Discount amount',
                    prefixText: symbol.isEmpty ? null : '$symbol ',
                  ),
                  onChanged: (_) => setDialogState(() {}),
                ),
                const SizedBox(height: 12),
                TextField(
                  controller: reasonCtrl,
                  decoration: const InputDecoration(
                    labelText: 'Reason',
                    hintText: 'e.g. damaged packaging',
                  ),
                  onChanged: (_) => setDialogState(() {}),
                ),
              ],
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(ctx, (0.0, '')),
                child: const Text('Clear'),
              ),
              FilledButton(
                onPressed: canApply
                    ? () => Navigator.pop(ctx, (amount, reason))
                    : null,
                child: const Text('Apply'),
              ),
            ],
          );
        },
      ),
    );
    if (result == null) return;
    discountState.state = result.$1.clamp(0, subtotal).toDouble();
    reasonState.state = result.$2;
  }

  @override
  Widget build(BuildContext context) {
    final items = ref.watch(posCartProvider);
    final showPrices = ref.watch(posShowPricesProvider);
    final subtotal = ref.watch(posCartProvider.notifier).total;
    final currency = items.isNotEmpty ? items.first.currency : '';
    final discount = ref
        .watch(posDiscountProvider)
        .clamp(0, subtotal)
        .toDouble();
    final deposits = ref.watch(posCartProvider.notifier).deposits;
    final net = subtotal - discount + deposits;
    final theme = Theme.of(context);
    final tt = theme.textTheme;
    final cs = theme.colorScheme;
    final qty = items.fold<int>(0, (s, l) => s + l.itemCount);
    String money(double v) => AppFormat.money(v, currencyCode: currency);

    final clearButton = Expanded(
      child: OutlinedButton(
        onPressed: items.isEmpty
            ? null
            : () {
                ref.read(posCartProvider.notifier).clear();
                ref.read(posDiscountProvider.notifier).state = 0;
              },
        child: const Text('Clear'),
      ),
    );

    // Catalog mode: no prices anywhere, checkout just places the order.
    if (!showPrices) {
      return Padding(
        padding: const EdgeInsets.fromLTRB(16, 10, 16, 14),
        child: Row(
          children: [
            clearButton,
            const SizedBox(width: 12),
            Expanded(
              flex: 2,
              child: FilledButton.icon(
                style: FilledButton.styleFrom(
                  backgroundColor: context.channelAccent.color,
                  foregroundColor: context.channelAccent.onColor,
                  padding: const EdgeInsets.symmetric(vertical: 16),
                ),
                onPressed: items.isEmpty
                    ? null
                    : () => context.go('/pos/tender'),
                icon: const Icon(Icons.receipt_long),
                label: Text(
                  'Place order${qty > 0 ? '  ($qty)' : ''}',
                  style: const TextStyle(fontSize: 16),
                ),
              ),
            ),
          ],
        ),
      );
    }

    final totalStyle = tt.titleLarge?.copyWith(fontWeight: FontWeight.bold);
    final folded = widget.collapsible && !_expanded;
    // What the folded total is made of, in a few words.
    final inTotal = [
      '$qty item${qty == 1 ? '' : 's'}',
      if (discount > 0) '${money(discount)} off',
      if (deposits > 0) '${money(deposits)} deposit',
    ].join(' · ');
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (!folded) ...[
            Row(
              children: [
                Expanded(child: Text('Subtotal', style: tt.bodyMedium)),
                Text(money(subtotal), style: tt.bodyMedium),
              ],
            ),
            if (deposits > 0) ...[
              const SizedBox(height: 2),
              Row(
                key: const Key('pos-deposit-row'),
                children: [
                  Expanded(
                    child: Text(
                      'Container deposit (refundable)',
                      style: tt.bodyMedium,
                    ),
                  ),
                  Text(money(deposits), style: tt.bodyMedium),
                ],
              ),
            ],
            const SizedBox(height: 2),
            Row(
              children: [
                Expanded(
                  child: Align(
                    alignment: AlignmentDirectional.centerStart,
                    child: TextButton.icon(
                      onPressed: items.isEmpty
                          ? null
                          : () => _editDiscount(currency, subtotal),
                      icon: const Icon(Icons.percent, size: 16),
                      label: Text(discount > 0 ? 'Discount' : 'Add discount'),
                      style: TextButton.styleFrom(
                        padding: EdgeInsets.zero,
                        minimumSize: const Size(0, 30),
                      ),
                    ),
                  ),
                ),
                if (discount > 0)
                  Text(
                    '− ${money(discount)}',
                    style: tt.bodyMedium?.copyWith(color: cs.error),
                  ),
              ],
            ),
            const Divider(),
          ],
          if (widget.collapsible)
            // The total and what is in it, on one row; a tap opens the
            // breakdown above it.
            InkWell(
              key: const Key('pos-totals-summary'),
              onTap: () => setState(() => _expanded = !_expanded),
              borderRadius: const BorderRadius.all(
                Radius.circular(AppRadius.sm),
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(
                  children: [
                    // Gives way before the amount when room runs out (large
                    // text, a long total): the hint first, then the word.
                    Expanded(
                      child: Text.rich(
                        TextSpan(
                          children: [
                            TextSpan(text: 'Total', style: totalStyle),
                            TextSpan(
                              text: '  $inTotal',
                              style: tt.bodySmall?.copyWith(
                                color: cs.onSurfaceVariant,
                              ),
                            ),
                          ],
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(money(net), style: totalStyle),
                    Icon(
                      _expanded ? Icons.expand_more : Icons.expand_less,
                      color: cs.onSurfaceVariant,
                      semanticLabel: _expanded
                          ? 'Hide price breakdown'
                          : 'Show price breakdown',
                    ),
                  ],
                ),
              ),
            )
          else
            Row(
              children: [
                Expanded(child: Text('Total', style: totalStyle)),
                Text(money(net), style: totalStyle),
              ],
            ),
          const SizedBox(height: 10),
          Row(
            children: [
              clearButton,
              const SizedBox(width: 12),
              Expanded(
                flex: 2,
                child: FilledButton(
                  style: FilledButton.styleFrom(
                    backgroundColor: context.channelAccent.color,
                    foregroundColor: context.channelAccent.onColor,
                    padding: const EdgeInsets.symmetric(vertical: 16),
                  ),
                  onPressed: items.isEmpty
                      ? null
                      : () => context.go('/pos/tender'),
                  child: Text(
                    'Charge ${money(net)}',
                    style: const TextStyle(fontSize: 16),
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

/// Search + pick a customer to attach to the sale (or clear to walk-in).
class _CustomerPickerDialog extends ConsumerStatefulWidget {
  const _CustomerPickerDialog();

  @override
  ConsumerState<_CustomerPickerDialog> createState() =>
      _CustomerPickerDialogState();
}

class _CustomerPickerDialogState extends ConsumerState<_CustomerPickerDialog> {
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(customersProvider);
    return Dialog(
      child: SizedBox(
        width: 460,
        height: 520,
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: SearchBar(
                      autoFocus: true,
                      hintText: 'Search name, email or phone…',
                      leading: const Icon(Icons.search),
                      onChanged: (v) =>
                          setState(() => _query = v.trim().toLowerCase()),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
                    tooltip: 'Close',
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
            ),
            ListTile(
              leading: const Icon(Icons.person_off_outlined),
              title: const Text('Walk-in customer (no account)'),
              onTap: () => Navigator.pop(
                context,
                const Customer(
                  id: '',
                  email: '',
                  firstName: '',
                  lastName: '',
                  status: '',
                ),
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: async.when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (e, _) => Center(
                  child: Text(
                    friendlyError(e, fallback: 'Could not load customers.'),
                    textAlign: TextAlign.center,
                  ),
                ),
                data: (all) {
                  final list = _query.isEmpty
                      ? all
                      : all.where((c) {
                          final hay =
                              '${c.fullName} ${c.email} ${c.phone ?? ''}'
                                  .toLowerCase();
                          return hay.contains(_query);
                        }).toList();
                  if (list.isEmpty) {
                    return const EmptyState(
                      icon: Icons.search_off,
                      title: 'No customers match.',
                    );
                  }
                  return ListView.separated(
                    itemCount: list.length,
                    separatorBuilder: (_, _) => const Divider(height: 1),
                    itemBuilder: (_, i) {
                      final c = list[i];
                      return ListTile(
                        leading: const Icon(Icons.person_outline),
                        title: Text(c.fullName.isEmpty ? c.email : c.fullName),
                        subtitle: Text(
                          [
                            c.email,
                            c.phone,
                          ].where((e) => e != null && e.isNotEmpty).join(' · '),
                        ),
                        onTap: () => Navigator.pop(context, c),
                      );
                    },
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}
