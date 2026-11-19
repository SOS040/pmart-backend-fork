package com.pmart.storefront.application;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventPublisher;
import com.pmart.storefront.domain.RankingRules;
import com.pmart.storefront.infrastructure.RankingStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 상품 순위 신호(2026-11-19 ADR-0072 — 사용자 "추천대로 5번 6번": 별점·리뷰 수 정렬, 최근 30일 인기순). storefront 가 리뷰
 * 사본(V6)·주문 줄 판매 원장(V7)으로 상품마다 세고, 바뀌었을 때만 {@code
 * ProductRankingSignalsChanged}(commerce.product-ranking.v1)를 낸다 — 검색 색인(Logstash)이 받아 정렬 칸을 채운다.
 * 판매 창이 하루씩 밀리므로 매일 04:40(Asia/Seoul) 전부 다시 센다. 정책값(pmart.storefront.ranking)이 없으면 내지 않는다.
 */
@Service
public class RankingSignals {

  private static final Logger log = LoggerFactory.getLogger(RankingSignals.class);

  private final RankingStore store;
  private final EventPublisher events;
  private final StorefrontProperties props;
  private final Clock clock;
  private final org.springframework.transaction.support.TransactionTemplate tx;
  private final AtomicBoolean warnedUnset = new AtomicBoolean();

  public RankingSignals(
      RankingStore store,
      EventPublisher events,
      StorefrontProperties props,
      Clock clock,
      org.springframework.transaction.support.TransactionTemplate tx) {
    this.tx = tx;
    this.store = store;
    this.events = events;
    this.props = props;
    this.clock = clock;
  }

  /** order.order.v1 — 주문 확정·줄 수량 변경으로 판매 원장, 그 SKU 를 대표로 둔 상품의 신호를 다시 센다. */
  @Transactional
  public void applyOrder(EventEnvelope e) {
    JsonNode p = e.payload();
    Instant now = clock.instant();
    String orderId = p.path("orderId").asString("");
    if ("OrderConfirmed".equals(e.eventType())) {
      int version = p.path("orderVersion").asInt((int) e.aggregateVersion());
      for (JsonNode l : p.path("lines")) {
        String sku = l.path("sku").asString("");
        String lineId = l.path("orderLineId").asString("");
        if (sku.isBlank() || lineId.isBlank()) {
          continue;
        }
        store.confirmedLine(
            lineId, orderId, sku, l.path("qty").asInt(0), version, e.occurredAt(), now);
        store.productsBySku(sku).forEach(this::recompute);
      }
    } else if ("OrderLineQuantityChanged".equals(e.eventType())) {
      String sku = p.path("sku").asString("");
      String lineId = p.path("orderLineId").asString("");
      if (sku.isBlank() || lineId.isBlank()) {
        return;
      }
      int qty =
          RankingRules.netQty(
              p.path("originalQty").asInt(0),
              p.path("cancelledBeforeShipQty").asInt(0),
              p.path("returnedQty").asInt(0));
      store.changedLine(
          lineId, orderId, sku, qty, p.path("version").asInt((int) e.aggregateVersion()), now);
      store.productsBySku(sku).forEach(this::recompute);
    }
  }

  /** 상품 하나를 다시 세어 바뀌었으면 낸다(같은 트랜잭션에서 사본·outbox). */
  @Transactional
  public void recompute(String productId) {
    publishIfChanged(productId, false);
  }

  /** 매일 — 판매 창이 하루 밀린다. */
  @Scheduled(cron = "0 40 4 * * *", zone = "Asia/Seoul")
  public void recomputeAll() {
    int changed = 0;
    for (String id : store.allProducts()) {
      // 상품마다 트랜잭션 하나(사본 + outbox). 같은 클래스 안 호출이라 @Transactional 프록시가 아니라 직접 연다
      if (Boolean.TRUE.equals(tx.execute(st -> publishIfChanged(id, false)))) {
        changed++;
      }
    }
    log.info("순위 신호 다시 셈 — 바뀐 상품 {}", changed);
  }

  /** 지금 값을 모두 다시 낸다(새 검색 색인 채우기 — ADR-0072 재색인 절차). 낸 건수. */
  public int republishAll() {
    int n = 0;
    for (String id : store.allProducts()) {
      if (Boolean.TRUE.equals(tx.execute(st -> publishIfChanged(id, true)))) {
        n++;
      }
    }
    return n;
  }

  private boolean publishIfChanged(String productId, boolean force) {
    StorefrontProperties.Ranking cfg = props.ranking();
    if (cfg == null || !cfg.complete()) {
      if (warnedUnset.compareAndSet(false, true)) {
        log.warn("pmart.storefront.ranking 설정이 없어 순위 신호를 내지 않는다");
      }
      return false;
    }
    Instant now = clock.instant();
    long[] r = store.ratings(productId);
    long sales =
        store
            .representativeSku(productId)
            .map(sku -> store.sales(sku, now.minus(Duration.ofDays(cfg.salesWindowDays()))))
            .orElse(0L);
    var prev = store.signal(productId);
    RankingStore.Signal next =
        new RankingStore.Signal(
            productId,
            (int) r[0],
            RankingRules.average(r[0], r[1]),
            RankingRules.score(r[0], r[1], cfg.ratingPriorCount(), cfg.ratingPriorMean()),
            (int) Math.min(Integer.MAX_VALUE, sales),
            cfg.salesWindowDays(),
            prev.map(s -> s.version() + 1).orElse(1));
    if (!force && prev.isPresent() && same(prev.get(), next)) {
      return false;
    }
    if (prev.isEmpty() && next.ratingCount() == 0 && next.salesWindow() == 0 && !force) {
      return false; // 신호가 없는 상품은 내지 않는다(색인 기본값 = 없음)
    }
    store.save(next, now);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("productId", productId);
    payload.put("version", next.version());
    payload.put("ratingCount", next.ratingCount());
    payload.put("ratingAverage", next.ratingAverage());
    payload.put("ratingScore", next.ratingScore());
    payload.put("salesWindow", next.salesWindow());
    payload.put("salesWindowDays", next.windowDays());
    payload.put("computedAt", now.toString());
    events.publish(
        "ProductRankingSignalsChanged",
        1,
        "ProductRanking",
        productId,
        next.version(),
        null,
        payload);
    return true;
  }

  private static boolean same(RankingStore.Signal a, RankingStore.Signal b) {
    return a.ratingCount() == b.ratingCount()
        && Objects.equals(a.ratingAverage(), b.ratingAverage())
        && Objects.equals(a.ratingScore(), b.ratingScore())
        && a.salesWindow() == b.salesWindow()
        && a.windowDays() == b.windowDays();
  }
}
