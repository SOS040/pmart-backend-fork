package com.pmart.product.content.application;

import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.PmartException;
import com.pmart.product.content.domain.ContentRules;
import com.pmart.product.content.infrastructure.CatalogStore;
import com.pmart.product.content.infrastructure.CatalogStore.ContentRow;
import com.pmart.product.content.infrastructure.CatalogStore.ProjectionRow;
import com.pmart.product.content.infrastructure.DraftStore;
import com.pmart.product.content.infrastructure.DraftStore.ImageRow;
import com.pmart.product.content.infrastructure.InventoryClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 고객용 조회: 최신 게시본(product_content) + product 사본(판매 상태·대표 카테고리·SKU) + mfcId 를 주면 mfc-inventory 가용 사본.
 * 가격·평점은 아직 받지 않으므로 응답에 넣지 않는다(지어내지 않는다). 결과는 계약 필드 이름의 Map 이고 api 가 DTO 로 바꾼다.
 */
@Service
public class ContentQueries {

  /** 이미지 본문 경로(게이트웨이 기준 상대 경로). */
  public static final String IMAGE_PATH = "/api/v1/content-images/";

  public static final String SYSTEM = "product-content-service";

  public static final String INVENTORY = "mfc-inventory-service";

  private static final Logger log = LoggerFactory.getLogger(ContentQueries.class);

  private final CatalogStore catalog;
  private final DraftStore drafts;
  private final ImageStorage storage;
  private final InventoryClient inventory;
  private final ObjectMapper mapper;
  private final Clock clock;

  public ContentQueries(
      CatalogStore catalog,
      DraftStore drafts,
      ImageStorage storage,
      InventoryClient inventory,
      ObjectMapper mapper,
      Clock clock) {
    this.catalog = catalog;
    this.drafts = drafts;
    this.storage = storage;
    this.inventory = inventory;
    this.mapper = mapper;
    this.clock = clock;
  }

  public Map<String, Object> content(String productId) {
    return content(productId, null);
  }

  /** 상품 상세. mfcId 를 주면 옵션별 취급·가용 사본을 붙인다(재고 조회 실패는 UNKNOWN + sources UNAVAILABLE). */
  public Map<String, Object> content(String productId, @Nullable String mfcId) {
    ContentRow c = latest(productId);
    Optional<ProjectionRow> p = catalog.projection(productId);
    Map<String, Object> m = card(c, p.orElse(null));
    Boolean stockOk = withAvailability(List.of(m), mfcId);
    m.put("productVersion", c.productVersion());
    m.put("description", c.description());
    m.put("images", images(c));
    m.put("storageGuide", c.storageGuide());
    m.put("returnGuide", c.returnGuide());
    m.put("notices", mapper.readTree(c.notices()));
    p.flatMap(this::categoryPath).ifPresent(path -> m.put("categoryPath", path));
    List<Map<String, Object>> sources = new ArrayList<>();
    sources.add(source(SYSTEM, c.publishedAt().toString(), c.contentVersion(), "CURRENT"));
    p.ifPresent(
        x ->
            sources.add(
                source(
                    "product-service", x.receivedAt().toString(), x.latestProductVersion(), null)));
    if (stockOk != null) {
      sources.add(
          Map.of(
              "system",
              INVENTORY,
              "asOf",
              Map.of(
                  "asOf",
                  clock.instant().toString(),
                  "freshness",
                  stockOk ? "CURRENT" : "UNAVAILABLE")));
    }
    m.put("sources", sources);
    return m;
  }

  public Map<String, Object> cards(List<String> productIds) {
    return cards(productIds, null);
  }

  /** 상품 카드 일괄. 미게시 상품은 missing. mfcId 를 주면 옵션별 취급·가용 사본을 붙인다. */
  public Map<String, Object> cards(List<String> productIds, @Nullable String mfcId) {
    List<String> ids = productIds.stream().distinct().toList();
    Map<String, ContentRow> contents =
        catalog.latestContents(ids).stream()
            .collect(Collectors.toMap(ContentRow::productId, Function.identity()));
    Map<String, ProjectionRow> projections =
        catalog.projections(ids).stream()
            .collect(Collectors.toMap(ProjectionRow::productId, Function.identity()));
    List<Map<String, Object>> items = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (String id : ids) {
      ContentRow c = contents.get(id);
      if (c == null) {
        missing.add(id);
      } else {
        items.add(card(c, projections.get(id)));
      }
    }
    withAvailability(items, mfcId);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("items", items);
    out.put("missing", missing);
    out.put("asOf", Map.of("asOf", clock.instant().toString(), "freshness", "CURRENT"));
    return out;
  }

