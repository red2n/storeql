# StoreQL: what it does for your business

A walkthrough for a retailer. It shows, in pictures, what happens in StoreQL from the moment goods arrive at your back door to the moment the books are closed, and everything a customer, a cashier and a manager do in between.

We are showing you this to learn from you. Every section ends with a few questions. Where something does not match how your shops really work, that is exactly what we want to hear.

**How to read the pictures**

- A **solid box** is something StoreQL does today and that has been tested.
- A **dashed box** is designed and being built next. It is shown so you can tell us whether it matters to you.
- Under each picture, *Proven by* names the matching part of our test catalogue ("StoreQL Flow Tests"), where every step is written down as a test case. Figures are as of 30 September 2026.

---

## 1. The whole thing on one page

One system for the shop floor, the online shop and the office. They share one record of stock, prices, customers and money, so they never disagree.

```mermaid
flowchart LR
    subgraph DOORS["Four ways in"]
        TILL["The till<br/>cashiers"]
        SHOP["The online shop<br/>your customers"]
        OFFICE["The back office<br/>owners, managers, storekeepers"]
        HQ["Head office view<br/>every shop at once"]
    end

    subgraph CORE["One record, shared by all of them"]
        direction TB
        P["Products and prices"]
        S["Stock, by shop and shelf"]
        O["Orders and payments"]
        C["Customers and loyalty"]
        B["Suppliers and the books"]
    end

    subgraph OUT["What comes out"]
        R1["Receipts and invoices"]
        R2["Reports and tax returns"]
        R3["Messages to customers"]
        R4["Your accounting package<br/>and other systems"]
    end

    TILL --> CORE
    SHOP --> CORE
    OFFICE --> CORE
    HQ --> CORE
    CORE --> R1
    CORE --> R2
    CORE --> R3
    CORE --> R4
```

| Area | What you can do | Tested today |
|---|---|---|
| The till | Sell, take any mix of payments, hold sales, work offline, cash up | 121 of 162 steps |
| Returns and refunds | Take goods back, exchange, void, refund to the right place | 121 of 127 |
| The online shop | Browse, order, collect or have delivered, be told what is happening | 92 of 170 |
| Stock control | Receive, put away, move, count, mark down, recall | 135 of 224 |
| Buying and the books | Quotes, orders, deliveries, invoices, payments, ledger, VAT | 199 of 214 |
| Products and prices | Catalogue, price lists, promotions, markdowns, tax | 156 of 162 |
| Setup, people and access | Shops, staff, roles, sign-in, plans, audit | 248 of 290 |
| Customers and reports | Records, loyalty, gift cards, messages, reports | 143 of 183 |

**1,215 of 1,532 written steps are tested automatically today.** The rest are written down and being automated. A further 166 checks are listed as still open; nearly all are designed for the next round of building (section 12).

---

## 2. Your business, your shops, your shelves

StoreQL is laid out the way your business is. Nothing about your country, currency, tax, phone numbers, time zone or language is assumed: it all comes from how you set it up.

```mermaid
flowchart TD
    BIZ["Your business<br/>one home currency, your own tax setup"]
    BIZ --> S1["High Street shop"]
    BIZ --> S2["Retail park shop"]
    BIZ --> WH["Warehouse<br/>serves the shops, never sells to the public"]
    BIZ --> DS["Dark store<br/>delivery only, no till"]

    S1 --> Z1["Aisles and shelves"]
    S1 --> Z2["Cold room"]
    S1 --> Z3["Back store"]
    WH --> Z4["Receiving bay"]
    WH --> Z5["Racks"]

    S1 -.-> T1["Tills and card machines"]
    S1 -.-> H1["Opening hours,<br/>delivery areas, time slots"]
```

- Each shop has its own address, opening hours, accepted payment methods, delivery areas and collection or delivery time slots.
- Stock is always "at this shop, in this place", not just a number for the whole business.
- A new business is set up in a few minutes: sign up, name the business, create the first shop, and you can receive stock and take a sale.

