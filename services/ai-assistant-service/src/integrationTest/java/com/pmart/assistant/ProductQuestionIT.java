package com.pmart.assistant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 운영자 상품 질문(2026-11-20 사용자 "상품검색 ES 를 RAG 에 연결", "상품 정보만 답함", ADR-0083): search-service 결과를 정해진
 * 문장으로, 질문 화면의 센터가 있으면 그 센터 취급 여부. 실시간 재고 수량은 답하지 않는다. 결과가 없거나 검색이 안 되면 문서 검색으로.
 */
class ProductQuestionIT extends AssistantITBase {

  static String key() {
    return UUID.randomUUID().toString();
  }

  @BeforeEach
  void reset() {
    search.resetAll();
  }

  void stubTwoTofu() {
    search.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/search/products"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"query":"두부","total":2,"items":[
                          {"productId":"P-1","name":"국산콩 두부 300g","basePrice":2500,"saleState":"ON_SALE","carried":true},
                          {"productId":"P-2","name":"연두부","basePrice":1800,"saleState":"ON_SALE","carried":false}],
                         "page":{"size":3}}
                        """)));
  }

  @Test
  void productQuestionWithCenterContextShowsCarriedWithoutToken() {
    stubTwoTofu();
    ingest();
    Http r =
        ask(operator("OP-1"), key(), "두부 있어요?", Map.of("centerId", "MFC-01"), "backoffice-web");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.text("state")).isEqualTo("PRODUCT_INFO");
    assertThat(r.text("answerMode")).isEqualTo("PRODUCT_TEMPLATE");
    assertThat(r.text("answer"))
        .contains("국산콩 두부 300g · 기본가 2,500원 · 판매 중 · 이 센터 취급")
        .contains("연두부 · 기본가 1,800원 · 판매 중 · 이 센터 미취급")
        .contains("실시간 재고 수량과 주문 상태는 이 기능으로 확인할 수 없");
    search.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/search/products"))
            .withQueryParam("q", equalTo("두부"))
            .withQueryParam("mfcId", equalTo("MFC-01"))
            .withQueryParam("includeNotCarried", equalTo("true"))
            .withHeader("Authorization", absent()));
  }

  @Test
  void withoutCenterNoCarriedMark() {
    stubTwoTofu();
    ingest();
    Http r = ask(operator("OP-2"), key(), "두부 가격 알려줘");
    assertThat(r.text("state")).isEqualTo("PRODUCT_INFO");
    assertThat(r.text("answer")).doesNotContain("이 센터");
    search.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/search/products"))
            .withQueryParam("mfcId", absent()));
  }

  @Test
  void searchFailureFallsBackToDocuments() {
    search.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/search/products"))
            .willReturn(aResponse().withStatus(500)));
    ingest();
    Http r = ask(operator("OP-3"), key(), "두부 있어요?");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.text("state")).isNotEqualTo("PRODUCT_INFO");
  }

  @Test
  void stockCountQuestionStaysOutOfProductPath() {
    ingest();
    ask(operator("OP-4"), key(), "두부 재고 몇 개 남았어?");
    search.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/search/products")));
  }
}
