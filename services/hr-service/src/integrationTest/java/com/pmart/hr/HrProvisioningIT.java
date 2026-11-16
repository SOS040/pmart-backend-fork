package com.pmart.hr;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.hr.application.ContractEndJob;
import com.pmart.hr.application.IamSyncWorker;
import com.pmart.hr.application.ProvisioningWorker;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 직원 등록 → 계정 발급 E2E — 실제 Keycloak 26.8.1(hr-provisioner 서비스 계정, manage/view/query-users
 * 만)·메일(Mailpit, Keycloak SMTP)·PostgreSQL·Kafka. mail-provisioner 는 WireMock(계약
 * mail-provisioner.yaml 대로). 사서함 → Keycloak 계정 → 비밀번호 설정 메일 → EmployeeRegistered, 사서함이 아직 메일을 못 받을
 * 때 기다림, 영구 거절 → 보상, 정지·퇴사·복귀가 Keycloak·사서함에 반영되는지, 계약 종료 자동 정지, U-11(manage-users 로 역할 매핑이 되는지) 사실
 * 확인.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.hr.provisioning.poll-interval=PT1H", // 시험이 직접 돌린다
      "pmart.hr.provisioning.max-attempts=3",
      "pmart.hr.keycloak.client-secret=it-hr-provisioner-secret"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock(
    @ConfigureWireMock(
        name = "mail",
        baseUrlProperties = {"pmart.hr.mail-provisioner.base-url"}))
class HrProvisioningIT extends PostgresKafkaContainerSupport {

  static final Network NET = Network.newNetwork();

  @SuppressWarnings("resource")
  static final GenericContainer<?> MAILPIT =
      new GenericContainer<>("axllent/mailpit:v1.21.8")
          .withNetwork(NET)
          .withNetworkAliases("mailpit")
          .withExposedPorts(8025)
          .waitingFor(Wait.forHttp("/api/v1/info").forPort(8025));

