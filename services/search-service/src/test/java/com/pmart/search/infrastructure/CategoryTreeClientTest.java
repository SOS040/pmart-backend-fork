package com.pmart.search.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.search.application.SearchProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 카테고리 사본(2026-11-19, 검색 성능 분석 1순위 ②): 만료돼도 검색이 기다리지 않고 뒤에서 한 번만 다시 읽음, 사본 없이 실패하면 30초 동안 다시 부르지
 * 않음, 없는 필터 패널은 병렬로.
 */
class CategoryTreeClientTest {

  static final String TREE =
      "{\"nodes\":[{\"categoryId\":\"t01\",\"path\":\"신선\",\"level\":\"TOP\"},"
          + "{\"categoryId\":\"t01-c01\",\"parentId\":\"t01\",\"path\":\"신선 > 두부\"},"
          + "{\"categoryId\":\"t02\",\"path\":\"간편식\",\"level\":\"TOP\"}]}";

  final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-19T00:00:00Z"));
  final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  final AtomicInteger treeCalls = new AtomicInteger();
  final AtomicInteger panelCalls = new AtomicInteger();
  volatile boolean treeFails;
  volatile String treeBody = TREE;

  CategoryTreeClient client(Executor background, long panelDelayMs) {
    return new CategoryTreeClient(
        RestClient.builder(),
        new SearchProperties("http://es:9200", "http://product:8101", null, null, null),
        JsonMapper.builder().build(),
        clock,
        background) {
      @Override
      String fetchTree() {
        treeCalls.incrementAndGet();
        if (treeFails) {
          throw new IllegalStateException("down");
        }
        return treeBody;
      }

      @Override
      List<FilterGroup> filterGroups(String categoryId) {
        panelCalls.incrementAndGet();
        try {
          Thread.sleep(panelDelayMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return List.of(new FilterGroup("g-" + categoryId, categoryId, true, List.of("a")));
      }
    };
  }

  @Test
  void expiredTreeIsServedWhileOneReloadRunsBehind() {
    Deque<Runnable> queued = new ArrayDeque<>();
    boolean[] queue = {false};
    Executor ex =
        r -> {
          if (queue[0]) {
            queued.add(r);
          } else {
            r.run();
          }
        };
    CategoryTreeClient c = client(ex, 0);
    assertThat(c.path("t01-c01")).containsExactly("신선", "두부"); // 처음만 기다려 읽음
    assertThat(treeCalls).hasValue(1);

    now.set(now.get().plus(CategoryTreeClient.TTL).plusSeconds(1));
    queue[0] = true;
    treeBody = TREE.replace("두부", "두부·계란");
    for (int i = 0; i < 50; i++) { // 결과 50개 — 모두 만료된 사본으로 바로
      assertThat(c.path("t01-c01")).containsExactly("신선", "두부");
    }
    assertThat(queued).as("다시 읽기는 하나만").hasSize(1);
    queued.poll().run();
    assertThat(c.path("t01-c01")).containsExactly("신선", "두부·계란");
    assertThat(treeCalls).hasValue(2);
  }

  @Test
  void failureWithoutCopyIsNotRetriedPerResult() {
    CategoryTreeClient c = client(Runnable::run, 0);
    treeFails = true;
    for (int i = 0; i < 50; i++) {
      assertThat(c.path("t01")).isEmpty();
    }
    assertThat(treeCalls).as("사본 없이 실패해도 결과마다 부르지 않는다").hasValue(1);

    now.set(now.get().plus(CategoryTreeClient.RETRY).plusSeconds(1));
    treeFails = false;
    c.path("t01"); // 30초 지나면 뒤에서 다시(동기 실행기라 바로 반영)
    assertThat(c.path("t01")).containsExactly("신선");
    assertThat(treeCalls).hasValue(2);
  }

  @Test
  void missingTopPanelsLoadInParallel() {
    var ex = Executors.newVirtualThreadPerTaskExecutor();
    try {
      CategoryTreeClient c = client(ex, 300);
      c.path("t01"); // 트리 먼저
      long t0 = System.nanoTime();
      var groups = c.filterGroupsFor(null); // 최상위 t01·t02 패널
      long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
      assertThat(groups)
          .extracting(CategoryTreeClient.FilterGroup::id)
          .containsExactly("g-t01", "g-t02");
      assertThat(ms).as("차례로면 600ms 넘음").isLessThan(550);
      assertThat(panelCalls).hasValue(2);
      c.filterGroupsFor(null);
      assertThat(panelCalls).as("사본이 있으면 다시 읽지 않는다").hasValue(2);
      // 하위 카테고리 — 자기·조상 패널을 가까운 것부터(t01 은 이미 있음)
      assertThat(c.filterGroupsFor("t01-c01"))
          .extracting(CategoryTreeClient.FilterGroup::id)
          .containsExactly("g-t01-c01", "g-t01");
      assertThat(panelCalls).hasValue(3);
    } finally {
      ex.close();
    }
  }
}
