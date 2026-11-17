package com.pmart.customerassistant.infrastructure.ingest;

import com.pmart.customerassistant.domain.ExtractiveComposer;
import com.pmart.customerassistant.domain.RequestHashing;
import com.pmart.customerassistant.infrastructure.ingest.KnowledgeManifest.Doc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 고객 안내 문서 → FAQ 항목(2026-11-18, 계약 0.2.0). 색인과 같은 바이트에서 만들어 같은 스냅숏에 둔다 — FAQ 목록과 AI 답변의 근거가 어긋나지
 * 않는다.
 *
 * <ul>
 *   <li>대상: manifest 의 domain {@value #FAQ_DOMAIN} 문서만.
 *   <li>분류 = 파일 이름(앞 번호 뺌, {@code 02-cancel.md → cancel}), 분류 제목 = 첫 {@code #} 제목.
 *   <li>항목 = {@code ##} 절 하나: 질문 = 절 제목, 답 = 다음 {@code #}·{@code ##} 제목 전까지의 본문({@code ###} 이하는 본문에
 *       남김).
 *   <li>지위 태그([확정 정책] 등)는 뺀다({@link ExtractiveComposer#stripTags}). 다른 안내 문서 링크 {@code
 *       [08](08-…md)} 는 {@code ‘그 문서 제목’} 으로(앱에서 열 수 없는 파일 경로를 보이지 않는다), 그 밖 .md 링크는 글자만.
 * </ul>
 */
public final class FaqExtractor {

  public static final String FAQ_DOMAIN = "customer-faq";

  public record FaqEntry(
      String faqId,
      String category,
      String categoryTitle,
      int categoryOrder,
      int itemOrder,
      String question,
      String answer,
      String searchText,
      String documentId) {}

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
  private static final Pattern MD_LINK =
      Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+\\.md)(?:#[^)\\s]*)?\\)");
  private static final Pattern LEADING_NO = Pattern.compile("^\\d+[-_]");
  private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");

  private FaqExtractor() {}

  /** manifest 순서대로. FAQ 문서가 없으면 빈 목록. */
  public static List<FaqEntry> extract(
      KnowledgeManifest manifest, Map<String, byte[]> bytesByPath) {
    List<Doc> docs =
        manifest.documents().stream().filter(d -> FAQ_DOMAIN.equals(d.domain())).toList();
    Map<String, String> textByPath = new HashMap<>();
    Map<String, String> titleByFileName = new HashMap<>();
    for (Doc d : docs) {
      String text = new String(bytesByPath.get(d.path()), StandardCharsets.UTF_8);
      textByPath.put(d.path(), text);
      titleByFileName.put(fileName(d.path()), title(text, d.path()));
    }
    List<FaqEntry> out = new ArrayList<>();
    int categoryOrder = 0;
    for (Doc d : docs) {
      categoryOrder++;
      out.addAll(entries(d.path(), textByPath.get(d.path()), categoryOrder, titleByFileName::get));
    }
    return out;
  }

  /** 문서 하나의 항목. */
  static List<FaqEntry> entries(
      String path,
      String text,
      int categoryOrder,
      java.util.function.Function<String, String> titleOfFile) {
    String category = category(path);
    String categoryTitle = title(text, path);
    List<FaqEntry> out = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    String question = null;
    List<String> body = new ArrayList<>();
    boolean fence = false;
    int item = 0;
    for (String line : (text + "\n").split("\\R", -1)) {
      if (line.stripLeading().startsWith("```")) {
        fence = !fence;
      }
      Matcher h = fence ? null : HEADING.matcher(line);
      if (h != null && h.matches() && h.group(1).length() <= 2) {
        if (question != null) {
          item++;
          add(
              out,
              ids,
              path,
              category,
              categoryTitle,
              categoryOrder,
              item,
              question,
              body,
              titleOfFile);
        }
        body.clear();
        question = h.group(1).length() == 2 ? h.group(2) : null;
        continue;
      }
      if (question != null) {
        body.add(line);
      }
    }
    if (question != null) {
      item++;
      add(
          out,
          ids,
          path,
          category,
          categoryTitle,
          categoryOrder,
          item,
          question,
          body,
          titleOfFile);
    }
    return out;
  }

  private static void add(
      List<FaqEntry> out,
      Set<String> ids,
      String path,
      String category,
      String categoryTitle,
      int categoryOrder,
      int item,
      String rawQuestion,
      List<String> body,
      java.util.function.Function<String, String> titleOfFile) {
    String question = ExtractiveComposer.stripTags(rawQuestion).strip();
    String answer =
        ExtractiveComposer.stripTags(links(String.join("\n", body), titleOfFile))
            .replaceAll("\\n{3,}", "\n\n")
            .strip();
    if (question.isEmpty() || answer.isEmpty()) {
      return;
    }
    String id = category + "-" + RequestHashing.sha256(question).substring(0, 10);
    String unique = id;
    for (int n = 2; !ids.add(unique); n++) {
      unique = id + "-" + n;
    }
    String plain = answer.replace("**", "").replace("`", "");
    out.add(
        new FaqEntry(
            unique,
            category,
            categoryTitle,
            categoryOrder,
            item,
            question,
            answer,
            (question + "\n" + plain).toLowerCase(Locale.ROOT),
            path));
  }

  /** 다른 안내 문서 링크 → ‘제목’, 모르는 .md 링크 → 글자만. */
  static String links(String text, java.util.function.Function<String, String> titleOfFile) {
    Matcher m = MD_LINK.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      String title = titleOfFile.apply(fileName(m.group(2)));
      String replacement = title == null ? m.group(1) : "‘" + title + "’";
      m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /** 첫 # 제목(지위 태그 뺌). 없으면 분류 이름. */
  static String title(String text, String path) {
    for (String line : text.split("\\R")) {
      Matcher h = HEADING.matcher(line);
      if (h.matches() && h.group(1).length() == 1) {
        return ExtractiveComposer.stripTags(h.group(2)).strip();
      }
    }
    return category(path);
  }

  /** 파일 이름에서 분류: 확장자·앞 번호를 빼고 소문자. 영문·숫자·- 가 아니면 색인 실패(조용히 다른 이름을 만들지 않는다). */
  static String category(String path) {
    String name = fileName(path);
    if (name.toLowerCase(Locale.ROOT).endsWith(".md")) {
      name = name.substring(0, name.length() - 3);
    }
    String slug = LEADING_NO.matcher(name).replaceFirst("").toLowerCase(Locale.ROOT);
    if (!SLUG.matcher(slug).matches()) {
      throw new KnowledgeIngestor.IngestFailedException(
          "FAQ 문서 파일 이름으로 분류를 만들 수 없습니다(영문 소문자·숫자·-): " + path, null);
    }
    return slug;
  }

  private static String fileName(String path) {
    Path p = Path.of(path).getFileName();
    return p == null ? path : p.toString();
  }
}
