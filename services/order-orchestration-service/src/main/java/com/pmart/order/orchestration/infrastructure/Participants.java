package com.pmart.order.orchestration.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.order.orchestration.application.OrchestrationProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S1 참여 정본들의 {@code /internal} 명령(서비스 토큰은 공용 RestClient 가 붙인다). 결과는 세 가지로만 나눈다: 성공(정본 결과 ID), 업무
 * 거절(4xx — 보상 대상), 불명·미전달(재전송·원결과 조회 대상 — 실패로 단정하지 않는다).
 */
@Component
public class Participants {

  public sealed interface Result permits Ok, Rejected, Unknown {}

  /** 성공. {@code expiresAt} 은 정본이 준 점유 만료(없으면 null). */
  public record Ok(String resultId, @Nullable Instant expiresAt, JsonNode body) implements Result {}

  public record Rejected(String code, String message) implements Result {}

  public record Unknown(String code, String message) implements Result {}

  public static final String INVENTORY = "mfc-inventory-service";
  public static final String CAPACITY = "mfc-capacity-service";
  public static final String SLOT = "delivery-capacity-service";
  public static final String PAYMENT = "payment-service";

  private final RestClient inventory;
  private final RestClient capacity;
  private final RestClient slot;
  private final RestClient payment;
  private final ObjectMapper mapper;

  public Participants(
      RestClient.Builder builder, OrchestrationProperties props, ObjectMapper mapper) {
    this.inventory = builder.clone().baseUrl(props.mfcInventoryBaseUrl()).build();
    this.capacity = builder.clone().baseUrl(props.mfcCapacityBaseUrl()).build();
    this.slot = builder.clone().baseUrl(props.deliveryCapacityBaseUrl()).build();
    this.payment = builder.clone().baseUrl(props.paymentBaseUrl()).build();
    this.mapper = mapper;
  }

  // ---------- 확보 ----------

  public Result reserveInventory(String requestId, Map<String, Object> body) {
    Result r =
        post(
            inventory,
            INVENTORY + " reservations",
            "/api/v1/internal/reservations",
            requestId,
            body);
    if (r instanceof Ok ok) {
      // 만료 시각은 정본 예약 조회로(명령 응답에는 없다)
      return new Ok(
          ok.resultId(),
          expiresAt(inventory, INVENTORY, "/api/v1/internal/reservations/{id}", ok.resultId()),
          ok.body());
    }
    return r;
  }

  public Result holdCapacity(String requestId, Map<String, Object> body) {
    Result r =
        post(
            capacity,
            CAPACITY + " capacity-holds",
            "/api/v1/internal/capacity-holds",
            requestId,
            body);
    if (r instanceof Ok ok) {
      return new Ok(
          ok.resultId(),
          expiresAt(capacity, CAPACITY, "/api/v1/internal/capacity-holds/{id}", ok.resultId()),
          ok.body());
    }
    return r;
  }

  /** delivery-capacity 는 명령 응답이 SlotHold 자체다(resultId 대신 holdId). */
  public Result holdSlot(String requestId, Map<String, Object> body) {
    Result r = post(slot, SLOT + " slot-holds", "/api/v1/internal/slot-holds", requestId, body);
    if (r instanceof Ok ok) {
      return new Ok(
          ok.body().path("holdId").asString(), instant(ok.body(), "expiresAt"), ok.body());
    }
    return r;
  }

  /** 결제 시작(결제 시도 생성·결제창 진입 정보). 응답 resultId 는 paymentAttemptId. */
  public Result startPayment(String requestId, Map<String, Object> body) {
    Result r = post(payment, PAYMENT + " payments", "/api/v1/internal/payments", requestId, body);
    if (r instanceof Ok ok) {
      JsonNode checkout = ok.body().path("checkout");
      return new Ok(
          ok.body().path("paymentAttemptId").asString(), instant(checkout, "expiresAt"), ok.body());
    }
    return r;
  }

