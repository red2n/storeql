package com.storeql.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * A throwaway certificate for a stub that speaks TLS: made at test time by the JDK's own {@code
 * keytool}, for {@code localhost} / {@code 127.0.0.1}, held in memory, its files deleted at once.
 * Nothing is committed and nothing is reused between runs. The client side trusts exactly this one
 * certificate.
 */
final class StubTls {

  private static final SecureRandom RANDOM = new SecureRandom();

  final SSLContext server;
  final SSLContext client;

  private StubTls(SSLContext server, SSLContext client) {
    this.server = server;
    this.client = client;
  }

  static StubTls create() {
    Path dir = null;
    try {
      dir = Files.createTempDirectory("stub-tls");
      Path file = dir.resolve("stub.p12");
      byte[] raw = new byte[18];
      RANDOM.nextBytes(raw);
      char[] password = Base64.getUrlEncoder().withoutPadding().encodeToString(raw).toCharArray();
      String keytool =
          Path.of(System.getProperty("java.home"), "bin", "keytool").toAbsolutePath().toString();
      Process p =
          new ProcessBuilder(
                  keytool,
                  "-genkeypair",
                  "-alias",
                  "stub",
                  "-keyalg",
                  "RSA",
                  "-keysize",
                  "2048",
                  "-dname",
                  "CN=localhost",
                  "-ext",
                  "san=dns:localhost,ip:127.0.0.1",
                  "-validity",
                  "1",
                  "-storetype",
                  "PKCS12",
                  "-keystore",
                  file.toString(),
                  "-storepass",
                  new String(password),
                  "-keypass",
                  new String(password))
              .redirectErrorStream(true)
              .start();
      String out;
      try (InputStream in = p.getInputStream()) {
        out = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      }
      if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
        throw new IllegalStateException("keytool could not make a test certificate: " + out);
      }
      KeyStore ks = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(file)) {
        ks.load(in, password);
      }
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(ks, password);
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(ks);
      SSLContext server = SSLContext.getInstance("TLS");
      server.init(kmf.getKeyManagers(), null, null);
      SSLContext client = SSLContext.getInstance("TLS");
      client.init(null, tmf.getTrustManagers(), null);
      return new StubTls(server, client);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    } finally {
      if (dir != null) {
        try (var files = Files.walk(dir)) {
          for (Path f :
              (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator) {
            Files.deleteIfExists(f);
          }
        } catch (IOException ignored) {
          // a temp file left behind holds nothing a test needs
        }
      }
    }
  }
}
