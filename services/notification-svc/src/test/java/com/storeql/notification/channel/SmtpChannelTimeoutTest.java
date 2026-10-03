package com.storeql.notification.channel;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** An SMTP server that accepts and never speaks must not hold a sender for ever. */
class SmtpChannelTimeoutTest {

  @Test
  void aSilentServerIsGivenUpOnAfterTheReadTimeout() throws Exception {
    List<Socket> held = new ArrayList<>();
    try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
      Thread accept =
          new Thread(
              () -> {
                try {
                  while (!server.isClosed()) {
                    held.add(server.accept());
                  }
                } catch (java.io.IOException ignored) {
                  // closed at the end of the test
                }
              });
      accept.setDaemon(true);
      accept.start();

      SmtpChannel smtp =
          new SmtpChannel(
              "127.0.0.1",
              server.getLocalPort(),
              null,
              null,
              "from@x.example",
              false,
              new SmtpChannel.Timeouts(500, 500, 500, 1));
      long start = System.nanoTime();
      assertThrows(
          IllegalStateException.class, () -> smtp.send(null, "to@x.example", "subject", "body"));
      long ms = (System.nanoTime() - start) / 1_000_000;
      assertTrue(ms < 8_000, "the send gave up in " + ms + " ms");
    } finally {
      for (Socket s : held) {
        s.close();
      }
    }
  }

  @Test
  void aZeroTimeoutIsRefusedBecauseItMeansWaitForEver() {
    assertThrows(IllegalArgumentException.class, () -> new SmtpChannel.Timeouts(0, 1, 1, 1));
  }
}
