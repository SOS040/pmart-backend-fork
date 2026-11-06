package com.pmart.approval.application;

import com.pmart.approval.domain.ApprovalRules;
import com.pmart.approval.infrastructure.ApprovalStore;
import com.pmart.approval.infrastructure.ApprovalStore.ApprovalRow;
import com.pmart.approval.infrastructure.ApprovalStore.ReceiptRow;
import com.pmart.common.messaging.EventEnvelope;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 반영 회신 수신(ADR-0039): ApplicationReceiptRecorded(platform.approval-receipt.v1)를 inbox 트랜잭션 안에서 저장하고
 * 승인의 실행·전달 축을 갱신한다.
 *
 * <ul>
 *   <li>같은 승인·수신자·순번 재전송은 저장하지 않는다(inbox 외에 한 번 더 막는 키).
 *   <li>실행 축은 그 승인의 정본(targetService)이 보낸 가장 큰 순번 회신을 따른다. 늦게 온 작은 순번은 기록만 한다.
 *   <li>모르는 승인의 회신은 버리고 경고만 남긴다(다른 환경·지워진 시험 데이터). DLT 로 보내도 고칠 방법이 없다.
 * </ul>
 */
@Service
public class ApplicationReceiptRecorder {

  public static final String EVENT_TYPE = "ApplicationReceiptRecorded";

  private static final Logger log = LoggerFactory.getLogger(ApplicationReceiptRecorder.class);

  private final ApprovalStore store;
  private final Clock clock;

  public ApplicationReceiptRecorder(ApprovalStore store, Clock clock) {
    this.store = store;
    this.clock = clock;
  }

  public void handle(EventEnvelope envelope) {
    if (!EVENT_TYPE.equals(envelope.eventType())) {
      return;
    }
    JsonNode p = envelope.payload();
    String approvalId = p.path("approvalId").asString();
    String receiver = p.path("receiverService").asString();
    int seq = p.path("receiptSeq").asInt(1);
    String result = p.path("result").asString();
    Optional<ApprovalRow> found = store.findApproval(approvalId);
    if (found.isEmpty()) {
      log.warn("receipt for unknown approval {} from {} — ignored", approvalId, receiver);
      return;
    }
    ApprovalRow approval = found.get();
    boolean saved =
        store.insert(
            new ReceiptRow(
                "RCP-" + UUID.randomUUID(),
                approvalId,
                receiver,
                seq,
                result,
                text(p, "domainRef"),
                text(p, "reason"),
                p.has("lines") && p.get("lines").isArray() ? p.get("lines").toString() : "[]",
                envelope.occurredAt(),
                clock.instant(),
                envelope.eventId().toString()));
    if (!saved) {
      return;
    }
    String latest =
        store.latestReceipt(approvalId, receiver).map(ReceiptRow::result).orElse(result);
    String execution =
        ApprovalRules.executionAfterReceipt(
            approval.reviewState(), approval.targetService(), receiver, latest);
    if (execution != null) {
      // 정본이 결과를 알렸다 = 승인 사건이 정본에 닿아 처리됐다
      store.markExecution(approvalId, execution, "ACKNOWLEDGED");
    }
    log.info(
        "receipt {} #{} from {} → {} (execution {})",
        approvalId,
        seq,
        receiver,
        result,
        execution == null ? approval.executionState() + " unchanged" : execution);
  }

  private static @Nullable String text(JsonNode n, String field) {
    return n.hasNonNull(field) ? n.get(field).asString() : null;
  }
}
