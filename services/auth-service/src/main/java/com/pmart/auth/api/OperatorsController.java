package com.pmart.auth.api;

import com.pmart.auth.api.generated.OperatorsApi;
import com.pmart.auth.api.generated.model.ChangeOwnPasswordRequest;
import com.pmart.auth.api.generated.model.CreateOperatorAccountRequest;
import com.pmart.auth.api.generated.model.ListOperatorAccounts200Response;
import com.pmart.auth.api.generated.model.ListOperatorAccounts200ResponsePage;
import com.pmart.auth.api.generated.model.OperatorAccount;
import com.pmart.auth.api.generated.model.OperatorLoginRequest;
import com.pmart.auth.api.generated.model.OperatorLoginResult;
import com.pmart.auth.api.generated.model.ResetOperatorPasswordRequest;
import com.pmart.auth.api.generated.model.UpdateOperatorAccountRequest;
import com.pmart.auth.application.OperatorAccountService;
import com.pmart.auth.application.OperatorAuthService;
import com.pmart.auth.infrastructure.OperatorStore.AccountRow;
import java.time.Clock;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 OperatorsApi — 운영자 로그인·계정 관리(ADR-0018). 업무 규칙은 application 에 있고 여기서는 변환만 한다. */
@RestController
public class OperatorsController implements OperatorsApi {

  private final OperatorAuthService auth;
  private final OperatorAccountService accounts;
  private final Clock clock;

  public OperatorsController(
      OperatorAuthService auth, OperatorAccountService accounts, Clock clock) {
    this.auth = auth;
    this.accounts = accounts;
    this.clock = clock;
  }

  @Override
  public ResponseEntity<OperatorLoginResult> operatorLogin(
      String xSourceSystem, OperatorLoginRequest body, @Nullable String xDeviceId) {
    OperatorAuthService.LoginResult r =
        auth.login(body.getLoginId(), body.getPassword(), xSourceSystem, xDeviceId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new OperatorLoginResult()
                .tokens(AuthMapping.tokens(r.tokens()))
                .operator(AuthMapping.account(r.account(), clock.instant())));
  }

  @Override
  public ResponseEntity<Void> changeOwnPassword(
      String idempotencyKey, String xSourceSystem, ChangeOwnPasswordRequest body) {
    auth.changeOwnPassword(body.getCurrentPassword(), body.getNewPassword());
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<ListOperatorAccounts200Response> listOperatorAccounts(
      Integer size, @Nullable String cursor) {
    List<AccountRow> rows = accounts.list(cursor, size + 1);
    boolean more = rows.size() > size;
    List<AccountRow> page = more ? rows.subList(0, size) : rows;
    return ResponseEntity.ok(
        new ListOperatorAccounts200Response()
            .items(page.stream().map(a -> AuthMapping.account(a, clock.instant())).toList())
            .page(
                new ListOperatorAccounts200ResponsePage()
                    .nextCursor(more ? page.getLast().loginId() : null)
                    .size(size)));
  }

  @Override
  public ResponseEntity<OperatorAccount> createOperatorAccount(
      String idempotencyKey, String xSourceSystem, CreateOperatorAccountRequest body) {
    String id =
        accounts
            .create(
                idempotencyKey,
                xSourceSystem,
                new OperatorAccountService.NewAccount(
                    body.getLoginId(),
                    body.getDisplayName(),
                    body.getInitialPassword(),
                    body.getRoles(),
                    body.getCenters()),
                body)
            .value();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(AuthMapping.account(accounts.find(id), clock.instant()));
  }

  @Override
  public ResponseEntity<OperatorAccount> getOperatorAccount(String operatorId) {
    return ResponseEntity.ok(AuthMapping.account(accounts.get(operatorId), clock.instant()));
  }

  @Override
  public ResponseEntity<OperatorAccount> updateOperatorAccount(
      String operatorId,
      String idempotencyKey,
      String xSourceSystem,
      UpdateOperatorAccountRequest body,
      @Nullable Integer xExpectedVersion) {
    accounts.update(
        idempotencyKey,
        xSourceSystem,
        operatorId,
        xExpectedVersion,
        new OperatorAccountService.Change(
            body.getDisplayName(), body.getRoles(), body.getCenters(), body.getState().getValue()),
        body);
    return ResponseEntity.ok(AuthMapping.account(accounts.find(operatorId), clock.instant()));
  }

  @Override
  public ResponseEntity<Void> resetOperatorPassword(
      String operatorId,
      String idempotencyKey,
      String xSourceSystem,
      ResetOperatorPasswordRequest body) {
    accounts.resetPassword(
        idempotencyKey, xSourceSystem, operatorId, body.getTemporaryPassword(), body);
    return ResponseEntity.noContent().build();
  }
}
