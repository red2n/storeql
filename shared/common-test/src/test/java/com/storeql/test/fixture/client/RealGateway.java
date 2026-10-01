package com.storeql.test.fixture.client;

public class RealGateway implements Gateway {
  private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

  @Override
  public String charge(long minor) {
    return String.valueOf(http.hashCode() + minor);
  }
}