*Proven by:* Flow Tests → **Business setup, people and access** → "Self sign-up and the setup wizard", "Stores, zones, till-phone choice and store open/close", "Delivery areas and delivery/collection windows".

**Questions for you**
1. How many sites do you have, and are any of them warehouses or delivery-only?
2. Do your shops price, stock or trade differently from each other?
3. Who sets up a new shop today, and how long does it take?

---

## 3. A sale at the till

```mermaid
flowchart TD
    A["Cashier clocks in at their shop"] --> B["Scan, type a code, or pick from the screen"]
    B --> CHK{"Anything stopping this item?"}
    CHK -->|"Recalled product"| STOP1["Refused, with the reason"]
    CHK -->|"Age-restricted"| AGE["Cashier confirms the age check"]
    CHK -->|"Weighed on a scale not fit for trade"| STOP2["Refused"]
    CHK -->|"No"| CUST
    AGE --> CUST["Customer: look up, take a phone number,<br/>or skip (each shop chooses)"]
    CUST --> DISC["Promotions apply by themselves.<br/>A manual discount is held to the cashier's limit"]
    DISC --> PARK{"Serve someone else first?"}
    PARK -->|"Yes"| HOLD["Hold the sale, resume it later<br/>(who held and who resumed is kept)"]
    HOLD --> PAY
    PARK -->|"No"| PAY["Take payment: cash, card, wallet, gift card,<br/>store credit, or any mix"]
    PAY --> DONE["Sale complete"]
    DONE --> RCPT["Receipt: printed, thermal printer or file,<br/>with a code to scan for a return"]
    DONE --> STK["Stock leaves the shelf, oldest date first"]
    DONE --> LOY["Customer earns points"]
    DONE --> BOOKS["Sale and tax reach the books"]

    OFF["No internet? The till keeps selling<br/>and sends the sales when it is back"] -.-> PAY

    OVR["Price changed at the till for a damaged item,<br/>with a reason and a limit"]:::next
    LINE["Discount on one line"]:::next
    DISC -.-> OVR
    DISC -.-> LINE

    classDef next stroke-dasharray: 6 4;
```

**At the end of the shift**

```mermaid
flowchart LR
    F["Open with a cash float"] --> M["During the day: cash drops,<br/>money in, money out, each with a reason"]
    M --> X["Mid-shift check<br/>without closing"]
    M --> Z["Close: count the drawer"]
    Z --> V["Expected against counted,<br/>over or short shown at once"]
    V --> REG["Reports per register,<br/>and a manager's note on a difference"]:::next
    classDef next stroke-dasharray: 6 4;
```

What else the till handles today: held sales, layaway (pay in instalments, collect when paid), special orders, gift cards sold and spent, bottle and container deposits, opening the drawer without a sale (logged), and shops that sell without showing prices until a manager prices the order.

*Proven by:* Flow Tests → **Point of sale and the till** (12 flows) → "Ringing up a sale", "Discounts, role ceilings and automatic promotions", "Hold, resume and discard a sale", "Tender: cash, card, UPI/wallet, gift card, store credit and splits", "Offline selling and replay", "Till session cash control".

**Questions for you**
1. Which payment methods do you take, and how often does a customer split a payment?
2. When a price is wrong at the till, who is allowed to change it, and by how much?
3. How do you cash up today, and what difference do you tolerate before someone has to explain it?
4. How often does the internet fail in your shops, and what do you do when it does?

---

## 4. An online order, from basket to doorstep

The online shop shows the same products, prices and stock your staff see. A shopper can browse without an account and signs in only to place the order.

