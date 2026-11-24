package com.pmart.customer.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.customer.domain.WishlistRules;
import com.pmart.customer.infrastructure.CustomerStore;
import com.pmart.customer.infrastructure.CustomerStore.ProfileRow;
import com.pmart.customer.infrastructure.ProductCardClient;
import com.pmart.customer.infrastructure.ProductCardClient.Card;
import com.pmart.customer.infrastructure.WishlistStore;
import com.pmart.customer.infrastructure.WishlistStore.Item;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 찜(2026-11-20 ADR-0081 — 사용자 결정: 회원만·상품 단위·200개·품절/판매 종료도 남김·customer-service).
 *
 * <ul>
 *   <li>찜하기: 계정 행을 잠그고 수를 센 뒤 넣는다(동시 요청이 한도를 넘지 않게). 이미 있으면 그대로. 탈퇴 신청 이후 409.
 *   <li>빼기: 없어도 성공(멱등).
 *   <li>목록: 표시 값은 product-content 카드(지금 쇼핑 주소의 담당 센터 기준) — {@link WishlistRules}.
 * </ul>
 *
 * 사건은 내지 않는다(추천·알림이 생길 때).
 */
@Service
public class WishlistService {

  private static final Pattern PRODUCT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

  public record Added(String productId, Instant addedAt, int total) {}

  public record Line(
      String productId,
      Instant addedAt,
      @Nullable String name,
      @Nullable String imageUrl,
      @Nullable Long price,
      String saleState) {}

  public record Page(List<Line> items, int total, int limit) {}

  private final WishlistStore store;
  private final CustomerStore customers;
  private final ProductCardClient cards;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final @Nullable Integer maxItems;

  public WishlistService(
      WishlistStore store,
      CustomerStore customers,
      ProductCardClient cards,
      TransactionTemplate tx,
      Clock clock,
      @Value("${pmart.customer.wishlist.max-items:#{null}}") @Nullable Integer maxItems) {
    this.store = store;
    this.customers = customers;
    this.cards = cards;
    this.tx = tx;
    this.clock = clock;
    this.maxItems = maxItems;
  }

  int limit() {
    if (maxItems == null || maxItems < 1) {
      throw PmartException.configRequired("pmart.customer.wishlist.max-items");
    }
    return maxItems;
  }

  public Added add(String accountId, String productId) {
    checkProductId(productId);
    int limit = limit();
    customers.ensureProfile(accountId, clock.instant());
    return tx.execute(
        s -> {
          ProfileRow p =
              customers
                  .lockProfile(accountId)
                  .orElseThrow(() -> PmartException.notFound("ACCOUNT_NOT_FOUND", "고객 계정이 없습니다."));
          if (!"ACTIVE".equals(p.status())) {
            throw PmartException.invalidState("탈퇴 신청 이후에는 찜할 수 없습니다.");
          }
          Item existing = store.find(accountId, productId).orElse(null);
          if (existing != null) {
            return new Added(productId, existing.addedAt(), store.count(accountId));
          }
          int count = store.count(accountId);
          if (count >= limit) {
            throw new PmartException(
                "WISHLIST_LIMIT_EXCEEDED",
                HttpStatus.UNPROCESSABLE_CONTENT,
                "찜은 " + limit + "개까지 할 수 있어요.",
                false,
                List.of(new PmartException.Detail("limit", String.valueOf(limit))));
          }
          Instant now = clock.instant();
          store.insert(accountId, productId, now);
          return new Added(productId, now, count + 1);
        });
  }

  public void remove(String accountId, String productId) {
    store.delete(accountId, productId);
  }

  /** 상품별 찜 수(공개, BR-100 R3) — 쉼표 목록 1~100개, 요청 순서·중복 제거, 없으면 0. */
  public List<java.util.Map.Entry<String, Integer>> counts(String productIdsCsv) {
    List<String> ids =
        java.util.Arrays.stream(productIdsCsv.split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .distinct()
            .toList();
    if (ids.isEmpty() || ids.size() > 100) {
      throw new com.pmart.common.web.PmartException(
          com.pmart.common.web.ErrorCode.VALIDATION_FAILED, "productIds 는 1~100개입니다.");
    }
    java.util.Map<String, Integer> found = store.countsByProduct(ids);
    return ids.stream().map(id -> java.util.Map.entry(id, found.getOrDefault(id, 0))).toList();
  }

  public List<String> productIds(String accountId) {
    return store.page(accountId, 0, Math.max(limitOrDefault(), store.count(accountId))).stream()
        .map(Item::productId)
        .toList();
  }

  public int total(String accountId) {
    return store.count(accountId);
  }

  public Page page(String accountId, int page, int size) {
    List<Item> items = store.page(accountId, page * size, size);
    String mfcId = customers.context(accountId).map(CustomerStore.ContextRow::mfcId).orElse(null);
    Map<String, Card> byId = cards.cards(items.stream().map(Item::productId).toList(), mfcId);
    List<Line> lines =
        items.stream()
            .map(
                i -> {
                  Card c = byId.get(i.productId());
                  WishlistRules.Display d = WishlistRules.display(c == null ? null : c.options());
                  return new Line(
                      i.productId(),
                      i.addedAt(),
                      c == null ? null : c.name(),
                      c == null ? null : c.imageUrl(),
                      d.price(),
                      d.saleState());
                })
            .toList();
    return new Page(lines, store.count(accountId), limitOrDefault());
  }

  private int limitOrDefault() {
    return maxItems == null || maxItems < 1 ? 0 : maxItems;
  }

  private static void checkProductId(String productId) {
    if (productId == null || !PRODUCT_ID.matcher(productId).matches()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "productId 형식이 맞지 않습니다.");
    }
  }
}
