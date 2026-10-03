package com.storeql.gateway.filters;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.container.ContainerRequestContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The login body is read only as far as the cap, never buffered whole (group-2). */
class BruteForceFilterBodyTest {

  @Test
  void aSmallLoginBodyKeysTheAccountAndStaysReadableForTheProxy() throws IOException {
    byte[] body = "{\"email\":\"Bob@Example.com\"}".getBytes(StandardCharsets.UTF_8);
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);
    when(ctx.getEntityStream()).thenReturn(new ByteArrayInputStream(body));

    String key = new BruteForceFilter().extractUserKey(ctx);

    assertEquals("user:bob@example.com", key);
    ArgumentCaptor<InputStream> restored = ArgumentCaptor.forClass(InputStream.class);
    verify(ctx).setEntityStream(restored.capture());
    assertArrayEquals(body, restored.getValue().readAllBytes());
  }

  @Test
  void anOversizedBodyIsNotReadWholeAndStillReachesTheProxyIntact() throws IOException {
    byte[] body = new byte[200_000];
    Arrays.fill(body, (byte) 'x');
    CountingStream counted = new CountingStream(body);
    ContainerRequestContext ctx = mock(ContainerRequestContext.class);
    when(ctx.getEntityStream()).thenReturn(counted);

    String key = new BruteForceFilter().extractUserKey(ctx);

    assertNull(key);
    assertEquals(8 * 1024 + 1, counted.read, "read one byte past the cap and no further");
    ArgumentCaptor<InputStream> restored = ArgumentCaptor.forClass(InputStream.class);
    verify(ctx).setEntityStream(restored.capture());
    assertArrayEquals(body, restored.getValue().readAllBytes());
  }

  private static final class CountingStream extends ByteArrayInputStream {
    int read;

    CountingStream(byte[] b) {
      super(b);
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) {
      int n = super.read(b, off, len);
      if (n > 0) read += n;
      return n;
    }
  }
}