```mermaid
sequenceDiagram
    autonumber
    actor Shopper
    participant Shop as Online shop
    participant SQL as StoreQL
    participant Staff as Shop staff

    Shopper->>Shop: Browse, search, add to basket
    Shopper->>Shop: Checkout: collect or deliver, choose a time slot
    Shop->>SQL: Place the order
    SQL-->>SQL: Price it and check stock
    Note over SQL: With stock reservation switched on, the stock is held,<br/>and an order one shop cannot fill is split across<br/>the nearest shops and paid once
    Shopper->>Shop: Pay
    SQL-->>Shopper: Order confirmed (email, app notice)
    SQL->>Staff: Order appears on the picking list
    Staff->>SQL: Pick and pack
    alt An item is out of stock
        Staff->>SQL: Offer a substitute or mark it short
        SQL-->>Shopper: Told what changed. Never charged more, the difference is refunded
    end
    alt Collection
        SQL-->>Shopper: Ready for collection
        Staff->>SQL: Handed over, who collected
    else Delivery
        Staff->>SQL: Dispatched, carrier and tracking reference
        SQL-->>Shopper: On its way
    end
```

```mermaid
flowchart LR
    subgraph TODAY["Works today"]
        A1["Pay online is recorded against the order"]
        A2["Shopper cancels their own unpaid order"]
        A3["Order history on any device"]
        A4["Delivery-only shops say so while browsing"]
    end
    subgraph NEXT["Being built next"]
        B1["Live card processor with<br/>bank security checks (3-D Secure)"]:::next
        B2["Shopper starts a return from their order,<br/>with a carrier return label"]:::next
        B3["Proof of delivery: signature or photo"]:::next
        B4["Age check repeated at handover"]:::next
        B5["Shopper approves each substitute"]:::next
        B6["Text messages for order updates"]:::next
    end
    classDef next stroke-dasharray: 6 4;
```

> **Said plainly:** today the online checkout records that a payment was made. Connecting it to a live card processor, so the money is taken and verified by the bank, is designed and is the first thing in the next round of building. In-shop payments are not affected.

*Proven by:* Flow Tests → **The online shop and fulfilment** (11 flows) → "Checkout", "Split fulfilment across shops", "Picking and packing", "Substitutions and short lines", "Handover", "Notifications to the shopper".

**Questions for you**
1. Do you sell online today? Through your own site, a marketplace, or both?
2. Collection, delivery, or both? Who delivers: your own drivers or a carrier?
3. When an item is out of stock, do you substitute, and does the customer get a say?
4. Which card processor do you use, and would you change it?

---

## 5. When goods come back

```mermaid
flowchart TD
    START["Customer brings goods back"] --> FIND{"Do they have the receipt?"}
    FIND -->|"Yes"| SCAN["Scan the receipt code or type its number"]
    FIND -->|"No"| NOREC["No-receipt return: only if you allow it,<br/>manager only, store credit or gift card only,<br/>at today's price, up to your limit"]
    SCAN --> COND["Choose what comes back and its condition:<br/>sealed, opened, damaged, faulty"]
    COND --> POLICY{"Inside your return policy?<br/>(your days, your cashier limit)"}
    POLICY -->|"Yes"| WHO1["Any cashier completes it"]
    POLICY -->|"No"| WHO2["A manager completes it and is named on the record"]
    WHO1 --> WHAT
    WHO2 --> WHAT{"What does the customer want?"}
    WHAT -->|"Money back"| REF["Refund to how they paid,<br/>to store credit, or to a gift card"]
    WHAT -->|"Something else"| EXC["Exchange: only the difference<br/>is charged or refunded"]
    NOREC --> REF2["Store credit or gift card"]
    REF --> STOCK
    EXC --> STOCK
    REF2 --> STOCK["Stock goes where its condition says"]
    STOCK --> S1["Sealed: back on sale"]
    STOCK --> S2["Opened: set aside to be checked"]
    STOCK --> S3["Damaged or faulty: off sale"]
    STOCK --> S4["Recalled product: quarantined"]
    REF --> PTS["Points earned on the sale are taken back"]
```

Also covered today: voiding a sale rung up by mistake, cancelling an order before it is handed over, refunds made from the office, chargebacks from the card network, and a full trail of who returned, voided or refunded what.

