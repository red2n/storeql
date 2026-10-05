package com.storeql.payment.provider;

import com.storeql.ids.Ids;
import com.storeql.payment.domain.Terminals;
import com.storeql.service.Fx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigInteger;
import java.math.RoundingMode;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A terminal that behaves like one, and says on the record that nothing left the building (07.16).
 *
 * <p>Always deployed, and the default, for the same reason {@code ManualPaymentProvider} is: a
 * stack with no acquirer must still start and still sell. Every k6 flow, every integration test and
 * every demonstration runs against this, so it has to behave like the real thing in the ways that
 * matter — which means it must be able to <b>decline</b>, to be <b>cancelled</b> and to <b>time
 * out</b>, not only to approve. A simulator that always approves is what lets a decline path ship
 * broken.
 *
 * <p>The outcome is chosen from the last two minor units of the amount in its own currency (pence,
 * fils, whole yen), so a test can ask for a particular one without a switch the production path
 * would also have to carry:
 *
 * <ul>
 *   <li>{@code .01} declines — insufficient funds;
 *   <li>{@code .02} is cancelled at the pinpad;
 *   <li>{@code .03} times out, the state where money may have moved;
 *   <li>{@code .04} fails outright;
 *   <li>anything else is approved.
 * </ul>
 *
 * <p>A refund is refused by the issuer on {@code .01}, fails on {@code .04} and is never answered
 * on {@code .05} — the refund that may or may not have gone back, which holds its machine until a
 * person says what it shows. Anything else is put back ({@code .03} included, so a sale that timed
 * out and was seen approved can be put back in full).
 *
 * <p>The convention is the acquirers' own: test amounts have driven card testing for decades, and
 * it keeps the simulator's behaviour out of the request shape, so no production field exists that
 * could ask a real terminal to decline.
 */
@ApplicationScoped
public class SimulatedCardTerminal implements CardTerminal {

  /**
   * Which scheme the simulator claims. Configurable so a shop can demonstrate its own market's.
   *
   * <p>{@code @Inject} is not optional beside {@code @ConfigProperty}: without it the field is
   * never written and stays null, which is how the first run of this class produced an APPROVED
   * attempt with no scheme and no application label. The schema's own CHECK caught it, which is
   * what that constraint is for — a card receipt without a scheme is not a valid card receipt.
   */
  @Inject
  @ConfigProperty(name = "storeql.terminal.simulated.scheme", defaultValue = "VISA")
  String scheme;

  @Inject
  @ConfigProperty(
      name = "storeql.terminal.simulated.application-label",
      defaultValue = "VISA DEBIT")
  String applicationLabel;

  @Override
  public String vendor() {
    return Terminals.SIMULATED;
  }

  @Override
  public boolean available() {
    // It has no credentials to be missing, which is the point of it.
    return true;
  }

  @Override
  public Terminals.Outcome sale(Request request) {
    return switch (testAmount(request)) {
      case 1 -> refused(Terminals.DECLINED, "DECLINED — insufficient funds", request);
      case 2 -> refused(Terminals.CANCELLED, "Cancelled at the terminal", request);
      // A timeout carries a provider reference even though it has no verdict: the reference is how
      // the
      // attempt is found in the acquirer's settlement file, which is the only way to learn what
      // really
      // happened. Dropping it would leave a possible charge with nothing to match it to.
      case 3 -> refused(Terminals.TIMED_OUT, "No answer from the terminal", request);
      case 4 -> refused(Terminals.FAILED, "Terminal reported a fault", request);
      default -> approved(request, "CONTACTLESS", "DEVICE");
    };
  }

  @Override
  public Terminals.Outcome refund(Request request, String originalProviderRef) {
    // A refund goes back to the card that paid, so it needs no cardholder present and no
    // verification.
    return switch (testAmount(request)) {
      case 1 -> refused(Terminals.DECLINED, "DECLINED — refund refused by the issuer", request);
      case 4 -> refused(Terminals.FAILED, "Terminal reported a fault", request);
      // Never answered: it may have gone back or not, and only a person looking at the machine (or
      // the acquirer's file) can say. Its reference is kept for that, as a sale's timeout keeps it.
      case 5 -> refused(Terminals.TIMED_OUT, "No answer from the terminal", request);
      default -> approved(request, "CHIP", "NONE");
    };
  }

  @Override
  public Terminals.Outcome cancel(String providerRef) {
    return Terminals.refused(Terminals.CANCELLED, providerRef, "Cancelled at the till");
  }

  private Terminals.Outcome approved(Request request, String entryMode, String verification) {
    return new Terminals.Outcome(
        Terminals.APPROVED,
        scheme,
        last4(request),
        authCode(request),
        "A0000000031010",
        applicationLabel,
        entryMode,
        verification,
        reference(request),
        null);
  }

  private Terminals.Outcome refused(String state, String detail, Request request) {
    return Terminals.refused(state, reference(request), detail);
  }

  /**
   * The last two minor units of an amount, in its own currency — the pence or cents, a dinar's
   * fils, whole yen — whatever its scale.
   *
   * <p>Taken from the value at the currency's own minor units (common-service {@code
   * Fx.minorUnits}, ISO 4217) rather than an assumed two places, so {@code 1001} yen declines and
   * {@code 1.001} dinars decline, and {@code 10.5} and {@code 10.50} pounds are the same request.
   */
  private static int testAmount(Request request) {
    // HALF_UP and not UNNECESSARY: the service has already refused an amount finer than its
    // currency, so this never rounds — and a simulator that threw on a programming error would
    // turn it into an unreadable 500 instead of a tender that behaves.
    return request
        .amount()
        .setScale(Fx.minorUnits(request.currency()), RoundingMode.HALF_UP)
        .unscaledValue()
        .remainder(BigInteger.valueOf(100))
        .abs()
        .intValue();
  }

  /** Four digits that are stable for one attempt, so a receipt reprint shows the same card. */
  private static String last4(Request request) {
    int n = Math.abs(request.reference().hashCode() % 10000);
    return String.format("%04d", n);
  }

  private static String authCode(Request request) {
    return String.format("%06d", Math.abs(request.reference().hashCode() % 1_000_000));
  }

  private static String reference(Request request) {
    return "SIM-" + (request.reference() == null ? Ids.newId() : request.reference());
  }
}
