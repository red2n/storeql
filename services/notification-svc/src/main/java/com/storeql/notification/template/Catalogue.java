package com.storeql.notification.template;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Every message a business can put in its own words (13.x, message templates): what each is for,
 * the forms it goes out in, the values it can use, the parts the law will not let it leave out, and
 * the platform's own words — the default, in English, used until a business writes its own.
 *
 * <p>The defaults are the messages as they were written before templates existed, so a business
 * that never opens the screen sends exactly what it sent before, bar two improvements every message
 * now gets: amounts and dates written the way the reader's language writes them, and a customer's
 * email signed by the shop rather than by the platform.
 */
public final class Catalogue {

  private Catalogue() {}

  /** The form a message takes, and how much of it a reader will see. */
  public enum Form {
    /** A subject and a letter. */
    EMAIL(200, 20_000),
    /** One text message: no subject, ten segments at most. */
    SMS(0, 1_600),
    /** A title and a line on a phone's lock screen. */
    PUSH(65, 240),
    /** A store's alert feed and its devices: a title and a few sentences. */
    ALERT(200, 2_000);

    public final int subjectMax;
    public final int bodyMax;

    Form(int subjectMax, int bodyMax) {
      this.subjectMax = subjectMax;
      this.bodyMax = bodyMax;
    }

    public boolean hasSubject() {
      return subjectMax > 0;
    }
  }

  /** Who reads it. */
  public enum Audience {
    CUSTOMER,
    STAFF,
    SUPPLIER
  }

  /**
   * One value a template can use.
   *
   * @param kind TEXT, MONEY, NUMBER, DAY, MOMENT, FLAG or LIST; a LIST's fields are named {@code
   *     list.field}, and every item also has the flags {@code first} and {@code last}
   */
  public record Variable(String name, String kind, String description) {}

  /**
   * One form of a message.
   *
   * @param required groups of names, one of each of which the template must use: the parts a law or
   *     the platform will not let a business leave out
   */
  public record FormSpec(Form form, String subject, String body, List<Set<String>> required) {

    public FormSpec {
      required = List.copyOf(required);
    }
  }

  /** One message. */
  public record MessageType(
      String key,
      String title,
      Audience audience,
      String why,
      List<Variable> variables,
      List<FormSpec> forms,
      /**
       * The sample values a preview or a check is written out with, given the currency to show a
       * money value in: the business's own home currency, or the platform's neutral one when it
       * cannot be read. Never bakes in a literal currency itself.
       */
      Function<String, Values> sample) {

    public MessageType {
      variables = List.copyOf(variables);
      forms = List.copyOf(forms);
    }

    public Optional<FormSpec> form(Form form) {
      return forms.stream().filter(f -> f.form() == form).findFirst();
    }

    /**
     * Every name a template of this message may use: the values, list fields, and the list flags.
     */
    public Set<String> names() {
      java.util.Set<String> out = new java.util.LinkedHashSet<>();
      for (Variable v : variables) {
        int dot = v.name().indexOf('.');
        out.add(dot < 0 ? v.name() : v.name().substring(dot + 1));
        if ("LIST".equals(v.kind())) {
          out.add("first");
          out.add("last");
        }
      }
      return out;
    }
  }

  private static Variable v(String name, String kind, String description) {
    return new Variable(name, kind, description);
  }

  private static final Variable SHOP =
      v("shop", "TEXT", "The business's name, as its message settings sign it");

  private static final List<Set<String>> NONE = List.of();

  // ── customers
  // ─────────────────────────────────────────────────────────────────────────────────────

  static final MessageType ORDER_CONFIRMED =
      new MessageType(
          "ORDER_CONFIRMED",
          "Order confirmed",
          Audience.CUSTOMER,
          "Sent to a shopper with an account when their order is confirmed.",
          List.of(
              v("order", "TEXT", "The order's reference"),
              v("total", "MONEY", "What the order came to"),
              v(
                  "window",
                  "TEXT",
                  "The delivery or collection window, in the store's own time, when the order has"
                      + " one; absent otherwise"),
              SHOP),
          List.of(
              new FormSpec(
                  Form.EMAIL,
                  "Your order is confirmed",
                  "Thanks for your order!\n\nOrder {{order}}\nTotal:"
                      + " {{total}}{{#window}}\n{{window}}{{/window}}\n\n— {{shop}}",
                  List.of(Set.of("order"))),
              new FormSpec(
                  Form.PUSH,
                  "Your order is confirmed",
                  "Order {{order}} — {{total}}",
                  List.of(Set.of("order")))),
          currency ->
              Values.of()
                  .text("order", "01a0c42a-11a0-76f6-a69f-c3297150342e")
                  .money("total", new BigDecimal("24.60"), currency)
                  .window(
                      "window",
                      true,
                      Instant.parse("2026-09-26T16:00:00Z"),
                      Instant.parse("2026-09-26T18:00:00Z"),
                      "Europe/London")
                  .text("shop", "Hollins Grocers"));

