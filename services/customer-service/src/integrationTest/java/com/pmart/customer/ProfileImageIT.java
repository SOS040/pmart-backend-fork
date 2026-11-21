package com.pmart.customer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 로그인 제공자 사진의 우리 사본(ADR-0024): CustomerAuthenticated(pictureUrl) → 커밋 뒤 받아 S3 에 저장 → /me 는 우리 경로 →
 * 본인만 읽기. 같은 원본은 다시 받지 않고, 바뀌면 새 사본·옛 사본 삭제, 허용 밖 주소는 요청하지 않는다. 실제 PostgreSQL·Kafka·SeaweedFS,
 * Google 사진 서버는 WireMock.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      // 시험에서만: WireMock(http://localhost)을 Google 사진 서버로 본다
      "pmart.customer.profile-image.allowed-host-suffixes=localhost",
      "pmart.customer.profile-image.allow-http=true"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(@ConfigureWireMock(name = "google"))
class ProfileImageIT extends PostgresKafkaContainerSupport {

  static final String S3_KEY = "it-access-key";
  static final String S3_SECRET = "it-secret-key-0123456789";
  static final String BUCKET = "pmart-customer-it";

  @SuppressWarnings("resource")
  static final GenericContainer<?> S3 =
      new GenericContainer<>("chrislusf/seaweedfs:4.48")
          .withCreateContainerCmdModifier(
              c ->
                  c.withEntrypoint(
                      "sh",
                      "-c",
                      "printf '{\"identities\":[{\"name\":\"app\",\"credentials\":[{\"accessKey\":\""
                          + S3_KEY
                          + "\",\"secretKey\":\""
                          + S3_SECRET
                          + "\"}],\"actions\":[\"Admin\",\"Read\",\"List\",\"Tagging\",\"Write\"]}]}'"
                          + " > /tmp/s3.json && exec /entrypoint.sh mini -dir=/data"
                          + " -s3.config=/tmp/s3.json -admin.ui=false -s3.port.iceberg=0"
                          + " -s3.port.lance=0"))
          .withExposedPorts(8333)
          .waitingFor(
              Wait.forLogMessage(".*S3\\s+ready.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

  static {
    S3.start();
  }

  static String s3Endpoint() {
    return "http://" + S3.getHost() + ":" + S3.getMappedPort(8333);
  }

  @DynamicPropertySource
  static void s3(DynamicPropertyRegistry r) {
    r.add("pmart.storage.s3.endpoint", ProfileImageIT::s3Endpoint);
    r.add("pmart.storage.s3.bucket", () -> BUCKET);
    r.add("pmart.storage.s3.access-key", () -> S3_KEY);
    r.add("pmart.storage.s3.secret-key", () -> S3_SECRET);
    r.add("pmart.storage.s3.create-bucket", () -> "true");
  }

  static final byte[] PNG_1 = png(1);
  static final byte[] PNG_2 = png(2);

  /** PNG 머리 + 채움(형식 판정은 머리로 한다). */
  static byte[] png(int fill) {
    byte[] b = new byte[64];
    byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    System.arraycopy(sig, 0, b, 0, sig.length);
    Arrays.fill(b, sig.length, b.length, (byte) fill);
    return b;
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("google")
  WireMockServer google;

  RestClient http;
  S3Client s3;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    s3 =
        S3Client.builder()
            .endpointOverride(java.net.URI.create(s3Endpoint()))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(S3_KEY, S3_SECRET)))
            .build();
    google.resetAll();
    google.stubFor(
        get(urlEqualTo("/a/photo1=s256-c"))
            .willReturn(aResponse().withHeader("Content-Type", "image/png").withBody(PNG_1)));
    google.stubFor(
        get(urlEqualTo("/a/photo2=s256-c"))
            .willReturn(aResponse().withHeader("Content-Type", "image/png").withBody(PNG_2)));
    google.stubFor(
        get(urlEqualTo("/a/html=s256-c"))
            .willReturn(aResponse().withHeader("Content-Type", "image/png").withBody("<html>")));
  }

