package com.pmart.common.messaging;

import java.util.Locale;
import java.util.regex.Pattern;

/** 토픽 이름 규칙 {@code {domain}.{aggregate}.v{n}} (ADR-0009). aggregate 는 kebab-case 소문자. */
public final class Topics {

  private static final Pattern SEGMENT = Pattern.compile("^[a-z][a-z0-9-]*$");

  private Topics() {}

  public static String of(String domain, String aggregateType, int schemaVersion) {
    String aggregate = toKebab(aggregateType);
    if (!SEGMENT.matcher(domain).matches() || !SEGMENT.matcher(aggregate).matches()) {
      throw new IllegalArgumentException(
          "invalid topic segments: " + domain + ", " + aggregateType);
    }
    return domain + "." + aggregate + ".v" + schemaVersion;
  }

  /** Dead letter topic 이름. */
  public static String deadLetter(String topic) {
    return topic + ".DLT";
  }

  /** PascalCase/camelCase → kebab-case (TransportRequest → transport-request). */
  static String toKebab(String value) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(value.charAt(i - 1))) {
        sb.append('-');
      }
      sb.append(Character.toLowerCase(c));
    }
    return sb.toString().replace('_', '-').toLowerCase(Locale.ROOT);
  }
}
