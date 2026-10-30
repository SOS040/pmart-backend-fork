package com.pmart.product.content.domain;

import java.util.Optional;

/** 허용 이미지 형식. 파일 이름·요청 헤더가 아니라 파일 앞부분(시그니처)으로 판정한다. */
public enum ImageFormat {
  JPEG("image/jpeg", "jpg"),
  PNG("image/png", "png"),
  WEBP("image/webp", "webp");

  private final String contentType;
  private final String extension;

  ImageFormat(String contentType, String extension) {
    this.contentType = contentType;
    this.extension = extension;
  }

  public String contentType() {
    return contentType;
  }

  public String extension() {
    return extension;
  }

  public static Optional<ImageFormat> detect(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return Optional.of(JPEG);
    }
    if (b.length >= 8
        && (b[0] & 0xFF) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && b[4] == 0x0D
        && b[5] == 0x0A
        && b[6] == 0x1A
        && b[7] == 0x0A) {
      return Optional.of(PNG);
    }
    if (b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') {
      return Optional.of(WEBP);
    }
    return Optional.empty();
  }
}
