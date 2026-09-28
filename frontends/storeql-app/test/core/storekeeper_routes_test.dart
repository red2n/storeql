import 'package:flutter_test/flutter_test.dart';
import 'package:storeql_app/core/router.dart';
import 'package:storeql_app/features/admin/admin_shell.dart';

// ---------------------------------------------------------------------------
// A storekeeper's back-office menu and the router agree: every page the menu
// offers a storekeeper is one the router lets them open, and a page whose
// reads are management-only (age checks) is offered to neither. The
// obligations register and the shelf-gap report are staff reads, so a
// storekeeper may open both (Shelf space shows the Gaps tab only there).
// ---------------------------------------------------------------------------

void main() {
  test('every page the menu offers a storekeeper, the router lets them open', () {
    expect(storekeeperAdminRoutes, isNotEmpty);
    for (final route in storekeeperAdminRoutes) {
      expect(storekeeperAdminAllowed(route), isTrue, reason: '$route is in the menu but the router refuses it');
    }
  });

  test('a management-only page is not offered, and the staff-readable registers are', () {
    expect(storekeeperAdminRoutes, isNot(contains('/admin/age-checks')));
    expect(storekeeperAdminAllowed('/admin/age-checks'), isFalse);
    expect(storekeeperAdminRoutes, contains('/admin/obligations'));
    expect(storekeeperAdminAllowed('/admin/obligations'), isTrue);
    expect(storekeeperAdminRoutes, contains('/admin/shelf-space'));
    expect(storekeeperAdminAllowed('/admin/shelf-space'), isTrue);
  });
}
