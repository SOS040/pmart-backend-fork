package com.pmart.search.application;

import com.pmart.search.domain.Hangul;
import com.pmart.search.infrastructure.SearchIndex;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 검색어 오타 교정(2026-11-20 ADR-0073). 공백으로 나눈 낱말 중 한글 2글자 이상이고 사전(상품명 낱말)에 없는 것만, 자모 거리(짧으면 1·길면 2)로 가장
 * 가깝고 상품이 많은 사전 낱말로 바꾼다. 고친 것이 없으면 empty. 초성·숫자·영문 낱말은 그대로.
 */
@Service
public class QueryCorrector {

  private final SearchIndex index;

  public QueryCorrector(SearchIndex index) {
    this.index = index;
  }

  public Optional<String> correct(String query) {
    String[] words = query.trim().split("\\s+");
    List<String> candidates = new ArrayList<>();
    for (String w : words) {
      if (w.length() >= 2 && Hangul.isHangulWord(w)) {
        candidates.add(w);
      }
    }
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    Set<String> known = known(candidates);
    boolean changed = false;
    StringBuilder out = new StringBuilder();
    for (String w : words) {
      String fixed = w;
      if (w.length() >= 2 && Hangul.isHangulWord(w) && !known.contains(w)) {
        fixed = closest(w).orElse(w);
        changed |= !fixed.equals(w);
      }
      if (out.length() > 0) {
        out.append(' ');
      }
      out.append(fixed);
    }
    return changed ? Optional.of(out.toString()) : Optional.empty();
  }

  private Set<String> known(List<String> words) {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("size", words.size());
    req.put("_source", List.of("term"));
    req.put("query", Map.of("terms", Map.of("term", words)));
    Set<String> out = new HashSet<>();
    for (JsonNode h : index.search(SearchIndex.SEARCH_TERMS, req).path("hits").path("hits")) {
      out.add(h.path("_source").path("term").asString());
    }
    return out;
  }

  private Optional<String> closest(String word) {
    String jamo = Hangul.jamo(word);
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("size", 1);
    req.put("_source", List.of("term"));
    req.put(
        "query",
        Map.of(
            "fuzzy",
            Map.of(
                "jamo",
                Map.of(
                    "value",
                    jamo,
                    "fuzziness",
                    Hangul.fuzziness(jamo),
                    "prefix_length",
                    1,
                    "transpositions",
                    true))));
    req.put("sort", List.of(Map.of("_score", "desc"), Map.of("df", "desc"), Map.of("term", "asc")));
    for (JsonNode h : index.search(SearchIndex.SEARCH_TERMS, req).path("hits").path("hits")) {
      return Optional.of(h.path("_source").path("term").asString());
    }
    return Optional.empty();
  }
}
