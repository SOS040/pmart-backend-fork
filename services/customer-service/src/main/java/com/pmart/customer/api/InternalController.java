package com.pmart.customer.api;

import com.pmart.customer.api.generated.InternalApi;
import com.pmart.customer.api.generated.model.AccountStatus;
import com.pmart.customer.api.generated.model.ChangeAccountConsentsInternalRequest;
import com.pmart.customer.api.generated.model.CheckFirstPurchaseInternal200Response;
import com.pmart.customer.api.generated.model.ConsentStatus;
import com.pmart.customer.api.generated.model.GetAccountStatusInternal200Response;
import com.pmart.customer.api.generated.model.GetReviewAuthorInternal200Response;
import com.pmart.customer.api.generated.model.GetShoppingContextInternal200Response;
import com.pmart.customer.application.AddressService;
import com.pmart.customer.application.ConsentService;
import com.pmart.customer.application.ProfileService;
import com.pmart.customer.domain.AddressRules;
import com.pmart.customer.domain.ProfileRules;
import com.pmart.customer.infrastructure.CustomerStore.AddressRow;
import com.pmart.customer.infrastructure.CustomerStore.ContextRow;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 InternalApi — 계정 상태·약관 동의(auth)·쇼핑 컨텍스트·첫 구매 대조(내부 호출). */
@RestController
public class InternalController implements InternalApi {

  private final ProfileService profiles;
  private final AddressService addresses;
  private final ConsentService consents;

  public InternalController(
      ProfileService profiles, AddressService addresses, ConsentService consents) {
    this.profiles = profiles;
    this.addresses = addresses;
    this.consents = consents;
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

  /**
   * 구매 가능 = ACTIVE, 그리고 스위치 {@code purchase-gate} 가 켜져 있으면 필수 동의가 현행 버전(0.3.0). 꺼져 있으면 지금까지처럼
   * ACTIVE 만 (LAN 2026-11-17 — 고객 앱 동의 흐름 반영 전). requiredConsentOutdated 는 스위치와 상관없이 실제 값.
   */
  @Override
  public ResponseEntity<GetAccountStatusInternal200Response> getAccountStatusInternal(
      String accountId) {
    ProfileRow p = profiles.profile(accountId);
    ConsentService.PurchaseCheck check = consents.purchaseCheck(accountId);
    return ResponseEntity.ok(
        new GetAccountStatusInternal200Response()
            .accountId(accountId)
            .status(AccountStatus.fromValue(p.status()))
            .purchasable(profiles.purchasable(p) && !check.blocksPurchase())
            .requiredConsentOutdated(check.requiredOutdated())
            .version(p.version())
            .asOf(CustomerMapping.asOf(Instant.now(), p.version(), true)));
  }

  /** [auth→customer] 현행 버전 기준 동의 상태. 계정 행이 없어도 200(가입 중 — 모두 빠짐). */
  @Override
  public ResponseEntity<ConsentStatus> getAccountConsentsInternal(String accountId) {
    return ResponseEntity.ok(CustomerMapping.consentStatus(consents.overview(accountId)));
  }

  /** [auth→customer] 로그인 중(AUTH_CONSENT) 받은 동의 반영 → 반영 뒤 상태. */
  @Override
  public ResponseEntity<ConsentStatus> changeAccountConsentsInternal(
      String accountId,
      String idempotencyKey,
      String xSourceSystem,
      ChangeAccountConsentsInternalRequest body) {
    consents.change(
        accountId,
        idempotencyKey,
        xSourceSystem,
        CustomerMapping.changes(body.getChanges()),
        body.getChannel().getValue(),
        ConsentService.OPERATION_INTERNAL);
    return ResponseEntity.ok(CustomerMapping.consentStatus(consents.overview(accountId)));
  }

  /** 리뷰 작성자 표시(0.2.0) — 별명을 가린 값만. 이름·이메일·전화는 내주지 않는다. */
  @Override
  public ResponseEntity<GetReviewAuthorInternal200Response> getReviewAuthorInternal(
      String accountId) {
    ProfileRow p = profiles.profile(accountId);
    return ResponseEntity.ok(
        new GetReviewAuthorInternal200Response()
            .accountId(accountId)
            .displayName(ProfileRules.maskedAuthor(p.nickname())));
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
