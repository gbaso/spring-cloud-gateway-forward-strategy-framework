package io.github.gbaso.gateway.fsf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.springframework.cloud.gateway.filter.headers.ForwardedHeadersFilter.FORWARDED_HEADER;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A client reaches the gateway through a proxy that publishes the original request in a
 * RFC 7239 {@code Forwarded} header. The proxy-to-gateway {@code Host} header
 * ({@code localhost:<port>}) differs from the original host ({@code example.com}).
 * <p>
 * Downstream Spring applications resolve the original request from the first
 * {@code Forwarded} element (see {@code ForwardedHeaderUtils}), so that element must carry
 * the original host.
 */
class ForwardedHeaderTests {

  static final Logger log = LoggerFactory.getLogger(ForwardedHeaderTests.class);

  static final RecordingServer downstream = new RecordingServer();

  @DynamicPropertySource
  static void downstreamProperties(DynamicPropertyRegistry registry) {
    registry.add("downstream.uri", downstream::uri);
  }

  @AfterAll
  static void shutdownDownstream() {
    downstream.stop();
  }

  abstract static class Base {

    @LocalServerPort
    int port;

    @AfterEach
    void reset() {
      downstream.reset();
    }

    @Test
    void downstreamResolvesOriginalHost() throws IOException, InterruptedException {
      var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test"))
          .header(FORWARDED_HEADER, "proto=https;host=example.com;for=192.0.2.10")
          .build();
      try (var client = HttpClient.newHttpClient()) {
        client.send(request, BodyHandlers.discarding());
      }

      List<String> forwarded = downstream.header("Forwarded");
      log.info("{} downstream Forwarded: {}", getClass().getSimpleName(), forwarded);

      assertThat(forwarded)
          .isNotEmpty()
          .first()
          .extracting(header -> header.split(",")[0], STRING)
          .contains("proto=https", "host=example.com");
    }

  }

  /**
   * Fails: the incoming {@code Forwarded} header is consumed and removed by
   * {@code ForwardedHeaderTransformer}, and the gateway builds a new one with
   * {@code proto} from the rewritten request URI but {@code host} from the unchanged
   * {@code Host} header.
   */
  @Nested
  @SpringBootTest(
      webEnvironment = WebEnvironment.RANDOM_PORT,
      properties = "server.forward-headers-strategy=framework"
  )
  class FrameworkStrategy extends Base {

  }

  /**
   * Control: Reactor Netty does not remove the incoming {@code Forwarded} header, so the
   * gateway keeps it (trusted) ahead of its own entry.
   */
  @Nested
  @SpringBootTest(
      webEnvironment = WebEnvironment.RANDOM_PORT,
      properties = "server.forward-headers-strategy=native"
  )
  class NativeStrategy extends Base {

  }

}
