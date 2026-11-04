package com.pmart.assistant.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.security.PmartPrincipal.PrincipalType;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 호출자 → 검색 허용 접근 범위. 1차 문서는 모두 운영자 공용({@value #OPERATOR_SCOPE}). 운영자 안에서 더 좁힐 역할은 [결정 필요].
 *
 * <p>본문 context(화면·센터)는 힌트일 뿐 범위를 넓히지 않는다. 저장 답변을 돌려줄 때마다 {@link #stillAllowed} 로 현재 권한을 다시 본다.
 */
@Component
public class AccessPolicy {

  public static final String OPERATOR_SCOPE = "operator";

  public Set<String> allowedScopes(PmartPrincipal principal) {
    return principal.type() == PrincipalType.OPERATOR ? Set.of(OPERATOR_SCOPE) : Set.of();
  }

  /** 지금도 운영자이고, 질문한 본인이며, 인용 근거의 접근 범위를 모두 가지는가. */
  public boolean stillAllowed(
      PmartPrincipal principal, String askerSubject, Collection<String> citationScopes) {
    if (principal.type() != PrincipalType.OPERATOR || !principal.subject().equals(askerSubject)) {
      return false;
    }
    return allowedScopes(principal).containsAll(citationScopes);
  }
}
