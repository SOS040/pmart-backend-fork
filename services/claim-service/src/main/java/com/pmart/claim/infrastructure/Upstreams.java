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

  /** 주문 원장(심사 근거) — 줄 사진 포함(BR-92, 접수 때 클레임 품목 사본). 없으면 null. 미전달·불명은 PmartException(503 등) 그대로. */
  public @Nullable JsonNode order(String orderId) {
    return order(orderId, true);
  }

  /** {@code images} false 면 사진 없이(order 가 product-content 를 부르지 않는다 — 센터 채우기 등). */
  public @Nullable JsonNode order(String orderId, boolean images) {
    try {
      String raw =
          Downstream.call(
              ORDER,
              () ->
                  order
                      .get()
                      .uri("/api/v1/internal/orders/{id}?includeImages={i}", orderId, images)
                      .retrieve()
                      .body(String.class));
      return raw == null ? null : mapper.readTree(raw);
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    }
  }

  /** 환불 실행(전체/부분 취소). 같은 Idempotency-Key 재전송은 기존 거래 결과. */
  public Result cancelPayment(String paymentAttemptId, String requestId, Map<String, Object> body) {
    return post(
        "/api/v1/internal/payments/" + paymentAttemptId + "/cancellations", requestId, body);
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

  // ---------- 반품 회수비(CL-1, 2026-11-17) — 오배송 다시 배송비(orchestration)와 같은 payment 경로 ----------

  /** 결제 시도 생성(승인 아님). 같은 Idempotency-Key 재전송은 같은 시도. Ok.body = StartPaymentResponse. */
  public Result startPayment(String requestId, Map<String, Object> body) {
    return post("/api/v1/internal/payments", requestId, body);
  }

  /** 결제 시도 상태(payment PaymentState). 조회 실패면 null — 확인 전에는 아무것도 단정하지 않는다. */
  public @Nullable String paymentState(String paymentAttemptId) {
    try {
      String raw =
          Downstream.call(
              PAYMENT,
              () ->
                  payment
                      .get()
                      .uri("/api/v1/internal/payments/{id}", paymentAttemptId)
                      .retrieve()
                      .body(String.class));
      return raw == null ? null : mapper.readTree(raw).path("state").asString(null);
    } catch (PmartException | HttpClientErrorException e) {
      return null;
    }
  }

  /** 승인 전액 보상 취소(기한·취소 뒤 늦게 승인된 회수비). */
  public Result compensationCancel(
      String paymentAttemptId, String requestId, Map<String, Object> body) {
    return post(
        "/api/v1/internal/payments/" + paymentAttemptId + "/compensation-cancellations",
        requestId,
        body);
  }

  private Result post(String path, String requestId, Map<String, Object> body) {
    try {
      String raw =
          Downstream.call(
              PAYMENT,
              () ->
                  payment
                      .post()
                      .uri(path)
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

  private JsonNode problem(@Nullable String raw) {
    try {
      return raw == null || raw.isBlank() ? mapper.createObjectNode() : mapper.readTree(raw);
    } catch (RuntimeException e) {
      return mapper.createObjectNode();
    }
  }
}
