package com.pmart.mfc.capacity.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.capacity.domain.ReceivingRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오전 입고 수령창·하차면 예약(MFC 문서 6-3 "입고 차량 매일 오전", 2026-11-08). 하루 창 하나(설정), 예약 = 창 안의 도착 구간 + 박스 환산 수량.
 * 1회 상한·하루 상한·하차면 수를 넘으면 422. 같은 날 같은 운행(tripId)은 하나. 설정이 없으면 CONFIG_REQUIRED.
 */
@Service
public class ReceivingService {

  public static final String BAY_RESERVATION_NOT_FOUND = "BAY_RESERVATION_NOT_FOUND";
  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final Logger log = LoggerFactory.getLogger(ReceivingService.class);

  public record Window(
      Instant start,
      Instant end,
      int maxPerArrival,
      int totalUnits,
      int reservedUnits,
      int bayCount,
      int activeReservations) {
    public String state() {
      return reservedUnits >= totalUnits || activeReservations >= bayCount ? "FULL" : "OPEN";
    }
  }

  public record Bay(
      String bayReservationId,
      String mfcId,
      LocalDate businessDate,
      String tripId,
      Instant windowStart,
      Instant windowEnd,
      int loadUnits,
      String bayCode,
      String state,
      int version) {}

  private final JdbcTemplate jdbc;
  private final ReceivingProperties props;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public ReceivingService(
      JdbcTemplate jdbc, ReceivingProperties props, RequestIdempotency idempotency, Clock clock) {
    this.jdbc = jdbc;
    this.props = props;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Window window(String mfcId, LocalDate date) {
    require();
    Instant s = date.atTime(props.windowStart()).atZone(SEOUL).toInstant();
    Instant e = date.atTime(props.windowEnd()).atZone(SEOUL).toInstant();
    Integer reserved =
        jdbc.queryForObject(
            "SELECT COALESCE(SUM(load_units), 0) FROM unloading_bay_reservation WHERE mfc_id = ?"
                + " AND business_date = ? AND state <> 'CANCELLED'",
            Integer.class,
            mfcId,
            java.sql.Date.valueOf(date));
    Integer active =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM unloading_bay_reservation WHERE mfc_id = ? AND business_date = ?"
                + " AND state <> 'CANCELLED'",
            Integer.class,
            mfcId,
            java.sql.Date.valueOf(date));
    return new Window(
        s,
        e,
        props.maxLoadUnitsPerArrival(),
        props.totalLoadUnits(),
        reserved == null ? 0 : reserved,
        props.bayCount(),
        active == null ? 0 : active);
  }

