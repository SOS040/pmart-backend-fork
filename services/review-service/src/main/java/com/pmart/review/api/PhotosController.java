package com.pmart.review.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.review.api.generated.PhotosApi;
import com.pmart.review.api.generated.model.ReviewPhoto;
import com.pmart.review.application.ReviewService;
import java.io.IOException;
import java.time.Duration;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 계약 PhotosApi — 리뷰 사진 올리기(본인)·본문(공개 리뷰에 붙은 것만). 사진 ID 마다 본문이 바뀌지 않아 길게 캐시한다. */
@RestController
public class PhotosController implements PhotosApi {

  private final ReviewService reviews;

  public PhotosController(ReviewService reviews) {
    this.reviews = reviews;
  }

  @Override
  public ResponseEntity<ReviewPhoto> uploadReviewPhoto(
      String idempotencyKey, String xSourceSystem, MultipartFile file) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "사진을 읽을 수 없습니다.");
    }
    var p = reviews.uploadPhoto(idempotencyKey, xSourceSystem, bytes);
    return ResponseEntity.status(HttpStatus.CREATED).body(ReviewViews.photo(p));
  }

  @Override
  public ResponseEntity<Resource> getReviewPhoto(String photoId, Integer w) {
    var p = reviews.photo(photoId, w);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(p.contentType()))
        .contentLength(p.length())
        .eTag("\"" + p.etag() + "\"")
        // 삭제되면 닫혀야 하므로 공유 캐시에는 두지 않는다(private)
        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
        .body(new InputStreamResource(p.body()));
  }
}
