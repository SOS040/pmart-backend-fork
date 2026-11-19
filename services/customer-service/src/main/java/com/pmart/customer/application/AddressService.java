package com.pmart.customer.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.PmartException;
import com.pmart.customer.domain.AddressData;
import com.pmart.customer.domain.AddressRules;
import com.pmart.customer.domain.Serviceability;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.CustomerStore.AddressRow;
import com.pmart.customer.infrastructure.CustomerStore.ContextRow;
import com.pmart.customer.infrastructure.ServiceabilityClient;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 주소록·현재 쇼핑 주소·비회원 배송지(Commerce 5장).
 *
 * <ul>
 *   <li>주소 저장과 배송 가능 판정은 다르다. 판정 실패·불명이어도 주소는 저장하고 serviceability=UNKNOWN.
 *   <li>판정(동기 호출)은 트랜잭션 밖에서 먼저 한다. 저장·컨텍스트·이벤트는 한 트랜잭션.
 *   <li>쇼핑 주소 선택은 그 주소를 다시 판정하고 shoppingContextVersion 을 올려 ShoppingAddressChanged 를 낸다(늦은 응답 폐기
 *       기준). 현재 쇼핑 주소를 고치면 컨텍스트도 같이 갱신한다.
 *   <li>확정 주문의 주소는 주문 스냅샷이 정본이라 주소록 수정·삭제가 바꾸지 않는다.
 * </ul>
 */
@Service
public class AddressService {

  public static final String TOPIC = "commerce.customer.v1";

  private static final Logger log = LoggerFactory.getLogger(AddressService.class);

  private final CustomerStore store;
  private final ServiceabilityClient serviceability;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final CustomerProperties props;
  private final Clock clock;

  public AddressService(
      CustomerStore store,
      ServiceabilityClient serviceability,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      CustomerProperties props,
      Clock clock) {
    this.store = store;
    this.serviceability = serviceability;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.props = props;
    this.clock = clock;
  }

  // ---------- 회원 주소록 ----------

  public List<AddressRow> list(String accountId) {
    return store.addresses(CustomerStore.OWNER_ACCOUNT, accountId);
  }

  public AddressRow get(String accountId, String addressId) {
    return owned(accountId, addressId);
  }

