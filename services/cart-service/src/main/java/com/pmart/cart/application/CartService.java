package com.pmart.cart.application;

import com.pmart.cart.domain.CartRules;
import com.pmart.cart.domain.CartRules.Owner;
import com.pmart.cart.infrastructure.CartStore;
import com.pmart.cart.infrastructure.CartStore.CartRow;
import com.pmart.cart.infrastructure.CartStore.ItemRow;
import com.pmart.cart.infrastructure.CatalogClient;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 장바구니 명령(Commerce 7장). 모든 수량은 절대값 설정이고 Idempotency-Key + expectedVersion(cartVersion)으로 충돌을 판정한다.
 *
 * <ul>
 *   <li>검증 순서: 판매 상태(product) → 구매 상한(product, 장바구니 전체를 기초상품 단위로) → 가용. 가용은 재고 서비스가 아직 없어 늘 불명이고,
 *       불명은 담기를 막지 않는다(Commerce 7장: 저장 성공·가용 조회 실패 → 담은 선택 유지·확인 중).
 *   <li>정본 조회(동기)는 트랜잭션 밖에서 먼저 한다. 반영·변경 결과·사건은 한 트랜잭션. 검증 단계의 하위 장애는 반영 전이라
 *       DEPENDENCY_UNAVAILABLE(재시도 가능).
 *   <li>같은 키 재전송은 저장된 결과를 돌려준다(수량을 다시 더하지 않음). 변경 결과는 {@code GET change-requests/{requestId}} 로도
 *       조회.
 * </ul>
 */
@Service
public class CartService {

  public static final String TOPIC = "commerce.cart.v1";

  private static final Logger log = LoggerFactory.getLogger(CartService.class);

  /** 명령 결과(계약 CartChangeResult). */
  public record ChangeResult(
      String status,
      String requestId,
      String cartId,
      @Nullable String cartItemId,
      String sku,
      int quantity,
      boolean selected,
      int cartVersion,
      Instant occurredAt) {}

  private final CartStore store;
  private final CatalogClient catalog;
  private final RequestIdempotency idempotency;
  private final EventPublisher events;
  private final EventEnvelopeCodec codec;
  private final CartProperties props;
  private final ObjectMapper mapper;
  private final TransactionTemplate tx;
  private final Clock clock;

  public CartService(
      CartStore store,
      CatalogClient catalog,
      RequestIdempotency idempotency,
      EventPublisher events,
      EventEnvelopeCodec codec,
      CartProperties props,
      ObjectMapper mapper,
      TransactionTemplate tx,
      Clock clock) {
    this.store = store;
    this.catalog = catalog;
    this.idempotency = idempotency;
    this.events = events;
    this.codec = codec;
    this.props = props;
    this.mapper = mapper;
    this.tx = tx;
    this.clock = clock;
  }

  // ---------- 장바구니 ----------

  /** 진행 중 장바구니 또는 새 장바구니. 보존 기간이 지난 것은 EXPIRED 로 두고 새로 만든다. */
  public CartRow createOrGet(
      Owner owner,
      @Nullable String deviceId,
      @Nullable String shoppingAddressId,
      @Nullable String mfcId) {
    return tx.execute(
        status -> {
          Instant now = clock.instant();
          CartRow current = store.activeCart(owner.type(), owner.id()).orElse(null);
          if (current != null
              && CartRules.expired(current.lastChangedAt(), props.retention(), now)) {
            store.expire(current.cartId());
            log.info("장바구니 보존 기간 지남 → 새로 만든다 {}", current.cartId());
            current = null;
          }
          if (current == null) {
            store.insertCart(
                new CartRow(
                    "CART-" + UUID.randomUUID(),
                    owner.type(),
                    owner.id(),
                    "ACTIVE",
                    deviceId,
                    shoppingAddressId,
                    mfcId,
                    0,
                    now,
                    now));
          } else if (shoppingAddressId != null || mfcId != null) {
            store.updateContext(current.cartId(), shoppingAddressId, mfcId);
          }
          return store.activeCart(owner.type(), owner.id()).orElseThrow();
        });
  }

  /** 내 진행 중 장바구니. 남의 것은 403, 없거나 보존 기간이 지났으면 404(POST /carts 로 새로). */
  public CartRow owned(String cartId, Owner owner) {
    CartRow c =
        store
            .cart(cartId, false)
            .orElseThrow(() -> PmartException.notFound("CART_NOT_FOUND", "장바구니가 없습니다."));
    if (!c.ownerType().equals(owner.type()) || !c.ownerId().equals(owner.id())) {
      throw CartRules.forbiddenOwner();
    }
    if (!"ACTIVE".equals(c.state())
        || CartRules.expired(c.lastChangedAt(), props.retention(), clock.instant())) {
      throw PmartException.notFound("CART_EXPIRED", "보존 기간이 지난 장바구니입니다. 장바구니를 다시 여세요.");
    }
    return c;
  }

