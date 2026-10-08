package com.storeql.payment.dto;

import com.storeql.web.PendingWorkCount;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** What waits for a person in payments, on the wire. */
public final class PendingWorkDtos {
  private PendingWorkDtos() {}

  @Schema(
      name = "PaymentPendingWork",
      description =
          "How many things wait for a person in payments, for the whole business. Each count"
              + " stops at "
              + PendingWorkCount.CAP
              + ": a count of "
              + PendingWorkCount.CAP
              + " means "
              + PendingWorkCount.CAP
              + " or more.")
  public record PendingWorkResponse(
      @Schema(
              description =
                  "Card refunds in NEEDS_ATTENTION: the card machine could not put the money"
                      + " back, and a manager has to say how it is given back. Counted to at most "
                      + PendingWorkCount.CAP
                      + "; "
                      + PendingWorkCount.CAP
                      + " means "
                      + PendingWorkCount.CAP
                      + " or more.")
          long cardRefundDuesNeedingAttention) {}
}
