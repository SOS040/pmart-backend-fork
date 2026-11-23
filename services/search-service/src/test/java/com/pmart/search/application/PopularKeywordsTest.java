package com.pmart.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.search.infrastructure.SearchIndex;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 인기 키워드: 설정 없음·집계 실패에도 자동완성이 깨지지 않는다(빈 목록·이전 사본), 사본 시간 동안 다시 부르지 않는다. */
class PopularKeywordsTest {

  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC);
  static final SearchProperties.Popular POLICY =
      new SearchProperties.Popular(Duration.ofDays(7), 10, 2, List.of("숨김"), Duration.ofMinutes(5));

  final SearchIndex index = mock(SearchIndex.class);

  static SearchProperties props(SearchProperties.Popular popular) {
    return new SearchProperties("http://es", "http://product", null, popular, "k", null, null);
  }

  @Test
  void unsetPolicyGivesEmptyWithoutCallingElasticsearch() {
    var popular = new PopularKeywords(index, props(null), CLOCK);
    assertThat(popular.current()).isEmpty();
    var partial =
        new PopularKeywords(
            index,
            props(new SearchProperties.Popular(Duration.ofDays(7), 10, null, List.of(), null)),
            CLOCK);
    assertThat(partial.current()).isEmpty();
    verify(index, never()).search(anyString(), any());
  }

  @Test
  void failureGivesEmptyAndIsNotRetriedUntilRefresh() {
    when(index.search(anyString(), any()))
        .thenThrow(new PmartException(ErrorCode.DEPENDENCY_UNAVAILABLE, "down"));
    var popular = new PopularKeywords(index, props(POLICY), CLOCK);
    assertThat(popular.current()).isEmpty();
    assertThat(popular.current()).isEmpty();
    verify(index, times(1)).search(anyString(), any());
  }

  @Test
  void bucketsBecomeKeywordsAndAreCached() {
    var json =
        new ObjectMapper()
            .readTree(
                """
                {"aggregations":{"k":{"buckets":[
                  {"key":"혼자","doc_count":9,"searchers":{"value":1}},
                  {"key":"숨김","doc_count":8,"searchers":{"value":5}},
                  {"key":"우유","doc_count":4,"searchers":{"value":2}},
                  {"key":"두부","doc_count":3,"searchers":{"value":3}}]}}}
                """);
    when(index.search(anyString(), any())).thenReturn(json);
    var popular = new PopularKeywords(index, props(POLICY), CLOCK);
    assertThat(popular.current()).containsExactly("우유", "두부");
    assertThat(popular.current()).containsExactly("우유", "두부");
    verify(index, times(1)).search(anyString(), any());
  }

  @Test
  void previousSnapshotSurvivesLaterFailure() {
    var json =
        new ObjectMapper()
            .readTree(
                """
                {"aggregations":{"k":{"buckets":[{"key":"우유","doc_count":4,"searchers":{"value":2}}]}}}
                """);
    when(index.search(anyString(), any()))
        .thenReturn(json)
        .thenThrow(new PmartException(ErrorCode.DEPENDENCY_UNAVAILABLE, "down"));
    var popular = new PopularKeywords(index, props(POLICY), CLOCK);
    assertThat(popular.refreshNow()).containsExactly("우유");
    assertThat(popular.refreshNow()).containsExactly("우유");
  }
}