  // ---------- 품목 ----------

  public ChangeResult add(
      String cartId,
      Owner owner,
      String requestId,
      String sourceSystem,
      String sku,
      int quantity,
      boolean selected,
      int expectedVersion) {
    CartRow cart = owned(cartId, owner);
    Map<String, Object> request = request(cartId, sku, quantity, selected, expectedVersion);
    RequestKey key = new RequestKey(sourceSystem, requestId, "addCartItem", owner.id());
    if (store.change(cartId, requestId).isPresent()) {
      return replay(key, request);
    }
    checkVersion(cart, expectedVersion);
    CatalogClient.Sku detail = saleable(sku);
    checkLimit(owner, cartId, sku, quantity);
    return idempotency
        .execute(
            key,
            request,
            ChangeResult.class,
            () -> {
              Instant now = clock.instant();
              checkVersion(store.cart(cartId, true).orElseThrow(), expectedVersion);
              ItemRow existing = store.itemBySku(cartId, sku).orElse(null);
              String itemId;
              if (existing == null) {
                itemId = "CI-" + UUID.randomUUID();
                store.insertItem(
                    new ItemRow(
                        itemId, cartId, sku, detail.productId(), quantity, selected, now, now));
              } else {
                itemId = existing.cartItemId();
                store.updateItem(itemId, quantity, selected, now);
              }
              return applied(cart, owner, requestId, itemId, sku, quantity, selected, now);
            })
        .value();
  }

