package com.pmart.assistant.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * 보이지 않는 행이 섞인 목록의 커서 페이지(BR-46 내 질문 목록). 저장 순서(최근 순)로 읽으면서 보이는 행만 모은다.
 *
 * <p>규칙:
 *
 * <ul>
 *   <li>한 페이지 = 보이는 행 최대 {@code size} 개. 보이지 않는 행(권한이 사라진 완료 답변)이 몇 개 연속돼도 건너뛰고 계속 읽는다.
 *   <li>{@code nextAfter} 는 <b>다음 페이지에 보이는 행이 하나라도 있을 때만</b> 준다 — 보이는 행을 {@code size + 1} 개째까지
 *       찾아본다. 그래서 커서가 있으면 다음 페이지는 비어 있지 않고, 커서가 없으면 더 볼 것이 없다.
 *   <li>{@code nextAfter} 는 이 페이지의 마지막 보이는 행이다. 그 뒤의 보이지 않는 행은 다음 페이지가 다시 건너뛴다 — 행을 빠뜨리거나 두 번 주지
 *       않는다.
 * </ul>
 *
 * <p>읽는 양은 그 사용자 행 수를 넘지 않는다(한 번에 {@code batch} 행씩, 끝나면 멈춘다).
 */
public final class HistoryPager {

  /** 한 번에 읽는 행 수 기본값(페이지 크기 + 1 보다 작으면 그 값). */
  public static final int DEFAULT_BATCH = 50;

  /** {@code after} 다음 행부터 최대 {@code limit} 행(저장 순서). {@code after} 가 null 이면 처음부터. */
  @FunctionalInterface
  public interface Fetcher<R> {
    List<R> fetch(@Nullable R after, int limit);
  }

  /**
   * @param items 보이는 행에서 만든 항목(최대 size)
   * @param nextAfter 다음 페이지의 시작 기준 행(이 행 다음부터). 다음에 보이는 행이 없으면 null
   */
  public record Page<R, T>(List<T> items, @Nullable R nextAfter) {}

  private HistoryPager() {}

  public static <R, T> Page<R, T> page(
      int size, @Nullable R start, Fetcher<R> fetcher, Function<R, Optional<T>> visible) {
    return page(size, start, fetcher, visible, DEFAULT_BATCH);
  }

  /** 시험용: 읽는 단위를 바꿔 경계(배치 끝)에서의 동작을 확인한다. */
  static <R, T> Page<R, T> page(
      int size,
      @Nullable R start,
      Fetcher<R> fetcher,
      Function<R, Optional<T>> visible,
      int batch) {
    if (size < 1) {
      throw new IllegalArgumentException("size 는 1 이상이어야 합니다.");
    }
    int limit = Math.max(size + 1, batch);
    List<R> rows = new ArrayList<>();
    List<T> items = new ArrayList<>();
    R after = start;
    scan:
    while (true) {
      List<R> chunk = fetcher.fetch(after, limit);
      for (R r : chunk) {
        after = r;
        Optional<T> item = visible.apply(r);
        if (item.isPresent()) {
          rows.add(r);
          items.add(item.get());
          if (items.size() > size) {
            break scan; // size+1 번째 보이는 행 — 다음 페이지가 있다
          }
        }
      }
      if (chunk.size() < limit) {
        break; // 더 읽을 행이 없다
      }
    }
    if (items.size() > size) {
      return new Page<>(List.copyOf(items.subList(0, size)), rows.get(size - 1));
    }
    return new Page<>(List.copyOf(items), null);
  }
}
