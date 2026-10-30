package com.pmart.product.content.api;

import com.pmart.product.content.api.generated.ContentImagesApi;
import com.pmart.product.content.application.ContentQueries;
import java.time.Duration;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 ContentImagesApi — 비공개 버킷의 이미지 본문을 대신 읽어 준다(presigned URL 을 내주지 않는다: 저장소 주소·자격 증명을 클라이언트에 노출하지
 * 않고, 같은 권한 검사를 거치게 한다). 내용 주소라 오래 캐시한다.
 */
@RestController
public class ContentImagesController implements ContentImagesApi {

  private final ContentQueries queries;

  public ContentImagesController(ContentQueries queries) {
    this.queries = queries;
  }

  @Override
  public ResponseEntity<Resource> getContentImage(String imageId) {
    ContentQueries.Image img = queries.image(imageId);
    var b =
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(img.meta().contentType()))
            .eTag("\"" + img.meta().sha256() + "\"")
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable());
    if (img.body().length() >= 0) {
      b = b.contentLength(img.body().length());
    }
    return b.body(new InputStreamResource(img.body().body()));
  }
}
