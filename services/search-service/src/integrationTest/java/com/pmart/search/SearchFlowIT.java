package com.pmart.search;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.pmart.common.test.EventEnvelopeFixtures;
import com.pmart.common.test.PostgresKafkaContainerSupport;
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
 * 상품·콘텐츠·판매 상태·가격 사건(Kafka) → <b>Logstash</b>(search-index.conf) → Elasticsearch(nori) → 검색 API
 * (ADR-0031). 실제 Kafka·Elasticsearch·Logstash 를 한 네트워크에 띄우고, 공개 카드·카테고리 트리·가용 조회는 WireMock(호스트). 사건
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
        // 카드(Logstash 가 부름)·카테고리 트리·가용 조회(서비스가 부름)를 한 WireMock 이 맡는다(경로가 서로 다르다)
        baseUrlProperties = {"pmart.search.product-base-url", "pmart.search.inventory-base-url"}))
@Testcontainers
class SearchFlowIT {

  static final Network NET = Network.newNetwork();

  @Container @ServiceConnection
  static final KafkaContainer KAFKA =
      new KafkaContainer(PostgresKafkaContainerSupport.KAFKA_IMAGE)
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

  void startLogstash() {
    if (logstash != null) {
      return;
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

  @InjectWireMock("content")
  WireMockServer content;

  RestClient http;

  @BeforeEach
  void setUp() {
    http =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1")
            .defaultStatusHandler(HttpStatusCode::isError, (q, r) -> {})
            .build();
    content.resetAll();
    // 색인은 Logstash 가 쓰므로 먼저 만든다(LAN 은 es-init — 같은 정의 파일)
    search("");
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
                                    Map.of("categoryId", "t02-c02", "path", "간편식 > 만두")))))));
    // MFC-1: E-1 취급, E-3 취급 안 함, E-2 는 응답에 없음(불명)
    content.stubFor(
        WireMock.get(urlPathEqualTo("/api/v1/internal/availability"))
            .withQueryParam("mfcId", equalTo("MFC-1"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        mapper.writeValueAsString(
                            Map.of(
                                "items",
                                List.of(
                                    Map.of("sku", "E-1", "available", 3, "handlingEnabled", true),
                                    Map.of(
                                        "sku",
                                        "E-3",
                                        "available",
                                        0,
                                        "handlingEnabled",
                                        false)))))));
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
        type.equals("PriceVersionEffective") ? "commerce.pricing.v1" : "commerce.product.v1";
    String json =
        EventEnvelopeFixtures.envelope(type)
            .from(topic.startsWith("commerce.pricing") ? "pricing-service" : "product-service")
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
  void eventsBecomeSearchableAndStayConsistent() throws Exception {
    // 가격이 상품보다 먼저 온 경우(E-2)
    price("E-2", 1900, 1);
    product("PRD-1", 1, "E-1", "t02-c01", "2026-10-30T01:00:00Z");
    product("PRD-2", 1, "E-2", "t01-c02", "2026-10-30T01:00:00Z");
    product("PRD-3", 1, "E-3", "t02-c02", "2026-10-30T01:00:00Z");
    product("PRD-4", 1, "E-4", "t02-c02", "2026-10-30T01:00:00Z"); // 콘텐츠 없음 → 검색에 안 나온다
    contentPublished("PRD-1", 1, "2026-10-30T02:00:00Z");
    contentPublished("PRD-2", 1, "2026-10-30T02:01:00Z");
    contentPublished("PRD-3", 1, "2026-10-30T02:02:00Z");
    price("E-1", 4900, 1);
    price("E-3", 7900, 1);

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

    // 센터 취급 여부: 취급 안 함이 확인된 상품(PRD-3)은 기본으로 빠지고, 불명(PRD-2)은 남는다
    JsonNode atMfc = search("?mfcId=MFC-1");
    assertThat(ids(atMfc)).containsExactly("PRD-2", "PRD-1");
    assertThat(atMfc.path("items").path(1).path("carried").asBoolean()).isTrue();
    assertThat(atMfc.path("items").path(0).path("carried").isNull()).isTrue();
    JsonNode withNotCarried = search("?mfcId=MFC-1&includeNotCarried=true");
    assertThat(ids(withNotCarried)).containsExactly("PRD-3", "PRD-2", "PRD-1");
    assertThat(withNotCarried.path("items").path(0).path("carried").asBoolean()).isFalse();

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
}
