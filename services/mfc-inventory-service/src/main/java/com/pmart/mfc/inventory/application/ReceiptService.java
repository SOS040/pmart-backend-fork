package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.web.CommandResponse;
import com.pmart.common.web.CommandStatus;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.inventory.domain.StockRules;
import com.pmart.mfc.inventory.domain.StockRules.ReceiptDelta;
import com.pmart.mfc.inventory.domain.StockRules.ReceiptLine;
import com.pmart.mfc.inventory.infrastructure.StockStore;
import com.pmart.mfc.inventory.infrastructure.StockStore.CommitRow;
import com.pmart.mfc.inventory.infrastructure.StockStore.EntryRow;
import com.pmart.mfc.inventory.infrastructure.StockStore.ReceiptRow;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * ConfirmReceipt(TIN 전기). 같은 receiptId 는 한 번만 계상한다: {@code stock_receipt} 유일 키로 선점하고, 같은 receiptId
 * 가 다시 오면 내용 지문이 같을 때 처음 commitId 를 돌려준다(Idempotency-Key 가 달라도). 검수 단계(INSPECT)가 아직 없어 정상 수량은 바로 E
 * 가 된다(계약 설명 참조). MfcReceiptPosted 는 TRF 경로 구현 때 발행한다.
 */
@Service
public class ReceiptService {

  public static final String RECEIPT_ALREADY_POSTED = "RECEIPT_ALREADY_POSTED";
  static final String OP = "confirmReceipt";

  private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);

  /** 수령 명령. */
  public record ReceiptCommand(
      String receiptId,
      String trfId,
      String departureId,
      String mfcId,
      @Nullable Instant receivedAt,
      List<ReceiptLine> lines) {}

  private final StockStore stock;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public ReceiptService(StockStore stock, RequestIdempotency idempotency, Clock clock) {
    this.stock = stock;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  public CommandResponse confirm(String requestId, String sourceSystem, ReceiptCommand cmd) {
    List<ReceiptDelta> deltas;
    try {
      deltas = StockRules.receiptDeltas(cmd.lines());
    } catch (IllegalArgumentException | ArithmeticException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, e.getMessage());
    }
    if (deltas.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "수령 수량 합이 0 입니다.");
    }
    String fingerprint =
        StockRules.receiptFingerprint(cmd.mfcId(), cmd.trfId(), cmd.departureId(), cmd.lines());
    return idempotency
        .execute(
            Callers.key(sourceSystem, requestId, OP),
            cmd,
            CommandResponse.class,
            () -> {
              Instant at = clock.instant();
              int total = deltas.stream().mapToInt(ReceiptDelta::onHand).sum();
              String commitId = "CMT-" + UUID.randomUUID();
              if (!stock.claimReceipt(
                  cmd.receiptId(), cmd.mfcId(), fingerprint, commitId, total, at)) {
                return already(requestId, cmd.receiptId(), fingerprint);
              }
              stock.insertCommit(
                  new CommitRow(
                      commitId,
                      "TIN",
                      cmd.mfcId(),
                      requestId,
                      sourceSystem,
                      cmd.receiptId(),
                      total,
                      at));
              for (ReceiptDelta d : deltas) {
                long v =
                    stock.addReceipt(
                        cmd.mfcId(), d.sku(), d.onHand(), d.quarantined(), d.sellable(), at);
                stock.insertEntry(commitId, cmd.mfcId(), new EntryRow(d.sku(), "H", d.onHand(), v));
                if (d.sellable() > 0) {
                  stock.insertEntry(
                      commitId, cmd.mfcId(), new EntryRow(d.sku(), "E", d.sellable(), v));
                }
                if (d.quarantined() > 0) {
                  stock.insertEntry(
                      commitId, cmd.mfcId(), new EntryRow(d.sku(), "Q", d.quarantined(), v));
                }
              }
              log.info(
                  "receipt {} posted to {} as {} ({} units)",
                  cmd.receiptId(),
                  cmd.mfcId(),
                  commitId,
                  total);
              return new CommandResponse(
                  CommandStatus.APPLIED, requestId, commitId, total, null, false, null, at);
            })
        .value();
  }

  /** 같은 receiptId 가 이미 전기됨: 같은 내용이면 처음 commitId, 다르면 409. */
  private CommandResponse already(String requestId, String receiptId, String fingerprint) {
    Optional<ReceiptRow> row = stock.receipt(receiptId);
    if (row.isEmpty() || !row.get().contentHash().equals(fingerprint)) {
      throw new PmartException(
          RECEIPT_ALREADY_POSTED,
          HttpStatus.CONFLICT,
          "receiptId " + receiptId + " 는 다른 내용으로 이미 전기되었습니다.",
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
}
