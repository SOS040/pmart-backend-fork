package com.pmart.customerassistant.infrastructure.ingest;

import com.pmart.customerassistant.domain.Authority;
import com.pmart.customerassistant.domain.Lifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * 마크다운 → 청크. 업무 규칙이 깨지지 않는 경계에서만 나눈다.
 *
 * <ul>
 *   <li>제목 계층을 절 경로로 남긴다(고객용: H1 문서 제목부터 — "문서 제목 > 절").
 *   <li>표·코드 블록·목록은 통째로. 콜론으로 끝나는 도입 문장은 뒤 블록(목록·표)과 묶는다(조건 문장과 목록을 함께).
 *   <li>문단 첫머리 지위 태그([확정 정책] 등)가 있으면 그 문단을 따로 청크로 만들고 그 지위를 준다. 태그 없는 문단은 절 지위(제목 태그 → 상위 절 → 문서
 *       기본값).
 *   <li>ADR 의 "## 결정" 아래 번호 항목("1. …")은 항목마다 청크("결정 > N") — 대체가 결정 번호 단위로 적용된다.
 *   <li>"구현 상태 (YYYY-MM-DD…)"·"구현 (YYYY-MM-DD…)" 절은 구현 기록 기준일을 남긴다(지위 축은 그대로).
 *   <li>제목에 "1차 제외" 또는 문단 태그 [1차 제외] → EXCLUDED_FROM_PHASE1.
 *   <li>큰 절은 블록 경계에서만 나눈다. 한 블록이 한도보다 크면 자르지 않고 통째로 둔다.
 * </ul>
 */
public final class MarkdownChunker {

  public record ParsedChunk(
      @Nullable String section,
      String content,
      Authority authority,
      Lifecycle lifecycle,
      @Nullable String implRecordDate,
      @Nullable String implNote,
      @Nullable Integer decisionNo) {}

  public record DocHeader(
      @Nullable String version,
      @Nullable String effectiveDate,
      @Nullable String adrStatusLine,
      List<String> supersessionLines) {}

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
  private static final Pattern IMPL = Pattern.compile("구현(?:\\s*상태)?\\s*\\((\\d{4}-\\d{2}-\\d{2})");
  private static final Pattern DECISION_ITEM = Pattern.compile("^(\\d+)\\.\\s+\\S");
  private static final Pattern VERSION =
      Pattern.compile("(?:문서 버전|버전)\\s*:?\\s*v?(\\d+(?:\\.\\d+)*)");
  private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
  private static final Pattern EXCLUDED_TAG = Pattern.compile("^`?\\[1차 제외[^\\]]*\\]");

  private final int maxChunkChars;

  public MarkdownChunker(int maxChunkChars) {
    this.maxChunkChars = maxChunkChars;
  }

  /** 첫 12줄의 버전·기준일, ADR 상태 줄, "- 대체:" 줄. 없는 값은 만들지 않는다(null). */
  public static DocHeader header(String text) {
    String[] lines = text.split("\\R", -1);
    String version = null;
    String date = null;
    String status = null;
    List<String> supersession = new ArrayList<>();
    for (int i = 0; i < Math.min(12, lines.length); i++) {
      String l = lines[i];
      if (l.matches("^\\s*-\\s*상태\\s*:.*")) {
        status = l;
        Matcher d = DATE.matcher(l);
        if (d.find() && date == null) {
          date = d.group(1);
        }
        continue;
      }
      if (l.matches("^\\s*-\\s*대체\\s*:.*")) {
        supersession.add(l);
        continue;
      }
      if (l.startsWith("#") || l.startsWith(">")) {
        continue;
      }
      Matcher v = VERSION.matcher(l);
      if (version == null && v.find()) {
        version = v.group(1);
        Matcher d = DATE.matcher(l);
        if (date == null && d.find()) {
          date = d.group(1);
        }
      }
    }
    return new DocHeader(version, date, status, List.copyOf(supersession));
  }

  public List<ParsedChunk> chunk(String text, Authority documentDefault, boolean adr) {
    List<ParsedChunk> out = new ArrayList<>();
    String[] lines = text.split("\\R", -1);
    Section root = new Section(0, null, documentDefault, Lifecycle.ACTIVE, null, null);
    List<Section> stack = new ArrayList<>(List.of(root));
    List<String> body = new ArrayList<>();
    boolean fence = false;
    for (String line : lines) {
      if (line.stripLeading().startsWith("```")) {
        fence = !fence;
        body.add(line);
        continue;
      }
      Matcher h = fence ? null : HEADING.matcher(line);
      if (h != null && h.matches()) {
        flush(stack.getLast(), stack, body, adr, out);
        body.clear();
        int level = h.group(1).length();
        String title = h.group(2).strip();
        while (stack.getLast().level >= level) {
          stack.removeLast();
        }
        stack.add(Section.child(stack.getLast(), level, title));
        continue;
      }
      body.add(line);
    }
    flush(stack.getLast(), stack, body, adr, out);
    return out;
  }

