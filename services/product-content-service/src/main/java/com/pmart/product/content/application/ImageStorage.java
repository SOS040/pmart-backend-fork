package com.pmart.product.content.application;

import java.io.InputStream;

/** 이미지 본체 저장소(S3 호환, ADR-0016). 구현은 infrastructure 의 S3 어댑터. */
public interface ImageStorage {

  /** 새 객체를 쓰는 버킷. */
  String bucket();

  boolean exists(String key);

  void put(String key, byte[] bytes, String contentType);

  /** 호출자가 닫는다. 없으면 {@link java.util.NoSuchElementException}. */
  Stored open(String bucket, String key);

  record Stored(InputStream body, long length) {}
}
