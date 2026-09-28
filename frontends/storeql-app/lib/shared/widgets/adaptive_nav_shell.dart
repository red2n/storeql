import 'package:flutter/foundation.dart' show defaultTargetPlatform;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/input_mode.dart';
import '../../core/spacing.dart';

class AdaptiveNavDestination {
  final String label;
  final IconData icon;
  final IconData selectedIcon;

  /// Count shown on the destination's icon; zero or null shows no badge. Used by
  /// POS to surface sales still waiting to reach the server from wherever the
  /// cashier happens to be in the terminal.
  final int? badgeCount;

  /// The group this destination is listed under (*Sell*, *Money*). Give every
  /// destination of a shell a section when it has more than a rail holds
  /// (about seven): the shell then lists them under headings in a navigation
  /// drawer, with a filter box, instead of in a rail. Destinations of one
  /// section must be next to each other.
  final String? section;

  const AdaptiveNavDestination({
    required this.label,
    required this.icon,
    required this.selectedIcon,
    this.badgeCount,
    this.section,
  });

  /// The destination's icon, badged when it has a non-zero count.
  Widget iconWidget({bool selected = false}) {
    final icon = Icon(selected ? selectedIcon : this.icon);
    final count = badgeCount ?? 0;
    return count > 0 ? Badge.count(count: count, child: icon) : icon;
  }
}

/// How the shell presents navigation below the [AdaptiveNavShell._railBreakpoint].
enum CompactNavStyle {
  /// A [NavigationDrawer] toggled from the app-bar leading icon — for shells
  /// with more destinations than a bottom bar comfortably holds (e.g. Admin).
  drawer,

  /// A persistent bottom [NavigationBar] — Material's recommended pattern for
  /// up to five top-level destinations on a phone (e.g. Storefront, POS).
  bottomBar,
}

/// Responsive navigation. On wide layouts (tablet / desktop / web) a persistent
/// [NavigationRail] sits beside the content and the app-bar icon expands/collapses
/// its labels. On phones it collapses to either a [NavigationDrawer] (toggled by
/// that same app-bar icon) or a bottom [NavigationBar], per [compactStyle].
///
/// A shell whose destinations carry sections (Admin, with ~30 pages) is laid out
/// differently, because no rail holds that many: from [AppBreakpoints.large] a
/// labelled navigation panel with section headings stays beside the content
/// (the app-bar icon hides and shows it); below that the same list opens as a
/// drawer. Either way the selected page is scrolled into view, and a *Find a
/// page* box filters the list — focused with Ctrl+K (⌘K) on a keyboard.
class AdaptiveNavShell extends StatefulWidget {
  final String title;
  final List<AdaptiveNavDestination> destinations;
  final int selectedIndex;
  final ValueChanged<int> onDestinationSelected;
  final Widget child;
  final List<Widget> actions;

  /// Icon shown at the start of the app bar; tapping it toggles the drawer.
  /// Unused when [compactStyle] is [CompactNavStyle.bottomBar].
  final IconData leadingIcon;

  /// Optional app-bar theming (used e.g. by the POS shell's accent colour).
  final Color? appBarBackgroundColor;
  final Color? appBarForegroundColor;

  final CompactNavStyle compactStyle;

  const AdaptiveNavShell({
    super.key,
    required this.title,
    required this.destinations,
    required this.selectedIndex,
    required this.onDestinationSelected,
    required this.child,
    this.actions = const [],
    this.leadingIcon = Icons.storefront_rounded,
    this.appBarBackgroundColor,
    this.appBarForegroundColor,
    this.compactStyle = CompactNavStyle.drawer,
  });

  @override
  State<AdaptiveNavShell> createState() => _AdaptiveNavShellState();
}

/// Below this width the shell collapses to a drawer (phones); at or above it a
/// persistent [NavigationRail] is shown (tablets / desktop / web), per Material 3.
const double _railBreakpoint = AppBreakpoints.rail;

/// Width of the sectioned navigation panel beside the content.
const double _panelWidth = 288;

class _AdaptiveNavShellState extends State<AdaptiveNavShell> {
  final _scaffoldKey = GlobalKey<ScaffoldState>();

