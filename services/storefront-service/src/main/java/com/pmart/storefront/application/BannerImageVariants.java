package com.pmart.storefront.application;

import com.pmart.common.web.PmartException;
import com.pmart.storefront.infrastructure.LayoutStore.ImageRow;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * 배너 그림 축소본(2026-11-25, 계약 0.16.0 — 클라이언트 BR-99 "홈 배너가 장당 2MB PNG"). 요청 폭을 360·720·1080·1440 중 그
 * 이상인 가장 작은 단계로 올려 JPEG 를 배너 버킷 {@code banner-variants/w<단계>/<sha256>.jpg} 에 두고 쓴다 — 내용 주소라 다시 만들
 * 일이 없다. 원본이 그 단계보다 작으면 원본 크기 그대로 JPEG(늘 있게). WEBP·풀 수 없는 본문이면 원본을 준다. 이미 올라간 배너도 첫 요청 때 만든다(리뷰 사진
 * ReviewPhotoVariants 와 같은 방식, 동시 둘까지 — 디코딩 메모리·CPU 상한).
 */
@Service
public class BannerImageVariants {

  public static final List<Integer> STEPS = List.of(360, 720, 1080, 1440);
  private static final Set<String> RESIZABLE = Set.of("image/jpeg", "image/png");

  /** 원본 바이트 → width 이하 JPEG. 풀 수 없으면 null. */
  public interface Resizer {
    byte @Nullable [] shrink(byte[] original, int width);
  }

  /** 축소본 본문. */
  public record Variant(byte[] body, String etag) {}

  private final BannerImageStorage storage;
  private final Resizer resizer;
  private final Semaphore gate = new Semaphore(2);

  public BannerImageVariants(BannerImageStorage storage, Resizer resizer) {
    this.storage = storage;
    this.resizer = resizer;
  }

  /** 요청 폭 → 단계(그 이상인 가장 작은 단계, 넘으면 가장 큰 단계). */
  public static int step(int width) {
    for (int s : STEPS) {
      if (width <= s) {
        return s;
      }
    }
    return STEPS.get(STEPS.size() - 1);
  }

  static String key(String sha256, int step) {
    return "banner-variants/w" + step + "/" + sha256 + ".jpg";
  }

  /** 축소본. 원본을 줘야 하면(WEBP·풀 수 없음·줄이 김) null. */
  public @Nullable Variant variant(ImageRow row, InputStream original, int width) {
    if (!RESIZABLE.contains(row.contentType())) {
      close(original);
      return null;
    }
    int step = step(width);
    String etag = row.sha256() + "-w" + step;
    String key = key(row.sha256(), step);
    try {
      byte[] stored = readAll(storage.get(key));
      close(original);
      return new Variant(stored, etag);
    } catch (PmartException e) {
      if (!"IMAGE_NOT_FOUND".equals(e.code())) {
        close(original);
        throw e;
      }
    }
    byte[] out = make(readAll(original), step);
    if (out == null) {
      return null;
    }
    storage.put(key, out, "image/jpeg");
    return new Variant(out, etag);
  }

  private byte @Nullable [] make(byte[] original, int width) {
    try {
      // 줄이 길면 기다리지 않고 원본을 준다(그림은 게이트웨이 처리율 제한 밖 — 요청 스레드가 쌓이지 않게)
      if (!gate.tryAcquire(5, TimeUnit.SECONDS)) {
        return null;
      }
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

  private static void close(InputStream in) {
    try {
      in.close();
    } catch (IOException ignored) {
      // 읽지 않은 원본 — 닫기 실패는 무시
    }
  }
}
