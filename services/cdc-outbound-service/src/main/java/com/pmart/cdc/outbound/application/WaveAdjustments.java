package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.application.WaveService.WaveView;
import com.pmart.cdc.outbound.infrastructure.OutboundStore;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.TransferRow;
import com.pmart.cdc.outbound.infrastructure.OutboundStore.WaveRow;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Wave 조작(2026-11-26, 0.22.0·V15 — DC 백오피스, WMS CDC-F-017). 자기 센터 운영자만, 같은 Idempotency-Key 는 같은 결과.
 *
 * <ul>
 *   <li>보류·재개: 단계(state)와 따로 사유를 둔다. 보류된 Wave 는 릴리스 안 되고 그 작업은 PDA 다음 작업 받기에 안 나온다(이미 받은·시작한 작업은
 *       그대로).
 *   <li>취소: PLANNED, 또는 RELEASED 인데 시작한 작업·실피킹이 없을 때만 — 작업 CANCELLED, TRF 는 Wave 에서 빠져 다시 편성할 수
 *       있다(할당은 그대로). 실피킹이 하나라도 있으면 409 PICKING_STARTED(기실적은 지우지 않는다).
 *   <li>병합·분할: 둘 다 PLANNED(작업이 생기기 전)만. 병합은 같은 센터·온도대, 원 Wave 는 CANCELLED. 분할은 일부 TRF 로 새 PLANNED
 *       Wave.
 * </ul>
 */
@Service
public class WaveAdjustments {

  private static final Logger log = LoggerFactory.getLogger(WaveAdjustments.class);
  static final Set<String> NOT_STARTED_TASK = Set.of("CREATED", "ACCEPTED", "HELD", "CANCELLED");
  static final Set<String> HOLDABLE = Set.of("PLANNED", "RELEASED", "IN_PROGRESS");

  public record Hold(String reason, String by, Instant at) {}

  private final JdbcTemplate jdbc;
  private final OutboundStore store;
  private final WaveService waves;
  private final RequestIdempotency idempotency;
  private final Support support;

  public WaveAdjustments(
      JdbcTemplate jdbc,
      OutboundStore store,
      WaveService waves,
      RequestIdempotency idempotency,
      Support support) {
    this.jdbc = jdbc;
    this.store = store;
    this.waves = waves;
    this.idempotency = idempotency;
    this.support = support;
  }

  public WaveView hold(
      String requestId, String sourceSystem, String waveId, @Nullable Integer ev, String reason) {
    String why = reason(reason, true);
    return run(
        requestId,
        sourceSystem,
        waveId,
        ev,
        "HOLD",
        Map.of("reason", why),
        (w, by, at) -> {
          if (!HOLDABLE.contains(w.state())) {
            throw PmartException.invalidState("Wave 상태 " + w.state() + " 에서는 보류할 수 없습니다.");
          }
          if (held(waveId) != null) {
            throw PmartException.invalidState("이미 보류된 Wave 입니다.");
          }
          jdbc.update(
              "UPDATE wave SET held_at = ?, held_by = ?, held_reason = ?, row_version = row_version + 1 WHERE wave_id = ?",
              Timestamp.from(at),
              by,
              why,
              waveId);
          record(waveId, w.cdcId(), "HOLD", w.state(), w.state(), null, List.of(), why, by, at);
          return waveId;
        });
  }

  public WaveView resume(
      String requestId,
      String sourceSystem,
      String waveId,
      @Nullable Integer ev,
      @Nullable String reason) {
    String why = reason(reason, false);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", why);
    return run(
        requestId,
        sourceSystem,
        waveId,
        ev,
        "RESUME",
        body,
        (w, by, at) -> {
          if (held(waveId) == null) {
            throw PmartException.invalidState("보류된 Wave 가 아닙니다.");
          }
          jdbc.update(
              "UPDATE wave SET held_at = NULL, held_by = NULL, held_reason = NULL, row_version = row_version + 1 WHERE wave_id = ?",
              waveId);
          record(waveId, w.cdcId(), "RESUME", w.state(), w.state(), null, List.of(), why, by, at);
          return waveId;
        });
  }

