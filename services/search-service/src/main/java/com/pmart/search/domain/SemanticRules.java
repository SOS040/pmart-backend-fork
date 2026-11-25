package com.pmart.search.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 의미 검색 규칙(ADR-0088). 꼬리표 프롬프트·응답 정리, 임베딩 글, 원문 해시. 꼬리표는 검색 전용(고객 비표시) — 원산지·영양·알레르기 같은 법적 표시는 만들지
 * 않는다(프롬프트에서 막고 응답에서도 거른다).
 */
public final class SemanticRules {

  /** 꼬리표 최대 개수·한 낱말 길이. */
  public static final int MAX_TAGS = 12;

  public static final int MAX_TAG_LENGTH = 12;

  /** 설명은 앞부분만(임베딩 문맥·꼬리표 프롬프트 길이). */
  public static final int DESCRIPTION_CHARS = 400;

  /** 꼬리표로 쓰지 않는 낱말(법적 표시·근거 없는 약속). */
  static final List<String> BANNED =
      List.of(
          "국산", "국내산", "수입", "원산지", "유기농", "무농약", "최저가", "알레르기", "함유", "무첨가", "건강", "다이어트", "직사광선",
          "고온", "보관", "주의", "금지");

  private SemanticRules() {}

  /** 꼬리표를 뽑을 상품 글. */
  public record Source(
      String productId,
      String name,
      List<String> categoryPath,
      @Nullable String description,
      @Nullable String originText) {}

  /** 원문 해시 — 이름·카테고리·설명·모델이 같으면 다시 만들지 않는다. */
  public static String hash(Source s, String embeddingModel, String tagModel) {
    String text =
        s.name()
            + "\n"
            + String.join(">", s.categoryPath())
            + "\n"
            + clip(s.description())
            + "\n"
            + embeddingModel
            + "\n"
            + tagModel
            + "\n"
            + PROMPT_VERSION;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String clip(@Nullable String d) {
    if (d == null) {
      return "";
    }
    String t = d.replaceAll("\\s+", " ").strip();
    return t.length() > DESCRIPTION_CHARS ? t.substring(0, DESCRIPTION_CHARS) : t;
  }

  /** 프롬프트 판 — 바꾸면 해시가 달라져 모든 상품을 다시 만든다. */
  public static final String PROMPT_VERSION = "p2";

  /** 꼬리표 프롬프트(한국어 상품 → JSON {"tags": [...]}) — 예시 둘(먹거리·생활용품)로 근거 있는 말만. */
  public static String tagPrompt(Source s) {
    return "너는 한국 온라인 장보기 앱의 검색 꼬리표를 만든다. 고객이 이 상품을 찾을 때 검색창에 칠 만한 짧은 한국어 낱말을 최대 "
        + MAX_TAGS
        + "개 골라라.\n"
        + "규칙:\n"
        + "- 상품명·카테고리·설명에서 분명히 알 수 있는 것만. 추측·과장 금지. 확실하지 않으면 적게 써라(0개도 된다).\n"
        + "- 색, 맛, 식감, 종류, 쓰임을 우선. 상황(야식·캠핑 등)은 그 상품에 정말 흔히 쓰일 때만.\n"
        + "- 상품명에 이미 있는 낱말·브랜드·회사 이름·숫자·용량은 쓰지 마라.\n"
        + "- 원산지·유기농·영양·알레르기·건강 효능·최저가·보관 주의 같은 말은 쓰지 마라.\n"
        + "- 낱말마다 "
        + MAX_TAG_LENGTH
        + "자 이하.\n"
        + "예1) 상품명: 농심 신라면 120g / 카테고리: 면·즉석 > 라면 → {\"tags\": [\"빨간\", \"매운\", \"얼큰한\", \"국물\", \"봉지라면\"]}\n"
        + "예2) 상품명: 3M 포스트잇 노트 / 카테고리: 생활 > 문구 → {\"tags\": [\"메모지\", \"붙이는 메모\", \"사무용품\", \"문구\"]}\n"
        + "JSON 으로만 답하라: {\"tags\": [\"…\"]}\n\n"
        + "상품명: "
        + s.name()
        + "\n카테고리: "
        + String.join(" > ", s.categoryPath())
        + "\n설명: "
        + clip(s.description());
  }

  /** 모델 응답 → 꼬리표(중복·빈 것·긴 것·금지 낱말·상품명에 있는 낱말 빼고 최대 MAX_TAGS). 읽을 수 없으면 빈 목록. */
  public static List<String> parseTags(@Nullable String json, String name, ObjectMapper mapper) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    JsonNode tags;
    try {
      tags = mapper.readTree(json).path("tags");
    } catch (RuntimeException e) {
      return List.of();
    }
    Set<String> out = new LinkedHashSet<>();
    String compactName = name.replaceAll("\\s+", "");
    for (JsonNode t : tags) {
      if (!t.isString()) {
        continue;
      }
      String v = t.asString().strip();
      if (v.isEmpty()
          || v.length() > MAX_TAG_LENGTH
          || compactName.contains(v.replaceAll("\\s+", ""))
          || BANNED.stream().anyMatch(v::contains)) {
        continue;
      }
      out.add(v);
      if (out.size() >= MAX_TAGS) {
        break;
      }
    }
    return new ArrayList<>(out);
  }

  /** 벡터로 만들 글(이름·카테고리·꼬리표·설명 앞부분). */
  public static String embeddingText(Source s, List<String> tags) {
    return s.name()
        + "\n"
        + String.join(" > ", s.categoryPath())
        + (tags.isEmpty() ? "" : "\n" + String.join(", ", tags))
        + (s.description() == null || s.description().isBlank()
            ? ""
            : "\n" + clip(s.description()));
  }
}
