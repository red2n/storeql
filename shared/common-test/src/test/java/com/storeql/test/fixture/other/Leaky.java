package com.storeql.test.fixture.other;

public class Leaky {
  Object c = java.net.http.HttpClient.newHttpClient();
}
