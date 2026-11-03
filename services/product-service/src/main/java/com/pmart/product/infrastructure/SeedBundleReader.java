package com.pmart.product.infrastructure;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 수집 저장소(code-and-pick/product-data) 의 seed-v1 묶음(zip) 읽기. 이 서비스는 {@code
 * products/{seedProductId}/product.json} 만 읽고 이미지는 건너뛴다(이미지·content 는 product-content-service 가 같은
 * 묶음을 따로 받는다).
 */
@Component
public class SeedBundleReader {

  public static final String FORMAT = "seed-v1";
  public static final String DEFAULT_SOURCE_SYSTEM = "bmart-capture";
  static final int MAX_PRODUCTS = 2_000;
  static final long MAX_JSON_BYTES = 2L * 1024 * 1024;
  private static final Pattern PRODUCT_JSON =
      Pattern.compile("^(?:.*/)?products/([^/]+)/product\\.json$");

  public record SeedProduct(String seedProductId, JsonNode json) {}

  private final ObjectMapper mapper;

  public SeedBundleReader(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public List<SeedProduct> read(InputStream zip) {
    List<SeedProduct> out = new ArrayList<>();
    try (ZipInputStream in = new ZipInputStream(zip)) {
      ZipEntry e;
      while ((e = in.getNextEntry()) != null) {
        if (e.isDirectory() || e.getName().contains("..")) {
          continue;
        }
        Matcher m = PRODUCT_JSON.matcher(e.getName().replace('\\', '/'));
        if (!m.matches()) {
          continue;
        }
        if (out.size() >= MAX_PRODUCTS) {
          throw invalid("상품이 너무 많습니다(최대 " + MAX_PRODUCTS + "개).");
        }
        byte[] body = in.readNBytes((int) MAX_JSON_BYTES + 1);
        if (body.length > MAX_JSON_BYTES) {
          throw invalid("product.json 이 너무 큽니다: " + e.getName());
        }
        JsonNode json = mapper.readTree(body);
        if (!FORMAT.equals(json.path("format").asString(FORMAT))) {
          throw invalid("형식이 seed-v1 이 아닙니다: " + e.getName());
        }
        String id = json.path("product").path("seedProductId").asString(m.group(1));
        out.add(new SeedProduct(id, json));
      }
    } catch (IOException ex) {
      throw invalid("zip 을 읽을 수 없습니다: " + ex.getMessage());
    } catch (tools.jackson.core.JacksonException ex) {
      throw invalid("product.json 을 해석할 수 없습니다: " + ex.getOriginalMessage());
    }
    if (out.isEmpty()) {
      throw invalid("묶음에 products/*/product.json 이 없습니다.");
    }
    return out;
  }

  private static final Pattern CATEGORIES_JSON = Pattern.compile("^(?:[^/]+/)?categories\\.json$");

  /**
   * 묶음의 categories.json(최상위 또는 한 단계 아래 폴더, 예 {@code seed-v1/categories.json}) — 카테고리 추가 업로드용. 다른
   * 파일은 읽지 않는다.
   */
  public JsonNode readCategories(InputStream zip) {
    try (ZipInputStream in = new ZipInputStream(zip)) {
      ZipEntry e;
      while ((e = in.getNextEntry()) != null) {
        String name = e.getName().replace('\\', '/');
        if (e.isDirectory() || name.contains("..") || !CATEGORIES_JSON.matcher(name).matches()) {
          continue;
        }
        byte[] body = in.readNBytes((int) MAX_JSON_BYTES + 1);
        if (body.length > MAX_JSON_BYTES) {
          throw invalid("categories.json 이 너무 큽니다.");
        }
        JsonNode json = mapper.readTree(body);
        if (!json.path("categories").isArray() || json.path("categories").isEmpty()) {
          throw invalid("categories.json 에 categories 배열이 없습니다.");
        }
        return json;
      }
    } catch (IOException ex) {
      throw invalid("zip 을 읽을 수 없습니다: " + ex.getMessage());
    } catch (tools.jackson.core.JacksonException ex) {
      throw invalid("categories.json 을 해석할 수 없습니다: " + ex.getOriginalMessage());
    }
    throw invalid("묶음에 categories.json 이 없습니다.");
  }

  private static PmartException invalid(String message) {
    return new PmartException(
        "BUNDLE_INVALID", ErrorCode.DOMAIN_REJECTED.status(), message, false, List.of());
  }
}
