package com.pmart.order.infrastructure;

import com.pmart.order.domain.OrderNumberRules;
import com.pmart.order.domain.ShippingAddress;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 확정 주문 원장(JDBC). 주문과 주문행은 주문 확정 한 트랜잭션에서 함께 쓴다. */
@Repository
public class OrderStore {

  public record OrderRow(
      String orderId,
      String accountId,
      String draftId,
      String mfcId,
      String status,
      int version,
      String deliveryMode,
      @Nullable LocalDate arrivalDate,
      @Nullable String arrivalStart,
      @Nullable String arrivalEnd,
      String outOfStockPreference,
      @Nullable String deliveryNote,
      String shoppingAddressId,
      int quoteVersion,
      int priceVersion,
      long itemAmount,
      long deliveryTipBase,
      long deliveryTip,
      String deliveryTipReason,
      long bagFee,
      long totalAmount,
      boolean firstPurchaseApplied,
      int weightG,
      int plannedShipments,
      String paymentAttemptId,
      String pgTransactionId,
      long approvedAmount,
      String reservationId,
      @Nullable String capacityHoldId,
      @Nullable String slotHoldId,
      Instant confirmedAt,
      @Nullable String orderNumber) {

    /** 저장 때 정해진 화면용 주문번호를 넣은 사본. */
    public OrderRow withOrderNumber(String number) {
      return new OrderRow(
          orderId,
          accountId,
          draftId,
          mfcId,
          status,
          version,
          deliveryMode,
          arrivalDate,
          arrivalStart,
          arrivalEnd,
          outOfStockPreference,
          deliveryNote,
          shoppingAddressId,
          quoteVersion,
          priceVersion,
          itemAmount,
          deliveryTipBase,
          deliveryTip,
          deliveryTipReason,
          bagFee,
          totalAmount,
          firstPurchaseApplied,
          weightG,
          plannedShipments,
          paymentAttemptId,
          pgTransactionId,
          approvedAmount,
          reservationId,
          capacityHoldId,
          slotHoldId,
          confirmedAt,
          number);
    }
  }

  public record LineRow(
      String orderLineId,
      String orderId,
      int lineNo,
      @Nullable String cartItemId,
      String sku,
      @Nullable String productId,
      int unitsPerSku,
      int quantity,
      long unitPrice,
      long lineAmount,
      long discountShare,
      int weightG,
      int originalQty,
      int unfulfilledQty,
      int handedOverQty,
      int cancelledBeforeShipQty,
      int deliveredQty,
      int returnedQty,
      @Nullable String productName,
      @Nullable String optionLabel,
      int reshippedQty) {}

  /** 목록 한 줄(주문 요약). first* = 대표 상품(첫 주문행, 0.12.0) — 행이 없으면 firstSku null. */
  public record Summary(
      String orderId,
      String status,
      Instant confirmedAt,
      long totalAmount,
      int itemCount,
      @Nullable String orderNumber,
      String deliveryMode,
      @Nullable String firstProductId,
      @Nullable String firstSku,
      @Nullable String firstProductName,
      @Nullable String firstOptionLabel,
      int firstQuantity,
      String mfcId) {}

  /** 목록 한 줄의 앞 주문행(BR-68, 0.17.0) — 주문 당시 사본 그대로(다른 서비스 조회 없음). */
  public record PreviewLine(
      String orderId,
      String orderLineId,
      @Nullable String productId,
      String sku,
      @Nullable String productName,
      @Nullable String optionLabel,
      int quantity,
      long lineAmount) {}

  /**
   * 고객 목록 조건(0.12.0). confirmedFrom 이상·confirmedToExclusive 미만, nameQuery 는 소문자 LIKE 패턴(이미 escape —
   * 어느 행이든 상품명 사본에 부분 일치). null 은 거르지 않음. 숨긴 주문은 늘 뺀다.
   */
  public record ListCriteria(
      @Nullable String status,
      @Nullable Instant confirmedFrom,
      @Nullable Instant confirmedToExclusive,
      @Nullable String nameLikePattern) {}

