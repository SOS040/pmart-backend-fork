package com.pmart.customerassistant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.pmart.common.test.TestJwt;
import com.pmart.common.test.TestJwtConfiguration;
import com.pmart.customerassistant.infrastructure.ingest.KnowledgeIngestor;
import com.pmart.customerassistant.infrastructure.ingest.KnowledgeManifest;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * provider=ollama 연결(ADR-0051): Ollama 는 WireMock. 색인·질문 임베딩이 {@code /api/embed} 로 가고(질문에만 지시문),
 * 답변이 {@code /api/chat}(생각 끔·JSON 형식·설정한 모델)으로 가며, 응답이 SEMANTIC 검색 + 생성 경로로 기록되는지 본다. 모델 품질은 여기서 보지
 * 않는다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.main.banner-mode=off",
      "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
      "pmart.assistant.model.provider=ollama",
      "pmart.assistant.model.embedding-dim=4",
      "pmart.assistant.model.ollama.base-url=${ollama.url}",
      "pmart.assistant.model.ollama.embedding-model=test-embed",
      "pmart.assistant.model.ollama.embedding-version=abc123",
      "pmart.assistant.model.ollama.query-instruction=Q-INSTRUCT: ",
      "pmart.assistant.model.ollama.chat-model=test-chat:4b",
      "pmart.assistant.model.ollama.embedding-num-gpu=0"
    })
@Import(TestJwtConfiguration.class)
@EnableWireMock({@ConfigureWireMock(name = "ollama", baseUrlProperties = "ollama.url")})
class OllamaProviderIT extends PgvectorContainerSupport {

  @InjectWireMock("ollama")
  WireMockServer ollama;

  @LocalServerPort int port;
  @Autowired KnowledgeIngestor ingestor;
  @Autowired JdbcTemplate jdbc;

  final JsonMapper json = JsonMapper.builder().build();

