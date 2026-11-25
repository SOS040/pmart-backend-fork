package com.pmart.search.application;

import com.pmart.search.domain.SemanticRules;
import com.pmart.search.infrastructure.CategoryTreeClient;
import com.pmart.search.infrastructure.OllamaClient;
import com.pmart.search.infrastructure.SearchIndex;
import java.time.Clock;
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
 * 상품 꼬리표·벡터 뒤 작업(ADR-0088). 주기마다 상품 문서를 훑어 원문 해시(이름·카테고리·설명·모델)가 다르거나 벡터가 없는 문서만 — 한 번에 {@code
 * batchSize} 개 — 꼬리표(qwen3:4b) → 벡터(qwen3-embedding)를 만들어 부분 갱신한다. 실패한 상품은 다음 주기에 다시. 한 인스턴스 전제(겹쳐
 * 돌지 않게 synchronized). 상품 글·꼬리표는 로그에 남기지 않는다(건수만).
 */
@Service
public class SemanticEnricher {

  private static final Logger log = LoggerFactory.getLogger(SemanticEnricher.class);

  private final SemanticProperties props;
  private final SearchIndex index;
  private final OllamaClient ollama;
  private final CategoryTreeClient categories;
  private final ObjectMapper mapper;
  private final Clock clock;

  public SemanticEnricher(
      SemanticProperties props,
      SearchIndex index,
      OllamaClient ollama,
      CategoryTreeClient categories,
      ObjectMapper mapper,
      Clock clock) {
    this.props = props;
    this.index = index;
    this.ollama = ollama;
    this.categories = categories;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${pmart.search.semantic.interval:PT2M}",
      initialDelayString = "PT1M")
  public void tick() {
    if (!props.enrichReady()) {
      return;
    }
    try {
      int made = runOnce();
      if (made > 0) {
        log.info("의미 검색 꼬리표·벡터 {}건 만듦", made);
      }
    } catch (RuntimeException e) {
      log.warn("의미 검색 뒤 작업 실패 — 다음 주기에: {}", e.getClass().getSimpleName());
    }
  }

  /** 한 주기. 만든 건수. */
  public synchronized int runOnce() {
    Map<String, Object> req = new LinkedHashMap<>();
    req.put("size", 2000);
    req.put(
        "_source",
        List.of(
            "productId", "name", "description", "originText", "primaryCategoryId", "semanticHash"));
    req.put(
        "query",
        Map.of(
            "bool",
            Map.of(
                "filter",
                List.of(
                    Map.of("exists", Map.of("field", "name")),
                    Map.of("exists", Map.of("field", "productVersion"))))));
    JsonNode res = index.search(SearchIndex.PRODUCTS, req);
    int made = 0;
    int tried =
        0; // 실패도 센다 — ollama 가 계속 실패하면 한 주기에 batch-size 번만 부른다(2026-11-26 LAN: 500 을 연달아 받으며 전 상품을
    // 훑었다)
    for (JsonNode h : res.path("hits").path("hits")) {
      if (tried >= props.batchSize()) {
        break;
      }
      JsonNode d = h.path("_source");
      String id = h.path("_id").asString();
      String primary = d.path("primaryCategoryId").asString(null);
      SemanticRules.Source s =
          new SemanticRules.Source(
              d.path("productId").asString(id),
              d.path("name").asString(""),
              primary == null ? List.of() : categories.path(primary),
              d.path("description").asString(null),
              d.path("originText").asString(null));
      String hash = SemanticRules.hash(s, props.embeddingModel(), props.tagModel());
      if (hash.equals(d.path("semanticHash").asString(null))) {
        continue;
      }
      tried++;
      try {
        List<String> tags =
            SemanticRules.parseTags(
                ollama.generateJson(SemanticRules.tagPrompt(s), props.tagTimeout()),
                s.name(),
                mapper);
        float[] vector =
            ollama.embed(List.of(SemanticRules.embeddingText(s, tags)), props.tagTimeout()).get(0);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("aiTags", String.join(" ", tags));
        doc.put("semanticVector", vector);
        doc.put("semanticHash", hash);
        doc.put("semanticModel", props.embeddingModel() + "+" + props.tagModel());
        doc.put("semanticAtMs", clock.millis());
        index.update(SearchIndex.PRODUCTS, id, doc);
        made++;
      } catch (RuntimeException e) {
        log.warn("의미 검색 상품 하나 실패 — 다음 주기에: {}", e.getClass().getSimpleName());
      }
    }
    return made;
  }
}
