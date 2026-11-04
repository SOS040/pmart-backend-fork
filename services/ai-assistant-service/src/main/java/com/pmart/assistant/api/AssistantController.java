package com.pmart.assistant.api;

import com.pmart.assistant.api.generated.AssistantApi;
import com.pmart.assistant.api.generated.model.AnswerMode;
import com.pmart.assistant.api.generated.model.AnswerState;
import com.pmart.assistant.api.generated.model.Authority;
import com.pmart.assistant.api.generated.model.Citation;
import com.pmart.assistant.api.generated.model.CitationAsOf;
import com.pmart.assistant.api.generated.model.CitationImplementationEvidence;
import com.pmart.assistant.api.generated.model.LifecycleStatus;
import com.pmart.assistant.api.generated.model.PolicyAnswer;
import com.pmart.assistant.api.generated.model.PolicyAnswerModel;
import com.pmart.assistant.api.generated.model.PolicyQuestion;
import com.pmart.assistant.api.generated.model.RetrievalMode;
import com.pmart.assistant.application.AssistantProperties;
import com.pmart.assistant.application.CitationRecord;
import com.pmart.assistant.application.PolicyQuestionService;
import com.pmart.assistant.application.PolicyQuestionService.Outcome;
import com.pmart.common.security.PmartPrincipal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 AssistantApi 구현. 요청·응답 변환만 — 선점·권한·검색 규칙은 application 에 있다. */
@RestController
public class AssistantController implements AssistantApi {

  private final PolicyQuestionService service;
  private final int retryAfterSeconds;

  public AssistantController(PolicyQuestionService service, AssistantProperties props) {
    this.service = service;
    this.retryAfterSeconds = props.getProcessing().getRetryAfterSeconds();
  }

  @Override
  public ResponseEntity<PolicyAnswer> askPolicyQuestion(
      String idempotencyKey, String xSourceSystem, PolicyQuestion body) {
    var ctx = body.getContext();
    return respond(
        service.ask(
            PmartPrincipal.current().orElse(null),
            xSourceSystem,
            idempotencyKey,
            body.getQuestion(),
            ctx == null ? null : ctx.getScreen(),
            ctx == null ? null : ctx.getCenterId()));
  }

  @Override
  public ResponseEntity<PolicyAnswer> findPolicyAnswerByRequestId(
      String xSourceSystem, String requestId) {
    return respond(
        service.findByRequestId(PmartPrincipal.current().orElse(null), xSourceSystem, requestId));
  }

  @Override
  public ResponseEntity<PolicyAnswer> getPolicyAnswer(String answerId) {
    return respond(service.getAnswer(PmartPrincipal.current().orElse(null), answerId));
  }

  private ResponseEntity<PolicyAnswer> respond(Outcome outcome) {
    PolicyAnswer dto = toDto(outcome);
    if (outcome instanceof Outcome.Processing) {
      return ResponseEntity.status(HttpStatus.ACCEPTED)
          .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
          .body(dto);
    }
    return ResponseEntity.ok(dto);
  }

  static PolicyAnswer toDto(Outcome outcome) {
    var row = outcome.row();
    boolean processing = outcome instanceof Outcome.Processing;
    PolicyAnswer dto =
        new PolicyAnswer()
            .answerId(row.answerId())
            .requestId(row.requestId())
            .state(processing ? AnswerState.PROCESSING : AnswerState.fromValue(row.resultState()))
            .answer(processing || row.answer() == null ? "" : row.answer())
            .citations(
                processing
                    ? List.of()
                    : outcome.citations().stream().map(AssistantController::citation).toList())
            .answerMode(
                processing || row.answerMode() == null
                    ? AnswerMode.NONE
                    : AnswerMode.fromValue(row.answerMode()))
            .retrievalMode(
                processing || row.retrievalMode() == null
                    ? RetrievalMode.NONE
                    : RetrievalMode.fromValue(row.retrievalMode()))
            .indexSnapshotId(processing ? null : row.snapshotId())
            .askedAt(odt(row.createdAt()));
    if (!processing) {
      dto.model(new PolicyAnswerModel().chat(row.chatModel()).embedding(row.embeddingModel()));
    }
    return dto;
  }

  static Citation citation(CitationRecord c) {
    return new Citation()
        .chunkId(c.chunkId())
        .documentId(c.documentId())
        .path(c.path())
        .section(c.section())
        .authority(Authority.fromValue(c.authority()))
        .lifecycleStatus(LifecycleStatus.fromValue(c.lifecycleStatus()))
        .supersededBy(c.supersededBy())
        .proposedSupersededBy(c.proposedSupersededBy())
        .implementationEvidence(
            c.implRecordDate() == null
                ? null
                : new CitationImplementationEvidence()
                    .recordDate(c.implRecordDate())
                    .note(c.implNote()))
        .documentVersion(c.documentVersion())
        .effectiveDate(c.effectiveDate())
        .asOf(
            new CitationAsOf()
                .snapshotId(c.snapshotId())
                .sourceRevision(c.sourceRevision())
                .sourceDirty(c.sourceDirty())
                .indexedAt(odt(Instant.parse(c.indexedAt()))))
        .excerpt(c.excerpt());
  }

  private static OffsetDateTime odt(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
