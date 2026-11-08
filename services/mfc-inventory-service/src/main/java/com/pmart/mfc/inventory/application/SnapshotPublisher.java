package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code InventorySnapshotPublished}(mfc.inventory.v1, 2026-11-08 남은 일 17 — 공급·예측 준비). 센터 × SKU
 * 요약(H·Q·E·R·A = E−R)을 주기적으로 알린다. 구독 예정 replenishment(재고 포지션 IP)·demand-planning(품절 구간 판정) — 아직 없다.
 *
 * <ul>
 *   <li>주기 {@code pmart.inventory.snapshot-interval}(값이 없으면 발행 안 함 — 구독자가 생기는 환경에서 켠다). trigger
 *       PERIODIC.
 *   <li>줄 = H 또는 R 이 0 보다 큰 SKU 만(없는 SKU = 0). LOT 은 넣지 않음(센터 × SKU — 계약의 lot 은 선택).
 *   <li>한 사건에 {@link #PART_SIZE} 줄까지 — 넘으면 같은 snapshotId·version 으로 나눠 보내고 payload 에 {@code
 *       part}·{@code partCount}(계약 additionalProperties). 구독자는 partCount 가 다 모여야 한 스냅샷.
 *   <li>버전 = 센터마다 1씩(inventory_snapshot_publication), 같은 트랜잭션 outbox. 여러 인스턴스는 센터 advisory lock.
 *   <li>safetyQty 는 비움(안전량 S 정책 미결 — 0 으로 채우지 않는다).
 * </ul>
 */
@Component
public class SnapshotPublisher {

  static final int PART_SIZE = 500;
  private static final Logger log = LoggerFactory.getLogger(SnapshotPublisher.class);

  public record Published(String mfcId, String snapshotId, int version, int lines, int parts) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final Clock clock;
  private final String interval;

  public SnapshotPublisher(
      JdbcTemplate jdbc,
      PlatformTransactionManager txm,
      EventPublisher events,
      EventEnvelopeCodec codec,
      Clock clock,
      @Value("${pmart.inventory.snapshot-interval:}") String interval) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(txm);
    this.events = events;
    this.codec = codec;
    this.clock = clock;
    this.interval = interval == null ? "" : interval.trim();
  }

  @Scheduled(
      fixedDelayString = "${pmart.inventory.snapshot-interval:PT1H}",
      initialDelayString = "${pmart.inventory.snapshot-initial-delay:PT2M}")
  void scheduled() {
    if (interval.isEmpty()) {
      return; // 설정 없음 — 발행 안 함
    }
    for (String mfcId :
        jdbc.queryForList(
            "SELECT DISTINCT mfc_id FROM stock_balance WHERE on_hand > 0 OR reserved > 0",
            String.class)) {
      try {
        publish(mfcId, "PERIODIC");
      } catch (RuntimeException e) {
        log.warn("재고 스냅샷 발행 실패 {}(다음 주기에 다시): {}", mfcId, e.toString());
      }
    }
  }

  public Published publish(String mfcId, String trigger) {
    Published p =
        tx.execute(
            s -> {
              jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "snapshot:" + mfcId);
              Instant at = clock.instant();
              List<Map<String, Object>> lines =
                  jdbc.query(
                      "SELECT sku, on_hand, quarantined, sellable, reserved FROM stock_balance"
                          + " WHERE mfc_id = ? AND (on_hand > 0 OR reserved > 0) ORDER BY sku",
                      (rs, i) -> {
                        Map<String, Object> l = new LinkedHashMap<>();
                        l.put("sku", rs.getString("sku"));
                        l.put("onHandQty", rs.getInt("on_hand"));
                        l.put("saleableQty", rs.getInt("sellable"));
                        l.put("reservedQty", rs.getInt("reserved"));
                        l.put("heldQty", rs.getInt("quarantined"));
                        l.put(
                            "availableQty",
                            Math.max(0, rs.getInt("sellable") - rs.getInt("reserved")));
                        return l;
                      },
                      mfcId);
              List<Integer> prev =
                  jdbc.queryForList(
                      "SELECT version FROM inventory_snapshot_publication WHERE mfc_id = ?",
                      Integer.class,
                      mfcId);
              int version = prev.isEmpty() ? 1 : prev.get(0) + 1;
              String snapshotId = "SNP-" + UUID.randomUUID();
              jdbc.update(
                  "INSERT INTO inventory_snapshot_publication (mfc_id, version, snapshot_id,"
                      + " line_count, published_at) VALUES (?,?,?,?,?) ON CONFLICT (mfc_id) DO UPDATE"
                      + " SET version = EXCLUDED.version, snapshot_id = EXCLUDED.snapshot_id,"
                      + " line_count = EXCLUDED.line_count, published_at = EXCLUDED.published_at",
                  mfcId,
                  version,
                  snapshotId,
                  lines.size(),
                  Timestamp.from(at));
              List<List<Map<String, Object>>> parts = new ArrayList<>();
              for (int i = 0; i < lines.size(); i += PART_SIZE) {
                parts.add(lines.subList(i, Math.min(lines.size(), i + PART_SIZE)));
              }
              if (parts.isEmpty()) {
                parts.add(List.of()); // 재고 없음도 한 번 알린다(품절 판정)
              }
              for (int i = 0; i < parts.size(); i++) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("centerType", "MFC");
                payload.put("centerId", mfcId);
                payload.put("snapshotId", snapshotId);
                payload.put("asOf", at.toString());
                payload.put("version", version);
                payload.put("trigger", trigger);
                payload.put("part", i + 1);
                payload.put("partCount", parts.size());
                payload.put("lines", parts.get(i));
                events.publish(
                    codec.create(
                        events.sourceSystem(),
                        "InventorySnapshotPublished",
                        1,
                        "InventorySnapshot",
                        mfcId,
                        version,
                        new EventEnvelope.Actor(
                            EventEnvelope.ActorType.SYSTEM, events.sourceSystem()),
                        EventEnvelope.EventSource.REAL,
                        payload),
                    HandoverPostingService.TOPIC);
              }
              return new Published(mfcId, snapshotId, version, lines.size(), parts.size());
            });
    if (p == null) {
      throw new IllegalStateException("snapshot returned nothing");
    }
    log.info(
        "재고 스냅샷 {} {} v{} — {}줄·{}건", p.mfcId(), p.snapshotId(), p.version(), p.lines(), p.parts());
    return p;
  }
}
