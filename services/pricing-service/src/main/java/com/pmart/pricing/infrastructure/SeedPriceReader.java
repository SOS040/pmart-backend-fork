package com.pmart.pricing.infrastructure;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * seed-v1 묶음(zip)에서 가격에 필요한 값만 읽는다: {@code products/{seedProductId}/product.json} 의
 * product.seedProductId·product.name·price.basePrice·source.system. 이미지는 건너뛴다(상품·콘텐츠 일괄 등록과 같은 파일).
 */
@Component
public class SeedPriceReader {

  public static final String FORMAT = "seed-v1";
  public static final String DEFAULT_SOURCE_SYSTEM = "bmart-capture";
  static final int MAX_PRODUCTS = 2_000;
  static final long MAX_JSON_BYTES = 2L * 1024 * 1024;
  private static final Pattern PRODUCT_JSON =
      Pattern.compile("^(?:.*/)?products/([^/]+)/product\\.json$");

  /** 묶음 상품 하나의 가격 정보. basePrice 가 없거나 숫자가 아니면 null. */
  public record SeedPrice(
      String seedProductId,
      String sourceSystem,
      @Nullable String productName,
      @Nullable Long basePrice) {}

  private final ObjectMapper mapper;

  public SeedPriceReader(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** 묶음 순서대로. 같은 seedProductId 가 다시 나오면 앞의 것만 쓴다. */
  public List<SeedPrice> read(InputStream zip) {
    List<SeedPrice> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
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
        if (!seen.add(id)) {
          continue;
        }
        JsonNode price = json.path("price").path("basePrice");
        JsonNode name = json.path("product").path("name");
        out.add(
            new SeedPrice(
                id,
                json.path("source").path("system").asString(DEFAULT_SOURCE_SYSTEM),
                name.isString() && !name.asString().isBlank() ? name.asString() : null,
                price.isIntegralNumber() ? price.asLong() : null));
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

  private static PmartException invalid(String message) {
    return new PmartException(
        "BUNDLE_INVALID", ErrorCode.DOMAIN_REJECTED.status(), message, false, List.of());
  }
}
