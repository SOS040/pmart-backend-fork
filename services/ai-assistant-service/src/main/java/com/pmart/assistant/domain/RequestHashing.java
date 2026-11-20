package com.pmart.assistant.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * 멱등 요청 해시(계약 명시): {@code sha256(정규화 질문 + "\n" + 정규화 context)}.
 *
 * <p>질문은 NFC·앞뒤 공백 제거·연속 공백 하나로. context 는 허용 키(screen·centerId)만 같은 정규화 후 키 정렬 JSON, 없거나 비면 {@code
 * {}}. 허용 밖 키는 해시에 넣지 않는다(계약상 무시).
 */
public final class RequestHashing {

  private RequestHashing() {}

  public static String normalize(@Nullable String text) {
    if (text == null) {
      return "";
    }
    return Normalizer.normalize(text, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
  }

  /** 허용 키만, 정렬된 JSON. 값이 비면 키를 뺀다. */
  public static String normalizedContext(@Nullable String screen, @Nullable String centerId) {
    Map<String, String> sorted = new TreeMap<>();
    if (!normalize(screen).isEmpty()) {
      sorted.put("screen", normalize(screen));
    }
    if (!normalize(centerId).isEmpty()) {
      sorted.put("centerId", normalize(centerId));
    }
    StringBuilder sb = new StringBuilder("{");
    sorted.forEach(
        (k, v) -> {
          if (sb.length() > 1) {
            sb.append(',');
          }
          sb.append('"').append(k).append("\":\"").append(jsonEscape(v)).append('"');
        });
    return sb.append('}').toString();
  }

  /** 세션을 고른 질문(ADR-0084)은 세션 ID 도 해시에 넣는다 — 같은 키로 다른 세션에 보내면 충돌. 세션을 고르지 않으면 예전과 같은 해시. */
  public static String requestHash(
      String question,
      @Nullable String screen,
      @Nullable String centerId,
      @Nullable String sessionId) {
    String base = requestHash(question, screen, centerId);
    return normalize(sessionId).isEmpty()
        ? base
        : sha256(base + "\nsession:" + normalize(sessionId));
  }

  public static String requestHash(
      String question, @Nullable String screen, @Nullable String centerId) {
    return sha256(normalize(question) + "\n" + normalizedContext(screen, centerId));
  }

  public static String sha256(String text) {
    return sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String jsonEscape(String v) {
    StringBuilder sb = new StringBuilder();
    for (char c : v.toCharArray()) {
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.toString();
  }
}
