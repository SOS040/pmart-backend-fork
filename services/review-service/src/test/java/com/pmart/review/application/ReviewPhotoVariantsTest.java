package com.pmart.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.web.PmartException;
import com.pmart.review.infrastructure.JpegResizer;
import com.pmart.review.infrastructure.ReviewStore.PhotoRow;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 리뷰 사진 축소본(2026-11-20, 계약 0.4.0): 실제 JPEG 를 줄이고 저장해 다시 쓰고, 미리 만들기는 세 폭, WEBP 는 원본, 지우기. */
class ReviewPhotoVariantsTest {

  final Map<String, byte[]> objects = new HashMap<>();

  final ReviewPhotoStorage storage =
      new ReviewPhotoStorage() {
        @Override
        public void put(String key, byte[] bytes, String contentType) {
          objects.put(key, bytes);
        }

        @Override
        public InputStream get(String key) {
          byte[] b = objects.get(key);
          if (b == null) {
            throw PmartException.notFound("PHOTO_NOT_FOUND", "사진이 없습니다.");
          }
          return new ByteArrayInputStream(b);
        }

        @Override
        public boolean delete(String key) {
          return objects.remove(key) != null;
        }
      };

  final ReviewPhotoVariants variants = new ReviewPhotoVariants(storage, new JpegResizer());

  static byte[] jpeg(int w, int h) throws Exception {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        img.setRGB(x, y, (x * 255 / w) << 16 | (y * 255 / h) << 8 | 0x40);
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "jpg", out);
    return out.toByteArray();
  }

  static PhotoRow row(String type) {
    return new PhotoRow(
        "RPH-1",
        "CU-1",
        "review-photos/CU-1/RPH-1",
        type,
        100,
        "s".repeat(64),
        Instant.now(),
        null);
  }

  static int width(byte[] b) throws Exception {
    return ImageIO.read(new ByteArrayInputStream(b)).getWidth();
  }

  @Test
  void shrinksStoresAndReuses() throws Exception {
    objects.put("review-photos/CU-1/RPH-1", jpeg(2048, 1536));
    var v = variants.variant(row("image/jpeg"), 240);
    assertThat(v).isNotNull();
    byte[] first = v.body().readAllBytes();
    assertThat(width(first)).isEqualTo(240);
    assertThat(v.etag()).endsWith("-w240");
    assertThat(objects).containsKey("review-photo-variants/w240/RPH-1.jpg");
    // 원본을 지워도 저장된 축소본을 쓴다
    objects.remove("review-photos/CU-1/RPH-1");
    assertThat(variants.variant(row("image/jpeg"), 240).body().readAllBytes()).isEqualTo(first);
  }

  @Test
  void preparesThreeWidthsAndKeepsSmallOriginalSize() throws Exception {
    objects.put("review-photos/CU-1/RPH-1", jpeg(600, 400));
    assertThat(variants.prepare(row("image/jpeg"))).isEqualTo(3);
    assertThat(width(objects.get("review-photo-variants/w240/RPH-1.jpg"))).isEqualTo(240);
    assertThat(width(objects.get("review-photo-variants/w480/RPH-1.jpg"))).isEqualTo(480);
    assertThat(width(objects.get("review-photo-variants/w720/RPH-1.jpg"))).isEqualTo(600);
    variants.delete("RPH-1");
    assertThat(objects).containsOnlyKeys("review-photos/CU-1/RPH-1");
  }

  @Test
  void webpAndUndecodableGiveOriginal() {
    objects.put("review-photos/CU-1/RPH-1", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1});
    assertThat(variants.variant(row("image/webp"), 240)).isNull();
    assertThat(variants.variant(row("image/jpeg"), 240)).isNull();
    assertThat(variants.prepare(row("image/jpeg"))).isZero();
    assertThatThrownBy(() -> ReviewPhotoVariants.checkWidth(1000))
        .isInstanceOf(PmartException.class)
        .hasMessageContaining("240");
  }

  /** 머리말만 있는 거대한 PNG(20000×20000) — 다 풀면 1.6GB. 풀지 않고 원본(null), 미리 만들기 0. */
  static byte[] pngBomb(int w, int h) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10});
    java.nio.ByteBuffer ihdr = java.nio.ByteBuffer.allocate(17);
    ihdr.put("IHDR".getBytes())
        .putInt(w)
        .putInt(h)
        .put((byte) 8)
        .put((byte) 2)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 0);
    out.write(java.nio.ByteBuffer.allocate(4).putInt(13).array());
    out.write(ihdr.array());
    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    crc.update(ihdr.array());
    out.write(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    return out.toByteArray();
  }

  @Test
  void refusesDecompressionBomb() throws Exception {
    byte[] bomb = pngBomb(20000, 20000);
    assertThat(JpegResizer.dimensions(bomb)).containsExactly(20000, 20000);
    objects.put("review-photos/CU-1/RPH-1", bomb);
    assertThat(variants.variant(row("image/png"), 240)).isNull();
    assertThat(variants.prepare(row("image/png"))).isZero();
    assertThat(new JpegResizer().shrink(bomb, 240)).isNull();
  }
}
