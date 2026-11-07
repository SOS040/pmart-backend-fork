package com.pmart.mfc.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.test.PostgresContainerSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * V4(ADR-0040 결정 7): V3 의 센터·SKU 잔량과 예약 줄이 UNTRACKED LOT·이관 위치 MIGRATION 단위 행과 예약 조각으로 옮겨지고 합계가
 * 그대로인지. 빈 DB 를 새로 만들어 V3 까지 적용 → 데이터 → V4.
 */
class StockMigrationV4IT extends PostgresContainerSupport {

  @Test
  void v3BalancesAndReservationsMoveToUnitsLotsAndParts() throws Exception {
    String db = "mig_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    try (Connection c =
        DriverManager.getConnection(
            postgres().getJdbcUrl(), postgres().getUsername(), postgres().getPassword())) {
      c.createStatement().execute("CREATE DATABASE " + db);
    }
    String url = postgres().getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
    var ds =
        new SingleConnectionDataSource(
            url, postgres().getUsername(), postgres().getPassword(), true);
    try {
      Flyway.configure()
          .dataSource(ds)
          .locations("classpath:db/migration")
          .target("3")
          .load()
          .migrate();
      var jdbc = new JdbcTemplate(ds);
      // H10 Q2 E6 R4 (미판정 2), 예약 4 HELD
      jdbc.update(
          "INSERT INTO stock_balance (mfc_id, sku, on_hand, quarantined, sellable, reserved,"
              + " row_version, updated_at) VALUES ('MFC-1','E-00000001',10,2,6,4,3,now())");
      jdbc.update(
          "INSERT INTO reservation (reservation_id, order_id, mfc_id, state, expires_at, row_version,"
              + " source_system, request_id, created_at, updated_at) VALUES ('RSV-1','ORD-1','MFC-1',"
              + "'HELD', now(), 1, 'order-orchestration-service', 'k1', now(), now())");
      jdbc.update(
          "INSERT INTO reservation_line (reservation_id, sku, qty) VALUES ('RSV-1','E-00000001',4)");

      Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();

      Map<String, Object> lot =
          jdbc.queryForMap("SELECT * FROM stock_lot_balance WHERE mfc_id = 'MFC-1'");
      assertThat(lot)
          .containsEntry("lot_id", "LOT-MIG-MFC-1-E-00000001")
          .containsEntry("on_hand", 10)
          .containsEntry("quarantined", 2)
          .containsEntry("sellable", 6)
          .containsEntry("reserved", 4);
      assertThat(
              jdbc.queryForObject(
                  "SELECT tracking FROM stock_lot WHERE lot_id = 'LOT-MIG-MFC-1-E-00000001'",
                  String.class))
          .isEqualTo("UNTRACKED");
      Map<String, Integer> units = new java.util.TreeMap<>();
      jdbc.query(
          "SELECT quality_state || '/' || work_state AS k, qty FROM stock_unit"
              + " WHERE mfc_id = 'MFC-1' AND location_code = 'MIGRATION'",
          rs -> {
            units.put(rs.getString("k"), rs.getInt("qty"));
          });
      assertThat(units)
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  "NORMAL/STORED", 6, "QUARANTINED/UNSTOWED", 2, "PENDING_INSPECTION/UNSTOWED", 2));
      Map<String, Object> part = jdbc.queryForMap("SELECT * FROM reservation_part");
      assertThat(part)
          .containsEntry("part_id", "RSV-1:E-00000001")
          .containsEntry("lot_id", "LOT-MIG-MFC-1-E-00000001")
          .containsEntry("qty", 4)
          .containsEntry("state", "HELD");
    } finally {
      ds.destroy();
    }
  }
}
