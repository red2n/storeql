# StoreQL

**The platform for running a retail business — stock, stores, and sales, online and in person, for as many independent businesses as want to use it.**

> This document is a product tour: what StoreQL does and how someone actually uses it, top to bottom. It deliberately says nothing about how it's built. For that side, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (engineering reference), [docs/API-GUIDE.md](docs/API-GUIDE.md) (the full API surface by business capability), and [docs/UI-GUIDE.md](docs/UI-GUIDE.md) (the full screen-by-screen UI tour). Product requirements and roadmap live in [PRD.md](PRD.md).

---

## Table of contents

1. [What StoreQL is](#1-what-storeql-is)
2. [Who uses it](#2-who-uses-it)
3. [The shape of a business on StoreQL: Tenant, Store, Zone](#3-the-shape-of-a-business-on-storeql-tenant-store-zone)
4. [One platform, four experiences](#4-one-platform-four-experiences)
5. [Setting up a business](#5-setting-up-a-business)
6. [The product catalog](#6-the-product-catalog)
7. [Inventory & warehouse operations](#7-inventory--warehouse-operations)
8. [Pricing, promotions & tax](#8-pricing-promotions--tax)
9. [Buying from suppliers](#9-buying-from-suppliers)
10. [Selling online: the storefront](#10-selling-online-the-storefront)
11. [Selling in person: the POS](#11-selling-in-person-the-pos)
12. [Orders, returns & alternative sales](#12-orders-returns--alternative-sales)
13. [Customers, loyalty & store credit](#13-customers-loyalty--store-credit)
14. [Reporting](#14-reporting)
15. [Running the platform itself](#15-running-the-platform-itself)
16. ["Catalog mode" — selling without showing a price](#16-catalog-mode--selling-without-showing-a-price)
17. [Three everyday workflows, start to finish](#17-three-everyday-workflows-start-to-finish)
18. [Multi-tenancy: what "isolated" actually means](#18-multi-tenancy-what-isolated-actually-means)
19. [Reach, languages & currencies](#19-reach-languages--currencies)
20. [Where to go next](#20-where-to-go-next)

---

## 1. What StoreQL is

A business that sells physical goods usually ends up stitching together two or three separate systems: one for inventory and purchasing, one for the till in the shop, and often a completely different one for an online store — none of which agree with each other about what's actually in stock. StoreQL is built to be all three at once, sharing one live picture of stock, one catalog, one set of customers, and one set of orders regardless of whether a sale happened on a phone at home or at a counter in a shop.

Concretely, a business running on StoreQL can:

- Track **what stock it has and exactly where** — down to the batch/lot, the serial number, and the shelf/aisle it's sitting on.
- **Buy stock from suppliers** against purchase orders and record what actually arrives.
- **Sell at the counter** with a full point-of-sale: scanning, split payments, till management, receipts, held sales, layaway, gift cards.
- **Sell the same catalog online**, on a public storefront customers can browse and check out on without any staff involvement.
- **Plan what to reorder and when**, from simple thresholds up to reorder-point/EOQ math, kanban cards, and ABC-classified cycle counts.
- Handle **UK-style VAT**, multi-price-list pricing, and time-bounded promotions.
- Run **loyalty points and store credit** for repeat customers.
- See **cross-store reports** of what sold, what's low, and what's in transit.

It's **multi-tenant**: many separate businesses share the same platform, each with their own catalog, stock, staff, and customers, and none of them can see or affect another's data (see [§18](#18-multi-tenancy-what-isolated-actually-means)). And it's built so that **one online sale and one in-store sale go through the exact same order, payment, and inventory logic** — a business never has to reconcile two different systems that happened to disagree about how many units are left.

---

## 2. Who uses it

| Persona | What they're here to do | Where they work |
|---|---|---|
| **Platform Admin** | Runs StoreQL itself as a service: onboards and monitors the businesses (tenants) using the platform, and can suspend one if needed. Not affiliated with any one business. | Platform Console |
| **Owner** | Owns a business on the platform. Full control over their stores, catalog, pricing, staff, and reporting. Automatically granted this role the moment they finish onboarding. | Admin Console |
| **Manager / Store Admin** | Runs one or more of the business's stores day to day: stock, purchasing, pricing, staff scheduling, reports. | Admin Console |
| **Storekeeper** | Receives and adjusts stock, runs cycle counts, manages batches/zones. | Admin Console |
| **Cashier** | Rings up in-store sales, handles tenders and returns, opens/closes the till. | POS |
| **Customer** | Browses the public storefront and buys — with or without creating an account. | Storefront |

A business owner signs up once, is walked through a short setup wizard, and lands as the **Owner** of their own tenant with their first store already created (see [§5](#5-setting-up-a-business)). From there they invite the rest of their team into whichever role fits.

---

## 3. The shape of a business on StoreQL: Tenant, Store, Zone

Every business on the platform is modeled the same way, three levels deep:

```
Tenant (the business itself — e.g. "Riverside Grocers")
  └── Store or Warehouse (a physical site — has an address, geo-location, hours, and a type)
        └── Zone (a place inside that site — an aisle, a rack, a cold room, the back store)
```

- A **tenant** is one business. It can own as many **stores** as it likes — a corner shop chain might have five retail stores and one warehouse, all under the same tenant.
- Every **store** has its own address, coordinates, opening hours, and a type (`STORE` for a retail site, `WAREHOUSE` for a non-selling one), plus its own settings — which payment methods it accepts, and whether it shows prices at all (see [§16](#16-catalog-mode--selling-without-showing-a-price)).
- Every store is broken into **zones** — its internal map (aisle, rack, cold room, receiving, back store, display) — so a batch of stock isn't just "at Store #2," it's "at Store #2, Cold Room, Rack 3."
- **Everything transactional is scoped to a store**: stock levels, prices, orders, and POS sales all happen in the context of one specific store. A customer shopping online picks (or is defaulted into) a store; a cashier is clocked in at one.
- Onboarding always creates a first store and a first zone automatically, so a brand-new business can receive stock and take a sale immediately — adding more stores and zones later is a two-minute admin task, not a project.

---

## 4. One platform, four experiences

Everyone — platform operator, business owner, cashier, or customer — ultimately uses the same underlying application, just through the door that matches their role:

| Experience | Who it's for | What it's for |
|---|---|---|
| **Storefront** | Customers (guest or signed in) | Browse the catalog, add to cart, check out for pickup or delivery, track past orders. Reachable by anyone, no login required to browse. |
| **POS** | Cashiers, managers | Ring up sales at the counter: scan, tender, hold/resume, manage the till. |
| **Admin Console** | Owners, managers, storekeepers | Everything about running the business: catalog, inventory, pricing, purchasing, staff, stores, orders across every channel, customers, reporting. |
| **Platform Console** | Platform admins | Onboard and monitor every business (tenant) on the platform; activate or suspend one. |

A person's role decides which of these they land in immediately after signing in, and the Admin/POS/Platform experiences are locked to the roles that should see them — a cashier can't wander into the Admin Console, and a Platform Admin's login is entirely separate from any business's staff login, so the two credentials can never be confused or reused for each other.

A business also defines **roles of its own** on those tiers, each holding fewer of the tier's permissions: a shift lead who is a manager in every way but cannot void a sale or post a journal, a trainee who is a cashier who cannot open the drawer without a sale. A role can only narrow the tier it stands on, never widen it; the login's token carries what the role holds, every gated decision refuses a narrowed role by name, a role redefined reaches its holders at their next sign-in, and a role cannot be deleted while anyone holds it.

---

## 5. Setting up a business

Getting a new business onto the platform is deliberately a two-minute job, either self-serve or done for them:

**Self-serve signup:**
1. Create an account (email + password).
2. **Business details** — business name, optional legal/registered name, country (India, US, UK, Singapore, or UAE today), and currency (auto-suggested from the country, editable).
3. **First store** — store name, a short store code, type (retail store or warehouse), address, city, postal code, country, and timezone.
4. Done — the new Owner lands straight in their Admin Console, with a working store already in place.

**Assisted onboarding:** a Platform Admin can also set a new business up on its owner's behalf — creating the owner's login, then the business and its first store in one form — ending in a hand-off screen showing the credentials to pass along to the new owner.

From there, an Owner/Manager fills the business out at their own pace, all from the Admin Console — not part of the initial wizard:
- **More stores and zones** — add further retail stores or warehouses, each with its own address/timezone/accepted payment methods/"show prices" setting, and lay out each one's internal zones (aisle, rack, cold room, etc.).
- **Staff** — assign a teammate to a store with a role (Owner, Manager, Storekeeper, Cashier). Typing an email either finds an existing account or provisions a brand-new one on the spot; a freshly created account's one-time temporary password is shown exactly once (masked by default, explicit reveal, one-tap copy) for the admin to hand to the new hire.
- **Inventory configuration** — tenant-wide policy for how strictly lots, serials, grading, and costing are tracked.

A deactivated business is locked out everywhere at once — its staff logins, POS, and storefront all stop working immediately, and its storefront shows customers a plain "currently unavailable" notice rather than an error.

---

## 6. The product catalog

The catalog is the shared product truth behind both the storefront and POS — nothing is entered twice.

- **Products & variants** — a product (e.g. "Basmati Rice") holds one or more purchasable variants/SKUs (e.g. "1kg bag," "5kg bag"), each with its own barcode, unit, and price. Variants can be tagged separately as sellable online, sellable at POS, both, or neither ("delisted" without deleting history).
- **Brands & categories** — categories support one level of parent/child nesting for browsing and reporting; brands are a flat tag.
- **Images** — one image per product (JPEG/PNG/WebP, size-limited), shown on both the storefront and the admin catalog.
- **Store assortment** — a product can be sold everywhere (including automatically at any new store opened later), or hand-picked to only appear at specific stores.
- **Barcode scanning** — a camera-based scanner (with a manual-entry fallback) is used consistently everywhere a barcode matters: tagging a new variant's barcode, receiving stock, and ringing up a sale at POS.
- **Bulk import** — a supplier catalogue can be uploaded as a CSV in one go: the system previews what it found (row count, distinct products/categories/stores, whether price and quantity columns are present), lets the admin map CSV store names onto real stores and choose a destination store to stock into, and offers "add to catalog" or "replace existing" modes. One import creates categories, products, prices, and opening stock together, and reports back exactly what succeeded and what didn't, per category of failure.
- **Deeper PIM (product information management) tools** for larger or more regulated catalogs, available underneath the everyday screens: units-of-measure conversions (including variant-specific factors), reusable attribute templates applied to many variants at once, dated revisions of a variant's spec, supplier/customer part-number cross-references, related-item links (substitutes, accessories), merchandising catalog groups, packaging/container types, and an alternate category hierarchy ("category sets") for businesses that need to slice their catalog more than one way.

---

## 7. Inventory & warehouse operations

This is the deepest part of the platform — everything about knowing what stock exists, where it is, and what to do about it.

**The everyday basics**
- **Receive stock** into a store and zone — by scanning or picking a variant, with an optional batch number, cost, and expiry date. Multiple lines can be received in one go.
- **Adjust** on-hand quantity up or down with a reason code (shrinkage, damage, found stock, etc.) — every adjustment is logged, never a silent overwrite.
- **Stock levels** — on-hand, reserved (held for a checkout in progress), and available, per store per variant, with a low-stock flag driven by each variant's configured threshold.
- **Movement history** — a full, append-only ledger of every stock movement, ever.

**Batches, lots & serials**
- Stock is tracked at the **batch/lot** level — quantity, expiry date, quality grade, and a material status (available, quarantined, rejected, on hold) — so a business handling perishables or regulated goods can hold back a bad batch without touching anything else.
- Batches can be **split** (spin off part of a batch) or **merged**, with a full audit trail of who did it and when, and linked into **genealogy** (a repack made from two source batches can be traced back to both parents, and forward to anything made from it).
- Individually **serial-numbered** items (registered explicitly or auto-generated with a prefix) are tracked one by one, with their own status (in stock, sold, defective) and full history.
- A dedicated view surfaces everything **expiring soon** (configurable window) per store.

**Counting stock**
- **Cycle counts** target a slice of the catalog (e.g. just the "A" items in an ABC classification) with a tolerance percentage — counts inside tolerance auto-approve, anything outside is flagged for a human to review before adjustments post.
- **Physical inventory** runs a full, tag-based count of a store from a system-quantity snapshot through to sign-off.
- **ABC analysis** classifies every variant at a store into A/B/C bands by revenue or velocity, which then drives which items get counted most often and how tightly they're planned.

**Planning what to reorder**
- Simple **reorder thresholds** (a floor and a ceiling per store/variant) sit alongside more advanced options: full **reorder-point & EOQ** math (lead time, ordering cost, holding cost, unit cost, lot-size and min/max order constraints), **safety-stock** calculation by service level, **kanban cards** that trigger a replenishment signal when pulled (optionally sourcing from another store), and **PAR levels** for periodic automatic replenishment. A planning run turns any of these into concrete replenishment suggestions, which a buyer can action straight into a purchase order.
- **Demand history** is aggregated from past sales into daily/weekly/monthly buckets to feed all of the above.

**Moving stock around**
- **Transfers** move stock between stores: create → ship from the source → receive at the destination (or cancel before it ships).
- **Move orders** relocate stock between zones inside the same store: create → pick → complete.
- **Picking rules** (e.g. pick the soonest-to-expire batch first, or the oldest-received) can be scoped to a store, category, or specific variant, with a defined zone priority order, and resolved on demand for "which batch/zone should I pick from for this line."

**Costing & the books**
- Each store/variant carries a configured **costing method** (e.g. FIFO or average cost), and postings can be locked behind **accounting periods** once closed: a goods receipt, a supplier invoice, a credit note or a journal dated in a closed month is refused, so a closed month's numbers can't shift underneath finance later. A store can be mapped to a **general-ledger code**, and the ledger uses it — the stock a goods receipt recognises posts to the store's mapped code.

**What the storefront sees:** customers only ever see an in-stock / out-of-stock flag for a store — actual on-hand counts are never exposed publicly.

---

## 8. Pricing, promotions & tax

- **Price lists** are scoped to a channel — one list for in-store, one for online, or one for both — and hold a price (and optional minimum quantity) per variant. Setting many prices at once (e.g. after a bulk import) is a single batch operation.
- **Price resolution** computes the effective price for a variant, channel, and quantity in one call, including a VAT breakdown — this is what both the storefront and POS ask before showing a customer a number, and it's resolved for every line of an order in one shot at checkout.
- **Promotions** are time-bounded (a start date and optional end date), either a percentage or a flat amount off, scoped to all products, a category, or specific items, with an optional minimum order amount to qualify. Live promotions also drive the storefront's rotating offers banner automatically.
- **Price overrides** let staff apply a one-off, approved price change at the counter — logged permanently as an audit trail, never as a silent edit to the real price.
- **VAT** is modelled UK-style: configurable VAT rates (standard/reduced/zero/exempt-style codes), a VAT category per product, and a VAT-exemption/reverse-charge status per business customer. Every sale logs a tax-transaction entry, and a full **VAT return** (matching the UK's Making Tax Digital box structure) can be produced for any date range.

---

## 9. Buying from suppliers

- **Suppliers** are onboarded with a name, country, currency, and VAT-registration flag.
- A **purchase order** starts as a draft, has lines added (variant, quantity, cost, tax rate) with a running total, and is then submitted to the supplier.
- When goods turn up, a **goods receipt** is recorded against the PO — confirming actual quantities received, which is what actually creates receivable stock in the relevant store/zone (see [§7](#7-inventory--warehouse-operations)). Partial and short deliveries are handled the same way, line by line.
- **Promotions** scope to the whole shop, to one variant, or to a **category** — a deal on *Drinks* reaches every product filed under *Soft drinks* beneath it, because the catalogue announces where each product sits and pricing keeps that map — and include **mix-and-match** ("any 3 for £10": the dearest units bundle, the rest are charged in full) beside percentage, amount, basket, threshold and buy-X-get-Y deals.
- The **nominal ledger** is written by the documents: a goods receipt recognises the stock against a goods-received-not-invoiced accrual; a supplier invoice posts the creditor (and VAT input) against that accrual, dated the invoice, due by the supplier's terms, whether or not it matched — a variance blocks *payment*, not the posting; a manager **approves** a flagged invoice for payment or **rejects** it, a rejection reversing the posting line for line and taking the invoice back out of the VAT return; a supplier's credit note reverses the creditor. Finance posts **manual journals** that must balance, reads any journal whole, and reads the **trial balance** per store or for the whole business. For businesses with related entities trading stock between themselves, **intercompany invoices** raise a matched receivable/payable pair for an inter-org transfer and can be settled once paid, on the same ledger.

---

## 10. Selling online: the storefront

The storefront is a normal online shop, reachable without an account, that always reflects the same catalog, prices, and stock the business's own staff see.

- **Browsing** — a searchable, filterable product grid/list, with category and "in stock only" filters, and a rotating offers banner built from whatever promotions are currently live. Businesses with more than one store get a store switcher right at the top, since catalog, pricing, and stock can all differ by store.
- **Product detail** — images, description, and every purchasable variant (size/pack option) with its own stock badge and an Add button.
- **Cart & checkout** — browsing and filling the cart need no account, but **placing the order requires signing in** (a lightweight customer sign-in/registration appears right at checkout). From there a customer chooses **pickup from a store** or **delivery to an address** (delivery collects a full address and recipient details; pickup just needs a contact number so the business can notify them when it's ready). Available payment options are built dynamically from what the chosen store actually accepts — card/UPI/wallet only appear where prices are shown at all, cash always reads as "cash on delivery" or "cash at pickup," and a store that accepts none of the priced methods still gets a working "pay on/at…" option so checkout is never a dead end.
- **Duplicate-order protection** — if a customer already has an order pending against the same cart, checkout offers to view/update that order instead of silently creating a second one.
- **Order history** — a signed-in customer gets a real, cross-device order history (and since checkout requires sign-in, every purchase lands there).
- **Account touches** — a lightweight sign-in/registration separate from staff logins; a one-time "who's shopping" preferences prompt (who they're shopping for, and how they want to be notified); an always-available feedback form (bug/feature/compliment/other, with an optional star rating); and a short post-purchase satisfaction survey. All of these are skippable — none block a purchase.
- A small self-promotional card for the platform itself is mixed into the product grid at a random position, alongside the real products.

If a business has been suspended by the Platform Admin, its entire storefront shows a plain "currently unavailable" message instead of attempting to sell anything.

---

## 11. Selling in person: the POS

The POS is built to run like a real till, on whatever screen a shop has — a tablet at the counter, a desktop, or a phone in a pinch.

- **Clocking in** — a cashier picks their store (and terminal) and clocks in before any sale can start; a background heartbeat keeps that session alive, and clocking out is one tap away, with in-progress sales preserved rather than lost.
- **Building a sale** — items go in by camera scan, a handheld barcode scanner, or manual entry; on wider screens the catalog sits alongside the running sale for tap-to-add, on a phone it opens as a browse sheet instead. Quantities adjust with +/-, lines swipe away to remove, and whether the till asks for the customer's phone is each store's choice ([a phone at the till](intent/phone-at-the-till.md)): **Required** (a registered customer looked up by the cashier, or the walk-in's number — the till won't complete a sale without one), **Optional** (asked for, and left blank when the customer would rather not say — every store's until its owner chooses) or **Don't ask**. The number can be typed on the sale or at the tender screen; it is read in the store's own country, then the business's, and kept in international form so a recall text reaches the buyer.
- **Holding a sale** — a sale in progress can be **parked** so the cashier can serve someone else, and **resumed** later (with a warning before discarding one that's been added to since).
- **No-sale** — opening the drawer without a transaction is its own logged action, distinct from a real sale, for loss-prevention purposes.
- **Notification channels** — email, in-app and MQTT, and now **SMS** and **mobile push** behind provider seams (Twilio and Firebase Cloud Messaging, simulated until configured); a shopper registers their device from the storefront and the shop's order confirmation reaches it; marketing goes only where consent is recorded.
- **Receipts** — each till chooses how its receipts come out: the browser's print dialog, an **ESC/POS thermal printer** (over the network, or through a print bridge for a browser till), or a saved file; a test page proves the path, and the record against the order says which way it went.
- **Tendering** — payment can be **split across multiple methods** in one sale (cash, card, UPI, wallet, gift card, store credit) until the balance clears; cash entry shows change due, gift-card/store-credit entries look up and cap against the actual balance available. A completed sale offers a reprint of the receipt before starting the next one; an "email receipt" option records the customer's address against the sale, though actual email delivery isn't wired up yet.
- **Till & cash management** — a shift opens with a starting cash float, supports mid-shift **cash drops** and **pay-in/pay-out** movements (each with a reason), and closes with a **Z-report**: the system shows the expected cash, the cashier enters what was actually counted, and the difference is reconciled on the spot. A separate **X-report** gives a read-only mid-shift snapshot without closing anything.

---

## 12. Orders, returns & alternative sales

Every sale — online or in-store — becomes the same kind of order underneath, which is what lets the rest of the business (inventory, payments, reporting) treat both channels identically:

- **Lifecycle** — placed → confirmed → fulfilled, with cancel available before fulfilment and **void** available afterward (a POS-specific correction to a completed sale, distinct from a cancel). A full status history is kept for every order.
- **Returns** — full or partial, against specific line items, with a reason and a choice of refund method (back to the original card, cash, or store credit); the refund total previews live before it's confirmed.
- **Collecting payment after the fact** — orders that weren't paid at the moment of sale (cash-on-delivery/at-pickup online orders, or orders from a "catalog mode" store — see [§16](#16-catalog-mode--selling-without-showing-a-price)) show what's already been collected versus what's still owed, and let staff record the balance at handover.
- **Layaway** — a sale reserved against a series of deposits, completed once fully paid (handing over the goods) or cancelled before then.
- **Gift cards** — issued with a store, amount, and currency (the code is shown once, in a copyable field); can be reloaded, redeemed against a sale, and have their own transaction history.
- **Special orders** — for stock a store doesn't currently carry but will bring in for a specific customer; these deliberately skip taking stock out of inventory until they're actually fulfilled.

---

## 13. Customers, loyalty & store credit

- **Profiles & addresses** — customer records (with lookup by email or phone for a fast POS match), and multiple saved addresses tagged Home/Work/Billing/Shipping with a default.
- **Loyalty points** — earned automatically on confirmed orders, redeemable against a sale, with a manual earn/redeem/adjust available to staff and a running ledger of every movement.
- **Store credit** — issued (e.g. as part of a return) and redeemed against future purchases, tracked per currency.
- **Right to be forgotten** — a customer record can be anonymized on request, scrubbing personal details while leaving the historical transaction record intact.

---

## 14. Reporting

A small set of always-available, whole-business snapshots, refreshable on demand:

- **On-hand inventory** — total units on hand, per store and variant, across the whole business.
- **Supply vs. demand** — on-hand stock netted against everything already inbound (open purchase orders, transfers), so a buyer can see real coverage, not just what's on the shelf today.
- **Movement statistics** — stock in/out/net, bucketed by day, week, or month.
- **Sales summary & daily trend** — gross, refunded, and net revenue and order counts, grouped by currency, plus a day-by-day revenue trend.

*(Today these are simple, whole-tenant, on-demand views — there's no custom date-range picker or CSV export in the reporting screens yet.)*

---

## 15. Running the platform itself

A separate console exists purely for the people operating StoreQL as a service to its business customers:

- A directory of **every business (tenant)** on the platform, with status, country, currency, and signup date.
- **Activate / suspend** a tenant — suspending immediately locks that business's staff logins, POS, and storefront (see [§5](#5-setting-up-a-business) and [§10](#10-selling-online-the-storefront)).
- **Assisted onboarding** on a business's behalf, ending in a hand-off screen with the credentials to pass along (see [§5](#5-setting-up-a-business)).

Platform administration is entirely separate from any one business's staff accounts — a platform credential and a store credential can never be used interchangeably.

---

## 16. "Catalog mode" — selling without showing a price

Some businesses don't want to (or legally can't) show a retail price up front — wholesalers, quote-driven sellers, or certain regulated goods. Any store can be switched into **catalog mode**, and the effect ripples consistently through everything that store touches:

- The storefront's product list, product detail, and cart all show stock and an **Add** button — never a price.
- POS's tender step disappears entirely; ringing up items produces a **"Place order"** ticket instead of collecting payment.
- Receipts and order history show "price on delivery" / "price in store" instead of a total.
- Staff (or the customer, once contacted) settle the actual price and collect payment afterward through the normal "collect payment" flow on the order (see [§12](#12-orders-returns--alternative-sales)).

It's a first-class, deliberate mode a store opts into — not a missing feature.

---

## 17. Three everyday workflows, start to finish

**A customer buys online:**
Browse the catalog → add items to cart → choose pickup or delivery → pick a payment method → review → place the order and pay → the business sees the order the moment it's confirmed, stock is set aside automatically, and the customer can track it from "My Orders." If payment never completes, the order is automatically released rather than sitting as a phantom hold on stock forever.

**A cashier rings up a sale:**
Clock in → scan or search items onto the sale (attach a customer if there is one) → hold it if interrupted, resume it later → tender (splitting across cash/card/UPI/wallet/gift card/store credit as needed) → print or email the receipt → start the next sale. At the end of the shift, close the till and reconcile the cash counted against what the system expected.

**A store restocks:**
A buyer raises a purchase order with a supplier and submits it → when the delivery arrives, goods are receipted against the PO (batch, expiry, cost) → the stock is immediately visible and available to sell, online and in-store alike. On the planning side, thresholds/safety-stock/kanban settings flag what's running low before it becomes a stockout, and a low-stock signal can be turned straight into the next purchase order.

---

## 18. Multi-tenancy: what "isolated" actually means

Every business on StoreQL operates as if it had the platform to itself:

- A business never sees another business's products, stock, prices, orders, or customers — there is no setting or permission that can cross that line.
- Staff accounts, customer accounts, and even login pages are kept separate per business (and separate again from the platform-operator login) — a password for one business's admin console does nothing anywhere else.
- Suspending one business has zero effect on any other business's storefront, POS, or admin console.
- A new business's very first action (signup) already has this isolation in place — there's no "shared" or "default" tenant that data could leak into.

---

## 19. Reach, languages & currencies

- **Countries supported at onboarding today:** India, United States, United Kingdom, Singapore, and the UAE — with currency (INR/USD/GBP/SGD/AED) auto-suggested per country and editable anywhere money is set up (stores, price lists, promotions, gift cards, suppliers, layaways).
- **Interface languages:** English (UK, the default), Polish, Romanian, Punjabi, Urdu, Bengali, Gujarati, and Arabic — chosen to match the largest non-English-speaking communities in the UK. Arabic and Urdu mirror the entire interface right-to-left automatically. *(Translation is fully wired up end to end today for the sign-in/registration screen; the rest of the interface is being translated progressively.)*
- **Tax model:** UK-style VAT out of the box (see [§8](#8-pricing-promotions--tax)), with per-product VAT categories and per-customer exemption status.

---

## 20. Where to go next

- **[docs/API-GUIDE.md](docs/API-GUIDE.md)** — every capability above, mapped to the actual API surface that provides it.
- **[docs/UI-GUIDE.md](docs/UI-GUIDE.md)** — every screen in every experience (Storefront, POS, Admin Console, Platform Console), what it shows, and how it fits into the flows above.
- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — how the system is actually built, for engineers extending it.
- **[docs/onboarding-and-locations.md](docs/onboarding-and-locations.md)** — the full onboarding and Tenant→Store→Zone design.
- **[PRD.md](PRD.md)** — product requirements, goals, non-goals, and the delivery roadmap.
- **[CLAUDE.md](CLAUDE.md)** — the engineering rules an AI agent (or a new engineer) must follow when changing this codebase.
