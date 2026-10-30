package com.pmart.product.content.infrastructure;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.product.content.domain.ContentInput;
import com.pmart.product.content.domain.ContentRules;
import com.pmart.product.content.domain.ImageRole;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 수집 저장소(code-and-pick/product-data) 의 seed-v1 묶음(zip) 읽기. 이 서비스는 {@code products/{seedProductId}/}
 * 아래의 product.json {@code content} 블록과 이미지만 읽는다(product·skus 는 product-service 몫).
 *
 * <p>이미지 role 은 {@code content.images[]{role, file, sortOrder}} 를 따르고, 그것이 없으면 파일 이름(main=MAIN,
 * description_NN=DESCRIPTION(NN-1), info=DETAIL)으로 정한다. 묶음은 제3자 화면 캡처이므로 로그에 내용을 남기지 않는다.
 */
@Component
public class ContentBundleReader {

  public static final String FORMAT = "seed-v1";
  public static final String DEFAULT_SOURCE_SYSTEM = "bmart-capture";
  static final int MAX_PRODUCTS = 2_000;
  static final long MAX_JSON_BYTES = 2L * 1024 * 1024;
  private static final Pattern PRODUCT_FILE = Pattern.compile("^(?:.*/)?products/([^/]+)/(.+)$");
  private static final Pattern DESCRIPTION_FILE =
      Pattern.compile("^images/description_(\\d{1,4})\\.(?:jpe?g|png|webp)$");
  private static final Pattern MAIN_FILE = Pattern.compile("^images/main\\.(?:jpe?g|png|webp)$");
  private static final Pattern INFO_FILE = Pattern.compile("^images/info\\.(?:jpe?g|png|webp)$");

  public record SeedImage(
      ImageRole role,
      int sortOrder,
      String file,
      byte[] bytes,
      @Nullable Integer width,
      @Nullable Integer height) {}

  public record SeedContent(String seedProductId, ContentInput input, List<SeedImage> images) {}

  private final ObjectMapper mapper;

  public ContentBundleReader(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public List<SeedContent> read(InputStream zip) {
    Map<String, JsonNode> jsons = new LinkedHashMap<>();
    Map<String, Map<String, byte[]>> files = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(zip)) {
      ZipEntry e;
      while ((e = in.getNextEntry()) != null) {
        String name = e.getName().replace('\\', '/');
        if (e.isDirectory() || name.contains("..")) {
          continue;
        }
        Matcher m = PRODUCT_FILE.matcher(name);
        if (!m.matches()) {
          continue;
        }
        String dir = m.group(1);
        String rel = m.group(2);
        if ("product.json".equals(rel)) {
          if (jsons.size() >= MAX_PRODUCTS) {
            throw invalid("상품이 너무 많습니다(최대 " + MAX_PRODUCTS + "개).");
          }
          byte[] body = in.readNBytes((int) MAX_JSON_BYTES + 1);
          if (body.length > MAX_JSON_BYTES) {
            throw invalid("product.json 이 너무 큽니다: " + name);
          }
          JsonNode json = mapper.readTree(body);
          if (!FORMAT.equals(json.path("format").asString(FORMAT))) {
            throw invalid("형식이 seed-v1 이 아닙니다: " + name);
          }
          jsons.put(dir, json);
        } else if (rel.startsWith("images/")) {
          byte[] body = in.readNBytes(ContentRules.MAX_IMAGE_BYTES + 1);
          if (body.length > ContentRules.MAX_IMAGE_BYTES) {
            throw invalid("이미지가 너무 큽니다: " + name);
          }
          files.computeIfAbsent(dir, k -> new LinkedHashMap<>()).put(rel, body);
        }
      }
    } catch (IOException ex) {
      throw invalid("zip 을 읽을 수 없습니다: " + ex.getMessage());
    } catch (tools.jackson.core.JacksonException ex) {
      throw invalid("product.json 을 해석할 수 없습니다: " + ex.getOriginalMessage());
    }
    if (jsons.isEmpty()) {
      throw invalid("묶음에 products/*/product.json 이 없습니다.");
    }
    List<SeedContent> out = new ArrayList<>();
    for (var entry : jsons.entrySet()) {
      out.add(
          content(entry.getKey(), entry.getValue(), files.getOrDefault(entry.getKey(), Map.of())));
    }
    return out;
  }

  private SeedContent content(String dir, JsonNode root, Map<String, byte[]> files) {
    String id = root.path("product").path("seedProductId").asString(dir);
    JsonNode c = root.path("content");
    List<String> notices = new ArrayList<>();
    c.path("notices").forEach(n -> notices.add(n.isString() ? n.asString() : n.toString()));
    List<ContentInput.Option> options = new ArrayList<>();
    for (JsonNode o : c.path("options")) {
      String label = text(o, "label");
      if (label != null) {
        options.add(new ContentInput.Option(label, text(o, "capacityLabel")));
      }
    }
    ContentInput input =
        new ContentInput(
            root.path("source").path("system").asString(DEFAULT_SOURCE_SYSTEM),
            id,
            null,
            text(c, "name"),
            text(c, "description"),
            text(c, "storageGuide"),
            text(c, "returnGuide"),
            notices,
            options);
    return new SeedContent(id, input, images(id, c.path("images"), files));
  }

  private List<SeedImage> images(String id, JsonNode declared, Map<String, byte[]> files) {
    List<SeedImage> out = new ArrayList<>();
    if (declared.isArray() && !declared.isEmpty()) {
      for (JsonNode i : declared) {
        String file = text(i, "file");
        String role = text(i, "role");
        if (file == null || role == null) {
          throw invalid(id + ": content.images 에 role·file 이 필요합니다.");
        }
        byte[] bytes = files.get(file.startsWith("./") ? file.substring(2) : file);
        if (bytes == null) {
          throw invalid(id + ": content.images 가 가리키는 파일이 없습니다: " + file);
        }
        out.add(
            new SeedImage(
                role(id, role),
                i.path("sortOrder").asInt(0),
                file,
                bytes,
                i.hasNonNull("width") ? i.get("width").asInt() : null,
                i.hasNonNull("height") ? i.get("height").asInt() : null));
      }
      return out;
    }
    for (var f : files.entrySet()) {
      String rel = f.getKey().toLowerCase(Locale.ROOT);
      Matcher d = DESCRIPTION_FILE.matcher(rel);
      if (MAIN_FILE.matcher(rel).matches()) {
        out.add(new SeedImage(ImageRole.MAIN, 0, f.getKey(), f.getValue(), null, null));
      } else if (INFO_FILE.matcher(rel).matches()) {
        out.add(new SeedImage(ImageRole.DETAIL, 0, f.getKey(), f.getValue(), null, null));
      } else if (d.matches()) {
        int n = Integer.parseInt(d.group(1));
        out.add(
            new SeedImage(
                ImageRole.DESCRIPTION, Math.max(0, n - 1), f.getKey(), f.getValue(), null, null));
      }
    }
    return out;
  }

  private static ImageRole role(String id, String role) {
    try {
      return ImageRole.valueOf(role.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw invalid(id + ": 알 수 없는 이미지 role: " + role);
    }
  }

  private static @Nullable String text(JsonNode n, String field) {
    return n.hasNonNull(field) && !n.get(field).asString().isBlank()
        ? n.get(field).asString()
        : null;
  }

  static PmartException invalid(String message) {
    return new PmartException(
        "BUNDLE_INVALID", ErrorCode.DOMAIN_REJECTED.status(), message, false, List.of());
  }
}
