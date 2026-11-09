package com.pmart.customerassistant.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 마크다운 표를 모델이 읽기 쉬운 줄로 바꾼다(2026-11-09 고객 평가 C05: 4B 모델이 표에서 한 줄만 골라 "배달팁은 3,000원"). 한 행 = "열 이름: 값,
 * 열 이름: 값" 한 줄. 표가 아닌 줄은 그대로. 원문 저장·인용 발췌는 바꾸지 않는다 — 모델에 넘기는 근거와 인용 검사 근거에만 쓴다.
 */
public final class TableText {

  private TableText() {}

  public static String linearize(String text) {
    String[] lines = text.split("\n", -1);
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < lines.length) {
      if (i + 1 < lines.length && isRow(lines[i]) && isSeparator(lines[i + 1])) {
        List<String> header = cells(lines[i]);
        int j = i + 2;
        while (j < lines.length && isRow(lines[j])) {
          List<String> c = cells(lines[j]);
          StringBuilder sb = new StringBuilder("- ");
          for (int k = 0; k < c.size(); k++) {
            if (k > 0) {
              sb.append(", ");
            }
            String h = k < header.size() ? header.get(k) : "";
            sb.append(h.isEmpty() ? "" : h + ": ").append(c.get(k));
          }
          out.add(sb.toString());
          j++;
        }
        i = j;
      } else {
        out.add(lines[i]);
        i++;
      }
    }
    return String.join("\n", out);
  }

  private static boolean isRow(String line) {
    String t = line.strip();
    return t.startsWith("|") && t.endsWith("|") && t.length() > 1;
  }

  private static boolean isSeparator(String line) {
    return isRow(line) && line.replaceAll("[|:\\-\\s]", "").isEmpty();
  }

  private static List<String> cells(String line) {
    String t = line.strip();
    t = t.substring(1, t.length() - 1);
    List<String> c = new ArrayList<>();
    for (String s : t.split("\\|", -1)) {
      c.add(s.strip().replace("**", ""));
    }
    return c;
  }
}
