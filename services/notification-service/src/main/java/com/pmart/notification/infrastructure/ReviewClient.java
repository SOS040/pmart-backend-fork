package com.pmart.notification.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.notification.application.NotificationProperties;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * review {@code GET /internal/orders/{orderId}/review-status?accountId=}(내부 호출, review 계약 0.3.0,
 * 2026-11-18) — 리뷰 요청 알림을 보낼지. 타임아웃은 공통({@code spring.http.clients.*} — 연결 1초·응답 5초). 연결 실패·5xx 는
 * {@link Downstream} 예외(호출자가 다음 정리 때 다시), 4xx 는 그대로 예외.
 */
@Component
public class ReviewClient {

  /** 그 주문에서 리뷰를 쓴 품목이 있는가(삭제 포함), 아직 쓸 수 있는 품목 수. */
  public record ReviewStatus(boolean reviewed, int openLines) {}

  private final RestClient client;
  private final ObjectMapper mapper;
  private final boolean configured;

  public ReviewClient(
      RestClient.Builder builder, NotificationProperties props, ObjectMapper mapper) {
    String base = props.reviewBaseUrl();
    this.configured = base != null && !base.isBlank();
    this.client =
        builder.clone().baseUrl(Objects.requireNonNullElse(base, "http://unconfigured")).build();
    this.mapper = mapper;
  }

  /** review-service 주소가 설정되어 있는가(없으면 묻지 않는다). */
  public boolean configured() {
    return configured;
  }

  public ReviewStatus status(String orderId, String accountId) {
    String raw =
        Downstream.call(
            "review-service review-status",
            () ->
                client
                    .get()
                    .uri(
                        b ->
                            b.path("/api/v1/internal/orders/{id}/review-status")
                                .queryParam("accountId", "{accountId}")
                                .build(orderId, accountId))
                    .retrieve()
                    .body(String.class));
    if (raw == null) {
      throw new IllegalStateException("review-service 응답 본문 없음");
    }
    JsonNode n = mapper.readTree(raw);
    JsonNode reviewed = n.get("reviewed");
    JsonNode open = n.get("openLineCount");
    if (reviewed == null || !reviewed.isBoolean() || open == null || !open.isNumber()) {
      // 모르는 답으로 보내지 않는다 — 다음 정리 때 다시
      throw new IllegalStateException("review-service 응답에 reviewed·openLineCount 없음");
    }
    return new ReviewStatus(reviewed.asBoolean(), open.asInt());
  }
}
