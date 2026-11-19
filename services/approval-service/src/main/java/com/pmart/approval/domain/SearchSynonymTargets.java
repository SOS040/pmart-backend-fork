package com.pmart.approval.domain;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.common.web.PmartException.Detail;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 검색 동의어 승인 종류 {@code SEARCH_SYNONYM}(ADR-0073 단계 B, 2026-11-20)의 변경 요청 형식. 정본은 search-service —
 * 동의어 집합 하나({@code pmart-product-synonyms})를 버전으로 바꾼다.
 *
 * <ul>
 *   <li>{@code targetService = search-service}, {@code targetRef = pmart-product-synonyms}
 *   <li>{@code targetExpectedVersion} 필수(0 이상) — 요청을 만들 때 본 규칙 버전. 승인 뒤 search-service 가 지금 버전과 다르면
 *       반영하지 않는다(그 사이 다른 변경이 들어감 — 다시 조회해 새로 요청)
 *   <li>행 = 바꾸는 규칙 하나(1~200개, lineKey = 규칙 ID, 겹치지 않음). {@code attributes.op}
 *       UPSERT(넣기·고치기)·DELETE, UPSERT 는 {@code attributes.terms} = 같은 뜻 낱말 2~20개(각 1~30자, 쉼표·{@code
 *       =>}·{@code #} 없음, 겹치지 않음)
 * </ul>
 *
 * 형식만 본다 — 실제 반영 가능 여부(지우려는 규칙이 있는지 등)는 정본이 반영 때 판정하고 회신한다.
 */
public final class SearchSynonymTargets {

  public static final String TYPE = "SEARCH_SYNONYM";
  public static final String TARGET_SERVICE = "search-service";
  public static final String TARGET_REF = "pmart-product-synonyms";
  public static final int MAX_LINES = 200;
  public static final int MAX_TERMS = 20;
  public static final int MAX_TERM_LENGTH = 30;

  /** 규칙 ID: ES 동의어 규칙 id 로 그대로 쓴다. */
  private static final Pattern RULE_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

  /** 검사할 행. terms 는 UPSERT 만. */
  public record Line(@Nullable String lineKey, @Nullable String op, @Nullable List<String> terms) {}

  private SearchSynonymTargets() {}

  public static boolean isSynonymType(String type) {
    return TYPE.equals(type);
  }

  /**
   * 변경 요청 생성 때 형식 검사.
   *
   * @throws PmartException VALIDATION_FAILED(400) — 어긋난 항목을 details 에
   */
  public static void validate(
      String targetService,
      String targetRef,
      @Nullable Integer targetExpectedVersion,
      List<Line> lines) {
    List<Detail> bad = new ArrayList<>();
    if (!TARGET_SERVICE.equals(targetService)) {
      bad.add(
          new Detail("targetService", "동의어 승인의 정본은 " + TARGET_SERVICE + " 입니다: " + targetService));
    }
    if (!TARGET_REF.equals(targetRef)) {
      bad.add(new Detail("targetRef", "동의어 승인의 대상은 " + TARGET_REF + " 입니다: " + targetRef));
    }
    if (targetExpectedVersion == null || targetExpectedVersion < 0) {
      bad.add(new Detail("targetExpectedVersion", "요청을 만들 때 본 규칙 버전(0 이상)이 필요합니다."));
    }
    if (lines.isEmpty() || lines.size() > MAX_LINES) {
      bad.add(new Detail("lines", "바꾸는 규칙은 1~" + MAX_LINES + "개입니다."));
    }
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      Line l = lines.get(i);
      String at = "lines[" + i + "]";
      if (l.lineKey() == null || !RULE_ID.matcher(l.lineKey()).matches()) {
        bad.add(new Detail(at + ".lineKey", "규칙 ID 는 영문·숫자·_.- 1~64자입니다."));
      } else if (!seen.add(l.lineKey())) {
        bad.add(new Detail(at + ".lineKey", "같은 규칙이 두 번 있습니다: " + l.lineKey()));
      }
      if ("DELETE".equals(l.op())) {
        continue;
      }
      if (!"UPSERT".equals(l.op())) {
        bad.add(new Detail(at + ".attributes.op", "UPSERT 또는 DELETE 입니다."));
        continue;
      }
      String problem = termsProblem(l.terms());
      if (problem != null) {
        bad.add(new Detail(at + ".attributes.terms", problem));
      }
    }
    if (!bad.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, TYPE + " 변경 요청 형식이 맞지 않습니다.", bad);
    }
  }

  /** 같은 뜻 낱말 목록의 문제, 없으면 null. search-service 의 반영 검사와 같은 기준. */
  public static @Nullable String termsProblem(@Nullable List<String> terms) {
    if (terms == null || terms.size() < 2 || terms.size() > MAX_TERMS) {
      return "같은 뜻 낱말은 2~" + MAX_TERMS + "개입니다.";
    }
    Set<String> seen = new HashSet<>();
    for (String t : terms) {
      String s = t == null ? "" : t.strip();
      if (s.isEmpty() || s.length() > MAX_TERM_LENGTH) {
        return "낱말은 1~" + MAX_TERM_LENGTH + "자입니다.";
      }
      if (s.contains(",") || s.contains("=>") || s.contains("#")) {
        return "낱말에 쉼표·=>·# 를 쓸 수 없습니다: " + s;
      }
      if (!seen.add(s.toLowerCase(java.util.Locale.ROOT))) {
        return "같은 낱말이 두 번 있습니다: " + s;
      }
    }
    return null;
  }
}
