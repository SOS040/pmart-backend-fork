package com.pmart.claim.api;

import com.pmart.claim.api.generated.ClaimsApi;
import com.pmart.claim.api.generated.model.Claim;
import com.pmart.claim.api.generated.model.ClaimState;
import com.pmart.claim.api.generated.model.ClaimType;
import com.pmart.claim.api.generated.model.CreateClaimRequest;
import com.pmart.claim.api.generated.model.ListClaims200Response;
import com.pmart.claim.api.generated.model.ListClaims200ResponsePage;
import com.pmart.claim.api.generated.model.RecoveryActionRequest;
import com.pmart.claim.application.ClaimService;
import com.pmart.claim.application.ReturnService;
import com.pmart.claim.domain.ClaimRules;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 ClaimsApi — 접수(고객 전체 취소·고객센터 반품)·조회·목록, 반품 회수비 결제 시도(고객)·회수 후속 조치(상담원). 심사·결정·철회는 인터페이스
 * default 가 501 을 돌려준다. 업무 규칙은 application/domain 에 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class ClaimsController implements ClaimsApi {

  private final ClaimService claims;
  private final ReturnService returns;

  public ClaimsController(ClaimService claims, ReturnService returns) {
    this.claims = claims;
    this.returns = returns;
  }

  @Override
  public ResponseEntity<Claim> createClaim(
      String idempotencyKey,
      String xSourceSystem,
      CreateClaimRequest body,
      @Nullable String traceparent) {
    PmartPrincipal who = principal();
    String channel =
        body.getChannel() != null
            ? body.getChannel().getValue()
            : who.type() == PmartPrincipal.PrincipalType.OPERATOR ? "CS" : "CUSTOMER_APP";
    ClaimService.ClaimView v =
        claims.create(
            idempotencyKey,
            xSourceSystem,
            who,
            new ClaimService.CreateCommand(
                body.getOrderId(),
                body.getType().getValue(),
                body.getLines().stream()
                    .map(l -> new ClaimRules.Requested(l.getOrderLineId(), l.getQty()))
                    .toList(),
                body.getReason().getValue(),
                body.getReasonDetail(),
                channel),
            new ClaimService.ReturnExtras(
                body.getFaultCode() == null ? null : body.getFaultCode().getValue(),
                body.getIdentityCheck() == null
                    ? null
                    : body.getIdentityCheck().getMethod().getValue(),
                body.getIdentityCheck() == null ? null : body.getIdentityCheck().getNote(),
                body.getCaseRef()));
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

  @Override
  public ResponseEntity<Claim> createReturnFeePaymentAttempt(
      String claimId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(
        ClaimMapping.claim(returns.retryFee(idempotencyKey, xSourceSystem, principal(), claimId)));
  }

  @Override
  public ResponseEntity<Claim> createReturnRecoveryAction(
      String claimId,
      String idempotencyKey,
      String xSourceSystem,
      RecoveryActionRequest body,
      @Nullable Integer xExpectedVersion) {
    return ResponseEntity.ok(
        ClaimMapping.claim(
            returns.recoveryAction(
                idempotencyKey,
                xSourceSystem,
                principal(),
                new ReturnService.RecoveryAction(
                    claimId, body.getAction().getValue(), body.getNote()),
                xExpectedVersion)));
  }

  static PmartPrincipal principal() {
    return PmartPrincipal.current()
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "로그인이 필요합니다."));
  }
}