  private final JdbcTemplate jdbc;

  public OrderStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 주문 당시 배송지 사본(BR-37). 주문 확정과 같은 트랜잭션에서 쓴다. */
  public void insertShipping(String orderId, ShippingAddress s) {
    jdbc.update(
        "INSERT INTO customer_order_shipping (order_id, road_address, detail_address, postal_code,"
            + " receiver_name, receiver_phone, entrance_method, entrance_note, lat, lng, captured_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        orderId,
        s.roadAddress(),
        s.detailAddress(),
        s.postalCode(),
        s.receiverName(),
        s.receiverPhone(),
        s.entranceMethod(),
        s.entranceNote(),
        s.lat(),
        s.lng(),
        Timestamp.from(s.capturedAt()));
  }

  /** 주문 당시 배송지 사본. 2026-11-02 이전 주문은 없다. */
  public Optional<ShippingAddress> shipping(String orderId) {
    return jdbc
        .query(
            "SELECT road_address, detail_address, postal_code, receiver_name, receiver_phone,"
                + " entrance_method, entrance_note, lat, lng, captured_at"
                + " FROM customer_order_shipping WHERE order_id = ?",
            (rs, i) ->
                new ShippingAddress(
                    rs.getString("road_address"),
                    rs.getString("detail_address"),
                    rs.getString("postal_code"),
                    rs.getString("receiver_name"),
                    rs.getString("receiver_phone"),
                    rs.getString("entrance_method"),
                    rs.getString("entrance_note"),
                    rs.getObject("lat", Double.class),
                    rs.getObject("lng", Double.class),
                    rs.getTimestamp("captured_at").toInstant()),
            orderId)
        .stream()
        .findFirst();
  }

  /**
   * 주문 원장 저장. 화면용 주문번호(V14)는 여기서 뽑는다 — 고유 색인에 겹치면(ON CONFLICT DO NOTHING → 0행) 다시 뽑는다. 정해진 번호를 넣은
   * 사본을 돌려준다.
   */
  public OrderRow insert(OrderRow o, List<LineRow> lines) {
    String number = null;
    for (int attempt = 0; number == null; attempt++) {
      if (attempt >= 10) {
        throw new IllegalStateException("화면용 주문번호를 10번 뽑아도 겹칩니다: " + o.orderId());
      }
      String candidate =
          attempt == 0 && o.orderNumber() != null ? o.orderNumber() : OrderNumberRules.next();
      if (jdbc.update(
              "INSERT INTO customer_order (order_id, account_id, draft_id, mfc_id, status, version,"
                  + " delivery_mode, arrival_date, arrival_start, arrival_end, out_of_stock_preference,"
                  + " delivery_note, shopping_address_id, quote_version, price_version, item_amount,"
                  + " delivery_tip_base, delivery_tip, delivery_tip_reason, bag_fee, total_amount,"
                  + " first_purchase_applied, weight_g, planned_shipments, payment_attempt_id,"
                  + " pg_transaction_id, approved_amount, reservation_id, capacity_hold_id, slot_hold_id,"
                  + " confirmed_at, order_number) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                  + " ON CONFLICT (order_number) DO NOTHING",
              o.orderId(),
              o.accountId(),
              o.draftId(),
              o.mfcId(),
              o.status(),
              o.version(),
              o.deliveryMode(),
              o.arrivalDate() == null ? null : Date.valueOf(o.arrivalDate()),
              o.arrivalStart(),
              o.arrivalEnd(),
              o.outOfStockPreference(),
              o.deliveryNote(),
              o.shoppingAddressId(),
              o.quoteVersion(),
              o.priceVersion(),
              o.itemAmount(),
              o.deliveryTipBase(),
              o.deliveryTip(),
              o.deliveryTipReason(),
              o.bagFee(),
              o.totalAmount(),
              o.firstPurchaseApplied(),
              o.weightG(),
              o.plannedShipments(),
              o.paymentAttemptId(),
              o.pgTransactionId(),
              o.approvedAmount(),
              o.reservationId(),
              o.capacityHoldId(),
              o.slotHoldId(),
              Timestamp.from(o.confirmedAt()),
              candidate)
          == 1) {
        number = candidate;
      }
    }
    for (LineRow l : lines) {
      jdbc.update(
          "INSERT INTO customer_order_line (order_line_id, order_id, line_no, cart_item_id, sku,"
              + " product_id, units_per_sku, quantity, unit_price, line_amount, discount_share,"
              + " weight_g, original_qty, unfulfilled_qty, handed_over_qty,"
              + " cancelled_before_ship_qty, delivered_qty, returned_qty, product_name, option_label)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          l.orderLineId(),
          l.orderId(),
          l.lineNo(),
          l.cartItemId(),
          l.sku(),
          l.productId(),
          l.unitsPerSku(),
          l.quantity(),
          l.unitPrice(),
          l.lineAmount(),
          l.discountShare(),
          l.weightG(),
          l.originalQty(),
          l.unfulfilledQty(),
          l.handedOverQty(),
          l.cancelledBeforeShipQty(),
          l.deliveredQty(),
          l.returnedQty(),
          l.productName(),
          l.optionLabel());
    }
    return o.withOrderNumber(number);
  }

