package com.pmart.mfc.inbound.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 초기 보유 반입 승인(INITIAL_STOCK) 내용 검증과 센터별 묶음(결정 D2).
 *
 * <ul>
 *   <li>승인 행 하나 = 센터·SKU·수량 하나. {@code lineKey} 는 SKU 와 같아야 한다(승인 요청의 행 키).
 *   <li>한 승인에 같은 센터·SKU 가 두 번 나오면 뜻이 모호하므로 합치지 않고 승인 전체를 거절한다.
 *   <li>한 행이라도 틀리면 승인 전체를 반영하지 않는다(일부 센터만 적재되는 상태를 만들지 않음).
 *   <li>센터마다 반입 기록(receiptId) 하나 — mfc-inventory ConfirmReceipt 가 센터 단위이기 때문.
 * </ul>
 */
public final class InitialStockRules {

  private static final Pattern SKU = Pattern.compile("^[EB]-[0-9]{8}$");

  /** 재시도 간격 상한. */
  public static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

  private static final Duration BASE_BACKOFF = Duration.ofSeconds(5);

  private InitialStockRules() {}

  /**
   * 승인 행 원문.
   *
   * @param qty 정수가 아니면 null
   */
  public record RawLine(
      @Nullable String lineKey,
      @Nullable String mfcId,
      @Nullable String sku,
      @Nullable Integer qty) {}

  /**
   * 검증 결과. 문제가 있으면 {@code problems} 가 비어 있지 않고 {@code byCenter} 는 쓰지 않는다.
   *
   * @param byCenter 센터 → (SKU 오름차순 → 수량), 센터 오름차순
   */
  public record Plan(
      SortedMap<String, SortedMap<String, Integer>> byCenter, List<String> problems) {
    public boolean valid() {
      return problems.isEmpty() && !byCenter.isEmpty();
    }

    /** 거절 사유 한 줄(INVALID: 로 시작). */
    public String rejectReason() {
      return "INVALID: " + String.join("; ", problems.isEmpty() ? List.of("행 없음") : problems);
    }

    /** 오류여도 센터를 하나로 정할 수 있으면 그 센터(조회 편의). */
    public @Nullable String soleCenter(List<RawLine> lines) {
      String only = null;
      for (RawLine l : lines) {
        if (l.mfcId() == null || l.mfcId().isBlank()) {
          continue;
        }
        if (only != null && !only.equals(l.mfcId())) {
          return null;
        }
        only = l.mfcId();
      }
      return only;
    }
  }

  public static Plan plan(List<RawLine> lines) {
    SortedMap<String, SortedMap<String, Integer>> byCenter = new TreeMap<>();
    List<String> problems = new ArrayList<>();
    if (lines.isEmpty()) {
      problems.add("행 없음");
    }
    for (int i = 0; i < lines.size(); i++) {
      RawLine l = lines.get(i);
      String at = "lines[" + i + "]";
      if (l.mfcId() == null || l.mfcId().isBlank()) {
        problems.add(at + " mfcId 없음");
        continue;
      }
      if (l.sku() == null || !SKU.matcher(l.sku()).matches()) {
        problems.add(at + " sku 형식 오류");
        continue;
      }
      if (l.lineKey() != null && !l.lineKey().isBlank() && !l.lineKey().equals(l.sku())) {
        problems.add(at + " lineKey 와 sku 가 다름");
        continue;
      }
      if (l.qty() == null || l.qty() < 1) {
        problems.add(at + " qty 는 1 이상 정수");
        continue;
      }
      Map<String, Integer> center = byCenter.computeIfAbsent(l.mfcId(), k -> new TreeMap<>());
      if (center.putIfAbsent(l.sku(), l.qty()) != null) {
        problems.add(at + " 같은 센터·SKU 중복 " + l.mfcId() + "/" + l.sku());
      }
    }
    return new Plan(byCenter, problems);
  }

  /** n 번째 실패 뒤 다음 시도까지 기다릴 시간(5초부터 두 배, 최대 5분). */
  public static Duration backoff(int attempts) {
    int n = Math.max(0, Math.min(attempts - 1, 10));
    Duration d = BASE_BACKOFF.multipliedBy(1L << n);
    return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
  }
}
