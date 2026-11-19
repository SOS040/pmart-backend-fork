package com.pmart.product.content.api;

import com.pmart.product.content.api.generated.ContentImagesApi;
import com.pmart.product.content.application.ContentQueries;
import com.pmart.product.content.application.ImageStorage;
import com.pmart.product.content.application.ImageVariants;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 ContentImagesApi — 비공개 버킷의 이미지 본문을 대신 읽어 준다(presigned URL 을 내주지 않는다: 저장소 주소·자격 증명을 클라이언트에 노출하지
 * 않고, 같은 권한 검사를 거치게 한다). 내용 주소라 오래 캐시한다. 0.3.0(2026-11-19): {@code ?w=} 휴대폰용 축소본({@link
 * ImageVariants}).
 */
@RestController
public class ContentImagesController implements ContentImagesApi {

  private final ContentQueries queries;
  private final ImageVariants variants;

  public ContentImagesController(ContentQueries queries, ImageVariants variants) {
    this.queries = queries;
    this.variants = variants;
  }

  @Override
  public ResponseEntity<Resource> getContentImage(String imageId, @Nullable Integer w) {
    if (w != null) {
      // 0.3.0 축소본 — 원본 폭 이하·줄일 수 없으면 아래 원본으로
      ImageVariants.checkWidth(w);
      ImageVariants.Variant v = variants.variant(queries.visibleImage(imageId), w);
      if (v != null) {
        return body(v.body(), "image/jpeg", v.etag());
      }
    }
    ContentQueries.Image img = queries.image(imageId);
    return body(img.body(), img.meta().contentType(), img.meta().sha256());
  }

  private static ResponseEntity<Resource> body(
      ImageStorage.Stored stored, String contentType, String etag) {
    var b =
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .eTag("\"" + etag + "\"")
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable());
    if (stored.length() >= 0) {
      b = b.contentLength(stored.length());
    }
    return b.body(new InputStreamResource(stored.body()));
  }
}