  /**
   * Ship-from-store and dark-store picking: a pickup order picked and packed in full is ready for
   * its shopper to collect.
   */
  static final MessageType ORDER_READY_FOR_COLLECTION =
      new MessageType(
          "ORDER_READY_FOR_COLLECTION",
          "Order ready for collection",
          Audience.CUSTOMER,
          "Sent to a shopper with an account when their pickup order is picked, packed and"
              + " waiting for them at the counter.",
          List.of(v("order", "TEXT", "The order's reference"), SHOP),
          List.of(
              new FormSpec(
                  Form.EMAIL,
                  "Your order is ready to collect",
                  "Your order {{order}} is ready to collect.\n\nBring this message or your order"
                      + " number to the counter.\n\n— {{shop}}",
                  List.of(Set.of("order"))),
              new FormSpec(
                  Form.PUSH,
                  "Ready to collect",
                  "Order {{order}} is ready to collect",
                  List.of(Set.of("order")))),
          currency ->
              Values.of()
                  .text("order", "01a0c42a-11a0-76f6-a69f-c3297150342e")
                  .text("shop", "Hollins Grocers"));

  /** Ship-from-store: a picked delivery order left the store with a carrier. */
  static final MessageType ORDER_DISPATCHED =
      new MessageType(
          "ORDER_DISPATCHED",
          "Order on its way",
          Audience.CUSTOMER,
          "Sent to a shopper with an account when their delivery order leaves the store with a"
              + " carrier, naming the carrier and its reference when the store noted one.",
          List.of(
              v("order", "TEXT", "The order's reference"),
              v("carrier", "TEXT", "Who is carrying it"),
              v("reference", "TEXT", "The carrier's reference or tracking number, when known"),
              SHOP),
          List.of(
              new FormSpec(
                  Form.EMAIL,
                  "Your order is on its way",
                  "Your order {{order}} left with {{carrier}}.{{#reference}}\nReference:"
                      + " {{reference}}{{/reference}}\n\n— {{shop}}",
                  List.of(Set.of("order"), Set.of("carrier"))),
              new FormSpec(
                  Form.PUSH,
                  "On its way",
                  "Order {{order}} left with {{carrier}}{{#reference}} — {{reference}}{{/reference}}",
                  List.of(Set.of("order")))),
          currency ->
              Values.of()
                  .text("order", "01a0c42a-11a0-76f6-a69f-c3297150342e")
                  .text("carrier", "DPD")
                  .text("reference", "15501234567890")
                  .text("shop", "Hollins Grocers"));

  /** Substitutions for out-of-stock online lines: an item the store could not include. */
  static final MessageType ORDER_LINE_SHORT =
      new MessageType(
          "ORDER_LINE_SHORT",
          "Item unavailable",
          Audience.CUSTOMER,
          "Sent to a shopper with an account when the store closes a line of their online order"
              + " short: what they will not get, and what goes back to them for it.",
          List.of(
              v("order", "TEXT", "The order's reference"),
              v("item", "TEXT", "The product the store could not include"),
              v("qty", "TEXT", "How many of it"),
              v("refund", "MONEY", "What goes back to the shopper, when anything does"),
              SHOP),
          List.of(
              new FormSpec(
                  Form.EMAIL,
                  "An item in your order was unavailable",
                  "We could not include {{qty}} × {{item}} in your order {{order}}.{{#refund}}\n"
                      + "{{refund}} goes back to the way you paid.{{/refund}}\n\n— {{shop}}",
                  List.of(Set.of("order"), Set.of("item"))),
              new FormSpec(
                  Form.PUSH,
                  "Item unavailable",
                  "{{item}} was unavailable for order {{order}}{{#refund}} — {{refund}}"
                      + " refunded{{/refund}}",
                  List.of(Set.of("order")))),
          currency ->
              Values.of()
                  .text("order", "01a0c42a-11a0-76f6-a69f-c3297150342e")
                  .text("item", "Braeburn apples 1kg")
                  .text("qty", "2")
                  .money("refund", new BigDecimal("3.80"), currency)
                  .text("shop", "Hollins Grocers"));

