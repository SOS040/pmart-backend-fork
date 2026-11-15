package com.pmart.rider.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * audit-service 개인정보 열람 기록(BO-25, ADR-0062) {@code POST /internal/pii-access-logs} — 직원 라이더 상세의
 * 이름·전화 원문 열람(0.4.0, 2026-11-15). {@code /internal} 은 토큰 없이 내부 호출(공용 RestClient 가 X-Source-System 을
 * 붙인다, ADR-0026). order-service 의 같은 이름 클래스와 같은 방식.
 *
 * <p>미전달·503 → DEPENDENCY_UNAVAILABLE, 응답 시간 초과·그 밖 5xx → UNKNOWN_RESULT({@link Downstream}). 호출자는
 * 둘 다 원문을 주지 않는다. 본문(사유)은 로그에 남기지 않는다.
 */
@Component
public class AuditClient {

  /** 기록 결과. */
  public record Recorded(String piiAccessLogId, Instant recordedAt) {}

  private final RestClient client;
  private final ObjectMapper mapper;

  public AuditClient(
      RestClient.Builder builder,
      @Value("${pmart.rider.audit-base-url:http://audit-service:8503}") String baseUrl,
      ObjectMapper mapper) {
    this.client = builder.clone().baseUrl(baseUrl).build();
    this.mapper = mapper;
  }

  public Recorded recordPiiAccess(String requestId, Map<String, Object> body) {
    try {
      String raw =
          Downstream.call(
              "audit-service pii-access-logs",
              () ->
                  client
                      .post()
                      .uri("/api/v1/internal/pii-access-logs")
                      .header("Idempotency-Key", requestId)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body(body)
                      .retrieve()
                      .body(String.class));
      JsonNode n = mapper.readTree(raw == null ? "{}" : raw);
      String id = n.path("piiAccessLogId").asString("");
      if (id.isBlank() || !n.hasNonNull("recordedAt")) {
        // 기록됐는지 확인할 수 없다 — 원문을 주지 않는다
        throw PmartException.unknownResult("audit-service 기록 결과를 확인하지 못했습니다.");
      }
      return new Recorded(id, Instant.parse(n.get("recordedAt").asString()));
    } catch (HttpClientErrorException e) {
      String b = e.getResponseBodyAsString();
      JsonNode p = mapper.readTree(b.isBlank() ? "{}" : b);
      throw new PmartException(
          p.path("code").asString("PII_ACCESS_LOG_REJECTED"),
          HttpStatus.valueOf(e.getStatusCode().value()),
          p.path("detail").asString("열람 기록이 거절되었습니다."),
          false,
          List.of());
    }
  }
}
