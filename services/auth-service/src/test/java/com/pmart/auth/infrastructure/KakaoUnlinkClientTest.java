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

  /** 2026-11-25: 사용자 정보 — 유효·확인된 이메일, 기본 사진 아닌 사진, 별명. 실패는 null. */
  @Test
  void profileReadsConsentedValuesAndNeverThrows() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicReference<String> query = new AtomicReference<>();
    AtomicReference<String> reply = new AtomicReference<>();
    server.createContext(
        "/v2/user/me",
        ex -> {
          query.set(ex.getRequestURI().getQuery());
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
          "{\"id\":123,\"kakao_account\":{\"email\":\"k@kakao.com\",\"is_email_valid\":true,"
              + "\"is_email_verified\":true,\"profile\":{\"nickname\":\"카카오\","
              + "\"profile_image_url\":\"https://k.kakaocdn.net/a.jpg\",\"is_default_image\":false}}}");
      var p = c.profile("123");
      assertThat(query.get()).contains("target_id_type=user_id").contains("target_id=123");
      assertThat(p.email()).isEqualTo("k@kakao.com");
      assertThat(p.emailVerified()).isTrue();
      assertThat(p.nickname()).isEqualTo("카카오");
      assertThat(p.imageUrl()).isEqualTo("https://k.kakaocdn.net/a.jpg");
      assertThat(p.name()).as("이름 동의 없음").isNull();

      // BR-111: 동의항목 "이름"이 있으면 실명, 제어 문자가 있으면 버림
      reply.set(
          "{\"id\":123,\"kakao_account\":{\"name\":\" 홍길동 \",\"profile\":{\"nickname\":\"길동이\"}}}");
      var named = c.profile("123");
      assertThat(named.name()).isEqualTo("홍길동");
      assertThat(named.nickname()).isEqualTo("길동이");
      reply.set("{\"id\":123,\"kakao_account\":{\"name\":\"홍\\n길동\"}}");
      assertThat(c.profile("123").name()).isNull();

      // 확인 안 된 이메일·기본 사진·별명 없음 → 빠진다
      reply.set(
          "{\"id\":123,\"kakao_account\":{\"email\":\"k@kakao.com\",\"is_email_valid\":true,"
              + "\"is_email_verified\":false,\"profile\":{\"profile_image_url\":\"https://k.kakaocdn.net/d.jpg\","
              + "\"is_default_image\":true}}}");
      var q = c.profile("123");
      assertThat(q.email()).isNull();
      assertThat(q.nickname()).isNull();
      assertThat(q.imageUrl()).isNull();
    } finally {
      server.stop(0);
    }
    assertThat(new KakaoUnlinkClient("", "http://127.0.0.1:1").profile("1")).isNull();
    assertThat(new KakaoUnlinkClient("k", "http://127.0.0.1:1").profile("1")).isNull();
  }
}
