package com.pmart.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
 * MFC 승인 번호 확인·한 번 사용(AP-1, 2026-11-17): 센터 관리자가 MFC 변경 요청을 만들고 제출 → 본사 승인자(설정 저장소 approver-roles —
 * 재고 INVENTORY_APPROVER, 운영 제한 OPERATOR_ADMIN)가 승인 → MFC 정본이 /internal/approval-ref-checks·uses 로
 * 확인하고 한 번만 쓴다. 실제 PostgreSQL·Kafka, 시험 토큰, 승인자 역할은 시험 사본 pmart-config/approval-service.yml 그대로.
 *
 * <p>속성 {@code pmart.it.context} 는 이 클래스만의 Spring 문맥을 만든다 — 속성이 GrantRequestIT 와 같으면 캐시된 문맥(앞 클래스가
 * 끝나며 멈춘 PostgreSQL 포트)을 다시 써서 연결이 거절된다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.it.context=MfcApprovalRefIT"
    })
@Import(TestJwtConfiguration.class)
class MfcApprovalRefIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  RestClient http;

  static final String SUP = TestJwt.operator("sup-1", List.of("MFC_SUPERVISOR"), List.of("MFC-01"));
  static final String SUP2 =
      TestJwt.operator("sup-2", List.of("MFC_SUPERVISOR"), List.of("MFC-01"));
  static final String HQ_INV =
      TestJwt.operator("hq-inv-1", List.of("INVENTORY_APPROVER"), List.of());
  static final String ADMIN = TestJwt.operator("admin-1", List.of("OPERATOR_ADMIN"), List.of());

  static final String STOCK = "MFC_STOCK_ADJUSTMENT";
  static final String RESTRICTION = "MFC_OPERATION_RESTRICTION";
  static final String INVENTORY = "mfc-inventory-service";
  static final String CAPACITY = "mfc-capacity-service";

  @BeforeEach
  void client() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> {})
            .build();
  }

  private record Res(int status, JsonNode body) {}

  private Res post(String path, @Nullable String token, String source, @Nullable Object body) {
    var spec =
        http.post()
            .uri(path)
            .headers(
                h -> {
                  if (token != null) {
                    h.setBearerAuth(token); // 내부 경로는 토큰 없이(ADR-0026)
                  }
                  h.set("Idempotency-Key", UUID.randomUUID().toString()); // 공통 헤더 검사(POST)
                  h.set("X-Source-System", source);
                })
            .contentType(MediaType.APPLICATION_JSON);
    if (body != null) {
      spec = spec.body(body);
    }
    var entity = spec.retrieve().toEntity(String.class);
    String json = entity.getBody();
    return new Res(
        entity.getStatusCode().value(),
        json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json));
  }

  /** 센터 관리자가 보내는 재고 승인 요청(실사 조정, 한 SKU 25개 감소 — Q-11 기준 이상). */
  private static Map<String, Object> countAdjustment(String targetRef) {
    return Map.of(
        "type",
        STOCK,
        "targetService",
        INVENTORY,
        "targetRef",
        targetRef,
        "centerScope",
        List.of("MFC-01"),
        "allowedActions",
        List.of("POST_COUNT_ADJUSTMENT"),
        "summary",
        "MFC-01 실사 조정 — 한 SKU 25개 감소(본사 재고 승인 기준 이상)",
        "lines",
        List.of(
            Map.of(
                "lineKey",
                "E-00000001",
                "requestedQty",
                25,
                "requestedAmount",
                125000,
                "attributes",
                Map.of("delta", -25, "unitPrice", 5000, "locationCode", "A-01-01"))));
  }

  private static Map<String, Object> restrictionLift(String targetRef) {
    return Map.of(
        "type",
        RESTRICTION,
        "targetService",
        CAPACITY,
        "targetRef",
        targetRef,
        "centerScope",
        List.of("MFC-01"),
        "allowedActions",
        List.of("LIFT_OPERATION_RESTRICTION"),
        "summary",
        "MFC-01 접수 중지 해제(냉장 설비 복구)");
  }

  /** 만들고 제출 → approvalId. */
  private String submitted(Map<String, Object> body) {
    Res created = post("/change-requests", SUP, "mfc-backoffice-web", body);
    assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
    Res sub =
        post(
            "/change-requests/" + created.body().get("changeRequestId").asString() + "/submissions",
            SUP,
            "mfc-backoffice-web",
            null);
    assertThat(sub.status()).isEqualTo(202);
    return sub.body().get("resultId").asString();
  }

  private Res decide(String approvalId, String token, Map<String, Object> decision) {
    return post("/approvals/" + approvalId + "/decisions", token, "backoffice-web", decision);
  }

  private static Map<String, Object> ask(
      String approvalId,
      String type,
      String service,
      String targetRef,
      @Nullable String actorId,
      @Nullable String useRef) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("approvalId", approvalId);
    m.put("type", type);
    m.put("targetService", service);
    m.put("targetRef", targetRef);
    if (actorId != null) {
      m.put("actorId", actorId);
    }
    if (useRef != null) {
      m.put("useRef", useRef);
    }
    return m;
  }

  private Res check(Map<String, Object> ask) {
    return post("/internal/approval-ref-checks", null, INVENTORY, ask);
  }

  private Res use(Map<String, Object> ask) {
    return post("/internal/approval-ref-uses", null, INVENTORY, ask);
  }

  private int uses(String approvalId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM approval_ref_use WHERE approval_id = ?",
            Integer.class,
            approvalId);
    return n == null ? 0 : n;
  }

  @Test
  void stockAdjustmentApprovalIsVerifiedAndUsedExactlyOnce() {
    String ref = "MFC-01:COUNT_ADJUSTMENT:CNT-" + UUID.randomUUID();
    String approvalId = submitted(countAdjustment(ref));

    // 승인 전: 쓸 수 없고, 사용 시도도 기록하지 않는다(승인 뒤 같은 useRef 로 다시 판정)
    String useRef = "mfc-inventory-service:COUNT_ADJUSTMENT:" + ref;
    Res pending = check(ask(approvalId, STOCK, INVENTORY, ref, "sup-1", null));
    assertThat(pending.status()).isEqualTo(200);
    assertThat(pending.body().get("usable").asBoolean()).isFalse();
    assertThat(pending.body().get("reason").asString()).isEqualTo("NOT_APPROVED");
    assertThat(pending.body().get("reviewState").asString()).isEqualTo("REVIEW_PENDING");
    Res earlyUse = use(ask(approvalId, STOCK, INVENTORY, ref, "sup-1", useRef));
    assertThat(earlyUse.body().get("reason").asString()).isEqualTo("NOT_APPROVED");
    assertThat(uses(approvalId)).isZero();

    // 결정 권한: 발의자(자기승인)·다른 센터 관리자·운영 관리자(재고 승인 역할 아님)는 못 한다
    Map<String, Object> approve = Map.of("decision", "APPROVE", "comment", "손실 확인");
    assertThat(decide(approvalId, SUP, approve).status()).isEqualTo(403);
    assertThat(decide(approvalId, SUP2, approve).status()).isEqualTo(403);
    assertThat(decide(approvalId, ADMIN, approve).status()).isEqualTo(403);
    assertThat(decide(approvalId, HQ_INV, approve).status()).isEqualTo(202);

    // 확인: 쓸 수 있음 + 승인 단위(행) — 정본이 자기 집행이 범위 안인지 본다. 값 없는 필드는 생략
    Res ok = check(ask(approvalId, STOCK, INVENTORY, ref, "sup-1", null));
    assertThat(ok.body().get("usable").asBoolean()).isTrue();
    assertThat(ok.body().has("reason")).isFalse();
    assertThat(ok.body().has("use")).isFalse();
    assertThat(ok.body().get("approverId").asString()).isEqualTo("hq-inv-1");
    assertThat(ok.body().get("requesterId").asString()).isEqualTo("sup-1");
    assertThat(ok.body().get("reviewState").asString()).isEqualTo("APPROVED");
    JsonNode line = ok.body().get("lines").get(0);
    assertThat(line.get("lineKey").asString()).isEqualTo("E-00000001");
    assertThat(line.get("requestedQty").asInt()).isEqualTo(25);
    assertThat(line.get("requestedAmount").asInt()).isEqualTo(125000);
    assertThat(line.get("attributes").get("delta").asInt()).isEqualTo(-25);

    // 어긋남: 종류·정본·대상(다른 실사·다른 센터), 승인자 본인이 집행
    assertThat(
            check(ask(approvalId, RESTRICTION, INVENTORY, ref, null, null))
                .body()
                .get("reason")
                .asString())
        .isEqualTo("TYPE_MISMATCH");
    assertThat(
            check(ask(approvalId, STOCK, "mfc-quality-service", ref, null, null))
                .body()
                .get("reason")
                .asString())
        .isEqualTo("TARGET_MISMATCH");
    assertThat(
            check(
                    ask(
                        approvalId,
                        STOCK,
                        INVENTORY,
                        "MFC-01:COUNT_ADJUSTMENT:CNT-other",
                        null,
                        null))
                .body()
                .get("reason")
                .asString())
        .isEqualTo("TARGET_MISMATCH");
    assertThat(
            check(ask(approvalId, STOCK, INVENTORY, ref, "hq-inv-1", null))
                .body()
                .get("reason")
                .asString())
        .isEqualTo("SELF_APPROVAL");
    assertThat(
            check(ask("AP-" + UUID.randomUUID(), STOCK, INVENTORY, ref, null, null))
                .body()
                .get("reason")
                .asString())
        .isEqualTo("NOT_FOUND");
    // 어긋난 사용 시도도 기록하지 않는다
    assertThat(
            use(ask(approvalId, STOCK, INVENTORY, "MFC-02:COUNT_ADJUSTMENT:CNT-x", null, useRef))
                .body()
                .get("reason")
                .asString())
        .isEqualTo("TARGET_MISMATCH");
    assertThat(uses(approvalId)).isZero();

    // useRef 없는 사용은 400
    assertThat(use(ask(approvalId, STOCK, INVENTORY, ref, "sup-1", null)).status()).isEqualTo(400);

    // 한 번 사용
    Res first = use(ask(approvalId, STOCK, INVENTORY, ref, "sup-1", useRef));
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.body().get("usable").asBoolean()).isTrue();
    JsonNode use = first.body().get("use");
    assertThat(use.get("useRef").asString()).isEqualTo(useRef);
    assertThat(use.get("usedByService").asString()).isEqualTo(INVENTORY);
    assertThat(use.get("actorId").asString()).isEqualTo("sup-1");

    // 같은 useRef = 같은 결과(재시도), 다른 useRef = ALREADY_USED
    Res again = use(ask(approvalId, STOCK, INVENTORY, ref, "sup-1", useRef));
    assertThat(again.body().get("usable").asBoolean()).isTrue();
    assertThat(again.body().get("use").get("usedAt").asString())
        .isEqualTo(use.get("usedAt").asString());
    Res other = use(ask(approvalId, STOCK, INVENTORY, ref, "sup-1", useRef + "-2"));
    assertThat(other.body().get("usable").asBoolean()).isFalse();
    assertThat(other.body().get("reason").asString()).isEqualTo("ALREADY_USED");
    assertThat(other.body().get("use").get("useRef").asString()).isEqualTo(useRef);
    assertThat(uses(approvalId)).isEqualTo(1);

    // 사용 뒤 확인: useRef 없으면 ALREADY_USED, 같은 useRef 면 쓸 수 있음
    assertThat(
            check(ask(approvalId, STOCK, INVENTORY, ref, null, null))
                .body()
                .get("reason")
                .asString())
        .isEqualTo("ALREADY_USED");
    assertThat(
            check(ask(approvalId, STOCK, INVENTORY, ref, null, useRef))
                .body()
                .get("usable")
                .asBoolean())
        .isTrue();
  }

  @Test
  void malformedMfcChangeRequestsAreRejected() {
    Map<String, Object> badFormat = new LinkedHashMap<>(countAdjustment("CNT-1"));
    assertThat(
            post("/change-requests", SUP, "mfc-backoffice-web", badFormat)
                .body()
                .get("code")
                .asString())
        .isEqualTo("VALIDATION_FAILED");

    Map<String, Object> wrongService =
        new LinkedHashMap<>(countAdjustment("MFC-01:DISPOSAL:DSP-1")); // 폐기 정본은 mfc-quality
    Res r = post("/change-requests", SUP, "mfc-backoffice-web", wrongService);
    assertThat(r.status()).isEqualTo(400);
    assertThat(r.body().get("code").asString()).isEqualTo("VALIDATION_FAILED");

    Map<String, Object> noLines =
        new LinkedHashMap<>(countAdjustment("MFC-01:COUNT_ADJUSTMENT:CNT-" + UUID.randomUUID()));
    noLines.remove("lines");
    assertThat(post("/change-requests", SUP, "mfc-backoffice-web", noLines).status())
        .isEqualTo(400);
  }

  @Test
  void restrictionLiftIsApprovedByOperatorAdminAndHonoursTheWindow() {
    // 재고 승인자는 운영 제한 해제를 결정하지 못한다 — 운영 관리자
    String ref = "MFC-01:RESTRICTION_LIFT:OPR-" + UUID.randomUUID();
    String approvalId = submitted(restrictionLift(ref));
    assertThat(decide(approvalId, HQ_INV, Map.of("decision", "APPROVE")).status()).isEqualTo(403);
    assertThat(decide(approvalId, ADMIN, Map.of("decision", "APPROVE")).status()).isEqualTo(202);
    Res used =
        post(
            "/internal/approval-ref-uses",
            null,
            CAPACITY,
            ask(approvalId, RESTRICTION, CAPACITY, ref, "sup-1", "mfc-capacity-service:" + ref));
    assertThat(used.body().get("usable").asBoolean()).isTrue();
    assertThat(used.body().get("use").get("usedByService").asString()).isEqualTo(CAPACITY);

    // 반려된 승인은 쓸 수 없다
    String rejectedRef = "MFC-01:RESTRICTION_LIFT:OPR-" + UUID.randomUUID();
    String rejected = submitted(restrictionLift(rejectedRef));
    assertThat(decide(rejected, ADMIN, Map.of("decision", "REJECT")).status()).isEqualTo(202);
    assertThat(
            check(ask(rejected, RESTRICTION, CAPACITY, rejectedRef, null, null))
                .body()
                .get("reason")
                .asString())
        .isEqualTo("NOT_APPROVED");

    // 승인자가 정한 만료·효력 시작
    String expiredRef = "MFC-01:RESTRICTION_LIFT:OPR-" + UUID.randomUUID();
    String expired = submitted(restrictionLift(expiredRef));
    assertThat(
            decide(
                    expired,
                    ADMIN,
                    Map.of(
                        "decision",
                        "APPROVE",
                        "expiresAt",
                        Instant.now().minusSeconds(60).toString()))
                .status())
        .isEqualTo(202);
    assertThat(
            use(ask(expired, RESTRICTION, CAPACITY, expiredRef, null, "u-" + expiredRef))
                .body()
                .get("reason")
                .asString())
        .isEqualTo("EXPIRED");
    assertThat(uses(expired)).isZero();

    String laterRef = "MFC-01:RESTRICTION_LIFT:OPR-" + UUID.randomUUID();
    String later = submitted(restrictionLift(laterRef));
    assertThat(
            decide(
                    later,
                    ADMIN,
                    Map.of(
                        "decision",
                        "APPROVE",
                        "effectiveFrom",
                        Instant.now().plusSeconds(3600).toString()))
                .status())
        .isEqualTo(202);
    assertThat(
            check(ask(later, RESTRICTION, CAPACITY, laterRef, null, null))
                .body()
                .get("reason")
                .asString())
        .isEqualTo("NOT_YET_EFFECTIVE");
  }

  @Test
  void internalPathsRefuseUserTokens() {
    // ADR-0026: 사용자 토큰으로 내부 경로를 부르면 403 — 센터 관리자가 직접 승인 번호를 "사용"할 수 없다
    Map<String, Object> q =
        ask("AP-x", STOCK, INVENTORY, "MFC-01:COUNT_ADJUSTMENT:CNT-1", null, "u-1");
    assertThat(post("/internal/approval-ref-checks", SUP, "mfc-backoffice-web", q).status())
        .isEqualTo(403);
    assertThat(post("/internal/approval-ref-uses", SUP, "mfc-backoffice-web", q).status())
        .isEqualTo(403);
  }
}