  void login(String accountId, String at, String pictureUrl) throws Exception {
    String json =
        EventEnvelopeFixtures.envelope("CustomerAuthenticated")
            .from("auth-service")
            .aggregate("Account", accountId, 1)
            .payload(
                Map.of(
                    "accountId",
                    accountId,
                    "provider",
                    "GOOGLE",
                    "authenticatedAt",
                    at,
                    "name",
                    "홍길동",
                    "pictureUrl",
                    pictureUrl))
            .buildJson();
    kafka.send("platform.auth.v1", accountId, json).join();
    // 원본이 처리(복사 또는 건너뜀)될 때까지
    await(
        () ->
            pictureUrl.equals(
                jdbc.queryForObject(
                    "SELECT profile_image_copied_from FROM customer WHERE account_id = ?",
                    String.class,
                    accountId)),
        "프로필 사진 처리 " + pictureUrl);
  }

  static void await(Supplier<Boolean> cond, String what) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)) {
      try {
        if (Boolean.TRUE.equals(cond.get())) {
          return;
        }
      } catch (org.springframework.dao.EmptyResultDataAccessException ignored) {
        // 행이 아직 없음
      }
      Thread.sleep(200);
    }
    throw new AssertionError("시간 초과: " + what);
  }

  JsonNode me(String token) {
    String body =
        http.get().uri("/me").headers(h -> h.setBearerAuth(token)).retrieve().body(String.class);
    return mapper.readTree(body);
  }

  ResponseEntity<byte[]> image(String token) {
    return http.get()
        .uri("/me/profile-image")
        .headers(h -> h.setBearerAuth(token))
        .retrieve()
        .toEntity(byte[].class);
  }

  java.util.List<String> keys(String accountId) {
    return s3
        .listObjectsV2(b -> b.bucket(BUCKET).prefix("profile-images/" + accountId + "/"))
        .contents()
        .stream()
        .map(S3Object::key)
        .toList();
  }

  @Test
  void googlePhotoIsCopiedServedToOwnerAndReplaced() throws Exception {
    String cu = "CU-" + UUID.randomUUID();
    String token = TestJwt.customer(cu);

    // 첫 로그인: Google 크기(=s96-c)를 우리 크기로 바꿔 한 번 받고 S3 에 둔다
    login(cu, "2026-10-31T00:00:00Z", google.baseUrl() + "/a/photo1=s96-c");
    google.verify(1, getRequestedFor(urlEqualTo("/a/photo1=s256-c")));
    String url1 = me(token).path("profileImageUrl").asString();
    assertThat(url1).startsWith("/api/v1/me/profile-image?v=");
    ResponseEntity<byte[]> img = image(token);
    assertThat(img.getStatusCode().value()).isEqualTo(200);
    assertThat(img.getHeaders().getContentType()).hasToString("image/png");
    assertThat(img.getHeaders().getCacheControl()).contains("private");
    assertThat(img.getBody()).isEqualTo(PNG_1);
    assertThat(keys(cu)).hasSize(1);

    // 같은 원본으로 다시 로그인: 다시 받지 않는다
    String json =
        EventEnvelopeFixtures.envelope("CustomerAuthenticated")
            .from("auth-service")
            .aggregate("Account", cu, 1)
            .payload(
                Map.of(
                    "accountId",
                    cu,
                    "provider",
                    "GOOGLE",
                    "authenticatedAt",
                    "2026-10-31T01:00:00Z",
                    "pictureUrl",
                    google.baseUrl() + "/a/photo1=s96-c"))
            .buildJson();
    kafka.send("platform.auth.v1", cu, json).join();
    await(
        () ->
            jdbc.queryForObject(
                    "SELECT last_authenticated_at FROM customer WHERE account_id = ?",
                    java.sql.Timestamp.class,
                    cu)
                .toInstant()
                .equals(Instant.parse("2026-10-31T01:00:00Z")),
        "두 번째 로그인 반영");
    Thread.sleep(500);
    google.verify(1, getRequestedFor(urlEqualTo("/a/photo1=s256-c")));

    // 사진을 바꾼 뒤 로그인: 새 사본, 주소 버전이 바뀌고 옛 사본은 지운다
    login(cu, "2026-10-31T02:00:00Z", google.baseUrl() + "/a/photo2=s96-c");
    String url2 = me(token).path("profileImageUrl").asString();
    assertThat(url2).isNotEqualTo(url1);
    assertThat(image(token).getBody()).isEqualTo(PNG_2);
    await(() -> keys(cu).size() == 1, "옛 사본 삭제");

    // 다른 회원은 자기 사진만 — 사본이 없으면 404
    String other = TestJwt.customer("CU-" + UUID.randomUUID());
    assertThat(image(other).getStatusCode().value()).isEqualTo(404);
    // 운영자 토큰은 고객 API 불가
    assertThat(
            image(TestJwt.operator("ops-1", java.util.List.of("OPS"), java.util.List.of()))
                .getStatusCode()
                .value())
        .isEqualTo(403);
  }

  @Test
  void disallowedHostIsNeverRequestedAndNonImageIsNotStored() throws Exception {
    String cu = "CU-" + UUID.randomUUID();
    String token = TestJwt.customer(cu);

    // 허용 밖 호스트: 서버가 요청하지 않는다(SSRF), 사진 없음
    login(cu, "2026-10-31T00:00:00Z", "http://evil.example/a/photo1=s96-c");
    assertThat(
            me(token).path("profileImageUrl").isMissingNode()
                || me(token).path("profileImageUrl").isNull())
        .isTrue();
    assertThat(image(token).getStatusCode().value()).isEqualTo(404);

    // 이미지가 아닌 응답(Content-Type 만 image/png): 저장하지 않는다
    login(cu, "2026-10-31T01:00:00Z", google.baseUrl() + "/a/html=s96-c");
    assertThat(image(token).getStatusCode().value()).isEqualTo(404);
    assertThat(keys(cu)).isEmpty();
  }

  // ---------- 0.15.0 BR-80: 고객이 고르는 사진 ----------

  /** 로그인 사건만 보내고 원본 URL 반영까지 기다린다(CUSTOM·NONE 이면 사본을 받지 않으므로 copied_from 을 기다리지 않는다). */
  void loginNoCopy(String accountId, String at, String pictureUrl) throws Exception {
    String json =
        EventEnvelopeFixtures.envelope("CustomerAuthenticated")
            .from("auth-service")
            .aggregate("Account", accountId, 1)
            .payload(
                Map.of(
                    "accountId",
                    accountId,
                    "provider",
                    "GOOGLE",
                    "authenticatedAt",
                    at,
                    "pictureUrl",
                    pictureUrl))
            .buildJson();
    kafka.send("platform.auth.v1", accountId, json).join();
    await(
        () ->
            pictureUrl.equals(
                jdbc.queryForObject(
                    "SELECT profile_image_source_url FROM customer WHERE account_id = ?",
                    String.class,
                    accountId)),
        "로그인 반영 " + at);
  }

  ResponseEntity<String> upload(String token, String key, byte[] bytes) {
    org.springframework.util.LinkedMultiValueMap<String, Object> form =
        new org.springframework.util.LinkedMultiValueMap<>();
    form.add(
        "file",
        new org.springframework.core.io.ByteArrayResource(bytes) {
          @Override
          public String getFilename() {
            return "photo.jpg";
          }
        });
    return http.put()
        .uri("/me/profile-image")
        .headers(
            h -> {
              h.setBearerAuth(token);
              h.set("Idempotency-Key", key);
              h.set("X-Source-System", "customer-app");
            })
        .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
        .body(form)
        .retrieve()
        .toEntity(String.class);
  }

  ResponseEntity<String> command(String token, String method, String path) {
    return http.method(org.springframework.http.HttpMethod.valueOf(method))
        .uri(path)
        .headers(
            h -> {
              h.setBearerAuth(token);
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "customer-app");
            })
        .retrieve()
        .toEntity(String.class);
  }

  static byte[] jpeg(int fill) {
    byte[] b = new byte[128];
    Arrays.fill(b, (byte) fill);
    b[0] = (byte) 0xFF;
    b[1] = (byte) 0xD8;
    b[2] = (byte) 0xFF;
    return b;
  }

  @Test
  void customerChoosesPhotoAndLoginDoesNotOverwriteIt() throws Exception {
    String cu = "CU-" + UUID.randomUUID();
    String token = TestJwt.customer(cu);
    login(cu, "2026-11-21T00:00:00Z", google.baseUrl() + "/a/photo1=s96-c");
    assertThat(me(token).path("photoSource").asString()).isEqualTo("PROVIDER");
    String providerUrl = me(token).path("profileImageUrl").asString();

    // R1 직접 올리기 → CUSTOM, 새 주소, 제공자 사본은 지움. 같은 키 재전송은 같은 결과
    byte[] mine = jpeg(7);
    String key = UUID.randomUUID().toString();
    ResponseEntity<String> up = upload(token, key, mine);
    assertThat(up.getStatusCode().value()).isEqualTo(200);
    assertThat(mapper.readTree(up.getBody()).path("status").asString()).isEqualTo("APPLIED");
    assertThat(upload(token, key, mine).getStatusCode().value()).isEqualTo(200);
    JsonNode me = me(token);
    assertThat(me.path("photoSource").asString()).isEqualTo("CUSTOM");
    assertThat(me.path("profileImageUrl").asString()).isNotEqualTo(providerUrl);
    ResponseEntity<byte[]> img = image(token);
    assertThat(img.getHeaders().getContentType()).hasToString("image/jpeg");
    assertThat(img.getBody()).isEqualTo(mine);
    await(() -> keys(cu).size() == 1, "제공자 사본 삭제");

    // R4 로그인(새 제공자 사진)해도 덮지 않는다
    loginNoCopy(cu, "2026-11-21T01:00:00Z", google.baseUrl() + "/a/photo2=s96-c");
    Thread.sleep(500);
    google.verify(0, getRequestedFor(urlEqualTo("/a/photo2=s256-c")));
    assertThat(image(token).getBody()).isEqualTo(mine);

    // 형식·크기 검사 — 400 VALIDATION_FAILED field file
    ResponseEntity<String> notImage =
        upload(token, UUID.randomUUID().toString(), "hello".getBytes());
    assertThat(notImage.getStatusCode().value()).isEqualTo(400);
    assertThat(notImage.getBody()).contains("\"field\":\"file\"");
    byte[] big = new byte[2 * 1024 * 1024 + 1];
    System.arraycopy(jpeg(1), 0, big, 0, 3);
    assertThat(upload(token, UUID.randomUUID().toString(), big).getStatusCode().value())
        .isEqualTo(400);

    // R2 기본 이미지 → NONE, 사진 없음, 저장소도 비움
    assertThat(command(token, "DELETE", "/me/profile-image").getStatusCode().value())
        .isEqualTo(200);
    me = me(token);
    assertThat(me.path("photoSource").asString()).isEqualTo("NONE");
    assertThat(me.path("profileImageUrl").isMissingNode() || me.path("profileImageUrl").isNull())
        .isTrue();
    assertThat(image(token).getStatusCode().value()).isEqualTo(404);
    await(() -> keys(cu).isEmpty(), "올린 사진 삭제");
    loginNoCopy(cu, "2026-11-21T02:00:00Z", google.baseUrl() + "/a/photo1=s96-c");
    Thread.sleep(500);
    assertThat(image(token).getStatusCode().value()).isEqualTo(404);

    // R3 로그인 계정 사진으로 되돌리기 → PROVIDER, 마지막 제공자 사진(photo1)을 다시 받는다
    assertThat(
            command(token, "POST", "/me/profile-image/provider-restorations")
                .getStatusCode()
                .value())
        .isEqualTo(200);
    await(() -> image(token).getStatusCode().value() == 200, "제공자 사진 다시 받기");
    assertThat(me(token).path("photoSource").asString()).isEqualTo("PROVIDER");
    assertThat(image(token).getBody()).isEqualTo(PNG_1);

    // 다른 사람·운영자는 못 바꾼다(운영자 토큰 403)
    assertThat(
            command(
                    TestJwt.operator("ops-1", java.util.List.of("OPS"), java.util.List.of()),
                    "DELETE",
                    "/me/profile-image")
                .getStatusCode()
                .value())
        .isEqualTo(403);
  }
}
