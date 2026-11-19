package com.pmart.product.content.application;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.product.content.infrastructure.DraftStore.ImageRow;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 휴대폰용 축소 이미지(2026-11-19, 계약 0.3.0 — 상품 상세 느림, 사용자 "1 -> 해줘"). 폭 240·480·720 중 하나로, 원본보다 작을 때만 줄인
 * JPEG 를 처음 요청 때 만들어 저장소 {@code variants/w<폭>/<sha256>.jpg} 에 두고 다시 쓴다(내용 주소라 바뀌지 않는다). 원본이 그 폭
 * 이하·줄일 수 없는 형식(WEBP)·풀 수 없는 본문이면 null(원본을 준다). 만들기는 동시에 넷까지(디코딩 메모리·CPU 상한 — 2026-11-20 둘 → 넷, PC
 * 2 논리 CPU 12). 2026-11-20 부터는 {@link VariantPreparer} 가 올린 이미지의 축소본을 뒤에서 미리 만들어 두어, 처음 보는 상품도 요청 때
 * 만들지 않는다 — 여기서 만드는 것은 미리 만들기 전의 짧은 틈뿐.
 */
@Service
public class ImageVariants {

  private static final Logger log = LoggerFactory.getLogger(ImageVariants.class);

  public static final Set<Integer> WIDTHS = Set.of(240, 480, 720);

  /** 원본 바이트를 width 로 줄인 JPEG. 풀 수 없거나 원본 폭이 width 이하면 null. */
  public interface Resizer {
    byte @Nullable [] shrink(byte[] original, int width);
  }

  public record Variant(ImageStorage.Stored body, String etag) {}

  private final ImageStorage storage;
  private final Resizer resizer;
  private final Semaphore gate = new Semaphore(4);

  public ImageVariants(ImageStorage storage, Resizer resizer) {
    this.storage = storage;
    this.resizer = resizer;
  }

  public static void checkWidth(Integer width) {
    if (!WIDTHS.contains(width)) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "w 는 240·480·720 중 하나입니다.");
    }
  }

  static String key(String sha256, int width) {
    return "variants/w" + width + "/" + sha256 + ".jpg";
  }

  /**
   * 그 이미지 내용의 축소본(240·480·720 중 원본보다 작은 폭)을 저장소에 없으면 모두 만든다(원본은 한 번만 읽는다). 줄일 수 없는 형식이면 아무것도 하지
   * 않는다. 만든 개수.
   */
  public int prepare(ImageRow row) {
    if (!"image/jpeg".equals(row.contentType()) && !"image/png".equals(row.contentType())) {
      return 0;
    }
    byte[] original = null;
    int made = 0;
    for (int width : List.of(240, 480, 720)) {
      if (row.width() != null && row.width() <= width) {
        continue;
      }
      String key = key(row.sha256(), width);
      if (storage.exists(key)) {
        continue;
      }
      if (original == null) {
        original = readOriginal(row);
      }
      byte[] out = shrink(original, width);
      if (out == null) {
        break; // 풀 수 없거나 원본이 더 작다 — 더 큰 폭도 같다
      }
      storage.put(key, out, "image/jpeg");
      made++;
    }
    return made;
  }

  private byte[] readOriginal(ImageRow row) {
    try (InputStream in = storage.open(row.bucket(), row.objectKey()).body()) {
      return in.readAllBytes();
    } catch (NoSuchElementException e) {
      throw PmartException.notFound("IMAGE_NOT_FOUND", "이미지 본문이 저장소에 없습니다.");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private byte @Nullable [] shrink(byte[] original, int width) {
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

  /** 축소본, 원본을 줘야 하면 null. */
  public @Nullable Variant variant(ImageRow row, int width) {
    checkWidth(width);
    if (row.width() != null && row.width() <= width) {
      return null;
    }
    if (!"image/jpeg".equals(row.contentType()) && !"image/png".equals(row.contentType())) {
      return null; // WEBP 는 JDK 가 풀지 못한다 — 원본
    }
    String etag = row.sha256() + "-w" + width;
    String key = key(row.sha256(), width);
    try {
      return new Variant(storage.open(storage.bucket(), key), etag);
    } catch (NoSuchElementException e) {
      // 처음 — 만든다
    }
    byte[] original = readOriginal(row);
    byte[] out = shrink(original, width);
    if (out == null) {
      return null;
    }
    storage.put(key, out, "image/jpeg");
    log.info(
        "image variant made image={} w={} {}B -> {}B",
        row.imageId(),
        width,
        original.length,
        out.length);
    return new Variant(new ImageStorage.Stored(new ByteArrayInputStream(out), out.length), etag);
  }
}
