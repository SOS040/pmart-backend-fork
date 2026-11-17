package com.pmart.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.ObjectMapper;

/**
 * 고객 휴대폰 푸시(2026-11-17 사용자 "응 해줘"). 시험 키(그때 만든 RSA)로 OAuth·FCM(WireMock). 고객 앱은 라이더와 다른 Firebase
 * 프로젝트라 고객 키(customer-service-account-json)로, 정한 알림 종류(customer-templates)만 보낸다. 라이더 푸시는
 * RiderPushIT(PC 5).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.notification.fcm.token-uri=${fcm.url}/token",
      "pmart.notification.fcm.send-base-url=${fcm.url}",
      "pmart.notification.fcm.customer-templates[0]=ORDER_CANCELLED",
      "pmart.notification.fcm.customer-templates[1]=DELIVERY_STARTED"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "fcm", baseUrlProperties = "fcm.url")})
class CustomerPushIT extends PostgresKafkaContainerSupport {

  @DynamicPropertySource
  static void fcmKey(DynamicPropertyRegistry r) throws Exception {
    String rider = serviceAccount("pmart-test"); // 라이더 앱 프로젝트
    String customer = serviceAccount("pmart-cust-test"); // 고객 앱 프로젝트(다른 Firebase 프로젝트)
    r.add("pmart.notification.fcm.service-account-json", () -> rider);
    r.add("pmart.notification.fcm.customer-service-account-json", () -> customer);
  }

  /** 시험 서비스 계정 키(그때 만든 RSA). */
  static String serviceAccount(String projectId) throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(g.generateKeyPair().getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    Map<String, String> key = new LinkedHashMap<>();
    key.put("type", "service_account");
    key.put("project_id", projectId);
    key.put("client_email", "push@" + projectId + ".iam.gserviceaccount.com");
    key.put("private_key", pem);
    return new ObjectMapper().writeValueAsString(key);
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;

  @InjectWireMock("fcm")
  WireMockServer fcm;

  /** 고객: 정한 종류(주문 취소)만 고객 앱 프로젝트 키로 보내고, 정하지 않은 종류(주문 확정)는 알림함만. 라이더 프로젝트로는 보내지 않는다. */
  @Test
  void customerGetsOnlyChosenNoticesOnCustomerProject() throws Exception {
    fcm.resetAll();
    fcm.stubFor(
        post(urlPathEqualTo("/token"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"access_token\":\"AT-C\",\"expires_in\":3600}")));
    fcm.stubFor(
        post(urlPathMatching("/v1/projects/.*/messages:send"))
            .willReturn(aResponse().withStatus(200).withBody("{\"name\":\"m1\"}")));

    String me = "ACC-push-" + UUID.randomUUID().toString().substring(0, 8);
    assertThat(register(TestJwt.customer(me), "dev-c1", "tok-cust")).isEqualTo(200);

    String orderId = "ORD-PUSH-" + UUID.randomUUID().toString().substring(0, 8);
    publishOrder(
        "OrderConfirmed",
        orderId,
        1,
        Map.of("orderId", orderId, "accountId", me, "totalAmount", 12000));
    publishOrder(
        "OrderCancelled", orderId, 2, Map.of("orderId", orderId, "accountId", me, "reason", "X"));

    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    while (Instant.now().isBefore(until)
        && (inbox(me) < 2
            || fcm.findAll(postRequestedFor(urlPathMatching("/v1/projects/.*/messages:send")))
                .isEmpty())) {
      Thread.sleep(200);
    }
    assertThat(inbox(me)).isEqualTo(2); // 알림함에는 둘 다
    // 푸시는 취소만, 고객 앱 프로젝트로(고객 키의 접근 토큰)
    fcm.verify(
        1,
        postRequestedFor(urlPathEqualTo("/v1/projects/pmart-cust-test/messages:send"))
            .withHeader("Authorization", equalTo("Bearer AT-C"))
            .withRequestBody(matchingJsonPath("$.message.token", equalTo("tok-cust")))
            .withRequestBody(
                matchingJsonPath("$.message.data.templateKey", equalTo("ORDER_CANCELLED"))));
    fcm.verify(0, postRequestedFor(urlPathEqualTo("/v1/projects/pmart-test/messages:send")));
    fcm.verify(
        0,
        postRequestedFor(urlPathMatching("/v1/projects/.*/messages:send"))
            .withRequestBody(
                matchingJsonPath("$.message.data.templateKey", equalTo("ORDER_CONFIRMED"))));
  }

  void publishOrder(String type, String orderId, int version, Map<String, Object> payload) {
    kafka
        .send(
            "order.order.v1",
            orderId,
            EventEnvelopeFixtures.envelope(type)
                .from("test")
                .aggregate("Order", orderId, version)
                .payload(payload)
                .buildJson())
        .join();
  }

  /** 그 고객의 알림함 알림 수. */
  int inbox(String accountId) {
    String body =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .get()
            .uri("http://localhost:" + port + "/api/v1/me/notifications?size=20")
            .header("Authorization", "Bearer " + TestJwt.customer(accountId))
            .retrieve()
            .body(String.class);
    return mapper.readTree(body == null ? "{}" : body).path("items").size();
  }

  int register(String jwt, String deviceId, String token) {
    return send(
        "PUT", jwt, "/me/push-tokens/" + deviceId, Map.of("token", token, "platform", "ANDROID"));
  }

  int send(String method, String token, String path, @Nullable Object body) {
    var spec =
        RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory())
            .build()
            .method(HttpMethod.valueOf(method))
            .uri("http://localhost:" + port + "/api/v1" + path)
            .headers(
                h -> {
                  h.setBearerAuth(token);
                  h.set("Idempotency-Key", UUID.randomUUID().toString());
                  h.set("X-Source-System", "customer-app");
                });
    var e =
        body == null
            ? spec
            : spec.contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body));
    return e.exchange((req, res) -> res.getStatusCode().value());
  }
}
