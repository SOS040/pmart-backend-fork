package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.StockRules;
import com.pmart.mfc.inventory.domain.StockUnit.Change;
import com.pmart.mfc.inventory.domain.StockUnit.Key;
import com.pmart.mfc.inventory.domain.StockUnit.Quality;
import com.pmart.mfc.inventory.domain.StockUnit.Work;
import com.pmart.mfc.inventory.infrastructure.SkuProjectionStore;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.LotRow;
import com.pmart.mfc.inventory.infrastructure.StockStore.ReceiptRow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * CommitTaskExecution(IV-02·IV-03·IV-13, ADR-0040 결정 3). 현장 실행 사실을 단위 행 이동으로 반영한다.
 *
 * <ul>
 *   <li>INSPECT: 같은 위치에서 PENDING_INSPECTION/UNSTOWED → NORMAL 또는 QUARANTINED(UNSTOWED). 격리면 Q+q. E
 *       변화 없음.
 *   <li>PUTAWAY: fromLocation 의 NORMAL/UNSTOWED → toLocation 의 NORMAL/STORED. E+q(이때 처음 판매 가능).
 *   <li>PICK·PACK·MOVE 는 6b 이후(UNSUPPORTED_ACTION).
 *   <li>같은 executionId 는 요청 ID 가 달라도 한 번만(내용 지문이 같으면 처음 commitId, 다르면 409).
 * </ul>
 *
 * 위치는 mfc-location 투영으로 검사한다({@link Locations}).
 */
@Service
public class TaskExecutionService {

  public static final String EXECUTION_ALREADY_POSTED = "EXECUTION_ALREADY_POSTED";
  static final String OP = "commitTaskExecution";

  private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

  public enum Type {
    INSPECT,
    PUTAWAY,
    PICK,
    PACK,
    MOVE
  }

  /** 실행 줄. resultQuality 는 INSPECT 전용. */
  public record ExecLine(
      String sku,
      String lotId,
      int qty,
      @Nullable Quality resultQuality,
      @Nullable String sourceLotRef,
      @Nullable LocalDate expiryDate,
      @Nullable LocalDate manufactureDate) {

    public ExecLine(String sku, String lotId, int qty, @Nullable Quality resultQuality) {
      this(sku, lotId, qty, resultQuality, null, null, null);
    }

    /** INSPECT 에서 LOT 을 나누는가(제조 LOT 표기·소비기한·제조일을 검수에서 확인). */
    boolean splitsLot() {
      return sourceLotRef != null || expiryDate != null || manufactureDate != null;
    }
  }

  public record ExecCommand(
      Type type,
      String mfcId,
      String taskId,
      String executionId,
      @Nullable String fromLocation,
      @Nullable String toLocation,
      List<ExecLine> lines) {}

