package com.pmart.customer.application;

import java.io.InputStream;

/** 프로필 사진 사본 저장소(S3 호환, 비공개 버킷). 구현은 infrastructure 의 S3 어댑터. */
public interface ProfileImageStorage {

  void put(String key, byte[] bytes, String contentType);

  /** 호출자가 닫는다. 없으면 {@link java.util.NoSuchElementException}. */
  Stored open(String key);

  /** 없어도 성공. */
  void delete(String key);

  record Stored(InputStream body, long length) {}
}
