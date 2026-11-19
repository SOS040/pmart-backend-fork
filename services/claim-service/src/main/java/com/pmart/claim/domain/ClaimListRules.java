package com.pmart.claim.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 클레임 목록(0.6.0, 2026-11-19 클라이언트 BR-33 "내 취소·반품·환불 목록") — 커서와 환불 요약 규칙.
 *
 * <ul>
 *   <li>커서 = 마지막 줄의 (openedAt, claimId) — 최근 순 정렬과 같은 순서로 다음 쪽을 읽는다(keyset). 겉은 base64url.
 *   <li>환불 요약: 환불 의도가 없으면 NONE, 끝나지 않은 것이 하나라도 있으면 IN_PROGRESS(PG 결과 전 — 완료로 보이지 않는다), 모두 COMPLETED
 *       면 COMPLETED, 아니면(실패가 섞임) FAILED.
 * </ul>
 */
public final class ClaimListRules {

  private ClaimListRules() {}

  public record Cursor(Instant openedAt, String claimId) {}

  public static String encode(Instant openedAt, String claimId) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((openedAt + "|" + claimId).getBytes(StandardCharsets.UTF_8));
  }

  /** 틀린 커서면 빈 값(호출자가 400). */
  public static Optional<Cursor> decode(@Nullable String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return Optional.empty();
    }
    try {
      String s = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
      int bar = s.indexOf('|');
      if (bar <= 0 || bar == s.length() - 1) {
        return Optional.empty();
      }
      return Optional.of(new Cursor(Instant.parse(s.substring(0, bar)), s.substring(bar + 1)));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      return Optional.empty();
    }
  }

  /** 환불 의도 한 건의 상태(refund_intent.state). */
  public static String refundState(List<String> intentStates) {
    if (intentStates.isEmpty()) {
      return "NONE";
    }
    boolean failed = false;
    for (String s : intentStates) {
      if ("FAILED".equals(s)) {
        failed = true;
      } else if (!"COMPLETED".equals(s)) {
        return "IN_PROGRESS";
      }
    }
    return failed ? "FAILED" : "COMPLETED";
  }
}
