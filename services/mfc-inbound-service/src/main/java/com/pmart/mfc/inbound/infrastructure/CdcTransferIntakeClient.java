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

/**
 * cdc-outbound {@code POST /internal/transfer-intakes} — 승인된 센터 발주를 보급 DC 에 이관(TRF) 요청으로
 * 보낸다(ADR-0091, 사용자 2026-11-26 "자동보충 만들어"). 원래 replenishment(SCM)의 TransferApproved 소비 경로지만 그 서비스가
 * 없어 mfc-inbound 가 대신 부른다. 토큰 없음(ADR-0026). Idempotency-Key·trfId·버전이 고정이라 다시 보내도 CDC 는 한 번만 인수한다.
 *
 * <p>결과: 2xx 인수, 409(같은 버전 다른 본문·이미 출발·취소)·400·422 는 내용 거절(다시 보내지 않음), 그 밖은 다시.
 */
@Component
public class CdcTransferIntakeClient {

  /** 보낼 TRF. */
  public record Transfer(
      String trfId,
      String cdcId,
      String mfcId,
      String approvalRef,
      Instant dueAt,
      String temperatureZone,
      Map<String, Integer> lines) {}

  /** 결과. */
  public sealed interface Result {
    record Accepted() implements Result {}

    record Rejected(String reason) implements Result {}

    record Retry(String error) implements Result {}
  }

  private final RestClient http;

  public CdcTransferIntakeClient(RestClient.Builder builder, InboundProperties props) {
    this.http = builder.baseUrl(props.cdcOutboundBaseUrl()).build();
  }

  public Result send(Transfer t) {
    List<Map<String, Object>> lines = new ArrayList<>();
    t.lines()
        .forEach(
            (sku, qty) -> {
              Map<String, Object> l = new LinkedHashMap<>();
              l.put("sku", sku);
              l.put("approvedQty", qty);
              lines.add(l);
            });
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trfId", t.trfId());
    body.put("trfVersion", 1);
    body.put("cdcId", t.cdcId());
    body.put("mfcId", t.mfcId());
    body.put("approvalRef", t.approvalRef());
    // R-27(사용자 2026-11-27 PC 3 전달): 기한 = 희망 사용준비 시각(READY), TRF 하나에 온도대 하나
    body.put("deadlineMeaning", "READY");
    body.put("dueAt", t.dueAt().toString());
    body.put(
        "conditions", List.of(Map.of("kind", "TEMPERATURE_ZONE", "value", t.temperatureZone())));
    body.put("lines", lines);
    try {
      Downstream.call(
          "cdc-outbound-service transfer-intakes",
          () ->
              http.post()
                  .uri("/api/v1/internal/transfer-intakes")
                  .header("Idempotency-Key", "mfc-inbound-supply-trf:" + t.trfId())
                  .header("X-Source-System", "mfc-inbound-service")
                  .contentType(MediaType.APPLICATION_JSON)
                  .body(body)
                  .retrieve()
                  .toBodilessEntity());
      return new Result.Accepted();
    } catch (HttpClientErrorException e) {
      int status = e.getStatusCode().value();
      // 401·403·404·408·429 는 설정·배포 순서 문제일 수 있어 다시, 그 밖 4xx 는 내용 거절
      if (status != 401 && status != 403 && status != 404 && status != 408 && status != 429) {
        return new Result.Rejected("HTTP " + status + " " + e.getResponseBodyAsString());
      }
      return new Result.Retry("HTTP " + status + " " + e.getResponseBodyAsString());
    } catch (PmartException e) {
      return new Result.Retry(e.code() + ": " + e.getMessage());
    } catch (RuntimeException e) {
      return new Result.Retry(e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