  public ChangeResult change(
      String cartId,
      Owner owner,
      String itemId,
      String requestId,
      String sourceSystem,
      @Nullable Integer quantity,
      @Nullable Boolean selected,
      int expectedVersion) {
    if (quantity == null && selected == null) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "quantity 나 selected 중 하나는 있어야 합니다.");
    }
    CartRow cart = owned(cartId, owner);
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("cartId", cartId);
    request.put("itemId", itemId);
    request.put("quantity", quantity);
    request.put("selected", selected);
    request.put("expectedVersion", expectedVersion);
    RequestKey key = new RequestKey(sourceSystem, requestId, "changeCartItem", owner.id());
    if (store.change(cartId, requestId).isPresent()) {
      return replay(key, request);
    }
    ItemRow item =
        store
            .item(cartId, itemId)
            .orElseThrow(() -> PmartException.notFound("CART_ITEM_NOT_FOUND", "장바구니 품목이 없습니다."));
    checkVersion(cart, expectedVersion);
    int newQty = quantity == null ? item.quantity() : quantity;
    boolean newSelected = selected == null ? item.selected() : selected;
    if (newQty != item.quantity()) {
      saleable(item.sku());
      checkLimit(owner, cartId, item.sku(), newQty);
    }
    return idempotency
        .execute(
            key,
            request,
            ChangeResult.class,
            () -> {
              Instant now = clock.instant();
              checkVersion(store.cart(cartId, true).orElseThrow(), expectedVersion);
              if (store.item(cartId, itemId).isEmpty()) {
                throw PmartException.notFound("CART_ITEM_NOT_FOUND", "장바구니 품목이 없습니다.");
              }
              store.updateItem(itemId, newQty, newSelected, now);
              return applied(cart, owner, requestId, itemId, item.sku(), newQty, newSelected, now);
            })
        .value();
  }

  /** 삭제. 이미 없으면 멱등 성공(버전 그대로). */
  public ChangeResult delete(
      String cartId,
      Owner owner,
      String itemId,
      String requestId,
      String sourceSystem,
      @Nullable Integer expectedVersion) {
    CartRow cart = owned(cartId, owner);
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("cartId", cartId);
    request.put("itemId", itemId);
    request.put("expectedVersion", expectedVersion);
    RequestKey key = new RequestKey(sourceSystem, requestId, "deleteCartItem", owner.id());
    return idempotency
        .execute(
            key,
            request,
            ChangeResult.class,
            () -> {
              Instant now = clock.instant();
              CartRow locked = store.cart(cartId, true).orElseThrow();
              ItemRow item = store.item(cartId, itemId).orElse(null);
              if (item == null) {
                return new ChangeResult(
                    "APPLIED", requestId, cartId, itemId, "", 0, false, locked.version(), now);
              }
              if (expectedVersion != null) {
                checkVersion(locked, expectedVersion);
              }
              store.deleteItem(itemId);
              return applied(cart, owner, requestId, itemId, item.sku(), 0, false, now);
            })
        .value();
  }

  public ChangeResult changeResult(String cartId, Owner owner, String requestId) {
    owned(cartId, owner);
    String raw =
        store
            .change(cartId, requestId)
            .orElseThrow(
                () -> PmartException.notFound("CHANGE_NOT_FOUND", "해당 키로 접수된 변경이 없습니다(미적용)."));
    return mapper.readValue(raw, ChangeResult.class);
  }

  // ---------- 내부 ----------

  /** 판매 가능한 SKU(존재·ON_SALE). */
  CatalogClient.Sku saleable(String sku) {
    CatalogClient.Sku detail =
        validation(() -> catalog.sku(sku))
            .orElseThrow(() -> CartRules.notSaleable(sku, "NOT_FOUND"));
    if (!"ON_SALE".equals(detail.saleState())) {
      throw CartRules.notSaleable(sku, detail.saleState());
    }
    return detail;
  }

  /** 장바구니 전체(이 SKU 는 새 수량)로 구매 상한 확인. */
  void checkLimit(Owner owner, String cartId, String sku, int quantity) {
    List<CatalogClient.Line> lines = new ArrayList<>();
    for (ItemRow i : store.items(cartId)) {
      if (!i.sku().equals(sku)) {
        lines.add(new CatalogClient.Line(i.sku(), i.quantity()));
      }
    }
    lines.add(new CatalogClient.Line(sku, quantity));
    checkLimit(owner, lines);
  }

  void checkLimit(Owner owner, List<CatalogClient.Line> lines) {
    CatalogClient.LimitResult r = validation(() -> catalog.purchaseLimit(owner.id(), lines));
    if (!r.passed() && !r.violations().isEmpty()) {
      CatalogClient.Violation v = r.violations().get(0);
      throw CartRules.limitExceeded(v.baseProductId(), v.totalUnits(), v.limitUnits());
    }
  }

  /** 검증용 동기 조회: 아직 반영 전이라 결과 불명도 "적용 안 됨" — 재시도 가능한 DEPENDENCY_UNAVAILABLE. */
  static <T> T validation(java.util.function.Supplier<T> call) {
    try {
      return call.get();
    } catch (PmartException e) {
      if ("UNKNOWN_RESULT".equals(e.code())) {
        throw PmartException.dependencyUnavailable("상품 정보를 확인하지 못했습니다. 다시 시도하세요.", e);
      }
      throw e;
    }
  }

  static void checkVersion(CartRow cart, int expected) {
    if (cart.version() != expected) {
      throw CartRules.versionConflict(cart.version());
    }
  }

  private ChangeResult replay(RequestKey key, Map<String, Object> request) {
    return idempotency
        .execute(
            key,
            request,
            ChangeResult.class,
            () -> {
              throw new IllegalStateException("저장된 결과가 있는데 재생되지 않았다");
            })
        .value();
  }

  private ChangeResult applied(
      CartRow cart,
      Owner owner,
      String requestId,
      String itemId,
      String sku,
      int quantity,
      boolean selected,
      Instant now) {
    int version = store.bump(cart.cartId(), now);
    ChangeResult r =
        new ChangeResult(
            "APPLIED", requestId, cart.cartId(), itemId, sku, quantity, selected, version, now);
    store.insertChange(cart.cartId(), requestId, mapper.writeValueAsString(r), now);
    publish(cart, owner, r);
    return r;
  }

  private void publish(CartRow cart, Owner owner, ChangeResult r) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("cartId", cart.cartId());
    if (CartRules.MEMBER.equals(owner.type())) {
      payload.put("accountId", owner.id());
    }
    payload.put("cartItemId", r.cartItemId());
    payload.put("sku", r.sku());
    payload.put("qty", r.quantity());
    payload.put("cartVersion", r.cartVersion());
    payload.put("appliedRequestId", r.requestId());
    payload.put("appliedAt", r.occurredAt().toString());
    EventEnvelope envelope =
        codec.create(
            events.sourceSystem(),
            "CartItemChangeApplied",
            1,
            "Cart",
            cart.cartId(),
            r.cartVersion(),
            CartRules.MEMBER.equals(owner.type())
                ? new EventEnvelope.Actor(EventEnvelope.ActorType.CUSTOMER, owner.id())
                : null,
            EventEnvelope.EventSource.REAL,
            payload);
    events.publish(envelope, TOPIC);
  }

  private static Map<String, Object> request(
      String cartId, String sku, int quantity, boolean selected, int expectedVersion) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("cartId", cartId);
    m.put("sku", sku);
    m.put("quantity", quantity);
    m.put("selected", selected);
    m.put("expectedVersion", expectedVersion);
    return m;
  }
}
