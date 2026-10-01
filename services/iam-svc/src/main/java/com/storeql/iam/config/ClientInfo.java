package com.storeql.iam.config;

import jakarta.enterprise.context.RequestScoped;

/** The client of the current request, as {@link ClientInfoFilter} read it; labels only. */
@RequestScoped
public class ClientInfo {
  private String device;
  private String network;

  /**
   * @return the short device label, or null when no request has set one
   */
  public String device() {
    return device;
  }

  /**
   * @return the truncated network prefix, or null
   */
  public String network() {
    return network;
  }

  void set(String device, String network) {
    this.device = device;
    this.network = network;
  }
}