  public WaveView cancel(
      String requestId, String sourceSystem, String waveId, @Nullable Integer ev, String reason) {
    String why = reason(reason, true);
    return run(
        requestId,
        sourceSystem,
        waveId,
        ev,
        "CANCEL",
        Map.of("reason", why),
        (w, by, at) -> {
          if (!Set.of("PLANNED", "RELEASED").contains(w.state())) {
            throw PmartException.invalidState(
                "Wave 상태 " + w.state() + " 에서는 취소할 수 없습니다(시작한 Wave 는 실적을 남기고 작업 단위로 정리).");
          }
          Integer started =
              jdbc.queryForObject(
                  "SELECT count(*) FROM task t WHERE t.wave_id = ? AND (t.state NOT IN ('CREATED','ACCEPTED','HELD','CANCELLED')"
                      + " OR EXISTS (SELECT 1 FROM task_line l WHERE l.task_id = t.task_id AND (l.picked_qty > 0 OR l.short_qty > 0)))",
                  Integer.class,
                  waveId);
          if (started != null && started > 0) {
            throw new PmartException(
                "PICKING_STARTED",
                HttpStatus.CONFLICT,
                "시작했거나 실적이 있는 작업이 있어 Wave 를 취소할 수 없습니다(기실적은 지우지 않음).",
                false,
                List.of());
          }
          List<String> trfs = detachTransfers(waveId, at);
          jdbc.update(
              "UPDATE task SET state = 'CANCELLED', assignee_id = NULL, row_version = row_version + 1,"
                  + " completed_at = ? WHERE wave_id = ? AND state <> 'CANCELLED'",
              Timestamp.from(at),
              waveId);
          closeWave(w, by, at, why, trfs);
          record(waveId, w.cdcId(), "CANCEL", w.state(), "CANCELLED", null, trfs, why, by, at);
          return waveId;
        });
  }

  /** source 의 TRF 를 target 으로 옮기고 source 는 CANCELLED(사유 MERGED). */
  public WaveView merge(
      String requestId,
      String sourceSystem,
      String targetId,
      @Nullable Integer ev,
      String sourceId,
      @Nullable String reason) {
    String why = reason(reason, false);
    if (targetId.equals(sourceId)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "같은 Wave 끼리는 합칠 수 없습니다.");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sourceWaveId", sourceId);
    body.put("reason", why);
    return run(
        requestId,
        sourceSystem,
        targetId,
        ev,
        "MERGE",
        body,
        (t, by, at) -> {
          WaveRow s =
              store
                  .wave(sourceId, true)
                  .orElseThrow(
                      () -> PmartException.notFound("NOT_FOUND", "Wave " + sourceId + " 가 없습니다."));
          if (!s.cdcId().equals(t.cdcId())) {
            throw PmartException.invalidState("다른 센터 Wave 와는 합칠 수 없습니다.");
          }
          if (!"PLANNED".equals(t.state()) || !"PLANNED".equals(s.state())) {
            throw PmartException.invalidState("릴리스 전(PLANNED) Wave 끼리만 합칠 수 있습니다.");
          }
          if (!Objects.equals(t.temperatureZone(), s.temperatureZone())) {
            throw PmartException.invalidState("온도대가 다른 Wave 는 합칠 수 없습니다.");
          }
          List<String> trfs =
              store.transfersOfWave(sourceId).stream().map(TransferRow::trfId).toList();
          jdbc.update(
              "UPDATE transfer SET wave_id = ?, row_version = row_version + 1, updated_at = ? WHERE wave_id = ?",
              targetId,
              Timestamp.from(at),
              sourceId);
          jdbc.update("UPDATE wave SET row_version = row_version + 1 WHERE wave_id = ?", targetId);
          closeWave(s, by, at, "MERGED " + targetId + (why == null ? "" : " — " + why), List.of());
          record(
              targetId, t.cdcId(), "MERGE_IN", "PLANNED", "PLANNED", sourceId, trfs, why, by, at);
          record(
              sourceId, s.cdcId(), "MERGED", "PLANNED", "CANCELLED", targetId, trfs, why, by, at);
          return targetId;
        });
  }

