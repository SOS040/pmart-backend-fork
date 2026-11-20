package com.pmart.mfc.fulfillment.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.mfc.fulfillment.application.FulfillmentProperties;
import com.pmart.mfc.fulfillment.domain.TaskRules.ActiveAssignment;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * mfc-capacity 배정 조회(WK-02 정본, 시작 판정의 동기 1 hop). {@code /assignments} 는 운영자 경로라 요청한 작업자 토큰을 그대로
 * 넘긴다(같은 센터 운영자는 읽을 수 있다). 조회가 안 되면 PmartException(503) 그대로 — 시작을 기록하지 않는다.
 */
@Component
public class CapacityClient {

  public static final String CAPACITY = "mfc-capacity-service";

  private final RestClient capacity;
  private final ObjectMapper mapper;

  public CapacityClient(
      RestClient.Builder builder, FulfillmentProperties props, ObjectMapper mapper) {
    this.capacity = builder.clone().baseUrl(props.mfcCapacityBaseUrl()).build();
    this.mapper = mapper;
  }

  public @Nullable ActiveAssignment active(String mfcId, String taskId) {
    String auth = currentAuthorization();
    String raw =
        Downstream.call(
            CAPACITY,
            () ->
                capacity
                    .get()
                    .uri("/api/v1/assignments?mfcId={m}&taskId={t}", mfcId, taskId)
                    .headers(
                        h -> {
                          if (auth != null) {
                            h.set(HttpHeaders.AUTHORIZATION, auth);
                          }
                        })
                    .retrieve()
                    .body(String.class));
    JsonNode items = (raw == null ? mapper.createObjectNode() : mapper.readTree(raw)).path("items");
    for (JsonNode a : items) {
      if ("ACTIVE".equals(a.path("state").asString(""))) {
        String why = a.path("workerEligibility").asString(null);
        return new ActiveAssignment(
            a.path("workerId").asString(""),
            a.path("assignmentVersion").asInt(0),
            why == null || why.isBlank() ? null : why);
      }
    }
    return null;
  }

  /**
   * 작업 내놓기·막힘 해제 — capacity 배정 해제(내부 경로). 409 NOT_ASSIGNEE 는 그대로. releasedBy = 해제를 일으킨 운영자(capacity
   * 0.31.0 배정 이력 endedBy).
   */
  public void release(
      String taskId, String workerId, @Nullable String releasedBy, @Nullable String reason) {
    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("workerId", workerId);
    if (releasedBy != null) {
      body.put("releasedBy", releasedBy);
    }
    if (reason != null) {
      body.put("reason", reason);
    }
    Downstream.call(
        CAPACITY,
        () ->
            capacity
                .post()
                .uri("/api/v1/internal/assignments/{t}/releases", taskId)
                .header("Idempotency-Key", "REL-" + java.util.UUID.randomUUID())
                .header("X-Source-System", "mfc-fulfillment-service")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class));
  }

  /** 피킹 방문 순서(capacity 0.10.0, ADR-0050 B2). 실패하면 예외 — 호출자가 칸 코드 순으로 대신한다. */
  public record PickRoute(java.util.List<String> ordered, String method) {}

  public PickRoute pickRoute(String mfcId, java.util.List<String> codes) {
    String raw =
        Downstream.call(
            CAPACITY,
            () ->
                capacity
                    .post()
                    .uri("/api/v1/internal/pick-routes")
                    .header("Idempotency-Key", "RTE-" + java.util.UUID.randomUUID())
                    .header("X-Source-System", "mfc-fulfillment-service")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("mfcId", mfcId, "locationCodes", codes))
                    .retrieve()
                    .body(String.class));
    tools.jackson.databind.JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
    java.util.List<String> ordered = new java.util.ArrayList<>();
    n.path("ordered").forEach(x -> ordered.add(x.asString("")));
    return new PickRoute(ordered, n.path("method").asString("ROUTING"));
  }

  private static @Nullable String currentAuthorization() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      HttpServletRequest r = a.getRequest();
      return r.getHeader(HttpHeaders.AUTHORIZATION);
    }
    return null;
  }
}
