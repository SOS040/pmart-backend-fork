package com.pmart.pricing.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 업로드한 묶음(zip). 파일 전체를 메모리에 올리지 않는다 — 해시는 스트림으로 한 번 읽어 내고, 읽을 때마다 {@link #open()} 으로 다시 연다 (Spring
 * 은 업로드를 임시 파일로 받는다). 100MB 넘는 묶음을 {@code getBytes()} 로 읽다 direct buffer 메모리 부족으로 500 이 났다
 * (2026-11-04, BR-48).
 *
 * @param sha256 파일 내용의 SHA-256 hex(멱등 판정용 — 예전 {@code sha256(byte[])} 와 같은 값)
 */
public record UploadedBundle(String sha256, Opener opener) {

  /** 묶음을 처음부터 읽는 스트림을 연다. 부를 때마다 새 스트림. */
  @FunctionalInterface
  public interface Opener {
    InputStream open() throws IOException;
  }

  public InputStream open() {
    try {
      return opener.open();
    } catch (IOException e) {
      throw new UncheckedIOException("업로드 파일을 다시 열 수 없습니다", e);
    }
  }

  /** 스트림을 한 번 읽어 해시를 내고, 같은 opener 를 묶음 읽기에 쓴다. */
  public static UploadedBundle from(Opener opener) throws IOException {
    try (InputStream in = opener.open()) {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      try (DigestOutputStream out = new DigestOutputStream(OutputStream.nullOutputStream(), md)) {
        in.transferTo(out);
      }
      return new UploadedBundle(HexFormat.of().formatHex(md.digest()), opener);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
    }
  }

  /** 시험·작은 입력용. */
  public static UploadedBundle of(byte[] bytes) {
    try {
      return from(() -> new ByteArrayInputStream(bytes));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