  /** Substitutions for out-of-stock online lines: a stand-in went in the bag. */
  static final MessageType ORDER_LINE_SUBSTITUTED =
      new MessageType(
          "ORDER_LINE_SUBSTITUTED",
          "Item substituted",
          Audience.CUSTOMER,
          "Sent to a shopper with an account when the store puts a substitute in their online"
              + " order for a line it could not fill: what was swapped for what, that they pay no"
              + " more, and what goes back when the substitute cost less.",
          List.of(
              v("order", "TEXT", "The order's reference"),
              v("item", "TEXT", "The product that was unavailable"),
              v("substitute", "TEXT", "What went in the bag instead"),
              v("qty", "TEXT", "How many"),
              v("refund", "MONEY", "The difference going back, when the substitute cost less"),
              SHOP),
          List.of(
              new FormSpec(
                  Form.EMAIL,
                  "We substituted an item in your order",
                  "{{item}} was unavailable, so your order {{order}} has {{qty}} × {{substitute}}"
                      + " instead. You pay no more than you did{{#refund}}, and {{refund}} goes"
                      + " back to the way you paid{{/refund}}.\n\nIf you would rather not keep it,"
                      + " hand it back when you collect or to the driver and it is refunded.\n\n—"
                      + " {{shop}}",
                  List.of(Set.of("order"), Set.of("item"), Set.of("substitute"))),
              new FormSpec(
                  Form.PUSH,
                  "Item substituted",
                  "{{substitute}} replaces {{item}} in order {{order}}{{#refund}} — {{refund}}"
                      + " refunded{{/refund}}",
                  List.of(Set.of("order")))),
          currency ->
              Values.of()
                  .text("order", "01a0c42a-11a0-76f6-a69f-c3297150342e")
                  .text("item", "Braeburn apples 1kg")
                  .text("substitute", "Gala apples 1kg")
                  .text("qty", "2")
                  .money("refund", new BigDecimal("0.40"), currency)
                  .text("shop", "Hollins Grocers"));

  /** GPSR (EU) 2023/988 art.36(2): the parts a recall notice to a buyer must have. */
  private static final Set<String> RECALL_REMEDY =
      Set.of("remedies", "remedy_refund", "remedy_replacement", "remedy_repair");

  private static final Set<String> RECALL_CONTACT =
      Set.of("contact", "contact_phone", "contact_url");

  private static final Set<String> RECALL_HAZARD =
      Set.of(
          "hazard",
          "hazard_code",
          "hazard_microbiological",
          "hazard_allergen",
          "hazard_foreign_body",
          "hazard_chemical",
          "hazard_labelling",
          "hazard_quality");

