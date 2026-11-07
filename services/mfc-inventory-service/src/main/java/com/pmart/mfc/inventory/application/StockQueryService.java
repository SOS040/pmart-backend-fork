package com.pmart.mfc.inventory.application;

import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.StockRules.Balance;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore;
import com.pmart.mfc.inventory.infrastructure.SkuHandlingStore.HandlingRow;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.CommitRow;
import com.pmart.mfc.inventory.infrastructure.StockStore.EntryRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가용·원결과 조회(서비스 간). 조회 사본이며 성공 확정은 commitId/requestId 로 한다. */
@Service
public class StockQueryService {

  /** 가용 한 행. 원장 행이 없으면 수량 0, version 0. */
  public record AvailabilityView(String sku, Balance balance, boolean handlingEnabled) {}

  public record CommitView(CommitRow commit, List<EntryRow> entries) {}

  /** 요청 원결과. 수량 변화가 없던 요청은 commit 이 null. */
  public record RequestView(String requestId, @Nullable CommitRow commit, Instant occurredAt) {}

  private final StockStore stock;
  private final SkuHandlingStore handlings;

  public StockQueryService(StockStore stock, SkuHandlingStore handlings) {
    this.stock = stock;
    this.handlings = handlings;
  }

  @Transactional(readOnly = true)
  public List<AvailabilityView> availability(String mfcId, List<String> skus) {
    Map<String, Balance> balances = stock.balances(mfcId, skus);
    Map<String, HandlingRow> flags = handlings.findAll(mfcId, skus);
    List<AvailabilityView> out = new ArrayList<>();
    for (String s : skus) {
      HandlingRow h = flags.get(s);
      out.add(
          new AvailabilityView(
              s, balances.getOrDefault(s, Balance.empty(s)), h != null && h.enabled()));
    }
    return out;
  }

  @Transactional(readOnly = true)
  public CommitView commit(String commitId) {
    CommitRow c =
        stock
            .commit(commitId)
            .orElseThrow(
                () -> PmartException.notFound("COMMIT_NOT_FOUND", "전기가 없습니다: " + commitId));
    return new CommitView(c, stock.entries(commitId));
  }

  /** 호출자(토큰 주체)가 보낸 요청의 원결과. 기록이 없으면 404 — 반영되지 않았다. */
  @Transactional(readOnly = true)
  public RequestView request(String requestId) {
    String actor = Callers.actor();
    Optional<String> source = stock.requestSource(requestId, actor);
    if (source.isEmpty()) {
      throw PmartException.notFound("REQUEST_NOT_FOUND", "기록된 요청이 없습니다: " + requestId);
    }
    Optional<CommitRow> c = stock.commitByRequest(source.get(), requestId);
    Instant at =
        c.map(CommitRow::occurredAt)
            .or(() -> stock.requestRecordedAt(source.get(), requestId))
            .orElseGet(Instant::now);
    return new RequestView(requestId, c.orElse(null), at);
  }

  /** 센터 재고 스냅샷(SCM·보충 조회, 서비스 호출). 단위 행 그대로 — 예약 R 은 LOT 단위라 줄에 나누지 않는다. */
  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  public List<StockStore.SnapshotRow> snapshot(
      String mfcId,
      @Nullable String sku,
      @Nullable String locationCode,
      @Nullable String cursor,
      int limit) {
    return stock.snapshot(mfcId, sku, locationCode, cursor, limit);
  }
}
