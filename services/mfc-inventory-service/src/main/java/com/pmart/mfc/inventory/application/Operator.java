package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;

/**
 * 센터 취급 변경 호출자(운영자). 운영자 토큰이고 {@code centers} 클레임에 대상 센터가 있어야 한다(자기 센터 범위). 세부 역할 검증은 아직 정한 규칙이 없어
 * 하지 않는다.
 */
public record Operator(String id, String sourceSystem) {

  public static Operator forCenter(String centerId, String sourceSystem) {
    return new Operator(requireCenter(centerId), sourceSystem);
  }

  /**
   * 현재 주체가 이 센터의 운영자인지 확인한다(조회에도 쓴다).
   *
   * @return 운영자 ID
   */
  public static String requireCenter(String centerId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (!p.canAccessCenter(centerId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    return p.subject();
  }

  RequestKey key(String requestId, String operation) {
    return new RequestKey(sourceSystem, requestId, operation, id);
  }
}
