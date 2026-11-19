package com.pmart.storefront.api;

import com.pmart.storefront.api.generated.InternalApi;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi 의 구현 진입점. 아직 구현하지 않은 메서드는 인터페이스 default 가 501 을 돌려준다. 업무 규칙은 application/domain 에
 * 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class InternalController implements InternalApi {

  private final com.pmart.storefront.application.RankingSignals ranking;

  public InternalController(com.pmart.storefront.application.RankingSignals ranking) {
    this.ranking = ranking;
  }

  /** 순위 신호 모두 다시 내기(ADR-0072 — 새 검색 색인 채우기). */
  @Override
  public org.springframework.http.ResponseEntity<
          com.pmart.storefront.api.generated.model.RepublishRankingSignals200Response>
      republishRankingSignals() {
    return org.springframework.http.ResponseEntity.ok(
        new com.pmart.storefront.api.generated.model.RepublishRankingSignals200Response()
            .published(ranking.republishAll()));
  }
}
