package com.storeql.test.fixture.service;

import com.storeql.test.fixture.client.Gateway;

public class GoodService {
  Gateway gateway;

  String pay() {
    return gateway.charge(1);
  }
}
