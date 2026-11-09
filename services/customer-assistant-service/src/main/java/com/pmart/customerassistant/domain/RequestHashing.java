package com.pmart.customerassistant.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * 멱등 요청 해시(계약 명시): {@code sha256(정규화 질문 + "\n" + {"orderId"} JSON)}.
 *
 * <p>질문은 NFC·앞뒤 공백 제거·연속 공백 하나로. 고른 주문이 없으면 {@code {}}.
 */
public final class RequestHashing {

  private RequestHashing() {}

  public static String normalize(@Nullable String text) {
    if (text == null) {
      return "";
    }
    return Normalizer.normalize(text, Normalizer.Form.NFC).strip().replaceAll("\\s+", " ");
  }

  /** 고른 주문만, JSON. 비면 {@code {}}. */
  public static String normalizedContext(@Nullable String orderId) {
    Map<String, String> sorted = new TreeMap<>();
    if (!normalize(orderId).isEmpty()) {
      sorted.put("orderId", normalize(orderId));
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

  public static String requestHash(String question, @Nullable String orderId) {
    return sha256(normalize(question) + "\n" + normalizedContext(orderId));
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
