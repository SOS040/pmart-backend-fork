package com.pmart.customer.api;

import com.pmart.customer.api.generated.WithdrawalsApi;
import com.pmart.customer.api.generated.model.ListWithdrawals200Response;
import com.pmart.customer.api.generated.model.RequestWithdrawalRequest;
import com.pmart.customer.api.generated.model.RevokeWithdrawalRequest;
import com.pmart.customer.api.generated.model.Withdrawal;
import com.pmart.customer.api.generated.model.WithdrawalOpenObligationsInner;
import com.pmart.customer.application.WithdrawalService;
import com.pmart.customer.application.WithdrawalService.View;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 WithdrawalsApi(0.12.0, ADR-0077) — 고객 탈퇴 접수·조회·철회. 업무 규칙은 {@link WithdrawalService}. 토큰은 로그
 * 금지.
 */
@RestController
public class WithdrawalsController implements WithdrawalsApi {

  private final WithdrawalService withdrawals;

  public WithdrawalsController(WithdrawalService withdrawals) {
    this.withdrawals = withdrawals;
  }

  @Override
  public ResponseEntity<Withdrawal> requestWithdrawal(
      String idempotencyKey,
      String xSourceSystem,
      RequestWithdrawalRequest body,
      @Nullable Integer xExpectedVersion) {
    View v =
        withdrawals.request(
            CustomerMapping.currentCustomer(),
            idempotencyKey,
            xSourceSystem,
            xExpectedVersion,
            body.getProvider() == null ? null : body.getProvider().getValue(),
            body.getProviderToken(),
            body.getReasonCode(),
            body.getReasonText(),
            body.getProviderAccessToken());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(response(v));
  }

  @Override
  public ResponseEntity<ListWithdrawals200Response> listWithdrawals() {
    return ResponseEntity.ok(
        new ListWithdrawals200Response()
            .items(
                withdrawals.list(CustomerMapping.currentCustomer()).stream()
                    .map(WithdrawalsController::response)
                    .toList()));
  }

  @Override
  public ResponseEntity<Withdrawal> getWithdrawal(String withdrawalId) {
    return ResponseEntity.ok(
        response(withdrawals.get(CustomerMapping.currentCustomer(), withdrawalId)));
  }

  @Override
  public ResponseEntity<Withdrawal> revokeWithdrawal(
      String withdrawalId,
      String idempotencyKey,
      String xSourceSystem,
      RevokeWithdrawalRequest body) {
    return ResponseEntity.ok(
        response(
            withdrawals.revoke(
                CustomerMapping.currentCustomer(),
                withdrawalId,
                idempotencyKey,
                xSourceSystem,
                body.getProvider() == null ? null : body.getProvider().getValue(),
                body.getProviderToken())));
  }

  static Withdrawal response(View v) {
    return new Withdrawal()
        .withdrawalId(v.withdrawalId())
        .state(Withdrawal.StateEnum.fromValue(v.state()))
        .requestedAt(v.requestedAt().atOffset(ZoneOffset.UTC))
        .completedAt(v.completedAt() == null ? null : v.completedAt().atOffset(ZoneOffset.UTC))
        .revokedAt(v.revokedAt() == null ? null : v.revokedAt().atOffset(ZoneOffset.UTC))
        .lastCheckedAt(
            v.lastCheckedAt() == null ? null : v.lastCheckedAt().atOffset(ZoneOffset.UTC))
        .openObligations(
            v.openObligations().stream()
                .map(o -> new WithdrawalOpenObligationsInner().kind(o.kind()).ref(o.ref()))
                .toList());
  }
}
