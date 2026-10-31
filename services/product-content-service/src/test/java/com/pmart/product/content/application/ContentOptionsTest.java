package com.pmart.product.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ContentOptionsTest {

  static final ObjectMapper MAPPER = new ObjectMapper();

  static JsonNode json(String s) {
    return MAPPER.readTree(s);
  }

  static final JsonNode ONE_SKU =
      json("[{\"sku\":\"E-00000001\",\"unitType\":\"E\",\"unitsPerSku\":1}]");

  @Test
  void singleLabelledOptionGetsTheSku() {
    List<Map<String, Object>> o =
        ContentQueries.options(json("[{\"label\":\"1개\"}]"), ONE_SKU, "ON_SALE");
    assertThat(o).hasSize(1);
    assertThat(o.getFirst())
        .containsEntry("label", "1개")
        .containsEntry("sku", "E-00000001")
        .containsEntry("saleState", "ON_SALE");
  }

  @Test
  void noContentOptionsWithOneSkuBecomesOneUnlabelledOption() {
    List<Map<String, Object>> o = ContentQueries.options(json("[]"), ONE_SKU, "ON_SALE");
    assertThat(o).hasSize(1);
    assertThat(o.getFirst())
        .containsEntry("sku", "E-00000001")
        .containsEntry("unitType", "E")
        .doesNotContainKey("label");
  }

  @Test
  void noSkuOrManySkusAddNoGuessedOption() {
    assertThat(ContentQueries.options(json("[]"), json("[]"), null)).isEmpty();
    JsonNode two = json("[{\"sku\":\"E-1\"},{\"sku\":\"E-2\"}]");
    assertThat(ContentQueries.options(json("[]"), two, null)).isEmpty();
    List<Map<String, Object>> labelled =
        ContentQueries.options(json("[{\"label\":\"A\"},{\"label\":\"B\"}]"), two, null);
    assertThat(labelled).hasSize(2).allSatisfy(m -> assertThat(m).doesNotContainKey("sku"));
  }
}
