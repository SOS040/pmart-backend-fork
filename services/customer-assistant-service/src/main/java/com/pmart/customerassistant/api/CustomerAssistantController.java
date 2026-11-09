package com.pmart.customerassistant.api;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.customerassistant.api.generated.CustomerAssistantApi;
import com.pmart.customerassistant.api.generated.model.AnswerMode;
import com.pmart.customerassistant.api.generated.model.Citation;
import com.pmart.customerassistant.api.generated.model.CustomerAnswer;
import com.pmart.customerassistant.api.generated.model.CustomerAnswerState;
import com.pmart.customerassistant.api.generated.model.CustomerQuestion;
import com.pmart.customerassistant.api.generated.model.Handoff;
import com.pmart.customerassistant.api.generated.model.ListRecentOrdersForChat200Response;
import com.pmart.customerassistant.api.generated.model.OrderBrief;
import com.pmart.customerassistant.api.generated.model.OrderBriefArrivalWindow;
import com.pmart.customerassistant.application.AssistantProperties;
import com.pmart.customerassistant.application.CitationRecord;
import com.pmart.customerassistant.application.CustomerQuestionService;
import com.pmart.customerassistant.application.CustomerQuestionService.Outcome;
import com.pmart.customerassistant.domain.ExtractiveComposer;
import com.pmart.customerassistant.infrastructure.OrderClient.OrderView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CustomerAssistantApi 구현. 요청·응답 변환과 고객센터 문의 연결(handoff) 문구만 — 규칙은 application 에 있다. */
@RestController
public class CustomerAssistantController implements CustomerAssistantApi {

  static final String BUTTON = "고객센터에 문의 남기기";

  /** C4 — 문구 정본 docs/business/customer-faq/README.md "근거가 없을 때". 답변 알림이 없어 "내 문의"에서 확인하게 한다. */
  static final String HANDOFF_NO_GROUNDING =
      "고객센터에 문의를 남겨 주시면 담당자가 확인한 뒤 답변드릴게요. 답변은 고객센터의 내 문의에서 확인할 수 있어요.";

  static final String HANDOFF_MORE = "더 궁금한 점이 있으면 고객센터에 문의를 남겨 주세요.";

  private final CustomerQuestionService service;
  private final int retryAfterSeconds;

  public CustomerAssistantController(CustomerQuestionService service, AssistantProperties props) {
    this.service = service;
    this.retryAfterSeconds = props.getProcessing().getRetryAfterSeconds();
  }

  @Override
  public ResponseEntity<CustomerAnswer> askCustomerQuestion(
      String idempotencyKey, String xSourceSystem, CustomerQuestion body) {
    return respond(
        service.ask(
            PmartPrincipal.current().orElse(null),
            xSourceSystem,
            idempotencyKey,
            body.getQuestion(),
            body.getOrderId()));
  }

  @Override
  public ResponseEntity<CustomerAnswer> findCustomerAnswerByRequestId(
      String xSourceSystem, String requestId) {
    return respond(
        service.findByRequestId(PmartPrincipal.current().orElse(null), xSourceSystem, requestId));
  }

  @Override
  public ResponseEntity<CustomerAnswer> getCustomerAnswer(String answerId) {
    return respond(service.getAnswer(PmartPrincipal.current().orElse(null), answerId));
  }

  @Override
  public ResponseEntity<ListRecentOrdersForChat200Response> listRecentOrdersForChat(
      @Nullable Integer size) {
    var items =
        service.recentOrders(PmartPrincipal.current().orElse(null), size == null ? 5 : size);
    return ResponseEntity.ok(
        new ListRecentOrdersForChat200Response()
            .items(items.stream().map(CustomerAssistantController::brief).toList()));
  }

  private ResponseEntity<CustomerAnswer> respond(Outcome outcome) {
    CustomerAnswer dto = toDto(outcome);
    if (outcome instanceof Outcome.Processing) {
      return ResponseEntity.status(HttpStatus.ACCEPTED)
          .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
          .body(dto);
    }
    return ResponseEntity.ok(dto);
  }

  private CustomerAnswer toDto(Outcome outcome) {
    var row = outcome.row();
    boolean processing = outcome instanceof Outcome.Processing;
    CustomerAnswerState state =
        processing
            ? CustomerAnswerState.PROCESSING
            : CustomerAnswerState.fromValue(row.resultState());
    CustomerAnswer dto =
        new CustomerAnswer()
            .answerId(row.answerId())
            .requestId(row.requestId())
            .state(state)
            .answer(processing || row.answer() == null ? "" : row.answer())
            .citations(
                processing
                    ? List.of()
                    : outcome.citations().stream()
                        .map(CustomerAssistantController::citation)
                        .toList())
            .answerMode(
                processing || row.answerMode() == null
                    ? AnswerMode.NONE
                    : AnswerMode.fromValue(row.answerMode()))
            .orderId(row.orderId())
            .recentOrders(List.of())
            .askedAt(odt(row.createdAt()));
    if (processing) {
      return dto;
    }
    service.currentOrder(row.orderId()).ifPresent(o -> dto.order(brief(o)));
    switch (state) {
      case REALTIME_DATA_REQUIRED ->
          dto.recentOrders(
              service.recentForAnswer().stream().map(CustomerAssistantController::brief).toList());
      case NO_GROUNDING, OUT_OF_SCOPE -> dto.handoff(handoff(HANDOFF_NO_GROUNDING, row.orderId()));
      case ORDER_STATUS -> dto.handoff(handoff(HANDOFF_MORE, row.orderId()));
      default -> {}
    }
    return dto;
  }

  private static Handoff handoff(String message, @Nullable String orderId) {
    return new Handoff()
        .target(Handoff.TargetEnum.INQUIRY)
        .message(message)
        .buttonLabel(BUTTON)
        .orderId(orderId);
  }

  static OrderBrief brief(OrderView o) {
    OrderBrief b =
        new OrderBrief()
            .orderId(o.orderId())
            .status(o.status())
            .statusLabel(o.statusLabel())
            .orderedAt(o.orderedAt() == null ? null : odt(o.orderedAt()))
            .itemCount(o.itemCount())
            .totalAmount(o.totalAmount() == null ? null : Math.toIntExact(o.totalAmount()));
    if (o.window() != null) {
      b.arrivalWindow(
          new OrderBriefArrivalWindow()
              .date(o.window().date())
              .start(o.window().start())
              .end(o.window().end()));
    }
    return b;
  }

  /** 절 경로 "문서 제목 > 절 제목" → 제목·절. 지위 태그는 뺀다. */
  static Citation citation(CitationRecord c) {
    String section = c.section() == null ? "" : ExtractiveComposer.stripTags(c.section());
    String[] parts = section.split("\\s*>\\s*", 2);
    String title = parts[0].isBlank() ? c.documentId() : parts[0].strip();
    String sub = parts.length > 1 ? parts[1].strip() : null;
    return new Citation()
        .documentId(c.documentId())
        .title(title)
        .section(sub)
        .excerpt(ExtractiveComposer.stripTags(c.excerpt()).strip());
  }

  private static OffsetDateTime odt(Instant i) {
    return i.atOffset(ZoneOffset.UTC);
  }
}
