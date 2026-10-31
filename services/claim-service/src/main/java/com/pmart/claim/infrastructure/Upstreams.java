package com.pmart.claim.infrastructure;

import com.pmart.claim.application.ClaimProperties;
import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * order·payment {@code /internal} 호출(서비스 간 인증 없음 — ADR-0026). 결과는 세 가지로만 나눈다: 성공, 업무 거절(4xx),
 * 불명·미전달(같은 키 재전송·원결과 조회 대상 — 실패로 단정하지 않는다).
 */
@Component
public class Upstreams {

  public sealed interface Result permits Ok, Rejected, Unknown {}

  public record Ok(JsonNode body) implements Result {}

  public record Rejected(int status, String code, String message) implements Result {}

  public record Unknown(String code, String message) implements Result {}

  public static final String ORDER = "order-service";
  public static final String PAYMENT = "payment-service";

  private final RestClient order;
  private final RestClient payment;
  private final ObjectMapper mapper;

  public Upstreams(RestClient.Builder builder, ClaimProperties props, ObjectMapper mapper) {
    this.order = builder.clone().baseUrl(props.orderBaseUrl()).build();
    this.payment = builder.clone().baseUrl(props.paymentBaseUrl()).build();
    this.mapper = mapper;
  }

  /** 주문 원장(심사 근거). 없으면 null. 미전달·불명은 PmartException(503 등) 그대로. */
  public @Nullable JsonNode order(String orderId) {
    try {
      String raw =
          Downstream.call(
              ORDER,
              () ->
                  order
                      .get()
                      .uri("/api/v1/internal/orders/{id}", orderId)
                      .retrieve()
                      .body(String.class));
      return raw == null ? null : mapper.readTree(raw);
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    }
  }

  /** 환불 실행(전체/부분 취소). 같은 Idempotency-Key 재전송은 기존 거래 결과. */
  public Result cancelPayment(String paymentAttemptId, String requestId, Map<String, Object> body) {
    try {
      String raw =
          Downstream.call(
              PAYMENT,
              () ->
                  payment
                      .post()
                      .uri("/api/v1/internal/payments/{id}/cancellations", paymentAttemptId)
                      .header("Idempotency-Key", requestId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      return new Ok(
          raw == null || raw.isBlank() ? mapper.createObjectNode() : mapper.readTree(raw));
    } catch (HttpClientErrorException e) {
      JsonNode p = problem(e.getResponseBodyAsString());
      return new Rejected(
          e.getStatusCode().value(),
          p.path("code").asString("HTTP_" + e.getStatusCode().value()),
          p.path("detail").asString(e.getStatusText()));
    } catch (PmartException e) {
      return new Unknown(e.code(), e.getMessage());
    }
  }

  /** 취소 거래 원결과(requestId = Idempotency-Key). 없거나 조회 실패면 null. */
  public @Nullable JsonNode cancellation(String paymentAttemptId, String requestId) {
    try {
      String raw =
          Downstream.call(
              PAYMENT,
              () ->
                  payment
                      .get()
                      .uri(
                          "/api/v1/internal/payments/{id}/cancellations/{rid}",
                          paymentAttemptId,
                          requestId)
                      .retrieve()
                      .body(String.class));
      return raw == null ? null : mapper.readTree(raw);
    } catch (PmartException | HttpClientErrorException e) {
      return null;
    }
  }

  private JsonNode problem(@Nullable String raw) {
    try {
      return raw == null || raw.isBlank() ? mapper.createObjectNode() : mapper.readTree(raw);
    } catch (RuntimeException e) {
      return mapper.createObjectNode();
    }
  }
}
