package com.pmart.cdc.outbound.application;

import com.pmart.cdc.outbound.infrastructure.StaffViewStore;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * DC 백오피스 직원 조회(0.11.0, 2026-11-25 — 사용자 "Dc 백오피스 만들어줘", 계획 docs/cdc/cdc-backoffice-plan.md 1단계).
 * 자기 센터 운영자만(토큰 centers). 읽기만 — 상태를 바꾸지 않는다.
 */
@Service
public class StaffViews {

  static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

  public record Page(List<StaffViewStore.TransferRow> items, int page, int size, boolean hasNext) {}

  public record Detail(StaffViewStore.TransferRow transfer, List<StaffViewStore.LineRow> lines) {}

  public record Summary(
      String cdcId,
      LocalDate date,
      StaffViewStore.Totals totals,
      Map<String, Long> transfersByState,
      Map<String, Long> wavesByState,
      Map<String, Long> tasksByKindState,
      @Nullable Double progress,
      Instant asOf) {}

  private final StaffViewStore store;
  private final Clock clock;
  private final WaveService waves;

  public StaffViews(StaffViewStore store, Clock clock, WaveService waves) {
    this.store = store;
    this.clock = clock;
    this.waves = waves;
  }

  public record WavePage(List<WaveService.WaveView> items, int page, int size, boolean hasNext) {}

  /** Wave 목록(0.15.0 W-1) — 줄은 GET /waves/{id} 와 같은 내용. */
  public WavePage waves(String cdcId, @Nullable String state, int page, int size) {
    Support.requireCenter(cdcId);
    int p = Math.max(0, page);
    int s = Math.min(200, Math.max(1, size));
    var ids = store.waveIds(cdcId, blank(state), p, s);
    boolean next = ids.size() > s;
    var items = (next ? ids.subList(0, s) : ids).stream().map(waves::get).toList();
    return new WavePage(items, p, s, next);
  }

  public Page transfers(
      String cdcId, @Nullable String state, @Nullable String mfcId, int page, int size) {
    Support.requireCenter(cdcId);
    int p = Math.max(0, page);
    int s = Math.min(200, Math.max(1, size));
    var rows = store.transfers(cdcId, blank(state), blank(mfcId), p, s);
    boolean next = rows.size() > s;
    return new Page(next ? rows.subList(0, s) : rows, p, s, next);
  }

  public Detail transfer(String cdcId, String trfId) {
    Support.requireCenter(cdcId);
    var t =
        store
            .transfer(cdcId, trfId)
            .orElseThrow(() -> PmartException.notFound("TRF_NOT_FOUND", "TRF 가 없습니다."));
    return new Detail(t, store.lines(trfId));
  }

  public Summary summary(String cdcId, @Nullable LocalDate date) {
    Support.requireCenter(cdcId);
    Instant now = Instant.now(clock);
    LocalDate d = date != null ? date : LocalDate.ofInstant(now, SEOUL);
    Instant from = d.atStartOfDay(SEOUL).toInstant();
    Instant to = d.plusDays(1).atStartOfDay(SEOUL).toInstant();
    var totals = store.totals(cdcId, from, to);
    Double progress = totals.approved() == 0 ? null : (double) totals.picked() / totals.approved();
    return new Summary(
        cdcId,
        d,
        totals,
        store.transfersByState(cdcId, from, to),
        store.wavesByState(cdcId, from, to),
        store.tasksByKindState(cdcId, from, to),
        progress,
        now);
  }

  private static @Nullable String blank(@Nullable String v) {
    return v == null || v.isBlank() ? null : v.strip();
  }
}
