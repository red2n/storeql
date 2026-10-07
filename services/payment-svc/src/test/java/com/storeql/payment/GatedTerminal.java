package com.storeql.payment;

import com.storeql.payment.domain.Terminals;
import com.storeql.payment.provider.CardTerminal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A card machine whose cardholder takes as long as the test says: a sale waits at the machine until
 * {@link #answer} is called, the way a real one waits for a PIN. That is the window in which a till
 * cancels, a sale is given up or a person decides — the races the server must win with no help from
 * the till. Added to an integration test as a bean ({@code @AddBean}) under a vendor the deployment
 * otherwise has no driver for, so the simulator's own machines are untouched.
 *
 * <p>One sale at a time: {@link #arm} before the press, {@link #atMachine} to wait for it, {@link
 * #answer} to finish it. A refund is put back at once, unless {@link #armRefund} held the next one
 * at the machine the same way; a cancel says "cancelled", as a driver that cannot know better
 * would, and is counted so a test can see it was sent.
 */
public class GatedTerminal implements CardTerminal {

  public static final String VENDOR = "VERIFONE";

  private static volatile CountDownLatch arrived = new CountDownLatch(1);
  private static volatile CompletableFuture<Terminals.Outcome> answer = new CompletableFuture<>();
  private static final AtomicInteger CANCELS = new AtomicInteger();
  private static volatile boolean refundHeld;

  /** Readies the machine for the next sale: it will wait at the machine until answered. */
  public static void arm() {
    arrived = new CountDownLatch(1);
    answer = new CompletableFuture<>();
  }

  /**
   * Readies the machine for the next refund: it waits at the machine until {@link #answer}, so a
   * test can see what a refund still at the machine, or one it never answers, does to it.
   */
  public static void armRefund() {
    arm();
    refundHeld = true;
  }

  /** Waits until the armed sale is at the machine, the cardholder about to enter a PIN. */
  public static void atMachine() throws InterruptedException {
    if (!arrived.await(30, TimeUnit.SECONDS)) {
      throw new AssertionError("the sale never reached the machine");
    }
  }

  /** What the machine finally says about the armed sale. */
  public static void answer(Terminals.Outcome outcome) {
    answer.complete(outcome);
  }

  /** How many times a till asked this machine to stop. */
  public static int cancels() {
    return CANCELS.get();
  }

  public static Terminals.Outcome approval(String ref) {
    return new Terminals.Outcome(
        Terminals.APPROVED,
        "VISA",
        "4242",
        "GATE01",
        "A0000000031010",
        "VISA DEBIT",
        "CONTACTLESS",
        "DEVICE",
        ref,
        null);
  }

  @Override
  public String vendor() {
    return VENDOR;
  }

  @Override
  public boolean available() {
    return true;
  }

  @Override
  public Terminals.Outcome sale(Request request) {
    return atTheMachine();
  }

  private static Terminals.Outcome atTheMachine() {
    arrived.countDown();
    try {
      return answer.get(60, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Terminals.refused(Terminals.TIMED_OUT, null, "interrupted at the machine");
    } catch (Exception e) {
      return Terminals.refused(Terminals.TIMED_OUT, null, "no answer from the machine");
    }
  }

  @Override
  public Terminals.Outcome refund(Request request, String originalProviderRef) {
    if (refundHeld) {
      refundHeld = false;
      return atTheMachine();
    }
    return new Terminals.Outcome(
        Terminals.APPROVED,
        "VISA",
        "4242",
        "GATE02",
        "A0000000031010",
        "VISA DEBIT",
        "CHIP",
        "NONE",
        "GATE-R-" + request.reference(),
        null);
  }

  @Override
  public Terminals.Outcome cancel(String providerRef) {
    CANCELS.incrementAndGet();
    return Terminals.refused(Terminals.CANCELLED, providerRef, "Cancelled at the till");
  }
}
