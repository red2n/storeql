package com.storeql.payment.domain;

/**
 * The till's card rule (pure): how a CARD tender typed in at a till may be recorded.
 *
 * <p>A card taken on a machine StoreQL drives is recorded from that machine's approval and never
 * typed. Where the store has such a machine, a typed CARD tender is refused unless the owner has
 * allowed a standalone machine at that store; and wherever a typed CARD tender is allowed, the
 * machine's own receipt reference is required, because a card taken on a machine StoreQL does not
 * see must still be findable in the acquirer's settlement file.
 */
public final class CardTenderRule {

  private CardTenderRule() {}

  /** The most characters of a machine's receipt or authorisation reference. */
  public static final int MAX_REFERENCE = 64;

  /** How the tender is recorded. */
  public static final String TERMINAL = "TERMINAL";

  public static final String STANDALONE = "STANDALONE";

  /** What the rule decided: the entry mode, or the refusal. */
  public record Decision(
      String entryMode, String refusalCode, int refusalStatus, String refusal, String reference) {
    public boolean allowed() {
      return refusalCode == null;
    }

    static Decision of(String entryMode, String reference) {
      return new Decision(entryMode, null, 0, null, reference);
    }

    static Decision refused(int status, String code, String why) {
      return new Decision(null, code, status, why, null);
    }
  }

  /**
   * Decides a CARD tender.
   *
   * @param namesTerminalAttempt the tender names the machine's approval it is recorded from
   * @param activeTerminalAtStore the store has at least one ACTIVE registered card machine
   * @param standaloneAllowed the owner has allowed a standalone machine at the store
   * @param reference what the cashier typed from the machine's receipt, or null
   */
  public static Decision decide(
      boolean namesTerminalAttempt,
      boolean activeTerminalAtStore,
      boolean standaloneAllowed,
      String reference) {
    if (namesTerminalAttempt) {
      return Decision.of(TERMINAL, reference);
    }
    if (activeTerminalAtStore && !standaloneAllowed) {
      return Decision.refused(
          409,
          "PAYMENT_CARD_NEEDS_TERMINAL",
          "this store has a card machine: take the card on it and record its approval");
    }
    String ref = reference == null ? "" : reference.strip();
    if (ref.isEmpty()) {
      return Decision.refused(
          400,
          "PAYMENT_CARD_REFERENCE_REQUIRED",
          "a card taken on a machine we do not drive needs that machine's receipt reference");
    }
    if (ref.length() > MAX_REFERENCE) {
      return Decision.refused(
          400,
          "PAYMENT_CARD_REFERENCE_INVALID",
          "the machine's receipt reference is at most " + MAX_REFERENCE + " characters");
    }
    return Decision.of(STANDALONE, ref);
  }
}
