package com.pmart.order.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.order.application.OrderProperties;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * order-orchestration 결제 진입 사가(S1). 시작은 202(사가 시작 ≠ 자원·결제 성공), 진행은 사가 조회로 반영한다. {@code /internal} 은
 * 토큰 없이 내부 호출(공용 RestClient, ADR-0026).
 */
@Component
public class OrchestrationClient {

  private final RestClient client;
  private final ObjectMapper mapper;

  public OrchestrationClient(
      RestClient.Builder builder, OrderProperties props, ObjectMapper mapper) {
    this.client = builder.clone().baseUrl(props.orchestrationBaseUrl()).build();
    this.mapper = mapper;
  }

  /** sagaId. 업무 거절(RE_QUOTE_REQUIRED·SAGA_ALREADY_ACTIVE 등)은 그대로 전한다. */
  public String start(String idempotencyKey, Map<String, Object> body) {
    try {
      String raw =
          Downstream.call(
              "order-orchestration checkout-sagas",
              () ->
                  client
                      .post()
                      .uri("/api/v1/internal/checkout-sagas")
                      .header("Idempotency-Key", idempotencyKey)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      return mapper.readTree(raw).path("resultId").asString();
    } catch (HttpClientErrorException e) {
      JsonNode p =
          mapper.readTree(
              e.getResponseBodyAsString().isBlank() ? "{}" : e.getResponseBodyAsString());
      throw new PmartException(
          p.path("code").asString("CHECKOUT_REJECTED"),
          HttpStatus.valueOf(e.getStatusCode().value()),
          p.path("detail").asString("결제 진입을 시작하지 못했습니다."),
          false,
          List.of());
    }
  }

  /** 사가 사본. 조회 실패면 null(진행 상태를 모름 — 호출자는 WAIT 로 둔다). */
  public @Nullable JsonNode saga(String sagaId) {
    try {
      String raw =
          Downstream.call(
              "order-orchestration sagas",
              () ->
                  client
                      .get()
                      .uri("/api/v1/internal/sagas/{id}", sagaId)
                      .retrieve()
                      .body(String.class));
      return raw == null ? null : mapper.readTree(raw);
    } catch (PmartException | HttpClientErrorException e) {
      return null;
    }
  }
}
