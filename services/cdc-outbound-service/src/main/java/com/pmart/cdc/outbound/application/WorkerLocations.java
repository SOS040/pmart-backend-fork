package com.pmart.cdc.outbound.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 작업자 위치(2026-11-25, 0.18.0 — DC 백오피스 M-1, 사용자 "서버에 작업 남기는 방식은 PDA로 스캔한 위치로 해"). PDA 가 보낸 위치 코드의
 * 마지막 한 줄(작업자별, 이력 없음): 피킹 실적 마지막 줄 위치 + 위치 스캔 경로. 마지막 스캔이 {@code
 * pmart.outbound.worker-location-stale} (기본 10분)보다 오래되면 STALE("모름"). 층·구역은 위치 코드(CDC01-구역-층-…)에서
 * 읽는다.
 */
@Service
public class WorkerLocations {

  public record WorkerRow(
      String workerId,
      @Nullable String locationCode,
      @Nullable String zone,
      @Nullable String floor,
      @Nullable String source,
      @Nullable Instant scannedAt,
      String locationState,
      @Nullable String taskId,
      @Nullable String taskKind,
      @Nullable String taskState) {}

  public record Board(List<WorkerRow> workers, Map<String, Long> byFloor, Instant asOf) {}

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Duration stale;

  public WorkerLocations(
      JdbcTemplate jdbc,
      Clock clock,
      @Value("${pmart.outbound.worker-location-stale:PT10M}") Duration stale) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.stale = stale;
  }

  /** 위치 코드 구간: CDC01-A-5-… → [CDC01, A, 5, …]. 구역 = 둘째, 층 = 셋째(없으면 null). */
  static @Nullable String part(String code, int i) {
    String[] p = code.split("-");
    return p.length > i && !p[i].isBlank() ? p[i] : null;
  }

  /** 위치 스캔(PDA 본인). */
  public void scan(String cdcId, String workerId, String locationCode, @Nullable String taskId) {
    Support.requireSelf(cdcId, workerId);
    record(cdcId, workerId, locationCode, "SCAN", taskId);
  }

  /** 피킹 실적 뒤(이미 본인 확인됨) — 마지막 줄 위치. 실패해도 실적은 그대로. */
  public void afterPick(
      String cdcId, String workerId, @Nullable String locationCode, String taskId) {
    if (locationCode == null || locationCode.isBlank()) {
      return;
    }
    try {
      record(cdcId, workerId, locationCode, "PICK_RESULT", taskId);
    } catch (RuntimeException e) {
      // 위치 기록 실패는 실적을 막지 않는다
    }
  }

  void record(
      String cdcId, String workerId, String locationCode, String source, @Nullable String taskId) {
    String code = locationCode.strip();
    if (code.length() > 40) {
      throw new com.pmart.common.web.PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "위치 코드가 너무 깁니다(40자).");
    }
    jdbc.update(
        "INSERT INTO worker_location (cdc_id, worker_id, location_code, zone, floor, source, task_id,"
            + " scanned_at) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (cdc_id, worker_id) DO UPDATE SET"
            + " location_code = EXCLUDED.location_code, zone = EXCLUDED.zone, floor = EXCLUDED.floor,"
            + " source = EXCLUDED.source, task_id = EXCLUDED.task_id, scanned_at = EXCLUDED.scanned_at",
        cdcId,
        workerId,
        code,
        part(code, 1),
        part(code, 2),
        source,
        taskId,
        Timestamp.from(clock.instant()));
  }

  /** 백오피스 작업자 현황 — 위치가 있거나 지금 작업을 든 작업자. floor 로 거르면 그 층 위치인 사람만. */
  public Board board(String cdcId, @Nullable String floor) {
    Support.requireCenter(cdcId);
    Instant now = clock.instant();
    Instant cut = now.minus(stale);
    String f = floor == null || floor.isBlank() ? null : floor.strip();
    List<Object> args = new ArrayList<>(List.of(cdcId, cdcId, cdcId));
    String sql =
        "WITH w AS (SELECT worker_id FROM worker_location WHERE cdc_id = ? UNION SELECT assignee_id"
            + " FROM task WHERE cdc_id = ? AND assignee_id IS NOT NULL AND state IN ('ACCEPTED',"
            + "'IN_PROGRESS')), cur AS (SELECT DISTINCT ON (assignee_id) assignee_id, task_id, kind,"
            + " state FROM task WHERE cdc_id = ? AND state IN ('ACCEPTED','IN_PROGRESS') ORDER BY"
            + " assignee_id, coalesce(started_at, accepted_at) DESC NULLS LAST) SELECT w.worker_id,"
            + " l.location_code, l.zone, l.floor, l.source, l.scanned_at, cur.task_id, cur.kind,"
            + " cur.state FROM w LEFT JOIN worker_location l ON l.cdc_id = ? AND l.worker_id ="
            + " w.worker_id LEFT JOIN cur ON cur.assignee_id = w.worker_id";
    args.add(cdcId);
    if (f != null) {
      sql += " WHERE l.floor = ?";
      args.add(f);
    }
    sql += " ORDER BY l.floor NULLS LAST, l.zone NULLS LAST, w.worker_id";
    List<WorkerRow> rows =
        jdbc.query(
            sql,
            (rs, i) -> {
              Timestamp ts = rs.getTimestamp("scanned_at");
              Instant at = ts == null ? null : ts.toInstant();
              String state = at == null ? "UNKNOWN" : at.isBefore(cut) ? "STALE" : "FRESH";
              return new WorkerRow(
                  rs.getString("worker_id"),
                  rs.getString("location_code"),
                  rs.getString("zone"),
                  rs.getString("floor"),
                  rs.getString("source"),
                  at,
                  state,
                  rs.getString("task_id"),
                  rs.getString("kind"),
                  rs.getString("state"));
            },
            args.toArray());
    Map<String, Long> byFloor = new LinkedHashMap<>();
    for (WorkerRow r : rows) {
      if ("FRESH".equals(r.locationState()) && r.floor() != null) {
        byFloor.merge(r.floor(), 1L, Long::sum);
      }
    }
    return new Board(rows, byFloor, now);
  }
}