  /** 주문 스냅샷용 표시 정보. sku 가 주어지면 그 SKU 의 옵션 라벨. */
  public Map<String, Object> snapshot(String productId, @Nullable String sku) {
    ContentRow c = latest(productId);
    Map<String, Object> card = card(c, catalog.projection(productId).orElse(null));
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("productId", productId);
    m.put("sku", sku);
    m.put("name", c.name());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> options = (List<Map<String, Object>>) card.get("options");
    options.stream()
        .filter(o -> sku == null || sku.equals(o.get("sku")))
        .findFirst()
        .ifPresent(o -> m.put("optionLabel", o.get("label")));
    @SuppressWarnings("unchecked")
    Map<String, Object> main = (Map<String, Object>) card.get("mainImage");
    if (main != null) {
      m.put("mainImageUrl", main.get("url"));
    }
    m.put("contentVersion", c.contentVersion());
    return m;
  }

  public Map<String, Object> projectionStatus(String productId) {
    Optional<ContentRow> c = catalog.latestContent(productId);
    Optional<ProjectionRow> p = catalog.projection(productId);
    if (c.isEmpty() && p.isEmpty()) {
      throw PmartException.notFound("NOT_FOUND", "상품을 모릅니다: " + productId);
    }
    Integer exposed = c.map(ContentRow::productVersion).orElse(null);
    Integer latest = p.map(ProjectionRow::latestProductVersion).orElse(null);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("productId", productId);
    m.put("exposedProductVersion", exposed);
    m.put("latestProductVersion", latest);
    m.put("contentVersion", c.map(ContentRow::contentVersion).orElse(null));
    m.put("lag", exposed != null && exposed.equals(latest) ? "IN_SYNC" : "PENDING");
    m.put("asOf", Map.of("asOf", clock.instant().toString(), "freshness", "CURRENT"));
    return m;
  }

  /** 이미지 본문. 고객은 게시된 이미지만, 운영자·서비스는 초안 이미지도 읽는다. */
  public Image image(String imageId) {
    ImageRow row =
        drafts
            .image(imageId)
            .orElseThrow(() -> PmartException.notFound("IMAGE_NOT_FOUND", "이미지가 없습니다."));
    boolean staff =
        PmartPrincipal.current()
            .map(
                x ->
                    x.type() == PmartPrincipal.PrincipalType.OPERATOR
                        || x.type() == PmartPrincipal.PrincipalType.SERVICE)
            .orElse(false);
    if (!staff
        && !drafts.find(row.draftId()).map(d -> "PUBLISHED".equals(d.state())).orElse(false)) {
      throw PmartException.notFound("IMAGE_NOT_FOUND", "이미지가 없습니다.");
    }
    try {
      return new Image(row, storage.open(row.bucket(), row.objectKey()));
    } catch (NoSuchElementException e) {
      throw PmartException.notFound("IMAGE_NOT_FOUND", "이미지 본문이 저장소에 없습니다.");
    }
  }

  public record Image(ImageRow meta, ImageStorage.Stored body) {}

  // ---------- 내부 ----------

  /**
   * 카드들의 SKU 옵션에 담당 센터 가용 사본(availability)을 붙인다. mfcId 가 없거나 SKU 옵션이 없으면 아무것도 안 하고 null. 재고 조회가
   * 실패하면 화면을 실패시키지 않고 UNKNOWN 을 붙이고 false(0·품절로 바꾸지 않는다).
   */
  @SuppressWarnings("unchecked")
  private @Nullable Boolean withAvailability(
      List<Map<String, Object>> cards, @Nullable String mfcId) {
    if (mfcId == null || mfcId.isBlank()) {
      return null;
    }
    List<Map<String, Object>> options = new ArrayList<>();
    cards.forEach(c -> options.addAll((List<Map<String, Object>>) c.get("options")));
    List<String> skus =
        options.stream().map(o -> (String) o.get("sku")).filter(s -> s != null).toList();
    if (skus.isEmpty()) {
      return null;
    }
    Map<String, InventoryClient.Stock> stock;
    boolean ok;
    try {
      stock = inventory.stock(mfcId, skus);
      ok = true;
    } catch (PmartException | org.springframework.web.client.RestClientException e) {
      log.warn("상품 화면 가용 조회 실패 — 불명으로 표시");
      stock = Map.of();
      ok = false;
    }
    for (Map<String, Object> o : options) {
      String sku = (String) o.get("sku");
      if (sku == null) {
        continue;
      }
      InventoryClient.Stock s = stock.get(sku);
      ContentRules.Availability a =
          s == null
              ? ContentRules.Availability.UNKNOWN
              : ContentRules.availability(s.available(), s.handlingEnabled());
      Map<String, Object> view = new LinkedHashMap<>();
      if (a.carried() != null) {
        view.put("carried", a.carried());
      }
      view.put("state", a.state());
      o.put("availability", view);
    }
    return ok;
  }

