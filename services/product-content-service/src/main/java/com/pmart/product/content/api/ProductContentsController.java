package com.pmart.product.content.api;

import com.pmart.product.content.api.generated.ProductContentsApi;
import com.pmart.product.content.api.generated.model.ListProductContents200Response;
import com.pmart.product.content.api.generated.model.ProductContent;
import com.pmart.product.content.application.ContentQueries;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * 계약 ProductContentsApi — 고객용 상품 상세·카드. 게시본(DISPLAY 승인 결과)만 보인다. 평점 집계(rating-summary)는 review 투영
 * 전이라 아직 501(인터페이스 default)이다.
 */
@RestController
public class ProductContentsController implements ProductContentsApi {

  private final ContentQueries queries;
  private final ObjectMapper mapper;

  public ProductContentsController(ContentQueries queries, ObjectMapper mapper) {
    this.queries = queries;
    this.mapper = mapper;
  }

  /** 평점 집계(0.2.1 — 비회원도). */
  @Override
  public ResponseEntity<com.pmart.product.content.api.generated.model.RatingSummary>
      getRatingSummary(String productId) {
    return ResponseEntity.ok(
        mapper.convertValue(
            queries.ratingSummary(productId),
            com.pmart.product.content.api.generated.model.RatingSummary.class));
  }

  @Override
  public ResponseEntity<ProductContent> getProductContent(
      String productId, @Nullable String mfcId) {
    return ResponseEntity.ok(
        mapper.convertValue(queries.content(productId, mfcId), ProductContent.class));
  }

  @Override
  public ResponseEntity<ListProductContents200Response> listProductContents(
      List<String> productIds, @Nullable String mfcId, @Nullable String include) {
    return ResponseEntity.ok(
        mapper.convertValue(
            queries.cards(productIds, mfcId, "search".equals(include)),
            ListProductContents200Response.class));
  }
}
