package com.pmart.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.auth.application.StaffBlockRelay;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Keycloak 직원 차단 중계(ADR-0054 ⑤ 결정 M): 관리 기록 → {@code SubjectBlocked}(Outbox). Keycloak 관리 API 는
 * WireMock. 비활성화·세션 로그아웃·비밀번호 재설정만 사건이 되고, 활성 사용자 수정·삭제·운영자 ID 없는 사용자는 아니다. 같은 기록을 다시 읽어도 한 번.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "pmart.auth.signing.ephemeral=true",
      "pmart.auth.issuer=http://auth-service.test",
      "pmart.messaging.outbox.delete-after-insert=false",
      "pmart.auth.staff-relay.enabled=true",
      "pmart.auth.staff-relay.client-secret=it-relay-secret",
      "pmart.auth.staff-relay.issuer=http://keycloak.test/realms/pmart-staff",
      "pmart.auth.staff-relay.initial-delay=PT1H" // 시험이 직접 poll()
    })
@EnableWireMock(
    @ConfigureWireMock(name = "keycloak", baseUrlProperties = "pmart.auth.staff-relay.base-url"))
class StaffBlockRelayIT extends PostgresKafkaContainerSupport {

  static final String REALM = "/admin/realms/pmart-staff";

  @Autowired StaffBlockRelay relay;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @InjectWireMock("keycloak")
  WireMockServer keycloak;

  @Test
  void blocksDisabledLoggedOutAndResetStaffOnce() {
    String disabled = UUID.randomUUID().toString();
    String active = UUID.randomUUID().toString();
    String loggedOut = UUID.randomUUID().toString();
    String reset = UUID.randomUUID().toString();
    String deleted = UUID.randomUUID().toString();
    String noOpId = UUID.randomUUID().toString();
    String opDisabled = "OP-" + UUID.randomUUID();
    String opLoggedOut = "OP-" + UUID.randomUUID();
    String opReset = "OP-" + UUID.randomUUID();
    long now = Instant.now().toEpochMilli();

    keycloak.stubFor(
        post(urlPathEqualTo("/realms/pmart-staff/protocol/openid-connect/token"))
            .withRequestBody(containing("grant_type=client_credentials"))
            .withRequestBody(containing("client_secret=it-relay-secret"))
            .willReturn(json("{\"access_token\":\"relay-token\",\"expires_in\":300}")));
    keycloak.stubFor(
        get(urlPathEqualTo(REALM + "/admin-events"))
            .withQueryParam("resourceTypes", equalTo("USER"))
            .withHeader("Authorization", equalTo("Bearer relay-token"))
            .willReturn(
                json(
                    "["
                        + event("e1", now - 1000, "UPDATE", "users/" + disabled)
                        + ","
                        + event("e2", now - 2000, "UPDATE", "users/" + active)
                        + ","
                        + event("e3", now - 3000, "ACTION", "users/" + loggedOut + "/logout")
                        + ","
                        + event("e4", now - 4000, "ACTION", "users/" + reset + "/reset-password")
                        + ","
                        + event("e5", now - 5000, "DELETE", "users/" + deleted)
                        + ","
                        + event("e6", now - 6000, "UPDATE", "users/" + noOpId)
                        + ","
                        + event("e7", now - 7000, "CREATE", "users/" + active)
                        + ","
                        // 하루 전보다 오래된 기록은 무시(lookback)
                        + event("e8", now - 3 * 86_400_000L, "UPDATE", "users/" + disabled)
                        + "]")));
    user(disabled, false, opDisabled);
    user(active, true, "OP-" + UUID.randomUUID());
    user(loggedOut, true, opLoggedOut);
    user(reset, true, opReset);
    user(noOpId, false, null);
    keycloak.stubFor(
        get(urlPathEqualTo(REALM + "/users/" + deleted)).willReturn(aResponse().withStatus(404)));

    assertThat(relay.poll()).isEqualTo(3);

    assertThat(reasons(opDisabled)).containsExactly("ACCOUNT_DISABLED");
    assertThat(reasons(opLoggedOut)).containsExactly("SESSIONS_REVOKED");
    assertThat(reasons(opReset)).containsExactly("PASSWORD_RESET");
    JsonNode p = payload(opDisabled);
    assertThat(p.path("issuer").asString()).isEqualTo("http://keycloak.test/realms/pmart-staff");
    assertThat(p.path("userType").asString()).isEqualTo("OPERATOR");
    assertThat(Instant.parse(p.path("blockedBefore").asString()).toEpochMilli())
        .isEqualTo(now - 1000);
    assertThat(
            jdbc.queryForObject(
                "SELECT topic FROM outbox_event WHERE type = 'SubjectBlocked' AND aggregateid = ?",
                String.class,
                opDisabled))
        .isEqualTo("platform.subject-block.v1");

    // 다시 읽어도(같은 기록) 더 내지 않는다. 토큰은 만료 전까지 다시 받지 않는다
    assertThat(relay.poll()).isZero();
    assertThat(reasons(opDisabled)).hasSize(1);
    keycloak.verify(
        1, postRequestedFor(urlPathEqualTo("/realms/pmart-staff/protocol/openid-connect/token")));
  }

  // --- 도움

  private void user(String id, boolean enabled, String operatorId) {
    String attrs =
        operatorId == null
            ? "{}"
            : "{\"pmart_operator_id\":[\"" + operatorId + "\"],\"centers\":[]}";
    keycloak.stubFor(
        get(urlPathEqualTo(REALM + "/users/" + id))
            .willReturn(
                json(
                    "{\"id\":\""
                        + id
                        + "\",\"username\":\"u-"
                        + id.substring(0, 8)
                        + "\",\"enabled\":"
                        + enabled
                        + ",\"attributes\":"
                        + attrs
                        + "}")));
  }

  private static String event(String id, long time, String op, String path) {
    return "{\"id\":\"%s\",\"time\":%d,\"realmId\":\"r\",\"operationType\":\"%s\",\"resourceType\":\"USER\",\"resourcePath\":\"%s\",\"authDetails\":{\"userId\":\"admin\"}}"
        .formatted(id + "-" + UUID.randomUUID(), time, op, path);
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
      String body) {
    return aResponse().withHeader("Content-Type", "application/json").withBody(body);
  }

  private List<String> reasons(String subject) {
    return jdbc
        .queryForList(
            "SELECT payload::text FROM outbox_event WHERE type = 'SubjectBlocked' AND aggregateid = ?",
            String.class,
            subject)
        .stream()
        .map(s -> mapper.readTree(s).path("payload").path("reason").asString())
        .toList();
  }

  private JsonNode payload(String subject) {
    return mapper
        .readTree(
            jdbc.queryForObject(
                "SELECT payload::text FROM outbox_event WHERE type = 'SubjectBlocked'"
                    + " AND aggregateid = ? LIMIT 1",
                String.class,
                subject))
        .path("payload");
  }
}
