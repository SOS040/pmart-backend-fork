package com.pmart.customerassistant.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.security.PmartPrincipal.PrincipalType;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 호출자 → 검색 허용 접근 범위. 고객 안내 문서는 모두 {@value #CUSTOMER_SCOPE}. 로그인한 회원 고객(typ=CUSTOMER)만 —
 * 비회원(GUEST)·운영자는 범위가 없다(사용자 결정 2026-11-09 C2).
 *
 * <p>저장 답변을 돌려줄 때마다 {@link #stillAllowed} 로 현재 권한을 다시 본다.
 */
@Component
public class AccessPolicy {

  public static final String CUSTOMER_SCOPE = "customer";

  public Set<String> allowedScopes(PmartPrincipal principal) {
    return principal.type() == PrincipalType.CUSTOMER ? Set.of(CUSTOMER_SCOPE) : Set.of();
  }

  /** 지금도 회원 고객이고, 질문한 본인이며, 인용 근거의 접근 범위를 모두 가지는가. */
  public boolean stillAllowed(
      PmartPrincipal principal, String askerSubject, Collection<String> citationScopes) {
    if (principal.type() != PrincipalType.CUSTOMER || !principal.subject().equals(askerSubject)) {
      return false;
    }
    return allowedScopes(principal).containsAll(citationScopes);
  }
}