  @SuppressWarnings("resource")
  static final GenericContainer<?> KEYCLOAK =
      new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
          .withNetwork(NET)
          .withCommand("start-dev", "--import-realm")
          .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
          .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("keycloak/pmart-staff-it-realm.json"),
              "/opt/keycloak/data/import/pmart-staff-realm.json") // 파일 이름 = Realm 이름(26.8)
          .withExposedPorts(8080)
          .waitingFor(
              Wait.forHttp("/realms/pmart-staff")
                  .forPort(8080)
                  .forStatusCode(200)
                  .withStartupTimeout(Duration.ofMinutes(4)));

  static {
    MAILPIT.start();
    KEYCLOAK.start();
  }

  static String kc() {
    return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("pmart.hr.keycloak.admin-base-url", HrProvisioningIT::kc);
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired ProvisioningWorker worker;
  @Autowired IamSyncWorker iamSync;
  @Autowired ContractEndJob contractEnd;

  @InjectWireMock("mail")
  WireMockServer mail;

  RestClient http;
  final String hr = TestJwt.operator("OP-IT-HR", List.of("HR_ADMIN"), List.of());

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    mail.resetAll();
    mail.stubFor(
        post(urlPathEqualTo("/api/v1/internal/mailboxes"))
            .willReturn(json("{\"address\":\"x\",\"state\":\"ACTIVE\",\"receiving\":false}")));
    mail.stubFor(
        get(urlMatching("/api/v1/internal/mailboxes/[^/]+"))
            .willReturn(json("{\"address\":\"x\",\"state\":\"ACTIVE\",\"receiving\":true}")));
    mail.stubFor(
        post(urlMatching("/api/v1/internal/mailboxes/.+/(disable|enable)")).willReturn(json("{}")));
    mail.stubFor(
        put(urlMatching("/api/v1/internal/mailboxes/.+/password"))
            .willReturn(aResponse().withStatus(204)));
  }

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  record Res(int status, JsonNode body) {}

  Res call(String method, String path, String token, Object body) {
    var spec =
        http.method(org.springframework.http.HttpMethod.valueOf(method))
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "hr-backoffice-web");
                });
    if (body != null) {
      spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
    }
    return spec.exchange(
        (q, r) -> new Res(r.getStatusCode().value(), read(r.bodyTo(byte[].class))));
  }

  JsonNode read(byte[] b) {
    return b == null || b.length == 0 ? mapper.createObjectNode() : mapper.readTree(b);
  }

  static String mailId() {
    return "it" + UUID.randomUUID().toString().substring(0, 8);
  }

  Map<String, Object> registration(String mailId, String type, LocalDate start, LocalDate end) {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("name", "홍길동");
    m.put("mailId", mailId);
    m.put("employmentType", type);
    m.put("affiliation", Map.of("type", "CENTER", "code", "MFC-IT-01"));
    m.put("startDate", start.toString());
    if (end != null) {
      m.put("endDate", end.toString());
    }
    m.put(
        "requestedGrants",
        List.of(Map.of("role", "MFC_WORKER", "centers", List.of("MFC-IT-01"), "reason", "입사")));
    return m;
  }

  String register(String mailId) {
    Res r = call("POST", "/hr/employees", hr, registration(mailId, "REGULAR", today(), null));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(201);
    return r.body().path("operatorId").asText();
  }

  String step(String operatorId) {
    return jdbc.queryForObject(
        "SELECT step FROM provisioning WHERE operator_id = ?", String.class, operatorId);
  }

  void runUntilSettled(String operatorId) {
    for (int i = 0; i < 10; i++) {
      jdbc.update(
          "UPDATE provisioning SET next_attempt_at = now() WHERE operator_id = ?", operatorId);
      worker.drain();
      String s = step(operatorId);
      if (List.of("DONE", "FAILED", "COMPENSATED").contains(s)) {
        return;
      }
    }
  }

  int attempts(String operatorId) {
    return jdbc.queryForObject(
        "SELECT attempts FROM provisioning WHERE operator_id = ?", Integer.class, operatorId);
  }

  int outbox(String operatorId, String type) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM outbox_event WHERE topic = 'platform.employee.v1' AND aggregateid = ? AND type = ?",
        Integer.class,
        operatorId,
        type);
  }

  /** 경로의 @ 는 %40 으로 나간다. */
  static String encoded(String address) {
    return address.replace("@", "%40");
  }

  static LocalDate today() {
    return LocalDate.now(ZoneId.of("Asia/Seoul"));
  }

  // ------------------------------------------------------------------ Keycloak·메일 확인 도우미

  String adminToken() throws Exception {
    HttpResponse<String> r =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(kc() + "/realms/master/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            "grant_type=password&client_id=admin-cli&username=admin&password=admin"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    return mapper.readTree(r.body()).path("access_token").asText();
  }

  JsonNode kcUser(String username) throws Exception {
    HttpResponse<String> r =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            kc()
                                + "/admin/realms/pmart-staff/users?exact=true&briefRepresentation=false&username="
                                + URLEncoder.encode(username, StandardCharsets.UTF_8)))
                    .header("Authorization", "Bearer " + adminToken())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    JsonNode list = mapper.readTree(r.body());
    return list.isEmpty() ? null : list.get(0);
  }

  int mailsTo(String address) throws Exception {
    HttpResponse<String> r =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            "http://"
                                + MAILPIT.getHost()
                                + ":"
                                + MAILPIT.getMappedPort(8025)
                                + "/api/v1/search?query="
                                + URLEncoder.encode("to:" + address, StandardCharsets.UTF_8)))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    return mapper.readTree(r.body()).path("messages_count").asInt();
  }

  // ------------------------------------------------------------------ 시험

  @Test
  void registrationProvisionsMailboxKeycloakAccountAndInvite() throws Exception {
    String mailId = mailId();
    String address = mailId + "@pmart.com";
    Res created = call("POST", "/hr/employees", hr, registration(mailId, "REGULAR", today(), null));
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String op = created.body().path("operatorId").asText();
    assertThat(op).startsWith("OP-");
    assertThat(created.body().path("employeeNo").asText())
        .matches("P" + today().getYear() + "-\\d{5}");
    assertThat(created.body().path("mailAddress").asText()).isEqualTo(address);
    assertThat(created.body().path("requestedGrants").get(0).path("submission").asText())
        .isEqualTo("PENDING");

    runUntilSettled(op);
    assertThat(step(op)).isEqualTo("DONE");

    JsonNode u = kcUser(address);
    assertThat(u).as("Keycloak 사용자 — username = 메일 주소(D2)").isNotNull();
    assertThat(u.path("email").asText()).isEqualTo(address);
    assertThat(u.path("enabled").asBoolean()).isTrue();
    assertThat(u.path("attributes").path("pmart_operator_id").get(0).asText()).isEqualTo(op);
    assertThat(u.path("requiredActions").toString()).contains("UPDATE_PASSWORD");
    assertThat(mailsTo(address)).as("비밀번호 설정 메일(Keycloak → SMTP)").isGreaterThanOrEqualTo(1);
    assertThat(outbox(op, "EmployeeRegistered")).isEqualTo(1);
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'EmployeeRegistered'",
            String.class,
            op);
    assertThat(payload).contains(u.path("id").asText()).doesNotContain("홍길동");
    mail.verify(postRequestedFor(urlPathEqualTo("/api/v1/internal/mailboxes")));

    // 요청 권한 → GrantRequested(같은 트랜잭션, ADR-0056 5-4) — 요청자 = 등록한 인사팀 직원, 기간 기본 = 근무 기간
    assertThat(outbox(op, "GrantRequested")).isEqualTo(1);
    JsonNode gr =
        mapper
            .readTree(
                jdbc.queryForObject(
                    "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'GrantRequested'",
                    String.class,
                    op))
            .path("payload");
    assertThat(gr.path("requestedBy").asText()).isEqualTo("OP-IT-HR");
    assertThat(gr.path("employeeNo").asText())
        .isEqualTo(created.body().path("employeeNo").asText());
    assertThat(gr.path("mailAddress").asText()).isEqualTo(address);
    assertThat(gr.toString()).doesNotContain("홍길동");
    assertThat(gr.path("affiliation").path("code").asText()).isEqualTo("MFC-IT-01");
    JsonNode line = gr.path("lines").get(0);
    assertThat(line.path("role").asText()).isEqualTo("MFC_WORKER");
    assertThat(line.path("validFrom").asText()).isEqualTo(today().toString());
    assertThat(line.path("validTo").isNull()).as("정규직 — 기한 없음").isTrue();
    Res submitted = call("GET", "/hr/employees/" + op, hr, null);
    assertThat(submitted.body().path("requestedGrants").get(0).path("submission").asText())
        .isEqualTo("SUBMITTED");

    // approval-service 의 결정(자동 승인) → APPROVED
    String approvalId = "AP-" + UUID.randomUUID();
    kafka
        .send(
            "platform.approval.v1",
            approvalId,
            EventEnvelopeFixtures.envelope("ApprovalDecided")
                .from("approval-service")
                .aggregate("Approval", approvalId, 2)
                .payload(
                    Map.of(
                        "approvalId",
                        approvalId,
                        "type",
                        "GRANT",
                        "decision",
                        "APPROVED",
                        "targetRef",
                        Map.of(
                            "targetType", "EmployeeGrant",
                            "targetId", op,
                            "targetService", "employee-auth-service"),
                        "lines",
                        List.of(
                            Map.of(
                                "lineId", line.path("lineKey").asText(), "decision", "APPROVED")),
                        "approverId",
                        "RULE:GRANT-MFC-WORKER-OWN-CENTER@v1",
                        "decidedAt",
                        java.time.Instant.now().toString()))
                .buildJson())
        .join();
    java.time.Instant until = java.time.Instant.now().plusSeconds(30);
    String state = "SUBMITTED";
    while (!"APPROVED".equals(state) && java.time.Instant.now().isBefore(until)) {
      Thread.sleep(200);
      state =
          call("GET", "/hr/employees/" + op, hr, null)
              .body()
              .path("requestedGrants")
              .get(0)
              .path("submission")
              .asText();
    }
    assertThat(state).isEqualTo("APPROVED");
    JsonNode g0 =
        call("GET", "/hr/employees/" + op, hr, null).body().path("requestedGrants").get(0);
    assertThat(g0.path("reason").asText()).isEqualTo("입사");
    assertThat(g0.path("validFrom").asText()).as("비운 기간 = 근무 시작일").isEqualTo(today().toString());
    assertThat(g0.path("approvalRef").asText()).isEqualTo(approvalId);

    // 본인 조회(그 직원 토큰)
    Res me = call("GET", "/hr/employees/me", TestJwt.operator(op, List.of(), List.of()), null);
    assertThat(me.status()).isEqualTo(200);
    assertThat(me.body().path("name").asText()).isEqualTo("홍길동");

    // 같은 메일 ID 다시 — 409
    assertThat(
            call("POST", "/hr/employees", hr, registration(mailId, "REGULAR", today(), null))
                .status())
        .isEqualTo(409);
  }

  @Test
  void waitsUntilMailboxReceivesBeforeCreatingAccount() throws Exception {
    mail.stubFor(
        get(urlMatching("/api/v1/internal/mailboxes/[^/]+"))
            .willReturn(json("{\"address\":\"x\",\"state\":\"ACTIVE\",\"receiving\":false}")));
    String mailId = mailId();
    String op = register(mailId);
    // 등록 직후 백그라운드 실행(kick)과 겹칠 수 있다 — 한 번 시도가 기록될 때까지
    for (int i = 0; i < 50 && attempts(op) == 0; i++) {
      worker.drain();
      Thread.sleep(100);
    }
    assertThat(step(op)).isEqualTo("REQUESTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT last_error_code FROM provisioning WHERE operator_id = ?", String.class, op))
        .isEqualTo("MAILBOX_NOT_READY");
    assertThat(kcUser(mailId + "@pmart.com")).as("사서함 준비 전에는 Keycloak 계정·메일 없음").isNull();

    mail.stubFor(
        get(urlMatching("/api/v1/internal/mailboxes/[^/]+"))
            .willReturn(json("{\"address\":\"x\",\"state\":\"ACTIVE\",\"receiving\":true}")));
    runUntilSettled(op);
    assertThat(step(op)).isEqualTo("DONE");
  }

  @Test
  void permanentRejectionCompensates() throws Exception {
    // 같은 아이디의 다른 사람 Keycloak 계정이 이미 있다
    String mailId = mailId();
    String address = mailId + "@pmart.com";
    HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(kc() + "/admin/realms/pmart-staff/users"))
                .header("Authorization", "Bearer " + adminToken())
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\""
                            + address
                            + "\",\"enabled\":true,\"attributes\":{\"pmart_operator_id\":[\"OP-SOMEONE-ELSE\"]}}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    String op = register(mailId);
    runUntilSettled(op);
    assertThat(step(op)).isEqualTo("COMPENSATED");
    assertThat(
            jdbc.queryForObject(
                "SELECT last_error_code FROM provisioning WHERE operator_id = ?", String.class, op))
        .isEqualTo("IAM_USERNAME_TAKEN");
    mail.verify(
        postRequestedFor(
            urlMatching("/api/v1/internal/mailboxes/" + encoded(address) + "/disable")));
    assertThat(outbox(op, "EmployeeRegistered")).isZero();
    assertThat(kcUser(address).path("enabled").asBoolean()).as("남의 계정은 건드리지 않는다").isTrue();

    // 다시 시도는 COMPENSATED 에서만
    Res retry = call("POST", "/hr/employees/" + op + "/provisioning-retries", hr, null);
    assertThat(retry.status()).isEqualTo(202);
    assertThat(retry.body().path("provisioning").path("step").asText()).isEqualTo("REQUESTED");
  }

  @Test
  void suspendDepartReinstateReachKeycloakMailboxAndEvents() throws Exception {
    String mailId = mailId();
    String address = mailId + "@pmart.com";
    String op = register(mailId);
    runUntilSettled(op);

    Res s = call("POST", "/hr/employees/" + op + "/suspensions", hr, Map.of("reason", "징계"));
    assertThat(s.status()).isEqualTo(200);
    assertThat(s.body().path("status").asText()).isEqualTo("SUSPENDED");
    assertThat(outbox(op, "EmployeeSuspended")).isEqualTo(1);
    iamSync.poll();
    assertThat(kcUser(address).path("enabled").asBoolean()).isFalse();

    Res l = call("POST", "/hr/employees/" + op + "/departures", hr, Map.of("reason", "퇴사"));
    assertThat(l.body().path("status").asText()).isEqualTo("LEFT");
    assertThat(outbox(op, "EmployeeLeft")).isEqualTo(1);
    iamSync.poll();
    mail.verify(
        postRequestedFor(
            urlMatching("/api/v1/internal/mailboxes/" + encoded(address) + "/disable")));

    Res b = call("POST", "/hr/employees/" + op + "/reinstatements", hr, Map.of("reason", "재입사"));
    assertThat(b.status()).as(b.body().toString()).isEqualTo(200);
    assertThat(b.body().path("status").asText()).isEqualTo("ACTIVE");
    assertThat(outbox(op, "EmployeeReinstated")).isEqualTo(1);
    iamSync.poll();
    assertThat(kcUser(address).path("enabled").asBoolean()).isTrue();
    mail.verify(
        postRequestedFor(
            urlMatching("/api/v1/internal/mailboxes/" + encoded(address) + "/enable")));
    assertThat(b.body().path("employeeNo").asText())
        .as("재입사도 같은 사번")
        .isEqualTo(s.body().path("employeeNo").asText());
  }

  /**
   * 재직 중 권한 추가·변경 요청(BO-01, 0.3.0): 결정 안 된 요청이 있으면 409, 결정된 뒤 새 요청 → GrantRequested(요청자 = 요청한 인사팀
   * 직원, 비운 기간 = 근무 기간), 자기 권한 403, 정지된 직원 409.
   */
  @Test
  void grantRequestsForActiveEmployee() throws Exception {
    String op = register(mailId());
    runUntilSettled(op);
    Map<String, Object> body =
        Map.of("grants", List.of(Map.of("role", "CS_AGENT")), "reason", "업무 변경 — 고객센터 지원");

    Res pending = call("POST", "/hr/employees/" + op + "/grant-requests", hr, body);
    assertThat(pending.status()).as("등록 때 요청이 아직 심사 중").isEqualTo(409);
    assertThat(pending.body().path("code").asText()).isEqualTo("GRANT_REQUEST_PENDING");

    jdbc.update(
        "UPDATE requested_grant SET submission = 'APPROVED' WHERE operator_id = ?",
        op); // 승인 결과가 왔다고 친다
    Res ok = call("POST", "/hr/employees/" + op + "/grant-requests", hr, body);
    assertThat(ok.status()).as(ok.body().toString()).isEqualTo(200);
    JsonNode grants = ok.body().path("requestedGrants");
    assertThat(grants).hasSize(2);
    assertThat(grants.get(1).path("role").asText()).isEqualTo("CS_AGENT");
    assertThat(grants.get(1).path("submission").asText()).isEqualTo("SUBMITTED");
    assertThat(outbox(op, "GrantRequested")).isEqualTo(2);
    JsonNode gr =
        mapper
            .readTree(
                jdbc.queryForObject(
                    "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'GrantRequested'"
                        + " ORDER BY created_at DESC LIMIT 1",
                    String.class,
                    op))
            .path("payload");
    assertThat(gr.path("requestedBy").asText()).isEqualTo("OP-IT-HR");
    assertThat(gr.path("lines")).hasSize(1);
    assertThat(gr.path("lines").get(0).path("role").asText()).isEqualTo("CS_AGENT");
    assertThat(gr.path("lines").get(0).path("validTo").isNull()).as("정규직 — 근무 기간까지").isTrue();
    assertThat(gr.path("lines").get(0).path("reason").asText()).isEqualTo("업무 변경 — 고객센터 지원");

    assertThat(call("POST", "/hr/employees/OP-IT-HR/grant-requests", hr, body).status())
        .as("자기 권한")
        .isEqualTo(403);

    jdbc.update("UPDATE requested_grant SET submission = 'APPROVED' WHERE operator_id = ?", op);
    assertThat(
            call("POST", "/hr/employees/" + op + "/suspensions", hr, Map.of("reason", "징계"))
                .status())
        .isEqualTo(200);
    assertThat(call("POST", "/hr/employees/" + op + "/grant-requests", hr, body).status())
        .as("재직 중 아님")
        .isEqualTo(409);
  }

  /**
   * 소속 변경(2026-11-16 BO-01): 발급 끝난 직원의 센터 → 센터 변경은 EmployeeAffiliationChanged(이전 센터 권한 끝 = 다음 날
   * 00:00), 같은 값은 사건 없음.
   */
  @Test
  void affiliationChangePublishesEventWithPreviousCenterAccessEnd() throws Exception {
    String op = register(mailId());
    runUntilSettled(op);
    Map<String, Object> body =
        Map.of("affiliation", Map.of("type", "CENTER", "code", "MFC-IT-02"), "reason", "센터 이동");
    Res r = call("POST", "/hr/employees/" + op + "/affiliation-changes", hr, body);
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(outbox(op, "EmployeeAffiliationChanged")).isEqualTo(1);
    JsonNode ev =
        mapper
            .readTree(
                jdbc.queryForObject(
                    "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'EmployeeAffiliationChanged'",
                    String.class,
                    op))
            .path("payload");
    assertThat(ev.path("previous").path("code").asText()).isEqualTo("MFC-IT-01");
    assertThat(ev.path("affiliation").path("code").asText()).isEqualTo("MFC-IT-02");
    assertThat(ev.path("previousCenterAccessUntil").asText())
        .isEqualTo(
            today()
                .plusDays(1)
                .atStartOfDay(java.time.ZoneId.of("Asia/Seoul"))
                .toInstant()
                .toString());

    assertThat(call("POST", "/hr/employees/" + op + "/affiliation-changes", hr, body).status())
        .isEqualTo(200);
    assertThat(outbox(op, "EmployeeAffiliationChanged")).as("같은 소속 — 사건 없음").isEqualTo(1);
  }

  @Test
  void contractEndSuspendsAutomatically() {
    String mailId = mailId();
    Res r =
        call(
            "POST",
            "/hr/employees",
            hr,
            registration(mailId, "DAILY", today().minusDays(3), today().minusDays(1)));
    String op = r.body().path("operatorId").asText();
    runUntilSettled(op);
    contractEnd.run();
    assertThat(
            jdbc.queryForObject(
                "SELECT status || ':' || status_reason FROM employee WHERE operator_id = ?",
                String.class,
                op))
        .isEqualTo("SUSPENDED:CONTRACT_ENDED");
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'EmployeeSuspended'",
            String.class,
            op);
    assertThat(payload).contains("CONTRACT_ENDED");
  }

  /** 본사 부서 코드 목록(2026-11-15): 처음 보는 코드는 등록·소속 변경이 더하고(거절 안 함), 목록은 코드 순, 이름은 PUT 으로. */
  @Test
  void hqDepartmentList() {
    String suffix = UUID.randomUUID().toString().substring(0, 6);
    String b = "IT-DEPT-B-" + suffix;
    String a = "IT-DEPT-A-" + suffix;
    Map<String, Object> reg = registration(mailId(), "REGULAR", today(), null);
    reg.put("affiliation", Map.of("type", "HQ_DEPARTMENT", "code", b));
    reg.remove("requestedGrants");
    Res created = call("POST", "/hr/employees", hr, reg);
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String op = created.body().path("operatorId").asText();

    // 다른 직원을 센터에서 본사 부서 a 로 옮긴다
    String other = register(mailId());
    Res moved =
        call(
            "POST",
            "/hr/employees/" + other + "/affiliation-changes",
            hr,
            Map.of("affiliation", Map.of("type", "HQ_DEPARTMENT", "code", a), "reason", "본사 발령"));
    assertThat(moved.status()).as(moved.body().toString()).isEqualTo(200);

    Res list = call("GET", "/hr/departments", hr, null);
    assertThat(list.status()).isEqualTo(200);
    List<String> codes = new java.util.ArrayList<>();
    list.body().path("items").forEach(i -> codes.add(i.path("code").asText()));
    assertThat(codes).contains(a, b).isSortedAccordingTo(String::compareTo);
    assertThat(codes).as("센터 코드는 목록에 넣지 않는다").doesNotContain("MFC-IT-01");
    JsonNode bItem = item(list.body(), b);
    assertThat(bItem.path("employeeCount").asInt()).isEqualTo(1);
    assertThat(bItem.has("name") && !bItem.path("name").isNull()).isFalse();

    Res named = call("PUT", "/hr/departments/" + b, hr, Map.of("name", " 인사팀 "));
    assertThat(named.status()).as(named.body().toString()).isEqualTo(200);
    assertThat(named.body().path("name").asText()).isEqualTo("인사팀");
    assertThat(named.body().path("employeeCount").asInt()).isEqualTo(1);
    assertThat(item(call("GET", "/hr/departments", hr, null).body(), b).path("name").asText())
        .isEqualTo("인사팀");

    // 이미 있는 코드로 다시 등록해도 이름은 그대로
    Map<String, Object> again = registration(mailId(), "REGULAR", today(), null);
    again.put("affiliation", Map.of("type", "HQ_DEPARTMENT", "code", b));
    assertThat(call("POST", "/hr/employees", hr, again).status()).isEqualTo(201);
    JsonNode after = item(call("GET", "/hr/departments", hr, null).body(), b);
    assertThat(after.path("name").asText()).isEqualTo("인사팀");
    assertThat(after.path("employeeCount").asInt()).isEqualTo(2);

    // PUT 으로 새 코드도 만든다(아직 소속 직원 없음)
    String c = "IT-DEPT-C-" + suffix;
    Res createdByPut = call("PUT", "/hr/departments/" + c, hr, Map.of("name", "기획팀"));
    assertThat(createdByPut.status()).isEqualTo(200);
    assertThat(createdByPut.body().path("employeeCount").asInt()).isZero();

    String worker = TestJwt.operator("OP-IT-WRK", List.of("MFC_WORKER"), List.of());
    assertThat(call("GET", "/hr/departments", worker, null).status()).isEqualTo(403);
    assertThat(call("PUT", "/hr/departments/" + b, worker, Map.of("name", "x")).status())
        .isEqualTo(403);
    assertThat(op).isNotBlank();
  }

  static JsonNode item(JsonNode list, String code) {
    for (JsonNode i : list.path("items")) {
      if (code.equals(i.path("code").asText())) {
        return i;
      }
    }
    throw new AssertionError("목록에 없음: " + code);
  }

  /**
   * 내부 이름 조회(0.5.0, 2026-11-17 — employee-auth-service 가 승인자·회수자 이름을 붙인다): 토큰 없이, 아는 ID 만, 퇴사자 포함,
   * 형식이 틀린 ID 는 400.
   */
  @Test
  void internalEmployeeNames() {
    String a = register(mailId());
    Map<String, Object> reg = registration(mailId(), "REGULAR", today(), null);
    reg.put("name", "김승인");
    reg.remove("requestedGrants");
    Res created = call("POST", "/hr/employees", hr, reg);
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    String b = created.body().path("operatorId").asText();
    jdbc.update("UPDATE employee SET status = 'LEFT' WHERE operator_id = ?", b);

    Res r =
        http.get()
            .uri(
                u ->
                    u.path("/internal/employee-names")
                        .queryParam("operatorIds", a, b, "OP-NO-SUCH", a)
                        .build())
            .exchange((q, s) -> new Res(s.getStatusCode().value(), read(s.bodyTo(byte[].class))));
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    Map<String, String> names = new java.util.HashMap<>();
    r.body()
        .path("items")
        .forEach(i -> names.put(i.path("operatorId").asText(), i.path("name").asText()));
    assertThat(names).containsOnly(Map.entry(a, "홍길동"), Map.entry(b, "김승인"));
    assertThat(r.body().path("items").get(0).has("mailAddress")).as("이름 말고는 싣지 않는다").isFalse();

    int bad =
        http.get()
            .uri(
                u -> u.path("/internal/employee-names").queryParam("operatorIds", "OP bad").build())
            .exchange((q, s) -> s.getStatusCode().value());
    assertThat(bad).isEqualTo(400);
  }

  @Test
  void validationAndAuthorization() {
    String worker = TestJwt.operator("OP-IT-WRK", List.of("MFC_WORKER"), List.of());
    assertThat(
            call("POST", "/hr/employees", worker, registration(mailId(), "REGULAR", today(), null))
                .status())
        .as("HR_ADMIN 아님")
        .isEqualTo(403);
    assertThat(call("GET", "/hr/employees", worker, null).status()).isEqualTo(403);
    assertThat(
            call("POST", "/hr/employees", hr, registration("postmaster", "REGULAR", today(), null))
                .status())
        .as("예약 메일 ID")
        .isEqualTo(400);
    assertThat(
            call("POST", "/hr/employees", hr, registration(mailId(), "PART_TIME", today(), null))
                .status())
        .as("파트타임은 계약 종료일 필요")
        .isEqualTo(400);
    assertThat(
            call("POST", "/hr/employees", hr, registration("Bad.ID", "REGULAR", today(), null))
                .status())
        .isEqualTo(400);
    assertThat(
            call(
                    "GET",
                    "/hr/employees/me",
                    TestJwt.operator("OP-NOBODY", List.of(), List.of()),
                    null)
                .status())
        .isEqualTo(404);
  }

  @Test
  void mailboxPasswordIsReturnedOnceAndNotStored() {
    String op = register(mailId());
    runUntilSettled(op);
    Res r = call("POST", "/hr/employees/" + op + "/mailbox-password-resets", hr, null);
    assertThat(r.status()).isEqualTo(200);
    String pw = r.body().path("initialPassword").asText();
    assertThat(pw).hasSizeGreaterThanOrEqualTo(16);
    mail.verify(
        putRequestedFor(urlMatching("/api/v1/internal/mailboxes/.+/password"))
            .withRequestBody(matching(".*" + pw + ".*")));
    Integer stored =
        jdbc.queryForObject(
            "SELECT count(*) FROM request_record WHERE result::text LIKE ?",
            Integer.class,
            "%" + pw + "%");
    assertThat(stored).as("비밀번호는 어디에도 저장하지 않는다").isZero();
  }

  /**
   * U-11 사실 확인: realm-management 의 manage-users 만 가진 서비스 계정이 Realm 역할 매핑까지 할 수 있는가(Keycloak
   * 26.8.1). 할 수 있으면 hr-service 가 역할 매핑 API 를 부르지 않는 것(U-11 B)이 유일한 막이다 — 결과를 문서에 적는다.
   */
  @Test
  void u11ManageUsersCanAlsoMapRealmRoles() throws Exception {
    String op = register(mailId());
    runUntilSettled(op);
    String userId =
        jdbc.queryForObject(
            "SELECT keycloak_user_id FROM employee WHERE operator_id = ?", String.class, op);
    HttpResponse<String> t =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(kc() + "/realms/pmart-staff/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            "grant_type=client_credentials&client_id=hr-provisioner&client_secret=it-hr-provisioner-secret"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    String token = mapper.readTree(t.body()).path("access_token").asText();
    // 역할 정보는 관리자로 읽는다(hr-provisioner 는 Realm 역할을 읽지 못한다 — 403)
    HttpResponse<String> roleByProvisioner =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(kc() + "/admin/realms/pmart-staff/roles/MD"))
                    .header("Authorization", "Bearer " + token)
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(roleByProvisioner.statusCode()).isEqualTo(403);
    HttpResponse<String> role =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(kc() + "/admin/realms/pmart-staff/roles/MD"))
                    .header("Authorization", "Bearer " + adminToken())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    HttpResponse<String> map =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create(
                            kc()
                                + "/admin/realms/pmart-staff/users/"
                                + userId
                                + "/role-mappings/realm"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("[" + role.body() + "]"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(map.statusCode())
        .as(
            "U-11 결과(2026-11-12 확인): manage-users 만으로도 Realm 역할 매핑이 된다 — hr-service 는 역할 매핑 API 를 부르지 않는다(B), 가능하면 발급자 표 Keycloak 줄을 hr-service 배포 전에 뺀다(A)")
        .isEqualTo(204);
  }
}
