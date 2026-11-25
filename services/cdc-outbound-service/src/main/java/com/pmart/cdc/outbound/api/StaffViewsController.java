package com.pmart.cdc.outbound.api;

import com.pmart.cdc.outbound.api.generated.StaffViewsApi;
import com.pmart.cdc.outbound.api.generated.model.CountByKey;
import com.pmart.cdc.outbound.api.generated.model.ListCenterTransfers200Response;
import com.pmart.cdc.outbound.api.generated.model.OutboundSummary;
import com.pmart.cdc.outbound.api.generated.model.TransferDetail;
import com.pmart.cdc.outbound.api.generated.model.TransferDetailAllOfLines;
import com.pmart.cdc.outbound.api.generated.model.TransferSummary;
import com.pmart.cdc.outbound.application.StaffViews;
import com.pmart.cdc.outbound.infrastructure.StaffViewStore.TransferRow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 StaffViewsApi(0.11.0) — DC 백오피스 직원 조회. */
@RestController
public class StaffViewsController implements StaffViewsApi {

  private final StaffViews views;

  public StaffViewsController(StaffViews views) {
    this.views = views;
  }

  @Override
  public ResponseEntity<ListCenterTransfers200Response> listCenterTransfers(
      String cdcId, @Nullable String state, @Nullable String mfcId, Integer page, Integer size) {
    var p = views.transfers(cdcId, state, mfcId, page == null ? 0 : page, size == null ? 50 : size);
    var out = new ListCenterTransfers200Response();
    out.setItems(new ArrayList<>(p.items().stream().map(StaffViewsController::summary).toList()));
    out.setPage(p.page());
    out.setSize(p.size());
    out.setHasNext(p.hasNext());
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<TransferDetail> getCenterTransfer(String cdcId, String trfId) {
    var d = views.transfer(cdcId, trfId);
    TransferRow t = d.transfer();
    var out = new TransferDetail();
    out.setTrfId(t.trfId());
    out.setTrfVersion(t.trfVersion());
    out.setMfcId(t.mfcId());
    out.setState(t.state());
    out.setDeadlineMeaning(t.deadlineMeaning());
    out.setDueAt(time(t.dueAt()));
    out.setWaveId(t.waveId());
    out.setAllocationError(t.allocationError());
    out.setLineCount(t.lineCount());
    out.setApprovedQty((int) t.approvedQty());
    out.setAllocatedQty((int) t.allocatedQty());
    out.setPickedQty((int) t.pickedQty());
    out.setShortQty((int) t.shortQty());
    out.setCreatedAt(time(t.createdAt()));
    out.setUpdatedAt(time(t.updatedAt()));
    out.setLines(
        new ArrayList<>(
            d.lines().stream()
                .map(
                    l ->
                        new TransferDetailAllOfLines()
                            .sku(l.sku())
                            .approvedQty(l.approvedQty())
                            .allocatedQty(l.allocatedQty())
                            .pickedQty(l.pickedQty())
                            .shortQty(l.shortQty()))
                .toList()));
    return ResponseEntity.ok(out);
  }

  @Override
  public ResponseEntity<OutboundSummary> getOutboundSummary(
      String cdcId, @Nullable LocalDate date) {
    var s = views.summary(cdcId, date);
    var out = new OutboundSummary();
    out.setCdcId(s.cdcId());
    out.setDate(s.date());
    out.setTransferCount((int) s.totals().count());
    out.setTransfersByState(counts(s.transfersByState()));
    out.setApprovedQty((int) s.totals().approved());
    out.setAllocatedQty((int) s.totals().allocated());
    out.setPickedQty((int) s.totals().picked());
    out.setShortQty((int) s.totals().shortQty());
    out.setProgress(
        s.progress() == null
            ? null
            : java.math.BigDecimal.valueOf(s.progress())
                .setScale(4, java.math.RoundingMode.HALF_UP));
    out.setWavesByState(counts(s.wavesByState()));
    out.setTasksByKindState(counts(s.tasksByKindState()));
    out.setAsOf(time(s.asOf()));
    return ResponseEntity.ok(out);
  }

  static TransferSummary summary(TransferRow t) {
    return new TransferSummary()
        .trfId(t.trfId())
        .trfVersion(t.trfVersion())
        .mfcId(t.mfcId())
        .state(t.state())
        .deadlineMeaning(t.deadlineMeaning())
        .dueAt(time(t.dueAt()))
        .waveId(t.waveId())
        .allocationError(t.allocationError())
        .lineCount(t.lineCount())
        .approvedQty((int) t.approvedQty())
        .allocatedQty((int) t.allocatedQty())
        .pickedQty((int) t.pickedQty())
        .shortQty((int) t.shortQty())
        .createdAt(time(t.createdAt()))
        .updatedAt(time(t.updatedAt()));
  }

  private static List<CountByKey> counts(Map<String, Long> m) {
    List<CountByKey> out = new ArrayList<>();
    m.forEach((k, v) -> out.add(new CountByKey().key(k).count(v.intValue())));
    return out;
  }

  private static OffsetDateTime time(java.time.Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
