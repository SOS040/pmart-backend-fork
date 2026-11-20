package com.pmart.order.infrastructure;

import com.pmart.common.web.Downstream;
import com.pmart.common.web.PmartException;
import com.pmart.order.application.OrderProperties;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 쿠폰 점유(ADR-0082): promotion-service {@code /internal/coupon-holds}(점유·확정·해제·복원)와 주문 쪽 사본 {@code
 * order_coupon_hold}. 점유 키는 초안 ID(같은 초안의 다시 결제는 같은 점유). 확정·해제·복원은 멱등이라 결과 불명이면 다시 부른다.
 */
@Component
public class CouponHolds {

  public record HoldRow(
      String draftId,
      String accountId,
      String couponId,
      String holdId,
      long discountAmount,
      String state) {}

  /** 확정·해제·복원 대상 한 줄 + 연결 주문(없으면 null). */
  public record Pending(
      HoldRow hold,
      @Nullable String orderId,
      @Nullable String orderStatus,
      @Nullable String sagaId,
      Instant quoteExpiresAt) {}

  private final RestClient promotion;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public CouponHolds(
      RestClient.Builder builder, OrderProperties props, JdbcTemplate jdbc, ObjectMapper mapper) {
    this.promotion = builder.clone().baseUrl(props.promotionBaseUrl()).build();
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  // ---------------------------------------------------------------- promotion

  /**
   * 점유. 쓸 수 없으면 422 COUPON_NOT_APPLICABLE(사유는 details). 미전달·503 은 DEPENDENCY_UNAVAILABLE.
   *
   * @return [holdId, discountAmount]
   */
  public JsonNode hold(String draftId, String couponId, String accountId, long itemAmount) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("orderId", draftId);
    body.put("couponId", couponId);
    body.put("accountId", accountId);
    body.put("itemAmount", itemAmount);
    try {
      return call("/api/v1/internal/coupon-holds", "hold:" + draftId, body);
    } catch (HttpClientErrorException.UnprocessableContent e) {
      JsonNode err = mapper.readTree(e.getResponseBodyAsString());
      String reason = err.path("details").path(0).path("reason").asString("UNKNOWN");
      throw new PmartException(
          "COUPON_NOT_APPLICABLE",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "이 쿠폰은 지금 쓸 수 없습니다(" + reason + "). 쿠폰을 빼고 주문서를 다시 만들어 주세요.",
          false,
          List.of(new PmartException.Detail("couponId", reason)));
    }
  }

  /** CONFIRM·RELEASE·RESTORE → promotion 의 새 상태. 상태가 어긋나 409 면 그 점유의 지금 상태를 읽는다. */
  public String transition(String holdId, String action) {
    String path =
        switch (action) {
          case "CONFIRM" -> "/confirmations";
          case "RELEASE" -> "/releases";
          case "RESTORE" -> "/restorations";
          default -> throw new IllegalArgumentException(action);
        };
    try {
      return call("/api/v1/internal/coupon-holds/" + holdId + path, action + ":" + holdId, Map.of())
          .path("state")
          .asString();
    } catch (HttpClientErrorException.Conflict e) {
      String raw =
          Downstream.call(
              "promotion-service coupon-holds",
              () ->
                  promotion
                      .get()
                      .uri("/api/v1/internal/coupon-holds/{id}", holdId)
                      .retrieve()
                      .body(String.class));
      return mapper.readTree(raw == null ? "{}" : raw).path("state").asString();
    }
  }

  private JsonNode call(String path, String key, Object body) {
    String raw =
        Downstream.call(
            "promotion-service coupon-holds",
            () ->
                promotion
                    .post()
                    .uri(path)
                    .header("Idempotency-Key", key)
                    .header("X-Source-System", "order-service")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class));
    return mapper.readTree(raw == null ? "{}" : raw);
  }

  // ---------------------------------------------------------------- 사본

  public Optional<HoldRow> find(String draftId) {
    return jdbc
        .query(
            "SELECT * FROM order_coupon_hold WHERE draft_id = ?",
            (rs, i) ->
                new HoldRow(
                    rs.getString("draft_id"),
                    rs.getString("account_id"),
                    rs.getString("coupon_id"),
                    rs.getString("hold_id"),
                    rs.getLong("discount_amount"),
                    rs.getString("state")),
            draftId)
        .stream()
        .findFirst();
  }

  public void save(HoldRow h, Instant at) {
    jdbc.update(
        "INSERT INTO order_coupon_hold (draft_id, account_id, coupon_id, hold_id, discount_amount,"
            + " state, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (draft_id) DO UPDATE"
            + " SET hold_id = EXCLUDED.hold_id, discount_amount = EXCLUDED.discount_amount,"
            + " state = EXCLUDED.state, updated_at = EXCLUDED.updated_at",
        h.draftId(),
        h.accountId(),
        h.couponId(),
        h.holdId(),
        h.discountAmount(),
        h.state(),
        Timestamp.from(at),
        Timestamp.from(at));
  }

  public void state(String draftId, String state, Instant at) {
    jdbc.update(
        "UPDATE order_coupon_hold SET state = ?, updated_at = ? WHERE draft_id = ?",
        state,
        Timestamp.from(at),
        draftId);
  }

  /** 살아 있는(HELD·CONFIRMED) 점유 + 초안의 주문·사가 + 확정 주문 상태. */
  public List<Pending> open(int limit) {
    return jdbc.query(
        "SELECT h.*, d.order_id AS d_order_id, d.saga_id, d.quote_expires_at, o.status AS o_status"
            + " FROM order_coupon_hold h JOIN order_draft d ON d.draft_id = h.draft_id"
            + " LEFT JOIN customer_order o ON o.draft_id = h.draft_id"
            + " WHERE h.state IN ('HELD', 'CONFIRMED') ORDER BY h.updated_at LIMIT ?",
        (rs, i) ->
            new Pending(
                new HoldRow(
                    rs.getString("draft_id"),
                    rs.getString("account_id"),
                    rs.getString("coupon_id"),
                    rs.getString("hold_id"),
                    rs.getLong("discount_amount"),
                    rs.getString("state")),
                rs.getString("d_order_id"),
                rs.getString("o_status"),
                rs.getString("saga_id"),
                rs.getTimestamp("quote_expires_at").toInstant()),
        limit);
  }
}
