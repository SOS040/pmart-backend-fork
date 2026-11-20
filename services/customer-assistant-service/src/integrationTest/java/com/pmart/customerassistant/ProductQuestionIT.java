package com.pmart.customerassistant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 상품 질문(2026-11-20 사용자 "상품검색 ES 를 RAG 에 연결", ADR-0083): search-service 상품 검색 결과를 정해진 문장으로. 결과가 없거나
 * 검색이 안 되면 안내 문서로 답한다. 검색은 토큰 없이(최근 검색어 기록에 남지 않게), 센터 없이(전체 기준).
 */
class ProductQuestionIT extends AssistantITBase {

  static String key() {
    return UUID.randomUUID().toString();
  }

  @BeforeEach
  void reset() {
    search.resetAll();
  }

  @Test
  void productQuestionIsAnsweredFromSearchWithFixedSentence() {
    search.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/search/products"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"query":"두부","total":5,"items":[
                          {"productId":"P-1","name":"국산콩 두부 300g","basePrice":2500,"saleState":"ON_SALE","carried":null},
                          {"productId":"P-2","name":"연두부","saleState":"TEMPORARILY_SOLD_OUT","carried":null}],
                         "page":{"size":3}}
                        """)));
    ingest();
    String me = "CU-" + key().substring(0, 8);
    Http r = ask(customer(me), key(), "두부 있어요?");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.text("state")).isEqualTo("PRODUCT_INFO");
    assertThat(r.text("answerMode")).isEqualTo("PRODUCT_TEMPLATE");
    assertThat(r.text("answer"))
        .contains("국산콩 두부 300g · 2,500원 · 판매 중")
        .contains("연두부 · 가격 준비 중 · 일시품절");
    assertThat(r.body().path("citations")).isEmpty();
    search.verify(
        getRequestedFor(urlPathEqualTo("/api/v1/search/products"))
            .withQueryParam("q", equalTo("두부"))
            .withQueryParam("size", equalTo("3"))
            .withQueryParam("mfcId", absent())
            .withHeader("Authorization", absent()));
  }

  @Test
  void noResultFallsBackToGuideDocuments() {
    search.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/search/products"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"query\":\"우주선\",\"total\":0,\"items\":[],\"page\":{\"size\":3}}")));
    ingest();
    Http r = ask(customer("CU-" + key().substring(0, 8)), key(), "우주선 팔아요?");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.text("state")).isNotEqualTo("PRODUCT_INFO");
  }

  @Test
  void searchFailureFallsBackToGuideDocuments() {
    search.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/search/products"))
            .willReturn(aResponse().withStatus(503)));
    ingest();
    Http r = ask(customer("CU-" + key().substring(0, 8)), key(), "두부 있어요?");
    assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
    assertThat(r.text("state")).isNotEqualTo("PRODUCT_INFO");
  }

  @Test
  void policyQuestionsDoNotCallSearch() {
    ingest();
    Http r = ask(customer("CU-" + key().substring(0, 8)), key(), "봉투값은 얼마인가요?");
    assertThat(r.status()).isEqualTo(200);
    search.verify(0, getRequestedFor(urlPathEqualTo("/api/v1/search/products")));
  }
}
