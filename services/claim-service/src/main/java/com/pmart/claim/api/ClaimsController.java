package com.pmart.claim.api;

import com.pmart.claim.api.generated.ClaimsApi;
import com.pmart.claim.api.generated.model.Claim;
import com.pmart.claim.api.generated.model.ClaimState;
import com.pmart.claim.api.generated.model.ClaimType;
import com.pmart.claim.api.generated.model.CreateClaimRequest;
import com.pmart.claim.api.generated.model.ListClaims200Response;
import com.pmart.claim.api.generated.model.ListClaims200ResponsePage;
import com.pmart.claim.application.ClaimService;
import com.pmart.claim.domain.ClaimRules;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 ClaimsApi — 접수(고객 전체 취소)·조회·목록. 심사·결정·철회는 인터페이스 default 가 501 을 돌려준다. 업무 규칙은
 * application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class ClaimsController implements ClaimsApi {

  private final ClaimService claims;

  public ClaimsController(ClaimService claims) {
    this.claims = claims;
  }

  @Override
  public ResponseEntity<Claim> createClaim(
      String idempotencyKey,
      String xSourceSystem,
      CreateClaimRequest body,
      @Nullable String traceparent) {
    ClaimService.ClaimView v =
        claims.create(
            idempotencyKey,
            xSourceSystem,
            principal(),
            new ClaimService.CreateCommand(
                body.getOrderId(),
                body.getType().getValue(),
                body.getLines().stream()
                    .map(l -> new ClaimRules.Requested(l.getOrderLineId(), l.getQty()))
                    .toList(),
                body.getReason().getValue(),
                body.getReasonDetail(),
                body.getChannel() == null ? "CUSTOMER_APP" : body.getChannel().getValue()));
    return ResponseEntity.status(HttpStatus.CREATED).body(ClaimMapping.claim(v));
  }

  @Override
  public ResponseEntity<Claim> getClaim(String claimId) {
    return ResponseEntity.ok(ClaimMapping.claim(claims.get(principal(), claimId)));
  }

  @Override
  public ResponseEntity<ListClaims200Response> listClaims(
      @Nullable String orderId,
      @Nullable ClaimType type,
      @Nullable ClaimState state,
      Integer size,
      @Nullable String cursor) {
    var rows =
        claims.list(
            principal(),
            orderId,
            type == null ? null : type.getValue(),
            state == null ? null : state.getValue(),
            size);
    ListClaims200Response out =
        new ListClaims200Response().page(new ListClaims200ResponsePage().size(rows.size()));
    rows.forEach(r -> out.addItemsItem(ClaimMapping.summary(r)));
    return ResponseEntity.ok(out);
  }

  static PmartPrincipal principal() {
    return PmartPrincipal.current()
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "로그인이 필요합니다."));
  }
}
