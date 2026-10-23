package com.pmart.common.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** 커서 기반 목록 응답 (contracts/_common.yaml Page). */
public record PageResponse<T>(List<T> items, @Nullable String nextCursor, int size) {

  public PageResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public static <T> PageResponse<T> of(List<T> items, @Nullable String nextCursor) {
    return new PageResponse<>(items, nextCursor, items.size());
  }
}
