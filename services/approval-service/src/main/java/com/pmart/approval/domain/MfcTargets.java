package com.pmart.approval.domain;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.common.web.PmartException.Detail;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * MFC 승인 종류(AP-1, 2026-11-17)의 대상 형식. 승인 번호(approvalRef)를 명령에 실어 MFC 정본이 확인·사용하므로, 승인 단위가 어느 센터의 어느
 * 원객체 집행인지 문자열 하나로 특정돼야 한다 — {@code targetRef = <mfcId>:<kind>:<ref>}, kind 가 정본(targetService)을
 * 정한다.
 *
 * <ul>
 *   <li>{@code MFC_STOCK_ADJUSTMENT}(본사 재고 승인 — Q-11 기준 이상): COUNT_ADJUSTMENT·CORRECTION →
 *       mfc-inventory-service, DISPOSAL·QUALITY_RELEASE → mfc-quality-service. 행 1개 이상(lineKey =
 *       SKU, 수량 ≥ 1) — 승인자가 무엇을 승인하는지 보이고 정본이 승인 범위를 비교한다.
 *   <li>{@code MFC_OPERATION_RESTRICTION}(운영 제한 해제 BO-08): RESTRICTION_LIFT → mfc-capacity-service.
 * </ul>
 *
 * 기준(10만 원·20개) 판정은 MFC 정본이 한다 — 여기서는 형식만 본다.
 */
public final class MfcTargets {

  public static final String STOCK_ADJUSTMENT = "MFC_STOCK_ADJUSTMENT";
  public static final String OPERATION_RESTRICTION = "MFC_OPERATION_RESTRICTION";

  /** 종류 → (kind → 정본). */
  static final Map<String, Map<String, String>> KINDS =
      Map.of(
          STOCK_ADJUSTMENT,
          Map.of(
              "COUNT_ADJUSTMENT", "mfc-inventory-service",
              "CORRECTION", "mfc-inventory-service",
              "DISPOSAL", "mfc-quality-service",
              "QUALITY_RELEASE", "mfc-quality-service"),
          OPERATION_RESTRICTION,
          Map.of("RESTRICTION_LIFT", "mfc-capacity-service"));

  /** 콜론·공백 없는 세 마디. */
  private static final Pattern PART = Pattern.compile("[^:\\s]+");

  /** 검사할 행(계약 ApprovalLine 의 lineKey·requestedQty). */
  public record Line(@Nullable String lineKey, @Nullable Integer requestedQty) {}

  /** 나눈 대상. */
  public record Target(String mfcId, String kind, String ref) {}

  private MfcTargets() {}

  public static boolean isMfcType(String type) {
    return KINDS.containsKey(type);
  }

  /**
   * 변경 요청 생성 때 형식 검사. MFC 종류가 아니면 아무것도 하지 않는다.
   *
   * @throws PmartException VALIDATION_FAILED(400) — 어긋난 항목을 details 에
   */
  public static void validate(
      String type, String targetService, String targetRef, List<Line> lines) {
    Map<String, String> kinds = KINDS.get(type);
    if (kinds == null) {
      return;
    }
    List<Detail> bad = new ArrayList<>();
    Target t = parse(targetRef);
    if (t == null) {
      bad.add(new Detail("targetRef", "<mfcId>:<kind>:<ref> 형식이어야 합니다(콜론·공백 없는 세 마디)."));
    } else if (!kinds.containsKey(t.kind())) {
      bad.add(
          new Detail("targetRef", type + " 의 kind 는 " + kinds.keySet() + " 중 하나입니다: " + t.kind()));
    } else if (!kinds.get(t.kind()).equals(targetService)) {
      bad.add(
          new Detail(
              "targetService",
              t.kind() + " 의 정본은 " + kinds.get(t.kind()) + " 입니다: " + targetService));
    }
    if (STOCK_ADJUSTMENT.equals(type)) {
      if (lines.isEmpty()) {
        bad.add(new Detail("lines", "재고 승인은 행(SKU·수량)이 1개 이상 필요합니다."));
      }
      for (int i = 0; i < lines.size(); i++) {
        Line l = lines.get(i);
        if (l.lineKey() == null || l.lineKey().isBlank()) {
          bad.add(new Detail("lines[" + i + "].lineKey", "SKU 가 필요합니다."));
        }
        if (l.requestedQty() == null || l.requestedQty() < 1) {
          bad.add(new Detail("lines[" + i + "].requestedQty", "수량은 1 이상이어야 합니다."));
        }
      }
    }
    if (!bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, type + " 변경 요청 형식이 맞지 않습니다.", bad);
    }
  }

  /** {@code <mfcId>:<kind>:<ref>} 를 나눈다. 형식이 아니면 null. */
  public static @Nullable Target parse(@Nullable String targetRef) {
    if (targetRef == null) {
      return null;
    }
    String[] parts = targetRef.split(":", -1);
    if (parts.length != 3) {
      return null;
    }
    for (String p : parts) {
      if (!PART.matcher(p).matches()) {
        return null;
      }
    }
    return new Target(parts[0], parts[1], parts[2]);
  }
}
