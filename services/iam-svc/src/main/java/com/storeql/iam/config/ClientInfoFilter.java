package com.storeql.iam.config;

import com.storeql.iam.domain.ClientLabel;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Reads the client's User-Agent and forwarded address into {@link ClientInfo}, reduced to a device
 * label and a network prefix, so a session can be shown to its owner.
 */
@Provider
public class ClientInfoFilter implements ContainerRequestFilter {

  @Inject ClientInfo info;

  @Override
  public void filter(ContainerRequestContext req) {
    info.set(
        ClientLabel.device(req.getHeaderString("User-Agent")),
        ClientLabel.network(req.getHeaderString("X-Forwarded-For")));
  }
}