  static final MessageType RECALL_NOTICE =
      new MessageType(
          "RECALL_NOTICE",
          "Product safety recall",
          Audience.CUSTOMER,
          "Sent to every buyer of a recalled product (GPSR art.36). The notice must say what the"
              + " product is, the hazard, what to do, the remedy and whom to contact: a template"
              + " that leaves any of them out is refused.",
          List.of(
              v("reference", "TEXT", "The recall's reference"),
              v("products", "LIST", "What the buyer bought, one item per line"),
              v("products.name", "TEXT", "The product's name"),
              v("products.sku", "TEXT", "Its SKU"),
              v("products.lot", "TEXT", "The lot or batch"),
              v("products.best_before", "DAY", "Its best-before or use-by date"),
              v("products.quantity", "NUMBER", "How many were bought"),
              v("product", "TEXT", "The first product, written out in English"),
              v("bought_on", "DAY", "The day of the purchase"),
              v("order", "TEXT", "The order's reference"),
              v("hazard", "TEXT", "The hazard, in English: \"undeclared allergen\""),
              v("hazard_code", "TEXT", "The hazard as a code: ALLERGEN, MICROBIOLOGICAL…"),
              v("hazard_microbiological", "FLAG", "True for microbiological contamination"),
              v("hazard_allergen", "FLAG", "True for an undeclared allergen"),
              v("hazard_foreign_body", "FLAG", "True for a foreign body"),
              v("hazard_chemical", "FLAG", "True for chemical contamination"),
              v("hazard_labelling", "FLAG", "True for a labelling error"),
              v("hazard_quality", "FLAG", "True for a quality defect"),
              v("reason", "TEXT", "Why the product is recalled, as the business wrote it"),
              v("what_to_do", "TEXT", "What the buyer should do, as the business wrote it"),
              v(
                  "remedies",
                  "TEXT",
                  "The remedies offered, in English: \"a refund or a replacement\""),
              v("remedy_refund", "FLAG", "True when a refund is offered"),
              v("remedy_replacement", "FLAG", "True when a replacement is offered"),
              v("remedy_repair", "FLAG", "True when a repair is offered"),
              v("single_remedy_reason", "TEXT", "Why only one remedy is offered, when so"),
              v("contact", "TEXT", "The phone and web address to contact, in English"),
              v("contact_phone", "TEXT", "The phone number to contact"),
              v("contact_url", "TEXT", "The web address to contact"),
              SHOP),
          List.of(
              new FormSpec(
                  Form.EMAIL,
                  "Product safety recall — {{reference}}",
                  "PRODUCT SAFETY RECALL\nReference {{reference}}\n\n"
                      + "What: {{#products}}{{name}}{{#sku}} ({{sku}}){{/sku}}{{#lot}}, lot {{lot}}{{/lot}}"
                      + "{{#best_before}}, best before {{best_before}}{{/best_before}}, {{quantity}} bought"
                      + "{{^last}}; {{/last}}{{/products}}\n"
                      + "Bought on {{bought_on}}, order {{order}}\n\n"
                      + "Hazard: {{hazard}}. {{reason}}\n\n"
                      + "What to do: Stop using this product immediately. {{what_to_do}}\n\n"
                      + "Your remedy — you choose: {{remedies}}."
                      + "{{#single_remedy_reason}} {{single_remedy_reason}}{{/single_remedy_reason}}\n\n"
                      + "Contact: {{contact}}\n"
                      + "Please pass this notice to anyone you have shared the product with.\n\n"
                      + "— {{shop}}",
                  List.of(
                      Set.of("reference"),
                      Set.of("products", "product"),
                      RECALL_HAZARD,
                      Set.of("what_to_do"),
                      RECALL_REMEDY,
                      RECALL_CONTACT)),
              new FormSpec(
                  Form.SMS,
                  // A text message has no subject; this one names it in the delivery log only.
                  "Product safety recall — {{reference}}",
                  "PRODUCT SAFETY RECALL {{reference}}: {{product}}. Stop using it now. {{hazard}}."
                      + " You may choose {{remedies}}. Contact {{contact}}",
                  List.of(
                      Set.of("reference"),
                      Set.of("products", "product"),
                      RECALL_REMEDY,
                      RECALL_CONTACT)),
              new FormSpec(
                  Form.PUSH,
                  "Product safety recall — {{reference}}",
                  "Stop using {{product}}. Open the app for what to do and your remedy.",
                  List.of(Set.of("products", "product")))),
          currency ->
              Values.of()
                  .text("reference", "RC-2026-014")
                  .items(
                      "products",
                      List.of(
                          Values.of()
                              .text("name", "Crunchy peanut butter")
                              .text("sku", "PB-340")
                              .text("lot", "L-2291")
                              .day("best_before", LocalDate.of(2026, 10, 1))
                              .number("quantity", BigDecimal.valueOf(2))))
                  .text("product", "Crunchy peanut butter (PB-340), lot L-2291, 2 bought")
                  .day("bought_on", LocalDate.of(2026, 9, 3))
                  .text("order", "01a0c42a-11a0-76f6-a69f-c3297150342e")
                  .text("hazard", "undeclared allergen")
                  .text("hazard_code", "ALLERGEN")
                  .flag("hazard_allergen", true)
                  .text("reason", "Contains sesame, which is not on the label.")
                  .text("what_to_do", "Do not eat it. Return it to the shop.")
                  .text("remedies", "a refund or a replacement")
                  .flag("remedy_refund", true)
                  .flag("remedy_replacement", true)
                  .flag("remedy_repair", false)
                  .text("contact", "0800 100 200 or https://hollins.example/recalls")
                  .text("contact_phone", "0800 100 200")
                  .text("contact_url", "https://hollins.example/recalls")
                  .text("shop", "Hollins Grocers"));

  // ── suppliers
  // ─────────────────────────────────────────────────────────────────────────────────────

