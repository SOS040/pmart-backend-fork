package com.pmart.mfc.fulfillment.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.mfc.fulfillment.application.FulfillmentProperties;
import com.pmart.mfc.fulfillment.domain.WorkRules.ReservationPart;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * mfc-inventory 예약 조회({@code /internal}, 서비스 간 인증 없음 — ADR-0026). 수량 정본은 inventory 다 — 여기서는 작업 접수
 * 판단에만 읽는다.
 */
@Component
public class InventoryClient {

  public static final String INVENTORY = "mfc-inventory-service";

  /** 예약 사본. 없으면(404) state null·parts 빈 목록. */
  public record Reservation(@Nullable String state, List<ReservationPart> parts) {}

  private final RestClient inventory;
  private final ObjectMapper mapper;

  public InventoryClient(
      RestClient.Builder builder, FulfillmentProperties props, ObjectMapper mapper) {
    this.inventory = builder.clone().baseUrl(props.mfcInventoryBaseUrl()).build();
    this.mapper = mapper;
  }

  /** 미전달·불명은 PmartException(503 DEPENDENCY_UNAVAILABLE 등) 그대로 — 호출자가 결정을 기록하지 않는다. */
  public Reservation reservation(String reservationId) {
    String raw;
    try {
      raw =
          Downstream.call(
              INVENTORY,
              () ->
                  inventory
                      .get()
                      .uri("/api/v1/internal/reservations/{id}", reservationId)
                      .retrieve()
                      .body(String.class));
    } catch (HttpClientErrorException.NotFound e) {
      return new Reservation(null, List.of());
    }
    JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    List<ReservationPart> parts = new ArrayList<>();
    for (JsonNode p : n.path("parts")) {
      parts.add(
          new ReservationPart(
              p.path("sku").asString(""), p.path("qty").asInt(0), p.path("state").asString("")));
    }
    return new Reservation(n.path("state").asString(null), parts);
  }
}