  private ContentRow latest(String productId) {
    return catalog
        .latestContent(productId)
        .orElseThrow(
            () -> PmartException.notFound("CONTENT_NOT_PUBLISHED", "게시된 콘텐츠가 없습니다: " + productId));
  }

  private Map<String, Object> card(ContentRow c, @Nullable ProjectionRow p) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("productId", c.productId());
    m.put("name", c.name());
    images(c).stream()
        .filter(i -> "MAIN".equals(i.get("role")))
        .findFirst()
        .ifPresent(i -> m.put("mainImage", i));
    m.put("options", options(c, p));
    m.put("contentVersion", c.contentVersion());
    return m;
  }

  private List<Map<String, Object>> options(ContentRow c, @Nullable ProjectionRow p) {
    return options(
        mapper.readTree(c.options()),
        p == null ? mapper.createArrayNode() : mapper.readTree(p.skus()),
        p == null ? null : p.saleState());
  }

  /**
   * 옵션 라벨 + product 사본. 가격은 pricing 의 것이라 넣지 않는다. 가용은 withAvailability 가 붙인다.
   *
   * <ul>
   *   <li>옵션 1개·SKU 1개면 그 SKU 를 붙인다. 여러 개면 대응 규칙이 없어 붙이지 않는다.
   *   <li>콘텐츠에 옵션이 없고 SKU 가 1개면 그 SKU 하나를 옵션으로 낸다(판매 단위는 product 정본). 라벨은 콘텐츠에 없으니 비운다(지어내지 않는다).
   * </ul>
   */
  static List<Map<String, Object>> options(
      JsonNode opts, JsonNode skus, @Nullable String saleState) {
    List<Map<String, Object>> out = new ArrayList<>();
    boolean oneToOne = skus.size() == 1 && opts.size() <= 1;
    List<JsonNode> labels = new ArrayList<>();
    opts.forEach(labels::add);
    if (labels.isEmpty() && oneToOne) {
      labels.add(null);
    }
    for (JsonNode o : labels) {
      Map<String, Object> m = new LinkedHashMap<>();
      if (o != null) {
        m.put("label", o.path("label").asString(null));
        if (o.hasNonNull("capacityLabel")) {
          m.put("capacityLabel", o.get("capacityLabel").asString());
        }
      }
      if (oneToOne) {
        JsonNode s = skus.get(0);
        if (s.hasNonNull("sku")) {
          m.put("sku", s.get("sku").asString());
        }
        if (s.hasNonNull("unitType")) {
          m.put("unitType", s.get("unitType").asString());
        }
        if (s.hasNonNull("unitsPerSku")) {
          m.put("unitsPerSku", s.get("unitsPerSku").asInt());
        }
      }
      if (saleState != null) {
        m.put("saleState", saleState);
      }
      out.add(m);
    }
    return out;
  }

  private List<Map<String, Object>> images(ContentRow c) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (JsonNode i : mapper.readTree(c.images())) {
      Map<String, Object> m = new LinkedHashMap<>();
      String id = i.path("imageId").asString();
      m.put("imageId", id);
      m.put("url", IMAGE_PATH + id);
      m.put("role", i.path("role").asString());
      m.put("sortOrder", i.path("sortOrder").asInt());
      out.add(m);
    }
    return out;
  }

  /** 대표 카테고리로 끝나는 id 경로(루트→리프). 없으면 대표 노드 하나. */
  private Optional<List<String>> categoryPath(ProjectionRow p) {
    if (p.primaryCategoryId() == null) {
      return Optional.empty();
    }
    for (JsonNode path : mapper.readTree(p.categoryPaths())) {
      List<String> parts = List.of(path.asString().split("/"));
      if (!parts.isEmpty() && p.primaryCategoryId().equals(parts.get(parts.size() - 1))) {
        return Optional.of(parts.stream().filter(s -> !s.isBlank()).toList());
      }
    }
    return Optional.of(List.of(p.primaryCategoryId()));
  }

  /** freshness 는 자기 정본만 CURRENT. 사본은 수신 시각만 알 뿐 최신인지 모르므로 비워 둔다. */
  private static Map<String, Object> source(
      String system, String asOf, int version, @Nullable String freshness) {
    Map<String, Object> a = new LinkedHashMap<>();
    a.put("asOf", asOf);
    a.put("sourceVersion", version);
    if (freshness != null) {
      a.put("freshness", freshness);
    }
    return Map.of("system", system, "asOf", a);
  }
}