  static final MessageType SUPPLIER_REMITTANCE =
      new MessageType(
          "SUPPLIER_REMITTANCE",
          "Remittance advice",
          Audience.SUPPLIER,
          "Sent to a supplier's remittance address when a payment run pays them: what was paid and"
              + " for which invoices.",
          List.of(
              v("reference", "TEXT", "The payment's reference"),
              v("payment_date", "DAY", "The day the payment goes"),
              v("supplier", "TEXT", "The supplier's name"),
              v("items", "LIST", "Each invoice paid and credit note set against it"),
              v("items.reference", "TEXT", "The document's number"),
              v("items.document_date", "DAY", "The document's date"),
              v("items.amount", "MONEY", "Its amount, always positive"),
              v("items.credit", "FLAG", "True for a credit note, which is taken off"),
              v("total", "MONEY", "What was paid"),
              SHOP),
          List.of(
              new FormSpec(
                  Form.EMAIL,
                  "Remittance advice {{reference}}",
                  "Remittance advice\n\nPayment {{reference}} on {{payment_date}}\nTo {{supplier}}\n\n"
                      + "{{#items}}{{#credit}}Less credit note {{/credit}}{{^credit}}Invoice {{/credit}}"
                      + "{{reference}}{{#document_date}} of {{document_date}}{{/document_date}}: "
                      + "{{#credit}}-{{/credit}}{{amount}}\n{{/items}}"
                      + "\nTotal paid: {{total}}\n\n"
                      + "Please quote {{reference}} in any query about this payment.\n",
                  List.of(Set.of("reference"), Set.of("total")))),
          currency ->
              Values.of()
                  .text("reference", "PR-000031")
                  .day("payment_date", LocalDate.of(2026, 9, 24))
                  .text("supplier", "Acme Foods Ltd")
                  .items(
                      "items",
                      List.of(
                          Values.of()
                              .text("reference", "INV-4410")
                              .day("document_date", LocalDate.of(2026, 8, 30))
                              .money("amount", new BigDecimal("1250.00"), currency)
                              .flag("credit", false),
                          Values.of()
                              .text("reference", "CN-118")
                              .day("document_date", LocalDate.of(2026, 9, 2))
                              .money("amount", new BigDecimal("40.00"), currency)
                              .flag("credit", true)))
                  .money("total", new BigDecimal("1210.00"), currency)
                  .text("shop", "Hollins Grocers"));

  // ── staff
  // ─────────────────────────────────────────────────────────────────────────────────────────

  static final MessageType STOCK_BELOW_THRESHOLD =
      new MessageType(
          "STOCK_BELOW_THRESHOLD",
          "Stock below threshold",
          Audience.STAFF,
          "To a store when a product's available stock falls below its reorder threshold.",
          List.of(
              v("variant", "TEXT", "The product variant"),
              v("store", "TEXT", "The store"),
              v("available", "NUMBER", "What is available now"),
              v("threshold", "NUMBER", "The threshold it fell below")),
          List.of(
              new FormSpec(
                  Form.ALERT,
                  "Stock below threshold",
                  "Variant {{variant}} at store {{store}}: available {{available}} (threshold"
                      + " {{threshold}})",
                  NONE)),
          currency ->
              Values.of()
                  .text("variant", "01a0c42a-3d45-70cf-b661-39c95b6b542b")
                  .text("store", "01a0c42a-fede-7391-982e-4b81b74bdce4")
                  .number("available", BigDecimal.valueOf(3))
                  .number("threshold", BigDecimal.valueOf(10)));

  static final MessageType FOOD_SAFETY_CHECK_FAILED =
      new MessageType(
          "FOOD_SAFETY_CHECK_FAILED",
          "Food safety check failed",
          Audience.STAFF,
          "To a store when a check at a monitoring point reads outside its limits.",
          List.of(
              v("point", "TEXT", "The monitoring point, e.g. \"Dairy chiller\""),
              v("reading", "NUMBER", "The temperature read, in °C, when there was one"),
              v("min", "NUMBER", "The lower limit, when there is one"),
              v("max", "NUMBER", "The upper limit, when there is one")),
          List.of(
              new FormSpec(
                  Form.ALERT,
                  "Food safety check failed: {{point}}",
                  "{{#reading}}{{point}} read {{reading}} °C against "
                      + "{{#min}}{{#max}}limits of {{min}} °C to {{max}} °C{{/max}}"
                      + "{{^max}}a limit of at least {{min}} °C{{/max}}{{/min}}"
                      + "{{^min}}a limit of at most {{max}} °C{{/min}}.{{/reading}}"
                      + "{{^reading}}{{point}} was recorded as failed.{{/reading}}"
                      + " Record what was done about it on the Food safety screen.",
                  List.of(Set.of("point")))),
          currency ->
              Values.of()
                  .text("point", "Dairy chiller")
                  .number("reading", new BigDecimal("8.5"))
                  .number("min", BigDecimal.ZERO)
                  .number("max", BigDecimal.valueOf(5)));

  static final MessageType FOOD_SAFETY_CHECK_OVERDUE =
      new MessageType(
          "FOOD_SAFETY_CHECK_OVERDUE",
          "Food safety check overdue",
          Audience.STAFF,
          "To a store when a monitoring point's check is past due and none has been recorded.",
          List.of(
              v("point", "TEXT", "The monitoring point"),
              v("due_since", "MOMENT", "When the check fell due, in UTC")),
          List.of(
              new FormSpec(
                  Form.ALERT,
                  "Food safety check overdue: {{point}}",
                  "{{point}} was due a check at {{due_since}} and none has been recorded. Take it"
                      + " now on the Food safety screen.",
                  List.of(Set.of("point")))),
          currency ->
              Values.of()
                  .text("point", "Dairy chiller")
                  .moment("due_since", Instant.parse("2026-09-21T08:00:00Z")));

