package com.pmart.mailprovisioner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 사서함 API: 계약대로 만들기(멱등)·조회(receiving)·비활성·다시 활성·비밀번호·도메인 검사. 계정 파일은 임시 폴더, 메일 서버는 계정 파일에 있는 주소만
 * RCPT 250 을 주는 가짜 SMTP(실제 docker-mailserver 와 Dovecot 확인은 LAN 배치 때).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.main.banner-mode=off"})
class MailboxesIT {

  static final Path DIR = tempDir();
  static final Path FILE = DIR.resolve("postfix-accounts.cf");
  static final FakeSmtp SMTP = new FakeSmtp(FILE);

  static Path tempDir() {
    try {
      return Files.createTempDirectory("mail-provisioner-it");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("pmart.mail-provisioner.accounts-file", FILE::toString);
    r.add("pmart.mail-provisioner.smtp-host", () -> "localhost");
    r.add("pmart.mail-provisioner.smtp-port", SMTP::port);
  }

  @AfterAll
  static void stop() {
    SMTP.close();
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  RestClient http;

  @BeforeEach
  void setUp() throws IOException {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/internal")
            .defaultHeader("X-Source-System", "hr-service")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    if (!Files.exists(FILE)) {
      Files.write(FILE, List.of("no-reply@pmart.com|{SHA512-CRYPT}$6$abc$x"));
    }
  }

  record Res(int status, JsonNode body) {}

  Res send(String method, String path, Object body) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .header("Idempotency-Key", UUID.randomUUID().toString());
    if (body != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    return spec.exchange(
        (q, r) -> {
          byte[] b = r.bodyTo(byte[].class);
          return new Res(
              r.getStatusCode().value(),
              b == null || b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b));
        });
  }

  String line(String address) throws IOException {
    return Files.readAllLines(FILE).stream()
        .filter(l -> l.startsWith(address + "|"))
        .findFirst()
        .orElse(null);
  }

  @Test
  void createIsIdempotentAndReceiving() throws Exception {
    String a = "it" + UUID.randomUUID().toString().substring(0, 6) + "@pmart.com";
    Res r = send("POST", "/mailboxes", Map.of("address", a));
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.body().path("state").asText()).isEqualTo("ACTIVE");
    assertThat(r.body().path("receiving").asBoolean()).isTrue();
    String first = line(a);
    assertThat(first).startsWith(a + "|{SHA512-CRYPT}$6$");
    assertThat(send("POST", "/mailboxes", Map.of("address", a)).status()).isEqualTo(200);
    assertThat(line(a)).as("다시 만들어도 해시가 바뀌지 않는다").isEqualTo(first);
    assertThat(line("no-reply@pmart.com")).as("다른 줄 보존").isNotNull();
    assertThat(send("POST", "/mailboxes", Map.of("address", a.toUpperCase())).status())
        .as("계약: 소문자 주소만")
        .isEqualTo(400);
    assertThat(send("GET", "/mailboxes/" + a, null).body().path("receiving").asBoolean()).isTrue();
  }

  @Test
  void disableEnablePasswordAndDomain() throws Exception {
    String a = "it" + UUID.randomUUID().toString().substring(0, 6) + "@pmart.com";
    send("POST", "/mailboxes", Map.of("address", a));
    String original = line(a);

    Res d = send("POST", "/mailboxes/" + a + "/disable", null);
    assertThat(d.body().path("state").asText()).isEqualTo("DISABLED");
    assertThat(d.body().path("receiving").asBoolean()).as("비활성이어도 메일은 받는다").isTrue();
    assertThat(line(a)).contains("|{SHA512-CRYPT}!$6$");
    assertThat(
            send("PUT", "/mailboxes/" + a + "/password", Map.of("password", "new-password-123"))
                .status())
        .as("비활성 사서함 비밀번호")
        .isEqualTo(409);

    Res e = send("POST", "/mailboxes/" + a + "/enable", null);
    assertThat(e.body().path("state").asText()).isEqualTo("ACTIVE");
    assertThat(line(a)).isEqualTo(original);

    assertThat(
            send("PUT", "/mailboxes/" + a + "/password", Map.of("password", "new-password-123"))
                .status())
        .isEqualTo(204);
    assertThat(line(a)).isNotEqualTo(original).doesNotContain("new-password-123");

    assertThat(send("POST", "/mailboxes", Map.of("address", "x@gmail.com")).status())
        .isEqualTo(400);
    assertThat(send("GET", "/mailboxes/nobody@pmart.com", null).status()).isEqualTo(404);
    assertThat(send("POST", "/mailboxes/nobody@pmart.com/disable", null).status()).isEqualTo(404);
  }