- A refund gives back what the customer actually paid, tax included, and their share of any discount on the sale.
- Pressing the button twice, or a retry after a lost connection, never refunds twice.

*Proven by:* Flow Tests → **Returns, refunds and voids** (12 flows, 121 of 127 steps tested) → "Return at the till against the original sale", "Return with no receipt", "Exchanges", "Disposition of returned stock", "Chargebacks".

**Questions for you**
1. What is your return policy: how many days, and who can approve an exception?
2. Do you take goods back without a receipt? On what terms?
3. What happens to returned goods in your shops today: back on the shelf, checked first, sent back to the supplier?

---

## 6. The back office: products and prices

```mermaid
flowchart LR
    subgraph CAT["Catalogue"]
        P1["Products and their sizes or packs,<br/>each with its own code and barcode"]
        P2["Categories and brands"]
        P3["Safety information, allergens,<br/>age restriction, deposit"]
        P4["Pictures"]
        P5["Life of a line: new, active,<br/>discontinued, delisted"]
    end
    subgraph PRICE["Prices"]
        Q1["Price lists by channel and date,<br/>with full history"]
        Q2["Price zones: shops that price alike"]
        Q3["Tax rates and categories"]
        Q4["Price per kilo or litre"]
        Q5["Prices shown in another currency,<br/>never charged in it"]
    end
    subgraph DEALS["Offers"]
        D1["Promotions: percentage, amount,<br/>buy one get one, spend thresholds"]
        D2["Coupons with limits per customer"]
        D3["Markdown stickers for short-dated stock"]
        D4["Competitor prices: the system proposes,<br/>a person decides"]
    end
    CAT --> PRICE --> DEALS
    DEALS --> TILL["The till"]
    DEALS --> WEB["The online shop"]

    N1["Tax rate changes dated in advance"]:::next
    N2["Small price changes applied automatically<br/>within a limit you set"]:::next
    N3["A second approval for a very deep discount"]:::next
    PRICE -.-> N1
    DEALS -.-> N2
    DEALS -.-> N3
    classDef next stroke-dasharray: 6 4;
```

Checks that already protect you: the same code or barcode cannot be used twice, a barcode's check digit is verified, a category cannot be placed inside itself, and a promotion cannot be spent against another business.

*Proven by:* Flow Tests → **Catalogue, pricing and promotions** (13 flows, 156 of 162 steps tested).

