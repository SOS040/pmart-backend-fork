package com.pmart.customerassistant.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.customerassistant.infrastructure.FaqStore;
import com.pmart.customerassistant.infrastructure.FaqStore.CategoryRow;
import com.pmart.customerassistant.infrastructure.FaqStore.FaqRow;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 고객앱 FAQ 목록·검색(2026-11-18 사용자 "A해줘", 계약 0.2.0). AI 상담이 근거로 쓰는 같은 ACTIVE 스냅숏의 안내 문서 절을 그대로 보여 준다 —
 * 모델·임베딩을 부르지 않는다(검색 = 질문·답변 부분 문자열, 대소문자 무시). 요청 제한(C3)에 세지 않는다. 검색어는 저장·로그하지 않는다(C5 와 같은 취지).
 *
 * <p>누가: 다른 고객용 읽기 API(최근 주문·답변 다시 보기)와 같이 로그인한 회원 고객만(C2) — 비회원·운영자 403.
 */
@Service
public class FaqService {

  public record Listing(List<FaqRow> items, List<CategoryRow> categories) {}

  private final FaqStore store;
  private final com.pmart.customerassistant.infrastructure.FaqFeedbackStore feedback;
  private final java.time.Clock clock;

  public FaqService(
      FaqStore store,
      com.pmart.customerassistant.infrastructure.FaqFeedbackStore feedback,
      java.time.Clock clock) {
    this.store = store;
    this.feedback = feedback;
    this.clock = clock;
  }

  /** 운영 집계 한 줄(지금 색인의 질문·분류 — 없어진 FAQ 면 null). */
  public record FeedbackStat(
      String faqId,
      @Nullable String category,
      @Nullable String question,
      int helpful,
      int notHelpful,
      java.time.Instant lastAt) {}

  /** 0.4.0 BR-91 — 내 의견(회원이 본 FAQ 들). */
  public java.util.Map<String, Boolean> myFeedback(
      @Nullable PmartPrincipal principal, java.util.Collection<String> faqIds) {
    requireCustomer(principal);
    return feedback.of(principal.subject(), faqIds);
  }

  /** 0.4.0 BR-91 — 의견 남기기(마지막 값). 지금 색인에 없는 FAQ 는 404. */
  public boolean sendFeedback(@Nullable PmartPrincipal principal, String faqId, boolean helpful) {
    get(principal, faqId); // 회원·색인 확인
    feedback.upsert(principal.subject(), faqId, helpful, clock.instant());
    return helpful;
  }

  /** 0.4.0 BR-91 — 운영 집계(CS_AGENT·OPERATOR_ADMIN). */
  public List<FeedbackStat> feedbackStats(@Nullable PmartPrincipal principal) {
    if (principal == null
        || !(principal.hasRole("CS_AGENT") || principal.hasRole("OPERATOR_ADMIN"))) {
      throw new PmartException(ErrorCode.FORBIDDEN, "상담원·운영 관리자만 볼 수 있습니다.");
    }
    java.util.Map<String, FaqRow> current = new java.util.HashMap<>();
    store
        .activeSnapshotWithFaqs()
        .ifPresent(s -> store.list(s, null, null).forEach(r -> current.put(r.faqId(), r)));
    return feedback.stats().stream()
        .map(
            x -> {
              FaqRow r = current.get(x.faqId());
              return new FeedbackStat(
                  x.faqId(),
                  r == null ? null : r.category(),
                  r == null ? null : r.question(),
                  x.helpful(),
                  x.notHelpful(),
                  x.lastAt());
            })
        .toList();
  }

  public Listing list(
      @Nullable PmartPrincipal principal, @Nullable String q, @Nullable String category) {
    requireCustomer(principal);
    String snapshot = activeSnapshot();
    String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);
    String cat = category == null || category.isBlank() ? null : category.strip();
    return new Listing(store.list(snapshot, cat, needle), store.categories(snapshot));
  }

  public FaqRow get(@Nullable PmartPrincipal principal, String faqId) {
    requireCustomer(principal);
    return store
        .find(activeSnapshot(), faqId)
        .orElseThrow(() -> PmartException.notFound(ErrorCode.NOT_FOUND.name(), "FAQ 를 찾을 수 없습니다."));
  }

  /** FAQ 가 든 ACTIVE 스냅숏. 색인 전이거나 이 기능 전의 색인이면 422 CONFIG_REQUIRED(재색인 필요) — 빈 목록으로 숨기지 않는다. */
  private String activeSnapshot() {
    return store
        .activeSnapshotWithFaqs()
        .orElseThrow(
            () ->
                new PmartException(
                    ErrorCode.CONFIG_REQUIRED, "FAQ 가 아직 준비되지 않았습니다(고객 안내 문서 재색인 필요)."));
  }

  private static void requireCustomer(@Nullable PmartPrincipal principal) {
    if (principal == null || principal.type() != PmartPrincipal.PrincipalType.CUSTOMER) {
      throw new PmartException(ErrorCode.FORBIDDEN, "로그인한 회원만 이용할 수 있습니다.");
    }
  }
}
