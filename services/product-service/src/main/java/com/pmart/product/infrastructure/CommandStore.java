package com.pmart.product.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 판매 상태 변경 승인·명령 결과, SKU 발급 확인 명령 저장(JDBC). */
@Repository
public class CommandStore {

  public record GrantRow(
      String approvalId,
      String productId,
      String saleState,
      String decision,
      @Nullable String consumedRequestId) {}

  public record IssuanceRow(
      String requestId, String approvalId, String sku, String productId, Instant createdAt) {}

  private final JdbcTemplate jdbc;

  public CommandStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // ---------- 판매 상태 ----------

  public void insertGrant(
      String approvalId, String productId, String saleState, String decision, Instant at) {
    jdbc.update(
        "INSERT INTO sale_state_grant (approval_id, product_id, sale_state, decision, received_at)"
            + " VALUES (?,?,?,?,?) ON CONFLICT (approval_id) DO NOTHING",
        approvalId,
        productId,
        saleState,
        decision,
        Timestamp.from(at));
  }

  /** 행 잠금으로 같은 승인을 두 요청이 동시에 쓰지 못하게 한다. */
  public Optional<GrantRow> lockGrant(String approvalId) {
    return jdbc
        .query(
            "SELECT approval_id, product_id, sale_state, decision, consumed_request_id"
                + " FROM sale_state_grant WHERE approval_id = ? FOR UPDATE",
            (rs, i) ->
                new GrantRow(
                    rs.getString("approval_id"),
                    rs.getString("product_id"),
                    rs.getString("sale_state"),
                    rs.getString("decision"),
                    rs.getString("consumed_request_id")),
            approvalId)
        .stream()
        .findFirst();
  }

  public void consumeGrant(String approvalId, String requestId) {
    jdbc.update(
        "UPDATE sale_state_grant SET consumed_request_id = ? WHERE approval_id = ?",
        requestId,
        approvalId);
  }

  public boolean updateSaleState(
      String productId, int expectedRowVersion, String state, Instant at) {
    return jdbc.update(
            "UPDATE product SET sale_state = ?, row_version = row_version + 1, updated_at = ?"
                + " WHERE product_id = ? AND row_version = ?",
            state,
            Timestamp.from(at),
            productId,
            expectedRowVersion)
        == 1;
  }

  public void insertChange(
      String productId,
      String requestId,
      String from,
      String to,
      String reason,
      @Nullable String approvalId,
      String actor,
      String responseJson,
      Instant at) {
    jdbc.update(
        "INSERT INTO sale_state_change (product_id, request_id, from_state, to_state, reason,"
            + " approval_id, actor, response, created_at) VALUES (?,?,?,?,?,?,?,?::jsonb,?)",
        productId,
        requestId,
        from,
        to,
        reason,
        approvalId,
        actor,
        responseJson,
        Timestamp.from(at));
  }

  public Optional<String> changeResponse(String productId, String requestId) {
    return jdbc
        .queryForList(
            "SELECT response::text FROM sale_state_change WHERE product_id = ? AND request_id = ?",
            String.class,
            productId,
            requestId)
        .stream()
        .findFirst();
  }

  // ---------- SKU 발급 확인 ----------

  /** 승인으로 반영된 초안의 결과 SKU(JSON 배열 문자열들). */
  public List<String> appliedSkusJson(String approvalId) {
    return jdbc.queryForList(
        "SELECT result_skus::text FROM product_draft WHERE approval_id = ? AND state = 'APPLIED'",
        String.class,
        approvalId);
  }

  public int insertIssuance(
      String requestId,
      String sourceSystem,
      String approvalId,
      String sku,
      String productId,
      Instant at) {
    return jdbc.update(
        "INSERT INTO sku_issuance_request (request_id, source_system, approval_id, sku, product_id,"
            + " created_at) VALUES (?,?,?,?,?,?) ON CONFLICT (request_id) DO NOTHING",
        requestId,
        sourceSystem,
        approvalId,
        sku,
        productId,
        Timestamp.from(at));
  }

  public Optional<IssuanceRow> issuance(String requestId) {
    return jdbc
        .query(
            "SELECT request_id, approval_id, sku, product_id, created_at FROM sku_issuance_request"
                + " WHERE request_id = ?",
            (rs, i) ->
                new IssuanceRow(
                    rs.getString("request_id"),
                    rs.getString("approval_id"),
                    rs.getString("sku"),
                    rs.getString("product_id"),
                    rs.getTimestamp("created_at").toInstant()),
            requestId)
        .stream()
        .findFirst();
  }
}
