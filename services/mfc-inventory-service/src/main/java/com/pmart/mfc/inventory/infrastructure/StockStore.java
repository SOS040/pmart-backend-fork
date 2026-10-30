package com.pmart.mfc.inventory.infrastructure;

import com.pmart.mfc.inventory.domain.StockRules.Balance;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 재고 원장 저장(JDBC): 잔량 {@code stock_balance}, 확정 단위 {@code stock_commit}·{@code stock_ledger_entry},
 * 수령 중복 방지 {@code stock_receipt}. 잔량 변경은 호출자가 잡은 행 잠금 안에서 하고, 불변식은 표 CHECK 로도 막는다.
 */
@Repository
public class StockStore {

  /** 확정 단위 머리. */
  public record CommitRow(
      String commitId,
      String commitType,
      String mfcId,
      @Nullable String requestId,
      String sourceSystem,
      @Nullable String sourceRef,
      int appliedQty,
      Instant occurredAt) {}

  /** 원장 기록 한 줄. */
  public record EntryRow(String sku, String symbol, int delta, long balanceVersion) {}

  /** 이미 전기된 수령. */
  public record ReceiptRow(String receiptId, String contentHash, String commitId, int appliedQty) {}

  private static final String BALANCE_COLUMNS =
      "sku, on_hand, quarantined, sellable, reserved, row_version";

  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public StockStore(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
    this.jdbc = jdbc;
    this.named = named;
  }

  // ---------- 잔량 ----------

  /** 잔량 행을 SKU 오름차순으로 잠근다(모든 수량 변경이 같은 순서로 잠가 교착을 피한다). 행이 없는 SKU 는 결과에 없다(= 0). */
  public Map<String, Balance> lockBalances(String mfcId, Collection<String> skus) {
    Map<String, Balance> out = new LinkedHashMap<>();
    if (skus.isEmpty()) {
      return out;
    }
    named.query(
        "SELECT "
            + BALANCE_COLUMNS
            + " FROM stock_balance WHERE mfc_id = :mfc AND sku IN (:skus) ORDER BY sku FOR UPDATE",
        Map.of("mfc", mfcId, "skus", skus),
        rs -> {
          Balance b = balance(rs);
          out.put(b.sku(), b);
        });
    return out;
  }

  /** 잠금 없는 조회(가용 조회). */
  public Map<String, Balance> balances(String mfcId, Collection<String> skus) {
    Map<String, Balance> out = new LinkedHashMap<>();
    if (skus.isEmpty()) {
      return out;
    }
    named.query(
        "SELECT " + BALANCE_COLUMNS + " FROM stock_balance WHERE mfc_id = :mfc AND sku IN (:skus)",
        Map.of("mfc", mfcId, "skus", skus),
        rs -> {
          Balance b = balance(rs);
          out.put(b.sku(), b);
        });
    return out;
  }

  /**
   * 수령 전기: 행이 없으면 만들고 있으면 더한다(INSERT ... ON CONFLICT 가 행을 잠근다).
   *
   * @return 반영 후 row_version
   */
  public long addReceipt(
      String mfcId, String sku, int onHand, int quarantined, int sellable, Instant at) {
    Long v =
        jdbc.queryForObject(
            "INSERT INTO stock_balance (mfc_id, sku, on_hand, quarantined, sellable, reserved,"
                + " row_version, updated_at) VALUES (?,?,?,?,?,0,1,?)"
                + " ON CONFLICT (mfc_id, sku) DO UPDATE SET"
                + " on_hand = stock_balance.on_hand + EXCLUDED.on_hand,"
                + " quarantined = stock_balance.quarantined + EXCLUDED.quarantined,"
                + " sellable = stock_balance.sellable + EXCLUDED.sellable,"
                + " row_version = stock_balance.row_version + 1, updated_at = EXCLUDED.updated_at"
                + " RETURNING row_version",
            Long.class,
            mfcId,
            sku,
            onHand,
            quarantined,
            sellable,
            Timestamp.from(at));
    return v == null ? 0 : v;
  }

  /**
   * 예약 수량 변경(R+delta). 호출자가 {@link #lockBalances} 로 잠근 행이어야 한다.
   *
   * @return 반영 후 row_version
   */
  public long addReserved(String mfcId, String sku, int delta, Instant at) {
    Long v =
        jdbc.queryForObject(
            "UPDATE stock_balance SET reserved = reserved + ?, row_version = row_version + 1,"
                + " updated_at = ? WHERE mfc_id = ? AND sku = ? RETURNING row_version",
            Long.class,
            delta,
            Timestamp.from(at),
            mfcId,
            sku);
    return v == null ? 0 : v;
  }

  // ---------- 확정 단위 ----------

  public void insertCommit(CommitRow c) {
    jdbc.update(
        "INSERT INTO stock_commit (commit_id, commit_type, mfc_id, request_id, source_system,"
            + " source_ref, applied_qty, occurred_at) VALUES (?,?,?,?,?,?,?,?)",
        c.commitId(),
        c.commitType(),
        c.mfcId(),
        c.requestId(),
        c.sourceSystem(),
        c.sourceRef(),
        c.appliedQty(),
        Timestamp.from(c.occurredAt()));
  }

