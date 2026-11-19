package com.pmart.storefront.application;

import java.io.InputStream;

/**
 * 배너 이미지 본문 저장소(S3 호환 — LAN SeaweedFS 버킷 pmart-storefront, 2026-11-20 ADR-0075). 실패는 503
 * DEPENDENCY_UNAVAILABLE.
 */
public interface BannerImageStorage {

  void put(String key, byte[] bytes, String contentType);

  /** 본문. 없으면 404 IMAGE_NOT_FOUND. */
  InputStream get(String key);

  /** 지우기. 실패하면 false(다음 정리 때 다시). */
  boolean delete(String key);
}
