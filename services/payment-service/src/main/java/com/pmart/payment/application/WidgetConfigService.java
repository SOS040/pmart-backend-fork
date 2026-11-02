package com.pmart.payment.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.payment.infrastructure.toss.TossProperties;
import org.springframework.stereotype.Service;

/**
 * 결제위젯 설정(BR-34, 주문서형): 결제 진입 전에 위젯을 그리기 위한 clientKey·customerKey. 회원만.
 *
 * <p>customerKey = 회원 accountId(결제 명세 D6, 2026-11-02 결정). 값은 로그에 남기지 않는다.
 */
@Service
public class WidgetConfigService {

  /** clientKey: 결제위젯 클라이언트 키(gck, 공개). customerKey: 토스 고객 식별값. */
  public record WidgetConfig(String clientKey, String customerKey) {
    @Override
    public String toString() {
      return "WidgetConfig[clientKey=" + clientKey + "]"; // customerKey 는 숨긴다
    }
  }

  private final TossProperties toss;

  public WidgetConfigService(TossProperties toss) {
    this.toss = toss;
  }

  public WidgetConfig current() {
    String accountId =
        PmartPrincipal.current()
            .filter(p -> p.type() == PmartPrincipal.PrincipalType.CUSTOMER)
            .map(PmartPrincipal::subject)
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "회원만 결제할 수 있습니다."));
    if (toss.clientKey() == null) {
      throw PmartException.configRequired("pmart.payment.toss.client-key");
    }
    return new WidgetConfig(toss.clientKey(), accountId);
  }
}
