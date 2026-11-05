package com.pmart.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * BR-46 내 질문 목록 페이지 — 권한 없는 행(보이지 않는 행)이 섞일 때 누락·중복이 없고, nextCursor 유무가 실제 다음 페이지와 일치하는지.
 *
 * <p>회귀 사례 A·B·C·D(2026-11-05 보고): 최근 순 [A(권한 없음), B, C, D], size 2. 고치기 전에는 1쪽 [B, C] 뒤 nextCursor
 * 가 없어 D 를 영영 볼 수 없었다.
 */
class HistoryPagerTest {

  /** 저장 순서 목록과 보이지 않는 행 집합으로 만든 가짜 저장소. 읽은 행 수도 센다. */
  static final class Store implements HistoryPager.Fetcher<String> {
    final List<String> rows;
    int reads;

    Store(List<String> rows) {
      this.rows = rows;
    }

    @Override
    public List<String> fetch(@Nullable String after, int limit) {
      int from = after == null ? 0 : rows.indexOf(after) + 1;
      List<String> out = rows.subList(from, Math.min(rows.size(), from + limit));
      reads += out.size();
      return out;
    }
  }

  static HistoryPager.Page<String, String> page(
      Store store, Set<String> hidden, int size, @Nullable String after, int batch) {
    return HistoryPager.page(
        size, after, store, r -> hidden.contains(r) ? Optional.empty() : Optional.of(r), batch);
  }

  /** 첫 쪽부터 커서를 따라 끝까지 읽은 쪽들. 커서가 있는데 빈 쪽이 오면 실패. */
  static List<List<String>> allPages(List<String> rows, Set<String> hidden, int size, int batch) {
    Store store = new Store(rows);
    List<List<String>> pages = new ArrayList<>();
    String after = null;
    for (int guard = 0; guard <= rows.size() + 1; guard++) {
      HistoryPager.Page<String, String> p = page(store, hidden, size, after, batch);
      if (after != null) {
        assertThat(p.items()).as("커서가 있었으면 다음 쪽은 비어 있지 않다").isNotEmpty();
      }
      pages.add(p.items());
      if (p.nextAfter() == null) {
        return pages;
      }
      after = p.nextAfter();
    }
    throw new AssertionError("끝나지 않는다");
  }

  @Test
  void caseABCD_hiddenNewestDoesNotLoseTheLastRow() {
    List<List<String>> pages = allPages(List.of("A", "B", "C", "D"), Set.of("A"), 2, 3);
    assertThat(pages).containsExactly(List.of("B", "C"), List.of("D"));
    // 실제 크기(기본 읽기 단위)로도 같다
    assertThat(allPages(List.of("A", "B", "C", "D"), Set.of("A"), 2, HistoryPager.DEFAULT_BATCH))
        .containsExactly(List.of("B", "C"), List.of("D"));
  }

  @Test
  void consecutiveHiddenRowsAreSkippedAcrossBatches() {
    List<String> rows = List.of("A", "B", "C", "D", "E", "F", "G");
    Set<String> hidden = Set.of("B", "C", "D", "E");
    // 읽기 단위 2 — 보이지 않는 행이 배치 경계를 넘어 이어진다
    assertThat(allPages(rows, hidden, 1, 2))
        .containsExactly(List.of("A"), List.of("F"), List.of("G"));
    assertThat(allPages(rows, hidden, 2, 2)).containsExactly(List.of("A", "F"), List.of("G"));
  }

  @Test
  void noCursorWhenOnlyHiddenRowsRemain() {
    Store store = new Store(List.of("A", "B", "C", "D"));
    HistoryPager.Page<String, String> p = page(store, Set.of("C", "D"), 2, null, 3);
    assertThat(p.items()).containsExactly("A", "B");
    assertThat(p.nextAfter()).as("뒤에 보이는 행이 없으면 커서를 주지 않는다").isNull();
  }

  @Test
  void cursorWhenExactlyOneMoreVisibleRowExists() {
    Store store = new Store(List.of("A", "B", "C"));
    HistoryPager.Page<String, String> p = page(store, Set.of(), 2, null, 3);
    assertThat(p.items()).containsExactly("A", "B");
    assertThat(p.nextAfter()).isEqualTo("B");
    assertThat(page(store, Set.of(), 2, "B", 3).items()).containsExactly("C");
  }

  @Test
  void allHiddenGivesEmptyPageWithoutCursor() {
    HistoryPager.Page<String, String> p =
        page(new Store(List.of("A", "B", "C")), Set.of("A", "B", "C"), 2, null, 2);
    assertThat(p.items()).isEmpty();
    assertThat(p.nextAfter()).isNull();
  }

  @Test
  void emptyStore() {
    HistoryPager.Page<String, String> p = page(new Store(List.of()), Set.of(), 3, null, 2);
    assertThat(p.items()).isEmpty();
    assertThat(p.nextAfter()).isNull();
  }

  /** 행 8개의 모든 보임/숨김 조합(256) × 쪽 크기 1~4 × 읽기 단위 1·2·3·50: 누락·중복 없음, 쪽 크기 지킴, 커서 일치. */
  @Test
  void exhaustiveNoGapNoDuplicateAndCursorMatchesNextPage() {
    List<String> rows = List.of("r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7");
    for (int mask = 0; mask < (1 << rows.size()); mask++) {
      Set<String> hidden = new java.util.HashSet<>();
      List<String> visible = new ArrayList<>();
      for (int i = 0; i < rows.size(); i++) {
        if ((mask & (1 << i)) != 0) {
          hidden.add(rows.get(i));
        } else {
          visible.add(rows.get(i));
        }
      }
      for (int size = 1; size <= 4; size++) {
        for (int batch : new int[] {1, 2, 3, 50}) {
          List<List<String>> pages = allPages(rows, hidden, size, batch);
          List<String> seen = new ArrayList<>();
          pages.forEach(seen::addAll);
          assertThat(seen)
              .as("mask=%s size=%s batch=%s", Integer.toBinaryString(mask), size, batch)
              .isEqualTo(visible); // 순서대로, 빠짐·중복 없음
          for (int i = 0; i < pages.size() - 1; i++) {
            assertThat(pages.get(i)).hasSize(size); // 마지막 쪽 전에는 꽉 찬다
          }
          assertThat(pages.getLast().size()).isLessThanOrEqualTo(size);
        }
      }
    }
  }

  @Test
  void readsAtMostOneBatchPastTheNeededRow() {
    Store store = new Store(List.of("A", "B", "C", "D", "E", "F", "G", "H"));
    page(store, Set.of(), 2, null, 3);
    assertThat(store.reads).isEqualTo(3); // size+1 = 3 행이면 충분
  }
}
