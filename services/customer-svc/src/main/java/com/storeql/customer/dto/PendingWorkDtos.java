package com.storeql.customer.dto;

import com.storeql.web.PendingWorkCount;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** What waits for a person in customer records, on the wire. */
public final class PendingWorkDtos {
  private PendingWorkDtos() {}

  @Schema(
      name = "CustomerPendingWork",
      description =
          "How many things wait for a person in customer records, for the whole business. A count"
              + " is read up to "
              + PendingWorkCount.CAP
              + " and no further: "
              + PendingWorkCount.CAP
              + " means "
              + PendingWorkCount.CAP
              + " or more.")
  public record PendingWorkResponse(
      @Schema(
              description =
                  "Privacy requests in OPEN: asked by a customer, not yet answered. At most "
                      + PendingWorkCount.CAP
                      + "; a count of "
                      + PendingWorkCount.CAP
                      + " means "
                      + PendingWorkCount.CAP
                      + " or more.")
          long privacyRequestsOpen) {}
}
