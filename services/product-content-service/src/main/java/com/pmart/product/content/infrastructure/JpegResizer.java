package com.pmart.product.content.infrastructure;

import com.pmart.product.content.application.ImageVariants;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * JDK ImageIO·Java2D 로 줄인다(새 의존성 없음, 2026-11-19). 반씩 줄이다가 마지막은 bicubic(한 번에 크게 줄일 때 계단 현상 방지), 흰 바탕
 * RGB 로 그려 JPEG 품질 0.82. 풀 수 없는 본문·원본 폭이 목표 이하면 null.
 */
@Component
public class JpegResizer implements ImageVariants.Resizer {

  static final float QUALITY = 0.82f;

  @Override
  public byte @Nullable [] shrink(byte[] original, int width) {
    BufferedImage src;
    try {
      src = decode(original, width);
    } catch (IOException | RuntimeException e) {
      return null;
    }
    if (src == null || src.getWidth() <= width) {
      return null;
    }
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

  /**
   * 화소 수 상한(2026-11-20 리뷰 지적 1 — 압축 폭탄): 단색 20000×20000 PNG 는 몇 KB 지만 다 풀면 1.6GB 라 서비스가 메모리 부족으로
   * 죽는다 (이미지 JVM 은 ExitOnOutOfMemoryError). 머리말에서 가로·세로만 읽어 이보다 크면 풀지 않는다(원본을 준다).
   */
  public static final long MAX_PIXELS = 40_000_000L;

  /** 머리말의 가로·세로(풀지 않음). 읽을 수 없는 형식이면 null. */
  public static int @Nullable [] dimensions(byte[] bytes) {
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      Iterator<ImageReader> it = ImageIO.getImageReaders(in);
      if (!it.hasNext()) {
        return null;
      }
      ImageReader r = it.next();
      try {
        r.setInput(in, true, true);
        return new int[] {r.getWidth(0), r.getHeight(0)};
      } finally {
        r.dispose();
      }
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /**
   * 화소 수 상한을 넘으면 null. 목표 폭의 네 배 이상이면 줄여 읽는다(subsampling — 2048px 사진을 240 으로 만들 때 메모리·시간이 준다, 목표의 두
   * 배 이상으로 남겨 마지막 bicubic 품질 유지).
   */
  private static @Nullable BufferedImage decode(byte[] original, int width) throws IOException {
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
      Iterator<ImageReader> it = ImageIO.getImageReaders(in);
      if (!it.hasNext()) {
        return null;
      }
      ImageReader r = it.next();
      try {
        r.setInput(in, true, true);
        int w = r.getWidth(0);
        int h = r.getHeight(0);
        if ((long) w * h > MAX_PIXELS) {
          return null;
        }
        ImageReadParam param = r.getDefaultReadParam();
        int step = Math.max(1, w / (width * 2));
        if (step > 1) {
          param.setSourceSubsampling(step, step, 0, 0);
        }
        return r.read(0, param);
      } finally {
        r.dispose();
      }
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