  static final MessageType STORE_TASK_MISSED =
      new MessageType(
          "STORE_TASK_MISSED",
          "Task not done",
          Audience.STAFF,
          "To a store when a task or an opening or closing list fell due and was not done.",
          List.of(
              v("title", "TEXT", "The task's title"),
              v("opening", "FLAG", "True for the opening list"),
              v("closing", "FLAG", "True for the closing list"),
              v("date", "DAY", "The trading day it was for"),
              v("required", "FLAG", "True when it may not be skipped")),
          List.of(
              new FormSpec(
                  Form.ALERT,
                  "Not done: {{title}}",
                  "{{#opening}}The opening list{{/opening}}{{#closing}}The closing list{{/closing}}"
                      + "{{^opening}}{{^closing}}The task{{/closing}}{{/opening}}"
                      + " \"{{title}}\" for {{date}} fell due and was not done"
                      + "{{#required}}, and it is required{{/required}}."
                      + " Do it now if it still can be, or record why it was skipped.",
                  List.of(Set.of("title")))),
          currency ->
              Values.of()
                  .text("title", "Lock up")
                  .flag("opening", false)
                  .flag("closing", true)
                  .day("date", LocalDate.of(2026, 9, 19))
                  .flag("required", true));

  static final MessageType STORE_NOTICE_URGENT =
      new MessageType(
          "STORE_NOTICE_URGENT",
          "Urgent notice",
          Audience.STAFF,
          "Wakes a store's devices when management publishes an urgent notice.",
          List.of(
              v("title", "TEXT", "The notice's title"),
              v("acknowledge", "FLAG", "True when staff must acknowledge it")),
          List.of(
              new FormSpec(
                  Form.ALERT,
                  "Urgent notice: {{title}}",
                  "Management has published \"{{title}}\". Read it on the notices screen"
                      + "{{#acknowledge}} and acknowledge it.{{/acknowledge}}{{^acknowledge}}.{{/acknowledge}}",
                  List.of(Set.of("title")))),
          currency ->
              Values.of().text("title", "Freezer 3 is out of use").flag("acknowledge", true));

  static final MessageType RECALL_OPENED =
      new MessageType(
          "RECALL_OPENED",
          "Recall or withdrawal opened",
          Audience.STAFF,
          "To each store holding stock when a recall or a withdrawal takes it off sale.",
          List.of(
              v("reference", "TEXT", "The recall's reference"),
              v("recall", "FLAG", "True for a recall; false for a withdrawal"),
              v("hazard", "TEXT", "The hazard, in English"),
              v("hazard_code", "TEXT", "The hazard as a code")),
          List.of(
              new FormSpec(
                  Form.ALERT,
                  "{{#recall}}Product recall{{/recall}}{{^recall}}Product withdrawal{{/recall}}"
                      + " {{reference}}: stock taken off sale",
                  "{{#recall}}Product recall{{/recall}}{{^recall}}Product withdrawal{{/recall}}"
                      + " {{reference}} ({{hazard}}) has taken stock at this store off sale. Pull it"
                      + " from the shelves and record what you found on the Recalls screen"
                      + "{{#recall}}, and display the recall notice at the tills.{{/recall}}"
                      + "{{^recall}}.{{/recall}}",
                  List.of(Set.of("reference")))),
          currency ->
              Values.of()
                  .text("reference", "RC-2026-014")
                  .flag("recall", true)
                  .text("hazard", "undeclared allergen")
                  .text("hazard_code", "ALLERGEN"));

  static final MessageType PAYMENT_DISPUTE_OPENED =
      new MessageType(
          "PAYMENT_DISPUTE_OPENED",
          "Chargeback opened",
          Audience.STAFF,
          "To the store, or the business, when a cardholder's bank disputes a card payment.",
          List.of(
              v("amount", "MONEY", "The amount disputed"),
              v("reason", "TEXT", "The bank's reason, in English words: \"fraudulent\""),
              v("reason_code", "TEXT", "The reason as a code: FRAUDULENT, DUPLICATE…"),
              v("due_by", "MOMENT", "When the evidence is due, in UTC, when the bank says")),
          List.of(
              new FormSpec(
                  Form.ALERT,
                  "Chargeback: {{amount}} disputed",
                  "A cardholder's bank has disputed a card payment of {{amount}} ({{reason}})."
                      + " {{#due_by}}Answer it on the Disputes screen by {{due_by}}: after that it"
                      + " is lost.{{/due_by}}{{^due_by}}Answer it on the Disputes screen as soon as"
                      + " you can.{{/due_by}}",
                  List.of(Set.of("amount")))),
          currency ->
              Values.of()
                  .money("amount", new BigDecimal("42.50"), currency)
                  .text("reason", "fraudulent")
                  .text("reason_code", "FRAUDULENT")
                  .moment("due_by", Instant.parse("2026-10-05T23:59:00Z")));

