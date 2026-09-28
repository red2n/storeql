package com.storeql.tenant.service;

import com.storeql.service.CapabilityTokens;
import com.storeql.tenant.repo.DunningRepository;
import com.storeql.web.ApiException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The link that pays one invoice with no sign-in: minted for a dunning notice (21.12) and for the
 * first invoice after a trial (21.13) alike.
 *
 * <p>Only the token's hash is kept, so asking again replaces the link rather than retrieving it;
 * the newest notice is the one to act on. The link opens the web app's pay page, which is why the
 * app's public address is this service's to know.
 */
@ApplicationScoped
public class PayLinks {

  @Inject DunningRepository repo;

  /** Where the web app is reached from outside. */
  @Inject
  @ConfigProperty(name = "storeql.platform.web-url", defaultValue = "http://localhost:8088")
  String webUrl;

  /**
   * A fresh token for one invoice, stored as its hash.
   *
   * @throws ApiException 409 {@code INVOICE_NOT_OPEN} for an invoice that cannot be paid, because a
   *     link to it would be a dead end
   */
  public String mint(UUID invoiceId) {
    String token = CapabilityTokens.mint();
    if (!repo.storePayToken(invoiceId, CapabilityTokens.hash(token))) {
      throw ApiException.conflict(
          "INVOICE_NOT_OPEN", "This invoice cannot be paid, so a link to it would lead nowhere");
    }
    return token;
  }

  /** The link a notice carries for a token: the web app's pay page, which needs no sign-in. */
  public String url(String token) {
    String base = webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
    return base + "/#/pay/" + token;
  }

  /** A fresh link for one invoice: {@link #mint} and {@link #url} in one. */
  public String issue(UUID invoiceId) {
    return url(mint(invoiceId));
  }
}
