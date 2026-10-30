package com.pmart.product.content.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** 이미지 크기만 읽는다(디코딩하지 않음). 읽을 수 없으면 null — 크기는 선택 메타다. */
@Component
public class ImageProbe {

  public record Size(int width, int height) {}

  public @Nullable Size size(byte[] bytes) {
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      if (in == null) {
        return null;
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext()) {
        return null;
      }
      ImageReader r = readers.next();
      try {
        r.setInput(in, true, true);
        return new Size(r.getWidth(0), r.getHeight(0));
      } finally {
        r.dispose();
      }
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }
}
