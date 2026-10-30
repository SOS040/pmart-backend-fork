package com.pmart.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 승인 최소 흐름(ADR-0014): 변경 요청 생성 → 제출 → 결정 → ApprovalDecided 가 Outbox 에 스키마 검증을 통과해 기록된다. 실제
 * PostgreSQL·Kafka, 시험 토큰.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.approval.approver-roles.PRICE=PRICE_APPROVER"
    })
@Import(TestJwtConfiguration.class)
class ApprovalFlowIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  RestClient http;

  static final String MD = TestJwt.operator("md-1", List.of("MD"), List.of());
  static final String LEAD = TestJwt.operator("lead-1", List.of("PRICE_APPROVER"), List.of());

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
            .build();
  }

  private Map<String, Object> priceChange(String sku) {
    return Map.of(
        "type", "PRICE",
        "targetService", "pricing-service",
        "targetRef", sku,
        "allowedActions", List.of("APPLY_PRICE_VERSION"),
        "summary", "기본가 변경",
        "requestedEffectiveFrom", "2026-11-01T00:00:00+09:00",
        "lines",
            List.of(
                Map.of(
                    "lineKey",
                    sku,
                    "requestedAmount",
                    3500,
                    "attributes",
                    Map.of("basePrice", 3500))));
  }

  private record Res(int status, JsonNode body) {}

  private Res post(String path, String token, String key, Object body) {
    var spec =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", key);
                  h.set("X-Source-System", "backoffice-web");
                })
            .contentType(MediaType.APPLICATION_JSON);
    if (body != null) {
      spec = spec.body(body);
    }
    var entity = spec.retrieve().toEntity(String.class);
    return new Res(entity.getStatusCode().value(), mapper.readTree(entity.getBody()));
  }

  private Res get(String path, String token) {
    var entity =
        http.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().toEntity(String.class);
    return new Res(entity.getStatusCode().value(), mapper.readTree(entity.getBody()));
  }

  @Test
  void createSubmitApprovePublishesApprovalDecided() {
    String sku = "E-" + UUID.randomUUID().toString().substring(0, 8);
    String key = UUID.randomUUID().toString();

    Res created = post("/change-requests", MD, key, priceChange(sku));
    assertThat(created.status()).isEqualTo(201);
    assertThat(created.body().get("requestState").asString()).isEqualTo("DRAFT");
    String crId = created.body().get("changeRequestId").asString();

    // 같은 키·같은 본문 → 같은 결과(새로 만들지 않음)
    Res replay = post("/change-requests", MD, key, priceChange(sku));
    assertThat(replay.body().get("changeRequestId").asString()).isEqualTo(crId);
    // 같은 키·다른 본문 → 409
    assertThat(post("/change-requests", MD, key, priceChange(sku + "X")).status()).isEqualTo(409);

    Res submitted =
        post("/change-requests/" + crId + "/submissions", MD, UUID.randomUUID().toString(), null);
    assertThat(submitted.status()).isEqualTo(202);
    String approvalId = submitted.body().get("resultId").asString();

    // 같은 대상 두 번째 제출 → 이중 발급 금지
    String cr2 =
        post("/change-requests", MD, UUID.randomUUID().toString(), priceChange(sku))
            .body()
            .get("changeRequestId")
            .asString();
    Res dup =
        post("/change-requests/" + cr2 + "/submissions", MD, UUID.randomUUID().toString(), null);
    assertThat(dup.status()).isEqualTo(409);
    assertThat(dup.body().get("code").asString()).isEqualTo("DUPLICATE_APPROVAL");

    // 자기승인 금지, 권한 없는 운영자 금지
    Map<String, Object> approve = Map.of("decision", "APPROVE", "comment", "승인");
    assertThat(
            post(
                    "/approvals/" + approvalId + "/decisions",
                    MD,
                    UUID.randomUUID().toString(),
                    approve)
                .status())
        .isEqualTo(403);

    Res decided =
        post(
            "/approvals/" + approvalId + "/decisions", LEAD, UUID.randomUUID().toString(), approve);
    assertThat(decided.status()).isEqualTo(202);
    assertThat(decided.body().get("status").asString()).isEqualTo("APPLIED");

    Res approval = get("/approvals/" + approvalId, LEAD);
    assertThat(approval.body().get("reviewState").asString()).isEqualTo("APPROVED");
    assertThat(approval.body().get("executionState").asString()).isEqualTo("VALIDATING");
    assertThat(approval.body().get("decisions")).hasSize(1);
    assertThat(approval.body().get("effectiveFrom").asString()).isEqualTo("2026-10-31T15:00:00Z");

    // 결정된 승인은 다시 결정할 수 없다
    assertThat(
            post(
                    "/approvals/" + approvalId + "/decisions",
                    LEAD,
                    UUID.randomUUID().toString(),
                    approve)
                .status())
        .isEqualTo(409);

    // Outbox: ChangeRequestSubmitted·ApprovalDecided (스키마 ENFORCE 통과해야 기록됨)
    List<String> types =
        jdbc.queryForList(
            "SELECT type FROM outbox_event WHERE aggregateid IN (?, ?) ORDER BY created_at",
            String.class,
            crId,
            approvalId);
    assertThat(types).containsExactly("ChangeRequestSubmitted", "ApprovalDecided");
    JsonNode event =
        mapper.readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE aggregateid = ? AND type = 'ApprovalDecided'",
                String.class,
                approvalId));
    JsonNode p = event.get("payload");
    assertThat(p.get("decision").asString()).isEqualTo("APPROVED");
    assertThat(p.get("targetRef").get("targetType").asString()).isEqualTo("Price");
    assertThat(p.get("targetRef").get("targetId").asString()).isEqualTo(sku);
    assertThat(p.get("lines").get(0).get("attributes").get("basePrice").asInt()).isEqualTo(3500);
    assertThat(p.get("approverId").asString()).isEqualTo("lead-1");
    assertThat(
            jdbc.queryForObject(
                "SELECT topic FROM outbox_event WHERE aggregateid = ? AND type = 'ApprovalDecided'",
                String.class,
                approvalId))
        .isEqualTo("platform.approval.v1");
  }

  @Test
  void unconfiguredApprovalTypeCannotBeDecided() {
    Map<String, Object> body =
        Map.of(
            "type", "CENTER_MASTER", // 승인자 역할을 설정하지 않은 종류
            "targetService", "center-master-service",
            "targetRef", "MFC-" + UUID.randomUUID(),
            "allowedActions", List.of("APPLY_CENTER_CHANGE"),
            "summary", "센터 기준정보 변경");
    String crId =
        post("/change-requests", MD, UUID.randomUUID().toString(), body)
            .body()
            .get("changeRequestId")
            .asString();
    String approvalId =
        post("/change-requests/" + crId + "/submissions", MD, UUID.randomUUID().toString(), null)
            .body()
            .get("resultId")
            .asString();
    Res res =
        post(
            "/approvals/" + approvalId + "/decisions",
            LEAD,
            UUID.randomUUID().toString(),
            Map.of("decision", "APPROVE"));
    assertThat(res.status()).isEqualTo(422);
    assertThat(res.body().get("code").asString()).isEqualTo("CONFIG_REQUIRED");
  }

  @Test
  void nonOperatorTokensAreForbidden() {
    Res res =
        post(
            "/change-requests",
            TestJwt.customer("c-1"),
            UUID.randomUUID().toString(),
            priceChange("E-00000001"));
    assertThat(res.status()).isEqualTo(403);
  }
}
