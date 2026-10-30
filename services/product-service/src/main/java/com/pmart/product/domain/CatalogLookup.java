package com.pmart.product.domain;

import java.util.Optional;

/** 초안 검증이 기존 정본을 조회하는 창구(발효 카테고리 트리·활성 바코드·기초상품). */
public interface CatalogLookup {

  /** 발효 트리에 이 노드가 있는가. */
  boolean categoryExists(String categoryId);

  /** 이름 경로("상위 > 하위 …")와 정확히 같은 노드. 앞뒤·구분자 주변 공백은 무시한다. */
  Optional<String> categoryByPath(String path);

  /** 그 필터 그룹에 이 옵션이 있는가. */
  boolean filterOptionExists(String filterGroupId, String option);

  /** 이 바코드가 활성 상태로 연결된 SKU(없으면 비어 있음). */
  Optional<String> activeBarcodeOwner(String barcode);

  boolean baseProductExists(String baseProductId);
}
