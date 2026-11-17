package com.pmart.notification;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.notification.application.ReviewRequestSweeper;
import com.pmart.notification.infrastructure.NotificationStore;
import com.pmart.notification.infrastructure.ReviewRequestStore;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 리뷰 요청 알림(2026-11-18 사용자 결정 "A해줘"): 배송 완료가 대기 줄을 주문당 하나 만들고, delay(시험 사본 PT1H)가 지난 줄만
 * review-service 에 물어 안 썼으면 고객 알림함 REVIEW_REQUESTED 한 번, 썼으면 안 보냄, review-service 장애면 PENDING 그대로
 * 다음에, max-age(P3D) 지나면 EXPIRED. 회수 목적 배송·취소된 주문은 기록하지 않는다. 정리 작업은 직접 부른다(일정 실행은 1시간 뒤로 미룸).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.notification.order-base-url=${order.url}",
      "pmart.notification.review-base-url=${review.url}",
      "pmart.notification.review-request.sweep-initial-delay=PT1H"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({
  @ConfigureWireMock(name = "order", baseUrlProperties = "order.url"),
  @ConfigureWireMock(name = "review", baseUrlProperties = "review.url")
})
class ReviewRequestIT extends PostgresKafkaContainerSupport {

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired NotificationStore notifications;
  @Autowired ReviewRequestSweeper sweeper;
  @Autowired ReviewRequestStore store;

  @InjectWireMock("order")
  WireMockServer order;

  @InjectWireMock("review")
  WireMockServer review;

