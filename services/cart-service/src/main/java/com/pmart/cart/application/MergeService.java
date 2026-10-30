package com.pmart.cart.application;

import com.pmart.cart.domain.CartRules;
import com.pmart.cart.domain.CartRules.Owner;
import com.pmart.cart.infrastructure.CartStore;
import com.pmart.cart.infrastructure.CartStore.CartRow;
import com.pmart.cart.infrastructure.CartStore.ItemRow;
import com.pmart.cart.infrastructure.CatalogClient;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 비회원 → 회원 장바구니 병합(Commerce 7.6: 주문서 로그인 후 고객이 확인한 항목만, 한 번).
 *
 * <ul>
 *   <li>회원 토큰으로 부르고, 원본 비회원 장바구니 소유는 {@code X-Guest-Token}(비회원 access token)을 같은 JWKS 로 검증해 증명한다.
 *   <li>항목마다 다시 검증한다: 원본 품목 그대로인지, 판매 상태, 구매 상한(대상 장바구니 + 앞서 이관한 항목). 실패 항목은 사유와 함께 남기고 원본에 그대로
 *       둔다(전체 재합산 없음).
 *   <li>성공분은 대상에 더하고(합산) 원본에서 뺀다 — 같은 DB 한 트랜잭션이라 MERGE_PENDING 은 생기지 않는다.
 *   <li>같은 키 재전송은 같은 병합 결과(중복 합산 없음). 일반 로그인은 자동 병합하지 않는다.
 * </ul>
 */
@Service
public class MergeService {

  private static final Logger log = LoggerFactory.getLogger(MergeService.class);

  public record Requested(String sourceItemId, String sku, int quantity) {}

  public record ItemResult(
      String sourceItemId,
      String sku,
      int requestedQuantity,
      String result,
      @Nullable String targetCartItemId,
      @Nullable Integer mergedQuantity,
      @Nullable String reasonCode) {}

  public record MergeView(
      String mergeId,
      String state,
      String targetCartId,
      int targetCartVersion,
      String sourceCartId,
      String sourceCleared,
      List<ItemResult> items,
      Instant occurredAt) {}

  private final CartStore store;
  private final CartService carts;
  private final RequestIdempotency idempotency;
  private final JwtDecoder jwtDecoder;
  private final ObjectMapper mapper;
  private final Clock clock;

  public MergeService(
      CartStore store,
      CartService carts,
      RequestIdempotency idempotency,
      JwtDecoder jwtDecoder,
      ObjectMapper mapper,
      Clock clock) {
    this.store = store;
    this.carts = carts;
    this.idempotency = idempotency;
    this.jwtDecoder = jwtDecoder;
    this.mapper = mapper;
    this.clock = clock;
  }

  public MergeView merge(
      String targetCartId,
      Owner member,
      String requestId,
      String sourceSystem,
      String guestToken,
      String sourceCartId,
      int expectedVersion,
      List<Requested> requested) {
    if (!CartRules.MEMBER.equals(member.type())) {
      throw new PmartException(ErrorCode.FORBIDDEN, "병합은 로그인한 회원만 할 수 있습니다.");
    }
    if (requested.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "이관할 항목이 없습니다.");
    }
    CartRow target = carts.owned(targetCartId, member);
    String guestId = guestSubject(guestToken);
    CartRow source =
        store
            .cart(sourceCartId, false)
            .orElseThrow(() -> PmartException.notFound("CART_NOT_FOUND", "비회원 장바구니가 없습니다."));
    if (!CartRules.GUEST.equals(source.ownerType()) || !source.ownerId().equals(guestId)) {
      throw CartRules.forbiddenOwner();
    }
    String mergeId =
        "MRG-"
            + UUID.nameUUIDFromBytes(
                (member.id() + ":" + requestId).getBytes(StandardCharsets.UTF_8));
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("targetCartId", targetCartId);
    request.put("sourceCartId", sourceCartId);
    request.put("expectedVersion", expectedVersion);
    request.put("items", requested);
    RequestKey key = new RequestKey(sourceSystem, requestId, "mergeCart", member.id());
    if (store.merge(targetCartId, mergeId).isPresent()) {
      return idempotency
          .execute(
              key,
              request,
              MergeView.class,
              () -> {
                throw new IllegalStateException("저장된 병합이 있는데 재생되지 않았다");
              })
          .value();
    }
    CartService.checkVersion(target, expectedVersion);

