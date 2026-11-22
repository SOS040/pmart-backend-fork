package com.pmart.claim.application;

import com.pmart.claim.infrastructure.ClaimStore;
import com.pmart.claim.infrastructure.Upstreams;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 0.13.0 CL-8(2026-11-23): 센터(mfc_id)를 모르는 옛 클레임(취소·결품 — 그 전에는 반품만 센터를 저장)에 원주문 이행 센터를 채운다. 서비스가 뜨면
 * 가상 스레드로 한 번 — order {@code GET /internal/orders/{id}} 의 mfcId. 새 클레임은 접수 때 저장하므로 다시 돌 필요가 없다.
 * order 를 못 부르면 멈추고(다음 기동 때 이어서) 경고만 — 클레임 처리는 막지 않는다. 주문에 센터가 없거나 주문이 없으면 비워 둔다(센터를 고르면 빠진다 — 계약
 * 0.13.0).
 */
@Component
public class ClaimCenterBackfill {

  private static final Logger log = LoggerFactory.getLogger(ClaimCenterBackfill.class);
  private static final int BATCH = 100;

  private final ClaimStore store;
  private final Upstreams upstreams;

  public ClaimCenterBackfill(ClaimStore store, Upstreams upstreams) {
    this.store = store;
    this.upstreams = upstreams;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    Thread.ofVirtual().name("claim-center-backfill").start(this::run);
  }

  /** 채운 클레임 수. 시험이 직접 부른다. */
  public int run() {
    Set<String> skipped = new HashSet<>();
    int filled = 0;
    try {
      while (true) {
        List<String> orders = store.ordersWithoutCenter(BATCH + skipped.size());
        List<String> todo = orders.stream().filter(o -> !skipped.contains(o)).toList();
        if (todo.isEmpty()) {
          break;
        }
        for (String orderId : todo) {
          JsonNode order = upstreams.order(orderId);
          String center = order == null ? null : order.path("mfcId").asString(null);
          if (center == null || center.isBlank()) {
            skipped.add(orderId);
          } else {
            filled += store.fillCenter(orderId, center);
          }
        }
      }
    } catch (RuntimeException e) {
      log.warn("클레임 센터 채우기 멈춤(다음 기동 때 이어서): {}", e.toString());
    }
    if (filled > 0 || !skipped.isEmpty()) {
      log.info("클레임 센터 채우기: 채움 {}, 센터 없는 주문 {}", filled, skipped.size());
    }
    return filled;
  }
}