**Questions for you**
1. How many product lines do you carry, and how do you load them today (by hand, a spreadsheet, your supplier's file)?
2. Do prices differ by shop or region?
3. Which kinds of offers do you run most?
4. Who may change a price, and does anyone check it?

---

## 7. The back office: stock, from the back door to the shelf

```mermaid
flowchart LR
    SUP["Supplier delivers"] --> RCV["Receive: quantity, batch, expiry date"]
    RCV --> PUT{"Where does it go?"}
    PUT -->|"A rule says"| ZONE["Straight to its shelf or cold room"]
    PUT -->|"No rule"| TASK["Put-away task for a person"]
    ZONE --> ONHAND["On hand at this shop"]
    TASK --> ONHAND

    ONHAND --> SELL["Sold at the till or online"]
    ONHAND --> TRF["Transfer to another shop"]
    ONHAND --> CNT["Counted: cycle counts and full stocktakes"]
    ONHAND --> ADJ["Adjusted or written off, with a reason"]
    ONHAND --> EXP["Past its date: stays in the count,<br/>can no longer be sold"]
    ONHAND --> RCL["Recalled: sales stopped,<br/>buyers told, refunds tracked"]

    WH["Warehouse"] -->|"Proposes what each shop needs"| TRF2["Replenishment to shops"]
    WH -->|"Goods for a shop pass straight through"| XD["Cross-dock"]

    LOW["Running low"] --> PROP["Reorder proposal for the buyer"]
    ONHAND --> LOW

    N1["Counting screens with blind counts<br/>and a second counter"]:::next
    N2["Record what actually arrived on a transfer,<br/>and what went missing"]:::next
    N3["Daily list of expired and nearly expired stock"]:::next
    CNT -.-> N1
    TRF -.-> N2
    EXP -.-> N3
    classDef next stroke-dasharray: 6 4;
```

Specialist stock StoreQL already understands:

| If you... | StoreQL... |
|---|---|
| hold a supplier's goods until they sell | keeps it apart as **consignment**, and works out what you owe when it sells |
| have suppliers ship direct to the customer | treats it as **dropship**: sold without ever being in your stockroom |
| hold goods with duty unpaid | keeps **bonded** stock out of sale until duty is released |
| cut meat or portion fresh food | records a **yield run**: one primal in, several cuts out, the loss measured |
| run a warehouse for your shops | proposes **replenishment** per shop and shares a short delivery fairly |
| pick many online orders at once | builds **picking waves** in shelf order |
| plan shelf space | keeps **planograms** and shows shelf gaps |

*Proven by:* Flow Tests → **Inventory and stock control** (16 flows) → "Receiving stock", "Transfers between stores", "Cycle counts and stocktakes", "Product recalls and withdrawals", "Expiry and markdown of short-dated stock", "Depot/DC replenishment proposals", "Stock valuation".

**Questions for you**
1. Do you track batches and expiry dates? For which products?
2. How do you count stock today, how often, and who signs off a difference?
3. How do goods get from your warehouse (or supplier) to each shop?
4. Have you had a product recall? What was hardest about it?

---

## 8. The back office: buying, suppliers and the books

```mermaid
flowchart LR
    NEED["Reorder proposal<br/>or a buyer's decision"] --> RFQ["Ask suppliers for quotes,<br/>compare them side by side"]
    RFQ --> PO["Purchase order"]
    NEED --> PO
    PO --> APP{"Within the buyer's spending limit?"}
    APP -->|"Yes"| SENT["Sent to the supplier"]
    APP -->|"No"| MGR["Waits for someone with the authority"]
    MGR --> SENT
    SENT --> GRN["Goods received against the order"]
    GRN --> INV["Supplier's invoice: keyed in,<br/>or received electronically"]
    INV --> MATCH{"Order, delivery and invoice agree?"}
    MATCH -->|"Yes"| OK["Approved for payment"]
    MATCH -->|"No"| VAR["A person decides on the difference"]
    VAR --> OK
    OK --> RUN["Payment run and the bank file"]
    GRN --> RTV["Wrong or damaged: return to supplier,<br/>debit note, their credit note"]

    SC["Supplier scorecard: on time, in full,<br/>quality, invoice accuracy"]
    GRN --> SC

    N1["Damaged and refused quantities<br/>recorded at the door"]:::next
    N2["Bank detail changes confirmed by a second person<br/>before the first payment"]:::next
    GRN -.-> N1
    RUN -.-> N2
    classDef next stroke-dasharray: 6 4;
```

**Where the money goes.** Every sale, refund, delivery and payment writes itself into the ledger. Nobody re-keys anything.

```mermaid
flowchart TD
    SALE["A sale"] --> T["What the customer paid with:<br/>cash, card, gift card, store credit"]
    SALE --> REV["Sales"]
    SALE --> TAX["Tax collected"]
    REFUND["A refund or exchange"] --> REV
    REFUND --> TAX
    GC["Gift card sold"] --> LIAB["Owed to customers until spent"]
    DELIV["A delivery from a supplier"] --> STOCKV["Stock value"]
    DELIV --> OWED["Owed to suppliers"]
    PAYRUN["A payment run"] --> OWED

    T --> LEDGER["Your ledger"]
    REV --> LEDGER
    TAX --> LEDGER
    LIAB --> LEDGER
    STOCKV --> LEDGER
    OWED --> LEDGER

    LEDGER --> TB["Trial balance"]
    LEDGER --> VAT["Tax return figures"]
    LEDGER --> ACC["Pushed to your accounting package<br/>(Xero, QuickBooks Online, Sage)"]

    CLOSE["Close a month so its figures cannot change"]:::next
    LEDGER -.-> CLOSE
    classDef next stroke-dasharray: 6 4;
```

Tax rules that apply only in one country (for example the UK's online VAT filing) are used only by a business in that country.

*Proven by:* Flow Tests → **Procurement, suppliers and finance** (15 flows, 199 of 214 steps tested) → "Request for quotation, comparison and award", "Purchase order approval by spend authority", "Supplier invoice capture, three-way match and variance decision", "Supplier payment runs, bank files and duplicate/payee protection", "Nominal ledger, manual journals, period close and trial balance", "Accounting package connection and journal sync".

**Questions for you**
1. Who places orders with suppliers, and is there a spending limit?
2. How do supplier invoices reach you, and who checks them against what arrived?
3. Which accounting package do you use? What do you key into it by hand today?
4. How do you pay suppliers: one by one, or in a run?

---

## 9. The back office: people, access and keeping watch

```mermaid
flowchart TD
    OWNER["Owner<br/>everything, in every shop"]
    HQM["Head-office manager<br/>every shop, business-wide settings"]
    SM["Shop manager<br/>their own shops only"]
    SK["Storekeeper<br/>stock, receiving, counts"]
    CA["Cashier<br/>the till"]
    CUSTOM["Your own roles:<br/>a manager who cannot void a sale,<br/>a trainee who cannot open the drawer"]

    OWNER --> HQM --> SM --> SK --> CA
    SM -.-> CUSTOM
    CA -.-> CUSTOM
```

| What | How StoreQL handles it today |
|---|---|
| Signing in | Password rules you can read before typing, a second step (authenticator code) where you require it, sign-in through your own company login, sign out of every device |
| Staff | Add by email, assign to a shop with a role, a shift roster and a time clock |
| Daily running | Task checklists per shop, announcements staff must acknowledge |
| Who did what | A trail of discounts, voids, returns, drawer openings and cancellations by person. A separate trail of changes to shops, staff and roles. Security events such as failed sign-ins |
| Refused by design | A shop manager cannot change another shop, only an owner can make another owner, and nobody can hand out more access than they hold |

```mermaid
flowchart LR
    ACT["A sensitive action:<br/>large write-off, big refund,<br/>change to a supplier's bank details"] --> LIMIT{"Above the limit<br/>you set?"}
    LIMIT -->|"No"| GO["Goes ahead"]
    LIMIT -->|"Yes"| SECOND["Waits for a second person<br/>with the authority"]
    SECOND --> GO
    WATCH["Too many voids, refunds or discounts<br/>by one person in a period"] --> ALERT["A manager is alerted"]

    class LIMIT,SECOND,WATCH,ALERT next;
    classDef next stroke-dasharray: 6 4;
```

The approval limits and the alerts in the second picture are designed and being built next. Limits are yours to set: nothing is switched on, and no figure is chosen, until you choose it.

*Proven by:* Flow Tests → **Business setup, people and access** (19 flows, 248 of 290 steps tested) → "Staff: assign by email, roles, custom roles, store-held managers", "Second factor", "Passwords", "Business audit trail and platform security incidents".

**Questions for you**
1. What roles do you really have in a shop, and what is each one not allowed to do?
2. Which actions need a second person today, on paper or in practice?
3. What would you want to be alerted about the same day?

---

## 10. The back office: customers, loyalty and messages

```mermaid
flowchart LR
    CUST["A customer record:<br/>name, phone, email, addresses"] --> LOY["Loyalty: points on every sale,<br/>tiers, points that expire"]
    CUST --> SV["Store credit and gift cards"]
    CUST --> CONS["What they agreed to receive,<br/>by channel"]
    CONS --> MSG["Messages in your own words and languages:<br/>order updates, receipts, offers"]
    MSG --> CH["Email, in-app and push notices"]
    CUST --> PRIV["Privacy requests:<br/>a copy of their data, correction, deletion"]

    N1["Spot and merge duplicate customers"]:::next
    N2["A notice before points or credit expire"]:::next
    N3["Text messages, and a place in the app<br/>listing everything sent"]:::next
    CUST -.-> N1
    LOY -.-> N2
    CH -.-> N3
    classDef next stroke-dasharray: 6 4;
```

- Points and store credit given by hand are for managers only, always with a reason.
- Marketing goes only where consent is recorded, and withdrawing consent switches it off at once.
- Your other systems can be told about events as they happen (orders, payments, price changes).

*Proven by:* Flow Tests → **Customers, loyalty, messages and reports** (15 flows) → "Loyalty: earning and redeeming points", "Store credit and gift cards", "Marketing consent, channels, and the send-time allowance check", "Privacy rights".

**Questions for you**
1. Do you run a loyalty scheme? What does a customer get, and what does it cost you?
2. How do you contact customers today, and how do you record that they agreed?
3. Do you sell gift cards? Do they expire?

---

## 11. The back office: reports

| Report | What it answers |
|---|---|
| Sales by day, hour, staff and category | What sold, when, and who sold it |
| Payment mix | How customers paid |
| Gross margin and return on stock | What you actually made |
| Stock valuation | What your stock is worth, by shop |
| On hand, inbound and low stock | What you have, what is coming, what to reorder |
| Discounts, voids, returns and drawer openings by staff | Where losses might be hiding |
| Tax summary and returns | What you owe |

A shop manager sees their own shops. An owner or head-office manager sees the whole business.

Being built next: figures for a closed month that stay fixed, a record of who opened a sensitive report, and every sales report counting "a day" as the shop's own day.

**Questions for you**
1. Which three numbers do you look at every morning?
2. Which report do you build by hand in a spreadsheet today?

---

## 12. What is being built next

Everything below is designed and written down. We would like your view on the order.

| Coming next | Why it matters to a retailer |
|---|---|
| **Live card payments online** with the bank's security check | Money is taken and verified, not just recorded |
| **Approval limits and second sign-off** | Large write-offs, refunds, price cuts and bank-detail changes need the right person |
| **Same-day alerts** on unusual voids, refunds and discounts | Loss is caught this week, not at year end |
| **Shopper-started returns** with a carrier label | Fewer phone calls, a cleaner return |
| **Proof of delivery** and an age check at handover | Evidence when a delivery is disputed |
| **Stock counting screens**, blind counts, a second counter | Counts you can trust |
| **Transfer differences** recorded on arrival | You know what went missing between shops |
| **Daily expiry list** with disposal recorded | Less waste, a clean audit |
| **Closing a month** in the books | Reported figures stop moving |
| **Discount on one line, price change at the till** | The cashier can fix a damaged item properly |
| **Registers named on every sale and cash report** | Two tills in one shop can be told apart |
| **Duplicate customers found and merged** | One customer, one record |

---

## 13. Tell us

The most useful things you can tell us:

1. **What would stop you switching?** One thing your current system does that you did not see here.
2. **What is wrong?** A picture above that does not match how your shops work.
3. **What would you never use?** So we do not make you pay for it in complexity.
4. **What is the first thing you would want working on day one?**
5. **What does a bad day look like** in your shops, and what should the system have done?

| Area | Works for us as shown | Needs to change | Missing | Notes |
|---|---|---|---|---|
| The till | | | | |
| Online orders | | | | |
| Returns and refunds | | | | |
| Products and prices | | | | |
| Stock | | | | |
| Buying and the books | | | | |
| People and access | | | | |
| Customers and loyalty | | | | |
| Reports | | | | |

---

*Backing detail: the StoreQL Flow Tests catalogue lists every flow above with its test cases, the rules enforced and what is still open: https://claude.ai/artifact/R391n2d2cV23sdKHKUnpGc (shared on request).*