  // ── the business, from the platform (21.12)
  // ─────────────────────────────────────────────────────────────────────────────────────

  /** What every billing notice must keep: which invoice, how much, and the way to pay it. */
  private static final List<Set<String>> BILLING_PARTS =
      List.of(Set.of("invoice"), Set.of("amount_due"), Set.of("pay_link"));

  private static final Variable PLATFORM =
      v("shop", "TEXT", "The platform's name, as it invoices — this notice is from it");

  private static final List<Variable> BILLING_VARIABLES =
      List.of(
          v("invoice", "TEXT", "The invoice's number"),
          v("amount_due", "MONEY", "What is left to pay on it"),
          v("due_date", "DAY", "The day it was due"),
          v("days_overdue", "NUMBER", "How many days past that day the notice went"),
          v(
              "pay_link",
              "TEXT",
              "A link that pays the invoice with no sign-in; the newest one is the one that works"),
          v(
              "suspend_on",
              "DAY",
              "The day the service is interrupted if it stays unpaid; absent once it has been"),
          PLATFORM);

  private static Values billingSample(String currency) {
    return Values.of()
        .text("invoice", "INV-2026-000041")
        .money("amount_due", new BigDecimal("29.00"), currency)
        .day("due_date", LocalDate.of(2026, 9, 15))
        .number("days_overdue", new BigDecimal("3"))
        .text("pay_link", "https://app.example/#/pay/9m2xKq1vT8sHc4bYw7Lp3Q")
        .day("suspend_on", LocalDate.of(2026, 9, 29))
        .text("shop", "StoreQL Platform Ltd");
  }

  static final MessageType INVOICE_OVERDUE =
      new MessageType(
          "INVOICE_OVERDUE",
          "Invoice overdue",
          Audience.STAFF,
          "From the platform to the business's billing address when an invoice for the platform"
              + " itself is past its date: a reminder at each day the dunning policy names.",
          BILLING_VARIABLES,
          List.of(
              new FormSpec(
                  Form.EMAIL,
                  "Invoice {{invoice}} is overdue: {{amount_due}}",
                  "Invoice {{invoice}} for {{amount_due}} was due on {{due_date}} and has not been"
                      + " paid.\n\nPay it here, no sign-in needed:\n{{pay_link}}\n\n{{#suspend_on}}If"
                      + " it is still unpaid on {{suspend_on}}, your service will be interrupted"
                      + " until it is paid.{{/suspend_on}}{{^suspend_on}}Paying it brings your service"
                      + " back at once.{{/suspend_on}}\n\nIf you have already paid, or need more"
                      + " time, reply to this message.\n\n— {{shop}}",
                  BILLING_PARTS)),
          Catalogue::billingSample);

  static final MessageType SERVICE_SUSPENDED =
      new MessageType(
          "SERVICE_SUSPENDED",
          "Service interrupted",
          Audience.STAFF,
          "From the platform to the business's billing address the day its service is interrupted"
              + " for non-payment: what is owed, and the link that brings the service back.",
          BILLING_VARIABLES,
          List.of(
              new FormSpec(
                  Form.EMAIL,
                  "Your service is interrupted: invoice {{invoice}} is unpaid",
                  "Invoice {{invoice}} for {{amount_due}}, due on {{due_date}}, is still unpaid, so"
                      + " your service is interrupted: your staff cannot sign in and your storefront"
                      + " is closed.\n\nPaying it brings everything back at once, no sign-in"
                      + " needed:\n{{pay_link}}\n\nIf you believe this is a mistake, reply to this"
                      + " message.\n\n— {{shop}}",
                  BILLING_PARTS)),
          Catalogue::billingSample);

  // ── the trial (21.13), from the platform
  // ─────────────────────────────────────────────────────────────────────────────────────

  private static final List<Variable> TRIAL_VARIABLES =
      List.of(
          v("plan", "TEXT", "The plan the trial is of"),
          v("trial_end", "DAY", "The day the trial ends"),
          v("price", "MONEY", "What the plan costs from then, each period"),
          v("interval", "TEXT", "How often it is billed: MONTH or YEAR"),
          PLATFORM);

