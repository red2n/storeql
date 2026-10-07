package com.storeql.iam.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** What is kept of a client: a short label and a truncated network, never more. */
class ClientLabelTest {

  @Test
  void aDeviceIsAShortLabel() {
    assertEquals(
        "Chrome on Windows",
        ClientLabel.device(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                + " Chrome/120.0 Safari/537.36"));
    assertEquals("StoreQL app on Android", ClientLabel.device("Dart/3.5 (dart:io); Android 14"));
    assertEquals(ClientLabel.UNKNOWN, ClientLabel.device(null));
    assertEquals(ClientLabel.UNKNOWN, ClientLabel.device(" "));
    assertEquals(ClientLabel.UNKNOWN, ClientLabel.device("curl-ish/1.0"));
  }

  @Test
  void aNetworkIsOnlyAPrefix() {
    assertEquals("203.0.113.0/24", ClientLabel.network("203.0.113.77"));
    assertEquals("203.0.113.0/24", ClientLabel.network("203.0.113.77, 10.0.0.1"));
    assertEquals("2001:db8:1::/48", ClientLabel.network("2001:db8:1:2:3:4:5:6"));
    assertNull(ClientLabel.network(null));
    assertNull(ClientLabel.network("not an address"));
  }
}
