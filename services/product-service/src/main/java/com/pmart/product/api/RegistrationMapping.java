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
    m.put("validCount", b.validCount());
    m.put("invalidCount", b.invalidCount());
    m.put("createdAt", b.createdAt().toString());
    ImportBatch out = convert(m, ImportBatch.class);
    return out.drafts(rows.stream().map(this::draft).toList());
  }
}