  /** 고른 TRF 로 새 PLANNED Wave. 새 Wave 를 돌려준다. */
  public WaveView split(
      String requestId,
      String sourceSystem,
      String waveId,
      @Nullable Integer ev,
      List<String> trfIds,
      @Nullable String reason) {
    String why = reason(reason, false);
    Set<String> pick = new TreeSet<>(trfIds);
    if (pick.isEmpty() || pick.size() != trfIds.size()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "trfIds 가 비었거나 같은 TRF 가 두 번 있습니다.");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("trfIds", List.copyOf(pick));
    body.put("reason", why);
    return run(
        requestId,
        sourceSystem,
        waveId,
        ev,
        "SPLIT",
        body,
        (w, by, at) -> {
          if (!"PLANNED".equals(w.state())) {
            throw PmartException.invalidState("릴리스 전(PLANNED) Wave 만 나눌 수 있습니다.");
          }
          Set<String> mine = new TreeSet<>();
          store.transfersOfWave(waveId).forEach(t -> mine.add(t.trfId()));
          if (!mine.containsAll(pick)) {
            throw PmartException.invalidState("이 Wave 에 없는 TRF 가 있습니다.");
          }
          if (mine.size() == pick.size()) {
            throw PmartException.invalidState("모든 TRF 를 옮기면 나누는 것이 아닙니다 — 하나 이상 남기세요.");
          }
          String newId = Support.newId("WAVE");
          store.insertWave(
              new WaveRow(
                  newId,
                  w.cdcId(),
                  "PLANNED",
                  1,
                  w.temperatureZone(),
                  w.windowStart(),
                  w.windowEnd(),
                  by,
                  at,
                  null));
          for (String trf : pick) {
            jdbc.update(
                "UPDATE transfer SET wave_id = ?, row_version = row_version + 1, updated_at = ? WHERE trf_id = ?",
                newId,
                Timestamp.from(at),
                trf);
          }
          jdbc.update("UPDATE wave SET row_version = row_version + 1 WHERE wave_id = ?", waveId);
          record(
              waveId,
              w.cdcId(),
              "SPLIT_OUT",
              "PLANNED",
              "PLANNED",
              newId,
              List.copyOf(pick),
              why,
              by,
              at);
          record(
              newId,
              w.cdcId(),
              "SPLIT_FROM",
              "PLANNED",
              "PLANNED",
              waveId,
              List.copyOf(pick),
              why,
              by,
              at);
          return newId;
        });
  }

  public record Adjustment(
      String adjustmentId,
      String action,
      String fromState,
      String toState,
      @Nullable String otherWaveId,
      List<String> trfIds,
      @Nullable String reason,
      String actorId,
      Instant at) {}