  public AddressRow add(String accountId, String requestId, String sourceSystem, AddressData d) {
    Integer limit = requireLimit();
    checkLimit(limit, store.countAddresses(CustomerStore.OWNER_ACCOUNT, accountId));
    Serviceability s = judge(d);
    AddressData stored = withCoordinates(d);
    String id =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "addMyAddress", accountId),
                d,
                String.class,
                () -> {
                  Instant now = clock.instant();
                  store.ensureProfile(accountId, now);
                  // 같은 계정 동시 추가 직렬화(컨텍스트 행 잠금) 후 다시 센다
                  store.lockContext(accountId, now);
                  checkLimit(limit, store.countAddresses(CustomerStore.OWNER_ACCOUNT, accountId));
                  String addressId = "ADR-" + UUID.randomUUID();
                  store.insertAddress(
                      new AddressRow(
                          addressId,
                          CustomerStore.OWNER_ACCOUNT,
                          accountId,
                          stored,
                          s,
                          0,
                          now,
                          now));
                  log.info("주소 추가 {} 판정 {}({})", addressId, s.state(), s.result());
                  return addressId;
                })
            .value();
    return owned(accountId, id);
  }

  public AddressRow update(
      String accountId,
      String addressId,
      String requestId,
      String sourceSystem,
      @Nullable Integer expectedVersion,
      AddressData d) {
    AddressRow current = owned(accountId, addressId);
    if (expectedVersion != null && expectedVersion != current.version()) {
      throw PmartException.versionConflict("주소가 그 사이 바뀌었습니다. 다시 조회하세요.");
    }
    int base = expectedVersion != null ? expectedVersion : current.version();
    d = d.keepingReceiverOf(current.data());
    Serviceability s = judge(d);
    AddressData stored = withCoordinates(d);
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("addressId", addressId);
    request.put("expectedVersion", base);
    request.put("address", d);
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "updateMyAddress", accountId),
        request,
        String.class,
        () -> {
          Instant now = clock.instant();
          ContextRow ctx = store.lockContext(accountId, now);
          if (store.updateAddress(addressId, base, stored, s, now) == 0) {
            throw PmartException.versionConflict("주소가 그 사이 바뀌었습니다. 다시 조회하세요.");
          }
          if (addressId.equals(ctx.shoppingAddressId())) {
            changeContext(ctx, addressId, s, now);
          }
          return addressId;
        });
    return owned(accountId, addressId);
  }

  public void delete(String accountId, String addressId, String requestId, String sourceSystem) {
    owned(accountId, addressId);
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "deleteMyAddress", accountId),
        Map.of("addressId", addressId),
        String.class,
        () -> {
          ContextRow ctx = store.lockContext(accountId, clock.instant());
          if (addressId.equals(ctx.shoppingAddressId())) {
            throw PmartException.invalidState("현재 쇼핑 주소는 삭제할 수 없습니다. 다른 주소를 먼저 선택하세요.");
          }
          store.deleteAddress(addressId);
          return addressId;
        });
  }

  // ---------- 현재 쇼핑 주소 ----------

  /** 현재 쇼핑 컨텍스트. 선택 전이면 빈 컨텍스트(버전 0). */
  public ContextRow context(String accountId) {
    return store
        .context(accountId)
        .orElseGet(() -> new ContextRow(accountId, null, null, null, false, 0, clock.instant()));
  }

  public ContextRow select(
      String accountId,
      String addressId,
      String requestId,
      String sourceSystem,
      @Nullable Integer expectedVersion) {
    AddressRow address = owned(accountId, addressId);
    if (expectedVersion != null && expectedVersion != context(accountId).version()) {
      throw PmartException.versionConflict("쇼핑 주소가 그 사이 바뀌었습니다. 다시 조회하세요.");
    }
    // 선택 시점에 다시 판정한다(권역 버전이 바뀌었을 수 있다). 판정 못 하면 저장된 사본을 유지.
    Serviceability fresh = judge(address.data());
    Serviceability s =
        Serviceability.UNKNOWN.equals(fresh.state()) && fresh.checkId() == null
            ? address.serviceability()
            : fresh;
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("addressId", addressId);
    request.put("expectedVersion", expectedVersion);
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "selectShoppingAddress", accountId),
        request,
        Integer.class,
        () -> {
          Instant now = clock.instant();
          ContextRow ctx = store.lockContext(accountId, now);
          if (expectedVersion != null && expectedVersion != ctx.version()) {
            throw PmartException.versionConflict("쇼핑 주소가 그 사이 바뀌었습니다. 다시 조회하세요.");
          }
          if (store.address(addressId).isEmpty()) {
            throw PmartException.notFound("ADDRESS_NOT_FOUND", "주소가 없습니다.");
          }
          if (s != address.serviceability()) {
            store.updateServiceability(addressId, s);
          }
          return changeContext(ctx, addressId, s, now).version();
        });
    return context(accountId);
  }

  // ---------- 비회원 배송지 ----------

  public AddressRow addGuest(
      String deviceId, String requestId, String sourceSystem, AddressData d) {
    Integer limit = requireLimit();
    checkLimit(limit, store.countAddresses(CustomerStore.OWNER_GUEST, deviceId));
    Serviceability s = judge(d);
    AddressData stored = withCoordinates(d);
    String id =
        idempotency
            .execute(
                new RequestKey(
                    sourceSystem, requestId, "createGuestDeliveryAddress", "device:" + deviceId),
                d,
                String.class,
                () -> {
                  Instant now = clock.instant();
                  String addressId = "GADR-" + UUID.randomUUID();
                  store.insertAddress(
                      new AddressRow(
                          addressId, CustomerStore.OWNER_GUEST, deviceId, stored, s, 0, now, now));
                  return addressId;
                })
            .value();
    return guest(deviceId, id);
  }

  /** 같은 기기만 조회. 다른 기기면 403(존재 여부는 숨기지 않는다 — ID 가 추측 불가). */
  public AddressRow guest(String deviceId, String guestAddressId) {
    AddressRow a =
        store
            .address(guestAddressId)
            .filter(r -> CustomerStore.OWNER_GUEST.equals(r.ownerType()))
            .orElseThrow(() -> PmartException.notFound("ADDRESS_NOT_FOUND", "배송지가 없습니다."));
    if (!a.ownerId().equals(deviceId)) {
      throw new PmartException(com.pmart.common.web.ErrorCode.FORBIDDEN, "이 기기에서 등록한 배송지가 아닙니다.");
    }
    return a;
  }

  // ---------- 내부 ----------

  /** 다른 사람 주소·비회원 배송지는 404(존재를 드러내지 않음). */
  public AddressRow owned(String accountId, String addressId) {
    return store
        .address(addressId)
        .filter(
            a -> CustomerStore.OWNER_ACCOUNT.equals(a.ownerType()) && a.ownerId().equals(accountId))
        .orElseThrow(() -> PmartException.notFound("ADDRESS_NOT_FOUND", "주소가 없습니다."));
  }

  private ContextRow changeContext(
      ContextRow ctx, String addressId, Serviceability s, Instant now) {
    ContextRow next =
        new ContextRow(
            ctx.accountId(),
            addressId,
            s.mfcId(),
            s.zoneId(),
            s.serviceable(),
            ctx.version() + 1,
            now);
    store.saveContext(next);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("accountId", next.accountId());
    payload.put("shoppingAddressId", addressId);
    payload.put("shoppingContextVersion", next.version());
    if (next.mfcId() != null) {
      payload.put("mfcId", next.mfcId());
    }
    if (next.zoneId() != null) {
      payload.put("zoneId", next.zoneId());
    }
    payload.put("serviceable", next.serviceable());
    EventEnvelope envelope =
        codec.create(
            events.sourceSystem(),
            "ShoppingAddressChanged",
            1,
            "Customer",
            next.accountId(),
            next.version(),
            new EventEnvelope.Actor(EventEnvelope.ActorType.CUSTOMER, next.accountId()),
            EventEnvelope.EventSource.REAL,
            payload);
    // 계약 토픽 명시(commerce.customer.v1)
    events.publish(envelope, TOPIC);
    log.info(
        "쇼핑 주소 변경 v{} 판정 {} mfc {}",
        next.version(),
        s.state(),
        Objects.requireNonNullElse(next.mfcId(), "-"));
    return next;
  }

  /**
   * 핀 좌표가 없으면 도로명주소 지오코딩 좌표로 채운 사본(2026-11-18 — 주문 사본·라이더 전달지 핀이 비지 않게, geoSource GEOCODE). 트랜잭션
   * 밖에서 먼저 부른다. 멱등 해시는 고객이 보낸 값(d) 그대로라 재시도가 지오코딩 결과에 흔들리지 않는다. 실패면 예전처럼 좌표 없이.
   */
  private AddressData withCoordinates(AddressData d) {
    if (d.lat() != null && d.lng() != null) {
      return d;
    }
    return serviceability
        .geocode(d.roadAddress())
        .map(p -> d.withGeocode(p.lat(), p.lng()))
        .orElse(d);
  }

  private Serviceability judge(AddressData d) {
    return serviceability.check(d.roadAddress(), d.lat(), d.lng(), "ADDRESS_BOOK");
  }

  private Integer requireLimit() {
    Integer limit = props.addressLimit();
    if (limit == null || limit <= 0) {
      throw PmartException.configRequired("pmart.customer.address-limit");
    }
    return limit;
  }

  private static void checkLimit(Integer limit, int current) {
    if (AddressRules.limitReached(limit, current)) {
      throw new PmartException(
          "ADDRESS_LIMIT_EXCEEDED",
          HttpStatus.UNPROCESSABLE_CONTENT,
          "주소는 최대 " + limit + "개까지 저장할 수 있습니다.",
          false,
          List.of());
    }
  }
}
