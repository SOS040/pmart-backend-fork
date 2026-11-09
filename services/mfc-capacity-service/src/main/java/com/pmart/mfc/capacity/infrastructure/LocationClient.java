package com.pmart.mfc.capacity.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.application.PlanProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** mfc-location 칸 사이 거리표(0.7.x) — 피킹 방문 순서(B2). 서비스 경로(사용자 토큰을 넘기지 않음). */
@Component
public class LocationClient {

  public static final String LOCATION = "mfc-location-service distances";
  public static final String BASE_URL_KEY = "pmart.capacity.plan.mfc-location-base-url";

  /** 칸 속성(거리표의 attributes). */
  public record Attr(String zone, String temperatureZone, @Nullable String areaCode) {}

  /** 거리표: codes 순서대로 meters, 좌표 없는 칸은 missing. */
  public record Table(
      List<String> codes, double[][] meters, List<String> missing, Map<String, Attr> attrs) {}

  private final @Nullable RestClient client;
  private final ObjectMapper mapper;

  public LocationClient(RestClient.Builder builder, PlanProperties props, ObjectMapper mapper) {
    this.client =
        props.mfcLocationBaseUrl() == null || props.mfcLocationBaseUrl().isBlank()
            ? null
            : builder.clone().baseUrl(Objects.requireNonNull(props.mfcLocationBaseUrl())).build();
    this.mapper = mapper;
  }

  public Table distances(String mfcId, List<String> codes) {
    RestClient c = client;
    if (c == null) {
      throw PmartException.configRequired(BASE_URL_KEY);
    }
    String raw =
        Downstream.call(
            LOCATION,
            () ->
                c.post()
                    .uri("/api/v1/internal/location-distances")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .header("X-Source-System", "mfc-capacity-service")
                    .body(Map.of("mfcId", mfcId, "locationCodes", codes))
                    .retrieve()
                    .body(String.class));
    JsonNode n = raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
    List<String> got = new ArrayList<>();
    n.path("codes").forEach(x -> got.add(x.asString("")));
    double[][] m = new double[got.size()][got.size()];
    for (int i = 0; i < got.size(); i++) {
      for (int j = 0; j < got.size(); j++) {
        m[i][j] = n.path("meters").path(i).path(j).asDouble(0);
      }
    }
    List<String> missing = new ArrayList<>();
    n.path("missing").forEach(x -> missing.add(x.asString("")));
    Map<String, Attr> attrs = new LinkedHashMap<>();
    for (JsonNode a : n.path("attributes")) {
      String area = a.path("areaCode").asString(null);
      attrs.put(
          a.path("locationCode").asString(""),
          new Attr(
              a.path("zone").asString(""),
              a.path("temperatureZone").asString(""),
              area == null || area.isBlank() ? null : area));
    }
    return new Table(got, m, missing, attrs);
  }
}
