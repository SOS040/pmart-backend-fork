package com.pmart.review.application;

import java.io.InputStream;

/** 리뷰 사진 본문 저장소(S3 호환 — LAN SeaweedFS 버킷 pmart-review). 실패는 503 DEPENDENCY_UNAVAILABLE. */
public interface ReviewPhotoStorage {

  void put(String key, byte[] bytes, String contentType);

  /** 본문. 없으면 404 PHOTO_NOT_FOUND. */
  InputStream get(String key);

  /** 지우기. 실패하면 false(다음 정리 때 다시). */
  boolean delete(String key);
}