  /// The sectioned list's *Find a page* box, focused by Ctrl+K / ⌘K.
  final _findFocus = FocusNode(debugLabel: 'Find a page');

  /// On wide layouts the rail is always visible; this toggles icon-only ⇄ labelled.
  /// Null until the person toggles it: then the rail starts labelled in a desktop-
  /// width window ([AppBreakpoints.large]) and icon-only on tablets.
  bool? _railExtended;

  /// Sectioned shells from [AppBreakpoints.large]: whether the panel beside the
  /// content is showing. It starts shown.
  bool _panelOpen = true;

  /// Whether the last layout put the sectioned panel beside the content.
  bool _panelLayout = false;

  bool get _sectioned => widget.destinations.any((d) => d.section != null);

  @override
  void initState() {
    super.initState();
    // A keyboard handler rather than Shortcuts: it works wherever focus is,
    // including before anything on the page has been clicked.
    HardwareKeyboard.instance.addHandler(_onKey);
  }

  @override
  void dispose() {
    HardwareKeyboard.instance.removeHandler(_onKey);
    _findFocus.dispose();
    super.dispose();
  }

  /// Ctrl+K (⌘K on Apple platforms) finds a page in a sectioned shell.
  bool _onKey(KeyEvent event) {
    if (!_sectioned ||
        event is! KeyDownEvent ||
        event.logicalKey != LogicalKeyboardKey.keyK) {
      return false;
    }
    final keyboard = HardwareKeyboard.instance;
    final command =
        applePlatform ? keyboard.isMetaPressed : keyboard.isControlPressed;
    // Not while a dialog or another page is on top of the shell.
    if (!command || !mounted || !(ModalRoute.of(context)?.isCurrent ?? true)) {
      return false;
    }
    _findPage();
    return true;
  }

  void _toggleDrawer() {
    final state = _scaffoldKey.currentState;
    if (state == null) return;
    if (state.isDrawerOpen) {
      Navigator.of(context).pop();
    } else {
      state.openDrawer();
    }
  }

  /// Ctrl+K / ⌘K: the panel's find box when the panel is beside the content,
  /// otherwise the drawer, opened with its find box focused.
  void _findPage() {
    if (_panelLayout) {
      if (!_panelOpen) setState(() => _panelOpen = true);
    } else {
      final state = _scaffoldKey.currentState;
      if (state != null && !state.isDrawerOpen) state.openDrawer();
    }
    // The box exists once the panel or drawer has been built.
    WidgetsBinding.instance
        .addPostFrameCallback((_) => _findFocus.requestFocus());
    // A post-frame callback doesn't schedule a frame, and with the panel
    // already open nothing else does: ask for one.
    WidgetsBinding.instance.ensureVisualUpdate();
  }

