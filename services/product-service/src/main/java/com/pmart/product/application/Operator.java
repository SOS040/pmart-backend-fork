package com.pmart.product.application;

import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.util.List;

/** 등록 명령 호출자(운영자). 승인은 approval-service 가 하므로 여기서는 운영자인지만 본다. */
public record Operator(String id, List<String> roles, String sourceSystem) {

  public static Operator current(String sourceSystem) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    return new Operator(p.subject(), p.roles(), sourceSystem);
  }

  /**
   * 조회도 운영자만(계약 x-pmart-user-types [OPERATOR]) — 게이트웨이 허용 목록이 막지만 서비스 안에서도 본다(2026-11-15, 깊이 방어).
   */
  public static void require() {
    current(null);
  }

  RequestKey key(String requestId, String operation) {
    return new RequestKey(sourceSystem, requestId, operation, id);
  }
}