    // 항목별 재검증(트랜잭션 밖 — 정본 조회)
    Map<String, ItemRow> sourceItems = new HashMap<>();
    store.items(sourceCartId).forEach(i -> sourceItems.put(i.cartItemId(), i));
    Map<String, Integer> targetQty = new LinkedHashMap<>();
    store.items(targetCartId).forEach(i -> targetQty.put(i.sku(), i.quantity()));
    List<ItemResult> results = new ArrayList<>();
    for (Requested r : requested) {
      ItemRow src = sourceItems.get(r.sourceItemId());
      if (src == null || !src.sku().equals(r.sku()) || r.quantity() > src.quantity()) {
        results.add(rejected(r, "SOURCE_ITEM_CHANGED"));
        continue;
      }
      try {
        carts.saleable(r.sku());
        Map<String, Integer> trial = new LinkedHashMap<>(targetQty);
        trial.merge(r.sku(), r.quantity(), Integer::sum);
        List<CatalogClient.Line> lines = new ArrayList<>();
        trial.forEach((sku, q) -> lines.add(new CatalogClient.Line(sku, q)));
        carts.checkLimit(member, lines);
        targetQty.put(r.sku(), trial.get(r.sku()));
        results.add(
            new ItemResult(
                r.sourceItemId(), r.sku(), r.quantity(), "MERGED", null, r.quantity(), null));
      } catch (PmartException e) {
        if (e.status().is5xxServerError()) {
          throw e; // 정본 장애: 아무것도 반영하지 않았다 — 같은 키로 다시
        }
        results.add(rejected(r, e.code()));
      }
    }

    return idempotency
        .execute(
            key,
            request,
            MergeView.class,
            () -> {
              Instant now = clock.instant();
              CartRow locked = store.cart(targetCartId, true).orElseThrow();
              CartService.checkVersion(locked, expectedVersion);
              store.cart(sourceCartId, true).orElseThrow();
              List<ItemResult> applied = new ArrayList<>();
              int merged = 0;
              for (ItemResult r : results) {
                if (!"MERGED".equals(r.result())) {
                  applied.add(r);
                  continue;
                }
                ItemRow src = store.item(sourceCartId, r.sourceItemId()).orElse(null);
                if (src == null || src.quantity() < r.requestedQuantity()) {
                  applied.add(
                      rejected(
                          new Requested(r.sourceItemId(), r.sku(), r.requestedQuantity()),
                          "SOURCE_ITEM_CHANGED"));
                  continue;
                }
                ItemRow existing = store.itemBySku(targetCartId, r.sku()).orElse(null);
                String targetItemId;
                if (existing == null) {
                  targetItemId = "CI-" + UUID.randomUUID();
                  store.insertItem(
                      new ItemRow(
                          targetItemId,
                          targetCartId,
                          r.sku(),
                          src.productId(),
                          r.requestedQuantity(),
                          true,
                          now,
                          now));
                } else {
                  targetItemId = existing.cartItemId();
                  store.updateItem(
                      targetItemId,
                      existing.quantity() + r.requestedQuantity(),
                      existing.selected(),
                      now);
                }
                if (src.quantity() == r.requestedQuantity()) {
                  store.deleteItem(src.cartItemId());
                } else {
                  store.updateItem(
                      src.cartItemId(),
                      src.quantity() - r.requestedQuantity(),
                      src.selected(),
                      now);
                }
                merged++;
                applied.add(
                    new ItemResult(
                        r.sourceItemId(),
                        r.sku(),
                        r.requestedQuantity(),
                        "MERGED",
                        targetItemId,
                        r.requestedQuantity(),
                        null));
              }
              int targetVersion = locked.version();
              if (merged > 0) {
                targetVersion = store.bump(targetCartId, now);
                store.bump(sourceCartId, now);
              }
              MergeView view =
                  new MergeView(
                      mergeId,
                      CartRules.mergeState(merged, requested.size()),
                      targetCartId,
                      targetVersion,
                      sourceCartId,
                      merged > 0 ? "DONE" : "NOT_STARTED",
                      applied,
                      now);
              store.insertMerge(
                  mergeId,
                  targetCartId,
                  sourceCartId,
                  view.state(),
                  mapper.writeValueAsString(view),
                  now);
              log.info(
                  "장바구니 병합 {} {} → {} ({}/{})",
                  mergeId,
                  sourceCartId,
                  targetCartId,
                  merged,
                  requested.size());
              return view;
            })
        .value();
  }

  public MergeView result(String targetCartId, Owner member, String mergeId) {
    carts.owned(targetCartId, member);
    String raw =
        store
            .merge(targetCartId, mergeId)
            .orElseThrow(() -> PmartException.notFound("MERGE_NOT_FOUND", "병합 결과가 없습니다."));
    return mapper.readValue(raw, MergeView.class);
  }

  /** X-Guest-Token 검증(같은 JWKS) → 비회원 주체. 무효·회원 토큰이면 소유를 증명하지 못한 것(403). */
  private String guestSubject(String token) {
    try {
      Jwt jwt = jwtDecoder.decode(token);
      if (!"GUEST".equals(jwt.getClaimAsString("typ")) || jwt.getSubject() == null) {
        throw CartRules.forbiddenOwner();
      }
      return jwt.getSubject();
    } catch (JwtException e) {
      throw CartRules.forbiddenOwner();
    }
  }

  private static ItemResult rejected(Requested r, String reason) {
    return new ItemResult(r.sourceItemId(), r.sku(), r.quantity(), "REJECTED", null, null, reason);
  }
}
