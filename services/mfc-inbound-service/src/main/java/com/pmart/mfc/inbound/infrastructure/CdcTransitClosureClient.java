package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.application.InboundProperties;
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
 * cdc-inventory {@code POST /internal/transit-closures}(0.14.0) — MFC 가 CDC 이관 입고를 "더 받을 것 없음"으로 종결했다고 알린다(요청
 * MI-5). 토큰 없음(ADR-0026 — 공용 RestClient 가 내부 호출로 붙인다). Idempotency-Key 는 TRF 마다 하나라 다시 보내도 CDC 는 한 번만
 * 기록한다.
 *
 * <p>결과: 2xx 기록됨, 409 TRANSIT_CLOSURE_CONFLICT 는 다른 내용으로 이미 종결(다시 보내지 않음), 그 밖(404 DEPARTURE_NOT_POSTED — CDC 가
 * 아직 출발을 모름, 미전달·503·응답 불명)은 나중에 다시.
 */
@Component
public class CdcTransitClosureClient {

  /** 알림 결과. */
  public sealed interface Result {
    /** CDC 가 기록함(지금 미정산 수량). */
    record Recorded(@Nullable Integer unreconciledQty) implements Result {}

    /** CDC 가 내용으로 거절 — 다시 보내지 않는다. */
    record Rejected(String reason) implements Result {}

    /** 나중에 다시. */
    record Retry(String error) implements Result {}
  }

  private final RestClient http;
  private final ObjectMapper mapper;

  public CdcTransitClosureClient(
      RestClient.Builder builder, InboundProperties props, ObjectMapper mapper) {
    this.http = builder.baseUrl(props.cdcInventoryBaseUrl()).build();
    this.mapper = mapper;
  }

  public Result send(
      String trfId, String departureId, String mfcId, Instant closedAt, String sourceRef) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trfId", trfId);
    body.put("departureId", departureId);
    body.put("mfcId", mfcId);
    body.put("closedAt", closedAt.toString());
    body.put("sourceRef", sourceRef);
    try {
      String raw =
          Downstream.call(
              "cdc-inventory-service transit-closures",
              () ->
                  http.post()
                      .uri("/api/v1/internal/transit-closures")
                      .header("Idempotency-Key", "mfc-inbound-transit-closure:" + trfId)
                      .header("X-Source-System", "mfc-inbound-service")
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      JsonNode n = mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
      return new Result.Recorded(
          n.hasNonNull("unreconciledQty") ? n.get("unreconciledQty").asInt() : null);
    } catch (HttpClientErrorException e) {
      int status = e.getStatusCode().value();
      if (status == 409) {
        return new Result.Rejected("HTTP 409 " + e.getResponseBodyAsString());
      }
      return new Result.Retry("HTTP " + status + " " + e.getResponseBodyAsString());
    } catch (PmartException e) {
      return new Result.Retry(e.code() + ": " + e.getMessage());
    } catch (RuntimeException e) {
      return new Result.Retry(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
