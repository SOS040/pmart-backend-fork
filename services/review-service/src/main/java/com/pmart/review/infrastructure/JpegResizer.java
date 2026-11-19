package com.pmart.review.infrastructure;

import com.pmart.review.application.ReviewPhotoVariants;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * JDK ImageIO·Java2D 로 줄인다(새 의존성 없음 — product-content-service JpegResizer 와 같은 방식, 2026-11-20). 반씩
 * 줄이다가 마지막은 bicubic, 흰 바탕 RGB 로 그려 JPEG 품질 0.82. 원본 폭이 목표 이하면 원본 크기 그대로 JPEG 로(축소본이 늘 있게 — 다음 요청이
 * 원본을 다시 풀지 않는다). 풀 수 없는 본문이면 null.
 */
@Component
public class JpegResizer implements ReviewPhotoVariants.Resizer {

  static final float QUALITY = 0.82f;

  @Override
  public byte @Nullable [] shrink(byte[] original, int width) {
    BufferedImage src;
    try {
      src = ImageIO.read(new ByteArrayInputStream(original));
    } catch (IOException | RuntimeException e) {
      return null;
    }
    if (src == null) {
      return null;
    }
    width = Math.min(width, src.getWidth());
    int height = Math.max(1, Math.round((float) src.getHeight() * width / src.getWidth()));
    BufferedImage cur = src;
    while (cur.getWidth() / 2 >= width) {
      cur =
          draw(
              cur,
              cur.getWidth() / 2,
              Math.max(1, cur.getHeight() / 2),
              RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }
    BufferedImage out = draw(cur, width, height, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
      ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
      try {
        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(QUALITY);
        writer.setOutput(ios);
        writer.write(null, new IIOImage(out, null, null), p);
      } finally {
        writer.dispose();
      }
      ios.flush();
      return bytes.toByteArray();
    } catch (IOException e) {
      return null;
    }
  }

  private static BufferedImage draw(BufferedImage src, int w, int h, Object interpolation) {
    BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = dst.createGraphics();
    try {
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, w, h);
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.drawImage(src, 0, 0, w, h, null);
    } finally {
      g.dispose();
    }
    return dst;
  }
}