  private final StockStore stock;
  private final Ledger ledger;
  private final Locations locations;
  private final SkuProjectionStore skus;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public TaskExecutionService(
      StockStore stock,
      Ledger ledger,
      Locations locations,
      SkuProjectionStore skus,
      RequestIdempotency idempotency,
      Clock clock) {
    this.stock = stock;
    this.ledger = ledger;
    this.locations = locations;
    this.skus = skus;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public CommandResponse commit(String requestId, String sourceSystem, ExecCommand cmd) {
    validate(cmd);
    int total = cmd.lines().stream().mapToInt(ExecLine::qty).sum();
    String fingerprint = fingerprint(cmd);
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, OP),
            cmd,
            CommandResponse.class,
            () -> {
              Instant at = clock.instant();
              checkLots(cmd);
              checkLocations(cmd);
              String commitId = Ledger.newCommitId();
              if (!stock.claimExecution(
                  cmd.executionId(),
                  cmd.mfcId(),
                  cmd.type().name(),
                  fingerprint,
                  commitId,
                  total,
                  at)) {
                return already(requestId, cmd.executionId(), fingerprint);
              }
              ledger.postAs(
                  commitId,
                  "TASK_EXECUTION",
                  cmd.mfcId(),
                  requestId,
                  sourceSystem,
                  cmd.taskId(),
                  total,
                  changes(cmd, at),
                  List.of(),
                  at);
              log.info(
                  "{} {} (task {}) posted to {} as {} ({} units)",
                  cmd.type(),
                  cmd.executionId(),
                  cmd.taskId(),
                  cmd.mfcId(),
                  commitId,
                  total);
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, commitId, total, null, false, null, at);
            })
        .value();
  }

  private static void validate(ExecCommand cmd) {
    if (cmd.type() == Type.PICK || cmd.type() == Type.PACK || cmd.type() == Type.MOVE) {
      throw new PmartException(
          ErrorCode.UNSUPPORTED_ACTION, cmd.type() + " 반영은 아직 구현하지 않았습니다(6b 이후).");
    }
    if (cmd.executionId() == null || cmd.executionId().isBlank()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "executionId 가 필요합니다.");
    }
    if (blank(cmd.fromLocation())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "fromLocation 이 필요합니다.");
    }
    if (cmd.type() == Type.PUTAWAY && blank(cmd.toLocation())) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "PUTAWAY 는 toLocation 이 필요합니다.");
    }
    for (ExecLine l : cmd.lines()) {
      if (l.qty() < 1) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "수량은 1 이상이어야 합니다: " + l.sku());
      }
      if (blank(l.lotId())) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "lotId 가 필요합니다: " + l.sku());
      }
      if (cmd.type() == Type.INSPECT
          && l.resultQuality() != Quality.NORMAL
          && l.resultQuality() != Quality.QUARANTINED) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            "INSPECT 는 resultQuality NORMAL/QUARANTINED 가 필요합니다: " + l.sku());
      }
    }
  }

  /** 출발 위치 실사 잠금 아님, 적치 목적지는 등록·ACTIVE·선반 구역·온도대 = SKU 보관 유형. */
  private void checkLocations(ExecCommand cmd) {
    locations.requireNotCountLocked(cmd.mfcId(), cmd.fromLocation());
    if (cmd.type() == Type.PUTAWAY) {
      var projections =
          skus.findAll(cmd.lines().stream().map(ExecLine::sku).collect(Collectors.toSet()));
      for (String sku : cmd.lines().stream().map(ExecLine::sku).distinct().toList()) {
        var p = projections.get(sku);
        locations.requireStorage(cmd.mfcId(), cmd.toLocation(), p == null ? null : p.storageType());
      }
    }
  }

  /** 줄의 lotId 가 그 SKU 의 LOT 인지. */
  private void checkLots(ExecCommand cmd) {
    Map<String, LotRow> lots =
        stock.lots(cmd.lines().stream().map(ExecLine::lotId).collect(Collectors.toSet()));
    for (ExecLine l : cmd.lines()) {
      LotRow lot = lots.get(l.lotId());
      if (lot == null || !lot.sku().equals(l.sku())) {
        throw new PmartException(
            ErrorCode.VALIDATION_FAILED,
            "lotId " + l.lotId() + " 는 SKU " + l.sku() + " 의 LOT 이 아닙니다.");
      }
    }
  }

  private List<Change> changes(ExecCommand cmd, Instant at) {
    List<Change> out = new ArrayList<>();
    for (ExecLine l : cmd.lines()) {
      switch (cmd.type()) {
        case INSPECT -> {
          Key from =
              new Key(
                  cmd.fromLocation(),
                  l.sku(),
                  l.lotId(),
                  Quality.PENDING_INSPECTION,
                  Work.UNSTOWED);
          out.add(new Change(from, -l.qty()));
          // LOT 분리: 검수에서 확인한 제조 LOT 표기·소비기한의 추적 LOT 으로 옮긴다(같은 위치, 총량 불변)
          String target =
              l.splitsLot()
                  ? stock.trackedLot(
                      l.sku(),
                      l.sourceLotRef(),
                      l.expiryDate(),
                      l.manufactureDate(),
                      "LOT-" + java.util.UUID.randomUUID(),
                      cmd.executionId(),
                      at)
                  : l.lotId();
          Key to = new Key(cmd.fromLocation(), l.sku(), target, l.resultQuality(), Work.UNSTOWED);
          out.add(new Change(to, l.qty()));
        }
        case PUTAWAY -> {
          Key from = new Key(cmd.fromLocation(), l.sku(), l.lotId(), Quality.NORMAL, Work.UNSTOWED);
          out.add(new Change(from, -l.qty()));
          out.add(new Change(from.at(cmd.toLocation(), Work.STORED), l.qty()));
        }
        default -> throw new IllegalStateException(cmd.type().name());
      }
    }
    return out;
  }

  private static String fingerprint(ExecCommand cmd) {
    StringBuilder sb = new StringBuilder();
    sb.append(cmd.type())
        .append('|')
        .append(cmd.mfcId())
        .append('|')
        .append(cmd.taskId())
        .append('|')
        .append(cmd.fromLocation())
        .append('|')
        .append(cmd.toLocation());
    for (ExecLine l : cmd.lines()) {
      sb.append('|')
          .append(l.sku())
          .append(':')
          .append(l.lotId())
          .append(':')
          .append(l.qty())
          .append(':')
          .append(l.resultQuality())
          .append(':')
          .append(l.sourceLotRef())
          .append(':')
          .append(l.expiryDate())
          .append(':')
          .append(l.manufactureDate());
    }
    return StockRules.sha256(sb.toString());
  }

  private CommandResponse already(String requestId, String executionId, String fingerprint) {
    Optional<ReceiptRow> row = stock.execution(executionId);
    if (row.isEmpty() || !row.get().contentHash().equals(fingerprint)) {
      throw new PmartException(
          EXECUTION_ALREADY_POSTED,
          HttpStatus.CONFLICT,
          "executionId " + executionId + " 는 다른 내용으로 이미 반영되었습니다.",
          false,
          List.of());
    }
    return new CommandResponse(
        CommandStatus.APPLIED,
        requestId,
        row.get().commitId(),
        row.get().appliedQty(),
        null,
        false,
        null,
        clock.instant());
  }

  private static boolean blank(@Nullable String s) {
    return s == null || s.isBlank();
  }
}
