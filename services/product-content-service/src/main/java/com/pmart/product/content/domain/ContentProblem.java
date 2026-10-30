package com.pmart.product.content.domain;

import org.jspecify.annotations.Nullable;

/** 제출을 막는 사유 또는 반려 사유(계약 ContentDraftProblem). */
public record ContentProblem(String code, @Nullable String field, @Nullable String detail) {

  public static final String NAME_REQUIRED = "NAME_REQUIRED";
  public static final String MAIN_IMAGE_REQUIRED = "MAIN_IMAGE_REQUIRED";
  public static final String PRODUCT_NOT_LINKED = "PRODUCT_NOT_LINKED";
  public static final String APPROVAL_REJECTED = "APPROVAL_REJECTED";
  public static final String APPROVAL_SCOPE_MISMATCH = "APPROVAL_SCOPE_MISMATCH";
}
