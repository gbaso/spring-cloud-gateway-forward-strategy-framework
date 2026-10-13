package io.github.gbaso.gateway.fsf;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Downstream HTTP server that records the headers of the last request it received.
 */
class RecordingServer {

  private final HttpServer server;

  private final AtomicReference<Headers> lastHeaders = new AtomicReference<>();

  RecordingServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext("/", exchange -> {
      lastHeaders.set(exchange.getRequestHeaders());
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
    });
    server.start();
  }

  String uri() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  @SuppressWarnings("SameParameterValue")
  List<String> header(String name) {
    Map<String, List<String>> headers = lastHeaders.get();
    return headers == null ? List.of() : headers.getOrDefault(name, List.of());
  }

  void reset() {
    lastHeaders.set(null);
  }

  void stop() {
    server.stop(0);
  }

}
