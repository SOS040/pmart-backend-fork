package com.pmart.delivery.serviceability.infrastructure.kakao;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.delivery.serviceability.application.Geocoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 카카오 로컬 API — 주소 검색({@code /v2/local/search/address.json})과 좌표→주소({@code
 * /v2/local/geo/coord2address.json}).
 *
 * <ul>
 *   <li>429 → QUOTA_EXCEEDED, 401·403 → CONFIG_REQUIRED(키 거절), 400 → 0건(질의 형식), 5xx·시간 초과·연결 오류 →
 *       DEPENDENCY_UNAVAILABLE.
 *   <li>키·Authorization 헤더·주소 질의는 로그에 남기지 않는다(개인정보).
 * </ul>
 */
public class KakaoLocalClient implements Geocoder {

  private static final Logger log = LoggerFactory.getLogger(KakaoLocalClient.class);
  private static final String TARGET = "kakao-local";

  private final RestClient http;
  private final boolean configured;

  public KakaoLocalClient(KakaoProperties props) {
    this.configured = props.restApiKey() != null;
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build());
    factory.setReadTimeout(props.readTimeout());
    this.http =
        RestClient.builder()
            .baseUrl(props.baseUrl())
            .requestFactory(factory)
            .defaultHeader("Authorization", configured ? "KakaoAK " + props.restApiKey() : "")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
  }

  @Override
  public List<Place> search(String query, boolean exact) {
    JsonNode body =
        get(
            u ->
                u.path("/v2/local/search/address.json")
                    .queryParam("query", query)
                    .queryParam("analyze_type", exact ? "exact" : "similar")
                    .queryParam("size", 10)
                    .build());
    List<Place> out = new ArrayList<>();
    for (JsonNode d : body.path("documents")) {
      JsonNode road = d.path("road_address");
      JsonNode jibun = d.path("address");
      double lng = d.path("x").asDouble(Double.NaN);
      double lat = d.path("y").asDouble(Double.NaN);
      if (Double.isNaN(lat) || Double.isNaN(lng)) {
        continue;
      }
      out.add(
          new Place(
              text(road, "address_name"),
              text(jibun, "address_name"),
              text(road, "zone_no"),
              text(road, "building_name"),
              lat,
              lng));
    }
    return out;
  }

  @Override
  public List<Place> reverse(double lat, double lng) {
    JsonNode body =
        get(
            u ->
                u.path("/v2/local/geo/coord2address.json")
                    .queryParam("x", lng)
                    .queryParam("y", lat)
                    .build());
    List<Place> out = new ArrayList<>();
    for (JsonNode d : body.path("documents")) {
      JsonNode road = d.path("road_address");
      out.add(
          new Place(
              text(road, "address_name"),
              text(d.path("address"), "address_name"),
              text(road, "zone_no"),
              text(road, "building_name"),
              lat,
              lng));
    }
    return out;
  }

  private JsonNode get(Function<UriBuilder, URI> uri) {
    if (!configured) {
      throw PmartException.configRequired("pmart.serviceability.kakao.rest-api-key");
    }
    var e = call(() -> http.get().uri(uri::apply).retrieve().toEntity(JsonNode.class));
    int status = e.getStatusCode().value();
    if (status == 429) {
      log.warn("카카오 로컬 사용량 소진(429)");
      throw new PmartException(
          "QUOTA_EXCEEDED",
          HttpStatus.TOO_MANY_REQUESTS,
          "주소 검색 사용량이 소진되었습니다. 잠시 뒤(다음 날) 다시 시도하세요.",
          false,
          List.of());
    }
    if (status == 401 || status == 403) {
      log.error("카카오 로컬 키 거절({}) — REST API 키·허용 IP 설정을 확인한다", status);
      throw PmartException.configRequired("pmart.serviceability.kakao.rest-api-key");
    }
    if (status == 400) {
      log.info("카카오 로컬 400 — 0건으로 본다");
      return JsonNodeFactory.instance.objectNode();
    }
    if (status >= 500 || e.getBody() == null) {
      log.warn("카카오 로컬 응답 {} — 조회 실패", status);
      throw new PmartException(ErrorCode.DEPENDENCY_UNAVAILABLE, "주소 검색을 지금 할 수 없습니다.");
    }
    return e.getBody();
  }

  private static <T> T call(Supplier<T> s) {
    try {
      return s.get();
    } catch (ResourceAccessException ex) {
      log.warn("{} 연결·시간 초과 — 조회 실패", TARGET);
      throw new PmartException(ErrorCode.DEPENDENCY_UNAVAILABLE, "주소 검색을 지금 할 수 없습니다.");
    }
  }

  private static @Nullable String text(JsonNode n, String field) {
    JsonNode v = n.path(field);
    return v.isMissingNode() || v.isNull() || v.asString().isBlank() ? null : v.asString();
  }
}