  public Bay reserve(
      String requestId,
      String sourceSystem,
      String mfcId,
      String tripId,
      @Nullable List<String> trfIds,
      String window,
      @Nullable String vehicleType,
      int loadUnits) {
    require();
    String[] parts = window.split("/", 2);
    if (parts.length != 2) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "window 는 ISO-8601 구간(시작/끝)입니다.");
    }
    Instant reqStart;
    Instant reqEnd;
    try {
      reqStart = Instant.parse(parts[0]);
      reqEnd = Instant.parse(parts[1]);
    } catch (RuntimeException ex) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "window 시각 형식이 아닙니다: " + window);
    }
    LocalDate date = LocalDate.ofInstant(reqStart, SEOUL);
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "reserveUnloadingBay", sourceSystem),
                List.of(mfcId, tripId, window, loadUnits),
                String.class,
                () -> {
                  jdbc.queryForList(
                      "SELECT pg_advisory_xact_lock(hashtext(?))", "bays:" + mfcId + ":" + date);
                  var existing =
                      jdbc.query(
                          "SELECT bay_reservation_id FROM unloading_bay_reservation WHERE mfc_id = ?"
                              + " AND business_date = ? AND trip_id = ? AND state <> 'CANCELLED'",
                          (rs, i) -> rs.getString(1),
                          mfcId,
                          java.sql.Date.valueOf(date),
                          tripId);
                  if (!existing.isEmpty()) {
                    return existing.get(0); // 같은 운행은 하나
                  }
                  Window w = window(mfcId, date);
                  String reject =
                      ReceivingRules.rejection(
                          reqStart,
                          reqEnd,
                          w.start(),
                          w.end(),
                          loadUnits,
                          w.maxPerArrival(),
                          w.reservedUnits(),
                          w.totalUnits(),
                          w.activeReservations(),
                          w.bayCount());
                  if (reject != null) {
                    throw new PmartException(
                        reject,
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        message(reject, w, loadUnits),
                        false,
                        List.of());
                  }
                  Set<String> used =
                      new LinkedHashSet<>(
                          jdbc.query(
                              "SELECT bay_code FROM unloading_bay_reservation WHERE mfc_id = ?"
                                  + " AND business_date = ? AND state <> 'CANCELLED'",
                              (rs, i) -> rs.getString(1),
                              mfcId,
                              java.sql.Date.valueOf(date)));
                  String bay = null;
                  for (int b = 1; b <= w.bayCount(); b++) {
                    if (!used.contains("BAY-" + b)) {
                      bay = "BAY-" + b;
                      break;
                    }
                  }
                  Instant now = clock.instant();
                  String rid = "BAY-" + UUID.randomUUID();
                  jdbc.update(
                      "INSERT INTO unloading_bay_reservation (bay_reservation_id, mfc_id, business_date,"
                          + " trip_id, trf_ids, vehicle_type, window_start, window_end, load_units,"
                          + " bay_code, state, row_version, created_at, updated_at)"
                          + " VALUES (?,?,?,?,?,?,?,?,?,?,'RESERVED',1,?,?)",
                      rid,
                      mfcId,
                      java.sql.Date.valueOf(date),
                      tripId,
                      trfIds == null ? null : String.join(",", trfIds),
                      vehicleType,
                      Timestamp.from(reqStart),
                      Timestamp.from(reqEnd),
                      loadUnits,
                      bay,
                      Timestamp.from(now),
                      Timestamp.from(now));
                  log.info("하차면 예약 {} {} {} {}단위 {}", rid, mfcId, tripId, loadUnits, bay);
                  return rid;
                })
            .value();
    return get(id);
  }

  public Bay get(String id) {
    return jdbc
        .query(
            "SELECT * FROM unloading_bay_reservation WHERE bay_reservation_id = ?",
            (rs, i) -> row(rs),
            id)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> PmartException.notFound(BAY_RESERVATION_NOT_FOUND, "하차면 예약이 없습니다: " + id));
  }

  @Transactional
  public Bay cancel(String id, @Nullable Integer expectedVersion) {
    Bay b = get(id);
    if ("CANCELLED".equals(b.state())) {
      return b;
    }
    if (expectedVersion != null && expectedVersion != b.version()) {
      throw PmartException.versionConflict(
          "하차면 예약이 이미 바뀌었습니다(기대 " + expectedVersion + ", 현재 " + b.version() + ").");
    }
    if (!"RESERVED".equals(b.state())) {
      throw PmartException.invalidState("도착한 차량의 예약(" + b.state() + ")은 취소하지 않습니다.");
    }
    jdbc.update(
        "UPDATE unloading_bay_reservation SET state = 'CANCELLED', row_version = row_version + 1,"
            + " updated_at = ? WHERE bay_reservation_id = ?",
        Timestamp.from(clock.instant()),
        id);
    return get(id);
  }

  private void require() {
    if (!props.complete()) {
      throw PmartException.configRequired(ReceivingProperties.KEY);
    }
  }

  private static String message(String code, Window w, int load) {
    return switch (code) {
      case ReceivingRules.OUTSIDE_WINDOW -> "수령창(" + w.start() + "~" + w.end() + ") 밖의 도착입니다.";
      case ReceivingRules.PER_ARRIVAL_EXCEEDED ->
          "1회 입고 상한 " + w.maxPerArrival() + "단위를 넘습니다(" + load + ").";
      case ReceivingRules.WINDOW_FULL ->
          "오늘 창 남은 수용 "
              + Math.max(0, w.totalUnits() - w.reservedUnits())
              + "단위 — "
              + load
              + "단위를 받을 수 없습니다.";
      case ReceivingRules.NO_BAY -> "하차면 " + w.bayCount() + "개가 모두 예약됐습니다.";
      default -> code;
    };
  }

  private static Bay row(ResultSet rs) throws SQLException {
    return new Bay(
        rs.getString("bay_reservation_id"),
        rs.getString("mfc_id"),
        rs.getDate("business_date").toLocalDate(),
        rs.getString("trip_id"),
        rs.getTimestamp("window_start").toInstant(),
        rs.getTimestamp("window_end").toInstant(),
        rs.getInt("load_units"),
        rs.getString("bay_code"),
        rs.getString("state"),
        rs.getInt("row_version"));
  }
}
