package com.pmart.search.infrastructure;

import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.search.application.SearchIndexer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 상품(commerce.product.v1: 상품 버전·콘텐츠·판매 상태)·가격(commerce.pricing.v1) 사건 구독. DB 가 없어 inbox 대신 색인 쪽 버전
 * 비교로 멱등하다(SearchIndexer).
 */
@Component
class CatalogEventListener {

  private final EventEnvelopeCodec codec;
  private final SearchIndexer indexer;

  CatalogEventListener(EventEnvelopeCodec codec, SearchIndexer indexer) {
    this.codec = codec;
    this.indexer = indexer;
  }

  @KafkaListener(topics = {"commerce.product.v1", "commerce.pricing.v1"})
  void onMessage(String value) {
    EventEnvelope envelope = codec.decode(value);
    indexer.apply(envelope);
  }
}
