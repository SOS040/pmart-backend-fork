package com.pmart.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KakaoUnlinkClientTest {

  @Test
  void unlinksByUserIdWithAdminKeyAndNeverThrows() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger status = new AtomicInteger(200);
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<String> auth = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();
    server.createContext(
        "/v1/user/unlink",
        ex -> {
          calls.incrementAndGet();
          auth.set(ex.getRequestHeaders().getFirst("Authorization"));
          body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] out = "{\"id\":123}".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(status.get(), out.length);
          ex.getResponseBody().write(out);
          ex.close();
        });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    try {
      // Admin 키가 없으면 부르지 않는다
      KakaoUnlinkClient none = new KakaoUnlinkClient("", base);
      assertThat(none.configured()).isFalse();
      assertThat(none.unlink("123")).isFalse();
      assertThat(calls.get()).isZero();

      KakaoUnlinkClient c = new KakaoUnlinkClient("admin-key", base + "/");
      assertThat(c.unlink("123")).isTrue();
      assertThat(auth.get()).isEqualTo("KakaoAK admin-key");
      assertThat(body.get()).contains("target_id_type=user_id").contains("target_id=123");

      status.set(401);
      assertThat(c.unlink("123")).isFalse();
    } finally {
      server.stop(0);
    }
    // 연결 안 됨도 false
    assertThat(new KakaoUnlinkClient("k", "http://127.0.0.1:1").unlink("1")).isFalse();
  }
}