  public Optional<OrderRow> order(String orderId) {
    return jdbc
        .query("SELECT * FROM customer_order WHERE order_id = ?", (rs, i) -> row(rs), orderId)
        .stream()
        .findFirst();
  }

  /** 수량 변경 전 주문 행 잠금(같은 주문의 변경을 줄 세운다). */
  /** S4: 도착 시간대 바꾸기(버전 올림). */
  public void updateArrival(
      String orderId, java.time.LocalDate date, String start, String end, int version) {
    jdbc.update(
        "UPDATE customer_order SET arrival_date = ?, arrival_start = ?, arrival_end = ?, version = ?"
            + " WHERE order_id = ?",
        java.sql.Date.valueOf(date),
        start,
        end,
        version,
        orderId);
  }

  public Optional<OrderRow> lock(String orderId) {
    return jdbc
        .query(
            "SELECT * FROM customer_order WHERE order_id = ? FOR UPDATE",
            (rs, i) -> row(rs),
            orderId)
        .stream()
        .findFirst();
  }

  /** 주문행 수량 누계 갱신(불변식은 호출자가 확인 — 원수량 = 미이행 + 인계 + 출고 전 취소). */
  public void updateLineQuantities(
      String orderLineId, int unfulfilledQty, int cancelledBeforeShipQty) {
    jdbc.update(
        "UPDATE customer_order_line SET unfulfilled_qty = ?, cancelled_before_ship_qty = ?"
            + " WHERE order_line_id = ?",
        unfulfilledQty,
        cancelledBeforeShipQty,
        orderLineId);
  }

  /** 이행 진행(인계·전달, O-1) 반영 — 미이행·인계·전달 누계. */
  /** 오배송 재발송(RESHIPMENT) — 미이행·재발송 누계. */
  public void updateLineReshipped(String orderLineId, int unfulfilledQty, int reshippedQty) {
    jdbc.update(
        "UPDATE customer_order_line SET unfulfilled_qty = ?, reshipped_qty = ? WHERE order_line_id = ?",
        unfulfilledQty,
        reshippedQty,
        orderLineId);
  }

  public void updateLineProgress(
      String orderLineId, int unfulfilledQty, int handedOverQty, int deliveredQty) {
    jdbc.update(
        "UPDATE customer_order_line SET unfulfilled_qty = ?, handed_over_qty = ?, delivered_qty = ?"
            + " WHERE order_line_id = ?",
        unfulfilledQty,
        handedOverQty,
        deliveredQty,
        orderLineId);
  }

