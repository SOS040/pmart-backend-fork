package com.pmart.product.domain;

import org.jspecify.annotations.Nullable;

/** 초안 검증 실패 1건. 초안은 INVALID 로도 저장하고 문제 목록을 그대로 보여 준다. */
public record DraftProblem(String code, @Nullable String field, @Nullable String detail) {

  public static DraftProblem of(String code, String field, String detail) {
    return new DraftProblem(code, field, detail);
  }
}
