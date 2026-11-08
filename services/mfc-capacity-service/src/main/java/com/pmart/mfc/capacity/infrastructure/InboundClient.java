package com.pmart.mfc.capacity.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.mfc.capacity.application.PlanProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** mfc-inbound 이동 지시 조회(작업자 쪽 4단계 — 다음 작업 받기에 이동·보충). 호출자(작업자) 토큰을 넘긴다. */
@Component
public class InboundClient {

  public static final String INBOUND = "mfc-inbound-service";

  /** 이동 지시 사본. taskType = REPLENISH(자동 보충) / MOVE(감독자). */
  public record MoveView(
      String moveId,
      String taskType,
      String state,
      boolean urgent,
      int pickedQty,
      @Nullable String assigneeId,
      @Nullable Instant createdAt) {}

  private final @Nullable RestClient client;
  private final ObjectMapper mapper;

  public InboundClient(RestClient.Builder builder, PlanProperties props, ObjectMapper mapper) {
    this.client =
        props.mfcInboundBaseUrl() == null || props.mfcInboundBaseUrl().isBlank()
            ? null
            : builder.clone().baseUrl(Objects.requireNonNull(props.mfcInboundBaseUrl())).build();
    this.mapper = mapper;
  }

  public boolean configured() {
    return client != null;
  }

  /** 열린 이동 지시(ACTIVE·CANCEL_PENDING). */
  public List<MoveView> openMoves(String mfcId) {
    if (client == null) {
      return List.of();
    }
    String auth = currentAuthorization();
    String raw =
        Downstream.call(
            INBOUND,
            () ->
                client
                    .get()
                    .uri("/api/v1/move-orders?mfcId={m}", mfcId)
                    .headers(
                        h -> {
                          if (auth != null) {
                            h.set(HttpHeaders.AUTHORIZATION, auth);
                          }
                        })
                    .retrieve()
                    .body(String.class));
    JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    List<MoveView> out = new ArrayList<>();
    for (JsonNode m : n.path("items")) {
      String created = m.path("createdAt").asString(null);
      String assignee = m.path("assigneeId").asString(null);
      out.add(
          new MoveView(
              m.path("moveId").asString(""),
              "REPLENISH".equals(m.path("kind").asString("MOVE")) ? "REPLENISH" : "MOVE",
              m.path("state").asString(""),
              m.path("urgent").asBoolean(false),
              m.path("pickedQty").asInt(0),
              assignee == null || assignee.isBlank() ? null : assignee,
              created == null || created.isBlank() ? null : Instant.parse(created)));
    }
    return out;
  }

  /** 이동 지시 한 건 원문 — 다음 작업 받기 상세(0.9.1). */
  public JsonNode moveOrder(String moveId) {
    if (client == null) {
      return mapper.createObjectNode();
    }
    String auth = currentAuthorization();
    String raw =
        Downstream.call(
            INBOUND,
            () ->
                client
                    .get()
                    .uri("/api/v1/move-orders/{m}", moveId)
                    .headers(
                        h -> {
                          if (auth != null) {
                            h.set(HttpHeaders.AUTHORIZATION, auth);
                          }
                        })
                    .retrieve()
                    .body(String.class));
    return raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
  }

  private static @Nullable String currentAuthorization() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
      HttpServletRequest r = a.getRequest();
      return r.getHeader(HttpHeaders.AUTHORIZATION);
    }
    return null;
  }
}