  /** 반품 수량 누계(O-7, 2026-11-17). */
  public void updateLineReturned(String orderLineId, int returnedQty) {
    jdbc.update(
        "UPDATE customer_order_line SET returned_qty = ? WHERE order_line_id = ?",
        returnedQty,
        orderLineId);
  }

  /** 이행 사실(인계·전달·반품 종결)을 업무 키로 한 번만 기록. 이미 있으면 false(반영하지 않는다). */
  public boolean recordFact(
      String kind,
      String sourceRef,
      String orderId,
      @Nullable String deliveryId,
      String sourceEventId,
      String state,
      @Nullable String detail,
      Instant occurredAt,
      Instant appliedAt) {
    return jdbc.update(
            "INSERT INTO order_fulfillment_fact (kind, source_ref, order_id, delivery_id,"
                + " source_event_id, state, detail, occurred_at, applied_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (kind, source_ref) DO NOTHING",
            kind,
            sourceRef,
            orderId,
            deliveryId,
            sourceEventId,
            state,
            detail,
            java.sql.Timestamp.from(occurredAt),
            java.sql.Timestamp.from(appliedAt))
        > 0;
  }

  /** 반영된 전달 완료의 배송 ID(OrderCompleted.deliveryIds). */
  public List<String> deliveredDeliveryIds(String orderId) {
    return jdbc.queryForList(
        "SELECT source_ref FROM order_fulfillment_fact WHERE order_id = ? AND kind = 'DELIVERY'"
            + " AND state = 'APPLIED' ORDER BY occurred_at, source_ref",
        String.class,
        orderId);
  }

  /** 주문 상태·버전 갱신. */
  public void updateStatus(String orderId, String status, int version) {
    jdbc.update(
        "UPDATE customer_order SET status = ?, version = ? WHERE order_id = ?",
        status,
        version,
        orderId);
  }

  public Optional<OrderRow> byDraft(String draftId) {
    return jdbc
        .query("SELECT * FROM customer_order WHERE draft_id = ?", (rs, i) -> row(rs), draftId)
        .stream()
        .findFirst();
  }

  /** 탈퇴 의무 확인(ADR-0077) — 그 고객의 끝나지 않은 주문(상태가 finished 에 없는 것). 숨긴 주문도 센다. */
  public List<StatusRow> unfinishedByAccount(
      String accountId, java.util.Collection<String> finished) {
    String in = String.join(",", java.util.Collections.nCopies(finished.size(), "?"));
    List<Object> args = new ArrayList<>();
    args.add(accountId);
    args.addAll(finished);
    return jdbc.query(
        "SELECT order_id, status FROM customer_order WHERE account_id = ? AND status NOT IN ("
            + in
            + ") ORDER BY confirmed_at, order_id",
        (rs, i) -> new StatusRow(rs.getString("order_id"), rs.getString("status")),
        args.toArray());
  }

  /** 주문 ID·상태(탈퇴 의무 확인). */
  public record StatusRow(String orderId, String status) {}

  public boolean exists(String orderId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM customer_order WHERE order_id = ?", Integer.class, orderId);
    return n != null && n > 0;
  }

  public List<LineRow> lines(String orderId) {
    return jdbc.query(
        "SELECT * FROM customer_order_line WHERE order_id = ? ORDER BY line_no",
        (rs, i) ->
            new LineRow(
                rs.getString("order_line_id"),
                rs.getString("order_id"),
                rs.getInt("line_no"),
                rs.getString("cart_item_id"),
                rs.getString("sku"),
                rs.getString("product_id"),
                rs.getInt("units_per_sku"),
                rs.getInt("quantity"),
                rs.getLong("unit_price"),
                rs.getLong("line_amount"),
                rs.getLong("discount_share"),
                rs.getInt("weight_g"),
                rs.getInt("original_qty"),
                rs.getInt("unfulfilled_qty"),
                rs.getInt("handed_over_qty"),
                rs.getInt("cancelled_before_ship_qty"),
                rs.getInt("delivered_qty"),
                rs.getInt("returned_qty"),
                rs.getString("product_name"),
                rs.getString("option_label"),
                rs.getInt("reshipped_qty")),
        orderId);
  }

