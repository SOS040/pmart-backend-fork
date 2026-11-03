package com.pmart.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 실행 중 설정 갱신(ADR-0033): GitHub push 웹훅 → {@code POST /monitor}(서명 검증) → Bus(Kafka {@code
 * springCloudBus}) → 해당 앱이 config-server 에서 다시 받아 간다. LAN 처럼 Bus 를 켜고 실제 Kafka 를 쓴다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.cloud.bus.enabled=true",
      // 서비스에는 common-messaging 이 앱 공통 Kafka 값을 넣는다 — Bus 바인더가 그 값에 끌려가지 않는지 같이 본다
      MonitorBusIT.STRING_SERIALIZER,
      MonitorBusIT.STRING_DESERIALIZER,
      "spring.kafka.consumer.auto-offset-reset=earliest"
    })
@Testcontainers
class MonitorBusIT {

  // common-test 의 KafkaContainerSupport 와 같은 이미지. common-test 는 JDBC 를 끌고 와 DB 없는 config-server 가
  // 뜨지 못한다
  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.1");

  static final String STRING_SERIALIZER =
      "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer";
  static final String STRING_DESERIALIZER =
      "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer";
  private static final String WEBHOOK_SECRET = "it-webhook-secret";
  private static final String PASSWORD = "it-config-password";
  private static final Path ROOT;

  static {
    try {
      ROOT = Files.createTempDirectory("config-monitor-it");
      Path repo = Files.createDirectories(ROOT.resolve("repo"));
      Files.writeString(repo.resolve("application.yml"), "spring.jpa.open-in-view: false\n");
      Files.writeString(repo.resolve("payment-service-lan.yml"), "pmart.it.greeting: before\n");
      try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
        git.add().addFilepattern(".").call();
        commit(git, "init");
      }
      Path secrets = Files.createDirectories(ROOT.resolve("secrets"));
      Files.writeString(secrets.resolve("spring.security.user.password"), PASSWORD);
      Files.writeString(
          secrets.resolve("spring.cloud.config.server.monitor.github.webhook-secret"),
          WEBHOOK_SECRET);
      System.setProperty("PMART_CONFIG_SECRETS_DIR", secrets.toString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("PMART_CONFIG_GIT_URI", () -> ROOT.resolve("repo").toUri().toString());
    r.add("spring.cloud.config.server.git.basedir", () -> ROOT.resolve("clone").toString());
    r.add("spring.cloud.config.server.git.ignore-local-ssh-settings", () -> "false");
    r.add("spring.kafka.bootstrap-servers", () -> KAFKA.getBootstrapServers());
  }

  @AfterAll
  static void cleanup() throws IOException {
    System.clearProperty("PMART_CONFIG_SECRETS_DIR");
    try (var paths = Files.walk(ROOT)) {
      paths.forEach(p -> p.toFile().setWritable(true));
    }
    FileSystemUtils.deleteRecursively(ROOT);
  }

  @LocalServerPort int port;

  @Test
  void 서명이_없거나_틀리거나_push_가_아니면_403() {
    String body = push("payment-service-lan.yml");
    assertThat(monitor(body, null, "push").status()).isEqualTo(403);
    assertThat(monitor(body, "sha256=" + "0".repeat(64), "push").status()).isEqualTo(403);
    assertThat(monitor(body, sign(body, "other-secret"), "push").status()).isEqualTo(403);
    // ping(웹훅 등록 직후) 등 push 가 아닌 이벤트는 검증기 대상이 아니라 403
    assertThat(monitor(body, sign(body, WEBHOOK_SECRET), "ping").status()).isEqualTo(403);
  }

  @Test
  void 서명이_맞으면_바뀐_파일의_앱_이름을_돌려준다() {
    String body = push("payment-service-lan.yml", "application-lan.yml");
    Res res = monitor(body, sign(body, WEBHOOK_SECRET), "push");

    assertThat(res.status()).isEqualTo(200);
    // payment-service-lan → payment-service-lan·payment-service·payment, application-lan → *(모든 앱)
    assertThat(res.body()).contains("\"payment-service\"").contains("\"*\"");
  }

  @Test
  void 웹훅을_받으면_실행_중인_앱이_새_설정을_다시_받는다() throws Exception {
    try (ConfigurableApplicationContext app = client()) {
      assertThat(app.getEnvironment().getProperty("pmart.it.greeting")).isEqualTo("before");

      // 저장소에 push(한글·꺾쇠가 든 커밋 메시지 — 서명은 받은 바이트 그대로 계산한다)
      try (Git git = Git.open(ROOT.resolve("repo").toFile())) {
        Files.writeString(
            ROOT.resolve("repo/payment-service-lan.yml"), "pmart.it.greeting: after\n");
        git.add().addFilepattern(".").call();
        commit(git, "인사말 변경 <it@example.invalid>");
      }
      String body = push("payment-service-lan.yml");
      assertThat(monitor(body, sign(body, WEBHOOK_SECRET), "push").status()).isEqualTo(200);

      await()
          .atMost(Duration.ofSeconds(60))
          .untilAsserted(
              () ->
                  assertThat(app.getEnvironment().getProperty("pmart.it.greeting"))
                      .isEqualTo("after"));
    }
  }

  /** LAN 의 서비스와 같은 클라이언트 설정(config client + Bus, lan 프로필). 웹 서버 없이 띄운다. */
  private ConfigurableApplicationContext client() {
    return new SpringApplicationBuilder(ClientApp.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.config.name=client-it",
            "--spring.application.name=payment-service",
            "--spring.profiles.active=lan",
            "--spring.config.import=optional:configserver:",
            "--spring.cloud.config.enabled=true",
            "--spring.cloud.config.uri=http://localhost:" + port,
            "--spring.cloud.config.fail-fast=true",
            "--spring.cloud.config.username=pmart",
            "--spring.cloud.config.password=" + PASSWORD,
            "--spring.cloud.bus.enabled=true",
            "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
            "--eureka.client.enabled=false",
            "--" + STRING_SERIALIZER,
            "--" + STRING_DESERIALIZER,
            "--spring.kafka.consumer.auto-offset-reset=earliest");
  }

  // @Configuration 을 붙이면 @SpringBootTest 가 이 중첩 클래스를 시험 설정으로 잡는다
  @EnableAutoConfiguration
  static class ClientApp {}

  private static String push(String... files) {
    StringBuilder list = new StringBuilder();
    for (String f : files) {
      list.append(list.isEmpty() ? "" : ",").append('"').append(f).append('"');
    }
    // GitHub push 페이로드에서 /monitor 가 읽는 부분(commits[].added·modified·removed)
    return "{\"ref\":\"refs/heads/main\",\"commits\":[{\"message\":\"인사말 <변경> & 확인\","
        + "\"added\":[],\"removed\":[],\"modified\":["
        + list
        + "]}]}";
  }

  private static String sign(String body, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return "sha256="
          + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private record Res(int status, String body) {}

  private Res monitor(String body, String signature, String event) {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> {})
        .build()
        .post()
        .uri("/monitor")
        .contentType(MediaType.APPLICATION_JSON)
        .headers(
            h -> {
              h.set("X-Github-Event", event);
              if (signature != null) {
                h.set("X-Hub-Signature-256", signature);
              }
            })
        .body(body.getBytes(StandardCharsets.UTF_8))
        .exchange(
            (req, resp) ->
                new Res(
                    resp.getStatusCode().value(),
                    new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8)));
  }

  private static void commit(Git git, String message) throws Exception {
    git.commit().setMessage(message).setAuthor("it", "it@example.invalid").setSign(false).call();
  }
}
