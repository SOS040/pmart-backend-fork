package com.pmart.product.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pmart.common.web.PmartException;
import com.pmart.product.content.domain.ImageRole;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** seed-v1 묶음 읽기. 시험 묶음은 합성 바이트만 쓴다(실제 캡처 이미지를 저장소에 넣지 않는다). */
class ContentBundleReaderTest {

  final ContentBundleReader reader = new ContentBundleReader(JsonMapper.builder().build());

  static byte[] zip(Map<String, byte[]> entries) throws Exception {
    var out = new ByteArrayOutputStream();
    try (var z = new ZipOutputStream(out)) {
      for (var e : entries.entrySet()) {
        z.putNextEntry(new ZipEntry(e.getKey()));
        z.write(e.getValue());
      }
    }
    return out.toByteArray();
  }

  static byte[] jpeg(int seed) {
    return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, (byte) seed};
  }

  @Test
  void rolesFromFileNamesWhenContentImagesMissing() throws Exception {
    String json =
        """
        {"format":"seed-v1","product":{"seedProductId":"bm-1"},
         "content":{"name":"상품","notices":["원산지: 국내"],"options":[{"label":"1개","capacityLabel":"180g"}]},
         "source":{"system":"bmart-capture"}}
        """;
    var list =
        reader.read(
            new ByteArrayInputStream(
                zip(
                    Map.of(
                        "seed-v1/products/bm-1/product.json",
                        json.getBytes(StandardCharsets.UTF_8),
                        "seed-v1/products/bm-1/images/main.jpg",
                        jpeg(1),
                        "seed-v1/products/bm-1/images/description_01.jpg",
                        jpeg(2),
                        "seed-v1/products/bm-1/images/description_02.jpg",
                        jpeg(3),
                        "seed-v1/products/bm-1/images/info.jpg",
                        jpeg(4),
                        "seed-v1/manifest.json",
                        "{}".getBytes()))));
    assertThat(list).hasSize(1);
    var c = list.get(0);
    assertThat(c.input().sourceSystem()).isEqualTo("bmart-capture");
    assertThat(c.input().sourceKey()).isEqualTo("bm-1");
    assertThat(c.input().notices()).containsExactly("원산지: 국내");
    assertThat(c.input().options().get(0).capacityLabel()).isEqualTo("180g");
    assertThat(c.images())
        .extracting(i -> i.role() + ":" + i.sortOrder())
        .containsExactlyInAnyOrder("MAIN:0", "DESCRIPTION:0", "DESCRIPTION:1", "DETAIL:0");
  }

  @Test
  void declaredContentImagesWinAndMissingFileIsInvalid() throws Exception {
    String json =
        """
        {"format":"seed-v1","product":{"seedProductId":"bm-2"},
         "content":{"name":"상품","images":[{"role":"MAIN","file":"images/main.jpg","sortOrder":0,"width":10,"height":20}]}}
        """;
    var list =
        reader.read(
            new ByteArrayInputStream(
                zip(
                    Map.of(
                        "products/bm-2/product.json",
                        json.getBytes(StandardCharsets.UTF_8),
                        "products/bm-2/images/main.jpg",
                        jpeg(1),
                        "products/bm-2/images/info.jpg",
                        jpeg(2)))));
    assertThat(list.get(0).images()).hasSize(1);
    assertThat(list.get(0).images().get(0).role()).isEqualTo(ImageRole.MAIN);
    assertThat(list.get(0).images().get(0).width()).isEqualTo(10);

    String broken = json.replace("images/main.jpg", "images/nope.jpg");
    assertThatThrownBy(
            () ->
                reader.read(
                    new ByteArrayInputStream(
                        zip(
                            Map.of(
                                "products/bm-2/product.json",
                                broken.getBytes(StandardCharsets.UTF_8))))))
        .isInstanceOf(PmartException.class)
        .hasMessageContaining("nope.jpg");
  }

  @Test
  void emptyOrForeignBundleIsRejected() throws Exception {
    assertThatThrownBy(
            () -> reader.read(new ByteArrayInputStream(zip(Map.of("x.txt", new byte[] {1})))))
        .isInstanceOf(PmartException.class);
    assertThatThrownBy(
            () ->
                reader.read(
                    new ByteArrayInputStream(
                        zip(
                            Map.of(
                                "products/a/product.json",
                                "{\"format\":\"seed-v2\"}".getBytes())))))
        .isInstanceOf(PmartException.class)
        .hasMessageContaining("seed-v1");
  }
}
