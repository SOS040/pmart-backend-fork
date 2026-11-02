package com.pmart.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.context.encrypt.EncryptorFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로컬 Git 저장소(file://)로 띄운 config-server 시험: 환경 폴더(lan/) 선택, 공통·앱별 파일 병합, {cipher} 서버 복호화, Basic 인증,
 * /encrypt·/decrypt 차단, configtree 로 받은 서버 비밀.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigServerIT {

  private static final String KEY = "it-encrypt-key-0123456789abcdef";
  private static final String SALT = "a1b2c3d4e5f60718";
  private static final String PASSWORD = "it-config-password";
  private static final Path ROOT;
  private static final String CIPHER;

  static {
    try {
      ROOT = Files.createTempDirectory("config-server-it");
      CIPHER = new EncryptorFactory(SALT).create(KEY).encrypt("db-secret-value");
      Path repo = Files.createDirectories(ROOT.resolve("repo/lan"));
      Files.writeString(
          repo.resolve("application.yml"),
          "PMART_KAFKA_BOOTSTRAP: kafka:9092\nPMART_DB_PASSWORD: '{cipher}" + CIPHER + "'\n");
      Files.writeString(
          repo.resolve("payment-service.yml"),
          "PMART_DB_URL: jdbc:postgresql://postgres-pc2:15432/payment_service\n");
      Files.createDirectories(ROOT.resolve("repo/other"));
      Files.writeString(ROOT.resolve("repo/other/payment-service.yml"), "PMART_DB_URL: wrong\n");
      try (Git git =
          Git.init().setDirectory(ROOT.resolve("repo").toFile()).setInitialBranch("main").call()) {
        git.add().addFilepattern(".").call();
        git.commit().setMessage("init").setAuthor("it", "it@example.invalid").setSign(false).call();
      }
      Path secrets = Files.createDirectories(ROOT.resolve("secrets"));
      write(secrets.resolve("encrypt.key"), KEY);
      write(secrets.resolve("encrypt.salt"), SALT);
      write(secrets.resolve("spring.security.user.password"), PASSWORD);
      // configtree import 는 환경 준비 단계에 처리된다 — @DynamicPropertySource 는 그보다 늦어서 시스템 속성으로 준다
      System.setProperty("PMART_CONFIG_SECRETS_DIR", secrets.toString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("PMART_CONFIG_GIT_URI", () -> ROOT.resolve("repo").toUri().toString());
    r.add("spring.cloud.config.server.git.basedir", () -> ROOT.resolve("clone").toString());
    // 시험 저장소는 file:// 이라 SSH 키가 없다(LAN 은 Deploy key + 호스트 키 고정)
    r.add("spring.cloud.config.server.git.ignore-local-ssh-settings", () -> "false");
  }

  @AfterAll
  static void cleanup() throws IOException {
    System.clearProperty("PMART_CONFIG_SECRETS_DIR");
    // Windows 에서 Git 객체 파일은 읽기 전용이라 먼저 쓰기 가능으로 바꾼다
    try (var paths = Files.walk(ROOT)) {
      paths.forEach(p -> p.toFile().setWritable(true));
    }
    FileSystemUtils.deleteRecursively(ROOT);
  }

  @LocalServerPort int port;
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void 인증한_앱은_환경_폴더의_공통과_앱별_설정을_복호화해_받는다() {
    JsonNode env = mapper.readTree(get("/payment-service/default", true).body());
    Map<String, String> merged = new HashMap<>();
    // propertySources 는 우선순위 높은 것(앱별)부터. 낮은 것부터 덮어써서 병합한다
    JsonNode sources = env.path("propertySources");
    for (int i = sources.size() - 1; i >= 0; i--) {
      sources
          .get(i)
          .path("source")
          .properties()
          .forEach(e -> merged.put(e.getKey(), e.getValue().asString()));
    }
    assertThat(merged)
        .containsEntry("PMART_KAFKA_BOOTSTRAP", "kafka:9092")
        .containsEntry("PMART_DB_PASSWORD", "db-secret-value")
        .containsEntry("PMART_DB_URL", "jdbc:postgresql://postgres-pc2:15432/payment_service");
    assertThat(env.toString()).doesNotContain("{cipher}").doesNotContain("wrong");
  }

  @Test
  void 인증이_없으면_401() {
    assertThat(get("/payment-service/default", false).status()).isEqualTo(401);
  }

  @Test
  void 틀린_비밀번호는_401() {
    var res =
        client()
            .get()
            .uri("/payment-service/default")
            .headers(h -> h.setBasicAuth("pmart", "wrong"))
            .exchange((req, resp) -> resp.getStatusCode().value());
    assertThat(res).isEqualTo(401);
  }

  @Test
  void 암호화_복호화_엔드포인트는_인증해도_막힌다() {
    for (String path : new String[] {"/encrypt", "/decrypt"}) {
      int status =
          client()
              .post()
              .uri(path)
              .headers(h -> h.setBasicAuth("pmart", PASSWORD))
              .body(CIPHER)
              .exchange((req, resp) -> resp.getStatusCode().value());
      assertThat(status).as(path).isEqualTo(403);
    }
  }

  @Test
  void 상태_확인은_인증_없이_UP() {
    var res = get("/actuator/health", false);
    assertThat(res.status()).isEqualTo(200);
    assertThat(res.body()).contains("\"UP\"");
  }

  @Test
  void 그_밖의_actuator_는_막힌다() {
    assertThat(get("/actuator/env", true).status()).isIn(403, 404);
  }

  // ---- 서비스 쪽(config client) 시험: 서비스 application.yml 과 같은 설정(optional:configserver: + 기본 꺼짐)에
  //      LAN compose 처럼 enabled·uri·fail-fast·인증을 덮어쓴다 ----

  @Test
  void 켜진_클라이언트는_기존_자리표시자를_서버_값으로_채운다() {
    try (ConfigurableApplicationContext ctx = client(PASSWORD, true)) {
      assertThat(ctx.getEnvironment().getProperty("x.db-password")).isEqualTo("db-secret-value");
      assertThat(ctx.getEnvironment().getProperty("x.db-url"))
          .isEqualTo("jdbc:postgresql://postgres-pc2:15432/payment_service");
    }
  }

  @Test
  void 기본_꺼짐이면_서버를_부르지_않고_자리표시자_기본값을_쓴다() {
    try (ConfigurableApplicationContext ctx = client(PASSWORD, false)) {
      assertThat(ctx.getEnvironment().getProperty("x.db-password")).isEqualTo("local-default");
    }
  }

  @Test
  void 켜진_클라이언트가_설정을_못_받으면_기동하지_않는다() {
    assertThatThrownBy(() -> client("wrong", true).close()).hasStackTraceContaining("401");
  }

  /** 서비스와 같은 import 설정의 최소 앱. 서버 application.yml 을 읽지 않게 config name 을 바꾼다. */
  private ConfigurableApplicationContext client(String password, boolean enabled) {
    return new SpringApplicationBuilder(ClientApp.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.config.name=client-it",
            "--spring.application.name=payment-service",
            "--spring.config.import=optional:configserver:",
            "--spring.cloud.config.enabled=" + enabled,
            "--spring.cloud.config.uri=http://localhost:" + port,
            "--spring.cloud.config.fail-fast=true",
            "--spring.cloud.config.username=pmart",
            "--spring.cloud.config.password=" + password,
            "--x.db-password=${PMART_DB_PASSWORD:local-default}",
            "--x.db-url=${PMART_DB_URL:local-default}");
  }

  // @Configuration 을 붙이면 @SpringBootTest 가 이 중첩 클래스를 시험 설정으로 잡아 서버 대신 띄운다
  static class ClientApp {}

  private record Res(int status, String body) {}

  private Res get(String path, boolean auth) {
    return client()
        .get()
        .uri(path)
        .headers(
            h -> {
              if (auth) {
                h.setBasicAuth("pmart", PASSWORD);
              }
            })
        .exchange(
            (req, resp) ->
                new Res(
                    resp.getStatusCode().value(),
                    new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8)));
  }

  private RestClient client() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port)
        .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> {})
        .build();
  }

  private static void write(Path p, String v) throws IOException {
    Files.writeString(p, v, StandardCharsets.UTF_8);
  }
}