  public List<Adjustment> history(String waveId) {
    WaveRow w =
        store
            .wave(waveId, false)
            .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "Wave " + waveId + " 가 없습니다."));
    Support.requireCenter(w.cdcId());
    return jdbc.query(
        "SELECT * FROM wave_adjustment WHERE wave_id = ? ORDER BY adjusted_at DESC, adjustment_id",
        (rs, i) ->
            new Adjustment(
                rs.getString("adjustment_id"),
                rs.getString("action"),
                rs.getString("from_state"),
                rs.getString("to_state"),
                rs.getString("other_wave_id"),
                rs.getString("trf_ids").isEmpty()
                    ? List.of()
                    : List.of(rs.getString("trf_ids").split(",")),
                rs.getString("reason"),
                rs.getString("actor_id"),
                rs.getTimestamp("adjusted_at").toInstant()),
        waveId);
  }

  /** 응답에 붙일 보류 정보(없으면 빠짐). */
  public Map<String, Hold> holds(Collection<String> waveIds) {
    Map<String, Hold> out = new HashMap<>();
    if (waveIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT wave_id, held_reason, held_by, held_at FROM wave WHERE held_at IS NOT NULL AND wave_id = ANY (?)",
        rs -> {
          out.put(
              rs.getString(1),
              new Hold(rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()));
        },
        (Object) waveIds.stream().distinct().toArray(String[]::new));
    return out;
  }

  // ---------- 내부 ----------

  private interface Change {
    String apply(WaveRow w, String by, Instant at);
  }

  private WaveView run(
      String requestId,
      String sourceSystem,
      String waveId,
      @Nullable Integer ev,
      String action,
      Map<String, Object> body,
      Change change) {
    WaveRow before =
        store
            .wave(waveId, false)
            .orElseThrow(() -> PmartException.notFound("NOT_FOUND", "Wave " + waveId + " 가 없습니다."));
    String by = Support.requireCenter(before.cdcId());
    Map<String, Object> req = new LinkedHashMap<>(body);
    req.put("waveId", waveId);
    req.put("expectedVersion", ev);
    String resultId =
        idempotency
            .execute(
                Support.key(sourceSystem, requestId, "cdcOutbound:wave" + action, by),
                req,
                String.class,
                () -> {
                  WaveRow w = store.wave(waveId, true).orElseThrow();
                  if (ev != null && ev != w.version()) {
                    throw PmartException.versionConflict(
                        "Wave 가 이미 바뀌었습니다(기대 버전 " + ev + ", 현재 " + w.version() + ").");
                  }
                  String id = change.apply(w, by, support.now());
                  log.info("Wave 조작 {} {} → {}", action, waveId, id);
                  return id;
                })
            .value();
    return waves.get(resultId);
  }

  /** Wave 의 TRF(IN_WAVE) 를 빼고 할당 상태로 되돌린다 — 다시 편성할 수 있게(할당·예약은 그대로). */
  private List<String> detachTransfers(String waveId, Instant at) {
    List<String> out = new ArrayList<>();
    for (TransferRow t : store.transfersOfWave(waveId)) {
      if (!"IN_WAVE".equals(t.state())) {
        continue; // 취소 중·취소됨·대체 중은 그 흐름이 정리한다
      }
      Integer shortLines =
          jdbc.queryForObject(
              "SELECT count(*) FROM transfer_line WHERE trf_id = ? AND allocated_qty < approved_qty - cancelled_qty",
              Integer.class,
              t.trfId());
      String back = shortLines != null && shortLines > 0 ? "PARTIALLY_ALLOCATED" : "ALLOCATED";
      jdbc.update(
          "UPDATE transfer SET state = ?, wave_id = NULL, row_version = row_version + 1, updated_at = ? WHERE trf_id = ?",
          back,
          Timestamp.from(at),
          t.trfId());
      out.add(t.trfId());
    }
    return out;
  }

  private void closeWave(
      WaveRow w, String by, Instant at, @Nullable String why, List<String> trfs) {
    jdbc.update(
        "UPDATE wave SET state = 'CANCELLED', cancel_reason = ?, held_at = NULL, held_by = NULL, held_reason = NULL,"
            + " row_version = row_version + 1 WHERE wave_id = ?",
        why,
        w.waveId());
    waves.publishState(w, "CANCELLED", trfs, by, w.version() + 1, at);
  }

  private @Nullable Hold held(String waveId) {
    return holds(List.of(waveId)).get(waveId);
  }

  private void record(
      String waveId,
      String cdcId,
      String action,
      String from,
      String to,
      @Nullable String other,
      List<String> trfs,
      @Nullable String reason,
      String by,
      Instant at) {
    jdbc.update(
        "INSERT INTO wave_adjustment (adjustment_id, wave_id, cdc_id, action, from_state, to_state, other_wave_id,"
            + " trf_ids, reason, actor_id, adjusted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        Support.newId("WADJ"),
        waveId,
        cdcId,
        action,
        from,
        to,
        other,
        String.join(",", trfs),
        reason,
        by,
        Timestamp.from(at));
  }

  private static @Nullable String reason(@Nullable String r, boolean required) {
    String v = r == null || r.isBlank() ? null : r.strip();
    if (required && v == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "사유가 필요합니다.");
    }
    if (v != null && v.length() > 300) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "사유는 300자까지입니다.");
    }
    return v;
  }
}
