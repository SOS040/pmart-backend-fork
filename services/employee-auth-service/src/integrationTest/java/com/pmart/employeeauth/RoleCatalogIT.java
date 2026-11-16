package com.pmart.employeeauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 직원 역할 목록(0.5.0, BR-57): 설정 roles 순서·role-info 이름·센터 지정 그대로, 토큰 없으면 401, 역할 없는(HR_ADMIN 아닌) 직원
 * 토큰으로도 200. 토큰은 로컬 개발 발급(DevMintIT 와 같은 설정, 시험 문맥은 따로 — 컨테이너가 클래스마다).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.employee-auth.issuer=http://employee-auth.test",
      "pmart.employee-auth.dev-token-mint.enabled=true",
      "pmart.test.context=role-catalog",
      "pmart.messaging.outbox.delete-after-insert=false" // 회수 SubjectBlocked 확인
    })
class RoleCatalogIT extends PostgresKafkaContainerSupport {

  /**
   * hr-service 내부 이름 조회 흉내(0.10.0): 받은 OP-… 마다 이름 "이름:" + ID, {@link #hrDown} 이면 503. 부른 횟수를
   * 센다(응답마다 한 번).
   */
  static final HttpServer HR = startHr();

  static volatile boolean hrDown = false;
  static final AtomicInteger HR_CALLS = new AtomicInteger();

  static HttpServer startHr() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext(
          "/api/v1/internal/employee-names",
          ex -> {
            HR_CALLS.incrementAndGet();
            if (hrDown) {
              ex.sendResponseHeaders(503, -1);
              ex.close();
              return;
            }
            List<Map<String, String>> items = new ArrayList<>();
            String q = ex.getRequestURI().getRawQuery();
            for (String kv : q == null ? new String[0] : q.split("&")) {
              String[] p = kv.split("=", 2);
              String id = p.length == 2 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "";
              if ("operatorIds".equals(p[0]) && id.startsWith("OP-")) {
                items.add(Map.of("operatorId", id, "name", "이름:" + id));
              }
            }
            byte[] body = JsonMapper.builder().build().writeValueAsBytes(Map.of("items", items));
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
          });
      s.start();
      return s;
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void hrBaseUrl(DynamicPropertyRegistry r) {
    r.add("pmart.employee-auth.hr-base-url", () -> "http://127.0.0.1:" + HR.getAddress().getPort());
  }

  @LocalServerPort int port;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.jdbc.core.JdbcTemplate jdbc;

  final JsonMapper mapper = JsonMapper.builder().build();

  record Res(int status, JsonNode body) {}

  RestClient client() {
    return RestClient.builder()
        .baseUrl("http://localhost:" + port + "/api/v1")
        .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
        .build();
  }

