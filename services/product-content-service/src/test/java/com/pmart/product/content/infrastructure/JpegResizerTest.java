package com.pmart.product.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 축소본(2026-11-19, 계약 0.3.0): 비율 유지·JPEG·투명 배경은 흰색, 원본 폭 이하·풀 수 없는 본문은 null. */
class JpegResizerTest {

  private final JpegResizer resizer = new JpegResizer();

  @Test
  void shrinksToWidthKeepingRatio() throws Exception {
    byte[] original = encode(photo(1080, 2000, BufferedImage.TYPE_INT_RGB), "jpeg");
    for (int w : new int[] {720, 480, 240}) {
      byte[] out = resizer.shrink(original, w);
      assertThat(out).isNotNull();
      BufferedImage img = ImageIO.read(new ByteArrayInputStream(out));
      assertThat(img.getWidth()).isEqualTo(w);
      assertThat(img.getHeight()).isEqualTo(Math.round(2000f * w / 1080));
      assertThat(out[0] & 0xFF).isEqualTo(0xFF); // JPEG
      assertThat(out[1] & 0xFF).isEqualTo(0xD8);
      assertThat(out.length).as("w=" + w).isLessThan(original.length);
    }
  }

  @Test
  void transparentPngBecomesWhiteJpeg() throws Exception {
    BufferedImage png = new BufferedImage(1000, 500, BufferedImage.TYPE_INT_ARGB); // 전부 투명
    byte[] out = resizer.shrink(encode(png, "png"), 480);
    BufferedImage img = ImageIO.read(new ByteArrayInputStream(out));
    assertThat(img.getWidth()).isEqualTo(480);
    assertThat(new Color(img.getRGB(10, 10)).getRed()).isGreaterThan(245);
  }

  @Test
  void noShrinkWhenNarrowOrUnreadable() throws Exception {
    assertThat(resizer.shrink(encode(photo(600, 800, BufferedImage.TYPE_INT_RGB), "jpeg"), 720))
        .isNull();
    assertThat(resizer.shrink(encode(photo(720, 800, BufferedImage.TYPE_INT_RGB), "jpeg"), 720))
        .isNull();
    assertThat(resizer.shrink(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2}, 720))
        .isNull();
  }

  static BufferedImage photo(int w, int h, int type) {
    BufferedImage img = new BufferedImage(w, h, type);
    Graphics2D g = img.createGraphics();
    Random r = new Random(7);
    for (int i = 0; i < 400; i++) {
      g.setColor(new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256)));
      g.fillOval(r.nextInt(w), r.nextInt(h), 20 + r.nextInt(200), 20 + r.nextInt(200));
    }
    g.dispose();
    return img;
  }

  static byte[] encode(BufferedImage img, String format) throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(img, format, out);
    return out.toByteArray();
  }
}
