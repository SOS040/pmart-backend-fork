package com.pmart.search;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.MountableFile;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 상품·콘텐츠·판매 상태·가격·센터 취급 사건(Kafka) → <b>Logstash</b>(search-index.conf) → Elasticsearch(nori) → 검색
 * API (ADR-0031). 실제 Kafka·Elasticsearch·Logstash 를 한 네트워크에 띄우고, 공개 카드·카테고리 트리는 WireMock(호스트). 사건
 * 순서 뒤바뀜(가격이 상품보다 먼저)·재전달·옛 버전·한국어 형태소/부분 일치도 확인한다. Elasticsearch 는 시작 때 nori 를 설치한다(인터넷 필요).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks"
    })
@EnableWireMock(
    @ConfigureWireMock(
        name = "content",
        // 카드(Logstash 가 부름)·카테고리 트리(서비스가 부름)를 한 WireMock 이 맡는다(경로가 서로 다르다)
        baseUrlProperties = {"pmart.search.product-base-url"}))
@Testcontainers
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class SearchFlowIT {

  static final Network NET = Network.newNetwork();

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      com.pmart.common.test.KafkaTestContainer.create()
          .withNetwork(NET)
          .withListener("kafka:19092");

  /** deploy/lan 과 같은 이미지·nori 설치 방식(ADR-0031). */
  @Container
  static final GenericContainer<?> ES =
      new GenericContainer<>("docker.elastic.co/elasticsearch/elasticsearch:8.18.0")
          .withNetwork(NET)
          .withNetworkAliases("elasticsearch")
          .withEnv("discovery.type", "single-node")
          .withEnv("xpack.security.enabled", "false")
          .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
          .withCommand(
              "sh",
              "-c",
              "bin/elasticsearch-plugin list | grep -q analysis-nori"
                  + " || bin/elasticsearch-plugin install --batch analysis-nori;"
                  + " exec /usr/local/bin/docker-entrypoint.sh eswrapper")
          .withExposedPorts(9200)
          .waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(5)));

  /** WireMock(호스트) 포트를 안 뒤에 띄운다 — 카드 API 주소가 필요하다. */
  static GenericContainer<?> logstash;

  @DynamicPropertySource
  static void elasticsearch(DynamicPropertyRegistry r) {
    r.add(
        "pmart.search.elasticsearch-url",
        () -> "http://" + ES.getHost() + ":" + ES.getMappedPort(9200));
  }

  void startLogstash() throws Exception {
    if (logstash != null) {
      return;
    }
    // 구독 토픽을 Logstash 보다 먼저 만든다 — 없는 토픽은 소비자 메타데이터 갱신(기본 5분)까지 못 읽는다. LAN 은 topics-init 이 만든다
    try (var admin =
        org.apache.kafka.clients.admin.AdminClient.create(
            Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      var existing = admin.listTopics().names().get();
      var wanted =
          java.util.stream.Stream.of("commerce.product.v1", "commerce.pricing.v1", HANDLING_TOPIC)
              .filter(t -> !existing.contains(t))
              .map(t -> new org.apache.kafka.clients.admin.NewTopic(t, 1, (short) 1))
              .toList();
      if (!wanted.isEmpty()) {
        admin.createTopics(wanted).all().get();
      }
    }
    org.testcontainers.Testcontainers.exposeHostPorts(content.port());
    logstash =
        new GenericContainer<>("docker.elastic.co/logstash/logstash:8.18.0")
            .withNetwork(NET)
            .withEnv("PMART_KAFKA_BOOTSTRAP", "kafka:19092")
            .withEnv("PMART_ES_URL", "http://elasticsearch:9200")
            .withEnv(
                "PMART_CONTENT_BASE_URL", "http://host.testcontainers.internal:" + content.port())
            .withEnv("XPACK_MONITORING_ENABLED", "false")
            .withEnv("LS_JAVA_OPTS", "-Xms256m -Xmx256m")
            .withCopyFileToContainer(
                MountableFile.forHostPath(Path.of("src/main/logstash/search-index.conf")),
                "/usr/share/logstash/pipeline/logstash.conf")
            .withCommand(
                "logstash",
                "-f",
                "/usr/share/logstash/pipeline/logstash.conf",
                "--pipeline.workers",
                "1",
                "--pipeline.batch.size",
                "1")
            .waitingFor(
                Wait.forLogMessage(".*Pipelines running.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));
    logstash.start();
  }

  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired com.pmart.search.infrastructure.SearchIndex index;

  @InjectWireMock("content")
  WireMockServer content;

  RestClient http;

  @BeforeEach
  void setUp() throws Exception {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    content.resetAll();
    // 색인은 Logstash 가 쓰므로 먼저 만든다(LAN 은 es-init — 같은 정의 파일). 2026-11-19 부터 검색은 자기 색인만 확인하므로 전부를 직접
    index.ensureIndices();
    startLogstash();
    card("PRD-1", "[시험] 소고기 미역국 500g", 1);
    card("PRD-2", "[시험] 국산콩 두부 300g", 1);
    card("PRD-3", "[시험] 고기 왕만두 1kg", 1);
    content.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/categories/tree"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of(
                                "version",
                                1,
                                "nodes",
                                List.of(
                                    Map.of("categoryId", "t01", "path", "신선식품"),
                                    Map.of("categoryId", "t01-c02", "path", "신선식품 > 두부·계란"),
                                    Map.of("categoryId", "t02", "path", "간편식"),
                                    Map.of("categoryId", "t02-c01", "path", "간편식 > 국·탕·찌개"),
                                    Map.of("categoryId", "t02-c02", "path", "간편식 > 만두"),
                                    Map.of("categoryId", "t09", "path", "필터시험", "level", "TOP"),
                                    Map.of(
                                        "categoryId",
                                        "t09-c01",
                                        "parentId",
                                        "t09",
                                        "path",
                                        "필터시험 > 생수")))))));
    // 필터 패널은 노드에 직접 붙은 것만 내려온다(상속 없음) — t09 에만 붙이고, 하위 t09-c01·카테고리 없음에서도 이름이 나오는지 본다
    content.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/categories/t09/filters"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of(
                                "categoryId",
                                "t09",
                                "groups",
                                List.of(
                                    Map.of(
                                        "filterGroupId",
                                        "capacity",
                                        "name",
                                        "용량",
                                        "selectionMode",
                                        "MULTIPLE",
                                        "options",
                                        List.of("1L", "500ml"))))))));
  }

  void card(String productId, String name, int contentVersion) {
    content.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/product-contents"))
            .withQueryParam("productIds", equalTo(productId))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of(
                                "items",
                                List.of(
                                    Map.of(
                                        "productId", productId,
                                        "name", name,
                                        "contentVersion", contentVersion,
                                        "mainImage",
                                            Map.of(
                                                "url", "/api/v1/content-images/IMG-" + productId))),
                                "missing",
                                List.of())))));
  }

  void send(String type, String aggregate, String id, long version, Map<String, Object> payload) {
    String topic =
        switch (type) {
          case "PriceVersionEffective" -> "commerce.pricing.v1";
          case "SkuHandlingChanged" -> HANDLING_TOPIC;
          default -> "commerce.product.v1";
        };
    String json =
        EventEnvelopeFixtures.envelope(type)
            .from(
                switch (topic) {
                  case "commerce.pricing.v1" -> "pricing-service";
                  case HANDLING_TOPIC -> "mfc-inventory-service";
                  default -> "product-service";
                })
            .aggregate(aggregate, id, version)
            .payload(payload)
            .buildJson();
    kafka.send(topic, id, json).join();
  }

  void product(String productId, int version, String sku, String category, String at) {
    send(
        "ProductVersionPublished",
        "Product",
        productId,
        version,
        Map.of(
            "productId", productId,
            "version", version,
            "primaryCategoryId", category,
            "categoryIds", List.of(category),
            "saleState", "ON_SALE",
            "skus", List.of(Map.of("sku", sku, "unitType", "E", "unitsPerSku", 1)),
            "effectiveAt", at));
  }

  void contentPublished(String productId, int contentVersion, String at) {
    send(
        "ProductContentPublished",
        "ProductContent",
        productId,
        contentVersion,
        Map.of("productId", productId, "contentVersion", contentVersion, "publishedAt", at));
  }

  void price(String sku, long amount, int priceVersion) {
    send(
        "PriceVersionEffective",
        "PriceVersion",
        sku,
        priceVersion,
        Map.of(
            "sku", sku,
            "price", amount,
            "priceVersion", priceVersion,
            "effectiveAt", "2026-10-30T00:00:00Z"));
  }

  static final String HANDLING_TOPIC = "mfc.sku-handling.v1";

  /** mfc-inventory SkuHandlingChanged(센터·SKU 행 버전). */
  void handling(String mfcId, String sku, boolean enabled, int version, boolean republished) {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("mfcId", mfcId);
    payload.put("sku", sku);
    payload.put("handlingEnabled", enabled);
    payload.put("version", version);
    payload.put("changedAt", "2026-11-05T00:00:00Z");
    if (republished) {
      payload.put("republished", true);
    }
    send("SkuHandlingChanged", "SkuHandling", mfcId + ":" + sku, version, payload);
  }

  JsonNode search(String query) {
    String body = http.get().uri("/search/products" + query).retrieve().body(String.class);
    return mapper.readTree(body == null ? "{}" : body);
  }

  List<String> ids(JsonNode result) {
    List<String> ids = new ArrayList<>();
    result.path("items").forEach(i -> ids.add(i.path("productId").asString()));
    return ids;
  }

  JsonNode await(String query, Predicate<JsonNode> until) throws InterruptedException {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
    JsonNode last = null;
    while (Instant.now().isBefore(deadline)) {
      last = search(query);
      if (until.test(last)) {
        return last;
      }
      Thread.sleep(300);
    }
    throw new AssertionError("조건 미충족: " + query + " → " + last);
  }

  @Test
  @org.junit.jupiter.api.Order(1) // 색인이 비어 있다고 가정한다(건수 단정) — 다른 시험보다 먼저
  void eventsBecomeSearchableAndStayConsistent() throws Exception {
    // 가격이 상품보다 먼저 온 경우(E-2), 센터 취급이 상품보다 먼저 온 경우(MFC-1 의 E-1)
    price("E-2", 1900, 1);
    handling("MFC-1", "E-1", true, 1, false);
    product("PRD-1", 1, "E-1", "t02-c01", "2026-10-30T01:00:00Z");
    product("PRD-2", 1, "E-2", "t01-c02", "2026-10-30T01:00:00Z");
    product("PRD-3", 1, "E-3", "t02-c02", "2026-10-30T01:00:00Z");
    product("PRD-4", 1, "E-4", "t02-c02", "2026-10-30T01:00:00Z"); // 콘텐츠 없음 → 검색에 안 나온다
    contentPublished("PRD-1", 1, "2026-10-30T02:00:00Z");
    contentPublished("PRD-2", 1, "2026-10-30T02:01:00Z");
    contentPublished("PRD-3", 1, "2026-10-30T02:02:00Z");
    price("E-1", 4900, 1);
    price("E-3", 7900, 1);
    // 상품 뒤에 온 취급: E-3 켬(1) → 끔(2) → 늦게 온 옛 사건 켬(1, 무시) → 재발행 끔(2, 같은 버전 — 바뀌지 않음). MFC-2 는 E-1 만 켬
    handling("MFC-1", "E-3", true, 1, false);
    handling("MFC-1", "E-3", false, 2, false);
    handling("MFC-1", "E-3", true, 1, false);
    handling("MFC-1", "E-3", false, 2, true);
    handling("MFC-2", "E-1", true, 1, false);

    JsonNode all =
        await(
            "",
            r ->
                r.path("total").asInt() == 3
                    // null 도 값으로 세지 않도록 숫자만(가격 사건은 다른 토픽이라 늦을 수 있다)
                    && r.path("items").findValues("basePrice").stream()
                            .filter(JsonNode::isNumber)
                            .count()
                        == 3);
    assertThat(ids(all)).containsExactly("PRD-3", "PRD-2", "PRD-1"); // 검색어 없으면 최근 게시 순
    JsonNode tofu = all.path("items").path(1);
    assertThat(tofu.path("name").asString()).isEqualTo("[시험] 국산콩 두부 300g");
    assertThat(tofu.path("basePrice").asInt()).as("상품보다 먼저 온 가격").isEqualTo(1900);
    assertThat(tofu.path("imageUrl").asString()).isEqualTo("/api/v1/content-images/IMG-PRD-2");
    assertThat(tofu.path("saleState").asString()).isEqualTo("ON_SALE");
    assertThat(tofu.path("carried").isNull() || tofu.path("carried").isMissingNode()).isTrue();
    assertThat(tofu.path("categoryPath").toString()).isEqualTo("[\"신선식품\",\"두부·계란\"]");

    // 센터 취급 여부(색인 carriedMfcIds, 대표 SKU 기준 — 2026-11-05). 취급 레코드 없음(E-2)은 OFF, 끈 E-3 도 미취급.
    // 기본(includeNotCarried=false)은 색인에서 거르므로 total 이 정확하고 다음 커서가 없다
    JsonNode atMfc =
        await("?mfcId=MFC-1", r -> ids(r).equals(List.of("PRD-1")) && r.path("total").asInt() == 1);
    assertThat(atMfc.path("items").path(0).path("carried").asBoolean()).isTrue();
    assertThat(
            atMfc.path("page").path("nextCursor").isNull()
                || atMfc.path("page").path("nextCursor").isMissingNode())
        .isTrue();
    JsonNode withNotCarried = search("?mfcId=MFC-1&includeNotCarried=true");
    assertThat(ids(withNotCarried)).containsExactly("PRD-3", "PRD-2", "PRD-1");
    assertThat(withNotCarried.path("total").asInt()).isEqualTo(3);
    assertThat(withNotCarried.path("items").path(0).path("carried").asBoolean()).isFalse();
    assertThat(withNotCarried.path("items").path(1).path("carried").asBoolean()).isFalse();
    assertThat(withNotCarried.path("items").path(2).path("carried").asBoolean()).isTrue();
    assertThat(ids(await("?mfcId=MFC-2", r -> r.path("total").asInt() == 1)))
        .containsExactly("PRD-1");
    // 모르는 센터: 아무 상품도 취급하지 않음(빈 결과, total 0)
    JsonNode unknown = search("?mfcId=MFC-NONE");
    assertThat(ids(unknown)).isEmpty();
    assertThat(unknown.path("total").asInt()).isZero();

    // 한국어 부분 일치(n-gram) · 카테고리 조상 · 가격 정렬
    assertThat(ids(search("?q=미역"))).containsExactly("PRD-1");
    // 형태소(nori): 조사가 붙은 검색어 — 조각 일치로는 "를" 이 없어 못 찾는다
    assertThat(ids(search("?q=두부를"))).containsExactly("PRD-2");
    assertThat(ids(search("?q=국산콩으로"))).containsExactly("PRD-2");
    assertThat(ids(search("?q=두부 300g"))).containsExactly("PRD-2");
    assertThat(ids(search("?q=없는상품명"))).isEmpty();
    assertThat(ids(search("?categoryId=t02"))).containsExactlyInAnyOrder("PRD-1", "PRD-3");
    assertThat(ids(search("?categoryId=t02-c02"))).containsExactly("PRD-3");
    assertThat(ids(search("?sort=PRICE_ASC"))).containsExactly("PRD-2", "PRD-1", "PRD-3");
    // 가격 범위(기본가, 양 끝 포함) — PRD-2 1900 · PRD-1 4900 · PRD-3 7900
    assertThat(ids(search("?minPrice=2000"))).containsExactlyInAnyOrder("PRD-1", "PRD-3");
    assertThat(ids(search("?minPrice=2000&maxPrice=5000"))).containsExactly("PRD-1");
    assertThat(ids(search("?maxPrice=1900"))).containsExactly("PRD-2");
    assertThat(ids(search("?q=두부&minPrice=2000"))).isEmpty();

    // 색인 신선도(내부): 문서 수, 마지막 반영(ANY)·가격 사건 시각
    JsonNode status =
        mapper.readTree(
            http.get()
                .uri("/internal/index-status")
                .header("X-Source-System", "backoffice-bff")
                .retrieve()
                .body(String.class));
    assertThat(status.path("documents").asInt()).isGreaterThanOrEqualTo(3);
    List<String> types = new ArrayList<>();
    status.path("sources").forEach(x -> types.add(x.path("eventType").asString()));
    assertThat(types).contains("ANY", "ProductContentPublished", "PriceVersionEffective");
    assertThat(status.path("sources").path(0).path("lagSeconds").asLong())
        .isGreaterThanOrEqualTo(0);
    assertThat(
            http.get()
                .uri("/search/products?minPrice=5000&maxPrice=1000")
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value())
        .isEqualTo(400);
    assertThat(search("?sort=POPULAR").path("appliedSort").asString()).isEqualTo("RELEVANCE");

    // 쪽 나누기
    JsonNode first = search("?size=2");
    assertThat(first.path("items")).hasSize(2);
    String cursor = first.path("page").path("nextCursor").asString();
    JsonNode second = search("?size=2&cursor=" + cursor);
    assertThat(ids(second)).containsExactly("PRD-1");
    assertThat(second.path("page").path("nextCursor").isNull()).isTrue();
    assertThat(
            http.get()
                .uri("/search/products?cursor=garbage!")
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value())
        .isEqualTo(400);
    // 1순위 ①(2026-11-19): ES 창(1만) 밖 커서 — 전에는 500, 이제 잘못된 커서 400
    String deep =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("o:20000".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThat(
            http.get()
                .uri("/search/products?cursor=" + deep)
                .retrieve()
                .toBodilessEntity()
                .getStatusCode()
                .value())
        .isEqualTo(400);

    // 자동완성
    JsonNode suggest =
        mapper.readTree(http.get().uri("/search/suggestions?q=두부").retrieve().body(String.class));
    assertThat(suggest.path("keywords").path(0).asString()).isEqualTo("[시험] 국산콩 두부 300g");

    // 판매중지 → 제외, 그보다 이른 시각의 상태 사건은 무시
    send(
        "ProductSaleStateChanged",
        "Product",
        "PRD-3",
        2,
        Map.of(
            "sku", "E-3",
            "productId", "PRD-3",
            "state", "DISCONTINUED",
            "previousState", "ON_SALE",
            "effectiveAt", "2026-10-30T03:00:00Z",
            "version", 2));
    send(
        "ProductSaleStateChanged",
        "Product",
        "PRD-3",
        1,
        Map.of(
            "sku", "E-3",
            "productId", "PRD-3",
            "state", "ON_SALE",
            "previousState", "TEMP_OUT",
            "effectiveAt", "2026-10-30T02:30:00Z",
            "version", 1));
    // 같은 상품 버전 재전달·옛 가격 버전은 바꾸지 않는다
    product("PRD-1", 1, "E-1", "t01-c01", "2026-10-30T01:00:00Z");
    price("E-1", 100, 0);
    // 토픽마다 마지막 사건(시험 토픽은 파티션 1개라 앞 사건이 모두 처리됐다는 표시): 가격 = 두부 v2, 상품 = 두부 콘텐츠 v2
    price("E-2", 2100, 2);
    card("PRD-2", "[시험] 국산콩 부침두부 300g", 2);
    contentPublished("PRD-2", 2, "2026-10-30T04:00:00Z");
    await(
        "?q=두부",
        r ->
            r.path("items").path(0).path("basePrice").asInt() == 2100
                && r.path("items").path(0).path("name").asString().contains("부침두부"));
    assertThat(ids(search(""))).containsExactly("PRD-2", "PRD-1");
    JsonNode soup = search("?q=미역").path("items").path(0);
    assertThat(soup.path("basePrice").asInt()).isEqualTo(4900);
    assertThat(ids(search("?categoryId=t02"))).as("재전달된 같은 버전의 카테고리 무시").containsExactly("PRD-1");
  }

  void productWith(
      String productId,
      int version,
      int rowVersion,
      String sku,
      String saleState,
      String at,
      List<Map<String, Object>> filterOptions) {
    send(
        "ProductVersionPublished",
        "Product",
        productId,
        version,
        Map.of(
            "productId", productId,
            "version", version,
            "rowVersion", rowVersion,
            "primaryCategoryId", "t09-c01",
            "categoryIds", List.of("t09-c01"),
            "saleState", saleState,
            "filterOptions", filterOptions,
            "skus", List.of(Map.of("sku", sku, "unitType", "E", "unitsPerSku", 1)),
            "effectiveAt", at));
  }

  void saleState(String productId, String sku, String state, int rowVersion, String at) {
    send(
        "ProductSaleStateChanged",
        "Product",
        productId,
        rowVersion,
        Map.of(
            "sku",
            sku,
            "productId",
            productId,
            "state",
            state,
            "effectiveAt",
            at,
            "version",
            rowVersion));
  }

  /**
   * 2026-11-04 검색 마무리: 승인 속성 필터(filters), 판매 상태를 시각이 아니라 상품 행 버전으로 비교, 콘텐츠 카드 실패는 재시도 뒤
   * search-index.DLT 로.
   */
  @Test
  @org.junit.jupiter.api.Order(2)
  void filtersRowVersionSaleStateAndContentDeadLetter() throws Exception {
    productWith(
        "PRD-F1",
        1,
        1,
        "E-F1",
        "ON_SALE",
        "2026-11-04T01:00:00Z",
        List.of(
            Map.of("filterGroupId", "capacity", "option", "500ml"),
            Map.of("filterGroupId", "pack", "option", "1개")));
    productWith(
        "PRD-F2",
        1,
        1,
        "E-F2",
        "ON_SALE",
        "2026-11-04T01:00:00Z",
        List.of(
            Map.of("filterGroupId", "capacity", "option", "1L"),
            Map.of("filterGroupId", "pack", "option", "1개")));
    card("PRD-F1", "[시험] 필터 생수 500ml", 1);
    card("PRD-F2", "[시험] 필터 생수 1L", 1);
    contentPublished("PRD-F1", 1, "2026-11-04T01:01:00Z");
    contentPublished("PRD-F2", 1, "2026-11-04T01:01:00Z");
    await("?categoryId=t09", r -> r.path("total").asInt() == 2);

    // 같은 그룹 안은 OR, 그룹끼리는 AND. 형식이 틀린 값은 무시하고 appliedFilters 로 알린다
    JsonNode one = search("?categoryId=t09&filters=capacity.500ml");
    assertThat(ids(one)).containsExactly("PRD-F1");
    assertThat(one.path("appliedFilters").toString()).isEqualTo("[\"capacity.500ml\"]");
    assertThat(ids(search("?categoryId=t09&filters=capacity.500ml&filters=capacity.1L")))
        .hasSize(2);
    assertThat(ids(search("?categoryId=t09&filters=capacity.1L&filters=pack.1개")))
        .containsExactly("PRD-F2");
    assertThat(ids(search("?categoryId=t09&filters=capacity.2L"))).isEmpty();
    assertThat(search("?categoryId=t09&filters=nodot").path("appliedFilters").size()).isZero();
    // 선택 가능한 필터(현재 조건의 건수)
    JsonNode attrs =
        mapper.readTree(
            http.get().uri("/search/filters?categoryId=t09").retrieve().body(String.class));
    JsonNode capacity = null;
    for (JsonNode a : attrs.path("attributes")) {
      if ("capacity".equals(a.path("key").asString())) {
        capacity = a;
      }
    }
    assertThat(capacity).isNotNull();
    assertThat(capacity.path("values").toString()).contains("\"500ml\"").contains("\"1L\"");
    assertThat(capacity.path("label").asString()).isEqualTo("용량");
    // 패널 옵션 순서(1L, 500ml)를 따른다
    assertThat(capacity.path("values").path(0).path("value").asString()).isEqualTo("1L");
    // 하위 카테고리(패널 없음)는 조상 t09 의 패널로, 카테고리 없음은 최상위 노드 패널로 이름을 찾는다. 패널에 없는 그룹(pack)은 id 그대로
    for (String query : List.of("?categoryId=t09-c01", "")) {
      JsonNode all =
          mapper.readTree(http.get().uri("/search/filters" + query).retrieve().body(String.class));
      java.util.Map<String, String> labels = new java.util.HashMap<>();
      all.path("attributes")
          .forEach(a -> labels.put(a.path("key").asString(), a.path("label").asString()));
      assertThat(labels).containsEntry("capacity", "용량").containsEntry("pack", "pack");
    }

    // 판매 상태: 행 버전이 큰 사건만. 시각이 더 이른 재적재(PVP rowVersion 3)가 일시품절(rv 2)을 풀고, 늦은 시각의 옛 사건(rv 2)은 무시
    saleState("PRD-F1", "E-F1", "TEMP_OUT", 2, "2026-11-04T05:00:00Z");
    await(
        "?categoryId=t09&filters=capacity.500ml",
        r -> "TEMPORARILY_SOLD_OUT".equals(r.path("items").path(0).path("saleState").asString()));
    productWith(
        "PRD-F1",
        2,
        3,
        "E-F1",
        "ON_SALE",
        "2026-11-04T04:00:00Z",
        List.of(Map.of("filterGroupId", "capacity", "option", "500ml")));
    await(
        "?categoryId=t09&filters=capacity.500ml",
        r -> "ON_SALE".equals(r.path("items").path(0).path("saleState").asString()));
    saleState("PRD-F1", "E-F1", "TEMP_OUT", 2, "2026-11-04T06:00:00Z");
    // 표시용 마지막 사건(같은 파티션 순서): F2 콘텐츠 v2 가 반영되면 앞 사건은 모두 처리됐다
    card("PRD-F2", "[시험] 필터 생수 1L 새 이름", 2);
    contentPublished("PRD-F2", 2, "2026-11-04T06:01:00Z");
    await("?categoryId=t09", r -> r.toString().contains("새 이름"));
    assertThat(
            search("?categoryId=t09&filters=capacity.500ml")
                .path("items")
                .path(0)
                .path("saleState")
                .asString())
        .as("옛 행 버전의 늦은 사건은 무시")
        .isEqualTo("ON_SALE");

    // 콘텐츠 카드가 없으면(게시본 없음) 재시도 뒤 DLT — 조용히 건너뛰지 않는다
    productWith("PRD-F3", 1, 1, "E-F3", "ON_SALE", "2026-11-04T01:00:00Z", List.of());
    contentPublished("PRD-F3", 1, "2026-11-04T01:02:00Z"); // card 스텁 없음 → 404
    java.util.Properties props = new java.util.Properties();
    props.put("bootstrap.servers", KAFKA.getBootstrapServers());
    props.put("group.id", "it-dlt-" + java.util.UUID.randomUUID());
    props.put("auto.offset.reset", "earliest");
    props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
    String dead = null;
    try (var consumer =
        new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(props)) {
      consumer.subscribe(List.of("search-index.DLT"));
      Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
      while (dead == null && Instant.now().isBefore(deadline)) {
        for (var rec : consumer.poll(Duration.ofSeconds(1))) {
          if ("PRD-F3".equals(rec.key())) {
            dead = rec.value();
          }
        }
      }
    }
    assertThat(dead).as("search-index.DLT 레코드").isNotNull();
    JsonNode dl = mapper.readTree(dead).path("pmartDeadLetter");
    assertThat(dl.path("op").asString()).isEqualTo("content");
    assertThat(dl.path("topic").asString()).isEqualTo("commerce.product.v1");
    assertThat(dl.path("offset").isMissingNode()).isFalse();
  }

  /**
   * 띄어쓰기만 다른 검색어(2026-11-04 고정). 공백 정규화 필드·동의어는 없다 — nori 가 붙임말을 같은 형태소로 나누는 경우만 양쪽으로 찾는다. 알려진 한계:
   * 붙여 쓴 검색어("생크림")는 띄어 쓴 상품명("생 크림")을 못 찾는다(nori 가 "생" 한 글자를 버리고, n-gram 은 "생크" 가 없다). 공백 제거 필드를
   * 넣으면 이 단정을 바꾼다.
   */
  @Test
  @org.junit.jupiter.api.Order(3)
  void spacingVariants() throws Exception {
    String[][] products = {
      {"PRD-S1", "E-S1", "[시험] 블루투스 무선 이어폰"},
      {"PRD-S2", "E-S2", "[시험] 무선이어폰 케이스"},
      {"PRD-S3", "E-S3", "[시험] 동물성 생 크림 500ml"},
      {"PRD-S4", "E-S4", "[시험] 휘핑 생크림 250ml"},
      // 구문 순위(낱말 순서), 초성 앞부분 순위
      {"PRD-P1", "E-P1", "[시험] 콜라 제로 500ml"},
      {"PRD-P2", "E-P2", "[시험] 제로 슈거 콜라맛 젤리"},
      {"PRD-C1", "E-C1", "[시험] 국산 두부 300g"},
      {"PRD-C2", "E-C2", "[시험] 고단백 과자"}
    };
    for (String[] p : products) {
      productWith(p[0], 1, 1, p[1], "ON_SALE", "2026-11-04T02:00:00Z", List.of());
      card(p[0], p[2], 1);
      contentPublished(p[0], 1, "2026-11-04T02:01:00Z");
    }
    await("?q=휘핑", r -> ids(r).contains("PRD-S4"));
    await("?q=동물성", r -> ids(r).contains("PRD-S3"));
    await("?q=케이스", r -> ids(r).contains("PRD-S2"));
    await("?q=블루투스", r -> ids(r).contains("PRD-S1"));

    // 띄어 쓴 검색어 ↔ 붙여 쓴 상품명, 붙여 쓴 검색어 ↔ 띄어 쓴 상품명: nori 형태소(무선·이어폰)가 같아 양쪽 다 찾는다
    assertThat(ids(search("?q=무선 이어폰"))).contains("PRD-S1", "PRD-S2");
    assertThat(ids(search("?q=무선이어폰"))).contains("PRD-S1", "PRD-S2");
    // 띄어 쓴 검색어 "생 크림"(nori: 크림)은 둘 다 찾는다
    assertThat(ids(search("?q=생 크림"))).contains("PRD-S3", "PRD-S4");
    // 붙여 쓴 "생크림"(nori: 생크림 한 낱말)도 띄어 쓴 "생 크림"을 찾는다 — 공백을 뺀 조각 필드(products-v4 name.compact)
    assertThat(ids(search("?q=생크림"))).contains("PRD-S3", "PRD-S4");
    assertThat(ids(search("?q=휘핑생크림"))).contains("PRD-S4").doesNotContain("PRD-S3");
    assertThat(ids(search("?q=블루투스무선이어폰"))).contains("PRD-S1").doesNotContain("PRD-S2");

    // 초성 검색(2자 이상, 상품명 초성 nameChosung — 공백을 뺀 조각): "ㅅㅋㄹ" → 생크림·생 크림, "ㅎㅍ" → 휘핑만
    assertThat(ids(search("?q=ㅅㅋㄹ"))).contains("PRD-S3", "PRD-S4");
    assertThat(ids(search("?q=ㅎㅍ"))).contains("PRD-S4").doesNotContain("PRD-S3");
    assertThat(ids(search("?q=ㅁㅅ ㅇㅇㅍ"))).contains("PRD-S1", "PRD-S2");
    assertThat(ids(search("?q=ㅂㄹㅌㅅ"))).contains("PRD-S1").doesNotContain("PRD-S2");
    // 순위(2026-11-05): 낱말이 검색어 순서로 붙은 상품이 위(구문), 초성은 낱말 앞부분이 맞는 상품이 위
    await("?q=콜라 제로", r -> ids(r).containsAll(List.of("PRD-P1", "PRD-P2")));
    List<String> phrase = ids(search("?q=콜라 제로"));
    assertThat(phrase.indexOf("PRD-P1")).isLessThan(phrase.indexOf("PRD-P2"));
    await("?q=ㄷㅂ", r -> ids(r).containsAll(List.of("PRD-C1", "PRD-C2")));
    List<String> chosung = ids(search("?q=ㄷㅂ"));
    assertThat(chosung.indexOf("PRD-C1")).isLessThan(chosung.indexOf("PRD-C2"));
    // 자동완성도 같은 조건
    String suggest = http.get().uri("/search/suggestions?q=ㅅㅋㄹ").retrieve().body(String.class);
    assertThat(suggest).contains("생 크림").contains("생크림");
  }
}
