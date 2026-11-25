package com.pmart.search.application;

import com.pmart.search.infrastructure.OllamaClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 검색어 벡터(ADR-0088) — ollama 로 한 번, 같은 검색어는 최근 {@link #MAX} 개 사본. 설정이 없거나 시간 상한을 넘거나 실패하면 null(검색은
 * 키워드만). 검색어는 로그에 남기지 않는다.
 */
@Service
public class QueryVectors {

  private static final Logger log = LoggerFactory.getLogger(QueryVectors.class);
  static final int MAX = 500;

  private final SemanticProperties props;
  private final OllamaClient ollama;

  private final Map<String, float[]> cache =
      new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> e) {
          return size() > MAX;
        }
      };

  public QueryVectors(SemanticProperties props, OllamaClient ollama) {
    this.props = props;
    this.ollama = ollama;
  }

  public float @Nullable [] of(String query) {
    if (!props.searchReady()) {
      return null;
    }
    synchronized (cache) {
      float[] hit = cache.get(query);
      if (hit != null) {
        return hit;
      }
    }
    try {
      float[] v = ollama.embed(List.of(props.instruction() + query), props.queryTimeout()).get(0);
      synchronized (cache) {
        cache.put(query, v);
      }
      return v;
    } catch (RuntimeException e) {
      log.warn("검색어 벡터 실패 — 키워드만: {}", e.getClass().getSimpleName());
      return null;
    }
  }
}