  /**
   * 내 주문 목록(최근 확정 순, 숨긴 주문 제외). keyset — after 가 있으면 그 (확정 시각, 주문 ID) 다음부터.
   *
   * @param limit 가져올 최대 건수(다음 쪽 판정을 위해 호출자가 +1 해서 준다)
   */
  public List<Summary> list(
      String accountId,
      ListCriteria c,
      @Nullable Instant afterConfirmedAt,
      @Nullable String afterOrderId,
      int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT o.order_id, o.status, o.confirmed_at, o.total_amount, o.order_number,"
                + " o.delivery_mode, o.mfc_id, f.product_id AS first_product_id, f.sku AS first_sku,"
                + " f.product_name AS first_product_name, f.option_label AS first_option_label,"
                + " f.quantity AS first_quantity,"
                + " (SELECT COALESCE(SUM(l.quantity), 0) FROM customer_order_line l"
                + " WHERE l.order_id = o.order_id) AS item_count"
                + " FROM customer_order o"
                + " LEFT JOIN LATERAL (SELECT l.product_id, l.sku, l.product_name, l.option_label,"
                + " l.quantity FROM customer_order_line l WHERE l.order_id = o.order_id"
                + " ORDER BY l.line_no LIMIT 1) f ON TRUE"
                + " WHERE o.account_id = ? AND o.hidden_at IS NULL");
    List<Object> args = new ArrayList<>();
    args.add(accountId);
    if (c.status() != null) {
      sql.append(" AND o.status = ?");
      args.add(c.status());
    }
    if (c.confirmedFrom() != null) {
      sql.append(" AND o.confirmed_at >= ?");
      args.add(Timestamp.from(c.confirmedFrom()));
    }
    if (c.confirmedToExclusive() != null) {
      sql.append(" AND o.confirmed_at < ?");
      args.add(Timestamp.from(c.confirmedToExclusive()));
    }
    if (c.nameLikePattern() != null) {
      sql.append(
          " AND EXISTS (SELECT 1 FROM customer_order_line q WHERE q.order_id = o.order_id"
              + " AND lower(q.product_name) LIKE ? ESCAPE '\\')");
      args.add(c.nameLikePattern());
    }
    if (afterConfirmedAt != null && afterOrderId != null) {
      sql.append(" AND (o.confirmed_at, o.order_id) < (?, ?)");
      args.add(Timestamp.from(afterConfirmedAt));
      args.add(afterOrderId);
    }
    sql.append(" ORDER BY o.confirmed_at DESC, o.order_id DESC LIMIT ?");
    args.add(limit);
    return jdbc.query(
        sql.toString(),
        (rs, i) ->
            new Summary(
                rs.getString("order_id"),
                rs.getString("status"),
                rs.getTimestamp("confirmed_at").toInstant(),
                rs.getLong("total_amount"),
                rs.getInt("item_count"),
                rs.getString("order_number"),
                rs.getString("delivery_mode"),
                rs.getString("first_product_id"),
                rs.getString("first_sku"),
                rs.getString("first_product_name"),
                rs.getString("first_option_label"),
                rs.getInt("first_quantity"),
                rs.getString("mfc_id")),
        args.toArray());
  }

  /**
   * 목록 쪽의 주문마다 앞 주문행 perOrder 개(line_no 순, BR-68 0.17.0) — 쪽 하나에 질의 한 번(N+1 없음). 주문 ID 순서는 받은 대로,
   * 행이 없는 주문은 빈 목록.
   */
  public Map<String, List<PreviewLine>> previewLines(Collection<String> orderIds, int perOrder) {
    Map<String, List<PreviewLine>> out = new LinkedHashMap<>();
    orderIds.forEach(id -> out.put(id, new ArrayList<>()));
    if (orderIds.isEmpty() || perOrder <= 0) {
      return out;
    }
    String in = String.join(",", java.util.Collections.nCopies(orderIds.size(), "?"));
    List<Object> args = new ArrayList<>(orderIds);
    args.add(perOrder);
    jdbc.query(
        "SELECT order_id, order_line_id, product_id, sku, product_name, option_label, quantity,"
            + " line_amount FROM (SELECT l.*, ROW_NUMBER() OVER (PARTITION BY l.order_id"
            + " ORDER BY l.line_no) AS rn FROM customer_order_line l WHERE l.order_id IN ("
            + in
            + ")) t WHERE rn <= ? ORDER BY order_id, rn",
        rs -> {
          PreviewLine l =
              new PreviewLine(
                  rs.getString("order_id"),
                  rs.getString("order_line_id"),
                  rs.getString("product_id"),
                  rs.getString("sku"),
                  rs.getString("product_name"),
                  rs.getString("option_label"),
                  rs.getInt("quantity"),
                  rs.getLong("line_amount"));
          out.computeIfAbsent(l.orderId(), k -> new ArrayList<>()).add(l);
        },
        args.toArray());
    return out;
  }

  /** 고객 목록에서 숨긴 시각(0.12.0). 숨기지 않았으면 empty. */
  public Optional<Instant> hiddenAt(String orderId) {
    return jdbc
        .query(
            "SELECT hidden_at FROM customer_order WHERE order_id = ? AND hidden_at IS NOT NULL",
            (rs, i) -> rs.getTimestamp("hidden_at").toInstant(),
            orderId)
        .stream()
        .findFirst();
  }

  /** 고객 목록에서 숨기기(0.12.0). 이미 숨겼으면 처음 시각 그대로. 상태·버전은 바꾸지 않는다. */
  public void hide(String orderId, Instant at) {
    jdbc.update(
        "UPDATE customer_order SET hidden_at = ? WHERE order_id = ? AND hidden_at IS NULL",
        Timestamp.from(at),
        orderId);
  }

  /** 직원용 검색 조건(O-6). null 은 거르지 않음. phoneDigits 는 숫자만(완전 일치). */
  public record StaffCriteria(
      @Nullable String orderId,
      @Nullable String orderNumber,
      @Nullable String accountId,
      @Nullable String status,
      @Nullable String mfcId,
      @Nullable String phoneDigits,
      @Nullable Instant from,
      @Nullable Instant to) {}

  /** 직원용 검색 한 줄. receiverPhone 은 원문(가림은 호출자) — 로그 금지. */
  public record StaffSummary(
      String orderId,
      String accountId,
      String status,
      int version,
      String mfcId,
      String deliveryMode,
      @Nullable LocalDate arrivalDate,
      @Nullable String arrivalStart,
      @Nullable String arrivalEnd,
      Instant confirmedAt,
      long totalAmount,
      long approvedAmount,
      int itemCount,
      int lineCount,
      int plannedShipments,
      int openFulfillmentIssues,
      @Nullable String receiverPhone,
      @Nullable String orderNumber) {}

  /**
   * 직원용 주문 검색(O-6, 읽기 전용). 최근 확정 순, offset 쪽 나눔. 기간 상한은 호출자가 확인한다. 연락처는 사본의 숫자만 비교(V9 식 색인).
   *
   * @param limit 가져올 최대 건수(다음 쪽 판정을 위해 호출자가 +1 해서 준다)
   */
  public List<StaffSummary> staffSearch(StaffCriteria c, int offset, int limit) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT o.order_id, o.account_id, o.status, o.version, o.mfc_id, o.delivery_mode,"
                + " o.arrival_date, o.arrival_start, o.arrival_end, o.confirmed_at, o.total_amount,"
                + " o.approved_amount, o.planned_shipments, o.order_number,"
                + " (SELECT COALESCE(SUM(l.quantity), 0) FROM customer_order_line l"
                + " WHERE l.order_id = o.order_id) AS item_count,"
                + " (SELECT COUNT(*) FROM customer_order_line l WHERE l.order_id = o.order_id)"
                + " AS line_count,"
                + " (SELECT COUNT(*) FROM order_fulfillment_issue f WHERE f.order_id = o.order_id"
                + " AND f.state = 'OPEN') AS open_issues,"
                + " s.receiver_phone"
                + " FROM customer_order o"
                + " LEFT JOIN customer_order_shipping s ON s.order_id = o.order_id WHERE TRUE");
    List<Object> args = new ArrayList<>();
    if (c.orderId() != null) {
      sql.append(" AND o.order_id = ?");
      args.add(c.orderId());
    }
    if (c.orderNumber() != null) {
      sql.append(" AND o.order_number = ?");
      args.add(c.orderNumber());
    }
    if (c.accountId() != null) {
      sql.append(" AND o.account_id = ?");
      args.add(c.accountId());
    }
    if (c.status() != null) {
      sql.append(" AND o.status = ?");
      args.add(c.status());
    }
    if (c.mfcId() != null) {
      sql.append(" AND o.mfc_id = ?");
      args.add(c.mfcId());
    }
    if (c.phoneDigits() != null) {
      sql.append(" AND regexp_replace(s.receiver_phone, '[^0-9]', '', 'g') = ?");
      args.add(c.phoneDigits());
    }
    if (c.from() != null) {
      sql.append(" AND o.confirmed_at >= ?");
      args.add(Timestamp.from(c.from()));
    }
    if (c.to() != null) {
      sql.append(" AND o.confirmed_at < ?");
      args.add(Timestamp.from(c.to()));
    }
    sql.append(" ORDER BY o.confirmed_at DESC, o.order_id DESC LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        sql.toString(),
        (rs, i) -> {
          Date arrival = rs.getDate("arrival_date");
          return new StaffSummary(
              rs.getString("order_id"),
              rs.getString("account_id"),
              rs.getString("status"),
              rs.getInt("version"),
              rs.getString("mfc_id"),
              rs.getString("delivery_mode"),
              arrival == null ? null : arrival.toLocalDate(),
              rs.getString("arrival_start"),
              rs.getString("arrival_end"),
              rs.getTimestamp("confirmed_at").toInstant(),
              rs.getLong("total_amount"),
              rs.getLong("approved_amount"),
              rs.getInt("item_count"),
              rs.getInt("line_count"),
              rs.getInt("planned_shipments"),
              rs.getInt("open_issues"),
              rs.getString("receiver_phone"),
              rs.getString("order_number"));
        },
        args.toArray());
  }

  private static OrderRow row(ResultSet rs) throws SQLException {
    Date arrival = rs.getDate("arrival_date");
    return new OrderRow(
        rs.getString("order_id"),
        rs.getString("account_id"),
        rs.getString("draft_id"),
        rs.getString("mfc_id"),
        rs.getString("status"),
        rs.getInt("version"),
        rs.getString("delivery_mode"),
        arrival == null ? null : arrival.toLocalDate(),
        rs.getString("arrival_start"),
        rs.getString("arrival_end"),
        rs.getString("out_of_stock_preference"),
        rs.getString("delivery_note"),
        rs.getString("shopping_address_id"),
        rs.getInt("quote_version"),
        rs.getInt("price_version"),
        rs.getLong("item_amount"),
        rs.getLong("delivery_tip_base"),
        rs.getLong("delivery_tip"),
        rs.getString("delivery_tip_reason"),
        rs.getLong("bag_fee"),
        rs.getLong("total_amount"),
        rs.getBoolean("first_purchase_applied"),
        rs.getInt("weight_g"),
        rs.getInt("planned_shipments"),
        rs.getString("payment_attempt_id"),
        rs.getString("pg_transaction_id"),
        rs.getLong("approved_amount"),
        rs.getString("reservation_id"),
        rs.getString("capacity_hold_id"),
        rs.getString("slot_hold_id"),
        rs.getTimestamp("confirmed_at").toInstant(),
        rs.getString("order_number"));
  }
}
