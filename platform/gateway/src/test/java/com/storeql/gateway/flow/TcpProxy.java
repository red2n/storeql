package com.storeql.gateway.flow;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A pipe between the gateway and its Redis that a test can cut and restore: {@link #stop} closes
 * the listening socket and every connection through it, which is what Redis going away looks like
 * from the gateway's side; {@link #start} listens again on the same port.
 */
final class TcpProxy {

  private final String targetHost;
  private final int targetPort;
  private final int port;
  private final Set<Socket> open = ConcurrentHashMap.newKeySet();
  private volatile ServerSocket server;

  TcpProxy(String targetHost, int targetPort) {
    this.targetHost = targetHost;
    this.targetPort = targetPort;
    try (ServerSocket probe = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
      this.port = probe.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    start();
  }

  int port() {
    return port;
  }

  boolean running() {
    ServerSocket s = server;
    return s != null && !s.isClosed();
  }

  synchronized void start() {
    if (running()) return;
    try {
      ServerSocket s = new ServerSocket();
      s.setReuseAddress(true);
      s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
      server = s;
      Thread.ofVirtual().name("tcp-proxy-accept").start(() -> accept(s));
    } catch (IOException e) {
      throw new IllegalStateException("cannot listen on " + port, e);
    }
  }

  synchronized void stop() {
    ServerSocket s = server;
    server = null;
    try {
      if (s != null) s.close();
    } catch (IOException ignored) {
      // closing is the point
    }
    for (Socket c : open) {
      try {
        c.close();
      } catch (IOException ignored) {
        // closing is the point
      }
    }
    open.clear();
  }

  private void accept(ServerSocket s) {
    while (!s.isClosed()) {
      try {
        Socket client = s.accept();
        Socket upstream = new Socket(targetHost, targetPort);
        open.add(client);
        open.add(upstream);
        Thread.ofVirtual().start(() -> pipe(client, upstream));
        Thread.ofVirtual().start(() -> pipe(upstream, client));
      } catch (IOException e) {
        if (s.isClosed()) return;
      }
    }
  }

  private void pipe(Socket from, Socket to) {
    try (InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream()) {
      byte[] buf = new byte[8192];
      for (int n = in.read(buf); n >= 0; n = in.read(buf)) {
        out.write(buf, 0, n);
        out.flush();
      }
    } catch (IOException ignored) {
      // either end closing ends the pipe
    } finally {
      try {
        from.close();
        to.close();
      } catch (IOException ignored) {
        // already closed
      }
      open.remove(from);
      open.remove(to);
    }
  }
}
