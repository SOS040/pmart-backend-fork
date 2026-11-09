package com.pmart.assistant.infrastructure.ingest;

import com.pmart.assistant.domain.Authority;
import com.pmart.assistant.domain.Lifecycle;
import com.pmart.assistant.domain.RequestHashing;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 색인 대상 명시 목록({@code knowledge/manifest.yml}). 목록에 없는 파일은 색인하지 않는다(새 문서가 조용히 섞이지 않게).
 *
 * <p>문서별: 경로, 업무 영역, 종류(business·adr·flow·state·reference), 기본 지위, 우선순위 등급(domain-map.md:35), 적용
 * 단계, 접근 범위. {@code overrides}: 규칙으로 판정되지 않는 지위·생애 예외 — 근거(reason)를 함께 적는다.
 */
public record KnowledgeManifest(
    int version,
    String defaultAccessScope,
    List<Doc> documents,
    List<Override> overrides,
    String hash) {

  public enum Kind {
    BUSINESS,
    ADR,
    FLOW,
    STATE,
    REFERENCE
  }

  public record Doc(
      String path,
      @Nullable String domain,
      Kind kind,
      @Nullable Authority authority,
      int precedence,
      @Nullable String stage,
      String accessScope) {}

  /**
   * 청크 본문에 {@code contains} 가 들어 있는 {@code path} 의 청크에 적용.
   *
   * @param lifecycle 바꿀 생애 상태
   * @param proposedSupersededBy 대체를 제안한 문서 경로(SUPERSESSION_PROPOSED)
   */
  public record Override(
      String path,
      String contains,
      @Nullable Lifecycle lifecycle,
      @Nullable Authority authority,
      List<String> proposedSupersededBy,
      String reason,
      @Nullable String supersededBy) {}

  public static KnowledgeManifest load(InputStream in) throws IOException {
    byte[] bytes = in.readAllBytes();
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Map<String, Object> root = yaml.load(new String(bytes, StandardCharsets.UTF_8));
    String scope = str(root.get("defaultAccessScope"), "operator");
    List<Doc> docs = new ArrayList<>();
    for (Object o : list(root.get("documents"))) {
      Map<?, ?> m = (Map<?, ?>) o;
      docs.add(
          new Doc(
              str(m.get("path"), null),
              opt(m.get("domain")),
              Kind.valueOf(str(m.get("kind"), "REFERENCE").toUpperCase()),
              m.get("authority") == null ? null : Authority.valueOf(str(m.get("authority"), null)),
              ((Number) m.get("precedence")).intValue(),
              opt(m.get("stage")),
              str(m.get("accessScope"), scope)));
    }
    List<Override> overrides = new ArrayList<>();
    for (Object o : list(root.get("overrides"))) {
      Map<?, ?> m = (Map<?, ?>) o;
      List<String> proposed = new ArrayList<>();
      for (Object p : list(m.get("proposedSupersededBy"))) {
        proposed.add(String.valueOf(p));
      }
      overrides.add(
          new Override(
              str(m.get("path"), null),
              str(m.get("contains"), null),
              m.get("lifecycle") == null ? null : Lifecycle.valueOf(str(m.get("lifecycle"), null)),
              m.get("authority") == null ? null : Authority.valueOf(str(m.get("authority"), null)),
              List.copyOf(proposed),
              str(m.get("reason"), ""),
              opt(m.get("supersededBy"))));
    }
    Number version = (Number) root.getOrDefault("version", 1);
    return new KnowledgeManifest(
        version.intValue(),
        scope,
        List.copyOf(docs),
        List.copyOf(overrides),
        RequestHashing.sha256(bytes));
  }

  private static List<?> list(@Nullable Object o) {
    return o instanceof List<?> l ? l : List.of();
  }

  private static @Nullable String opt(@Nullable Object o) {
    return o == null ? null : String.valueOf(o);
  }

  private static String str(@Nullable Object o, @Nullable String dflt) {
    if (o == null) {
      if (dflt == null) {
        throw new IllegalArgumentException("manifest 필수 값 누락");
      }
      return dflt;
    }
    return String.valueOf(o);
  }
}
