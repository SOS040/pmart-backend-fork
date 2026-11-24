package com.pmart.riderauth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KakaoUnlinkClientTest {

  /** 2026-11-25: 카카오 사용자 정보 이메일 — 유효·확인된 것만, 실패는 null. */
  @Test
  void emailOnlyValidAndVerifiedAndNeverThrows() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> query = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    AtomicReference<String> reply = new AtomicReference<>();
    server.createContext(
        "/v2/user/me",
        ex -> {
          query.set(ex.getRequestURI().getQuery());
          auth.set(ex.getRequestHeaders().getFirst("Authorization"));
          byte[] out = reply.get().getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, out.length);
          ex.getResponseBody().write(out);
          ex.close();
        });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    try {
      KakaoUnlinkClient c = new KakaoUnlinkClient("admin-key", base);
      reply.set(
          "{\"id\":7,\"kakao_account\":{\"email\":\"r@kakao.com\",\"is_email_valid\":true,"
              + "\"is_email_verified\":true}}");
      assertThat(c.email("7")).isEqualTo("r@kakao.com");
      assertThat(query.get()).contains("target_id_type=user_id").contains("target_id=7");
      assertThat(auth.get()).isEqualTo("KakaoAK admin-key");

      reply.set(
          "{\"id\":7,\"kakao_account\":{\"email\":\"r@kakao.com\",\"is_email_valid\":true,"
              + "\"is_email_verified\":false}}");
      assertThat(c.email("7")).isNull();
    } finally {
      server.stop(0);
    }
    assertThat(new KakaoUnlinkClient("", "http://127.0.0.1:1").email("1")).isNull();
    assertThat(new KakaoUnlinkClient("k", "http://127.0.0.1:1").email("1")).isNull();
  }
}
