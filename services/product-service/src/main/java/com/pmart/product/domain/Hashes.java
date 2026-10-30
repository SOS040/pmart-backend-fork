package com.pmart.product.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 제출 시 잠그는 내용 해시(SHA-256 hex). 승인 행의 draftHash 와 대조해 승인 뒤 바뀐 초안을 반영하지 않는다. */
public final class Hashes {

  private Hashes() {}

  public static String sha256(String... parts) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (String p : parts) {
        md.update(p.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
    }
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
    }
  }
}
