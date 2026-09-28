package com.storeql.web;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The permission catalogue (20.10): the named decisions a tenant may take away from a role.
 *
 * <p>The five built-in roles stay as tiers — the shared filter gates whole subtrees by tier, and
 * that is not changing. What a tenant may now do is define a role <em>on</em> a tier that holds
 * fewer of that tier's permissions: a shift lead who is a manager in every way but cannot void a
 * sale or post a journal; a trainee who is a cashier who cannot open the drawer without a sale. A
 * custom role can only narrow the tier it stands on, never widen it, so nothing a tier refuses by
 * path becomes reachable by naming a permission.
 *
 * <p>Each permission is checked at the one place the action happens, with {@link
 * TenantContext#requirePermission(String)}. A login whose token carries no permission claim — an
 * owner, a platform admin, or a token minted before the claim existed — is judged by its tier's
 * defaults, so nothing that worked yesterday stops working today.
 */
public final class Permissions {

  private Permissions() {}

  public static final String SALES_VOID = "sales.void";
  public static final String SALES_REFUND = "sales.refund";
  public static final String TILL_NO_SALE = "till.no_sale";
  public static final String TILL_MANAGE = "till.manage";
  public static final String STOCK_ADJUST = "stock.adjust";
  public static final String STOCK_TRANSFER = "stock.transfer";
  public static final String PURCHASING_APPROVE = "purchasing.approve";
  public static final String PURCHASING_INVOICES_DECIDE = "purchasing.invoices.decide";
  public static final String FINANCE_JOURNAL = "finance.journal";
  public static final String PRICING_WRITE = "pricing.write";
  public static final String CUSTOMERS_PRIVACY = "customers.privacy";
  public static final String STAFF_MANAGE = "staff.manage";
  public static final String FINANCE_PAYMENTS = "finance.payments";

  /** Every permission, with the sentence a screen shows beside its checkbox. */
  private static final Map<String, String> CATALOGUE;

  static {
    Map<String, String> m = new LinkedHashMap<>();
    m.put(SALES_VOID, "Void a completed till sale, putting the stock back");
    m.put(SALES_REFUND, "Record a refund against an order");
    m.put(TILL_NO_SALE, "Open the cash drawer without a sale");
    m.put(TILL_MANAGE, "Close a till, record cash drops and movements, run the Z report");
    m.put(STOCK_ADJUST, "Adjust stock levels and write stock off");
    m.put(
        STOCK_TRANSFER,
        "Move stock between stores and zones: raise, ship, receive and cancel transfer and move"
            + " orders");
    m.put(
        PURCHASING_APPROVE,
        "Approve a purchase order awaiting approval, within the spend ceiling configured for the"
            + " role");
    m.put(PURCHASING_INVOICES_DECIDE, "Approve or reject a flagged supplier invoice");
    m.put(FINANCE_JOURNAL, "Post a manual journal to the nominal ledger");
    m.put(PRICING_WRITE, "Create and change price lists");
    m.put(CUSTOMERS_PRIVACY, "Export or erase a customer's personal data");
    m.put(STAFF_MANAGE, "Assign and remove staff, and define roles");
    m.put(
        FINANCE_PAYMENTS,
        "Propose, approve and pay supplier payment runs, and change a supplier's bank details");
    CATALOGUE = Map.copyOf(m);
  }

  /** The permission codes, in catalogue order. */
  public static final Set<String> ALL = Set.copyOf(CATALOGUE.keySet());

  /** The tiers a custom role may stand on. Owners are not narrowed; customers hold nothing. */
  public static final Set<String> TIERS = Set.of("MANAGER", "STOREKEEPER", "CASHIER");

  private static final Set<String> MANAGER_DEFAULTS = ALL;

  // Purchase approval is held by every staff tier by default because the spend ceiling, not the
  // tier, decides who may approve how much (storeql.purchase.approval.limits names roles and
  // amounts, and a storekeeper with a ceiling is a legitimate approver). The permission is what a
  // custom role can take away; the ceiling is what it still has to clear.
  // Moving stock is the storekeeper's job and never the till's (SJ-D73: a cashier could raise and
  // ship a transfer to another store, because nothing under /admin/inventory asked who they were).
  private static final Set<String> STOREKEEPER_DEFAULTS =
      Set.of(STOCK_ADJUST, STOCK_TRANSFER, PURCHASING_APPROVE);
  private static final Set<String> CASHIER_DEFAULTS = Set.of(TILL_NO_SALE, PURCHASING_APPROVE);

  /**
   * The catalogue: each code with the sentence describing it, in a stable order.
   *
   * @return an unmodifiable, ordered map
   */
  public static Map<String, String> catalogue() {
    return CATALOGUE;
  }

  /**
   * Whether the code names a permission.
   *
   * @param code the candidate
   * @return {@code true} when it is in the catalogue
   */
  public static boolean isKnown(String code) {
    return code != null && CATALOGUE.containsKey(code);
  }

  /**
   * What a built-in role holds by default: everything for a manager, stock work for a storekeeper,
   * the drawer for a cashier — both of the latter with purchase approval, which the spend ceiling
   * then bounds — and nothing for a customer or an unknown role.
   *
   * @param role a role name as carried in the token
   * @return the role's default permissions; unmodifiable
   */
  public static Set<String> defaultsFor(String role) {
    if (role == null) return Set.of();
    return switch (role) {
      case "PLATFORM_ADMIN", "OWNER", "MANAGER" -> MANAGER_DEFAULTS;
      case "STOREKEEPER" -> STOREKEEPER_DEFAULTS;
      case "CASHIER" -> CASHIER_DEFAULTS;
      default -> Set.of();
    };
  }

  /**
   * The union of the defaults of every role held.
   *
   * @param roles the roles carried in the token
   * @return the permissions those roles hold by default
   */
  public static Set<String> effective(Set<String> roles) {
    Set<String> out = new LinkedHashSet<>();
    if (roles != null) {
      for (String r : roles) out.addAll(defaultsFor(r));
    }
    return Set.copyOf(out);
  }

  /**
   * Whether a holder of these roles is never narrowed: the tenant's owner and the platform.
   *
   * @param roles the roles carried in the token
   * @return {@code true} for an owner or a platform admin
   */
  public static boolean unrestricted(Set<String> roles) {
    return roles != null && (roles.contains("OWNER") || roles.contains("PLATFORM_ADMIN"));
  }
}
