package com.pmart.customerassistant.api;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.customerassistant.api.generated.FaqsApi;
import com.pmart.customerassistant.api.generated.model.FaqCategory;
import com.pmart.customerassistant.api.generated.model.FaqItem;
import com.pmart.customerassistant.api.generated.model.FaqList;
import com.pmart.customerassistant.application.FaqService;
import com.pmart.customerassistant.infrastructure.FaqStore.FaqRow;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 FaqsApi 구현(0.2.0, 2026-11-18). 변환만 — 규칙은 {@link FaqService}. */
@RestController
public class FaqsController implements FaqsApi {

  private final FaqService service;

  public FaqsController(FaqService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<FaqList> listCustomerFaqs(@Nullable String q, @Nullable String category) {
    PmartPrincipal who = PmartPrincipal.current().orElse(null);
    var listing = service.list(who, q, category);
    var mine = service.myFeedback(who, listing.items().stream().map(FaqRow::faqId).toList());
    return ResponseEntity.ok(
        new FaqList()
            .items(
                listing.items().stream()
                    .map(r -> item(r).myFeedback(mine.get(r.faqId()))) // 0.4.0 BR-91
                    .toList())
            .categories(
                listing.categories().stream()
                    .map(
                        c ->
                            new FaqCategory()
                                .category(c.category())
                                .title(c.title())
                                .count(c.count()))
                    .toList()));
  }

  @Override
  public ResponseEntity<FaqItem> getCustomerFaq(String faqId) {
    PmartPrincipal who = PmartPrincipal.current().orElse(null);
    FaqRow r = service.get(who, faqId);
    return ResponseEntity.ok(
        item(r).myFeedback(service.myFeedback(who, java.util.List.of(faqId)).get(faqId)));
  }

  /** 0.4.0 BR-91 — FAQ 답 의견(마지막 값, 재전송 안전). */
  @Override
  public ResponseEntity<com.pmart.customerassistant.api.generated.model.SendFaqFeedback200Response>
      sendFaqFeedback(
          String faqId,
          String idempotencyKey,
          String xSourceSystem,
          com.pmart.customerassistant.api.generated.model.SendFaqFeedbackRequest body) {
    boolean v =
        service.sendFeedback(
            PmartPrincipal.current().orElse(null), faqId, Boolean.TRUE.equals(body.getHelpful()));
    return ResponseEntity.ok(
        new com.pmart.customerassistant.api.generated.model.SendFaqFeedback200Response()
            .faqId(faqId)
            .helpful(v));
  }

  /** 0.4.0 BR-91 — 운영 집계. */
  @Override
  public ResponseEntity<
          com.pmart.customerassistant.api.generated.model.ListFaqFeedbackStats200Response>
      listFaqFeedbackStats() {
    return ResponseEntity.ok(
        new com.pmart.customerassistant.api.generated.model.ListFaqFeedbackStats200Response()
            .items(
                service.feedbackStats(PmartPrincipal.current().orElse(null)).stream()
                    .map(
                        x ->
                            new com.pmart.customerassistant.api.generated.model
                                    .ListFaqFeedbackStats200ResponseItemsInner()
                                .faqId(x.faqId())
                                .category(x.category())
                                .question(x.question())
                                .helpfulCount(x.helpful())
                                .notHelpfulCount(x.notHelpful())
                                .lastAt(
                                    java.time.OffsetDateTime.ofInstant(
                                        x.lastAt(), java.time.ZoneOffset.UTC)))
                    .toList()));
  }

  private static FaqItem item(FaqRow r) {
    return new FaqItem()
        .faqId(r.faqId())
        .category(r.category())
        .categoryTitle(r.categoryTitle())
        .question(r.question())
        .answer(r.answer());
  }
}