  static String id() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  void stubOrder(String orderId, String account, String status) {
    order.stubFor(
        get(urlPathEqualTo("/api/v1/internal/orders/" + orderId))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"orderId":"%1$s","accountId":"%2$s","status":"%3$s","lines":[
                          {"orderLineId":"%1$s-L1","sku":"E-00000001","productName":"사과 1kg"}]}"""
                            .formatted(orderId, account, status))));
  }

  void stubReview(String orderId, String account, boolean reviewed, int open) {
    review.stubFor(
        get(urlPathEqualTo("/api/v1/internal/orders/" + orderId + "/review-status"))
            .withQueryParam("accountId", equalTo(account))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"orderId":"%s","accountId":"%s","eligibleLineCount":1,
                         "reviewedLineCount":%d,"openLineCount":%d,"reviewed":%s}"""
                            .formatted(orderId, account, reviewed ? 1 : 0, open, reviewed))));
  }

  void stubReviewDown(String orderId) {
    review.stubFor(
        get(urlPathEqualTo("/api/v1/internal/orders/" + orderId + "/review-status"))
            .willReturn(aResponse().withStatus(503)));
  }

  void delivered(String orderId, Instant at, @Nullable String purpose) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("deliveryId", "DLV-" + orderId);
    p.put("orderId", orderId);
    p.put("deliveredAt", at.toString());
    p.put("lines", List.of(Map.of("orderLineId", orderId + "-L1", "qty", 1)));
    if (purpose != null) {
      p.put("purpose", purpose);
    }
    kafka
        .send(
            "delivery.delivery.v1",
            "DLV-" + orderId,
            EventEnvelopeFixtures.envelope("DeliveryCompleted")
                .from("test")
                .aggregate("Delivery", "DLV-" + orderId, 1)
                .payload(p)
                .buildJson())
        .join();
  }

  static <T> T await(Supplier<T> s, Predicate<T> ok) throws InterruptedException {
    Instant until = Instant.now().plus(Duration.ofSeconds(30));
    T v = s.get();
    while (!ok.test(v) && Instant.now().isBefore(until)) {
      Thread.sleep(200);
      v = s.get();
    }
    return v;
  }

  ReviewRequestStore.Row awaitRow(String orderId) throws InterruptedException {
    return await(() -> store.find(orderId), java.util.Optional::isPresent).orElseThrow();
  }

  /** 받는 고객의 알림 수(종류별). 배송 완료 알림 = DELIVERY_COMPLETED. */
  int notices(String account, String templateKey) {
    return (int)
        notifications.page("CUSTOMER", account, false, null, 0, 100).stream()
            .filter(n -> n.templateKey().equals(templateKey))
            .count();
  }

  JsonNode inbox(String account) {
    return RestClient.builder()
        .requestFactory(new JdkClientHttpRequestFactory())
        .build()
        .get()
        .uri("http://localhost:" + port + "/api/v1/me/notifications?size=20")
        .headers(h -> h.setBearerAuth(TestJwt.customer(account)))
        .exchange((req, res) -> mapper.readTree(res.getBody().readAllBytes()));
  }

  void misdeliveryConfirmed(String orderId) {
    kafka
        .send(
            "platform.case.v1",
            "CASE-" + orderId,
            EventEnvelopeFixtures.envelope("MisdeliveryConfirmed")
                .from("test")
                .aggregate("Case", "CASE-" + orderId, 1)
                .payload(
                    Map.of(
                        "caseId",
                        "CASE-" + orderId,
                        "orderId",
                        orderId,
                        "deliveryId",
                        "DLV-" + orderId,
                        "fault",
                        "RIDER",
                        "customerChoice",
                        "RESHIP"))
                .buildJson())
        .join();
  }

  /**
   * 오배송 확인(2026-11-18 사용자 "추천대로"): 대기 중 리뷰 요청은 보류(ON_HOLD — 기다림이 지나도 안 보냄), 다시 배송 완료가 오면 그 시각부터 다시
   * 센다(PENDING·완료 시각 바뀜) → 지나면 보냄.
   */
  @Test
  void misdeliveryHoldsReviewRequestUntilRedeliveryCompletes() throws Exception {
    String me = "ACC-rr-" + id();
    String orderId = "ORD-RR-" + id();
    stubOrder(orderId, me, "COMPLETED");
    stubReview(orderId, me, false, 1);
    delivered(orderId, Instant.now().minus(Duration.ofHours(2)), null);
    assertThat(awaitRow(orderId).state()).isEqualTo("PENDING");

    misdeliveryConfirmed(orderId);
    assertThat(await(() -> store.find(orderId).orElseThrow().state(), st -> st.equals("ON_HOLD")))
        .isEqualTo("ON_HOLD");
    sweeper.sweep();
    assertThat(notices(me, "REVIEW_REQUESTED")).as("보류 중엔 안 보냄").isZero();

    // 다시 배송 완료(방금) — 다시 세기 시작, 아직 1시간 안 지남
    Instant redelivered = Instant.now().minus(Duration.ofMinutes(5));
    delivered(orderId, redelivered, "MISDELIVERY_REDELIVERY");
    ReviewRequestStore.Row row =
        await(() -> store.find(orderId).orElseThrow(), r -> r.state().equals("PENDING"));
    assertThat(row.completedAt()).isCloseTo(redelivered, within(1, ChronoUnit.SECONDS));
    sweeper.sweep();
    assertThat(notices(me, "REVIEW_REQUESTED")).as("다시 1시간 기다림").isZero();
  }

  @Test
  void notReviewedOrderGetsOneInboxNoticeAfterDelay() throws Exception {
    String me = "ACC-rr-" + id();
    String orderId = "ORD-RR-" + id();
    stubOrder(orderId, me, "COMPLETED");
    stubReview(orderId, me, false, 1);
    delivered(orderId, Instant.now().minus(Duration.ofHours(2)), null);
    ReviewRequestStore.Row row = awaitRow(orderId);
    assertThat(row.state()).isEqualTo("PENDING");
    assertThat(row.accountId()).isEqualTo(me);

    sweeper.sweep();

    row = store.find(orderId).orElseThrow();
    assertThat(row.state()).isEqualTo("SENT");
    assertThat(row.notificationId()).startsWith("NTF-");
    assertThat(notices(me, "REVIEW_REQUESTED")).isEqualTo(1);
    JsonNode n =
        inbox(me)
            .path("items")
            .valueStream()
            .filter(x -> x.path("title").asString().equals("받은 상품은 어떠셨나요?"))
            .findFirst()
            .orElseThrow();
    assertThat(n.path("category").asString()).isEqualTo("ORDER");
    assertThat(n.path("destination").path("type").asString()).isEqualTo("ORDER");
    assertThat(n.path("destination").path("ref").asString()).isEqualTo(orderId);

    // 같은 주문의 다른 배송 완료(분할·다시 배송)는 새 줄·새 알림을 만들지 않는다
    delivered(orderId, Instant.now().minus(Duration.ofHours(2)), "MISDELIVERY_REDELIVERY");
    await(() -> notices(me, "DELIVERY_COMPLETED"), c -> c == 2);
    sweeper.sweep();
    assertThat(notices(me, "REVIEW_REQUESTED")).isEqualTo(1);
    assertThat(store.find(orderId).orElseThrow().state()).isEqualTo("SENT");
  }

  @Test
  void reviewedOrderIsSkippedAndNotDueOrderWaits() throws Exception {
    String me = "ACC-rr-" + id();
    String reviewed = "ORD-RR-" + id();
    String fresh = "ORD-RR-" + id();
    stubOrder(reviewed, me, "COMPLETED");
    stubOrder(fresh, me, "COMPLETED");
    stubReview(reviewed, me, true, 0);
    stubReview(fresh, me, false, 1);
    delivered(reviewed, Instant.now().minus(Duration.ofHours(3)), null);
    delivered(fresh, Instant.now(), null); // 1시간 안 지남
    awaitRow(reviewed);
    awaitRow(fresh);

    sweeper.sweep();

    assertThat(store.find(reviewed).orElseThrow().state()).isEqualTo("SKIPPED_REVIEWED");
    assertThat(store.find(fresh).orElseThrow().state()).isEqualTo("PENDING");
    assertThat(notices(me, "REVIEW_REQUESTED")).isZero();
  }

  @Test
  void reviewServiceDownRetriesNextSweepAndOldRowsExpire() throws Exception {
    String me = "ACC-rr-" + id();
    String orderId = "ORD-RR-" + id();
    String old = "ORD-RR-" + id();
    stubOrder(orderId, me, "COMPLETED");
    stubOrder(old, me, "COMPLETED");
    stubReviewDown(orderId);
    stubReview(old, me, false, 1);
    delivered(orderId, Instant.now().minus(Duration.ofHours(2)), null);
    delivered(old, Instant.now().minus(Duration.ofDays(4)), null); // max-age 3일 지남
    awaitRow(orderId);
    awaitRow(old);

    var r = sweeper.sweep();

    assertThat(r.failed()).isEqualTo(1);
    ReviewRequestStore.Row row = store.find(orderId).orElseThrow();
    assertThat(row.state()).isEqualTo("PENDING");
    assertThat(row.attempts()).isEqualTo(1);
    assertThat(row.lastError()).isEqualTo("DEPENDENCY_UNAVAILABLE");
    assertThat(store.find(old).orElseThrow().state()).isEqualTo("EXPIRED");

    // review-service 가 돌아오면 다음 정리 때 보낸다
    stubReview(orderId, me, false, 1);
    sweeper.sweep();
    assertThat(store.find(orderId).orElseThrow().state()).isEqualTo("SENT");
    assertThat(notices(me, "REVIEW_REQUESTED")).isEqualTo(1); // 포기한 주문은 보내지 않는다
  }

  @Test
  void returnDeliveriesAndCancelledOrdersAreNotRecorded() throws Exception {
    String me = "ACC-rr-" + id();
    String returned = "ORD-RR-" + id();
    String cancelled = "ORD-RR-" + id();
    stubOrder(returned, me, "COMPLETED");
    stubOrder(cancelled, me, "CANCELLED");
    delivered(returned, Instant.now().minus(Duration.ofHours(2)), "CLAIM_RETURN");
    delivered(cancelled, Instant.now().minus(Duration.ofHours(2)), null);
    // 배송 완료 알림은 그대로(기존 규칙) — 그 뒤에도 리뷰 요청 줄은 없다
    await(() -> notices(me, "DELIVERY_COMPLETED"), c -> c == 2);
    assertThat(store.find(returned)).isEmpty();
    assertThat(store.find(cancelled)).isEmpty();
  }
}
