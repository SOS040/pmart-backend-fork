package com.pmart.claim.api;

import com.pmart.claim.api.generated.RefundsApi;
import com.pmart.claim.api.generated.model.ListClaimRefunds200Response;
import com.pmart.claim.api.generated.model.ListClaimRefunds200ResponseAsOf;
import com.pmart.claim.application.ClaimService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 RefundsApi — 클레임 환불 상태 조회(고객 본인·운영자). PG 결과 확인 전에는 COMPLETED 가 아니다. */
@RestController
public class RefundsController implements RefundsApi {

  private final ClaimService claims;

  public RefundsController(ClaimService claims) {
    this.claims = claims;
  }

  @Override
  public ResponseEntity<ListClaimRefunds200Response> listClaimRefunds(String claimId) {
    ClaimService.ClaimView v = claims.get(ClaimsController.principal(), claimId);
    ListClaimRefunds200Response out =
        new ListClaimRefunds200Response()
            .claimId(claimId)
            .asOf(
                new ListClaimRefunds200ResponseAsOf()
                    .asOf(OffsetDateTime.now(ZoneOffset.UTC))
                    .freshness(ListClaimRefunds200ResponseAsOf.FreshnessEnum.CURRENT));
    v.refunds()
        .forEach(
            r -> out.addItemsItem(ClaimMapping.refund(r, v.refundSeqs().get(r.refundIntentId()))));
    return ResponseEntity.ok(out);
  }
}