  public void insertEntry(String commitId, String mfcId, EntryRow e) {
    jdbc.update(
        "INSERT INTO stock_ledger_entry (commit_id, mfc_id, sku, symbol, delta, balance_version)"
            + " VALUES (?,?,?,?,?,?)",
        commitId,
        mfcId,
        e.sku(),
        e.symbol(),
        e.delta(),
        e.balanceVersion());
  }

  public Optional<CommitRow> commit(String commitId) {
    return jdbc
        .query(
            "SELECT commit_id, commit_type, mfc_id, request_id, source_system, source_ref,"
                + " applied_qty, occurred_at FROM stock_commit WHERE commit_id = ?",
            (rs, i) -> commitRow(rs),
            commitId)
        .stream()
        .findFirst();
  }

  /** 요청(X-Source-System, Idempotency-Key)이 만든 확정 단위. 요청 하나는 확정 단위를 하나만 만든다. */
  public Optional<CommitRow> commitByRequest(String sourceSystem, String requestId) {
    return jdbc
        .query(
            "SELECT commit_id, commit_type, mfc_id, request_id, source_system, source_ref,"
                + " applied_qty, occurred_at FROM stock_commit"
                + " WHERE source_system = ? AND request_id = ? ORDER BY occurred_at LIMIT 1",
            (rs, i) -> commitRow(rs),
            sourceSystem,
            requestId)
        .stream()
        .findFirst();
  }

  public List<EntryRow> entries(String commitId) {
    return jdbc.query(
        "SELECT sku, symbol, delta, balance_version FROM stock_ledger_entry WHERE commit_id = ?"
            + " ORDER BY entry_id",
        (rs, i) ->
            new EntryRow(
                rs.getString("sku"),
                rs.getString("symbol"),
                rs.getInt("delta"),
                rs.getLong("balance_version")),
        commitId);
  }

  // ---------- 수령 중복 방지 ----------

  /**
   * receiptId 를 선점한다. 다른 트랜잭션이 같은 receiptId 를 먼저 넣고 있으면 그쪽이 끝날 때까지 기다린다(유일 키).
   *
   * @return 선점했으면 true. 이미 있으면 false(호출자가 {@link #receipt} 로 내용 비교)
   */
  public boolean claimReceipt(
      String receiptId,
      String mfcId,
      String contentHash,
      String commitId,
      int appliedQty,
      Instant at) {
    return jdbc.update(
            "INSERT INTO stock_receipt (receipt_id, mfc_id, content_hash, commit_id, applied_qty,"
                + " posted_at) VALUES (?,?,?,?,?,?) ON CONFLICT (receipt_id) DO NOTHING",
            receiptId,
            mfcId,
            contentHash,
            commitId,
            appliedQty,
            Timestamp.from(at))
        == 1;
  }

  public Optional<ReceiptRow> receipt(String receiptId) {
    return jdbc
        .query(
            "SELECT receipt_id, content_hash, commit_id, applied_qty FROM stock_receipt"
                + " WHERE receipt_id = ?",
            (rs, i) ->
                new ReceiptRow(
                    rs.getString("receipt_id"),
                    rs.getString("content_hash"),
                    rs.getString("commit_id"),
                    rs.getInt("applied_qty")),
            receiptId)
        .stream()
        .findFirst();
  }

  // ---------- 요청 기록(common-messaging request_record, 같은 서비스 DB) ----------

  /** 이 행위자(토큰 주체)가 보낸 요청의 X-Source-System. 없으면 empty. */
  public Optional<String> requestSource(String requestId, String actor) {
    return jdbc
        .query(
            "SELECT source_system FROM request_record WHERE request_id = ? AND actor = ?"
                + " ORDER BY created_at LIMIT 1",
            (rs, i) -> rs.getString(1),
            requestId,
            actor)
        .stream()
        .findFirst();
  }

  /** 요청 기록 시각. */
  public Optional<Instant> requestRecordedAt(String sourceSystem, String requestId) {
    List<Instant> out = new ArrayList<>();
    jdbc.query(
        "SELECT created_at FROM request_record WHERE source_system = ? AND request_id = ?",
        rs -> {
          out.add(rs.getTimestamp(1).toInstant());
        },
        sourceSystem,
        requestId);
    return out.stream().findFirst();
  }

  private static Balance balance(ResultSet rs) throws SQLException {
    return new Balance(
        rs.getString("sku"),
        rs.getInt("on_hand"),
        rs.getInt("quarantined"),
        rs.getInt("sellable"),
        rs.getInt("reserved"),
        rs.getLong("row_version"));
  }

  private static CommitRow commitRow(ResultSet rs) throws SQLException {
    return new CommitRow(
        rs.getString("commit_id"),
        rs.getString("commit_type"),
        rs.getString("mfc_id"),
        rs.getString("request_id"),
        rs.getString("source_system"),
        rs.getString("source_ref"),
        rs.getInt("applied_qty"),
        rs.getTimestamp("occurred_at").toInstant());
  }
}