  /** 모델 오류는 재시도하지 않고 바로 503 — 같은 입력에서 되풀이되는 500 을 다시 불러 처리 시간을 다 쓰지 않는다(2026-11-09 N08). */
  @Test
  void chatModelErrorIsNotRetriedAndAnswersDependencyUnavailable() throws Exception {
    ollama.resetAll(); // 같은 클래스 다른 시험의 요청 기록·스텁을 지운다
    jdbc.update("DELETE FROM customer_answer");
    jdbc.update("DELETE FROM index_snapshot");
    ollama.stubFor(
        post(urlPathEqualTo("/api/embed"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"model\":\"test-embed\",\"embeddings\":[[0.5,0.5,0.5,0.5]]}")));
    ollama.stubFor(
        post(urlPathEqualTo("/api/chat"))
            .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"boom\"}")));
    Path repo = Files.createTempDirectory("assistant-ollama-err");
    for (String f : AssistantITBase.FIXTURE_FILES) {
      Path target = repo.resolve(f);
      Files.createDirectories(target.getParent());
      try (InputStream in = new ClassPathResource("fixture/" + f).getInputStream()) {
        Files.copy(in, target);
      }
    }
    try (InputStream in = new ClassPathResource("fixture/manifest.yml").getInputStream()) {
      ingestor.ingest(repo, KnowledgeManifest.load(in));
    }
    long t0 = System.nanoTime();
    // JDK 클라이언트 — 기본(Apache) 클라이언트는 503 을 스스로 다시 보내 모델 호출 수가 2 로 보인다
    int status =
        RestClient.builder()
            .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory())
            .build()
            .post()
            .uri("http://localhost:" + port + "/api/v1/customer-assistant/questions")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.customer("CU-e"))
            .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
            .header("X-Source-System", "customer-app")
            .contentType(MediaType.APPLICATION_JSON)
            .body(json.writeValueAsString(Map.of("question", "봉투값은 얼마인가요?")))
            .exchange((req, res) -> res.getStatusCode().value());
    assertThat(status).isEqualTo(503);
    assertThat((System.nanoTime() - t0) / 1_000_000_000.0).isLessThan(10.0);
    ollama.verify(1, postRequestedFor(urlPathEqualTo("/api/chat")));
  }

  @Test
  void ingestsAndAnswersThroughOllamaWithQueryInstructionThinkingOffAndJsonFormat()
      throws Exception {
    jdbc.update("DELETE FROM customer_answer");
    jdbc.update("DELETE FROM index_snapshot");
    ollama.stubFor(
        post(urlPathEqualTo("/api/embed"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"model\":\"test-embed\",\"embeddings\":[[0.5,0.5,0.5,0.5]]}")));
    String content =
        json.writeValueAsString(
            Map.of("answer", "봉투값은 주문당 100원 1회다.", "usedIds", List.of("D1"), "grounded", true));
    ollama.stubFor(
        post(urlPathEqualTo("/api/chat"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        json.writeValueAsString(
                            Map.of(
                                "model",
                                "test-chat:4b",
                                "message",
                                Map.of("role", "assistant", "content", content),
                                "done",
                                true)))));

    Path repo = Files.createTempDirectory("assistant-ollama");
    for (String f : AssistantITBase.FIXTURE_FILES) {
      Path target = repo.resolve(f);
      Files.createDirectories(target.getParent());
      try (InputStream in = new ClassPathResource("fixture/" + f).getInputStream()) {
        Files.copy(in, target);
      }
    }
    KnowledgeManifest manifest;
    try (InputStream in = new ClassPathResource("fixture/manifest.yml").getInputStream()) {
      manifest = KnowledgeManifest.load(in);
    }
    var result = ingestor.ingest(repo, manifest);
    assertThat(result.chunkCount()).isPositive();
    // 스냅숏이 Ollama 모델로 기록된다(버전·차원 포함) — 모델이 바뀌면 재색인 필요가 드러난다
    assertThat(
            jdbc.queryForObject(
                "SELECT embedding_model || '@' || embedding_model_version || '/' || embedding_dim"
                    + " FROM index_snapshot WHERE state = 'ACTIVE'",
                String.class))
        .isEqualTo("ollama:test-embed@abc123/4");

    String question = "봉투값은 얼마인가요?";
    JsonNode body =
        RestClient.create()
            .post()
            .uri("http://localhost:" + port + "/api/v1/customer-assistant/questions")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestJwt.customer("CU-o"))
            .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
            .header("X-Source-System", "customer-app")
            .contentType(MediaType.APPLICATION_JSON)
            .body(json.writeValueAsString(Map.of("question", question)))
            .retrieve()
            .body(JsonNode.class);

    // 인용 검사를 통과하면 생성, 못 하면 발췌로(고객 응답에는 모델 이름을 싣지 않는다 — DB 기록만)
    assertThat(body.path("answerMode").asString()).isIn("GENERATIVE", "BASELINE_EXTRACTIVE");
    assertThat(
            jdbc.queryForObject(
                "SELECT retrieval_mode || ' ' || chat_model || ' ' || embedding_model FROM customer_answer"
                    + " WHERE answer_id = ?",
                String.class,
                body.path("answerId").asString()))
        .contains("SEMANTIC")
        .contains("ollama:test-chat:4b")
        .contains("ollama:test-embed");

    // 질문은 지시문을 붙여, 문서는 그대로 임베딩
    ollama.verify(
        postRequestedFor(urlPathEqualTo("/api/embed"))
            .withRequestBody(containing("Q-INSTRUCT: " + question)));
    ollama.verify(
        postRequestedFor(urlPathEqualTo("/api/embed"))
            .withRequestBody(containing("봉투값은 주문당 100원 1회다")));
    // 임베딩은 CPU(num_gpu 0) — GPU 는 대화 모델에
    ollama.verify(
        postRequestedFor(urlPathEqualTo("/api/embed"))
            .withRequestBody(containing("\"num_gpu\":0"))
            .withRequestBody(containing("\"keep_alive\":\"24h\"")));
    // 생성: 설정한 모델, 생각 끔, JSON 형식
    ollama.verify(
        postRequestedFor(urlPathEqualTo("/api/chat"))
            .withRequestBody(
                equalToJson(
                    "{\"model\":\"test-chat:4b\",\"format\":\"json\",\"think\":false}", true, true))
            .withRequestBody(containing("\"num_ctx\":8192")));
  }
}
