package com.pmart.mfc.location.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.mfc.location.application.LocationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** mfc-inventory 조회({@code /internal}, 서비스 간 인증 없음 — ADR-0026). 재고 정본은 inventory. */
@Component
public class InventoryClient {

  public static final String INVENTORY = "mfc-inventory-service";

  private final RestClient inventory;
  private final ObjectMapper mapper;

  public InventoryClient(
      RestClient.Builder builder, LocationProperties props, ObjectMapper mapper) {
    this.inventory = builder.clone().baseUrl(props.inventoryUrl()).build();
    this.mapper = mapper;
  }

  /** 그 위치에 0 초과 재고 단위 행이 있는가(스냅샷 1줄). 조회 실패는 예외(503 등) 그대로 — 폐지하지 않는다. */
  public boolean hasStockAt(String mfcId, String locationCode) {
    String raw =
        Downstream.call(
            INVENTORY,
            () ->
                inventory
                    .get()
                    .uri(
                        "/api/v1/internal/snapshots?mfcId={m}&locationCode={l}&size=1",
                        mfcId,
                        locationCode)
                    .retrieve()
                    .body(String.class));
    JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    return n.path("items").size() > 0;
  }
}
