package com.pmart.product.content.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 콘텐츠 초안 입력(계약 ContentDraftInput, seed-v1 content 블록). 고객용 명칭·설명·안내·옵션만 담는다 — 가격·재고·판매 상태는 다른 정본의
 * 것이라 넣지 않는다.
 */
public record ContentInput(
    @Nullable String sourceSystem,
    @Nullable String sourceKey,
    @Nullable String productId,
    @Nullable String name,
    @Nullable String description,
    @Nullable String storageGuide,
    @Nullable String returnGuide,
    @Nullable List<String> notices,
    @Nullable List<Option> options) {

  /** 판매 옵션 라벨(실제 구매 단위는 product 의 sku). */
  public record Option(String label, @Nullable String capacityLabel) {}

  public List<String> noticesOrEmpty() {
    return notices == null ? List.of() : notices;
  }

  public List<Option> optionsOrEmpty() {
    return options == null ? List.of() : options;
  }

  public boolean hasSource() {
    return sourceSystem != null
        && !sourceSystem.isBlank()
        && sourceKey != null
        && !sourceKey.isBlank();
  }
}
