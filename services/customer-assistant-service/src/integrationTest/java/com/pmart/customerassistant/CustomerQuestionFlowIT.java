package com.pmart.customerassistant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.TestJwt;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 고객 질문 흐름(ADR-0051, 사용자 결정 2026-11-09 C2·C3·C4·C5·C6). 모델 없음(해시 임베딩 + 발췌) — 의미 검색·생성 품질은 여기서 보지
 * 않는다.
 */
class CustomerQuestionFlowIT extends AssistantITBase {

  static String key() {
    return UUID.randomUUID().toString();
  }

  void stubOrder(String orderId, String status) {
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/" + orderId))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"orderId":"%s","status":"%s","confirmedAt":"2026-11-09T06:40:00Z",
                         "arrivalWindow":{"date":"2026-11-09","start":"13:00","end":"14:00"},
                         "shippingAddress":{"line1":"비밀 주소"},
                         "lines":[{"originalQty":2},{"originalQty":1}]}
                        """
                            .formatted(orderId, status))));
  }

  void stubRecent() {
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"items":[{"orderId":"ORD-A","status":"IN_DELIVERY","orderedAt":"2026-11-09T06:40:00Z","totalAmount":23400,"itemCount":3},
                                  {"orderId":"ORD-B","status":"COMPLETED","orderedAt":"2026-11-08T06:40:00Z","totalAmount":9900,"itemCount":1}],
                         "page":{"size":2}}
                        """)));
  }

  @Test
  void answersFromCustomerDocsWithCitationsAndStoresNoQuestionText() {
    ingest();
    String me = "CU-" + key().substring(0, 8);
    Http r = ask(customer(me), key(), "봉투값은 얼마인가요?");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.text("state")).isEqualTo("ANSWERED");
    assertThat(r.text("answerMode")).isEqualTo("BASELINE_EXTRACTIVE");
    assertThat(r.text("answer")).contains("100원").doesNotContain("[확정 정책]").doesNotContain("docs/");
    var cite = r.body().path("citations").path(0);
    assertThat(cite.path("documentId").asString()).isEqualTo("docs/business/policy.md");
    assertThat(cite.path("title").asString()).isEqualTo("테스트 업무 정책");
    assertThat(cite.path("excerpt").asString()).doesNotContain("[확정 정책]");
    assertThat(r.body().path("handoff").isNull() || r.body().path("handoff").isMissingNode())
        .isTrue();
    // 다른 범위(hq-only) 문서는 검색되지 않는다
    assertThat(r.body().path("citations").findValuesAsString("documentId"))
        .doesNotContain("docs/secret/hq.md", "docs/adr/0003-category.md");
    // C5: 질문 원문은 어디에도 저장하지 않는다(해시만)
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM customer_answer WHERE answer::text LIKE '%봉투값은 얼마%'"
                    + " OR request_hash LIKE '%봉투%'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name='customer_answer'",
                String.class))
        .doesNotContain("question");
  }

  @Test
  void guestsOperatorsAndRidersAreForbidden() {
    ingest();
    String guest = TestJwt.token("GS-1", "GUEST", List.of(), List.of(), null);
    assertThat(ask(guest, key(), "봉투값은 얼마인가요?").status()).isEqualTo(403);
    assertThat(
            ask(TestJwt.operator("op-1", List.of("OPS"), List.of()), key(), "봉투값은 얼마인가요?").status())
        .isEqualTo(403);
    assertThat(ask(TestJwt.rider("RD-1"), key(), "봉투값은 얼마인가요?").status()).isEqualTo(403);
    assertThat(recentOrders(guest).status()).isEqualTo(403);
    // 출처 헤더가 고객앱이 아니면 403
    assertThat(ask(customer("CU-x"), key(), "봉투값은 얼마인가요?", null, "backoffice-web").status())
        .isEqualTo(403);
  }

  @Test
  void unrelatedQuestionIsNoGroundingWithInquiryHandoff() {
    ingest();
    Http r = ask(customer("CU-ng"), key(), "회원 등급별 생일 쿠폰은 언제 지급되나요?");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.text("state")).isEqualTo("NO_GROUNDING");
    assertThat(r.text("answer")).contains("고객센터에 문의를 남겨");
    assertThat(r.body().path("handoff").path("target").asString()).isEqualTo("INQUIRY");
    assertThat(r.body().path("handoff").path("buttonLabel").asString()).isEqualTo("고객센터에 문의 남기기");
    assertThat(r.body().path("handoff").path("message").asString()).contains("내 문의");
  }

  @Test
  void actionRequestIsOutOfScopeWithHandoff() {
    Http r = ask(customer("CU-oos"), key(), "지금 바로 환불해 주세요");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.text("state")).isEqualTo("OUT_OF_SCOPE");
    assertThat(r.text("answer")).contains("직접 처리하지 않아요");
    assertThat(r.body().path("handoff").path("target").asString()).isEqualTo("INQUIRY");
  }

  @Test
  void orderStateQuestionWithoutOrderOffersRecentOrders() {
    stubRecent();
    String tok = customer("CU-rt");
    Http r = ask(tok, key(), "주문한 거 언제 와요?");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.text("state")).isEqualTo("REALTIME_DATA_REQUIRED");
    assertThat(r.body().path("recentOrders").findValuesAsString("orderId"))
        .containsExactly("ORD-A", "ORD-B");
    assertThat(r.body().path("recentOrders").path(0).path("statusLabel").asString())
        .isEqualTo("배송 중");
    // 고객 토큰을 그대로 넘긴다
    order.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/orders"))
            .withHeader("Authorization", equalTo("Bearer " + tok)));
    // "주문 건에 대한 문의" 목록도 같은 출처
    Http list = recentOrders(tok);
    assertThat(list.status()).isEqualTo(200);
    assertThat(list.body().path("items").path(0).path("totalAmount").asInt()).isEqualTo(23400);
    // 배송 현황을 "어떻게 보는지"는 안내 질문(실시간 아님)
    ingest();
    assertThat(ask(tok, key(), "배송 현황은 어떻게 보나요?").text("state")).isIn("ANSWERED", "NO_GROUNDING");
  }

  @Test
  void chosenOrderStateIsAnsweredFromOrderServiceWithFixedSentence() {
    stubOrder("ORD-MINE", "IN_DELIVERY");
    order.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/orders/ORD-OTHER"))
            .willReturn(aResponse().withStatus(404)));
    String tok = customer("CU-os");
    Http r = ask(tok, key(), "언제 도착해요?", "ORD-MINE", "customer-app");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.text("state")).isEqualTo("ORDER_STATUS");
    assertThat(r.text("answerMode")).isEqualTo("ORDER_TEMPLATE");
    assertThat(r.text("answer"))
        .isEqualTo(
            "고르신 주문은 지금 '배송 중' 상태예요. 도착 예정 시간은 11월 9일 13:00~14:00이에요. 자세한 진행 상황은 주문 상세에서 볼 수 있어요.");
    assertThat(r.body().path("order").path("statusLabel").asString()).isEqualTo("배송 중");
    assertThat(r.body().path("order").path("itemCount").asInt()).isEqualTo(3);
    assertThat(r.body().toString()).doesNotContain("비밀 주소");
    assertThat(r.body().path("handoff").path("orderId").asString()).isEqualTo("ORD-MINE");
    // 남의·없는 주문 → 422, 기록 없음(요청 제한에도 세지 않음)
    Http other = ask(tok, key(), "언제 도착해요?", "ORD-OTHER", "customer-app");
    assertThat(other.status()).isEqualTo(422);
    assertThat(other.text("code")).isEqualTo("ORDER_NOT_FOUND");
    // 다시 볼 때 주문 상태를 새로 읽는다
    stubOrder("ORD-MINE", "COMPLETED");
    Http again = getAnswer(tok, r.text("answerId"));
    assertThat(again.body().path("order").path("statusLabel").asString()).isEqualTo("배송 완료");
  }

  @Test
  void rateLimitIsFivePerMinutePerCustomerAndReplayIsNotCounted() {
    String tok = customer("CU-rl");
    String first = key();
    for (int i = 0; i < 5; i++) {
      Http r = ask(tok, i == 0 ? first : key(), "지금 바로 환불해 주세요 " + i);
      assertThat(r.status()).as("질문 " + i).isEqualTo(200);
    }
    Http sixth = ask(tok, key(), "지금 바로 환불해 주세요 6");
    assertThat(sixth.status()).isEqualTo(429);
    assertThat(sixth.text("code")).isEqualTo("RATE_LIMITED");
    assertThat(Integer.parseInt(sixth.headers().getFirst("Retry-After"))).isBetween(1, 60);
    // 같은 키 재전송은 세지 않는다 — 같은 답
    Http replay = ask(tok, first, "지금 바로 환불해 주세요 0");
    assertThat(replay.status()).isEqualTo(200);
    // 다른 고객은 영향 없음
    assertThat(ask(customer("CU-rl2"), key(), "지금 바로 환불해 주세요").status()).isEqualTo(200);
  }

  @Test
  void dailyLimitCountsSeoulDay() {
    String me = "CU-day";
    // 오늘(서울) 앞서 연 질문 50개 — 1분 한도에 걸리지 않게 2분 전으로
    for (int i = 0; i < 50; i++) {
      jdbc.update(
          "INSERT INTO customer_answer (answer_id, source_system, asker_subject, request_id, request_hash, state,"
              + " attempt, created_at) VALUES (?, 'customer-app', ?, ?, ?, 'COMPLETED', 1, now() - interval '2 minutes')",
          "CANS-d" + i,
          me,
          "k-d" + i,
          "h".repeat(64));
    }
    java.time.Instant dayStart =
        com.pmart.customerassistant.domain.RateLimit.dayStart(java.time.Instant.now());
    org.junit.jupiter.api.Assumptions.assumeTrue(
        java.time.Instant.now().minusSeconds(120).isAfter(dayStart), "서울 자정 직후 2분은 건너뛴다");
    Http r = ask(customer(me), key(), "지금 바로 환불해 주세요");
    assertThat(r.status()).isEqualTo(429);
    assertThat(r.body().path("detail").asString() + r.body().toString()).contains("내일");
  }

  @Test
  void sameKeyReplaysAndDifferentQuestionOrOrderConflicts() {
    ingest();
    stubOrder("ORD-1", "PREPARING");
    String tok = customer("CU-idem");
    String k = key();
    Http a = ask(tok, k, "봉투값은 얼마인가요?");
    Http b = ask(tok, k, "  봉투값은   얼마인가요? ");
    assertThat(b.status()).isEqualTo(200);
    assertThat(b.text("answerId")).isEqualTo(a.text("answerId"));
    assertThat(ask(tok, k, "배달팁은 얼마인가요?").status()).isEqualTo(409);
    assertThat(ask(tok, k, "봉투값은 얼마인가요?", "ORD-1", "customer-app").status()).isEqualTo(409);
    // 응답을 잃으면 requestId 로 — 본인만
    assertThat(findByRequest(tok, k, "customer-app").text("answerId"))
        .isEqualTo(a.text("answerId"));
    assertThat(findByRequest(customer("CU-else"), k, "customer-app").status()).isEqualTo(404);
    assertThat(getAnswer(customer("CU-else"), a.text("answerId")).status()).isEqualTo(404);
  }

  @Test
  void blockedWordIs422AndNothingIsStoredOrCounted() {
    ingest();
    String me = "CU-blk";
    String tok = customer(me);
    String k = key();
    // 띄어 쓰거나 문장부호를 끼워도 잡는다
    Http r = ask(tok, k, "봉투값 시험 막기.말 얼마예요?");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(422);
    assertThat(r.text("code")).isEqualTo("BLOCKED_WORDS");
    assertThat(r.body().toString()).contains("쓸 수 없는 말").doesNotContain("시험막기말");
    // 답하지 않고 아무것도 남기지 않는다 — 요청 제한에도 세지 않는다
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM customer_answer WHERE asker_subject = ?", Integer.class, me))
        .isZero();
    for (int i = 0; i < 5; i++) {
      assertThat(ask(tok, key(), "시험막기말 " + i).status()).isEqualTo(422);
    }
    // 같은 키로 고쳐 보내면 정상 답
    Http fixed = ask(tok, k, "봉투값은 얼마인가요?");
    assertThat(fixed.status()).isEqualTo(200);
    assertThat(fixed.text("state")).isEqualTo("ANSWERED");
  }

  @Test
  void noActiveIndexIs422ThenRetrySucceeds() {
    String tok = customer("CU-noidx");
    String k = key();
    assertThat(ask(tok, k, "봉투값은 얼마인가요?").status()).isEqualTo(422);
    ingest();
    Http r = ask(tok, k, "봉투값은 얼마인가요?");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.text("state")).isEqualTo("ANSWERED");
  }

  @Test
  void dependencyFailureIs503ThenSameKeyRetrySucceeds() {
    ingest();
    String tok = customer("CU-dep");
    String k = key();
    embedding.failing.set(true);
    assertThat(ask(tok, k, "봉투값은 얼마인가요?").status()).isEqualTo(503);
    embedding.failing.set(false);
    Http r = ask(tok, k, "봉투값은 얼마인가요?");
    assertThat(r.status()).isEqualTo(200);
    assertThat(r.text("state")).isEqualTo("ANSWERED");
  }
}
