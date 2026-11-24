package com.pmart.storefront.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.common.web.PmartException;
import com.pmart.storefront.infrastructure.JpegResizer;
import com.pmart.storefront.infrastructure.LayoutStore.ImageRow;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class BannerImageVariantsTest {

  /** 메모리 저장소 — 없으면 IMAGE_NOT_FOUND(S3 저장소와 같음). */
  static class MemStorage implements BannerImageStorage {
    final Map<String, byte[]> objects = new HashMap<>();

    @Override
    public void put(String key, byte[] bytes, String contentType) {
      objects.put(key, bytes);
    }

    @Override
    public InputStream get(String key) {
      byte[] b = objects.get(key);
      if (b == null) {
        throw PmartException.notFound("IMAGE_NOT_FOUND", "이미지가 없습니다.");
      }
      return new ByteArrayInputStream(b);
    }

    @Override
    public boolean delete(String key) {
      return objects.remove(key) != null;
    }
  }

  @Test
  void stepRoundsUpToNextSizeAndCapsAtLargest() {
    assertThat(BannerImageVariants.step(1)).isEqualTo(360);
    assertThat(BannerImageVariants.step(360)).isEqualTo(360);
    assertThat(BannerImageVariants.step(361)).isEqualTo(720);
    assertThat(BannerImageVariants.step(1069)).isEqualTo(1080);
    assertThat(BannerImageVariants.step(1300)).isEqualTo(1440);
    assertThat(BannerImageVariants.step(4096)).isEqualTo(1440);
  }

  @Test
  void pngBannerBecomesStoredJpegAtStepWidth() throws Exception {
    byte[] png = png(1960, 700);
    MemStorage storage = new MemStorage();
    BannerImageVariants variants = new BannerImageVariants(storage, new JpegResizer());
    ImageRow row = row("image/png", png.length);

    BannerImageVariants.Variant v = variants.variant(row, new ByteArrayInputStream(png), 1069);

    assertThat(v).isNotNull();
    assertThat(v.etag()).isEqualTo("abc-w1080");
    BufferedImage out = ImageIO.read(new ByteArrayInputStream(v.body()));
    assertThat(out.getWidth()).isEqualTo(1080);
    assertThat(out.getHeight()).isEqualTo(386);
    assertThat(storage.objects).containsKey("banner-variants/w1080/abc.jpg");

    // 두 번째는 저장된 축소본(원본을 다시 풀지 않는다 — 다른 본문을 줘도 같은 결과)
    BannerImageVariants.Variant again =
        variants.variant(row, new ByteArrayInputStream(new byte[] {1, 2, 3}), 1080);
    assertThat(again).isNotNull();
    assertThat(again.body()).isEqualTo(v.body());
  }

  @Test
  void smallOriginalKeepsItsSizeAndWebpGetsOriginal() throws Exception {
    byte[] png = png(600, 200);
    MemStorage storage = new MemStorage();
    BannerImageVariants variants = new BannerImageVariants(storage, new JpegResizer());

    BannerImageVariants.Variant v =
        variants.variant(row("image/png", png.length), new ByteArrayInputStream(png), 1440);
    assertThat(v).isNotNull();
    assertThat(ImageIO.read(new ByteArrayInputStream(v.body())).getWidth()).isEqualTo(600);

    assertThat(variants.variant(row("image/webp", 10), new ByteArrayInputStream(new byte[10]), 720))
        .isNull();
  }

  private static ImageRow row(String contentType, long size) {
    return new ImageRow(
        "BIMG-1",
        "banner-images/BIMG-1",
        contentType,
        size,
        "abc",
        null,
        null,
        "md",
        Instant.EPOCH);
  }

  private static byte[] png(int w, int h) throws Exception {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    var g = img.createGraphics();
    g.setColor(Color.ORANGE);
    g.fillRect(0, 0, w, h / 2);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }
}
