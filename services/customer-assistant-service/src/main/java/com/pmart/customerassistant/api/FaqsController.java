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
    var listing = service.list(PmartPrincipal.current().orElse(null), q, category);
    return ResponseEntity.ok(
        new FaqList()
            .items(listing.items().stream().map(FaqsController::item).toList())
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
    return ResponseEntity.ok(item(service.get(PmartPrincipal.current().orElse(null), faqId)));
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
