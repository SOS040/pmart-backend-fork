package com.pmart.customer.api;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.customer.api.generated.model.AccountStatus;
import com.pmart.customer.api.generated.model.Address;
import com.pmart.customer.api.generated.model.AddressAllOfServiceability;
import com.pmart.customer.api.generated.model.AddressInput;
import com.pmart.customer.api.generated.model.AddressInputGeo;
import com.pmart.customer.api.generated.model.GetAccountStatusInternal200ResponseAsOf;
import com.pmart.customer.api.generated.model.Profile;
import com.pmart.customer.api.generated.model.ShoppingContext;
import com.pmart.customer.domain.AddressData;
import com.pmart.customer.domain.Serviceability;
import com.pmart.customer.infrastructure.CustomerStore.AddressRow;
import com.pmart.customer.infrastructure.CustomerStore.ContextRow;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;

/** 계약 DTO ↔ 내부 모델 변환. */
final class CustomerMapping {

  private CustomerMapping() {}

  /** 고객 토큰의 계정 ID. 운영자·기사 토큰은 403. */
  static String currentCustomer() {
    return PmartPrincipal.current()
        .filter(p -> p.type() == PmartPrincipal.PrincipalType.CUSTOMER)
        .map(PmartPrincipal::subject)
        .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "고객만 이용할 수 있습니다."));
  }

  static AddressData data(AddressInput in) {
    AddressInputGeo geo = in.getGeo();
    return new AddressData(
        in.getLabel(),
        in.getRoadAddress(),
        in.getDetailAddress(),
        in.getPostalCode(),
        in.getReceiverName(),
        in.getReceiverPhone(),
        in.getEntranceMethod() == null ? null : in.getEntranceMethod().getValue(),
        in.getEntranceNote(),
        geo == null || geo.getLat() == null ? null : geo.getLat().doubleValue(),
        geo == null || geo.getLng() == null ? null : geo.getLng().doubleValue());
  }

  static Address address(AddressRow a) {
    AddressData d = a.data();
    Serviceability s = a.serviceability();
    Address out =
        new Address()
            .addressId(a.addressId())
            .version(a.version())
            .label(d.label())
            .roadAddress(d.roadAddress())
            .detailAddress(d.detailAddress())
            .postalCode(d.postalCode())
            .receiverName(d.receiverName())
            .receiverPhone(d.receiverPhone())
            .entranceMethod(
                d.entranceMethod() == null
                    ? null
                    : Address.EntranceMethodEnum.fromValue(d.entranceMethod()))
            .entranceNote(d.entranceNote())
            .serviceability(
                new AddressAllOfServiceability()
                    .state(AddressAllOfServiceability.StateEnum.fromValue(s.state()))
                    .mfcId(s.mfcId())
                    .serviceabilityVersion(s.mappingVersion())
                    .asOf(asOf(s.asOf(), s.mappingVersion(), s.checkId() != null)));
    if (d.lat() != null && d.lng() != null) {
      out.geo(
          new AddressInputGeo().lat(BigDecimal.valueOf(d.lat())).lng(BigDecimal.valueOf(d.lng())));
    }
    return out;
  }

  static ShoppingContext context(ContextRow c) {
    return new ShoppingContext()
        .accountId(c.accountId())
        .shoppingAddressId(c.shoppingAddressId())
        .mfcId(c.mfcId())
        .shoppingContextVersion(c.version())
        .changedAt(time(c.changedAt()));
  }

  static Profile profile(ProfileRow p) {
    return new Profile()
        .accountId(p.accountId())
        .status(AccountStatus.fromValue(p.status()))
        .nickname(p.nickname())
        .socialProvider(
            p.socialProvider() == null
                ? null
                : Profile.SocialProviderEnum.fromValue(p.socialProvider()))
        .joinedAt(time(p.joinedAt()))
        .version(p.version());
  }

  /** 판정 사본의 시점. 판정하지 못한 사본(checkId 없음)은 UNAVAILABLE. */
  static GetAccountStatusInternal200ResponseAsOf asOf(
      Instant at, @Nullable Integer sourceVersion, boolean judged) {
    return new GetAccountStatusInternal200ResponseAsOf()
        .asOf(time(at))
        .sourceVersion(sourceVersion)
        .freshness(
            judged
                ? GetAccountStatusInternal200ResponseAsOf.FreshnessEnum.CURRENT
                : GetAccountStatusInternal200ResponseAsOf.FreshnessEnum.UNAVAILABLE);
  }

  static OffsetDateTime time(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }
}
