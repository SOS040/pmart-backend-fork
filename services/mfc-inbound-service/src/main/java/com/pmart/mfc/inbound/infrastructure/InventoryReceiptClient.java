package com.pmart.mfc.inbound.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inbound.application.InboundProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * mfc-inventory ConfirmReceipt 호출(토큰 없음(ADR-0026) — 공용 RestClient 가 {@code /internal/} 에 내부 호출(토큰
 * 없음)을 붙인다). Idempotency-Key = receiptId 라서 재시도해도 inventory 는 한 번만 계상한다.
 *
 * <p>결과 해석: 2xx → 전기됨(commitId), 4xx(401·403 제외) → inventory 의 명시적 거절, 그 밖(미전달·503·응답 불명·인증 설정 문제)
 * → 같은 receiptId 로 다시 보낸다(응답 불명을 실패로 단정하지 않는다).
 */
@Component
public class InventoryReceiptClient {

  /** 전기 결과. */
  public sealed interface Result {
    record Posted(String commitId) implements Result {}

    record Rejected(String reason) implements Result {}

    record Retry(String error) implements Result {}
  }

  /** 전기할 수령. */
  public record Receipt(
      String receiptId,
      String trfId,
      String departureId,
      String mfcId,
      Instant receivedAt,
      Map<String, Integer> normalQtyBySku) {}

  private final RestClient http;
  private final ObjectMapper mapper;

  public InventoryReceiptClient(
      RestClient.Builder builder, InboundProperties props, ObjectMapper mapper) {
    this.http = builder.baseUrl(props.mfcInventoryBaseUrl()).build();
    this.mapper = mapper;
  }

  public Result post(Receipt r) {
    List<Map<String, Object>> lines = new ArrayList<>();
    r.normalQtyBySku()
        .forEach(
            (sku, qty) -> {
              Map<String, Object> l = new LinkedHashMap<>();
              l.put("sku", sku);
              l.put("normalQty", qty);
              l.put("quarantinedQty", 0);
              l.put("pendingQty", 0);
              lines.add(l);
            });
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("receiptId", r.receiptId());
    body.put("trfId", r.trfId());
    body.put("departureId", r.departureId());
    body.put("mfcId", r.mfcId());
    body.put("receivedAt", r.receivedAt().toString());
    body.put("lines", lines);
    try {
      String raw =
          Downstream.call(
              "mfc-inventory-service receipts",
              () ->
                  http.post()
                      .uri("/api/v1/internal/receipts")
                      .header("Idempotency-Key", r.receiptId())
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
      String commitId = n.path("resultId").asString("");
      if (commitId.isBlank()) {
        return new Result.Retry("inventory 응답에 commitId 가 없음");
      }
      return new Result.Posted(commitId);
    } catch (HttpClientErrorException e) {
      int status = e.getStatusCode().value();
      String code = code(e.getResponseBodyAsString());
      if (status == 401 || status == 403) {
        // 권한·설정 문제 — 내용 거절이 아니므로 설정이 고쳐지면 다시 보낸다
        return new Result.Retry("HTTP " + status + " " + code);
      }
      return new Result.Rejected("INVENTORY_REJECTED:" + code + " (HTTP " + status + ")");
    } catch (PmartException e) {
      return new Result.Retry(e.code() + ": " + e.getMessage());
    } catch (RuntimeException e) {
      return new Result.Retry(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private String code(String problem) {
    try {
      return mapper
          .readTree(problem == null || problem.isBlank() ? "{}" : problem)
          .path("code")
          .asString("UNKNOWN");
    } catch (RuntimeException e) {
      return "UNKNOWN";
    }
  }
}
