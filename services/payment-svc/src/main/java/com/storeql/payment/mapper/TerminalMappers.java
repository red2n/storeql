package com.storeql.payment.mapper;

import com.storeql.payment.domain.CardSettlement;
import com.storeql.payment.domain.Terminals.Attempt;
import com.storeql.payment.domain.Terminals.Terminal;
import com.storeql.payment.dto.TerminalDtos;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Terminals and their attempts as the wire carries them (07.16). */
public final class TerminalMappers {

  private TerminalMappers() {}

  public static TerminalDtos.TerminalResponse toDto(Terminal t) {
    return new TerminalDtos.TerminalResponse(
        t.id().toString(),
        t.storeId().toString(),
        t.label(),
        t.vendor(),
        t.serial(),
        t.status(),
        t.retiredReason(),
        text(t.createdAt()),
        text(t.updatedAt()));
  }

  /**
   * An attempt on the wire.
   *
   * <p>The amount goes out as a string, like every other amount on this platform: a JSON number is
   * a double by the time a browser has parsed it, and this one is money on a customer's card.
   */
  public static TerminalDtos.AttemptResponse toDto(Attempt a) {
    return toDto(a, null, null);
  }

  /** An attempt with where it stands and a person's word on it. */
  public static TerminalDtos.AttemptResponse toDto(CardSettlement.Facts f) {
    return toDto(f.attempt(), f.decision(), f.standing().name());
  }

  private static TerminalDtos.AttemptResponse toDto(
      Attempt a, CardSettlement.Decision d, String standing) {
    return new TerminalDtos.AttemptResponse(
        a.id().toString(),
        a.terminalId().toString(),
        a.orderId().toString(),
        // At the currency's own minor units, as it was taken: the column holds four places for any
        // currency, and 1250.0000 yen or 12.5000 pounds is not how money is written.
        com.storeql.payment.service.Amounts.shown(a.amount(), a.currency()).toPlainString(),
        a.currency(),
        a.kind(),
        text(a.refundOf()),
        a.state(),
        a.outcomeDetail(),
        a.scheme(),
        a.panLast4(),
        a.authCode(),
        a.aid(),
        a.applicationLabel(),
        a.entryMode(),
        a.verification(),
        a.providerRef(),
        text(a.paymentId()),
        a.receiptLine(),
        text(a.requestedAt()),
        text(a.settledAt()),
        text(a.requestedBy()),
        a.reason(),
        text(a.dueId()),
        standing,
        d == null
            ? null
            : new TerminalDtos.DecisionResponse(
                d.outcome(), d.reason(), text(d.decidedBy()), text(d.decidedAt())));
  }

  public static List<TerminalDtos.AttemptResponse> facts(List<CardSettlement.Facts> all) {
    return all.stream().map(TerminalMappers::toDto).toList();
  }

  public static TerminalDtos.RefundDueResponse toDto(CardSettlement.Due d) {
    return toDto(d, null);
  }

  /**
   * A due with how it was given back another way, when it was.
   *
   * @param k what a person did instead of the machine, or null
   */
  public static TerminalDtos.RefundDueResponse toDto(
      CardSettlement.Due d, CardSettlement.Closure k) {
    return new TerminalDtos.RefundDueResponse(
        d.id().toString(),
        d.storeId().toString(),
        d.orderId().toString(),
        d.saleAttemptId().toString(),
        text(d.paymentId()),
        com.storeql.payment.service.Amounts.shown(d.amount(), d.currency()).toPlainString(),
        d.currency(),
        d.reason(),
        d.source(),
        d.state(),
        d.attention(),
        text(d.refundAttemptId()),
        text(d.refundId()),
        text(d.requestedBy()),
        text(d.createdAt()),
        text(d.updatedAt()),
        k == null
            ? null
            : new TerminalDtos.AnotherWayResponse(
                k.method(), k.reference(), k.reason(), text(k.closedBy()), text(k.closedAt())));
  }

  public static List<TerminalDtos.RefundDueResponse> dues(List<CardSettlement.Due> all) {
    return all.stream().map(TerminalMappers::toDto).toList();
  }

  public static List<TerminalDtos.TerminalResponse> terminals(List<Terminal> all) {
    return all.stream().map(TerminalMappers::toDto).toList();
  }

  public static List<TerminalDtos.AttemptResponse> attempts(List<Attempt> all) {
    return all.stream().map(TerminalMappers::toDto).toList();
  }

  private static String text(Instant at) {
    return at == null ? null : at.toString();
  }

  private static String text(UUID id) {
    return id == null ? null : id.toString();
  }
}
