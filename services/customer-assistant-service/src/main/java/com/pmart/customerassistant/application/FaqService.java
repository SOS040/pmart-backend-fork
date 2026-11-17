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

  public FaqService(FaqStore store) {
    this.store = store;
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