  // onLeading == null omits the leading toggle icon entirely (bottom-bar mode
  // has no drawer to open, so there's nothing for it to control).
  PreferredSizeWidget _appBar({VoidCallback? onLeading, String? leadingTooltip}) {
    return AppBar(
      backgroundColor: widget.appBarBackgroundColor,
      foregroundColor: widget.appBarForegroundColor,
      // Replace the automatic hamburger with the product icon as the toggle.
      automaticallyImplyLeading: false,
      leading: onLeading == null
          ? null
          : IconButton(
              icon: Icon(widget.leadingIcon),
              tooltip: leadingTooltip ?? 'Toggle menu',
              onPressed: onLeading,
            ),
      title: Text(widget.title, overflow: TextOverflow.ellipsis),
      actions: widget.actions,
    );
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      _panelLayout =
          _sectioned && constraints.maxWidth >= AppBreakpoints.large;
      if (_sectioned) {
        return _panelLayout ? _buildPanel(context) : _buildNarrow(context);
      }
      if (constraints.maxWidth >= _railBreakpoint) {
        return _buildWide(context, constraints.maxWidth);
      }
      return widget.compactStyle == CompactNavStyle.bottomBar
          ? _buildBottomBar(context)
          : _buildNarrow(context);
    });
  }

  // Phones (drawer style): nav lives in a drawer toggled from the app-bar icon.
  // Sectioned shells use it up to desktop width.
  Widget _buildNarrow(BuildContext context) {
    return Scaffold(
      key: _scaffoldKey,
      appBar: _appBar(onLeading: _toggleDrawer),
      drawer: _sectioned
          ? _SectionedNav(
              title: widget.title,
              leadingIcon: widget.leadingIcon,
              destinations: widget.destinations,
              selectedIndex: widget.selectedIndex,
              onSelected: widget.onDestinationSelected,
              findFocus: _findFocus,
              inDrawer: true,
            )
          : _buildDrawer(context),
      body: widget.child,
    );
  }

  // Phones (bottom-bar style): nav lives in a persistent NavigationBar — the
  // Material-recommended pattern for a small, flat set of destinations.
  Widget _buildBottomBar(BuildContext context) {
    return Scaffold(
      appBar: _appBar(),
      body: widget.child,
      bottomNavigationBar: NavigationBar(
        selectedIndex: widget.selectedIndex,
        onDestinationSelected: widget.onDestinationSelected,
        destinations: widget.destinations
            .map((d) => NavigationDestination(
                  icon: d.iconWidget(),
                  selectedIcon: d.iconWidget(selected: true),
                  label: d.label,
                ))
            .toList(),
      ),
    );
  }

  // Tablet / desktop / web: a persistent rail beside the content. The app-bar
  // icon expands/collapses it (labels beside icons vs. under them).
  Widget _buildWide(BuildContext context, double width) {
    final extended = _railExtended ?? width >= AppBreakpoints.large;
    return Scaffold(
      appBar: _appBar(
        onLeading: () => setState(() => _railExtended = !extended),
      ),
      body: Row(
        children: [
          LayoutBuilder(
            builder: (context, c) => SingleChildScrollView(
              // Not the primary scroll view: that is the page's, so a status-bar
              // tap on iOS scrolls the page, not the rail.
              primary: false,
              child: ConstrainedBox(
                constraints: BoxConstraints(minHeight: c.maxHeight),
                child: IntrinsicHeight(
                  child: NavigationRail(
                    extended: extended,
                    selectedIndex: widget.selectedIndex,
                    onDestinationSelected: widget.onDestinationSelected,
                    labelType: extended
                        ? NavigationRailLabelType.none
                        : NavigationRailLabelType.all,
                    destinations: widget.destinations
                        .map((d) => NavigationRailDestination(
                              icon: d.iconWidget(),
                              selectedIcon: d.iconWidget(selected: true),
                              label: Text(d.label),
                            ))
                        .toList(),
                  ),
                ),
              ),
            ),
          ),
          const VerticalDivider(width: 1),
          Expanded(child: widget.child),
        ],
      ),
    );
  }

  // Desktop, sectioned shells: the labelled list stays beside the content; the
  // app-bar icon hides it for more room and brings it back.
  Widget _buildPanel(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: _appBar(
        onLeading: () => setState(() => _panelOpen = !_panelOpen),
        leadingTooltip: _panelOpen ? 'Hide menu' : 'Show menu',
      ),
      body: Row(
        children: [
          if (_panelOpen) ...[
            SizedBox(
              width: _panelWidth,
              // A standard (non-modal) drawer: flat, square, page-coloured.
              child: Theme(
                data: theme.copyWith(
                  drawerTheme: theme.drawerTheme.copyWith(
                    width: _panelWidth,
                    elevation: 0,
                    shape: const RoundedRectangleBorder(),
                    backgroundColor: theme.colorScheme.surface,
                  ),
                ),
                child: PrimaryScrollController.none(
                  child: _SectionedNav(
                    title: widget.title,
                    leadingIcon: widget.leadingIcon,
                    destinations: widget.destinations,
                    selectedIndex: widget.selectedIndex,
                    onSelected: widget.onDestinationSelected,
                    findFocus: _findFocus,
                    inDrawer: false,
                  ),
                ),
              ),
            ),
            const VerticalDivider(width: 1),
          ],
          Expanded(child: widget.child),
        ],
      ),
    );
  }

  Widget _buildDrawer(BuildContext context) {
    return NavigationDrawer(
      selectedIndex: widget.selectedIndex,
      onDestinationSelected: (i) {
        Navigator.of(context).pop(); // undock after picking
        widget.onDestinationSelected(i);
      },
      children: [
        _DrawerHeading(title: widget.title, icon: widget.leadingIcon),
        ...widget.destinations.map((d) => NavigationDrawerDestination(
              icon: d.iconWidget(),
              selectedIcon: d.iconWidget(selected: true),
              label: Text(d.label),
            )),
      ],
    );
  }
}