  /** Roundcube password 플러그인(httpapi)처럼: 헤더 없이 form user·curpass·newpass. */
  int change(String user, String curpass, String newpass) {
    var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
    form.add("user", user);
    form.add("curpass", curpass);
    form.add("newpass", newpass);
    return RestClient.builder()
        .baseUrl("http://localhost:" + port + "/api/v1/internal")
        .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
        .build()
        .post()
        .uri("/mailbox-password-changes")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(form)
        .exchange((q, r) -> r.getStatusCode().value());
  }

  @Test
  void ownPasswordChangeNeedsCurrentPassword() throws Exception {
    String local = "it" + UUID.randomUUID().toString().substring(0, 6);
    String a = local + "@pmart.com";
    send("POST", "/mailboxes", Map.of("address", a));
    send("PUT", "/mailboxes/" + a + "/password", Map.of("password", "first-password-1"));
    String before = line(a);

    assertThat(change(a, "wrong-password-9", "second-password-2")).as("현재 비밀번호 틀림").isEqualTo(403);
    assertThat(change(a, "first-password-1", "short")).as("새 비밀번호 12자 미만").isEqualTo(400);
    assertThat(change("nobody@pmart.com", "first-password-1", "second-password-2"))
        .as("없는 사서함도 같은 403")
        .isEqualTo(403);
    assertThat(change("x@gmail.com", "first-password-1", "second-password-2"))
        .as("다른 도메인도 같은 403")
        .isEqualTo(403);
    assertThat(line(a)).as("실패하면 그대로").isEqualTo(before);

    assertThat(change(a, "first-password-1", "second-password-2"))
        .as("헤더 없이(Roundcube) 바뀐다")
        .isEqualTo(204);
    String after = line(a);
    assertThat(after)
        .isNotEqualTo(before)
        .startsWith(a + "|{SHA512-CRYPT}$6$")
        .doesNotContain("second-password-2");
    assertThat(change(a, "first-password-1", "third-password-3"))
        .as("옛 비밀번호는 이제 틀림")
        .isEqualTo(403);
    assertThat(change(local, "second-password-2", "third-password-3"))
        .as("@ 없는 로그인 이름은 설정 도메인")
        .isEqualTo(204);

    send("POST", "/mailboxes/" + a + "/disable", null);
    String disabled = line(a);
    assertThat(change(a, "third-password-3", "fourth-password-4")).as("비활성은 403").isEqualTo(403);
    assertThat(line(a)).isEqualTo(disabled);
  }

  /** 계정 파일에 있는 주소만 받는 가짜 SMTP. */
  static final class FakeSmtp implements AutoCloseable {
    final ServerSocket server;
    final Thread thread;

    FakeSmtp(Path accounts) {
      try {
        server = new ServerSocket(0);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      thread =
          new Thread(
              () -> {
                while (!server.isClosed()) {
                  try (Socket s = server.accept()) {
                    serve(s, accounts);
                  } catch (IOException ignored) {
                    // 닫힘
                  }
                }
              },
              "fake-smtp");
      thread.setDaemon(true);
      thread.start();
    }

    int port() {
      return server.getLocalPort();
    }

    static void serve(Socket s, Path accounts) throws IOException {
      BufferedReader in =
          new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
      OutputStream out = s.getOutputStream();
      out.write("220 fake ESMTP\r\n".getBytes(StandardCharsets.US_ASCII));
      String line;
      while ((line = in.readLine()) != null) {
        String reply;
        if (line.startsWith("EHLO")) {
          reply = "250-fake\r\n250 OK";
        } else if (line.startsWith("RCPT TO:<")) {
          String addr = line.substring(9, line.indexOf('>')).toLowerCase();
          boolean known =
              Files.readAllLines(accounts).stream().anyMatch(l -> l.startsWith(addr + "|"));
          reply = known ? "250 OK" : "550 5.1.1 User unknown";
        } else if (line.startsWith("QUIT")) {
          out.write("221 bye\r\n".getBytes(StandardCharsets.US_ASCII));
          return;
        } else {
          reply = "250 OK";
        }
        out.write((reply + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
      }
    }

    @Override
    public void close() {
      try {
        server.close();
      } catch (IOException ignored) {
        // 무시
      }
    }
  }
}