  String mint(List<String> roles) {
    return client()
        .post()
        .uri("/internal/dev-tokens")
        .headers(
            h -> {
              h.set("Idempotency-Key", UUID.randomUUID().toString());
              h.set("X-Source-System", "local-dev");
              h.set("X-Device-Id", "dev-1");
            })
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("operatorId", "OP-ROLES-" + UUID.randomUUID(), "roles", roles))
        .exchange((q, r) -> mapper.readTree(r.getBody().readAllBytes()))
        .path("tokens")
        .path("accessToken")
        .asText();
  }

  Res roles(@Nullable String token) {
    return client()
        .get()
        .uri("/employee-auth/roles")
        .headers(
            h -> {
              if (token != null) {
                h.setBearerAuth(token);
              }
            })
        .exchange(
            (q, r) -> {
              byte[] b = r.getBody().readAllBytes();
              return new Res(r.getStatusCode().value(), b.length == 0 ? null : mapper.readTree(b));
            });
  }

  @Test
  void listsConfiguredRolesInOrderWithNamesAndCenterScope() {
    Res r = roles(mint(List.of("MD"))); // HR_ADMIN 이 아닌 직원도 본다
    assertThat(r.status()).isEqualTo(200);
    JsonNode items = r.body().path("items");
    List<String> codes = new ArrayList<>();
    items.forEach(i -> codes.add(i.path("code").asText()));
    assertThat(codes)
        .containsExactly(
            "OPERATOR_ADMIN",
            "HR_ADMIN",
            "CENTER_MASTER_APPROVER",
            "CS_AGENT",
            "DISPLAY_APPROVER",
            "INVENTORY_APPROVER",
            "MD",
            "MFC_SUPERVISOR",
            "MFC_WORKER",
            "PRICE_APPROVER",
            "PRODUCT_APPROVER",
            "RIDER_OPS",
            "SERVICEABILITY_APPROVER");
    for (int n = 0; n < items.size(); n++) {
      assertThat(items.get(n).path("order").asInt()).isEqualTo(n + 1);
    }
    List<String> centerScoped = new ArrayList<>();
    items.forEach(
        i -> {
          if (i.path("centerScoped").asBoolean()) {
            centerScoped.add(i.path("code").asText());
          }
        });
    assertThat(centerScoped).containsExactly("MFC_SUPERVISOR", "MFC_WORKER");
    assertThat(items.get(0).path("name").asText()).isEqualTo("운영 관리자");
    assertThat(items.get(7).path("name").asText()).isEqualTo("센터 관리자");
    assertThat(items.get(11).path("name").asText()).isEqualTo("라이더 운영");
    assertThat(items.get(12).path("name").asText()).isEqualTo("배송 권역 승인자");
  }

  @Test
  void worksForStaffWithoutRolesAndRequiresToken() {
    assertThat(roles(mint(List.of())).status()).isEqualTo(200);
    assertThat(roles(null).status()).isEqualTo(401);
  }

  Res call(String method, String path, String token, @Nullable Object body) {
    var spec =
        client()
            .method(org.springframework.http.HttpMethod.valueOf(method))
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
        (q, r) -> {
          byte[] b = r.getBody().readAllBytes();
          return new Res(r.getStatusCode().value(), b.length == 0 ? null : mapper.readTree(b));
        });
  }

  /**
   * 직원 조회(0.7.0, BO-01 인사팀 권한 관리 메뉴): HR_ADMIN 도 읽는다(바꾸기는 OPERATOR_ADMIN 만), 지금 유효한 역할·센터·grant 목록.
   */
  @Test
  void hrAdminReadsEmployeeAccessButCannotChangeIt() {
    String admin = mint(List.of("OPERATOR_ADMIN"));
    String hr = mint(List.of("HR_ADMIN"));
    String worker = mint(List.of("MFC_WORKER"));
    String op = "OP-LOOKUP-" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> change =
        Map.of(
            "state",
            "ACTIVE",
            "roles",
            List.of("MFC_SUPERVISOR"),
            "centers",
            List.of("MFC-LOOKUP-1"),
            "reason",
            "조회 시험");
    assertThat(call("PUT", "/employee-auth/employees/" + op, admin, change).status())
        .isEqualTo(200);

    Res got = call("GET", "/employee-auth/employees/" + op, hr, null);
    assertThat(got.status()).isEqualTo(200);
    assertThat(got.body().path("effectiveRoles").get(0).asText()).isEqualTo("MFC_SUPERVISOR");
    assertThat(got.body().path("effectiveCenters").get(0).asText()).isEqualTo("MFC-LOOKUP-1");
    assertThat(got.body().path("grants").isArray()).isTrue();
    assertThat(got.body().path("grants")).isEmpty();

    assertThat(call("GET", "/employee-auth/employees/" + op, worker, null).status())
        .as("작업자는 못 본다")
        .isEqualTo(403);
    assertThat(call("PUT", "/employee-auth/employees/" + op, hr, change).status())
        .as("인사팀은 바꾸지 못한다")
        .isEqualTo(403);
  }

  void insertGrant(String grantId, String operatorId, String role) {
    insertGrant(grantId, operatorId, role, "OP-APPROVER");
  }

  void insertGrant(String grantId, String operatorId, String role, String approverId) {
    jdbc.update(
        "INSERT INTO employee_grant (grant_id, operator_id, role, centers, valid_from, valid_until,"
            + " approval_id, line_id, approver_id, reason, applied_at)"
            + " VALUES (?, ?, ?, '{}'::varchar[], now() - interval '1 day', NULL, ?, ?, ?, '시험', now())",
        grantId,
        operatorId,
        role,
        grantId.split(":")[0],
        grantId.split(":")[1],
        approverId);
  }

  static JsonNode grant(JsonNode employee, String grantId) {
    for (JsonNode g : employee.path("grants")) {
      if (grantId.equals(g.path("grantId").asText())) {
        return g;
      }
    }
    throw new AssertionError("grant 없음: " + grantId);
  }

  /**
   * 승인자·회수자 이름(0.10.0, 2026-11-17 — 인사팀 앱 권한 상세 승인 칸): hr-service 이름을 응답마다 한 번에 붙이고, 자동 승인(RULE:…)은
   * 부르지 않아 null, hr-service 를 못 읽으면 이름만 null 이고 조회는 200.
   */
  @Test
  void grantsCarryApproverAndRevokerNamesWithNullFallback() {
    String admin = mint(List.of("OPERATOR_ADMIN"));
    String hr = mint(List.of("HR_ADMIN"));
    String op = "OP-NAMES-" + UUID.randomUUID().toString().substring(0, 8);
    assertThat(
            call(
                    "PUT",
                    "/employee-auth/employees/" + op,
                    admin,
                    Map.of(
                        "state", "ACTIVE", "roles", List.of(), "centers", List.of(), "reason",
                        "이름 시험"))
                .status())
        .isEqualTo(200);
    String manual = "AP-" + op + ":1";
    String rule = "AP-" + op + ":2";
    insertGrant(manual, op, "CS_AGENT", "OP-APPROVER-1");
    insertGrant(rule, op, "MFC_WORKER", "RULE:GRANT-MFC-WORKER-OWN-CENTER@v1");

    hrDown = false;
    int before = HR_CALLS.get();
    Res got = call("GET", "/employee-auth/employees/" + op, hr, null);
    assertThat(got.status()).isEqualTo(200);
    assertThat(HR_CALLS.get() - before).as("응답마다 한 번에").isEqualTo(1);
    assertThat(grant(got.body(), manual).path("approverId").asText()).isEqualTo("OP-APPROVER-1");
    assertThat(grant(got.body(), manual).path("approverName").asText())
        .isEqualTo("이름:OP-APPROVER-1");
    assertThat(grant(got.body(), rule).hasNonNull("approverName")).as("자동 승인").isFalse();
    assertThat(grant(got.body(), manual).hasNonNull("revokedByName")).isFalse();

    Res revoked =
        call(
            "POST",
            "/employee-auth/employees/" + op + "/grant-revocations",
            hr,
            Map.of("grantId", manual, "reason", "부서 이동"));
    assertThat(revoked.status()).as(String.valueOf(revoked.body())).isEqualTo(200);
    JsonNode r = grant(revoked.body(), manual);
    assertThat(r.path("revokedBy").asText()).startsWith("OP-ROLES-");
    assertThat(r.path("revokedByName").asText()).isEqualTo("이름:" + r.path("revokedBy").asText());

    hrDown = true;
    try {
      Res down = call("GET", "/employee-auth/employees/" + op, hr, null);
      assertThat(down.status()).as("hr-service 장애에도 조회는 된다").isEqualTo(200);
      assertThat(grant(down.body(), manual).path("approverId").asText()).isEqualTo("OP-APPROVER-1");
      assertThat(grant(down.body(), manual).hasNonNull("approverName")).isFalse();
      assertThat(grant(down.body(), manual).hasNonNull("revokedByName")).isFalse();
    } finally {
      hrDown = false;
    }
  }

  /**
   * 권한 회수(0.8.0, BO-01 — 승인 없이 즉시): 인사팀은 일반 권한을 회수하고 관리자 권한(OPERATOR_ADMIN·HR_ADMIN)은 못 뺀다, 운영 관리자는
   * 뺀다, 다시 보내면 지금 상태(멱등), 남의 grant 404, 작업자 403, 회수 기록(누가·왜)이 조회에 보인다.
   */
  @Test
  void revokesGrantsImmediatelyWithAdminOnlyRolesProtected() {
    String admin = mint(List.of("OPERATOR_ADMIN"));
    String hr = mint(List.of("HR_ADMIN"));
    String worker = mint(List.of("MFC_WORKER"));
    String op = "OP-REVOKE-" + UUID.randomUUID().toString().substring(0, 8);
    assertThat(
            call(
                    "PUT",
                    "/employee-auth/employees/" + op,
                    admin,
                    Map.of(
                        "state", "ACTIVE", "roles", List.of(), "centers", List.of(), "reason",
                        "회수 시험"))
                .status())
        .isEqualTo(200);
    String cs = "AP-" + op + ":1";
    String hrRole = "AP-" + op + ":2";
    insertGrant(cs, op, "CS_AGENT");
    insertGrant(hrRole, op, "HR_ADMIN");
    String path = "/employee-auth/employees/" + op + "/grant-revocations";
    assertThat(
            call("GET", "/employee-auth/employees/" + op, hr, null)
                .body()
                .path("effectiveRoles")
                .toString())
        .contains("CS_AGENT", "HR_ADMIN");

    assertThat(call("POST", path, worker, Map.of("grantId", cs, "reason", "x")).status())
        .as("작업자")
        .isEqualTo(403);
    Res hrDenied = call("POST", path, hr, Map.of("grantId", hrRole, "reason", "이동"));
    assertThat(hrDenied.status()).as("인사팀은 관리자 권한을 못 뺀다 " + hrDenied.body()).isEqualTo(403);
    assertThat(call("POST", path, hr, Map.of("grantId", "AP-other:9", "reason", "x")).status())
        .isEqualTo(404);

    Res r = call("POST", path, hr, Map.of("grantId", cs, "reason", "부서 이동"));
    assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
    assertThat(r.body().path("effectiveRoles").toString())
        .doesNotContain("CS_AGENT")
        .contains("HR_ADMIN");
    JsonNode revoked = null;
    for (JsonNode g : r.body().path("grants")) {
      if (cs.equals(g.path("grantId").asText())) {
        revoked = g;
      }
    }
    assertThat(revoked).isNotNull();
    assertThat(revoked.path("revokedAt").isNull()).isFalse();
    assertThat(revoked.path("revokeReason").asText()).isEqualTo("부서 이동");
    assertThat(revoked.path("validUntil").asText()).isEqualTo(revoked.path("revokedAt").asText());
    assertThat(call("POST", path, hr, Map.of("grantId", cs, "reason", "부서 이동")).status())
        .as("이미 회수 — 지금 상태")
        .isEqualTo(200);

    Res a = call("POST", path, admin, Map.of("grantId", hrRole, "reason", "역할 정리"));
    assertThat(a.status()).as(String.valueOf(a.body())).isEqualTo(200);
    assertThat(a.body().path("effectiveRoles")).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregateid = ? AND type = 'SubjectBlocked'",
                Integer.class,
                op))
        .as("회수마다 이미 나간 토큰 차단")
        .isEqualTo(2);
  }
}
