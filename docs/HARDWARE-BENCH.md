# Hardware bench: what a till needs, and how to prove it on the real thing

Nothing in StoreQL has run on a real receipt printer, cash drawer, scanner or scale until this page has
been worked through on the customer's own kit. The software paths exist and are tested against fakes; this
page is the checklist that turns "should work" into "does", with a result for each line. Do it before the
staff-only dry run, at the till PC, and keep the filled table in the repository (`docs/bench-<date>.md`).

## What the till does, and how it reaches each device

| Device | How StoreQL talks to it | Notes |
|---|---|---|
| **Receipt printer** | ESC/POS bytes, four ways chosen per till in its printer settings: the **browser's print dialog** (web), a **network printer** on port 9100 (desktop and mobile builds), a **print bridge** on the till PC (`tools/print-bridge`, for a browser till with a thermal printer), or a **file**. A test page proves the path. | Windows-1252, so £ and € print. A paper receipt is optional per till. |
| **Cash drawer** | Plugged into the **printer's** drawer port; the till appends the kick pulse (pin 2, 50 ms on, 500 ms off) after the cut, **for a sale that took cash only**. | A product name cannot open the drawer (the receipt text is stripped of control bytes). Through the browser dialog the drawer opens only if the OS driver is set to open it on print. |
| **Barcode scanner** | A scanner that types, **keyboard-wedge** mode, ending with Enter. The till also reads GS1 DataMatrix, GS1-128 and Digital Link QR (a 2D imager). | Set the scanner's suffix to Enter and no prefix; leave check-digit transmission on. |
| **Labelling scale** | Not connected. It prints a **price- or weight-embedded label** (prefix 2); the till reads it by the scale's scheme registered in the back office (item digits, price or weight, decimals; the UK price-check digit is skipped, not verified). | A label the scheme cannot read exactly is not a reading at all, and the till says so. |
| **Card machine** | **Not connected** at the pilot. The customer's own standalone machine takes the card; the cashier types the machine's **receipt reference** with the tender (required). | The till refuses a card with no reference, so every card sale can be found in the acquirer's file. |
| **Customer display** | A second window on the same PC (a browser tab or window on the second screen). | Optional. |

The online storefront is closed at the pilot; only the in-store till is on the bench.

## The print bridge (browser tills with a thermal printer)

A browser cannot open a socket, and a page served over https may call `http://localhost` but not another
address. So the bridge runs **on the till PC** and the till's default URL, `http://localhost:9109/print`, is
what it listens on:

```
python3 tools/print-bridge/print_bridge.py --printer 192.168.1.50:9100 --allow-origin https://<the till's web address>
python3 tools/print-bridge/print_bridge.py --device /dev/usb/lp0       --allow-origin https://<the till's web address>   # USB printer on Linux
```

It forwards only a POST of octets to `/print`, only for the origins it was told, one receipt at a time, and
answers `GET /health` (200 when the printer answers, 503 when not). Run it as a service on the PC
(systemd, or a Windows scheduled task at logon). A Windows PC with a USB printer can instead share the
printer on the network at port 9100 through a vendor utility, or use the browser dialog.

## The bench: do each line, write what happened

Record the hardware (make, model, firmware, how connected), the till PC and browser or build, and the date.
A line passes only if the result is what the right-hand column says.

### Printing and the drawer

| # | Do | Pass when |
|---|---|---|
| P1 | Print the test page from the till's printer settings | Prints; the paper is the width the settings say (58 or 80 mm); the cut happens |
| P2 | Ring a sale of three items at different VAT rates and pay cash | Receipt shows each line at its shelf price, the seller's name and VAT number, a VAT table by rate whose gross column adds to the total; **£ prints as £** |
| P3 | Same sale paid by card with the machine's reference typed | The receipt shows the card line; the drawer does **not** open |
| P4 | Pay cash | The drawer opens once, after the cut |
| P5 | A product named with odd characters (`é`, `€`, `\x1Bp`) | The name prints; the drawer does not open on a card sale |
| P6 | A 40-line receipt, then reprint it from history | Both identical; no lost or doubled lines; paper feed leaves the cut clear |
| P7 | Switch the printer off, ring a sale, switch it on | The till says the receipt did not print and lets the cashier reprint; the sale is not lost |
| P8 | Restart the print bridge or the printer mid-day | The next receipt prints with no re-set-up |

### Scanning

| # | Do | Pass when |
|---|---|---|
| S1 | Scan 20 different EAN-13s, then EAN-8 and UPC-A items | Each adds the right item once; no doubled or dropped scans at the scanner's top speed |
| S2 | Scan the same item 5 times fast | Quantity 5 |
| S3 | Scan a pack's GS1 DataMatrix (if the shop's suppliers print them) and a Digital Link QR | The item is found by its GTIN; the lot and expiry are kept |
| S4 | Scan a barcode the catalogue has as an **old** EAN, a multipack and a case | The item is found, with its pack quantity |
| S5 | Scan an unknown barcode | A clear "not found", no crash, the sale intact |
| S6 | Scan while a dialog is open or the cursor is in the wrong field | Nothing is added to the wrong place |

### Weighed and labelled items

| # | Do | Pass when |
|---|---|---|
| W1 | Print a price-embedded label from the shop's scale; scan it | The item and the price are as on the label, to the penny |
| W2 | Print a weight-embedded label; scan it | The weight is right and the price is weight × the shelf price per kg |
| W3 | A label whose check digit is wrong (alter one digit) | Refused with words, not read as another price |
| W4 | A weighed item sold twice in one sale | Two lines, each with its own label price |

### Cash, cards and the end of the day

| # | Do | Pass when |
|---|---|---|
| C1 | Open a till with a float, take cash, a card (with a reference), a refund, a pay-out | The X report's expected cash = float + cash sales − cash refunds + pay-ins − pay-outs − drops |
| C2 | Close counting the cash exactly, then once £1 short | The over/short is exactly £0.00 and −£1.00, with the till's own name on it |
| C3 | Settle the day | The day report counts the store's own day, cannot be settled twice, and a correction is a new version with a reason |
| C4 | Take a card with no reference | Refused with a plain sentence; nothing recorded |

### Network and recovery

| # | Do | Pass when |
|---|---|---|
| N1 | Unplug the till from the network mid-sale; finish it; reconnect | The sale is queued and arrives once; nothing doubles; the receipt prints (or says why not) |
| N2 | Reboot the till PC | The sale in progress or held comes back as it was |
| N3 | Two tills sell the last unit at once | One succeeds; the other is told, with nothing taken |

Anything that fails goes in the table with what was seen, and becomes a bug with a failing test before it is
fixed. The offline till **cannot scan a barcode it has not already loaded**; the pilot does not promise it,
and N1 is about sales already rung, not new items.

## What to buy (and what to ask the customer first)

Ask first: what printers, drawers, scanners and scales the shops already have, whether the tills are
Windows PCs, tablets or Linux, and how many lanes each shop trades. Re-using their kit is cheaper and is
the realistic test.

If something must be bought: an **80 mm network thermal receipt printer** with ESC/POS and a drawer port
(Epson TM-T20/T88 class and compatibles; a model tested on this bench is the one to buy more of), an
**RJ11 drawer** with a 24 V kick matching that printer, a **2D imager** in keyboard-wedge mode (so GS1
DataMatrix reads), and a **PC with a wired network** for the till. No model is recommended here that has not
passed the bench above.
