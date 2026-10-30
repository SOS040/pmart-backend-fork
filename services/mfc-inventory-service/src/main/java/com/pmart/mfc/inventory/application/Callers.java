package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;

/** 서비스 간 명령 호출자(서비스 토큰 주체). /internal 경로는 보안 체인이 TYPE_SERVICE 만 들인다. */
final class Callers {

  private Callers() {}

  /** 토큰 주체(없으면 anonymous). 같은 키를 다른 주체가 쓰면 IDEMPOTENCY_CONFLICT. */
  static String actor() {
    return PmartPrincipal.current()
        .map(PmartPrincipal::subject)
        .orElse(RequestIdempotency.ANONYMOUS);
  }

  static RequestKey key(String sourceSystem, String requestId, String operation) {
    return new RequestKey(sourceSystem, requestId, operation, actor());
  }
}
