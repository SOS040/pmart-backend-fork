package com.pmart.order.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 고객 주문의 배송 짝(V18 order_delivery, 2026-11-19 BR-61). */
@Repository
public class DeliveryLinkStore {

  public record Link(
      String deliveryId, String orderId, String shipmentId, String type, String state) {}

  private final JdbcTemplate jdbc;

  public DeliveryLinkStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 배송 생성 — 이미 있으면 그대로(재전달). */
  public void created(
      String deliveryId, String orderId, String shipmentId, String type, Instant at) {
    jdbc.update(
        "INSERT INTO order_delivery (delivery_id, order_id, shipment_id, type, state, state_rank, created_at,"
            + " updated_at) VALUES (?,?,?,?,'READY',1,?,?) ON CONFLICT (delivery_id) DO NOTHING",
        deliveryId,
        orderId,
        shipmentId,
        type,
        Timestamp.from(at),
        Timestamp.from(at));
  }

  /** 상태를 앞으로만 옮긴다(순위가 더 높을 때만). 모르는 배송이면 0. */
  public int advance(String deliveryId, String state, int rank, Instant at) {
    return jdbc.update(
        "UPDATE order_delivery SET state = ?, state_rank = ?, updated_at = ?"
            + " WHERE delivery_id = ? AND state_rank < ?",
        state,
        rank,
        Timestamp.from(at),
        deliveryId,
        rank);
  }

  public List<Link> byOrder(String orderId) {
    return jdbc.query(
        "SELECT delivery_id, order_id, shipment_id, type, state FROM order_delivery WHERE order_id = ?"
            + " ORDER BY created_at, delivery_id",
        (rs, i) ->
            new Link(
                rs.getString("delivery_id"),
                rs.getString("order_id"),
                rs.getString("shipment_id"),
                rs.getString("type"),
                rs.getString("state")),
        orderId);
  }
}