class _DrawerHeading extends StatelessWidget {
  final String title;
  final IconData icon;

  const _DrawerHeading({required this.title, required this.icon});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsetsDirectional.fromSTEB(28, 24, 16, 16),
      child: Row(
        children: [
          Icon(icon),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              title,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.titleMedium,
            ),
          ),
        ],
      ),
    );
  }
}

/// The sectioned destination list — in the desktop panel and in the drawer.
class _SectionedNav extends StatefulWidget {
  final String title;
  final IconData leadingIcon;
  final List<AdaptiveNavDestination> destinations;
  final int selectedIndex;
  final ValueChanged<int> onSelected;
  final FocusNode findFocus;

  /// In a modal drawer: close it after a pick.
  final bool inDrawer;

  const _SectionedNav({
    required this.title,
    required this.leadingIcon,
    required this.destinations,
    required this.selectedIndex,
    required this.onSelected,
    required this.findFocus,
    required this.inDrawer,
  });

  @override
  State<_SectionedNav> createState() => _SectionedNavState();
}

class _SectionedNavState extends State<_SectionedNav> {
  final _find = TextEditingController();
  final _scroll = ScrollController();
  final _selectedKey = GlobalKey();
  String _query = '';

  // Row heights of the list, for placing the selected page before it is
  // built (the drawer's list builds lazily, so a row below the fold has no
  // context to scroll to yet). The title and find box are the drawer's
  // header, above the list, so they don't count.
  static const double _sectionHeight = 44; // section heading
  static const double _dividerHeight = 9;
  static const double _rowHeight = 56; // NavigationDrawerDestination

  // Room for a label: the panel is 288 wide (the drawer 304), less 24 tile
  // padding, 16 + 24 + 12 for the icon, and a little end space.
  static const double _labelWidth = 196;

  @override
  void initState() {
    super.initState();
    _revealSelectedAfterLayout();
  }

  @override
  void didUpdateWidget(_SectionedNav old) {
    super.didUpdateWidget(old);
    if (old.selectedIndex != widget.selectedIndex) _revealSelectedAfterLayout();
  }

  @override
  void dispose() {
    _find.dispose();
    _scroll.dispose();
    super.dispose();
  }

  /// Where the selected row starts in the unfiltered list.
  double? _estimatedTop(int selected) {
    var y = 0.0;
    String? section;
    for (var i = 0; i < widget.destinations.length; i++) {
      final d = widget.destinations[i];
      if (d.section != null && d.section != section) {
        if (section != null) y += _dividerHeight;
        y += _sectionHeight;
        section = d.section;
      }
      if (i == selected) return y;
      y += _rowHeight;
    }
    return null;
  }

