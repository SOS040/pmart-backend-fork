package com.pmart.review.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.review.infrastructure.ReviewStore.PhotoRow;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 리뷰 사진 축소본(2026-11-20, 계약 0.4.0 — 리뷰 사진 느림, 사용자 "응 해주는데"). 앱은 2048px·300~550KB 사진을 올리는데 목록 썸네일도 그
 * 원본을 받았다. 폭 240·480·720 JPEG 를 사진 버킷 {@code review-photo-variants/w<폭>/<photoId>.jpg} 에 두고 쓴다 — 사진
 * ID 마다 본문이 바뀌지 않아 다시 만들 일이 없다. 원본이 그 폭보다 작으면 원본 크기 그대로 JPEG(늘 있게). WEBP 는 JDK 가 풀지 못해 원본을 준다.
 *
 * <p>보통은 {@link ReviewPhotoPreparer} 가 올린 뒤 뒤에서 미리 만든다. 요청 때 없으면 그 자리에서 만든다(동시 둘까지 — 디코딩 메모리·CPU
 * 상한).
 */
@Service
public class ReviewPhotoVariants {

  public static final List<Integer> WIDTHS = List.of(240, 480, 720);
  private static final Set<String> RESIZABLE = Set.of("image/jpeg", "image/png");

  /** 원본 바이트 → width 이하 JPEG. 풀 수 없으면 null. */
  public interface Resizer {
    byte @Nullable [] shrink(byte[] original, int width);
  }

  /** 축소본 본문. */
  public record Variant(InputStream body, long length, String etag) {}

  private final ReviewPhotoStorage storage;
  private final Resizer resizer;
  private final Semaphore gate = new Semaphore(2);

  public ReviewPhotoVariants(ReviewPhotoStorage storage, Resizer resizer) {
    this.storage = storage;
    this.resizer = resizer;
  }

  public static void checkWidth(int width) {
    if (!WIDTHS.contains(width)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "w 는 240·480·720 중 하나입니다.");
    }
  }

  static String key(String photoId, int width) {
    return "review-photo-variants/w" + width + "/" + photoId + ".jpg";
  }

  public static boolean resizable(PhotoRow row) {
    return RESIZABLE.contains(row.contentType());
  }

  /** 축소본, 원본을 줘야 하면(WEBP·풀 수 없음) null. */
  public @Nullable Variant variant(PhotoRow row, int width) {
    checkWidth(width);
    if (!resizable(row)) {
      return null;
    }
    String etag = row.sha256() + "-w" + width;
    try {
      byte[] stored = readAll(storage.get(key(row.photoId(), width)));
      return new Variant(new ByteArrayInputStream(stored), stored.length, etag);
    } catch (PmartException e) {
      if (!"PHOTO_NOT_FOUND".equals(e.code())) {
        throw e;
      }
    }
    byte[] out = make(readAll(storage.get(row.objectKey())), width);
    if (out == null) {
      return null;
    }
    storage.put(key(row.photoId(), width), out, "image/jpeg");
    return new Variant(new ByteArrayInputStream(out), out.length, etag);
  }

  /** 세 폭을 모두 만들어 둔다(원본은 한 번 읽는다). 만든 개수 — 풀 수 없거나 WEBP 면 0. */
  public int prepare(PhotoRow row) {
    if (!resizable(row)) {
      return 0;
    }
    byte[] original = readAll(storage.get(row.objectKey()));
    int made = 0;
    for (int width : WIDTHS) {
      byte[] out = make(original, width);
      if (out == null) {
        break;
      }
      storage.put(key(row.photoId(), width), out, "image/jpeg");
      made++;
    }
    return made;
  }

  /** 사진을 지울 때 축소본도(실패는 무시 — 다음 정리 때 원본과 함께 다시). */
  public void delete(String photoId) {
    for (int width : WIDTHS) {
      storage.delete(key(photoId, width));
    }
  }

  private byte @Nullable [] make(byte[] original, int width) {
    try {
      gate.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
    try {
      return resizer.shrink(original, width);
    } finally {
      gate.release();
    }
  }

  private static byte[] readAll(InputStream in) {
    try (in) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
