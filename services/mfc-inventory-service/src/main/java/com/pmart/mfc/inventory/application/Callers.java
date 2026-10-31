package com.pmart.mfc.inventory.application;

import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;

/** 서비스 간 명령 호출자(호출 서비스(X-Source-System)). /internal 은 토큰 없이 들어온다(ADR-0026). */
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
