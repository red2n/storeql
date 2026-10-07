package com.storeql.einvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** What is built once and shared: the Factur-X font and the hardened XML factory. */
class CachedResourcesTest {

  @Test
  void theFontIsReadFromTheJarOnce() {
    byte[] first = FacturX.fontBytes();
    assertTrue(first.length > 100_000, "the real DejaVu font");
    assertSame(first, FacturX.fontBytes());
  }

  @Test
  void theSharedXmlFactoryParsesConcurrentlyAndStillRefusesADtd() throws Exception {
    byte[] ok = "<a xmlns=\"urn:x\"><b>1</b></a>".getBytes(StandardCharsets.UTF_8);
    byte[] dtd = "<!DOCTYPE a [<!ENTITY e \"x\">]><a>&e;</a>".getBytes(StandardCharsets.UTF_8);
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 400; i++) {
        results.add(
            pool.submit(
                () -> {
                  SafeXml.parse(ok);
                  try {
                    SafeXml.parse(dtd);
                    return false;
                  } catch (EInvoiceFormatException e) {
                    return "DTD_REFUSED".equals(e.code());
                  }
                }));
      }
      for (Future<Boolean> f : results) assertEquals(true, f.get());
    }
  }
}
