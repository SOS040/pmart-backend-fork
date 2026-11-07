package com.pmart.mfc.quality.application;

import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.mfc.quality.domain.QualityRules;
import org.jspecify.annotations.Nullable;

/**
 * 권한(MFC Q-11 결정 전 기본): 판정·요청·실행 = 그 센터 운영자 + MFC_WORKER 또는 MFC_SUPERVISOR, 승인·반려 = MFC_SUPERVISOR.
 * 행위자 ID 는 토큰 주체 — 본문의 ID 가 다르면 거절한다(대리 승인 막기).
 */
final class Actors {

  private Actors() {}

  static String worker(String mfcId, @Nullable String claimed) {
    PmartPrincipal p = operator(mfcId);
    if (!p.hasRole(QualityRules.WORKER) && !p.hasRole(QualityRules.SUPERVISOR)) {
      throw new PmartException(
          ErrorCode.FORBIDDEN, "이 센터의 MFC_WORKER 또는 MFC_SUPERVISOR 만 할 수 있습니다.");
    }
    return self(p, claimed);
  }

  static String supervisor(String mfcId, @Nullable String claimed) {
    PmartPrincipal p = operator(mfcId);
    if (!p.hasRole(QualityRules.SUPERVISOR)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터의 MFC_SUPERVISOR 만 승인할 수 있습니다.");
    }
    return self(p, claimed);
  }

  static void reader(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.isService()) {
      return;
    }
    if (p.type() != PmartPrincipal.PrincipalType.OPERATOR || !p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
  }

  static RequestKey key(String sourceSystem, String requestId, String operation) {
    String actor = PmartPrincipal.current().map(PmartPrincipal::subject).orElse("anonymous");
    return new RequestKey(sourceSystem, requestId, operation, actor);
  }

  private static PmartPrincipal operator(String mfcId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .filter(x -> x.type() == PmartPrincipal.PrincipalType.OPERATOR)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "운영자만 할 수 있습니다."));
    if (!p.canAccessCenter(mfcId)) {
      throw new PmartException(ErrorCode.FORBIDDEN, "이 센터에 대한 권한이 없습니다.");
    }
    return p;
  }

  private static String self(PmartPrincipal p, @Nullable String claimed) {
    if (claimed != null && !claimed.isBlank() && !claimed.equals(p.subject())) {
      throw new PmartException(ErrorCode.FORBIDDEN, "행위자 ID 는 토큰 주체여야 합니다.");
    }
    return p.subject();
  }
}
