package com.pmart.product.content.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.product.content.infrastructure.DraftStore;
import com.pmart.product.content.infrastructure.DraftStore.SummaryRow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 콘텐츠 초안 목록·상태별 수(0.4.0 PCT-1 CD-1·CD-2 — 백오피스 콘텐츠 조회, 2026-11-20). 운영자만(초안 만들기와 같은 권한). 최근 바뀐
 * 순(updatedAt·draftId 내림차순) keyset 커서.
 */
@Service
public class ContentDraftListing {

  /** 계약 순서 그대로 — 수는 모든 상태를 0 까지. */
  public static final List<String> STATES = List.of("DRAFT", "SUBMITTED", "PUBLISHED", "REJECTED");

  public record Page(List<SummaryRow> rows, @Nullable String nextCursor) {}

  private final DraftStore store;

  public ContentDraftListing(DraftStore store) {
    this.store = store;
  }

  public Page list(
      @Nullable List<String> states,
      @Nullable String productId,
      int size,
      @Nullable String cursor) {
    Operator.require();
    List<String> wanted = new ArrayList<>();
    if (states != null) {
      for (String raw : states) {
        for (String s : raw.split(",")) {
          String v = s.strip();
          if (v.isEmpty()) {
            continue;
          }
          if (!STATES.contains(v)) {
            throw new PmartException(
                ErrorCode.VALIDATION_FAILED,
                "모르는 초안 상태입니다: " + v,
                List.of(
                    new PmartException.Detail("state", "DRAFT·SUBMITTED·PUBLISHED·REJECTED 중 하나")));
          }
          if (!wanted.contains(v)) {
            wanted.add(v);
          }
        }
      }
    }
    Instant afterAt = null;
    String afterId = null;
    if (cursor != null && !cursor.isBlank()) {
      try {
        String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        int bar = decoded.indexOf('|');
        afterAt = Instant.parse(decoded.substring(0, bar));
        afterId = decoded.substring(bar + 1);
      } catch (RuntimeException e) {
        throw new PmartException(ErrorCode.VALIDATION_FAILED, "커서가 올바르지 않습니다.");
      }
    }
    int limit = Math.max(1, Math.min(size, 200));
    List<SummaryRow> rows = store.summaries(wanted, productId, afterAt, afterId, limit + 1);
    String next = null;
    if (rows.size() > limit) {
      rows = rows.subList(0, limit);
      SummaryRow last = rows.get(limit - 1);
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (last.updatedAt() + "|" + last.draftId()).getBytes(StandardCharsets.UTF_8));
    }
    return new Page(List.copyOf(rows), next);
  }

  /** 상태별 수 — 모든 상태 0 까지, 계약 순서. */
  public Map<String, Long> counts(@Nullable String productId) {
    Operator.require();
    Map<String, Long> raw = store.countByState(productId);
    Map<String, Long> out = new LinkedHashMap<>();
    STATES.forEach(s -> out.put(s, raw.getOrDefault(s, 0L)));
    return out;
  }
}
