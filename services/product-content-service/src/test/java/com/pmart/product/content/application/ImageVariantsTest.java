package com.pmart.product.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.web.PmartException;
import com.pmart.product.content.infrastructure.DraftStore.ImageRow;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 축소본 저장·재사용(2026-11-19, 계약 0.3.0): 처음 한 번 만들어 저장, 다음은 저장본. 원본 폭 이하·WEBP·잘못된 w. */
class ImageVariantsTest {

  static final String SHA = "a".repeat(64);

  final Map<String, byte[]> objects = new HashMap<>();
  final AtomicInteger made = new AtomicInteger();

  final ImageStorage storage =
      new ImageStorage() {
        @Override
        public String bucket() {
          return "b";
        }

        @Override
        public boolean exists(String key) {
          return objects.containsKey(key);
        }

        @Override
        public void put(String key, byte[] bytes, String contentType) {
          objects.put(key, bytes);
        }

        @Override
        public Stored open(String bucket, String key) {
          byte[] b = objects.get(key);
          if (b == null) {
            throw new NoSuchElementException(key);
          }
          return new Stored(new ByteArrayInputStream(b), b.length);
        }
      };

  final ImageVariants variants =
      new ImageVariants(
          storage,
          (original, width) -> {
            made.incrementAndGet();
            return ("small-" + width).getBytes();
          });

  static ImageRow row(String contentType, Integer width) {
    return new ImageRow(
        "IMG-1",
        "DRF-1",
        "DESCRIPTION",
        1,
        "b",
        "products/x/" + SHA + ".jpg",
        contentType,
        width,
        2000,
        300_000,
        SHA,
        Instant.now());
  }

  @Test
  void makesOnceThenReusesStored() throws Exception {
    objects.put("products/x/" + SHA + ".jpg", new byte[] {1, 2, 3});
    var first = variants.variant(row("image/jpeg", 1080), 720);
    assertThat(first.body().body().readAllBytes()).isEqualTo("small-720".getBytes());
    assertThat(first.etag()).isEqualTo(SHA + "-w720");
    assertThat(objects).containsKey("variants/w720/" + SHA + ".jpg");
    var second = variants.variant(row("image/jpeg", 1080), 720);
    assertThat(second.body().body().readAllBytes()).isEqualTo("small-720".getBytes());
    assertThat(made).hasValue(1);
  }

  @Test
  void originalWhenNarrowOrWebp() {
    assertThat(variants.variant(row("image/jpeg", 720), 720)).isNull();
    assertThat(variants.variant(row("image/webp", 1080), 720)).isNull();
    assertThat(made).hasValue(0);
  }

  @Test
  void rejectsOtherWidths() {
    assertThatThrownBy(() -> ImageVariants.checkWidth(1000))
        .isInstanceOf(PmartException.class)
        .hasMessageContaining("240");
  }
}
