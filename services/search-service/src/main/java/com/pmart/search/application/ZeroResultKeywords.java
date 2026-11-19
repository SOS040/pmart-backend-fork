package com.pmart.search.application;

import com.pmart.search.infrastructure.SearchIndex;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 결과 0건 검색어(2026-11-20 ADR-0073 — 운영 화면: 동의어·상품명 개선의 근거). 검색 기록(search-log — 고객·비회원 첫 쪽 검색)에서 최근 N일
 * 결과 0건을 검색어별로 세어 많이 나온 순. 인기 키워드 숨김 목록은 뺀다. 검색자 원문은 없다(해시만 — 수만 센다).
 */
@Service
public class ZeroResultKeywords {

  public record Row(
      String keyword, long searches, long searchers, @Nullable Instant lastSearchedAt) {}

  private final SearchIndex index;
  private final SearchProperties props;
  private final Clock clock;

  public ZeroResultKeywords(SearchIndex index, SearchProperties props, Clock clock) {
    this.index = index;
    this.props = props;
    this.clock = clock;
  }

  public List<Row> list(int days, int size) {
    Instant since = clock.instant().minus(Duration.ofDays(days));
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("size", 0);
    req.put(
        "query",
        Map.of(
            "bool",
            Map.of(
                "filter",
                List.of(
                    Map.of("range", Map.of("at", Map.of("gte", since.toString()))),
                    Map.of("term", Map.of("resultCount", 0))))));
    req.put(
        "aggs",
        Map.of(
            "k",
            Map.of(
                "terms",
                Map.of(
                    "field",
                    "keyword",
                    "size",
                    size + 50,
                    "order",
                    List.of(Map.of("_count", "desc"), Map.of("_key", "asc"))),
                "aggs",
                Map.of(
                    "searchers",
                    Map.of("cardinality", Map.of("field", "searcher", "precision_threshold", 3000)),
                    "last",
                    Map.of("max", Map.of("field", "at"))))));
    Set<String> hidden =
        props.popular() == null || props.popular().hiddenKeywords() == null
            ? Set.of()
            : props.popular().hiddenKeywords().stream()
                .map(h -> h.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    List<Row> out = new ArrayList<>();
    for (JsonNode b :
        index.search(SearchIndex.SEARCH_LOG, req).path("aggregations").path("k").path("buckets")) {
      String k = b.path("key").asString();
      if (hidden.contains(k.strip().toLowerCase(Locale.ROOT))) {
        continue;
      }
      JsonNode last = b.path("last").path("value_as_string");
      out.add(
          new Row(
              k,
              b.path("doc_count").asLong(),
              b.path("searchers").path("value").asLong(),
              last.isMissingNode() || last.isNull() ? null : Instant.parse(last.asString())));
      if (out.size() >= size) {
        break;
      }
    }
    return out;
  }
}
