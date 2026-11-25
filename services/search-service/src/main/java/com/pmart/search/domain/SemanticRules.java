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
      List.of("국산", "국내산", "수입", "원산지", "유기농", "무농약", "최저가", "알레르기", "함유", "무첨가", "건강", "다이어트");

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
            + tagModel;
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

  /** 꼬리표 프롬프트(한국어 상품 → JSON {"tags": [...]}). */
  public static String tagPrompt(Source s) {
    return "너는 한국 온라인 장보기 앱의 검색 꼬리표를 만든다. 아래 상품을 고객이 찾을 때 쓸 만한 짧은 한국어 낱말을 최대 "
        + MAX_TAGS
        + "개 골라라.\n"
        + "- 색(예 빨간·하얀), 맛(매운·단·짠·고소한), 식감(바삭한·쫄깃한), 종류(국물·볶음·면), 쓰임·상황(야식·아침·캠핑·비 오는 날), 대상(아이·어른) 위주.\n"
        + "- 상품명에 이미 있는 낱말은 빼라. 원산지·유기농·영양·알레르기·건강 효능·최저가처럼 사실 확인이 필요한 말은 쓰지 마라.\n"
        + "- 확실하지 않으면 넣지 마라. 낱말마다 "
        + MAX_TAG_LENGTH
        + "자 이하.\n"
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
