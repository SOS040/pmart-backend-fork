package com.pmart.customer.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.customer.application.CustomerProperties;
import com.pmart.customer.domain.Serviceability;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * delivery-serviceability {@code POST /serviceability-checks} 호출(좌표 판정). 회원은 본인 토큰이 전파되고 비회원은 익명(계약
 * security: []).
 *
 * <p>판정하지 못하면 예외 대신 UNKNOWN 사본을 돌려준다 — 주소 저장은 판정 성공과 별개(Commerce 5.1). 응답 시간 초과도 UNKNOWN 이며 다음 쇼핑
 * 주소 선택 때 다시 판정한다.
 */
@Component
public class ServiceabilityClient {

  private static final Logger log = LoggerFactory.getLogger(ServiceabilityClient.class);
  private static final String TARGET = "delivery-serviceability-service checks";

  private final RestClient http;
  private final Clock clock;

  public ServiceabilityClient(RestClient.Builder builder, CustomerProperties props, Clock clock) {
    this.http = builder.baseUrl(props.serviceabilityBaseUrl()).build();
    this.clock = clock;
  }

  public Serviceability check(
      String roadAddress, @Nullable Double lat, @Nullable Double lng, String purpose) {
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("roadAddress", roadAddress);
    if (lat != null && lng != null) {
      address.put("lat", lat);
      address.put("lng", lng);
    }
    try {
      JsonNode r =
          Downstream.call(
              TARGET,
              () ->
                  http.post()
                      .uri("/api/v1/serviceability-checks")
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(Map.of("address", address, "purpose", purpose))
                      .retrieve()
                      .body(JsonNode.class));
      if (r == null) {
        return Serviceability.unknown("EMPTY_RESPONSE", clock.instant());
      }
      return Serviceability.fromCheck(
          r.path("result").asString("UNKNOWN"),
          text(r, "mfcId"),
          text(r, "zoneId"),
          r.hasNonNull("mappingVersion") ? r.get("mappingVersion").asInt() : null,
          text(r, "checkId"),
          r.hasNonNull("evaluatedAt")
              ? Instant.parse(r.get("evaluatedAt").asString())
              : clock.instant());
    } catch (PmartException e) {
      log.warn("권역 판정 실패({}) — UNKNOWN 으로 저장", e.code());
      return Serviceability.unknown(e.code(), clock.instant());
    } catch (HttpClientErrorException e) {
      // 422 CONFIG_REQUIRED(발효 버전 없음) 등 상대의 명시 판정. 주소 원문은 남기지 않는다.
      String code = "HTTP_" + e.getStatusCode().value();
      try {
        JsonNode problem = e.getResponseBodyAs(JsonNode.class);
        if (problem != null && problem.hasNonNull("code")) {
          code = problem.get("code").asString();
        }
      } catch (RuntimeException ignored) {
        // 본문 해석 실패는 상태 코드로 남긴다
      }
      log.warn("권역 판정 거절({}) — UNKNOWN 으로 저장", code);
      return Serviceability.unknown(code, clock.instant());
    }
  }

  private static @Nullable String text(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asString() : null;
  }

  /** 지오코딩 좌표 [lat, lng]. */
  public record Point(double lat, double lng) {}

  /**
   * 도로명주소 → 좌표(2026-11-18, 클라이언트 요청 rider-app drop-exact-coordinates 2): delivery-serviceability
   * {@code POST /internal/address-geocodes}(토큰 없는 내부 호출 — 배차와 같은 카카오 주소 변환, ADR-0023). 결과 중 도로명주소가
   * 같은 것, 없으면 첫 번째. 실패·0건·시간 초과는 empty — 주소 저장을 막지 않는다(핀 없이 저장되는 것은 예전과 같다). 주소 원문은 로그에 남기지 않는다.
   */
  public java.util.Optional<Point> geocode(String roadAddress) {
    try {
      JsonNode r =
          Downstream.call(
              "delivery-serviceability-service geocodes",
              () ->
                  http.post()
                      .uri("/api/v1/internal/address-geocodes")
                      .header("Idempotency-Key", UUID.randomUUID().toString())
                      .header("X-Source-System", "customer-service")
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(Map.of("query", roadAddress))
                      .retrieve()
                      .body(JsonNode.class));
      if (r == null) {
        return java.util.Optional.empty();
      }
      JsonNode pick = null;
      for (JsonNode x : r.path("results")) {
        if (!x.path("lat").isNumber() || !x.path("lng").isNumber()) {
          continue;
        }
        if (pick == null) {
          pick = x;
        }
        if (roadAddress.trim().equals(x.path("roadAddress").asString("").trim())) {
          pick = x;
          break;
        }
      }
      return pick == null
          ? java.util.Optional.empty()
          : java.util.Optional.of(
              new Point(pick.get("lat").asDouble(), pick.get("lng").asDouble()));
    } catch (RuntimeException e) {
      log.warn("주소 좌표 변환 실패({}) — 좌표 없이 저장", e.getClass().getSimpleName());
      return java.util.Optional.empty();
    }
  }
}
