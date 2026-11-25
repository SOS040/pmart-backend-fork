package com.pmart.search.infrastructure;

import com.pmart.search.application.SemanticProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG 묶음 ollama 호출(ADR-0088) — 임베딩 {@code POST /api/embed}·꼬리표 생성 {@code POST /api/generate}(JSON,
 * 생각 끔). 공용 RestClient.Builder 를 쓰지 않는다(사용자 토큰이 밖으로 나가지 않게). 상품 글·검색어는 로그에 남기지 않는다.
 */
@Component
public class OllamaClient {

  private final SemanticProperties props;
  private final ObjectMapper mapper;

  public OllamaClient(SemanticProperties props, ObjectMapper mapper) {
    this.props = props;
    this.mapper = mapper;
  }

  private RestClient client(Duration timeout) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1))
                .build());
    factory.setReadTimeout(timeout);
    return RestClient.builder()
        .requestFactory(factory)
        .baseUrl(props.ollamaBaseUrl().replaceAll("/+$", ""))
        .build();
  }

  /** 글 여러 개 → 벡터. 실패는 예외(호출자가 키워드만으로·다음 주기로). */
  public List<float[]> embed(List<String> inputs, Duration timeout) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", props.embeddingModel());
    body.put("input", inputs);
    if (props.keepAlive() != null) {
      body.put("keep_alive", props.keepAlive());
    }
    String raw =
        client(timeout)
            .post()
            .uri("/api/embed")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(body))
            .retrieve()
            .body(String.class);
    JsonNode e = mapper.readTree(raw == null ? "{}" : raw).path("embeddings");
    if (!e.isArray() || e.size() != inputs.size()) {
      throw new IllegalStateException("ollama 임베딩 응답 개수가 요청과 다름");
    }
    List<float[]> out = new ArrayList<>();
    for (JsonNode v : e) {
      float[] f = new float[v.size()];
      for (int i = 0; i < f.length; i++) {
        f[i] = (float) v.get(i).asDouble();
      }
      out.add(f);
    }
    return out;
  }

  /** 프롬프트 → 모델 응답 글(JSON 형식 요청, 온도 0, 생각 끔). 실패는 예외. */
  public @Nullable String generateJson(String prompt, Duration timeout) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", props.tagModel());
    body.put("prompt", prompt);
    body.put("stream", false);
    body.put("format", "json");
    body.put("think", false);
    body.put("options", Map.of("temperature", 0));
    if (props.keepAlive() != null) {
      body.put("keep_alive", props.keepAlive());
    }
    String raw =
        client(timeout)
            .post()
            .uri("/api/generate")
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapper.writeValueAsString(body))
            .retrieve()
            .body(String.class);
    JsonNode r = mapper.readTree(raw == null ? "{}" : raw).path("response");
    return r.isString() ? r.asString() : null;
  }
}
