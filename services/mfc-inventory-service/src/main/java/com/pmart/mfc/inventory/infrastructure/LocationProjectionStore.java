package com.pmart.mfc.inventory.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** mfc-location 위치 투영(JDBC). 오래된 버전은 {@code ON CONFLICT ... WHERE} 로 막는다. */
@Repository
public class LocationProjectionStore {

  /** 위치 사본. */
  public record LocationRow(
      String locationId,
      String mfcId,
      String locationCode,
      String zone,
      String temperatureZone,
      String state,
      int version) {}

  private final JdbcTemplate jdbc;

  public LocationProjectionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @return 반영한 행 수(오래된 버전이면 0)
   */
  public int upsert(LocationRow r, Instant at) {
    return jdbc.update(
        "INSERT INTO location_projection (location_id, mfc_id, location_code, zone,"
            + " temperature_zone, state, location_version, updated_at) VALUES (?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (location_id) DO UPDATE SET mfc_id = EXCLUDED.mfc_id,"
            + " location_code = EXCLUDED.location_code, zone = EXCLUDED.zone,"
            + " temperature_zone = EXCLUDED.temperature_zone, state = EXCLUDED.state,"
            + " location_version = EXCLUDED.location_version, updated_at = EXCLUDED.updated_at"
            + " WHERE location_projection.location_version <= EXCLUDED.location_version",
        r.locationId(),
        r.mfcId(),
        r.locationCode(),
        r.zone(),
        r.temperatureZone(),
        r.state(),
        r.version(),
        Timestamp.from(at));
  }

  public Optional<LocationRow> byCode(String mfcId, String locationCode) {
    return jdbc
        .query(
            "SELECT location_id, mfc_id, location_code, zone, temperature_zone, state,"
                + " location_version FROM location_projection WHERE mfc_id = ? AND location_code = ?"
                + " ORDER BY location_version DESC LIMIT 1",
            (rs, i) ->
                new LocationRow(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getInt(7)),
            mfcId,
            locationCode)
        .stream()
        .findFirst();
  }
}
