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
}