  /// Twenty-odd destinations run past the fold; the one you are on should
  /// never be hidden below it. Jump near it, then line it up once built.
  void _revealSelectedAfterLayout() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients || _query.isNotEmpty) return;
      final position = _scroll.position;
      final top = _estimatedTop(widget.selectedIndex);
      if (top == null) return;
      final shownFrom = position.pixels;
      final shownTo = shownFrom + position.viewportDimension;
      if (top >= shownFrom && top + _rowHeight <= shownTo) return;
      _scroll.jumpTo((top - position.viewportDimension * 0.3)
          .clamp(0.0, position.maxScrollExtent));
      WidgetsBinding.instance.addPostFrameCallback((_) {
        final target = _selectedKey.currentContext;
        if (mounted && target != null) {
          Scrollable.ensureVisible(target, alignment: 0.3);
        }
      });
    });
  }

  void _pick(int index) {
    if (widget.inDrawer) Navigator.of(context).pop();
    if (_query.isNotEmpty) {
      _find.clear();
      setState(() => _query = '');
    }
    widget.onSelected(index);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final q = _query.trim().toLowerCase();
    final visible = <int>[
      for (var i = 0; i < widget.destinations.length; i++)
        if (q.isEmpty ||
            widget.destinations[i].label.toLowerCase().contains(q) ||
            (widget.destinations[i].section?.toLowerCase().contains(q) ??
                false))
          i,
    ];
    final selectedPosition = visible.indexOf(widget.selectedIndex);

    // The title and the find box stay put above the scrolling list.
    final header = Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _DrawerHeading(title: widget.title, icon: widget.leadingIcon),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
          child: TextField(
            controller: _find,
            focusNode: widget.findFocus,
            textInputAction: TextInputAction.go,
            decoration: InputDecoration(
              isDense: true,
              prefixIcon: const Icon(Icons.search),
              hintText: 'Find a page',
              // Empty: the shortcut, on a keyboard platform. Typed in: clear.
              suffixIcon: _query.isNotEmpty
                  ? IconButton(
                      tooltip: 'Clear',
                      icon: const Icon(Icons.close),
                      onPressed: () {
                        _find.clear();
                        setState(() => _query = '');
                      },
                    )
                  : pointerFirst
                      ? Padding(
                          padding: const EdgeInsetsDirectional.only(end: 12),
                          child: Center(
                            widthFactor: 1,
                            child: Text(
                              shortcutLabel('K'),
                              style: theme.textTheme.labelMedium?.copyWith(
                                  color: theme.colorScheme.onSurfaceVariant),
                            ),
                          ),
                        )
                      : null,
            ),
            onChanged: (v) => setState(() => _query = v),
            // Enter opens the first match, so the keyboard alone gets anywhere.
            onSubmitted: (_) {
              if (visible.isNotEmpty) _pick(visible.first);
            },
          ),
        ),
      ],
    );

    final children = <Widget>[];

    String? section;
    for (final i in visible) {
      final d = widget.destinations[i];
      // Headings only for the full list; a filtered list is short and flat.
      if (q.isEmpty && d.section != null && d.section != section) {
        if (section != null) {
          children.add(const Padding(
            padding: EdgeInsets.fromLTRB(28, 8, 28, 0),
            child: Divider(height: 1),
          ));
        }
        children.add(Padding(
          padding: const EdgeInsetsDirectional.fromSTEB(28, 16, 16, 8),
          child: Text(
            d.section!,
            style: theme.textTheme.titleSmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ));
        section = d.section;
      }
      children.add(NavigationDrawerDestination(
        key: i == widget.selectedIndex ? _selectedKey : null,
        icon: d.iconWidget(),
        selectedIcon: d.iconWidget(selected: true),
        // The destination lays its label out unbounded in a Row, so a long
        // label at large text sizes would overflow; give it the room the
        // drawer has (its width less the icon and paddings) and ellipsize.
        label: SizedBox(
          width: _labelWidth,
          child: Text(d.label, maxLines: 1, overflow: TextOverflow.ellipsis),
        ),
      ));
    }
    if (visible.isEmpty) {
      children.add(Padding(
        padding: const EdgeInsetsDirectional.fromSTEB(28, 16, 16, 16),
        child: Text(
          'No page matches “${_query.trim()}”.',
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
      ));
    }
    children.add(const SizedBox(height: 16));

    // The drawer's list takes this controller as its primary one — on this
    // platform too, not only on phones — so the selected row can be scrolled to.
    return PrimaryScrollController(
      controller: _scroll,
      automaticallyInheritForPlatforms: {defaultTargetPlatform},
      child: NavigationDrawer(
        header: header,
        // Beside the content it is a standard drawer: flat, on the page colour
        // like the rail. As a modal drawer it keeps its raised surface.
        backgroundColor: widget.inDrawer ? null : theme.colorScheme.surface,
        elevation: widget.inDrawer ? null : 0,
        selectedIndex: selectedPosition < 0 ? null : selectedPosition,
        onDestinationSelected: (position) => _pick(visible[position]),
        children: children,
      ),
    );
  }
}
