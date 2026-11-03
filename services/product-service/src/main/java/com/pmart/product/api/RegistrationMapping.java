package com.pmart.product.api;

import com.pmart.product.api.generated.model.ImportBatch;
import com.pmart.product.api.generated.model.ProductDraft;
import com.pmart.product.application.ProductDraftService;
import com.pmart.product.infrastructure.DraftStore.BatchRow;
import com.pmart.product.infrastructure.DraftStore.DraftRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 저장 행 → 계약 DTO 변환(필드 이름을 계약에 맞춘 Map 을 Jackson 으로 바꾼다). */
@Component
class RegistrationMapping {

  private final ObjectMapper mapper;
  private final ProductDraftService drafts;

  RegistrationMapping(ObjectMapper mapper, ProductDraftService drafts) {
    this.mapper = mapper;
    this.drafts = drafts;
  }

  <T> T convert(Object value, Class<T> type) {
    return mapper.convertValue(value, type);
  }

  ProductDraft draft(DraftRow d) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("draftId", d.draftId());
    m.put("batchId", d.batchId());
    m.put("sourceSystem", d.sourceSystem());
    m.put("sourceKey", d.sourceKey());
    m.put("state", d.state());
    m.put("validationState", d.validationState());
    m.put("problems", drafts.problems(d));
    m.put("input", mapper.readTree(d.input()));
    m.put("resolvedCategoryIds", mapper.readTree(d.resolvedCategories()));
    m.put("draftHash", d.draftHash());
    m.put("approvalId", d.approvalId());
    m.put("resultProductId", d.resultProductId());
    m.put("resultSkus", mapper.readTree(d.resultSkus()));
    // BR-39: 심사 전 초안이 기존 상품을 갱신하는지
    if ("DRAFT".equals(d.state()) || "SUBMITTED".equals(d.state())) {
      var existing = drafts.existingProduct(d);
      m.put("registration", existing.isPresent() ? "UPDATE" : "NEW");
      m.put("existingProductId", existing.map(l -> l.productId()).orElse(null));
      m.put(
          "existingSkus", existing.map(l -> (Object) mapper.readTree(l.skus())).orElse(List.of()));
    }
    m.put("version", d.version());
    m.put("updatedAt", d.updatedAt().toString());
    return convert(m, ProductDraft.class);
  }

  ImportBatch batch(BatchRow b, List<DraftRow> rows) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("batchId", b.batchId());
    m.put("format", b.format());
    m.put("sourceSystem", b.sourceSystem());
    m.put("state", b.state());
    m.put("productCount", b.productCount());
    // BR-38: 저장된 수는 업로드 때 값이라 행을 고친(PUT) 뒤 맞지 않는다 — 현재 행의 검증 결과로 센다
    long valid = rows.stream().filter(r -> "VALID".equals(r.validationState())).count();
    m.put("validCount", rows.isEmpty() ? b.validCount() : (int) valid);
    m.put("invalidCount", rows.isEmpty() ? b.invalidCount() : rows.size() - (int) valid);
    m.put("createdAt", b.createdAt().toString());
    ImportBatch out = convert(m, ImportBatch.class);
    return out.drafts(rows.stream().map(this::draft).toList());
  }
}
