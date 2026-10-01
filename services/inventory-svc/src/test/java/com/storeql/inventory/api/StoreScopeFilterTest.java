package com.storeql.inventory.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class StoreScopeFilterTest {

  @Test
  void aBodyOverTheLimitIsReadOnlyToTheLimitAndStillReachesTheResourceWhole() throws Exception {
    byte[] body = new byte[StoreScopeFilter.MAX_INSPECTED_BYTES + 5000];
    Arrays.fill(body, (byte) 'x');
    InputStream in = new ByteArrayInputStream(body);
    byte[] head = in.readNBytes(StoreScopeFilter.MAX_INSPECTED_BYTES + 1);
    assertThat(head.length, is(StoreScopeFilter.MAX_INSPECTED_BYTES + 1));
    // the rest of the upload had not been pulled into memory
    assertThat(in.available(), is(4999));
    assertThat(Arrays.equals(StoreScopeFilter.replay(head, in).readAllBytes(), body), is(true));
  }

  @Test
  void aSmallBodyIsReplayedWhole() throws Exception {
    byte[] body = "{\"storeId\":\"x\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    InputStream in = new ByteArrayInputStream(body);
    byte[] head = in.readNBytes(StoreScopeFilter.MAX_INSPECTED_BYTES + 1);
    assertThat(Arrays.equals(StoreScopeFilter.replay(head, in).readAllBytes(), body), is(true));
  }
}