  private static final List<Variable> TRIAL_ENDED_VARIABLES =
      List.of(
          v("plan", "TEXT", "The plan the trial was of"),
          v("trial_end", "DAY", "The day the trial ended"),
          v("price", "MONEY", "What the plan costs, each period"),
          v("interval", "TEXT", "How often it is billed: MONTH or YEAR"),
          v("invoice", "TEXT", "The first invoice's number"),
          v("amount_due", "MONEY", "What the first invoice comes to"),
          v("due_date", "DAY", "The day it is due"),
          v("pay_link", "TEXT", "A link that pays it with no sign-in"),
          PLATFORM);

  static final MessageType TRIAL_ENDING =
      new MessageType(
          "TRIAL_ENDING",
          "Trial ending",
          Audience.STAFF,
          "From the platform to the business's billing address a few days before its free trial"
              + " ends: the day, and what the plan costs from then.",
          TRIAL_VARIABLES,
          List.of(
              new FormSpec(
                  Form.EMAIL,
                  "Your trial of {{plan}} ends on {{trial_end}}",
                  "Your free trial of {{plan}} ends on {{trial_end}}. From then it is {{price}} a"
                      + " {{interval}}, billed in advance; the first invoice comes on that day.\n\nIf"
                      + " you would rather not continue, cancel before then from Billing and nothing"
                      + " is owed.\n\n— {{shop}}",
                  List.of(Set.of("plan"), Set.of("trial_end"), Set.of("price")))),
          currency ->
              Values.of()
                  .text("plan", "Starter")
                  .day("trial_end", LocalDate.of(2026, 10, 6))
                  .money("price", new BigDecimal("49.00"), currency)
                  .text("interval", "MONTH")
                  .text("shop", "StoreQL Platform Ltd"));

  static final MessageType TRIAL_ENDED =
      new MessageType(
          "TRIAL_ENDED",
          "Trial ended",
          Audience.STAFF,
          "From the platform to the business's billing address the day its trial ends: the first"
              + " invoice, and the link that pays it.",
          TRIAL_ENDED_VARIABLES,
          List.of(
              new FormSpec(
                  Form.EMAIL,
                  "Your trial has ended: invoice {{invoice}} for {{amount_due}}",
                  "Your free trial of {{plan}} has ended, and your first invoice, {{invoice}} for"
                      + " {{amount_due}}, is due on {{due_date}}.\n\nPay it here, no sign-in"
                      + " needed:\n{{pay_link}}\n\nThank you for staying with us.\n\n— {{shop}}",
                  List.of(Set.of("invoice"), Set.of("amount_due"), Set.of("pay_link")))),
          currency ->
              Values.of()
                  .text("plan", "Starter")
                  .day("trial_end", LocalDate.of(2026, 10, 6))
                  .money("price", new BigDecimal("49.00"), currency)
                  .text("interval", "MONTH")
                  .text("invoice", "INV-2026-000042")
                  .money("amount_due", new BigDecimal("60.27"), currency)
                  .day("due_date", LocalDate.of(2026, 10, 13))
                  .text("pay_link", "https://app.example/#/pay/9m2xKq1vT8sHc4bYw7Lp3Q")
                  .text("shop", "StoreQL Platform Ltd"));

  private static final Map<String, MessageType> ALL = new LinkedHashMap<>();

  static {
    for (MessageType t :
        List.of(
            ORDER_CONFIRMED,
            ORDER_READY_FOR_COLLECTION,
            ORDER_DISPATCHED,
            ORDER_LINE_SHORT,
            ORDER_LINE_SUBSTITUTED,
            RECALL_NOTICE,
            SUPPLIER_REMITTANCE,
            STOCK_BELOW_THRESHOLD,
            FOOD_SAFETY_CHECK_FAILED,
            FOOD_SAFETY_CHECK_OVERDUE,
            STORE_TASK_MISSED,
            STORE_NOTICE_URGENT,
            RECALL_OPENED,
            PAYMENT_DISPUTE_OPENED,
            INVOICE_OVERDUE,
            SERVICE_SUSPENDED,
            TRIAL_ENDING,
            TRIAL_ENDED)) {
      ALL.put(t.key(), t);
    }
  }

  public static List<MessageType> all() {
    return List.copyOf(ALL.values());
  }

  public static Optional<MessageType> find(String key) {
    return Optional.ofNullable(key == null ? null : ALL.get(key));
  }

  public static MessageType get(String key) {
    return find(key).orElseThrow(() -> new IllegalArgumentException("no message " + key));
  }
}
