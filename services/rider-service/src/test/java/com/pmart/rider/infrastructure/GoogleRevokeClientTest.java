package com.pmart.rider.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GoogleRevokeClientTest {

  /** 2026-11-25: form token 으로 끊고, 실패는 예외 없이 false. */
  @Test
  void revokesWithFormTokenAndNeverThrows() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> body = new AtomicReference<>();
    AtomicInteger status = new AtomicInteger(200);
    server.createContext(
        "/revoke",
        ex -> {
          body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          ex.sendResponseHeaders(status.get(), -1);
          ex.close();
        });
    server.start();
    try {
      var c =
          new GoogleRevokeClient("http://127.0.0.1:" + server.getAddress().getPort() + "/revoke");
      assertThat(c.revoke("ya29.token")).isTrue();
      assertThat(body.get()).isEqualTo("token=ya29.token");
      status.set(400);
      assertThat(c.revoke("bad")).isFalse();
    } finally {
      server.stop(0);
    }
    assertThat(new GoogleRevokeClient("http://127.0.0.1:1/revoke").revoke("x")).isFalse();
  }
}