  // ---------- 해제·확인 ----------

  public Result release(String resourceType, String resourceId, String requestId, String sagaId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", "SAGA_COMPENSATION");
    return switch (resourceType) {
      case "INVENTORY_RESERVATION" -> {
        body.put("sourceRef", sagaId);
        yield post(
            inventory,
            INVENTORY + " releases",
            "/api/v1/internal/reservations/" + resourceId + "/releases",
            requestId,
            body);
      }
      case "CAPACITY_HOLD" -> {
        body.put("sourceRef", sagaId);
        yield post(
            capacity,
            CAPACITY + " releases",
            "/api/v1/internal/capacity-holds/" + resourceId + "/releases",
            requestId,
            body);
      }
      case "SLOT_HOLD" -> {
        body.put("sagaId", sagaId);
        yield post(
            slot,
            SLOT + " releases",
            "/api/v1/internal/slot-holds/" + resourceId + "/releases",
            requestId,
            body);
      }
      default -> throw new IllegalArgumentException("해제할 수 없는 자원: " + resourceType);
    };
  }

  /** 원예약 조회로 해제를 확인한다. 조회 실패면 null(확인 전 — RELEASED 로 표시하지 않는다). */
  public @Nullable String resourceState(String resourceType, String resourceId) {
    try {
      JsonNode n =
          switch (resourceType) {
            case "INVENTORY_RESERVATION" ->
                query(inventory, INVENTORY, "/api/v1/internal/reservations/{id}", resourceId);
            case "CAPACITY_HOLD" ->
                query(capacity, CAPACITY, "/api/v1/internal/capacity-holds/{id}", resourceId);
            case "SLOT_HOLD" -> query(slot, SLOT, "/api/v1/internal/slot-holds/{id}", resourceId);
            default -> throw new IllegalArgumentException(resourceType);
          };
      return n.path("state").asString(null);
    } catch (PmartException | HttpClientErrorException e) {
      return null;
    }
  }

  /** 결제 시도 상태(조회 실패면 null). */
  public @Nullable String paymentState(String paymentAttemptId) {
    try {
      return query(payment, PAYMENT, "/api/v1/internal/payments/{id}", paymentAttemptId)
          .path("state")
          .asString(null);
    } catch (PmartException | HttpClientErrorException e) {
      return null;
    }
  }

  // ---------- 공통 ----------

  private Result post(RestClient client, String target, String uri, String requestId, Object body) {
    try {
      String raw =
          Downstream.call(
              target,
              () ->
                  client
                      .post()
                      .uri(uri)
                      .header("Idempotency-Key", requestId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      JsonNode n = raw == null || raw.isBlank() ? mapper.createObjectNode() : mapper.readTree(raw);
      return new Ok(n.path("resultId").asString(""), null, n);
    } catch (HttpClientErrorException e) {
      JsonNode p = problem(e.getResponseBodyAsString());
      String code = p.path("code").asString("HTTP_" + e.getStatusCode().value());
      String message = p.path("detail").asString(e.getStatusText());
      return new Rejected(code, message);
    } catch (PmartException e) {
      // Downstream: 미전달(DEPENDENCY_UNAVAILABLE)·불명(UNKNOWN_RESULT) — 같은 키 재전송으로 해소
      return new Unknown(e.code(), e.getMessage());
    }
  }

  private JsonNode query(RestClient client, String target, String uri, String id) {
    String raw =
        Downstream.call(target, () -> client.get().uri(uri, id).retrieve().body(String.class));
    return raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
  }

  /** 정본 조회 실패면 null — 호출자가 TTL 로 보수 추정한다. */
  private @Nullable Instant expiresAt(RestClient client, String target, String uri, String id) {
    try {
      return instant(query(client, target, uri, id), "expiresAt");
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

  private static @Nullable Instant instant(JsonNode n, String field) {
    String v = n.path(field).asString(null);
    return v == null || v.isBlank() ? null : Instant.parse(v);
  }
}
