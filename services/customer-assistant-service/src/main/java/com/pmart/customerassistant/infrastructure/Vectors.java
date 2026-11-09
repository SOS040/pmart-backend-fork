package com.pmart.customerassistant.infrastructure;

/** pgvector 문자열 표기('[1,2,3]')와 float[] 변환. JDBC 에서는 {@code ?::vector} 로 넘긴다. */
public final class Vectors {

  private Vectors() {}

  public static String literal(float[] v) {
    StringBuilder sb = new StringBuilder(v.length * 10).append('[');
    for (int i = 0; i < v.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(v[i]);
    }
    return sb.append(']').toString();
  }

  public static float[] parse(String literal) {
    String body = literal.substring(1, literal.length() - 1);
    if (body.isEmpty()) {
      return new float[0];
    }
    String[] parts = body.split(",");
    float[] v = new float[parts.length];
    for (int i = 0; i < parts.length; i++) {
      v[i] = Float.parseFloat(parts[i]);
    }
    return v;
  }
}
