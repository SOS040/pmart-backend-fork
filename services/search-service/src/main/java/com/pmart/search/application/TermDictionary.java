package com.pmart.search.application;

import com.pmart.search.domain.Hangul;
import com.pmart.search.infrastructure.SearchIndex;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 오타 교정 낱말 사전(2026-11-20 ADR-0073). 진열 가능한 상품의 이름 낱말(이름에 쓰인 낱말 그대로 + 색인 분석기 nori 낱말, 한글 2글자 이상)을 세어
 * {@code search-terms} (낱말·자모·상품 수)에 둔다. 10분마다 다시 만든다(새 상품 이름도 몇 분 안에 교정 대상) — 이번에 안 나온 낱말은 지운다.
 */
@Service
public class TermDictionary {

  private static final Logger log = LoggerFactory.getLogger(TermDictionary.class);
  private static final int BATCH = 100;

  private final SearchIndex index;
  private final ObjectMapper mapper;
  private final Clock clock;

  public TermDictionary(SearchIndex index, ObjectMapper mapper, Clock clock) {
    this.index = index;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
  public void scheduled() {
    try {
      rebuild();
    } catch (RuntimeException e) {
      log.warn("오타 사전 다시 만들기 실패 — 다음 차례에: {}", e.toString());
    }
  }

  /** 다시 만든다. 낱말 수. */
  public int rebuild() {
    Instant now = clock.instant();
    List<String> ids = new ArrayList<>();
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("size", com.pmart.search.domain.SearchRules.MAX_WINDOW);
    req.put("_source", List.of("name"));
    req.put(
        "query",
        Map.of(
            "bool",
            Map.of(
                "filter",
                List.of(Map.of("exists", Map.of("field", "name"))),
                "must_not",
                List.of(
                    Map.of(
                        "term",
                        Map.of("saleState", com.pmart.search.domain.SearchRules.SALE_STOPPED))))));
    Map<String, Integer> df = new HashMap<>();
    for (JsonNode h : index.search(SearchIndex.PRODUCTS, req).path("hits").path("hits")) {
      ids.add(h.path("_id").asString());
      // 상품명에 쓰인 낱말 그대로(한글만 이어진 2글자 이상) — nori 가 "순두부" 를 "순·두부" 로 나눠도 "순두부" 가 사전에 있게
      java.util.Arrays.stream(h.path("_source").path("name").asString("").split("[^가-힣]+"))
          .filter(t -> t.length() >= 2)
          .distinct()
          .forEach(t -> df.merge(t, 1, Integer::sum));
    }
    for (int i = 0; i < ids.size(); i += BATCH) {
      for (List<String> terms :
          index
              .fieldTerms(
                  SearchIndex.PRODUCTS, ids.subList(i, Math.min(ids.size(), i + BATCH)), "name")
              .values()) {
        // nori 낱말 — 이름 그대로의 낱말과 겹치면 한 번만 더하지 않도록 이미 센 상품 낱말은 건너뛴다(근사 — 상품 수 순위용)
        terms.stream()
            .filter(t -> t.length() >= 2 && Hangul.isHangulWord(t))
            .distinct()
            .forEach(t -> df.putIfAbsent(t, 1));
      }
    }
    List<String> lines = new ArrayList<>();
    for (var e : df.entrySet()) {
      lines.add(mapper.writeValueAsString(Map.of("index", Map.of("_id", e.getKey()))));
      lines.add(
          mapper.writeValueAsString(
              Map.of(
                  "term", e.getKey(),
                  "jamo", Hangul.jamo(e.getKey()),
                  "df", e.getValue(),
                  "builtAt", now.toString())));
    }
    for (int i = 0; i < lines.size(); i += 1000) {
      index.bulk(SearchIndex.SEARCH_TERMS, lines.subList(i, Math.min(lines.size(), i + 1000)));
    }
    index.deleteByQuery(
        SearchIndex.SEARCH_TERMS, Map.of("range", Map.of("builtAt", Map.of("lt", now.toString()))));
    log.info("오타 사전 {} 낱말(상품 {})", df.size(), ids.size());
    return df.size();
  }
}