  private void flush(
      Section sec, List<Section> stack, List<String> body, boolean adr, List<ParsedChunk> out) {
    List<String> blocks = blocks(body);
    if (blocks.isEmpty()) {
      return;
    }
    @Nullable String path = sectionPath(stack);
    if (adr && "결정".equals(sec.title)) {
      decisionChunks(sec, path, body, out);
      return;
    }
    StringBuilder pending = new StringBuilder();
    for (String block : blocks) {
      Optional<Authority> tag = Authority.fromLeadingTag(block);
      boolean excluded = EXCLUDED_TAG.matcher(block.stripLeading()).find();
      if (tag.isPresent() || excluded) {
        emit(sec, path, pending, sec.authority, sec.lifecycle, null, out);
        Lifecycle lc = excluded ? Lifecycle.EXCLUDED_FROM_PHASE1 : sec.lifecycle;
        out.add(
            new ParsedChunk(
                path, block, tag.orElse(sec.authority), lc, sec.implDate, sec.implNote, null));
        continue;
      }
      if (pending.length() > 0 && pending.length() + block.length() > maxChunkChars) {
        emit(sec, path, pending, sec.authority, sec.lifecycle, null, out);
      }
      if (pending.length() > 0) {
        pending.append("\n\n");
      }
      pending.append(block);
    }
    emit(sec, path, pending, sec.authority, sec.lifecycle, null, out);
  }

  private void decisionChunks(
      Section sec, @Nullable String path, List<String> body, List<ParsedChunk> out) {
    StringBuilder pending = new StringBuilder();
    Integer current = null;
    for (String line : body) {
      Matcher m = DECISION_ITEM.matcher(line);
      if (m.find()) {
        emit(
            sec,
            current == null ? path : path + " > " + current,
            pending,
            sec.authority,
            sec.lifecycle,
            current,
            out);
        current = Integer.parseInt(m.group(1));
      }
      if (pending.length() > 0 || !line.isBlank()) {
        pending.append(line).append('\n');
      }
    }
    emit(
        sec,
        current == null ? path : path + " > " + current,
        pending,
        sec.authority,
        sec.lifecycle,
        current,
        out);
  }

  private static void emit(
      Section sec,
      @Nullable String path,
      StringBuilder pending,
      Authority authority,
      Lifecycle lifecycle,
      @Nullable Integer decisionNo,
      List<ParsedChunk> out) {
    String content = pending.toString().strip();
    pending.setLength(0);
    if (content.isEmpty()) {
      return;
    }
    out.add(
        new ParsedChunk(
            path, content, authority, lifecycle, sec.implDate, sec.implNote, decisionNo));
  }

  /** 빈 줄로 나눈 블록. 표·목록·코드 블록은 이어진 줄이라 한 블록. 콜론으로 끝나는 블록은 다음 블록과 합친다. */
  static List<String> blocks(List<String> body) {
    List<String> raw = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean fence = false;
    for (String line : body) {
      if (line.stripLeading().startsWith("```")) {
        fence = !fence;
      }
      if (!fence && line.isBlank()) {
        if (cur.length() > 0) {
          raw.add(cur.toString().stripTrailing());
          cur.setLength(0);
        }
        continue;
      }
      cur.append(line).append('\n');
    }
    if (cur.length() > 0) {
      raw.add(cur.toString().stripTrailing());
    }
    List<String> merged = new ArrayList<>();
    for (String b : raw) {
      if (!merged.isEmpty() && introduces(merged.getLast())) {
        merged.set(merged.size() - 1, merged.getLast() + "\n\n" + b);
      } else {
        merged.add(b);
      }
    }
    return merged;
  }

  private static boolean introduces(String block) {
    String t = block.stripTrailing();
    return t.endsWith(":") || t.endsWith("：") || t.endsWith("다음과 같다.") || t.endsWith("아래와 같다.");
  }

  private static @Nullable String sectionPath(List<Section> stack) {
    List<String> titles = new ArrayList<>();
    // 고객용: H1(문서 제목)부터 — 인용에 "문서 제목 > 절 제목" 으로 보인다(운영자용은 H2 부터)
    for (Section s : stack) {
      if (s.level >= 1) {
        titles.add(s.title);
      }
    }
    return titles.isEmpty() ? null : String.join(" > ", titles);
  }

  private record Section(
      int level,
      @Nullable String title,
      Authority authority,
      Lifecycle lifecycle,
      @Nullable String implDate,
      @Nullable String implNote) {

    static Section child(Section parent, int level, String title) {
      Authority a = Authority.fromHeadingTag(title).orElse(parent.authority);
      Lifecycle lc = title.contains("1차 제외") ? Lifecycle.EXCLUDED_FROM_PHASE1 : parent.lifecycle;
      Matcher impl = IMPL.matcher(title);
      String implDate = parent.implDate;
      String implNote = parent.implNote;
      if (impl.find()) {
        implDate = impl.group(1);
        implNote = title.length() > 200 ? title.substring(0, 200) : title;
      }
      return new Section(level, title, a, lc, implDate, implNote);
    }
  }
}
