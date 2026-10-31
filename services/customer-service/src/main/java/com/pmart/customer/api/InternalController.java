package com.pmart.customer.api;

import com.pmart.customer.api.generated.InternalApi;
import com.pmart.customer.api.generated.model.AccountStatus;
import com.pmart.customer.api.generated.model.CheckFirstPurchaseInternal200Response;
import com.pmart.customer.api.generated.model.GetAccountStatusInternal200Response;
import com.pmart.customer.api.generated.model.GetShoppingContextInternal200Response;
import com.pmart.customer.application.AddressService;
import com.pmart.customer.application.ProfileService;
import com.pmart.customer.domain.AddressRules;
import com.pmart.customer.infrastructure.CustomerStore.AddressRow;
import com.pmart.customer.infrastructure.CustomerStore.ContextRow;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — 계정 상태·쇼핑 컨텍스트·첫 구매 대조(내부 호출). */
@RestController
public class InternalController implements InternalApi {

  private final ProfileService profiles;
  private final AddressService addresses;

  public InternalController(ProfileService profiles, AddressService addresses) {
    this.profiles = profiles;
    this.addresses = addresses;
  }

  /** 첫 구매 무료배달 후보(계정 기준 사용 기록). 자격 점유는 order. 소셜 식별 대조(탈퇴 재가입)는 이후 — 지금은 NONE. */
  @Override
  public ResponseEntity<CheckFirstPurchaseInternal200Response> checkFirstPurchaseInternal(
      String accountId) {
    return ResponseEntity.ok(
        new CheckFirstPurchaseInternal200Response()
            .accountId(accountId)
            .firstPurchaseCandidate(profiles.firstPurchaseCandidate(accountId))
            .matchedBy(CheckFirstPurchaseInternal200Response.MatchedByEnum.NONE)
            .asOf(CustomerMapping.asOf(Instant.now(), null, true)));
  }

  @Override
  public ResponseEntity<GetAccountStatusInternal200Response> getAccountStatusInternal(
      String accountId) {
    ProfileRow p = profiles.profile(accountId);
    return ResponseEntity.ok(
        new GetAccountStatusInternal200Response()
            .accountId(accountId)
            .status(AccountStatus.fromValue(p.status()))
            .purchasable(profiles.purchasable(p))
            .requiredConsentOutdated(false)
            .version(p.version())
            .asOf(CustomerMapping.asOf(Instant.now(), p.version(), true)));
  }

  @Override
  public ResponseEntity<GetShoppingContextInternal200Response> getShoppingContextInternal(
      String accountId, @Nullable String shoppingAddressId) {
    profiles.profile(accountId);
    ContextRow c = addresses.context(accountId);
    String target = shoppingAddressId != null ? shoppingAddressId : c.shoppingAddressId();
    var out =
        new GetShoppingContextInternal200Response()
            .accountId(accountId)
            .shoppingAddressId(c.shoppingAddressId())
            .mfcId(c.mfcId())
            .shoppingContextVersion(c.version())
            .changedAt(CustomerMapping.time(c.changedAt()));
    if (target != null) {
      AddressRow a = addresses.owned(accountId, target);
      out.address(CustomerMapping.address(a))
          .phoneRef(AddressRules.maskPhone(a.data().receiverPhone()));
    }
    return ResponseEntity.ok(out);
  }
}
