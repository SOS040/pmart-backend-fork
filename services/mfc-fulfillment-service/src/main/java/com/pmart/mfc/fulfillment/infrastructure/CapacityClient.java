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

  private static @Nullable String currentAuthorization() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      HttpServletRequest r = a.getRequest();
      return r.getHeader(HttpHeaders.AUTHORIZATION);
    }
    return null;
  }
}
